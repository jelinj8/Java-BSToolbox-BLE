package cz.bliksoft.javautils.ble;

/**
 * {@link BlePeripheral#connect()} failed because the peripheral no longer
 * accepts the stored bond - it has lost its own copy (factory reset, wiped
 * storage), so encryption with the stored key fails and the link drops on every
 * attempt. Retrying can't help: {@link BlePeripheral#unpair()}, scan again and
 * {@link BlePeripheral#pair(String)} after the next connect.
 */
public class BleBondRejectedException extends BleConnectException {

	private static final long serialVersionUID = 1L;

	public BleBondRejectedException(String message) {
		super(message);
	}
}
