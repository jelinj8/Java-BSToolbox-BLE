package cz.bliksoft.javautils.ble;

/**
 * Preferred connection parameter presets for
 * {@link BlePeripheral#requestConnectionParameters}. Backend support varies
 * (confirmed on Windows; other platforms may return {@link BleException} with
 * "NotSupported") - a request is advisory, the remote device may accept or
 * reject it.
 */
public enum ConnectionParameterPreset {
	/** Balanced between throughput and power (the connection's default). */
	BALANCED,
	/**
	 * Low latency, high throughput - use temporarily for bulk transfers, then
	 * switch back.
	 */
	THROUGHPUT_OPTIMIZED,
	/** Reduced power consumption, higher latency. */
	POWER_OPTIMIZED
}
