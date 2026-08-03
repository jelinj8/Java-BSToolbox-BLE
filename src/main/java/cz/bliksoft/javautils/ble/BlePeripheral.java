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

	// Must exceed the sidecar's own retry budget for a single GATT op (ble-bridge's retry_gatt:
	// currently ~21.5s worst case), or the Java side times out before the sidecar's own retries -
	// which exist to ride out transient OS-level BLE delays - get a chance to finish.
	private static final long CONNECT_TIMEOUT_MS = 25000;
	private static final long DEFAULT_TIMEOUT_MS = 25000;

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
	 * Fires whenever this peripheral drops - see {@link DisconnectListener} for the
	 * crash-isolation case.
	 */
	public void setDisconnectListener(DisconnectListener listener) {
		this.disconnectListener = listener;
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
