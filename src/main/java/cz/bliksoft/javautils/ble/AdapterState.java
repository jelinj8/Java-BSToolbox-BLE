package cz.bliksoft.javautils.ble;

/**
 * Bluetooth radio power state, as reported by
 * {@link BleAdapter#getAdapterState()}.
 */
public enum AdapterState {
	UNKNOWN, POWERED_ON, POWERED_OFF;

	/** Parses the ble-bridge wire value ({@code powered_on}/{@code powered_off}/anything else). */
	static AdapterState fromWire(String state) {
		switch (state == null ? "" : state) {
		case "powered_on":
			return POWERED_ON;
		case "powered_off":
			return POWERED_OFF;
		default:
			return UNKNOWN;
		}
	}
}
