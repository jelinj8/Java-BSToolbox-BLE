package cz.bliksoft.javautils.ble;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A handle to one BLE peripheral, obtained via
 * {@link BleAdapter#getPeripheral(String)}. One instance is cached per address
 * for the lifetime of its {@link BleAdapter}, so listeners registered here keep
 * receiving events across reconnects.
 */
public class BlePeripheral {

	// Must exceed the sidecar's own worst-case time for the corresponding op, or
	// the Java side
	// times out before the sidecar - retrying to ride out transient OS-level BLE
	// delays - gets a
	// chance to finish. connect: ~21.5s retry budget (ble-bridge's retry_gatt) + a
	// 2s post-connect
	// settle delay = ~23.5s worst case.
	private static final long CONNECT_TIMEOUT_MS = 30000;
	// On Linux/macOS, subscribe/read/write/unsubscribe can each chain an implicit
	// discover_services
	// first (if characteristics aren't cached yet), whose own retry budget (~46s)
	// stacks with the
	// op's own (~21.5s): ~67.5s worst case. On Windows those same calls go through
	// win_gatt.rs
	// instead, which has no internal retry loop - this timeout is also the only
	// bound on how long a
	// stuck WinRT call there is given before the caller gets a BleTimeoutException.
	private static final long DEFAULT_TIMEOUT_MS = 75000;

	private final BleAdapter adapter;
	private final String address;
	private final Map<String, NotificationListener> notificationListeners = new ConcurrentHashMap<>();
	private volatile boolean connected;
	private volatile DisconnectListener disconnectListener;

	BlePeripheral(BleAdapter adapter, String address) {
		this.adapter = adapter;
		this.address = address;
	}

	public String getAddress() {
		return address;
	}

	public boolean isConnected() {
		return connected;
	}

	public void connect() throws BleException {
		adapter.sendRequest("connect", fields("address", address), CONNECT_TIMEOUT_MS);
		connected = true;
	}

	public void disconnect() throws BleException {
		adapter.sendRequest("disconnect", fields("address", address), CONNECT_TIMEOUT_MS);
		connected = false;
	}

	public List<BleService> discoverServices() throws BleException {
		JsonNode resp = adapter.sendRequest("discover_services", fields("address", address), CONNECT_TIMEOUT_MS);
		List<BleService> services = new ArrayList<>();
		for (JsonNode s : resp.path("services")) {
			List<BleCharacteristic> chars = new ArrayList<>();
			for (JsonNode c : s.path("characteristics")) {
				List<String> props = new ArrayList<>();
				for (JsonNode p : c.path("properties")) {
					props.add(p.asText());
				}
				chars.add(new BleCharacteristic(c.path("uuid").asText(), props));
			}
			services.add(new BleService(s.path("uuid").asText(), chars));
		}
		return services;
	}

	public byte[] readCharacteristic(String serviceUuid, String charUuid) throws BleException {
		JsonNode resp = adapter.sendRequest("read",
				fields("address", address, "service_uuid", serviceUuid, "char_uuid", charUuid), DEFAULT_TIMEOUT_MS);
		return HexCodec.decode(resp.path("value_hex").asText(""));
	}

	public void writeCharacteristic(String serviceUuid, String charUuid, byte[] value, boolean withResponse)
			throws BleException {
		Map<String, Object> f = fields("address", address, "service_uuid", serviceUuid, "char_uuid", charUuid);
		f.put("value_hex", HexCodec.encode(value));
		f.put("with_response", withResponse);
		adapter.sendRequest("write", f, DEFAULT_TIMEOUT_MS);
	}

	/**
	 * Splits {@code value} into {@code chunkSize}-byte pieces and writes each in turn (sleeping
	 * {@code chunkDelayMs} between writes), all as <b>one</b> sidecar/bridge-side operation - a
	 * single request/response round trip covers the whole transfer, instead of one round trip per
	 * chunk. Built for one-way, ack-free protocols that already send many same-characteristic
	 * writes back-to-back with a fixed inter-write delay (e.g. Phomemo's {@code d-series} label
	 * protocol) - calling {@link #writeCharacteristic} in a loop for that shape works correctly
	 * over a low-latency link, but on a higher-latency one (this library's own remote-adapter
	 * feature, especially an ESP32-class remote bridge) that per-chunk round trip can make the
	 * whole transfer slow enough to blow a downstream peripheral's own real-time expectations for
	 * long transfers, even though every individual write still succeeds.
	 *
	 * <p>
	 * Deliberately <b>not</b> built by pipelining repeated {@link #writeCharacteristic} calls
	 * without waiting for each response: the sidecar dispatches each incoming command as an
	 * independent concurrent task with no ordering guarantee between them (see
	 * {@code ble-bridge/src/main.rs}'s command loop), so firing many writes without waiting could
	 * let them reach the peripheral out of order and corrupt a sequential byte stream like this
	 * one. Sending the whole payload as a single {@code write_stream} command keeps the entire
	 * chunk-by-chunk write sequence inside that one command's own sequential handling on the
	 * sidecar/bridge side, so ordering is guaranteed by construction rather than by the caller
	 * waiting on each response.
	 */
	public void writeCharacteristicStream(String serviceUuid, String charUuid, byte[] value, boolean withResponse,
			int chunkSize, long chunkDelayMs) throws BleException {
		Map<String, Object> f = fields("address", address, "service_uuid", serviceUuid, "char_uuid", charUuid);
		f.put("value_hex", HexCodec.encode(value));
		f.put("with_response", withResponse);
		f.put("chunk_size", (long) chunkSize);
		f.put("chunk_delay_ms", chunkDelayMs);
		adapter.sendRequest("write_stream", f, DEFAULT_TIMEOUT_MS);
	}

	public void subscribe(String serviceUuid, String charUuid, NotificationListener listener) throws BleException {
		notificationListeners.put(charUuid.toUpperCase(Locale.ROOT), listener);
		adapter.sendRequest("subscribe", fields("address", address, "service_uuid", serviceUuid, "char_uuid", charUuid),
				DEFAULT_TIMEOUT_MS);
	}

	public void unsubscribe(String serviceUuid, String charUuid) throws BleException {
		adapter.sendRequest("unsubscribe",
				fields("address", address, "service_uuid", serviceUuid, "char_uuid", charUuid), DEFAULT_TIMEOUT_MS);
		notificationListeners.remove(charUuid.toUpperCase(Locale.ROOT));
	}

	/**
	 * Programmatically pairs/bonds with this peripheral, supplying {@code pin} as
	 * the passkey automatically instead of surfacing any OS-level pairing prompt -
	 * must already be connected (a prior {@link #connect()}). Currently
	 * Windows-only (see {@code ble-bridge/src/win_gatt.rs}'s {@code pair} function
	 * and this repo's CLAUDE.md, "Planned: programmatic pairing"); other platforms
	 * throw {@link BleException} for now, with Linux support planned next.
	 */
	public void pair(String pin) throws BleException {
		adapter.sendRequest("pair", fields("address", address, "pin", pin), CONNECT_TIMEOUT_MS);
	}

	/**
	 * Fires whenever this peripheral drops - see {@link DisconnectListener} for the
	 * crash-isolation case.
	 */
	public void setDisconnectListener(DisconnectListener listener) {
		this.disconnectListener = listener;
	}

	/**
	 * Returns the currently negotiated ATT MTU in bytes (usable characteristic
	 * payload per write is this minus 3 bytes of ATT overhead). Backend support
	 * varies by platform.
	 */
	public int getMtu() throws BleException {
		JsonNode resp = adapter.sendRequest("get_mtu", fields("address", address), DEFAULT_TIMEOUT_MS);
		return resp.path("mtu").asInt();
	}

	/**
	 * Reads the current RSSI (signal strength) in dBm. Behavior varies by platform
	 * - see {@code btleplug::api::Peripheral::read_rssi}'s own doc for the
	 * per-platform freshness caveats (e.g. Windows returns the most recent value
	 * from advertisements, which needs scanning to be active to stay fresh).
	 */
	public int readRssi() throws BleException {
		JsonNode resp = adapter.sendRequest("read_rssi", fields("address", address), DEFAULT_TIMEOUT_MS);
		return resp.path("rssi").asInt();
	}

	/**
	 * Returns the current BLE connection parameters as reported by the OS, or
	 * {@code null} if this platform doesn't expose them (backend support varies).
	 * Throws if not connected.
	 */
	public ConnectionParameters getConnectionParameters() throws BleException {
		JsonNode resp = adapter.sendRequest("get_connection_parameters", fields("address", address),
				DEFAULT_TIMEOUT_MS);
		if (!resp.has("interval_us")) {
			return null;
		}
		return new ConnectionParameters(resp.path("interval_us").asLong(), resp.path("latency").asInt(),
				resp.path("supervision_timeout_us").asLong());
	}

	/**
	 * Requests a connection parameter update using a preset - e.g.
	 * {@link ConnectionParameterPreset#THROUGHPUT_OPTIMIZED} before a bulk
	 * transfer, switched back to {@link ConnectionParameterPreset#BALANCED}
	 * afterward. This is only a request: the remote device may accept or reject it:
	 * read {@link #getConnectionParameters()} afterward to see what actually took
	 * effect, rather than assuming the request was honored. Throws
	 * {@link BleException} on backends that don't support this (currently:
	 * confirmed on Windows).
	 */
	public void requestConnectionParameters(ConnectionParameterPreset preset) throws BleException {
		String presetStr;
		switch (preset) {
		case THROUGHPUT_OPTIMIZED:
			presetStr = "throughput_optimized";
			break;
		case POWER_OPTIMIZED:
			presetStr = "power_optimized";
			break;
		default:
			presetStr = "balanced";
		}
		adapter.sendRequest("request_connection_parameters", fields("address", address, "preset", presetStr),
				DEFAULT_TIMEOUT_MS);
	}

	// --- internals used by BleAdapter's reader thread
	// -----------------------------------------

	void fireNotification(String charUuid, byte[] value) {
		NotificationListener l = notificationListeners.get(charUuid.toUpperCase(Locale.ROOT));
		if (l != null) {
			l.onNotification(charUuid, value);
		}
	}

	void fireDisconnected(String reason) {
		connected = false;
		DisconnectListener l = disconnectListener;
		if (l != null) {
			l.onDisconnected(reason);
		}
	}

	private static Map<String, Object> fields(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}
}
