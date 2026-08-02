package cz.bliksoft.javautils.ble;

/** The peripheral rejected or dropped a connect/service/characteristic operation. */
public class BleConnectException extends BleException {

	private static final long serialVersionUID = 1L;

	public BleConnectException(String message) {
		super(message);
	}
}
