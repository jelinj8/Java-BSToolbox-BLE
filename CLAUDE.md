# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`cz.bliksoft.java:common-java-utils-ble` — a cross-platform BLE client library for Java. Instead of
an in-process JNI binding, it drives BLE through a bundled Rust sidecar process (`ble-bridge`, built
on `btleplug`) over newline-delimited JSON on stdin/stdout. This means a native/driver-level BLE
fault (common around pairing/connect) surfaces as a `BleSidecarException` or a
`DisconnectListener` callback with reason `sidecar_crashed` — never a JVM crash. See README.md for
the full rationale (including why SimpleBLE's BUSL-1.1 license was avoided) and pairing/bonding's
current status (works via the OS's own system prompt today; programmatic pairing - no system
prompt, PIN supplied automatically - is planned but not implemented, see "Planned" below).

Two modules:
- `ble-bridge/` — the Rust sidecar (Cargo project, `cargo build --release`, `btleplug` 0.13).
  `main.rs` holds the wire protocol and the `btleplug`-backed implementation used on every
  platform; `win_gatt.rs` is Windows-only and works around three separate, unrelated `btleplug`/
  WinRT gaps:
  - A direct-WinRT GATT path (discover/read/write/subscribe/unsubscribe only — connect/disconnect
    stay on `btleplug`) that works around a bug where an abandoned WinRT operation can permanently
    block every later GATT call on the same device object.
  - A second, unfiltered `BluetoothLEAdvertisementWatcher` run alongside `btleplug`'s own scan
    watcher, forwarding every peripheral it sees independently — `btleplug`'s Windows watcher
    hardcodes `SetAllowExtendedAdvertisements(true)`/`SetUseCodedPhy(true)` with no way to disable
    either, and at least one real peripheral has been observed to become entirely invisible to
    scan once that's enabled (filed upstream as
    [btleplug#472](https://github.com/deviceplug/btleplug/issues/472), unfixed as of 0.13).
    `main.rs`'s `get_peripheral` pairs this with a `Central::add_peripheral()` fallback (new in
    0.13, Windows-supported) so `Connect` can still reach such a peripheral by address even though
    `btleplug`'s own scan never discovered it.
  - `resolve_service`'s UUID-scoped `GetGattServicesForUuidAsync` needs a prior broad, unscoped
    `GetGattServicesAsync` to have completed at least once per connection before it reliably
    succeeds — without it, the first `read`/`write`/`subscribe` right after `connect()` can fail
    even though the peripheral is genuinely connected (confirmed on real hardware: `subscribe()`
    on a MeshCore radio reliably failed a few times in a row immediately post-connect).
    `ensure_services_discovered` runs that broad discovery once per address (cached in
    `DISCOVERED`) before the first scoped query, so callers don't need to call
    `discoverServices()` themselves for this to work.

  See `win_gatt.rs`'s module doc comment and README.md before touching any of these paths.
- `src/main/java/cz/bliksoft/javautils/ble/` — the Java client library.

## Build / test commands

Java (from repo root):
```bash
mvn verify                 # compile + test the Java library
mvn formatter:format       # apply src/formatter/EclipseFormatter.xml
```

Rust sidecar (from `ble-bridge/`):
```bash
cargo build --release --target x86_64-pc-windows-msvc
cargo build --release --target x86_64-unknown-linux-gnu
```

The Java build does **not** compile the Rust sidecar itself. Prebuilt sidecar binaries must be
staged into `src/main/resources/native/<os>-<arch>/ble-bridge[.exe]` (e.g.
`native/win-x86_64/ble-bridge.exe`, `native/linux-x86_64/ble-bridge`) before the Java jar will have
a working sidecar to extract at runtime — `NativeBinaryLoader` looks up that exact resource path
based on `os.name`/`os.arch` (32-bit ARM reports inconsistently across JVMs, so it's normalized to
`armv7` there).

`.github/workflows/ble-bridge-build.yml` (GitHub-hosted runners, separate from the Forgejo
workflows below) builds every supported target — win-x86_64, linux-x86_64/aarch64/armv7 (the
ARM targets cover Raspberry Pi and similar SBCs, cross-compiled via `cross` since there's no
hosted ARM Linux runner), mac-x86_64/aarch64 — and uploads a `ble-bridge-native-resources`
artifact already laid out to drop into `src/main/resources/native/`. It doesn't commit anything
back to the repo; staging the downloaded artifact there is still a manual step, and per-target ARM
cross-compilation (the `dbus` "vendored" feature pinned in `ble-bridge/Cargo.toml`) hasn't been
validated against a real run yet — if it fails, check whether the pinned `dbus` version there
still matches what btleplug's Linux backend depends on.

## Architecture: the wire protocol

`BleAdapter` (the library entry point) launches one `ble-bridge` process per instance and is the
only thing that talks to its stdin/stdout. The protocol is one JSON object per line in both
directions:

- **Commands** (Java → Rust): tagged by a `cmd` field (`scan`, `stop_scan`, `connect`, `disconnect`,
  `discover_services`, `read`, `write`, `subscribe`, `unsubscribe`, `pair`, plus the connection-
  quality/diagnostic group `adapter_state`, `read_rssi`, `get_mtu`, `get_connection_parameters`,
  `request_connection_parameters`), each carrying an `id` used to match it to its response. See the
  `Command` enum in `ble-bridge/src/main.rs` and the `BleAdapter.sendRequest` overloads for the
  canonical field lists. The diagnostic group is all backed by trait-default
  `btleplug::api::Peripheral`/`Central` methods that return `Err(NotSupported)` where a backend
  doesn't implement them - confirmed working on Windows for all five; other platforms untested.
  `read_rssi` on Windows additionally falls back to `win_gatt.rs`'s supplementary-watcher RSSI
  cache when `btleplug`'s own value is unavailable - see that module's doc comment. `pair` is
  implemented on Windows and Linux; not possible on macOS at all (permanent platform limitation,
  not a gap) - see "Programmatic pairing" below.
- **Responses** (Rust → Java): `{"type": "response", "id": ..., "ok": bool, ...}`. `BleAdapter`
  keeps a `CompletableFuture` per in-flight `id` in `pending`, resolved or failed by
  `handleResponse` when the matching line arrives.
- **Events** (Rust → Java, unsolicited): `device_found` (scan results), `notification`
  (characteristic value changes), `connected`/`disconnected`, `fatal`/`error`. Dispatched by
  `BleAdapter.dispatch` to the current `BleScanListener` or the relevant cached `BlePeripheral`.

Binary characteristic values are always hex-encoded on the wire (lowercase, no separators) — see
`HexCodec.java` on the Java side and `hex_encode`/`hex_decode` in `main.rs` on the Rust side. Keep
both sides in sync if this encoding ever changes.

Changing the protocol means editing **both** sides: the `Command` enum + response-building code in
`ble-bridge/src/main.rs`, and the corresponding `sendRequest`/`dispatch` logic in `BleAdapter.java`
(plus the JSON field parsing in `BlePeripheral.java` methods that call it).

## Architecture: Java-side concurrency

- `BleAdapter` runs three daemon threads per instance: `readLoop` (parses sidecar stdout, dispatches
  responses/events), `stderrLoop` (drains sidecar stderr to logs), and `watchExit` (detects sidecar
  process death and fails all pending requests + fires `sidecar_crashed` disconnects on every cached
  `BlePeripheral`).
- `BlePeripheral` instances are cached per-address in `BleAdapter.peripherals` for the adapter's
  lifetime (`getPeripheral` is the only way to obtain one), so listeners registered on one instance
  keep receiving events across reconnects.
- One `BleAdapter` = one sidecar process = one BLE session. `close()` closes the sidecar's stdin,
  waits up to 3s, then force-kills it, and fails any still-pending requests.

## Programmatic pairing

A peripheral that requires OS-level bonding to expose its GATT service can be paired
programmatically - no OS Bluetooth-settings/system prompt - via a new `"pair"` sidecar command and
`BlePeripheral.pair(String pin)` on the Java side. Supplies a known PIN/passkey automatically the
moment the peripheral requests one; the peripheral must already be connected (a prior `connect()`).
Peripherals that don't need bonding at all continue to work with zero OS interaction, unpaired, as
before - this only matters for ones that do (e.g. Niimbot label printers, a planned future consumer
of this library, are expected to need authenticated bonding just to talk to them).

- **Windows** (implemented, 2026-09-20): `win_gatt.rs`'s `pair` function, built on the same
  `BluetoothLEDevice`/`DeviceInformation` resolution pattern the rest of that module uses -
  `DeviceInformationPairing::Custom()` + a `PairingRequested` handler answering with
  `AcceptWithPin(pin)` (or a bare `Accept()` for a `ConfirmOnly` request), then
  `PairAsync(ProvidePin | ConfirmOnly)`. Verified end-to-end on real hardware against a real
  PIN-protected peripheral (a MeshCore radio) - succeeded on the first attempt, both driven
  directly via `ble-bridge.exe`'s own stdin/stdout and via the ESP32-C6 remote-bridge firmware's
  independent NimBLE-based implementation of the same wire command.
- **Linux** (implemented, 2026-09-20): `linux_pair.rs` registers our own `org.bluez.Agent1` D-Bus
  object directly (`dbus`/`dbus-tokio`/`dbus-crossroads`, plus `bluez-generated`'s
  `OrgBluezAgentManager1`/`OrgBluezDevice1` proxies - all already in the dependency graph
  transitively via `bluez-async`, `btleplug`'s own Linux backend, which has no pairing support of
  its own at all beyond a bare `Device1::Pair()` with no way to supply a PIN). Answers
  `RequestPasskey`/`RequestPinCode` with whichever PIN the in-flight `pair()` call supplied
  (looked up by BT address, parsed back out of the D-Bus device object path - `PENDING_PINS`),
  registered with `KeyboardOnly` capability (same idea as Windows' `ProvidePin` / the ESP32
  firmware's `BLE_HS_IO_KEYBOARD_ONLY`). Verified end-to-end on a real Raspberry Pi CM5 (Debian
  13, BlueZ 5.82) against a real PIN-protected peripheral (a MeshCore radio) - succeeded on the
  first real attempt after one compile-error fix, confirmed bonded at the system level afterward
  (`bluetoothctl info <address>` showing `Paired: yes`/`Bonded: yes`), with zero prompt of any kind
  on this headless machine.
- **macOS**: not possible, permanently, via any public API - not a "not yet". CoreBluetooth has no
  way for an app to supply a BLE pairing passkey/PIN programmatically (unlike Android's
  `BluetoothDevice.setPin()`, or the Windows/Linux APIs this project already uses), and no way to
  suppress or intercept the system's own pairing prompt - confirmed repeatedly on Apple's own
  developer forums (e.g. `https://developer.apple.com/forums/thread/703663`), with no workaround
  offered; Apple keeps BLE bonding entirely inside `bluetoothd`/system UI by design. `platform_pair`
  on macOS returns a clear, permanent "not supported" error rather than "not implemented yet" -
  don't spend time trying to close this gap without first finding an actual new Apple API. No
  Mac has been available to test any of this locally - `ble-bridge-build.yml`'s existing
  `aarch64-apple-darwin`/`x86_64-apple-darwin` matrix entries are the only current build coverage.

Wire shape: `{"cmd":"pair","id":...,"address":...,"pin":...}`, response `{"type":"response",
"id":...,"ok":bool[,"error":...]}` - same shape independently implemented by the ESP32-C6
remote-bridge firmware (`firmware-BSBleRemoteBridge`), which got there first; this sidecar
implementation matches it deliberately.

## Release process

Use the `prepare-maven-release` / `deploy-maven-release` / `deploy-maven-local` skills for
versioning and publishing — don't hand-edit the `<revision>` property or run `mvn deploy` directly.
Note `.forgejo/workflows/` files are the source of truth on GitHub (Forgejo pull-mirrors this repo);
a commit made only on the Forgejo side would be silently discarded on the next sync.
