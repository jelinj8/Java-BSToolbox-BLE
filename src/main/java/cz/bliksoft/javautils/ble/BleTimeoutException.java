package cz.bliksoft.javautils.ble;

/**
 * A request to the sidecar (connect, read, write, ...) did not get a response
 * in time.
 */
public class BleTimeoutException extends BleException {

	private static final long serialVersionUID = 1L;

	public BleTimeoutException(String message) {
		super(message);
	}
}
