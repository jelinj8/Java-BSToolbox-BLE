package cz.bliksoft.javautils.ble;

/** Base type for every failure this library can raise. */
public class BleException extends Exception {

	private static final long serialVersionUID = 1L;

	public BleException(String message) {
		super(message);
	}

	public BleException(String message, Throwable cause) {
		super(message, cause);
	}
}
