package cz.bliksoft.javautils.ble;

/**
 * Called from the adapter's internal reader thread whenever a connected peripheral drops -
 * whether the peripheral disconnected itself, or the ble-bridge sidecar process died. In the
 * latter case {@code reason} is {@code "sidecar_crashed"}: the whole point of running BLE in a
 * separate process is that this callback fires instead of the JVM going down with it.
 */
public interface DisconnectListener {

	void onDisconnected(String reason);
}
