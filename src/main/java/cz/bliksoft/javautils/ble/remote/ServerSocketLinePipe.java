package cz.bliksoft.javautils.ble.remote;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import cz.bliksoft.javautils.ble.transport.BleLinePipe;

/**
 * Adapts a raw-socket {@link WsFrameCodec} to {@link BleLinePipe}, the {@link RemoteAdapterServer}
 * equivalent of {@code StorageManagerServer}'s {@code SpringSessionLinePipe} (which adapts a Spring
 * {@code WebSocketSession} the same way) - so a connection accepted here can be registered with a
 * {@code RemoteAdapterRegistry} exactly like any other transport.
 */
final class ServerSocketLinePipe implements BleLinePipe {

	private static final Logger LOG = Logger.getLogger(ServerSocketLinePipe.class.getName());

	private final WsFrameCodec codec;
	private volatile Consumer<String> lineHandler;
	private volatile Consumer<String> closeHandler;

	ServerSocketLinePipe(WsFrameCodec codec) {
		this.codec = codec;
	}

	@Override
	public void send(String jsonLine) throws IOException {
		codec.sendText(jsonLine);
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
		return !codec.isClosed();
	}

	@Override
	public void close() {
		codec.sendClose();
		codec.close();
	}

	/**
	 * Blocks the calling thread reading messages until the connection closes (peer-initiated or
	 * {@link #close()}), dispatching each to the registered line handler and firing the close handler
	 * exactly once when it ends, with a short reason string (see {@link BleLinePipe#onClose}). Meant to
	 * be run on its own dedicated thread per connection - see {@link RemoteAdapterServer}.
	 */
	void runReadLoop() {
		String reason = "remote peer closed the connection";
		try {
			while (true) {
				String line;
				try {
					line = codec.readTextMessage();
				} catch (IOException e) {
					LOG.log(Level.FINE, "BLE remote connection read failed", e);
					reason = "connection error: " + e.getMessage();
					break;
				}
				if (line == null) {
					break;
				}
				Consumer<String> handler = lineHandler;
				if (handler != null) {
					handler.accept(line);
				}
			}
		} finally {
			codec.close();
			Consumer<String> handler = closeHandler;
			if (handler != null) {
				handler.accept(reason);
			}
		}
	}
}
