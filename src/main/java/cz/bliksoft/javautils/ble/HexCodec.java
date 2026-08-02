package cz.bliksoft.javautils.ble;

/**
 * Matches the hex encoding used on the wire by the ble-bridge sidecar
 * (lowercase, no separators).
 */
final class HexCodec {

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private HexCodec() {
	}

	static String encode(byte[] bytes) {
		char[] out = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			int v = bytes[i] & 0xFF;
			out[i * 2] = HEX[v >>> 4];
			out[i * 2 + 1] = HEX[v & 0x0F];
		}
		return new String(out);
	}

	static byte[] decode(String hex) {
		if (hex == null || hex.isEmpty()) {
			return new byte[0];
		}
		int len = hex.length();
		byte[] out = new byte[len / 2];
		for (int i = 0; i < out.length; i++) {
			int hi = Character.digit(hex.charAt(i * 2), 16);
			int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
			out[i] = (byte) ((hi << 4) | lo);
		}
		return out;
	}
}
