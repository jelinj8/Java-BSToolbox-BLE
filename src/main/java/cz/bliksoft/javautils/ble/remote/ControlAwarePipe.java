package cz.bliksoft.javautils.ble.remote;

import java.io.IOException;
import java.util.function.Consumer;

import cz.bliksoft.javautils.ble.transport.BleLinePipe;

/**
 * Wraps a host-supplied {@link BleLinePipe} so
 * {@link DefaultRemoteAdapterRegistry} can observe it (for its own
 * name-&gt;adapter bookkeeping and for {@link SessionControl} session-reset
 * lines) while still handing the {@code BleAdapter} constructed on top of it a
 * plain {@link BleLinePipe} that only ever sees real {@code ble-bridge} lines.
 */
final class ControlAwarePipe implements BleLinePipe {

	private final BleLinePipe raw;
	private final Consumer<String> registryOnClose;
	private final Runnable registryOnSessionReady;
	private volatile Consumer<String> lineHandler;
	private volatile Consumer<String> closeHandler;

	ControlAwarePipe(BleLinePipe raw, Consumer<String> registryOnClose, Runnable registryOnSessionReady) {
		this.raw = raw;
		this.registryOnClose = registryOnClose;
		this.registryOnSessionReady = registryOnSessionReady;
		raw.onLine(this::handleRawLine);
		raw.onClose(this::handleRawClose);
	}

	@Override
	public void send(String jsonLine) throws IOException {
		raw.send(jsonLine);
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
		return raw.isAlive();
	}

	@Override
	public void close() {
		raw.close();
	}

	private void handleRawLine(String line) {
		if (SessionControl.SESSION_READY.equals(SessionControl.typeOf(line))) {
			registryOnSessionReady.run();
			return;
		}
		Consumer<String> handler = lineHandler;
		if (handler != null) {
			handler.accept(line);
		}
	}

	private void handleRawClose(String reason) {
		registryOnClose.accept(reason);
		Consumer<String> handler = closeHandler;
		if (handler != null) {
			handler.accept(reason);
		}
	}
}
