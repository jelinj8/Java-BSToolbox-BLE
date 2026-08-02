# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`cz.bliksoft.java:common-java-utils-ble` — a cross-platform BLE client library for Java. Instead of
an in-process JNI binding, it drives BLE through a bundled Rust sidecar process (`ble-bridge`, built
on `btleplug`) over newline-delimited JSON on stdin/stdout. This means a native/driver-level BLE
fault (common around pairing/connect) surfaces as a `BleSidecarException` or a
`DisconnectListener` callback with reason `sidecar_crashed` — never a JVM crash. See README.md for
the full rationale (including why SimpleBLE's BUSL-1.1 license was avoided) and pairing/bonding's
explicit out-of-scope status.

Two modules:
- `ble-bridge/` — the Rust sidecar (Cargo project, `cargo build --release`).
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
  `discover_services`, `read`, `write`, `subscribe`, `unsubscribe`), each carrying an `id` used to
  match it to its response. See the `Command` enum in `ble-bridge/src/main.rs` and the
  `BleAdapter.sendRequest` overloads for the canonical field lists.
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

## Release process

Use the `prepare-maven-release` / `deploy-maven-release` / `deploy-maven-local` skills for
versioning and publishing — don't hand-edit the `<revision>` property or run `mvn deploy` directly.
Note `.forgejo/workflows/` files are the source of truth on GitHub (Forgejo pull-mirrors this repo);
a commit made only on the Forgejo side would be silently discarded on the next sync.
