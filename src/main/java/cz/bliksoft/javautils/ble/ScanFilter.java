package cz.bliksoft.javautils.ble;

import java.util.Locale;

/**
 * Optional narrowing for {@link BleAdapter#scan}. Leave every field null to see
 * everything.
 * <p>
 * {@link #withAddress} / {@link #withName} apply an exact (case-insensitive)
 * match; {@link #withMatchingAddress} / {@link #withMatchingName} apply a
 * substring (case-insensitive, "contains") match. When more than one of these
 * four criteria is configured, a device is considered a match if it satisfies
 * <em>any</em> of them (logical OR) - there is no way to require several
 * criteria to hold simultaneously.
 * <p>
 * {@link BleAdapter#scan} evaluates this filter itself, once per raw
 * {@code device_found} event - not just once per address - because a device's
 * advertised name is sometimes only revealed by a later scan-response packet
 * for an address already seen without a name. A non-matching first
 * advertisement for an eventually-matching device is therefore just dropped,
 * not treated as a permanent rejection of that address.
 * <p>
 * When an <em>exact</em> criterion ({@link #withAddress} or {@link #withName})
 * is satisfied, {@link BleAdapter#scan} stops the scan automatically right
 * after delivering that event. Substring criteria never trigger this auto-stop,
 * since callers relying on them (e.g. a fulltext device search) expect every
 * match found during the full scan window.
 */
public class ScanFilter {

	private String serviceUuid;
	private String exactAddress;
	private String exactName;
	private String containsAddress;
	private String containsName;

	public String getServiceUuid() {
		return serviceUuid;
	}

	public ScanFilter withServiceUuid(String serviceUuid) {
		this.serviceUuid = serviceUuid;
		return this;
	}

	public String getAddress() {
		return exactAddress;
	}

	/** Exact (case-insensitive) address match. */
	public ScanFilter withAddress(String address) {
		this.exactAddress = address;
		return this;
	}

	public String getMatchingAddress() {
		return containsAddress;
	}

	/** Substring (case-insensitive) address match. */
	public ScanFilter withMatchingAddress(String addressSubstring) {
		this.containsAddress = addressSubstring;
		return this;
	}

	public String getName() {
		return exactName;
	}

	/** Exact (case-insensitive) name match. */
	public ScanFilter withName(String name) {
		this.exactName = name;
		return this;
	}

	public String getMatchingName() {
		return containsName;
	}

	/** Substring (case-insensitive) name match. */
	public ScanFilter withMatchingName(String nameSubstring) {
		this.containsName = nameSubstring;
		return this;
	}

	/**
	 * True if no address/name criteria are configured (a plain discovery filter,
	 * e.g. {@code serviceUuid}-only or entirely empty) - such a filter matches
	 * every device.
	 */
	public boolean hasAddressOrNameCriteria() {
		return exactAddress != null || exactName != null || containsAddress != null || containsName != null;
	}

	/**
	 * True if {@code address}/{@code name} satisfy at least one configured
	 * address/name criterion (logical OR), or unconditionally true if none are
	 * configured. {@code name} may be {@code null}.
	 */
	public boolean matches(String address, String name) {
		if (!hasAddressOrNameCriteria()) {
			return true;
		}
		String normAddress = normalize(address);
		String normName = normalize(name);
		if (exactAddress != null && normalize(exactAddress).equals(normAddress)) {
			return true;
		}
		if (exactName != null && normalize(exactName).equals(normName)) {
			return true;
		}
		if (containsAddress != null && normAddress.contains(normalize(containsAddress))) {
			return true;
		}
		if (containsName != null && normName.contains(normalize(containsName))) {
			return true;
		}
		return false;
	}

	/**
	 * True if {@code address}/{@code name} satisfy an <em>exact</em> criterion
	 * ({@link #withAddress} or {@link #withName}) - used by {@link BleAdapter} to
	 * auto-stop a scan as soon as the specific device it was looking for is found.
	 * Never true for substring criteria alone.
	 */
	public boolean isExactMatch(String address, String name) {
		if (exactAddress != null && normalize(exactAddress).equals(normalize(address))) {
			return true;
		}
		if (exactName != null && normalize(exactName).equals(normalize(name))) {
			return true;
		}
		return false;
	}

	private static String normalize(String s) {
		return s != null ? s.toUpperCase(Locale.ROOT).trim() : "";
	}
}
