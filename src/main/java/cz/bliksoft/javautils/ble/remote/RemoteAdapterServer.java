package cz.bliksoft.javautils.ble.remote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hosts the dial-in side of a remote-BLE-adapter connection directly over a plain {@link ServerSocket} -
 * no HTTP server, no Spring, no third-party WebSocket library. Accepts a {@code RemoteAdapterClient}'s
 * WebSocket upgrade by hand (just enough RFC 6455 to interoperate with {@code java.net.http.WebSocket},
 * the one real client this needs to support - see {@link WsFrameCodec}), authenticates it the same way
 * {@code StorageManagerServer}'s {@code BleRemoteAuthInterceptor} does -
 * {@value #NAME_HEADER} header + {@code Authorization: Bearer <token>} - then wraps the connection as a
 * {@link ServerSocketLinePipe} and registers it with a {@link RemoteAdapterRegistry}, exactly like
 * {@code SpringSessionLinePipe}/{@code BleRemoteWebSocketHandler} do for a Spring-hosted server.
 *
 * <p>
 * Deliberately its own {@link ServerSocket}/port, not plugged into an existing HTTP server: verified
 * empirically that {@code com.sun.net.httpserver.HttpExchange} cannot be hijacked for raw bytes after a
 * {@code 101} response through its public API, so there's no clean way to share an existing embedded HTTP
 * server's port for this.
 */
public final class RemoteAdapterServer implements Closeable {

	private static final Logger LOG = Logger.getLogger(RemoteAdapterServer.class.getName());
	private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	static final String NAME_HEADER = "x-ble-remote-name";
	private static final String AUTH_HEADER = "authorization";
	private static final String UPGRADE_HEADER = "upgrade";
	private static final String WS_KEY_HEADER = "sec-websocket-key";

	private final int port;
	private final String expectedToken;
	private final RemoteAdapterRegistry registry;
	private final Set<ServerSocketLinePipe> activePipes = ConcurrentHashMap.newKeySet();

	private volatile ServerSocket serverSocket;
	private volatile Thread acceptThread;

	public RemoteAdapterServer(int port, String expectedToken, RemoteAdapterRegistry registry) {
		this.port = port;
		this.expectedToken = expectedToken;
		this.registry = registry;
	}

	/** Binds the listening socket and starts accepting connections on a daemon thread. */
	public synchronized void start() throws IOException {
		if (serverSocket != null) {
			throw new IllegalStateException("RemoteAdapterServer is already started");
		}
		serverSocket = new ServerSocket(port);
		acceptThread = new Thread(this::acceptLoop, "ble-remote-server-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();
		LOG.info("RemoteAdapterServer listening on port " + serverSocket.getLocalPort());
	}

	/** Actual bound port - useful when constructed with port {@code 0} (let the OS pick one), e.g. in tests. */
	public int getLocalPort() {
		ServerSocket s = serverSocket;
		if (s == null) {
			throw new IllegalStateException("RemoteAdapterServer is not started");
		}
		return s.getLocalPort();
	}

	/** Stops accepting new connections and closes every currently-open one. Safe to call more than once. */
	@Override
	public synchronized void close() {
		ServerSocket s = serverSocket;
		if (s == null) {
			return;
		}
		try {
			s.close();
		} catch (IOException ignored) {
		}
		Thread t = acceptThread;
		if (t != null) {
			t.interrupt();
		}
		for (ServerSocketLinePipe pipe : activePipes) {
			pipe.close();
		}
		activePipes.clear();
		serverSocket = null;
		acceptThread = null;
	}

	private void acceptLoop() {
		while (true) {
			Socket socket;
			try {
				socket = serverSocket.accept();
			} catch (IOException e) {
				if (serverSocket == null || serverSocket.isClosed()) {
					return;
				}
				LOG.log(Level.WARNING, "accept() failed", e);
				continue;
			}
			Thread handler = new Thread(() -> handleConnection(socket), "ble-remote-server-conn");
			handler.setDaemon(true);
			handler.start();
		}
	}

	private void handleConnection(Socket socket) {
		String name = null;
		try {
			socket.setTcpNoDelay(true);
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();

			Map<String, String> headers = readHandshakeHeaders(in);
			if (headers == null) {
				closeQuietly(socket);
				return;
			}
			name = headers.get(NAME_HEADER);
			String rejection = validate(headers);
			if (rejection != null) {
				LOG.warning("Rejected BLE remote-adapter handshake" + (name != null ? " for '" + name + "'" : "")
						+ ": " + rejection);
				out.write(rejection.getBytes(StandardCharsets.US_ASCII));
				out.flush();
				closeQuietly(socket);
				return;
			}

			writeHandshakeAccept(out, headers.get(WS_KEY_HEADER));

			WsFrameCodec codec = new WsFrameCodec(socket);
			ServerSocketLinePipe pipe = new ServerSocketLinePipe(codec);
			activePipes.add(pipe);
			registry.register(name, pipe);
			LOG.info("BLE remote adapter '" + name + "' connected from " + socket.getRemoteSocketAddress());
			try {
				pipe.runReadLoop();
			} finally {
				activePipes.remove(pipe);
				LOG.info("BLE remote adapter '" + name + "' disconnected");
			}
		} catch (IOException e) {
			LOG.log(Level.FINE, "BLE remote-adapter connection ended" + (name != null ? " for '" + name + "'" : ""),
					e);
			closeQuietly(socket);
		}
	}

	/** {@code null} if the handshake couldn't be parsed at all (not a valid request). */
	private static Map<String, String> readHandshakeHeaders(InputStream in) throws IOException {
		String requestLine = WsFrameCodec.readLine(in);
		if (requestLine == null || requestLine.isEmpty()) {
			return null;
		}
		Map<String, String> headers = new HashMap<>();
		String line;
		while ((line = WsFrameCodec.readLine(in)) != null && !line.isEmpty()) {
			int colon = line.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = line.substring(colon + 1).trim();
			headers.put(key, value);
		}
		return headers;
	}

	/** Returns a full HTTP status-line response to write back and close on, or {@code null} if valid. */
	private String validate(Map<String, String> headers) {
		String upgrade = headers.get(UPGRADE_HEADER);
		if (upgrade == null || !upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
			return "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n";
		}
		String name = headers.get(NAME_HEADER);
		if (name == null || name.trim().isEmpty()) {
			return "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n";
		}
		if (expectedToken == null || expectedToken.trim().isEmpty()) {
			return "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n";
		}
		String authorization = headers.get(AUTH_HEADER);
		String presentedToken = authorization != null && authorization.startsWith("Bearer ")
				? authorization.substring("Bearer ".length())
				: null;
		if (presentedToken == null || !expectedToken.equals(presentedToken)) {
			return "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n";
		}
		return null;
	}

	private static void writeHandshakeAccept(OutputStream out, String secWebSocketKey) throws IOException {
		String accept = acceptKeyFor(secWebSocketKey != null ? secWebSocketKey : "");
		String response = "HTTP/1.1 101 Switching Protocols\r\n" + "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n" + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
		out.write(response.getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	private static String acceptKeyFor(String secWebSocketKey) {
		try {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			byte[] hash = sha1.digest((secWebSocketKey + WS_GUID).getBytes(StandardCharsets.US_ASCII));
			return Base64.getEncoder().encodeToString(hash);
		} catch (NoSuchAlgorithmException e) {
			// SHA-1 is guaranteed available on every JDK.
			throw new IllegalStateException("SHA-1 not available", e);
		}
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}
}
