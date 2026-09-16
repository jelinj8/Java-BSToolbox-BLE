package cz.bliksoft.javautils.ble.remote;

import cz.bliksoft.javautils.ble.BleException;

/**
 * The server rejected the WebSocket handshake's bearer token (or none was sent). The name/token a
 * {@link RemoteAdapterClient} presents are fixed at construction, so retrying the same connection
 * can never succeed - the caller must be restarted with a corrected token instead.
 */
public class BleRemoteAuthException extends BleException {

	private static final long serialVersionUID = 1L;

	public BleRemoteAuthException(String message) {
		super(message);
	}
}
