package cz.bliksoft.javautils.ble;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

/**
 * Entry point for this library. Launches and owns one {@code ble-bridge}
 * sidecar process (a bundled Rust/btleplug binary, see
 * {@link NativeBinaryLoader}) and speaks newline-delimited JSON with it over
 * stdin/stdout.
 * <p>
 * The sidecar exists specifically so a native/driver-level BLE fault can't take
 * this JVM down with it: a crash surfaces as {@link BleSidecarException} /
 * {@link DisconnectListener#onDisconnected} with reason
 * {@code "sidecar_crashed"}, never as a JVM crash. Create one
 * {@code BleAdapter} per BLE session; call {@link #close()} when done with it.
 */
public class BleAdapter implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(BleAdapter.class.getName());
	private static final long DEFAULT_TIMEOUT_MS = 15000;

	private final ObjectMapper mapper = new ObjectMapper();
	private final Process process;
	private final OutputStream stdin;
	private final Object writeLock = new Object();
	private final AtomicLong idGenerator = new AtomicLong();
	private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private final Map<String, BlePeripheral> peripherals = new ConcurrentHashMap<>();
	private volatile BleScanListener scanListener;
	private volatile boolean alive = true;

	public BleAdapter() throws BleSidecarException {
		java.io.File binary = NativeBinaryLoader.extract();
		try {
			ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
			pb.redirectErrorStream(false);
			this.process = pb.start();
		} catch (IOException e) {
			throw new BleSidecarException("failed to launch ble-bridge sidecar", e);
		}
		this.stdin = process.getOutputStream();

		Thread reader = new Thread(this::readLoop, "ble-bridge-reader");
		reader.setDaemon(true);
		reader.start();

		Thread stderrPump = new Thread(this::stderrLoop, "ble-bridge-stderr");
		stderrPump.setDaemon(true);
		stderrPump.start();

		Thread exitWatcher = new Thread(this::watchExit, "ble-bridge-exit-watcher");
		exitWatcher.setDaemon(true);
		exitWatcher.start();
	}

	/**
	 * Scans for nearby peripherals for {@code timeoutMs} milliseconds, reporting
	 * each one found to {@code listener}. Blocks until the sidecar confirms
	 * scanning has stopped.
	 */
	public void scan(ScanFilter filter, long timeoutMs, BleScanListener listener) throws BleException {
		this.scanListener = listener;
		ObjectNode cmd = mapper.createObjectNode();
		cmd.put("cmd", "scan");
		if (filter != null && filter.getServiceUuid() != null) {
			cmd.put("filter_service_uuid", filter.getServiceUuid());
		}
		cmd.put("timeout_ms", timeoutMs);
		sendRequest(cmd, timeoutMs + 5000);
	}

	public void stopScan() throws BleException {
		ObjectNode cmd = mapper.createObjectNode();
		cmd.put("cmd", "stop_scan");
		sendRequest(cmd, DEFAULT_TIMEOUT_MS);
		this.scanListener = null;
	}

	/** Returns the (cached) handle for a peripheral address; does not connect. */
	public BlePeripheral getPeripheral(String address) {
		String key = address.toUpperCase(java.util.Locale.ROOT);
		return peripherals.computeIfAbsent(key, k -> new BlePeripheral(this, k));
	}

	public boolean isAlive() {
		return alive && process.isAlive();
	}

	@Override
	public void close() {
		alive = false;
		try {
			stdin.close();
		} catch (IOException ignored) {
			// sidecar may already be gone
		}
		try {
			if (!process.waitFor(3, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
		failAllPending("adapter closed");
	}

	// --- internals used by BlePeripheral
	// -----------------------------------------------------

	JsonNode sendRequest(String cmd, Map<String, Object> fields, long timeoutMs) throws BleException {
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

	private JsonNode sendRequest(ObjectNode node, long timeoutMs) throws BleException {
		if (!isAlive()) {
			throw new BleSidecarException("ble-bridge sidecar is not running");
		}
		String id = String.valueOf(idGenerator.incrementAndGet());
		node.put("id", id);
		CompletableFuture<JsonNode> future = new CompletableFuture<>();
		pending.put(id, future);
		try {
			writeLine(node);
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			pending.remove(id);
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
		} catch (IOException e) {
			pending.remove(id);
			throw new BleSidecarException("failed to write to sidecar stdin", e);
		}
	}

	private void writeLine(ObjectNode node) throws IOException {
		byte[] bytes = (mapper.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
		synchronized (writeLock) {
			stdin.write(bytes);
			stdin.flush();
		}
	}

	// --- background threads
	// -------------------------------------------------------------------

	private void readLoop() {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.trim().isEmpty()) {
					continue;
				}
				try {
					dispatch(mapper.readTree(line));
				} catch (IOException e) {
					LOG.log(Level.FINE, "unparseable line from ble-bridge: " + line, e);
				}
			}
		} catch (IOException e) {
			LOG.log(Level.FINE, "ble-bridge stdout closed", e);
		}
	}

	private void stderrLoop() {
		try (BufferedReader err = new BufferedReader(
				new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = err.readLine()) != null) {
				LOG.fine("[ble-bridge] " + line);
			}
		} catch (IOException ignored) {
			// process gone
		}
	}

	private void watchExit() {
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		alive = false;
		failAllPending("ble-bridge sidecar process exited unexpectedly");
		for (BlePeripheral p : peripherals.values()) {
			p.fireDisconnected("sidecar_crashed");
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
				l.onDeviceFound(node.path("address").asText(null), node.path("name").asText(null),
						node.hasNonNull("rssi") ? node.path("rssi").asInt() : null);
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
