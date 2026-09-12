/**
 * BLE client library - cross-platform Bluetooth Low Energy support for Java.
 * <p>
 * This package provides the main API for interacting with BLE peripherals. The
 * library drives BLE through a bundled Rust sidecar process
 * ({@code ble-bridge}) that uses {@code btleplug} for platform-level
 * operations.
 * <p>
 * Key classes:
 * <ul>
 * <li>{@link cz.bliksoft.javautils.ble.BleAdapter} - Entry point for BLE
 * operations</li>
 * <li>{@link cz.bliksoft.javautils.ble.BlePeripheral} - Handle to a specific
 * BLE device</li>
 * <li>{@link cz.bliksoft.javautils.ble.ScanFilter} - Filter for scanning</li>
 * <li>{@link cz.bliksoft.javautils.ble.utils.BleUtils} - Utility methods for
 * common operations</li>
 * </ul>
 * <p>
 * Usage example:
 *
 * <pre>{@code
 * try (BleAdapter adapter = new BleAdapter()) {
 * 	// Scan for devices
 * 	adapter.scan(new ScanFilter(), 5000, (address, name, rssi) -> System.out.println(address + " " + name));
 *
 * 	// Connect to a known device
 * 	BlePeripheral peripheral = adapter.getPeripheral("AA:BB:CC:DD:EE:FF");
 * 	peripheral.connect();
 *
 * 	// Discover services and characteristics
 * 	for (BleService service : peripheral.discoverServices()) {
 * 		System.out.println("Service: " + service);
 * 	}
 *
 * 	// Read/write/subscribe to characteristics
 * 	byte[] value = peripheral.readCharacteristic(SERVICE_UUID, CHARACTERISTIC_UUID);
 * 	peripheral.writeCharacteristic(SERVICE_UUID, CHARACTERISTIC_UUID, new byte[] { 0x01 }, true);
 * 	peripheral.subscribe(SERVICE_UUID, CHARACTERISTIC_UUID,
 * 			(uuid, data) -> System.out.println("Notification: " + Arrays.toString(data)));
 * }
 * }</pre>
 * <p>
 * For more examples, including using
 * {@link cz.bliksoft.javautils.ble.utils.BleUtils} for device discovery and
 * connection patterns, see the project README.
 */
package cz.bliksoft.javautils.ble;
