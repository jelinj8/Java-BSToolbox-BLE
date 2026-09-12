package cz.bliksoft.javautils.ble;

import java.util.List;

import cz.bliksoft.javautils.ble.utils.BleUtils;
import cz.bliksoft.javautils.ble.utils.BleUtils.BleDeviceResult;

/**
 * Command-line entry point for ad-hoc device discovery. Scans for nearby
 * peripherals and prints them as tab-separated {@code ADDRESS\tNAME\tRSSI}
 * lines to stdout - one device per line, {@code -} in place of a missing name
 * or RSSI. If an argument is given, it's used as a substring search term (see
 * {@link BleUtils#find}); otherwise every discovered device is printed (see
 * {@link BleUtils#scan}). See README.md for how to build and run this as a
 * standalone jar.
 */
public final class Main {

	private static final long TIMEOUT_MS = 10000;

	public static void main(String[] args) {
		String searchTerm = args.length > 0 ? args[0] : null;
		try (BleAdapter adapter = new BleAdapter()) {
			List<BleDeviceResult> results = searchTerm != null ? BleUtils.find(adapter, null, searchTerm, TIMEOUT_MS)
					: BleUtils.scan(adapter, TIMEOUT_MS);
			for (BleDeviceResult result : results) {
				System.out.println(result.getAddress() + "\t" + (result.getName() != null ? result.getName() : "-")
						+ "\t" + (result.getRssi() != null ? result.getRssi() : "-"));
			}
		} catch (BleException e) {
			System.err.println("error: " + e.getMessage());
			System.exit(1);
		}
	}

	private Main() {
	}
}
