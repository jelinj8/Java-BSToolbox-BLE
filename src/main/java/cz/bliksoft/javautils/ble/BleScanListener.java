package cz.bliksoft.javautils.ble;

/** Called from the adapter's internal reader thread - implementations must not block. */
public interface BleScanListener {

	void onDeviceFound(String address, String name, Integer rssi);
}
