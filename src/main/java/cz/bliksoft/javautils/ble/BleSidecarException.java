package cz.bliksoft.javautils.ble;

/**
 * The ble-bridge sidecar process could not be started, crashed, or sent
 * something that didn't parse as the expected protocol. This is the "isolation
 * held" failure mode this library exists for: it always surfaces as this
 * exception, never as a fault inside the JVM itself.
 */
public class BleSidecarException extends BleException {

	private static final long serialVersionUID = 1L;

	public BleSidecarException(String message) {
		super(message);
	}

	public BleSidecarException(String message, Throwable cause) {
		super(message, cause);
	}
}
