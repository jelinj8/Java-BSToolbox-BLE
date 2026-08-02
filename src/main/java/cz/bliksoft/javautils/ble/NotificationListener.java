package cz.bliksoft.javautils.ble;

/** Called from the adapter's internal reader thread - implementations must not block. */
public interface NotificationListener {

	void onNotification(String characteristicUuid, byte[] value);
}
