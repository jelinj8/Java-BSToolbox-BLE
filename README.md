# BSToolbox-BLE

Cross-platform Bluetooth Low Energy (BLE) client library for Java (Windows + Linux; macOS
possible but not built/tested here yet).

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

**Pairing/bonding is out of scope.** If a peripheral's GATT requires OS-level bonding (MITM
protection, a PIN prompt, etc), that has to happen through the OS's own Bluetooth settings before
this library can connect to it. Neither `btleplug` nor a from-scratch pairing implementation is
wired up here; `connect()` against an unpaired-but-bonding-required device will simply fail with a
clear error.

**Windows GATT path bypasses btleplug entirely.** `discover_services`/read/write/subscribe/
unsubscribe were observed hanging indefinitely on Windows shortly after connecting to a bonded
device via btleplug's own Windows backend - a documented btleplug issue (#325): an abandoned
uncached WinRT GATT operation isn't actually cancelled when the Rust future wrapping it is dropped,
and appears to hold an internal lock that queues up every subsequent GATT operation behind it, on
that same long-lived device object, no matter how wide a retry budget it's given. `win_gatt.rs`
works around this on Windows by going straight to WinRT instead: a fresh `BluetoothLEDevice` per
service (cached and shared across every characteristic on that service - two independent proxies
for the same service open at once made Windows reject GATT access with `AccessDenied`, confirmed
against real hardware) and a targeted `GetCharacteristicsForUuidAsync` per characteristic, rather
than enumerating everything. Confirmed end-to-end against real MeshCore hardware: connect +
subscribe + write + full protocol handshake all complete on the first attempt. `connect`/
`disconnect`/scan are unaffected and still go through btleplug there, since they haven't shown this
failure mode. Linux/BlueZ was already confirmed working via btleplug directly (full handshake in
well under a second, no retries) and is unaffected by any of this.

## Modules

- `ble-bridge/` — the Rust sidecar (`cargo build --release`). Prebuilt binaries for each
  supported OS/arch are bundled into the Java jar's resources at
  `src/main/resources/native/<os>-<arch>/ble-bridge[.exe]`. `src/win_gatt.rs` is the Windows-only
  direct-WinRT GATT path described above (everything else goes through btleplug, in `main.rs`).
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

Early scaffolding. The public API is deliberately generic GATT-level
(scan/connect/discover/read/write/subscribe) — no assumptions about any particular peripheral or
protocol.

## License

LGPL-2.1-or-later, see `LICENSE`. `ble-bridge` (the Rust sidecar) is a separate executable
communicating over stdio, not linked into consuming applications, so its own dependencies'
licenses (MIT/Apache-2.0 via `btleplug` and friends) don't propagate to them.
