package cz.bliksoft.javautils.ble.remote;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.BleSidecarException;
import cz.bliksoft.javautils.ble.NativeBinaryLoader;
import cz.bliksoft.javautils.ble.transport.ProcessLinePipe;

/**
 * Spawns a local {@code ble-bridge} sidecar exactly like {@code BleAdapter}
 * does, then pipes its NDJSON lines to/from a server over a WebSocket
 * connection, so a {@link RemoteAdapterRegistry} on that server can drive this
 * machine's BLE hardware. Reconnects with backoff on any connection drop while
 * keeping the local sidecar alive across reconnects.
 * <p>
 * Usable two ways: {@link #run()} blocks the calling thread until it's
 * interrupted (what {@code cz.bliksoft.javautils.ble.Main}'s {@code --remote}
 * CLI flag uses); {@link #start()}/{@link #close()} instead run it on an
 * internal daemon thread, for embedding in a longer-lived application (e.g. a
 * desktop client that also uses {@code BleAdapter} directly for local hardware)
 * that doesn't want to manage that thread itself.
 */
public final class RemoteAdapterClient implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(RemoteAdapterClient.class.getName());
	private static final long INITIAL_BACKOFF_MS = 1000;
	private static final long MAX_BACKOFF_MS = 30000;
	private static final long HEARTBEAT_INTERVAL_MS = 25000;
	private static final long CONNECT_TIMEOUT_MS = 15000;
	private static final long STOP_JOIN_TIMEOUT_MS = 5000;

	private final URI serverUri;
	private final String name;
	private final String token;
	private volatile Consumer<BleException> onFatalError;
	private Thread thread;

	public RemoteAdapterClient(URI serverUri, String name, String token) {
		this.serverUri = serverUri;
		this.name = name;
		this.token = token;
		this.onFatalError = e -> LOG.log(Level.SEVERE, "remote adapter client for '" + name + "' stopped", e);
	}

	/**
	 * Registers a callback for when {@link #run()} exits early - the local sidecar
	 * died, or the server rejected the connection outright (bad/missing auth token,
	 * see {@link BleRemoteAuthException}) - while running under {@link #start()}
	 * (where nothing else observes that failure). Replaces any previously set
	 * callback; defaults to logging at {@code SEVERE}.
	 */
	public void setOnFatalError(Consumer<BleException> handler) {
		this.onFatalError = handler != null ? handler
				: e -> LOG.log(Level.SEVERE, "remote adapter client for '" + name + "' stopped", e);
	}

	/**
	 * Starts {@link #run()} on an internal daemon thread and returns immediately.
	 * For embedding in an application that manages its own lifecycle rather than
	 * dedicating a thread to a blocking {@link #run()} call - see
	 * {@link #setOnFatalError} for how a local-sidecar death is reported in this
	 * mode. Call {@link #close()} to stop. Not reentrant: throws if already
	 * started.
	 */
	public synchronized void start() {
		if (thread != null) {
			throw new IllegalStateException("RemoteAdapterClient for '" + name + "' is already started");
		}
		thread = new Thread(() -> {
			try {
				run();
			} catch (BleException e) {
				onFatalError.accept(e);
			}
		}, "ble-remote-client-" + name);
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Stops a client started via {@link #start()}: interrupts its thread and waits
	 * briefly for it to finish shutting down its local sidecar. Does nothing if not
	 * started (or already stopped). Safe to call from any thread, including from
	 * inside {@link #setOnFatalError}'s callback.
	 */
	@Override
	public synchronized void close() {
		Thread t = thread;
		if (t == null) {
			return;
		}
		t.interrupt();
		if (t != Thread.currentThread()) {
			// Guard against a self-join if this is called from inside the
			// setOnFatalError callback, which runs on `t` itself.
			try {
				t.join(STOP_JOIN_TIMEOUT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		thread = null;
	}

	/**
	 * Spawns the local sidecar and runs until the current thread is interrupted. If
	 * the local sidecar itself dies (not just the network connection), this throws
	 * instead of trying to silently respawn it - same crash-isolation contract as
	 * {@code BleAdapter}: whatever supervises this CLI process is expected to
	 * restart it, rather than this method papering over a dead sidecar. Likewise
	 * throws immediately, without retrying, if the server rejects the connection's
	 * auth token ({@link BleRemoteAuthException}) - the name/token are fixed at
	 * construction, so retrying the same connection could never succeed.
	 */
	public void run() throws BleException {
		AtomicReference<ProcessLinePipe> sidecarRef = new AtomicReference<>(
				new ProcessLinePipe(NativeBinaryLoader.extract()));
		try {
			long backoff = INITIAL_BACKOFF_MS;
			while (!Thread.currentThread().isInterrupted()) {
				try {
					runOneConnection(sidecarRef);
					backoff = INITIAL_BACKOFF_MS;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (ExecutionException e) {
					if (e.getCause() instanceof SidecarDiedException) {
						throw new BleSidecarException(
								"local ble-bridge sidecar died: " + ((SidecarDiedException) e.getCause()).reason
										+ " (exit code " + sidecarRef.get().getExitCode() + ")");
					}
					if (e.getCause() instanceof WebSocketHandshakeException) {
						int status = ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode();
						if (status == 401) {
							throw new BleRemoteAuthException("remote connection to " + serverUri
									+ " rejected: access denied (HTTP 401) - check app.ble.remote-token; not "
									+ "retrying, this cannot succeed without restarting with a corrected token");
						}
						LOG.warning(
								"remote connection to " + serverUri + " rejected (HTTP " + status + "), retrying in "
										+ backoff + "ms");
					} else {
						LOG.log(Level.WARNING,
								"remote connection to " + serverUri + " failed, retrying in " + backoff + "ms", e);
					}
				} catch (Exception e) {
					LOG.log(Level.WARNING,
							"remote connection to " + serverUri + " failed, retrying in " + backoff + "ms", e);
				}
				if (Thread.currentThread().isInterrupted()) {
					break;
				}
				try {
					Thread.sleep(backoff);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
				backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
			}
		} finally {
			sidecarRef.get().close();
		}
	}

	private void runOneConnection(AtomicReference<ProcessLinePipe> sidecarRef) throws Exception {
		CompletableFuture<Void> connectionDone = new CompletableFuture<>();
		HttpClient client = HttpClient.newHttpClient();
		WebSocket.Builder builder = client.newWebSocketBuilder().header("X-BLE-Remote-Name", name);
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}

		StringBuilder assembling = new StringBuilder();
		AtomicReference<WebSocket> socketRef = new AtomicReference<>();

		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				assembling.append(data);
				webSocket.request(1);
				if (last) {
					String line = assembling.toString();
					assembling.setLength(0);
					handleIncomingLine(sidecarRef, socketRef.get(), line, connectionDone);
				}
				return null;
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				connectionDone.completeExceptionally(error);
			}

			@Override
			public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				connectionDone.complete(null);
				return null;
			}
		};

		WebSocket webSocket = builder.buildAsync(serverUri, listener).get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		socketRef.set(webSocket);

		ProcessLinePipe sidecar = sidecarRef.get();
		sidecar.onLine(line -> webSocket.sendText(line, true));
		sidecar.onClose(reason -> connectionDone.completeExceptionally(new SidecarDiedException(reason)));

		ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "ble-remote-heartbeat");
			t.setDaemon(true);
			return t;
		});
		heartbeat.scheduleAtFixedRate(() -> webSocket.sendPing(ByteBuffer.allocate(0)), HEARTBEAT_INTERVAL_MS,
				HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
		try {
			connectionDone.get();
		} finally {
			heartbeat.shutdownNow();
			webSocket.abort();
		}
	}

	private void handleIncomingLine(AtomicReference<ProcessLinePipe> sidecarRef, WebSocket webSocket, String line,
			CompletableFuture<Void> connectionDone) {
		if (line.isEmpty()) {
			return;
		}
		if (SessionControl.SESSION_RESET.equals(SessionControl.typeOf(line))) {
			try {
				ProcessLinePipe old = sidecarRef.get();
				// This close is intentional (a server-requested session reset, not a
				// crash) - clear the close handler first so it doesn't masquerade as a
				// SidecarDiedException and kill the connection we're trying to keep.
				old.onClose(reason -> {
				});
				old.close();
				ProcessLinePipe fresh = new ProcessLinePipe(NativeBinaryLoader.extract());
				fresh.onLine(l -> webSocket.sendText(l, true));
				fresh.onClose(reason -> connectionDone.completeExceptionally(new SidecarDiedException(reason)));
				sidecarRef.set(fresh);
				webSocket.sendText(SessionControl.line(SessionControl.SESSION_READY), true);
			} catch (BleSidecarException e) {
				connectionDone.completeExceptionally(e);
			}
			return;
		}
		try {
			sidecarRef.get().send(line);
		} catch (IOException e) {
			connectionDone.completeExceptionally(e);
		}
	}

	private static final class SidecarDiedException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final String reason;

		SidecarDiedException(String reason) {
			super(reason);
			this.reason = reason;
		}
	}
}
