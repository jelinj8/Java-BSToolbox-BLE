package cz.bliksoft.javautils.ble;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import cz.bliksoft.javautils.ble.remote.RemoteAdapterClient;
import cz.bliksoft.javautils.ble.utils.BleUtils;
import cz.bliksoft.javautils.ble.utils.BleUtils.BleDeviceResult;

/**
 * Command-line entry point. With no arguments (or a device-name search
 * substring), scans for nearby peripherals and prints them as tab-separated
 * {@code ADDRESS\tRSSI\tNAME} lines to stdout - one device per line, {@code -}
 * in place of a missing name or RSSI (see {@link BleUtils#find} /
 * {@link BleUtils#scan}). With {@code --remote}, instead runs as a
 * {@link RemoteAdapterClient} - see README.md for both usages and how to build
 * and run this as a standalone jar.
 */
public final class Main {

	private static final long TIMEOUT_MS = 10000;

	public static void main(String[] args) {
		if (args.length > 0 && "--remote".equals(args[0])) {
			runRemote(Arrays.copyOfRange(args, 1, args.length));
			return;
		}

		String searchTerm = args.length > 0 ? args[0] : null;
		try (BleAdapter adapter = new BleAdapter()) {
			List<BleDeviceResult> results = searchTerm != null ? BleUtils.find(adapter, null, searchTerm, TIMEOUT_MS)
					: BleUtils.scan(adapter, TIMEOUT_MS);
			for (BleDeviceResult result : results) {
				System.out.println(result.getAddress() + "\t" + (result.getRssi() != null ? result.getRssi() : "-")
						+ "\t" + (result.getName() != null ? result.getName() : "-"));
			}
		} catch (BleException e) {
			System.err.println("error: " + e.getMessage());
			System.exit(1);
		}
	}

	private static void runRemote(String[] args) {
		String server = null;
		String name = null;
		String token = null;
		try {
			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
				case "--server":
					server = args[++i];
					break;
				case "--name":
					name = args[++i];
					break;
				case "--token":
					token = args[++i];
					break;
				default:
					System.err.println("error: unrecognized argument '" + args[i] + "'");
					System.exit(1);
					return;
				}
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			server = null; // force the usage message below
		}
		if (server == null || name == null) {
			System.err.println("usage: --remote --server <ws-uri> --name <name> [--token <token>]");
			System.exit(1);
			return;
		}
		try {
			new RemoteAdapterClient(URI.create(server), name, token).run();
		} catch (BleException e) {
			System.err.println("error: " + e.getMessage());
			System.exit(1);
		}
	}

	private Main() {
	}
}
