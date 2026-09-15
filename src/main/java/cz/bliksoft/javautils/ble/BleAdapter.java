package cz.bliksoft.javautils.ble;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cz.bliksoft.javautils.ble.transport.BleLinePipe;
import cz.bliksoft.javautils.ble.transport.ProcessLinePipe;

/**
 * Entry point for this library. Owns one {@link BleLinePipe} - normally a
 * locally-spawned {@code ble-bridge} sidecar process (a bundled Rust/btleplug
 * binary, see {@link NativeBinaryLoader}), reached via {@link ProcessLinePipe}
 * - and speaks newline-delimited JSON over it.
 * <p>
 * The sidecar exists specifically so a native/driver-level BLE fault can't take
 * this JVM down with it: a crash surfaces as {@link BleSidecarException} /
 * {@link DisconnectListener#onDisconnected} with reason
 * {@code "sidecar_crashed"}, never as a JVM crash. Create one
 * {@code BleAdapter} per BLE session; call {@link #close()} when done with it.
 * <p>
 * <b>Each {@code BleAdapter} has its own independent scan cache.</b> The
 * sidecar only knows a peripheral is connectable once it's seen it via
 * {@link #scan}; that knowledge lives in this specific adapter's own sidecar
 * process, not anywhere shared or global. A peripheral discovered by scanning
 * on one {@code BleAdapter} instance generally cannot be connected via a
 * <em>different</em> {@code BleAdapter} that never scanned for it - doing so
 * typically fails with "unknown peripheral address ... scan for it first" even
 * though the address is valid and was just seen moments ago on the other
 * instance. In practice this means: scan and connect using the <em>same</em>
 * {@code BleAdapter}, and don't construct a fresh one per connection attempt.
 */
public class BleAdapter implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(BleAdapter.class.getName());
	private static final long DEFAULT_TIMEOUT_MS = 15000;

	private final ObjectMapper mapper = new ObjectMapper();
	private final BleLinePipe pipe;
	private final AtomicLong idGenerator = new AtomicLong();
	private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private final Map<String, BlePeripheral> peripherals = new ConcurrentHashMap<>();
	private volatile BleScanListener scanListener;
	private volatile ScanFilter scanFilter;
	private volatile CompletableFuture<JsonNode> scanFuture = null;
	private volatile boolean alive = true;
	private volatile boolean closing = false;

	public BleAdapter() throws BleSidecarException {
		this(new ProcessLinePipe(NativeBinaryLoader.extract()));
	}

	/**
	 * Wraps an already-established {@link BleLinePipe} - e.g. one backed by a
	 * remote connection rather than a local sidecar process. Used by
	 * {@code RemoteAdapterRegistry} to expose a remotely-driven adapter through the
	 * exact same API as a local one.
	 */
	public BleAdapter(BleLinePipe pipe) {
		this.pipe = pipe;
		pipe.onLine(this::handleRawLine);
		pipe.onClose(this::handlePipeClosed);
	}

	/**
	 * Scans for nearby peripherals for {@code timeoutMs} milliseconds, reporting
	 * each one found to {@code listener}. Blocks until the sidecar confirms
	 * scanning has stopped.
	 */
	public void scan(ScanFilter filter, long timeoutMs, BleScanListener listener) throws BleException {
		this.scanListener = listener;
		this.scanFilter = filter;
		ObjectNode cmd = mapper.createObjectNode();
		cmd.put("cmd", "scan");
		if (filter != null && filter.getServiceUuid() != null) {
			cmd.put("filter_service_uuid", filter.getServiceUuid());
		}
		cmd.put("timeout_ms", timeoutMs);
		CompletableFuture<JsonNode> future = sendRequestAsync(cmd, timeoutMs + 5000);
		// Store the scan future so stopScan() can cancel it early
		scanFuture = future;
		// Clear scanFuture when done (normal timeout or exception)
		future.whenComplete((r, e) -> scanFuture = null);
		// Wait for the future, but ignore the "scan stopped early" exception
		try {
			future.get(timeoutMs + 5000, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			// Check if this is a "scan stopped early" exception (which is expected)
			Throwable cause = e.getCause();
			if (!(cause instanceof BleException && cause.getMessage().contains("scan stopped early"))) {
				// Re-throw if it's a different exception
				if (cause instanceof BleException) {
					throw (BleException) cause;
				}
				throw new BleSidecarException("sidecar request failed", cause);
			}
			// If it's "scan stopped early", we treat it as success - scan completed
			// successfully
		} catch (TimeoutException e) {
			throw new BleTimeoutException("timed out waiting for response to 'scan'");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BleSidecarException("interrupted waiting for sidecar response", e);
		}
	}

	public void stopScan() throws BleException {
		ObjectNode cmd = mapper.createObjectNode();
		cmd.put("cmd", "stop_scan");
		// Send stop_scan but don't wait for response - we just need to stop the scan
		// in the sidecar and complete the scan future. The sidecar will eventually
		// respond, but we don't need to block on it.
		sendRequestAsync(cmd, DEFAULT_TIMEOUT_MS);
		// Clear the scan listener/filter to stop further device_found events
		this.scanListener = null;
		this.scanFilter = null;
		// Cancel the pending scan future so scan() returns early
		CompletableFuture<JsonNode> future = scanFuture;
		if (future != null) {
			// Remove the scan command from pending so handleResponse doesn't try to
			// complete it again after we've already completed it exceptionally
			Iterator<Map.Entry<String, CompletableFuture<JsonNode>>> it = pending.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<String, CompletableFuture<JsonNode>> entry = it.next();
				if (entry.getValue() == future) {
					it.remove();
					break;
				}
			}
			future.completeExceptionally(new BleException("scan stopped early by stopScan()"));
		}
	}

	/**
	 * Returns the (cached) handle for a peripheral address; does not connect. The
	 * address must have been (or must still be, for {@link BlePeripheral#connect()}
	 * to succeed) discovered via {@link #scan} on <em>this</em> {@code BleAdapter}
	 * instance - see the class doc.
	 */
	public BlePeripheral getPeripheral(String address) {
		String key = address.toUpperCase(java.util.Locale.ROOT);
		return peripherals.computeIfAbsent(key, k -> new BlePeripheral(this, k));
	}

	/**
	 * Queries the Bluetooth radio's current power state. Useful for distinguishing
	 * "nothing found" from "Bluetooth is off" after an empty {@link #scan}.
	 */
	public AdapterState getAdapterState() throws BleException {
		JsonNode resp = sendRequest("adapter_state", null, DEFAULT_TIMEOUT_MS);
		switch (resp.path("state").asText("unknown")) {
		case "powered_on":
			return AdapterState.POWERED_ON;
		case "powered_off":
			return AdapterState.POWERED_OFF;
		default:
			return AdapterState.UNKNOWN;
		}
	}

	public boolean isAlive() {
		return alive && pipe.isAlive();
	}

	@Override
	public void close() {
		closing = true;
		alive = false;
		pipe.close();
		failAllPending("adapter closed");
	}

	/**
	 * Marks this adapter as no longer usable and fails any pending requests,
	 * <b>without</b> closing the underlying {@link BleLinePipe} - unlike
	 * {@link #close()}. For {@code RemoteAdapterRegistry}'s own use only: when it
	 * replaces this adapter with a fresh one wrapping the exact <em>same</em>,
	 * still-open transport (a session reset, not a real disconnect), the pipe
	 * itself must survive - the new adapter is about to take over receiving from
	 * it. Any other caller should use {@link #close()} instead, including when
	 * genuinely done with a connection.
	 */
	public void invalidate() {
		closing = true;
		alive = false;
		failAllPending("adapter session replaced");
	}

	// --- internals used by BlePeripheral
	// -----------------------------------------------------

	/**
	 * Sends a request and returns a CompletableFuture for async handling. The
	 * caller is responsible for handling the future and any exceptions.
	 */
	CompletableFuture<JsonNode> sendRequestAsync(ObjectNode node, long timeoutMs) throws BleException {
		if (!isAlive()) {
			throw new BleSidecarException("ble-bridge sidecar is not running");
		}
		String id = String.valueOf(idGenerator.incrementAndGet());
		node.put("id", id);
		CompletableFuture<JsonNode> future = new CompletableFuture<>();
		pending.put(id, future);
		try {
			writeLine(node);
		} catch (IOException e) {
			pending.remove(id);
			throw new BleSidecarException("failed to write to sidecar stdin", e);
		}
		// Set a timeout on the future
		Thread timeoutThread = new Thread(() -> {
			try {
				Thread.sleep(timeoutMs);
				future.completeExceptionally(new BleTimeoutException(
						"timed out waiting for response to '" + node.get("cmd").asText() + "'"));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		timeoutThread.setDaemon(true);
		timeoutThread.start();
		return future;
	}

	private JsonNode sendRequest(ObjectNode node, long timeoutMs) throws BleException {
		CompletableFuture<JsonNode> future = sendRequestAsync(node, timeoutMs);
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new BleTimeoutException("timed out waiting for response to '" + node.get("cmd").asText() + "'");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof BleException) {
				throw (BleException) cause;
			}
			throw new BleSidecarException("sidecar request failed", cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BleSidecarException("interrupted waiting for sidecar response", e);
		}
	}

	public JsonNode sendRequest(String cmd, Map<String, Object> fields, long timeoutMs) throws BleException {
		ObjectNode node = mapper.createObjectNode();
		node.put("cmd", cmd);
		if (fields != null) {
			for (Map.Entry<String, Object> e : fields.entrySet()) {
				Object v = e.getValue();
				if (v instanceof String) {
					node.put(e.getKey(), (String) v);
				} else if (v instanceof Boolean) {
					node.put(e.getKey(), (Boolean) v);
				} else if (v instanceof Long) {
					node.put(e.getKey(), (Long) v);
				} else {
					node.putPOJO(e.getKey(), v);
				}
			}
		}
		return sendRequest(node, timeoutMs);
	}

	private void writeLine(ObjectNode node) throws IOException {
		pipe.send(mapper.writeValueAsString(node));
	}

	// --- pipe callbacks
	// -------------------------------------------------------------------

	private void handleRawLine(String line) {
		if (line.trim().isEmpty()) {
			return;
		}
		try {
			dispatch(mapper.readTree(line));
		} catch (IOException e) {
			LOG.log(Level.FINE, "unparseable line from ble-bridge: " + line, e);
		}
	}

	private void handlePipeClosed(String reason) {
		alive = false;
		if (closing) {
			// close() already owns tearing down pending requests/peripherals for an
			// intentional shutdown - reporting a disconnect here would be a lie.
			return;
		}
		failAllPending("ble line pipe closed unexpectedly: " + reason);
		for (BlePeripheral p : peripherals.values()) {
			p.fireDisconnected(reason);
		}
	}

	private void failAllPending(String message) {
		Iterator<Map.Entry<String, CompletableFuture<JsonNode>>> it = pending.entrySet().iterator();
		while (it.hasNext()) {
			it.next().getValue().completeExceptionally(new BleSidecarException(message));
			it.remove();
		}
	}

	private void dispatch(JsonNode node) {
		String type = node.path("type").asText("");
		switch (type) {
		case "response":
			handleResponse(node);
			break;
		case "device_found":
			BleScanListener l = scanListener;
			if (l != null) {
				String address = node.path("address").asText(null);
				String name = node.path("name").asText(null);
				Integer rssi = node.hasNonNull("rssi") ? node.path("rssi").asInt() : null;
				ScanFilter f = scanFilter;
				if (f == null || f.matches(address, name)) {
					l.onDeviceFound(address, name, rssi);
					if (f != null && f.isExactMatch(address, name)) {
						try {
							stopScan();
						} catch (BleException e) {
							LOG.log(Level.FINE, "stopScan() after exact ScanFilter match failed", e);
						}
					}
				}
			}
			break;
		case "notification":
			BlePeripheral np = peripherals.get(node.path("address").asText(""));
			if (np != null) {
				np.fireNotification(node.path("char_uuid").asText(""),
						HexCodec.decode(node.path("value_hex").asText("")));
			}
			break;
		case "disconnected":
			BlePeripheral dp = peripherals.get(node.path("address").asText(""));
			if (dp != null) {
				dp.fireDisconnected(node.path("reason").asText("unknown"));
			}
			break;
		case "connected":
			// informational only - the connect() call itself resolves the corresponding
			// request future.
			break;
		case "fatal":
		case "error":
			LOG.warning("ble-bridge reported " + type + ": " + node.path("message").asText(""));
			break;
		default:
			LOG.fine("unrecognized ble-bridge message type: " + type);
		}
	}

	private void handleResponse(JsonNode node) {
		String id = node.path("id").asText(null);
		if (id == null) {
			return;
		}
		CompletableFuture<JsonNode> future = pending.remove(id);
		if (future == null) {
			return;
		}
		if (node.path("ok").asBoolean(false)) {
			future.complete(node);
		} else {
			future.completeExceptionally(new BleConnectException(node.path("error").asText("request failed")));
		}
	}
}
