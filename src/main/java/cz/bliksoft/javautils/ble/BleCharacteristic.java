package cz.bliksoft.javautils.ble;

import java.util.Collections;
import java.util.List;

public class BleCharacteristic {

	private final String uuid;
	private final List<String> properties;

	public BleCharacteristic(String uuid, List<String> properties) {
		this.uuid = uuid;
		this.properties = Collections.unmodifiableList(properties);
	}

	public String getUuid() {
		return uuid;
	}

	/** e.g. {@code READ}, {@code WRITE}, {@code WRITE_WITHOUT_RESPONSE}, {@code NOTIFY}, {@code INDICATE}. */
	public List<String> getProperties() {
		return properties;
	}

	@Override
	public String toString() {
		return uuid + properties;
	}
}
