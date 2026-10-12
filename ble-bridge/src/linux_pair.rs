//! Linux-only programmatic pairing via a direct BlueZ D-Bus `Agent1` registration - counterpart
//! to `win_gatt.rs`'s `pair` on Windows (see that function's doc comment and CLAUDE.md's
//! "Programmatic pairing" section for the overall feature).
//!
//! `btleplug`'s Linux backend (`bluez-async`, pulled in transitively) has a `pair()` that just
//! calls `Device1::Pair()` and nothing else - it assumes some other agent (e.g. `bluetoothctl`'s
//! own, or none at all for a "just works" peripheral) is already registered to answer any
//! passkey/PIN request BlueZ needs during that call. There is no way to supply a PIN through it.
//! This module goes around `btleplug` entirely and talks to BlueZ directly, in the same spirit
//! `win_gatt.rs` goes around it on Windows for a different reason: it registers our own
//! `org.bluez.Agent1` object (once, kept alive for the life of the process, same pattern as
//! `win_gatt.rs`'s `SERVICES`/`SESSIONS` statics) that answers `RequestPasskey`/`RequestPinCode`
//! with whichever PIN the current `pair()` call supplied, then calls `Device1::Pair()`.
//!
//! `RequestPasskey`/`RequestPinCode` only carry the *device's D-Bus object path*, not our command
//! id - `PENDING_PINS` (keyed by uppercased BT address, parsed back out of that path) is the
//! hand-off point between `pair()` and the agent's callback, which run as part of the same
//! nonblock D-Bus connection's dispatch, not a separate OS thread.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use dbus::channel::MatchingReceiver;
use dbus::message::MatchRule;
use dbus::nonblock::stdintf::org_freedesktop_dbus::ObjectManager;
use dbus::nonblock::{self, SyncConnection};
use dbus::Path;
use bluez_generated::{OrgBluezAdapter1, OrgBluezAgentManager1, OrgBluezDevice1};
use dbus_crossroads::{Crossroads, IfaceBuilder, MethodErr};
use tokio::sync::OnceCell;

const AGENT_PATH: &str = "/cz/bliksoft/blebridge/agent";
const DBUS_TIMEOUT: Duration = Duration::from_secs(30);

static CONNECTION: OnceCell<Arc<SyncConnection>> = OnceCell::const_new();
static AGENT_REGISTERED: OnceCell<()> = OnceCell::const_new();

/// Address (uppercased "AA:BB:CC:DD:EE:FF") -> the PIN the currently in-flight `pair()` call for
/// it supplied. Only ever holds entries for pairings actually requested through this module - the
/// agent methods below treat "no entry" as "reject this, we didn't ask for it".
static PENDING_PINS: Mutex<Option<HashMap<String, String>>> = Mutex::new(None);

fn address_from_device_path(device: &Path<'static>) -> Option<String> {
	let segment = device.rsplit('/').next()?;
	let hex = segment.strip_prefix("dev_")?;
	Some(hex.replace('_', ":").to_uppercase())
}

fn pin_for_device(device: &Path<'static>) -> Result<String, MethodErr> {
	let address = address_from_device_path(device)
		.ok_or_else(|| MethodErr::failed(&format!("malformed device path: {}", device)))?;
	PENDING_PINS
		.lock()
		.unwrap()
		.as_ref()
		.and_then(|m| m.get(&address))
		.cloned()
		.ok_or_else(|| MethodErr::failed(&format!("no pending pair() request for {}", address)))
}

/// The shared system-bus connection (opened once) - see [`ensure_agent`] for the agent registered
/// on it, which `is_bonded`/`unpair` don't need.
async fn ensure_connection() -> Result<Arc<SyncConnection>, String> {
	CONNECTION
		.get_or_try_init(|| async {
			let (resource, conn) = dbus_tokio::connection::new_system_sync().map_err(|e| e.to_string())?;
			tokio::spawn(async move {
				let err = resource.await;
				eprintln!("[ble-bridge] D-Bus system connection lost: {}", err);
			});
			Ok::<_, String>(conn)
		})
		.await
		.cloned()
}

/// Registers (once) our `Agent1` object and makes it the default agent - see the module doc
/// comment for the overall approach. Returns the shared connection for callers to build their own
/// `nonblock::Proxy`s from.
async fn ensure_agent() -> Result<Arc<SyncConnection>, String> {
	let conn = ensure_connection().await?;
	AGENT_REGISTERED
		.get_or_try_init(|| async {
			let mut cr = Crossroads::new();
			let iface_token = cr.register("org.bluez.Agent1", |b: &mut IfaceBuilder<()>| {
				b.method("RequestPinCode", ("device",), ("pincode",), |_ctx, _data, (device,): (Path<'static>,)| {
					Ok((pin_for_device(&device)?,))
				});
				b.method("RequestPasskey", ("device",), ("passkey",), |_ctx, _data, (device,): (Path<'static>,)| {
					let pin = pin_for_device(&device)?;
					let passkey: u32 =
						pin.trim().parse().map_err(|_| MethodErr::failed("pin is not a valid numeric passkey"))?;
					Ok((passkey,))
				});
				b.method(
					"DisplayPasskey",
					("device", "passkey", "entered"),
					(),
					|_ctx, _data, (_device, _passkey, _entered): (Path<'static>, u32, u16)| Ok(()),
				);
				b.method("DisplayPinCode", ("device", "pincode"), (), |_ctx, _data, (_device, _pincode): (Path<'static>, String)| {
					Ok(())
				});
				// We only ever reach RequestConfirmation/RequestAuthorization/AuthorizeService for a
				// pairing *we* just initiated via pair() below (this agent is only made the default
				// agent, never registered for unsolicited inbound pairing) - accepting unconditionally
				// here is safe under that precondition, not a blanket "trust everything" policy.
				b.method(
					"RequestConfirmation",
					("device", "passkey"),
					(),
					|_ctx, _data, (_device, _passkey): (Path<'static>, u32)| Ok(()),
				);
				b.method("RequestAuthorization", ("device",), (), |_ctx, _data, (_device,): (Path<'static>,)| Ok(()));
				b.method("AuthorizeService", ("device", "uuid"), (), |_ctx, _data, (_device, _uuid): (Path<'static>, String)| {
					Ok(())
				});
				b.method("Cancel", (), (), |_ctx, _data, ()| Ok(()));
				b.method("Release", (), (), |_ctx, _data, ()| Ok(()));
			});
			cr.insert(AGENT_PATH, &[iface_token], ());

			// Export the agent object on the bus BEFORE registering it with BlueZ (see bluez's own
			// agent-api docs) - handle_message below starts doing that from this point on.
			conn.start_receive(
				MatchRule::new_method_call(),
				Box::new(move |msg, conn| {
					cr.handle_message(msg, conn).unwrap();
					true
				}),
			);

			let bluez = nonblock::Proxy::new("org.bluez", "/org/bluez", DBUS_TIMEOUT, conn.clone());
			// KeyboardOnly: this agent can "type in" a passkey/PIN when asked (RequestPasskey/
			// RequestPinCode) - matches the ESP32 firmware's own NimBLE IO-capability choice
			// (BLE_HS_IO_KEYBOARD_ONLY) and Windows' ProvidePin, for the same reason.
			bluez.register_agent(Path::from(AGENT_PATH), "KeyboardOnly").await.map_err(|e| e.to_string())?;
			bluez.request_default_agent(Path::from(AGENT_PATH)).await.map_err(|e| e.to_string())?;

			Ok::<_, String>(())
		})
		.await?;
	Ok(conn)
}

async fn find_adapter_path(conn: &Arc<SyncConnection>) -> Result<Path<'static>, String> {
	let root = nonblock::Proxy::new("org.bluez", "/", DBUS_TIMEOUT, conn.clone());
	let objects = root.get_managed_objects().await.map_err(|e| e.to_string())?;
	objects
		.into_iter()
		.find(|(_, ifaces)| ifaces.contains_key("org.bluez.Adapter1"))
		.map(|(path, _)| path)
		.ok_or_else(|| "no BlueZ adapter found".to_string())
}

fn device_path(adapter_path: &Path<'static>, address: &str) -> Path<'static> {
	Path::from(format!("{}/dev_{}", adapter_path, address.to_uppercase().replace(':', "_")))
}

/// How long [`pair`] waits for BlueZ to report a dropped link before pairing anyway.
const DISCONNECT_WAIT: Duration = Duration::from_secs(5);
/// Pause after the link is down before [`pair`] calls `Pair` - see there.
const DISCONNECT_SETTLE: Duration = Duration::from_secs(1);

pub async fn pair(address: &str, pin: &str) -> Result<(), String> {
	let conn = ensure_agent().await?;
	let adapter_path = find_adapter_path(&conn).await?;
	let device_path = device_path(&adapter_path, address);
	let device = nonblock::Proxy::new("org.bluez", device_path, DBUS_TIMEOUT, conn);

	// The kernel fixes a link's SMP IO capability when the link is created: pairing over a link
	// opened before our KeyboardOnly agent became the default (e.g. connect, then pair) uses the
	// old default (DisplayYesNo), which against a DisplayOnly legacy peripheral (MeshCore radios)
	// means Just Works - an unauthenticated bond such a peripheral then refuses with "Insufficient
	// Authentication". So pair over a fresh link and restore the connection afterwards. An
	// already-paired device skips this (Pair just answers AlreadyExists).
	let reconnect = device.connected().await.unwrap_or(false) && !device.paired().await.unwrap_or(false);
	if reconnect {
		device.disconnect().await.map_err(|e| e.to_string())?;
		let deadline = tokio::time::Instant::now() + DISCONNECT_WAIT;
		while device.connected().await.unwrap_or(false) && tokio::time::Instant::now() < deadline {
			tokio::time::sleep(Duration::from_millis(100)).await;
		}
		// BlueZ handles a disconnect twice - on the link-down event (Connected turns false) and
		// again on the MGMT Disconnect command's completion, tens of ms later - and the second
		// pass cancels any bonding started in between (Pair fails with AuthenticationCanceled
		// while the kernel goes on pairing without anyone left to answer its passkey request).
		tokio::time::sleep(DISCONNECT_SETTLE).await;
	}

	let key = address.to_uppercase();
	PENDING_PINS.lock().unwrap().get_or_insert_with(HashMap::new).insert(key.clone(), pin.to_string());
	let result = device.pair().await.map(|_| ()).map_err(|e| e.to_string());
	if let Some(m) = PENDING_PINS.lock().unwrap().as_mut() {
		m.remove(&key);
	}

	if reconnect && !device.connected().await.unwrap_or(false) {
		let reconnected = device.connect().await.map_err(|e| format!("reconnect after pairing failed: {}", e));
		if result.is_ok() {
			reconnected?;
		}
	}
	result
}

/// Whether BlueZ holds a bond (stored keys) for `address`. `Bonded` only exists since BlueZ 5.68 -
/// older versions report it through `Paired` alone. A device BlueZ doesn't know is not bonded.
pub async fn is_bonded(address: &str) -> Result<bool, String> {
	let conn = ensure_connection().await?;
	let adapter_path = find_adapter_path(&conn).await?;
	let device = nonblock::Proxy::new("org.bluez", device_path(&adapter_path, address), DBUS_TIMEOUT, conn);
	match device.bonded().await {
		Ok(b) => Ok(b),
		Err(e) if e.name() == Some("org.freedesktop.DBus.Error.UnknownObject") => Ok(false),
		Err(_) => device.paired().await.or(Ok(false)),
	}
}

/// Removes the device - and with it the stored bond - from BlueZ (`Adapter1::RemoveDevice`), the
/// counterpart of `bluetoothctl remove`. BlueZ forgets the device entirely, so it has to be scanned
/// for again before the next connect. A device BlueZ doesn't know counts as already unpaired.
pub async fn unpair(address: &str) -> Result<(), String> {
	let conn = ensure_connection().await?;
	let adapter_path = find_adapter_path(&conn).await?;
	let adapter = nonblock::Proxy::new("org.bluez", adapter_path.clone(), DBUS_TIMEOUT, conn);
	match adapter.remove_device(device_path(&adapter_path, address)).await {
		Ok(()) => Ok(()),
		Err(e) if e.name() == Some("org.bluez.Error.DoesNotExist") => Ok(()),
		Err(e) => Err(e.to_string()),
	}
}
