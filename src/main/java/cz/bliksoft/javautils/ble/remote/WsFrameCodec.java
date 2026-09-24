package cz.bliksoft.javautils.ble.remote;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal RFC 6455 WebSocket frame reader/writer over a raw {@link Socket} - just enough to
 * interoperate with {@code java.net.http.WebSocket} (the one real client {@link RemoteAdapterServer}
 * needs to support, since both endpoints of this connection are ours). Handles text/ping/pong/close
 * frames; deliberately does not support fragmented (multi-frame) messages - the only client here
 * ({@code RemoteAdapterClient}) always sends single-frame text messages
 * ({@code webSocket.sendText(line, true)}) - {@link #readTextMessage()} fails clearly rather than
 * silently mis-assembling a continuation it doesn't expect.
 */
final class WsFrameCodec implements Closeable {

	private static final int OP_CONTINUATION = 0x0;
	private static final int OP_TEXT = 0x1;
	private static final int OP_CLOSE = 0x8;
	private static final int OP_PING = 0x9;
	private static final int OP_PONG = 0xA;

	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private volatile boolean closed = false;

	WsFrameCodec(Socket socket) throws IOException {
		this.socket = socket;
		this.in = socket.getInputStream();
		this.out = socket.getOutputStream();
	}

	/**
	 * Blocks until a complete text message arrives, transparently answering pings with pongs and
	 * ignoring pongs. Returns {@code null} on a peer-initiated close or end of stream.
	 */
	String readTextMessage() throws IOException {
		while (true) {
			Frame frame = readFrame();
			if (frame == null) {
				return null;
			}
			switch (frame.opcode) {
			case OP_TEXT:
				if (!frame.fin) {
					throw new IOException("Fragmented WebSocket messages are not supported");
				}
				return new String(frame.payload, StandardCharsets.UTF_8);
			case OP_PING:
				writeFrame(OP_PONG, frame.payload);
				break;
			case OP_PONG:
				break;
			case OP_CLOSE:
				writeFrame(OP_CLOSE, frame.payload);
				return null;
			case OP_CONTINUATION:
				throw new IOException("Fragmented WebSocket messages are not supported");
			default:
				throw new IOException("Unsupported WebSocket opcode: " + frame.opcode);
			}
		}
	}

	void sendText(String text) throws IOException {
		writeFrame(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
	}

	void sendClose() {
		try {
			writeFrame(OP_CLOSE, new byte[0]);
		} catch (IOException ignored) {
			// best-effort - we're closing anyway
		}
	}

	boolean isClosed() {
		return closed || socket.isClosed();
	}

	@Override
	public void close() {
		closed = true;
		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}

	/** Server-to-client frames are never masked, per RFC 6455. */
	private synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
		if (closed) {
			throw new IOException("WsFrameCodec is closed");
		}
		out.write(0x80 | (opcode & 0x0F));
		int len = payload.length;
		if (len < 126) {
			out.write(len);
		} else if (len <= 0xFFFF) {
			out.write(126);
			out.write((len >>> 8) & 0xFF);
			out.write(len & 0xFF);
		} else {
			out.write(127);
			for (int i = 7; i >= 0; i--) {
				out.write((int) (((long) len >>> (8 * i)) & 0xFF));
			}
		}
		out.write(payload);
		out.flush();
	}

	/** Client-to-server frames must be masked, per RFC 6455 - unmasked here. */
	private Frame readFrame() throws IOException {
		int b0 = in.read();
		if (b0 == -1) {
			return null;
		}
		boolean fin = (b0 & 0x80) != 0;
		int opcode = b0 & 0x0F;

		int b1 = readByteOrEof();
		boolean masked = (b1 & 0x80) != 0;
		long len = b1 & 0x7F;
		if (len == 126) {
			len = (readByteOrEof() << 8) | readByteOrEof();
		} else if (len == 127) {
			len = 0;
			for (int i = 0; i < 8; i++) {
				len = (len << 8) | readByteOrEof();
			}
		}
		if (len > Integer.MAX_VALUE) {
			throw new IOException("WebSocket frame too large: " + len);
		}

		byte[] maskKey = null;
		if (masked) {
			maskKey = new byte[4];
			readFully(maskKey);
		}

		byte[] payload = new byte[(int) len];
		readFully(payload);
		if (masked) {
			for (int i = 0; i < payload.length; i++) {
				payload[i] ^= maskKey[i % 4];
			}
		}
		return new Frame(fin, opcode, payload);
	}

	private int readByteOrEof() throws IOException {
		int b = in.read();
		if (b == -1) {
			throw new EOFException("Unexpected end of stream reading a WebSocket frame");
		}
		return b;
	}

	private void readFully(byte[] buf) throws IOException {
		int off = 0;
		while (off < buf.length) {
			int n = in.read(buf, off, buf.length - off);
			if (n == -1) {
				throw new EOFException("Unexpected end of stream reading a WebSocket frame");
			}
			off += n;
		}
	}

	/** Only used internally while assembling a request line/headers before the frame codec takes over. */
	static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
		int prev = -1;
		int b;
		while ((b = in.read()) != -1) {
			if (prev == '\r' && b == '\n') {
				byte[] bytes = buf.toByteArray();
				return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
			}
			buf.write(b);
			prev = b;
		}
		return null;
	}

	private static final class Frame {
		final boolean fin;
		final int opcode;
		final byte[] payload;

		Frame(boolean fin, int opcode, byte[] payload) {
			this.fin = fin;
			this.opcode = opcode;
			this.payload = payload;
		}
	}
}
