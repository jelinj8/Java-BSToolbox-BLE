package cz.bliksoft.javautils.ble.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * In-memory, synchronous {@link BleLinePipe} test double. {@link #send} records
 * every line the adapter under test wrote instead of forwarding it anywhere;
 * tests drive the other direction by calling {@link #deliverLine} /
 * {@link #closePeer} directly.
 */
public class FakeLinePipe implements BleLinePipe {

	private final List<String> sent = new ArrayList<>();
	private Consumer<String> lineHandler;
	private Consumer<String> closeHandler;
	private volatile boolean alive = true;
	private volatile boolean closedByLocal = false;

	@Override
	public void send(String jsonLine) {
		sent.add(jsonLine);
	}

	@Override
	public void onLine(Consumer<String> handler) {
		this.lineHandler = handler;
	}

	@Override
	public void onClose(Consumer<String> reasonHandler) {
		this.closeHandler = reasonHandler;
	}

	@Override
	public boolean isAlive() {
		return alive;
	}

	@Override
	public void close() {
		closedByLocal = true;
		alive = false;
	}

	/** Simulates a line arriving from the remote/sidecar peer. */
	public void deliverLine(String jsonLine) {
		if (lineHandler != null) {
			lineHandler.accept(jsonLine);
		}
	}

	/**
	 * Simulates the peer dying (process crash, socket drop) with the given reason.
	 */
	public void closePeer(String reason) {
		alive = false;
		if (closeHandler != null) {
			closeHandler.accept(reason);
		}
	}

	public List<String> getSent() {
		return sent;
	}

	public boolean wasClosedByLocal() {
		return closedByLocal;
	}
}
