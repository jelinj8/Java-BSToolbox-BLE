package cz.bliksoft.javautils.ble.transport;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Transport-agnostic pipe of newline-delimited JSON lines to/from a
 * {@code ble-bridge}-speaking peer - either a locally-spawned sidecar process
 * ({@link ProcessLinePipe}) or a remote connection. {@code BleAdapter} talks to
 * this abstraction only; it never touches a process or a socket directly.
 */
public interface BleLinePipe extends AutoCloseable {

	/** Sends one JSON line to the peer. */
	void send(String jsonLine) throws IOException;

	/** Registers the callback invoked for each incoming JSON line. */
	void onLine(Consumer<String> handler);

	/**
	 * Registers the callback invoked exactly once when the pipe dies (process exit,
	 * socket close), with a short reason string describing why.
	 */
	void onClose(Consumer<String> reasonHandler);

	/** Whether the underlying transport is still up. */
	boolean isAlive();

	@Override
	void close();
}
