package cz.bliksoft.javautils.ble;

import java.util.Collections;
import java.util.List;

public class BleService {

	private final String uuid;
	private final List<BleCharacteristic> characteristics;

	public BleService(String uuid, List<BleCharacteristic> characteristics) {
		this.uuid = uuid;
		this.characteristics = Collections.unmodifiableList(characteristics);
	}

	public String getUuid() {
		return uuid;
	}

	public List<BleCharacteristic> getCharacteristics() {
		return characteristics;
	}

	@Override
	public String toString() {
		return uuid + characteristics;
	}
}
