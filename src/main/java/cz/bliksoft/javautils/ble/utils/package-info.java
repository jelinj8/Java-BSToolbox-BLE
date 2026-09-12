/**
 * Utility classes for common BLE operations.
 * <p>
 * The {@link cz.bliksoft.javautils.ble.utils.BleUtils} class provides
 * convenient methods for scanning, searching, and filtering BLE devices. It's
 * designed to simplify common BLE workflows like finding a specific device by
 * name or address, or scanning for all nearby devices.
 * <p>
 * Key classes:
 * <ul>
 * <li>{@link cz.bliksoft.javautils.ble.utils.BleUtils} - Utility methods for
 * BLE operations</li>
 * <li>{@link cz.bliksoft.javautils.ble.utils.BleUtils.BleDeviceResult} -
 * Container for scan results</li>
 * </ul>
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * try (BleAdapter adapter = new BleAdapter()) {
 * 	// Scan for all devices
 * 	List<BleDeviceResult> devices = BleUtils.scan(adapter, 10000);
 *
 * 	// Find a specific device by name
 * 	BleDeviceResult result = BleUtils.findOne(adapter, null, "MyDevice", 5000);
 * 	BlePeripheral peripheral = result.getPeripheral(adapter);
 * 	peripheral.connect();
 *
 * 	// Find multiple devices matching a search term
 * 	List<BleDeviceResult> matches = BleUtils.find(adapter, null, "ABC", 10000);
 * }
 * }</pre>
 */
package cz.bliksoft.javautils.ble.utils;
