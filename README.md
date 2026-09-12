# BSToolbox-BLE

Cross-platform Bluetooth Low Energy (BLE) client library for Java (Windows + Linux; macOS sidecar
builds too, but isn't verified against real hardware yet - see "Status" below). Effectively a Java
wrapper around [btleplug](https://github.com/deviceplug/btleplug) — all the actual platform-level
BLE work (WinRT/BlueZ/CoreBluetooth) happens there, via the `ble-bridge` Rust sidecar; this library
adds the Java API, the sidecar process/IPC boundary, and a handful of Windows-specific workarounds
for gaps in `btleplug`'s own Windows backend (see "Platform backends" below).

## Why a sidecar process instead of a native binding

Every existing cross-platform Java BLE binding (TinyB, SimpleBLE's Java binding, ...) runs the
native BLE stack **in-process** via JNI. That means a fault on the native side — which BLE stacks
are prone to, especially around pairing/connect — takes the whole JVM down with it. This library
instead drives BLE through a small, separately-built **Rust sidecar process** (`ble-bridge`,
built on [btleplug](https://github.com/deviceplug/btleplug)) and talks to it over newline-delimited
JSON on stdin/stdout. If the sidecar crashes or hangs, that surfaces as a normal
`BleSidecarException` / `DisconnectListener` callback — never a JVM crash.

It also sidesteps SimpleBLE's BUSL-1.1 licensing (commercial use requires a paid license);
`btleplug` is MIT/Apache-2.0.

**Pairing/bonding is not implemented yet — planned.** Today, if a peripheral's GATT requires
OS-level bonding (MITM protection, a PIN prompt, etc), that has to happen through the OS's own
Bluetooth settings/system pairing prompt before this library can connect to it; neither `btleplug`
nor a from-scratch pairing implementation is wired up here, and `connect()` against an
unpaired-but-bonding-required device will simply fail with a clear error. That's acceptable for now
(most GATT operations don't need it - confirmed a plain unencrypted service connects and works
fine, unpaired, with no OS interaction at all), but **programmatic pairing is a desired feature for
a future release**: triggering pairing from this library and auto-supplying a known PIN/passkey,
without the OS's system prompt appearing at all. Needed for peripherals that require authenticated
bonding just to expose their GATT service (e.g. Niimbot label printers), where popping OS UI isn't
acceptable for an unattended/embedded caller. Would need a new sidecar command wrapping, per
platform: Windows - `DeviceInformationPairing.PairAsync` with a custom pairing handler that answers
a `PairingRequested` event with the PIN automatically instead of surfacing a system prompt; Linux -
BlueZ's D-Bus `Pair()` method with an agent registered to auto-respond with the PIN.

## Platform backends

On Linux and macOS, every operation (scan/connect/disconnect/discover/read/write/subscribe) goes
through `btleplug` directly.

On Windows, connect/disconnect go through `btleplug`; discover/read/write/subscribe/unsubscribe
are implemented directly against WinRT in `ble-bridge/src/win_gatt.rs`, bypassing `btleplug`'s
Windows GATT layer entirely. Reason: that layer can leave an abandoned WinRT operation in a state
that blocks every later GATT call on the same device object, with no way out via retries or
timeouts (see [btleplug#325](https://github.com/deviceplug/btleplug/issues/325)). `win_gatt.rs`
avoids this by resolving each service via a dedicated `BluetoothLEDevice` / `GattDeviceService`
pair — cached and reused for every characteristic on that service, since two independent handles
to the same service open at once make Windows reject GATT access outright — and looking up each
characteristic by UUID on demand rather than enumerating the whole GATT profile.

Scan on Windows is `btleplug`'s own watcher *plus* a second, independent one `win_gatt.rs` runs
alongside it: `btleplug`'s Windows watcher hardcodes `SetAllowExtendedAdvertisements(true)` and
`SetUseCodedPhy(true)`, with no way to disable either via its public API, and at least one real
peripheral has been observed to become entirely invisible to scan once
`AllowExtendedAdvertisements` is enabled - not filtered, never reported at all, even by an
otherwise-identical *unfiltered* scan (filed upstream as
[btleplug#472](https://github.com/deviceplug/btleplug/issues/472), unfixed as of 0.13). The
supplementary watcher skips both settings, applies the same caller-requested service-UUID filter
`btleplug`'s own watcher gets (software-side, same two-step logic `btleplug` 0.13 itself uses), and
reports whatever it sees directly; a device both watchers see is just reported twice, which every
caller already handles (repeated advertisements within one scan behave the same way). `Connect`
gets the matching fix on the other end: if a peripheral was never discovered via either scan
watcher, `get_peripheral` falls back to `Central::add_peripheral()` (new in `btleplug` 0.13,
Windows-supported) to reach it by address anyway. `readRssi()` gets a third, matching fix: the
supplementary watcher also caches the latest RSSI per address it observes, used as a fallback when
`btleplug`'s own value (which needs its own scan to have cached one) is unavailable.

Read `win_gatt.rs`'s module doc comment for the full reasoning on both workarounds before touching
that file.

## Modules

- `ble-bridge/` — the Rust sidecar (`cargo build --release`). Prebuilt binaries for each
  supported OS/arch are bundled into the Java jar's resources at
  `src/main/resources/native/<os>-<arch>/ble-bridge[.exe]`. `src/main.rs` holds the wire protocol
  and the `btleplug`-backed implementation used on every platform; `src/win_gatt.rs` is the
  Windows-only direct-WinRT path described above.
- `src/main/java/cz/bliksoft/javautils/ble/` — the Java client library
  (`cz.bliksoft.java:common-java-utils-ble`).

## Usage

```java
try (BleAdapter adapter = new BleAdapter()) {
    adapter.scan(new ScanFilter(), 5000, (address, name, rssi) ->
        System.out.println(address + " " + name + " rssi=" + rssi));

    BlePeripheral peripheral = adapter.getPeripheral("AA:BB:CC:DD:EE:FF");
    peripheral.setDisconnectListener(reason -> System.out.println("disconnected: " + reason));
    peripheral.connect();

    for (BleService service : peripheral.discoverServices()) {
        System.out.println(service);
    }

    peripheral.subscribe(SERVICE_UUID, RX_CHAR_UUID, (charUuid, value) ->
        System.out.println("notification " + charUuid + " = " + java.util.Arrays.toString(value)));
    peripheral.writeCharacteristic(SERVICE_UUID, TX_CHAR_UUID, someBytes, true);
}
```

### Scanning for devices

Scan for nearby peripherals for a specified timeout. Call `adapter.stopScan()` from within the listener to stop early:

```java
adapter.scan(new ScanFilter(), 5000, (address, name, rssi) -> {
    System.out.println(address + " " + name + " rssi=" + rssi);
    // Optionally stop scanning early when you find what you need
    // adapter.stopScan();
});

// Or with a service UUID filter:
adapter.scan(new ScanFilter().withServiceUuid("1234"), 5000, listener);
```

### Connecting to devices

**Important:** A peripheral must be discovered via `scan()` on the **same** `BleAdapter` instance before you can connect to it.

**Fast reconnection to a known device:** If you previously discovered a device via `scan()` on this adapter (and it's still in range), you can reconnect directly using its address:

```java
// Get handle to a previously discovered device and reconnect
BlePeripheral peripheral = adapter.getPeripheral("AA:BB:CC:DD:EE:FF");
peripheral.connect();
```

**Connect to an unknown device:** Use `BleUtils.findOne()` to scan and connect in one call. This method scans for devices until timeout, then filters results by substring match:

```java
// Find by substring match (scans until timeout)
BleDeviceResult result = BleUtils.findOne(adapter, null, "MyDevice", 10000);

// Or find by partial address match
result = BleUtils.findOne(adapter, null, "AA:BB:CC", 10000);

BlePeripheral peripheral = result.getPeripheral(adapter);
peripheral.connect();
```

For exact match with auto-stop, use the `match` parameter in `scan()` directly:

```java
// Scan stops immediately when exact match found (case-insensitive, full string)
List<BleDeviceResult> results = BleUtils.scan(adapter, null, 10000, "AA:BB:CC:DD:EE:FF");
// or
List<BleDeviceResult> results = BleUtils.scan(adapter, null, 10000, "MyDevice");
```

**Find by partial match:** For partial address/name matches, use `BleUtils.find()` - this scans for the full duration and filters results:

```java
// Find all devices matching partial address (e.g., first 3 pairs)
List<BleDeviceResult> matches = BleUtils.find(adapter, null, "AA:BB:CC", 10000);
```

**Note:** Each `BleAdapter` has its own independent scan cache. A peripheral must be discovered via `scan()` on a specific adapter instance before you can connect to it using that same adapter's `getPeripheral()`. Scanning on one adapter does not make devices connectable via a different adapter instance.

### Utility class: BleUtils

The `cz.bliksoft.javautils.ble.utils.BleUtils` class provides common BLE operations:

| Method | Description |
|--------|-------------|
| `scan(adapter)` / `scan(adapter, filter)` / `scan(adapter, timeoutMs)` | Scan for peripherals, return unique devices (deduplicates by address, prefers non-null names) |
| `scan(adapter, filter, timeoutMs, match)` | Scan with optional exact match - if `match` is provided, scan stops immediately when a device's address or name exactly matches (case-insensitive) |
| `find(adapter, filter, searchTerm)` / `find(adapter, filter, searchTerms)` | Search for devices by address or name (case-insensitive substring match); scans until timeout |
| `findOne(adapter, filter, searchTerm)` | Get a single device matching by substring; throws if 0 or multiple matches; scans until timeout |
| `BleDeviceResult.getPeripheral(adapter)` | Get a `BlePeripheral` handle from a scan result |

Example:

```java
// Scan and get all unique devices
List<BleDeviceResult> devices = BleUtils.scan(adapter, 10000);

// Find devices by name or address
List<BleDeviceResult> matches = BleUtils.find(adapter, null, "MyDevice", 10000);

// Get exactly one matching device
BleDeviceResult result = BleUtils.findOne(adapter, null, "ABC123", 5000);
BlePeripheral peripheral = result.getPeripheral(adapter);
```

### Scan filter

Use `ScanFilter` to limit scan results to a specific service UUID:

```java
ScanFilter filter = new ScanFilter().withServiceUuid("12345678-1234-5678-1234-567890123456");
adapter.scan(filter, 5000, listener);
```

### Complete examples

**Fast reconnect to a known device (previously scanned):**
```java
try (BleAdapter adapter = new BleAdapter()) {
    // Fast reconnect - device must have been discovered in a previous scan on this adapter
    BlePeripheral peripheral = adapter.getPeripheral("AA:BB:CC:DD:EE:FF");
    peripheral.setDisconnectListener(reason -> System.out.println("disconnected: " + reason));
    peripheral.connect();
    
    for (BleService service : peripheral.discoverServices()) {
        System.out.println(service);
    }
    
    byte[] value = peripheral.readCharacteristic(SERVICE_UUID, CHARACTERISTIC_UUID);
    peripheral.writeCharacteristic(SERVICE_UUID, CHARACTERISTIC_UUID, new byte[]{0x01}, true);
}
```

**Connect to an unknown device (scan and connect):**
```java
try (BleAdapter adapter = new BleAdapter()) {
    // Find a device by substring match (scan runs until timeout)
    BleDeviceResult result = BleUtils.findOne(adapter, null, "MyDevice", 10000);
    BlePeripheral peripheral = result.getPeripheral(adapter);
    
    peripheral.connect();
    
    // Use the peripheral...
    for (BleService service : peripheral.discoverServices()) {
        System.out.println(service);
    }
}

// Or for exact match with auto-stop:
try (BleAdapter adapter = new BleAdapter()) {
    List<BleDeviceResult> results = BleUtils.scan(adapter, null, 10000, "AA:BB:CC:DD:EE:FF");
    if (!results.isEmpty()) {
        BlePeripheral peripheral = results.get(0).getPeripheral(adapter);
        peripheral.connect();
    }
}
```

### Connection-quality / diagnostic API

Beyond the generic GATT surface above, `BleAdapter`/`BlePeripheral` also expose:

```java
adapter.getAdapterState();               // AdapterState.POWERED_ON / POWERED_OFF / UNKNOWN
peripheral.getMtu();                     // negotiated ATT MTU in bytes
peripheral.readRssi();                   // current signal strength in dBm
peripheral.getConnectionParameters();    // ConnectionParameters (interval/latency/supervision timeout), or null
peripheral.requestConnectionParameters(ConnectionParameterPreset.THROUGHPUT_OPTIMIZED); // advisory - read
                                          // getConnectionParameters() afterward to see what actually took effect
```

Backed by trait-default `btleplug::api::Peripheral`/`Central` methods that return `NotSupported` on
backends that don't implement them - confirmed working on Windows for all five; other platforms
untested. `readRssi()` on Windows additionally falls back to the supplementary scan watcher's own
RSSI cache (see below) for a peripheral reached only via `add_peripheral()`, since `btleplug`'s own
value depends on its own scan having cached one - which never happens in that case.

## Building the sidecar

`.github/workflows/ble-bridge-build.yml` builds `ble-bridge` for every supported OS/arch
(Windows, Linux x86_64/aarch64/armv7 - e.g. Raspberry Pi, macOS x86_64/aarch64) and uploads a
`ble-bridge-native-resources` artifact laid out ready to drop into `src/main/resources/native/`.
Run it via `workflow_dispatch` or a push touching `ble-bridge/**`.

To build a single target locally instead:

```bash
cd ble-bridge
cargo build --release --target x86_64-pc-windows-msvc
cargo build --release --target x86_64-unknown-linux-gnu
```

Copy the resulting binary into `src/main/resources/native/<os>-<arch>/ble-bridge[.exe]` (e.g.
`native/win-x86_64/ble-bridge.exe`, `native/linux-x86_64/ble-bridge`) before building the Java
jar. `NativeBinaryLoader` resolves that path from the JVM's `os.name`/`os.arch` at runtime.

## Status

Verified end-to-end on Windows and Linux against real peripherals: scan, connect, discover,
subscribe, read and write all work, including reconnect after a manual disconnect. Confirmed
against two independent devices with different GATT profiles - a custom-service e-paper display
(CrowPanel) and a Nordic UART Service-based MeshCore radio - the latter specifically to shake out
the Windows scan/discovery gaps described above (the radio was invisible to scan and
unconnectable before the `win_gatt.rs` supplementary watcher and `add_peripheral()` fallback).
macOS has no automated or manual verification yet — the sidecar builds for it, but nothing has
exercised it against real hardware.

The public API is deliberately generic GATT-level (scan/connect/discover/read/write/subscribe) —
no assumptions about any particular peripheral or protocol.

## Credits

This library exists to give Java the same cross-platform BLE support
[btleplug](https://github.com/deviceplug/btleplug) already gives Rust — nearly everything at the
protocol/OS level (scanning, connecting, GATT) is `btleplug` doing the actual work behind
`ble-bridge`; see "Platform backends" above for the specific Windows-only gaps this library works
around on top of it, several [reported upstream](https://github.com/deviceplug/btleplug/issues/472).
Currently pinned to `btleplug` 0.13 (`ble-bridge/Cargo.toml`).

## License

LGPL-2.1-or-later, see `LICENSE`. `ble-bridge` (the Rust sidecar) is a separate executable
communicating over stdio, not linked into consuming applications, so its own dependencies'
licenses (MIT/Apache-2.0 via `btleplug` and friends) don't propagate to them.
