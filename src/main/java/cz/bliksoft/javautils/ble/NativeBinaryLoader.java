package cz.bliksoft.javautils.ble;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Extracts the platform-appropriate {@code ble-bridge} binary bundled as a
 * classpath resource (built separately per OS/arch by the {@code ble-bridge}
 * Rust crate) to a temp file so it can be launched with {@link ProcessBuilder}.
 * No BLE library or runtime needs to be pre-installed on the target machine -
 * the binary is fully self-contained.
 */
final class NativeBinaryLoader {

	private NativeBinaryLoader() {
	}

	static File extract() throws BleSidecarException {
		String platformDir = platformDir();
		boolean windows = platformDir.startsWith("win-");
		String resourceName = "ble-bridge" + (windows ? ".exe" : "");
		String resourcePath = "/native/" + platformDir + "/" + resourceName;

		try (InputStream in = NativeBinaryLoader.class.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new BleSidecarException("no bundled ble-bridge binary for " + platformDir
						+ " (expected classpath resource " + resourcePath + ")");
			}
			File tmp = File.createTempFile("ble-bridge-", windows ? ".exe" : "");
			tmp.deleteOnExit();
			try (OutputStream out = Files.newOutputStream(tmp.toPath())) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) != -1) {
					out.write(buf, 0, n);
				}
			}
			if (!windows) {
				tmp.setExecutable(true, true);
			}
			return tmp;
		} catch (IOException e) {
			throw new BleSidecarException("failed to extract ble-bridge binary", e);
		}
	}

	private static String platformDir() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

		String osKey;
		if (os.contains("win")) {
			osKey = "win";
		} else if (os.contains("linux")) {
			osKey = "linux";
		} else if (os.contains("mac") || os.contains("darwin")) {
			osKey = "mac";
		} else {
			osKey = os;
		}

		String archKey;
		if (arch.equals("amd64") || arch.equals("x86_64")) {
			archKey = "x86_64";
		} else if (arch.equals("aarch64") || arch.equals("arm64")) {
			archKey = "aarch64";
		} else if (arch.startsWith("arm")) {
			// JVMs report this 32-bit-ARM value inconsistently ("arm", "armv7l", "arm32", ...);
			// normalize to the one directory name the ble-bridge build produces (armv7-a hard-float,
			// i.e. Raspberry Pi 2/3/4/5 running a 32-bit OS and similar SBCs - not the armv6
			// original Pi 1 / Pi Zero, which isn't binary-compatible with this build).
			archKey = "armv7";
		} else {
			archKey = arch;
		}

		return osKey + "-" + archKey;
	}
}
