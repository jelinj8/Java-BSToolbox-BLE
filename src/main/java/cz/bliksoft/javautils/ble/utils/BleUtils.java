package cz.bliksoft.javautils.ble.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.BlePeripheral;
import cz.bliksoft.javautils.ble.BleScanListener;
import cz.bliksoft.javautils.ble.ScanFilter;

/**
 * Utility class providing common BLE operations.
 */
public class BleUtils {

	/**
	 * Scans for nearby peripherals and returns a list of unique devices, preferring
	 * those with non-null device names. This method delegates to
	 * {@link BleAdapter#scan(ScanFilter, long, BleScanListener)} but deduplicates
	 * results based on device address, keeping only the result with a non-null name
	 * when duplicates exist.
	 * <p>
	 * Address/name matching (exact or substring) is expressed on {@code filter}
	 * itself - see {@link ScanFilter#withAddress}, {@link ScanFilter#withName},
	 * {@link ScanFilter#withMatchingAddress}, {@link ScanFilter#withMatchingName}.
	 * {@link BleAdapter} enforces that matching (and any exact-match auto-stop)
	 * before this method ever sees a {@code device_found} event, so this method
	 * itself only needs to deduplicate what it's given.
	 *
	 * @param adapter   The BLE adapter to use for scanning
	 * @param filter    Optional scan filter to apply
	 * @param timeoutMs Timeout for the scan operation in milliseconds
	 * @return List of unique device results found during scanning, with preference
	 *         given to those having device names
	 * @throws BleException if the scan fails
	 */
	public static List<BleDeviceResult> scan(BleAdapter adapter, ScanFilter filter, long timeoutMs)
			throws BleException {
		// Map to store devices by address, keeping track of which ones have names
		Map<String, BleDeviceResult> deviceMap = new HashMap<>();

		// Create a listener that collects results
		BleScanListener scanListener = (address, name, rssi) -> {
			// Normalize the address to uppercase for consistent comparison
			String normalizedAddress = address.toUpperCase();

			// Update the device result, preferring non-null names
			deviceMap.compute(normalizedAddress, (addr, existingResult) -> {
				if (existingResult == null) {
					return new BleDeviceResult(address, name, rssi);
				} else {
					// If we already have a result and this one has a name, prefer it
					if (name != null && existingResult.name == null) {
						return new BleDeviceResult(address, name, rssi);
					}
					// Otherwise keep the existing result
					return existingResult;
				}
			});
		};

		// Start scanning - the adapter handles blocking until scan completion
		adapter.scan(filter, timeoutMs, scanListener);

		// Convert map values to list
		return new ArrayList<>(deviceMap.values());
	}

	/**
	 * Scans for nearby peripherals and returns a list of unique devices, preferring
	 * those with non-null device names. This method uses the default timeout.
	 *
	 * @param adapter The BLE adapter to use for scanning
	 * @param filter  Optional scan filter to apply
	 * @return List of unique device results found during scanning, with preference
	 *         given to those having device names
	 * @throws BleException if the scan fails
	 */
	public static List<BleDeviceResult> scan(BleAdapter adapter, ScanFilter filter) throws BleException {
		return scan(adapter, filter, 10000); // Default 10 second timeout
	}

	/**
	 * Scans for nearby peripherals and returns a list of unique devices, preferring
	 * those with non-null device names. This method scans without any filter.
	 *
	 * @param adapter   The BLE adapter to use for scanning
	 * @param timeoutMs Timeout for the scan operation in milliseconds
	 * @return List of unique device results found during scanning, with preference
	 *         given to those having device names
	 * @throws BleException if the scan fails
	 */
	public static List<BleDeviceResult> scan(BleAdapter adapter, long timeoutMs) throws BleException {
		return scan(adapter, null, timeoutMs);
	}

	/**
	 * Scans for nearby peripherals and returns a list of unique devices, preferring
	 * those with non-null device names. This method uses the default timeout and no
	 * filter.
	 *
	 * @param adapter The BLE adapter to use for scanning
	 * @return List of unique device results found during scanning, with preference
	 *         given to those having device names
	 * @throws BleException if the scan fails
	 */
	public static List<BleDeviceResult> scan(BleAdapter adapter) throws BleException {
		return scan(adapter, null, 10000); // Default 10 second timeout
	}

	/**
	 * Finds a single BLE peripheral by fulltext search (address or name contains
	 * string). Returns the peripheral if exactly one match is found, otherwise
	 * throws an exception.
	 *
	 * @param adapter    The BLE adapter to use for scanning
	 * @param searchTerm Search term to match against address or name (case
	 *                   insensitive)
	 * @param timeoutMs  Timeout for the scan operation in milliseconds
	 * @return The single matching device result
	 * @throws BleException if no matches or multiple matches are found
	 */
	public static BleDeviceResult findOne(BleAdapter adapter, ScanFilter filter, String searchTerm, long timeoutMs)
			throws BleException {
		List<BleDeviceResult> results = find(adapter, filter, searchTerm, timeoutMs);
		if (results.isEmpty()) {
			throw new BleException("No peripheral found matching search term: " + searchTerm);
		} else if (results.size() > 1) {
			throw new BleException("Multiple peripherals found matching search term: " + searchTerm + " (found "
					+ results.size() + " matches)");
		}
		return results.get(0);
	}

	/**
	 * Builds a filter carrying {@code filter}'s {@code serviceUuid} (if any) plus a
	 * substring address/name match on {@code searchTerm} - never mutates the
	 * caller's {@code filter} instance.
	 */
	private static ScanFilter withSearchTerm(ScanFilter filter, String searchTerm) {
		ScanFilter result = new ScanFilter();
		if (filter != null) {
			result.withServiceUuid(filter.getServiceUuid());
		}
		return result.withMatchingAddress(searchTerm).withMatchingName(searchTerm);
	}

	/**
	 * Finds a single BLE peripheral by fulltext search (address or name contains
	 * string). Returns the peripheral if exactly one match is found, otherwise
	 * throws an exception. Uses default timeout.
	 *
	 * @param adapter    The BLE adapter to use for scanning
	 * @param searchTerm Search term to match against address or name (case
	 *                   insensitive)
	 * @return The single matching device result
	 * @throws BleException if no matches or multiple matches are found
	 */
	public static BleDeviceResult findOne(BleAdapter adapter, ScanFilter filter, String searchTerm)
			throws BleException {
		return findOne(adapter, filter, searchTerm, 10000); // Default 10 second timeout
	}

	/**
	 * Finds BLE peripherals by fulltext search (address or name contains string).
	 * Returns a list of all matching device results.
	 *
	 * @param adapter    The BLE adapter to use for scanning
	 * @param searchTerm Search term to match against address or name (case
	 *                   insensitive)
	 * @param timeoutMs  Timeout for the scan operation in milliseconds
	 * @return List of matching device results
	 * @throws BleException if the scan fails
	 */
	public static List<BleDeviceResult> find(BleAdapter adapter, ScanFilter filter, String searchTerm, long timeoutMs)
			throws BleException {
		return scan(adapter, withSearchTerm(filter, searchTerm), timeoutMs);
	}

	/**
	 * Finds BLE peripherals by fulltext search (address or name contains string).
	 * Returns a list of all matching device results. Uses default timeout.
	 *
	 * @param adapter    The BLE adapter to use for scanning
	 * @param searchTerm Search term to match against address or name (case
	 *                   insensitive)
	 * @return List of matching device results
	 * @throws BleException if the scan fails
	 */
	public static List<BleDeviceResult> find(BleAdapter adapter, ScanFilter filter, String searchTerm)
			throws BleException {
		return find(adapter, filter, searchTerm, 10000); // Default 10 second timeout
	}

	/**
	 * Finds BLE peripherals by fulltext search with multiple comma-separated terms.
	 * Returns a list of all matching device results.
	 *
	 * @param adapter     The BLE adapter to use for scanning
	 * @param searchTerms Comma-separated list of search terms to match against
	 *                    address or name (case insensitive)
	 * @param timeoutMs   Timeout for the scan operation in milliseconds
	 * @return List of matching device results
	 * @throws BleException if the scan fails
	 */
	public static List<BleDeviceResult> find(BleAdapter adapter, ScanFilter filter, String[] searchTerms,
			long timeoutMs) throws BleException {
		// Normalize search terms to lowercase for case-insensitive comparison
		List<String> normalizedSearchTerms = new ArrayList<>();
		for (String term : searchTerms) {
			normalizedSearchTerms.add(term.toLowerCase().trim());
		}
		List<BleDeviceResult> devices = BleUtils.scan(adapter, filter, timeoutMs);

		// Filter results based on search terms
		List<BleDeviceResult> result = new ArrayList<>();
		for (BleDeviceResult deviceResult : devices) {
			boolean matches = false;
			String normalizedAddress = deviceResult.address.toLowerCase();
			String normalizedName = deviceResult.name != null ? deviceResult.name.toLowerCase() : "";

			// Check if any search term matches address or name
			for (String searchTerm : normalizedSearchTerms) {
				if (normalizedAddress.contains(searchTerm) || normalizedName.contains(searchTerm)) {
					matches = true;
					break;
				}
			}

			if (matches) {
				result.add(deviceResult);
			}
		}

		return result;
	}

	/**
	 * Finds BLE peripherals by fulltext search with multiple comma-separated terms.
	 * Returns a list of all matching device results. Uses default timeout.
	 *
	 * @param adapter     The BLE adapter to use for scanning
	 * @param searchTerms Comma-separated list of search terms to match against
	 *                    address or name (case insensitive)
	 * @return List of matching device results
	 * @throws BleException if the scan fails
	 */
	public static List<BleDeviceResult> find(BleAdapter adapter, ScanFilter filter, String[] searchTerms)
			throws BleException {
		return find(adapter, filter, searchTerms, 10000); // Default 10 second timeout
	}

	/**
	 * Helper class to store scan results.
	 */
	public static class BleDeviceResult {

		public String getAddress() {
			return address;
		}

		public String getName() {
			return name;
		}

		public Integer getRssi() {
			return rssi;
		}

		final String address;
		final String name;
		final Integer rssi;

		public BleDeviceResult(String address, String name, Integer rssi) {
			this.address = address;
			this.name = name;
			this.rssi = rssi;
		}

		/**
		 * Gets the BLE peripheral for this device result.
		 *
		 * @param adapter The BLE adapter to use
		 * @return The BlePeripheral for this device
		 */
		public BlePeripheral getPeripheral(BleAdapter adapter) {
			return adapter.getPeripheral(address);
		}
	}
}