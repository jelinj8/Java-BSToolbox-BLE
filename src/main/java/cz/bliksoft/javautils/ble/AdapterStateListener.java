package cz.bliksoft.javautils.ble;

/**
 * Called from the adapter's internal reader thread whenever the Bluetooth
 * radio's power state changes (switched off in the OS, rfkill, a USB dongle
 * unplugged, ...) - see {@link BleAdapter#setAdapterStateListener}.
 */
public interface AdapterStateListener {
	void onAdapterStateChanged(AdapterState state);
}
