package cz.bliksoft.javautils.ble;

/**
 * Optional narrowing for {@link BleAdapter#scan}. Leave {@link #serviceUuid}
 * null to see everything.
 */
public class ScanFilter {

	private String serviceUuid;

	public String getServiceUuid() {
		return serviceUuid;
	}

	public ScanFilter withServiceUuid(String serviceUuid) {
		this.serviceUuid = serviceUuid;
		return this;
	}
}
