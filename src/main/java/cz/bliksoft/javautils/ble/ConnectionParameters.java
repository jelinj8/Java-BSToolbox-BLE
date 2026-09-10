package cz.bliksoft.javautils.ble;

/**
 * Current BLE connection parameters as reported by the OS, from
 * {@link BlePeripheral#getConnectionParameters()}.
 */
public class ConnectionParameters {

	private final long intervalUs;
	private final int latency;
	private final long supervisionTimeoutUs;

	public ConnectionParameters(long intervalUs, int latency, long supervisionTimeoutUs) {
		this.intervalUs = intervalUs;
		this.latency = latency;
		this.supervisionTimeoutUs = supervisionTimeoutUs;
	}

	/** Connection interval in microseconds (typically 7_500..4_000_000). */
	public long getIntervalUs() {
		return intervalUs;
	}

	/** Slave latency in number of connection events (0..499). */
	public int getLatency() {
		return latency;
	}

	/** Supervision timeout in microseconds (100_000..32_000_000). */
	public long getSupervisionTimeoutUs() {
		return supervisionTimeoutUs;
	}

	@Override
	public String toString() {
		return "interval=" + (intervalUs / 1000.0) + "ms latency=" + latency + " supervisionTimeout="
				+ (supervisionTimeoutUs / 1000.0) + "ms";
	}
}
