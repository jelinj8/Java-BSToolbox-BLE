//! Windows-only direct WinRT GATT path. discover/read/write/subscribe/unsubscribe go through this
//! module instead of btleplug's Windows backend; connect/disconnect/scan still use btleplug (see
//! `main.rs`) since they don't hit the problem described below.
//!
//! ## Why this exists
//!
//! btleplug's Windows backend can leave an abandoned WinRT GATT operation holding an internal lock
//! that every later GATT call on the same device object then queues behind - permanently, since
//! the operation was never actually cancelled when the Rust future wrapping it was dropped. No
//! retry budget recovers from this once it happens (see
//! [btleplug#325](https://github.com/deviceplug/btleplug/issues/325)).
//!
//! The fix is to never reuse a device object across GATT operations, and to query the specific
//! service/characteristic needed by UUID rather than enumerating everything. `resolve_service`
//! opens a `BluetoothLEDevice` via `FromBluetoothAddressAsync` and queries it via
//! `GetGattServicesForUuidAsync`, then `find_characteristic` queries the specific characteristic
//! needed via `GetCharacteristicsForUuidAsync` on that service. `FromBluetoothAddressAsync` is a
//! lightweight proxy to the OS's single underlying connection for a device, not a new physical
//! connection, so this doesn't disturb the connection btleplug already established via
//! `Peripheral::connect()`.
//!
//! `resolve_service` caches the resolved `(BluetoothLEDevice, GattDeviceService)` pair per
//! (address, service_uuid) in `SERVICES` and reuses it for every characteristic on that service,
//! rather than opening an independent pair per call: two independent `GattDeviceService` proxies
//! for the same service, open at the same time, make Windows reject GATT access on the second one
//! with `GattCommunicationStatus::AccessDenied`. A fresh `GetCharacteristicsForUuidAsync` for the
//! specific characteristic still runs on every call.
//!
//! A subscription's event handler and the characteristic it's registered on must stay alive for
//! the life of the subscription (unlike a one-shot read/write) - `SUBSCRIPTIONS` keeps that alive;
//! the device/service it depends on are kept alive independently by `SERVICES`.
//!
//! ## Supplementary scan watcher (`ensure_scan_watcher`/`stop_scan_watcher`)
//!
//! A second, unrelated Windows-only scan gap lives here too: `btleplug`'s own Windows scan path
//! (`winrtble::ble::watcher::BLEWatcher`, still used as-is for connect/disconnect - see the module
//! doc above) hardcodes `SetAllowExtendedAdvertisements(true)` and `SetUseCodedPhy(true)` on its
//! watcher with no way to disable either via `btleplug`'s public API. At least one real peripheral
//! (a MeshCore radio, nRF52-based) has been observed to stop being reported by Windows to *any*
//! watcher with `AllowExtendedAdvertisements` enabled, even though the exact same device shows up
//! immediately on an otherwise-identical watcher with that one setting left at its default - this
//! isn't a filtering issue (confirmed via an *unfiltered* scan on both watchers), it's specific to
//! that one flag. Root cause presumably sits below WinRT (adapter/driver-level PHY scan-window
//! trade-off), so there's nothing `btleplug` itself could sensibly work around either.
//!
//! Rather than trying to argue Windows out of dropping the advertisement, this runs a second,
//! independent `BluetoothLEAdvertisementWatcher` alongside `btleplug`'s own - unfiltered, and
//! without touching either problem setting - and forwards every peripheral *it* sees straight into
//! the same `device_found` stream `handle_central_event` (Windows-only) populates from `btleplug`'s
//! events, address and name taken directly from this watcher's own advertisement data (not routed
//! through `btleplug`'s `Peripheral`/`PeripheralProperties` at all, since `btleplug` never learns
//! this address exists in the first place). Devices both watchers see just get reported twice -
//! already how repeated advertisements within one scan behave, and every existing caller already
//! dedupes `device_found` by address. See `main.rs`'s `get_peripheral` for the matching other half
//! of this: `Central::add_peripheral()` (btleplug 0.13+) lets `Connect` reach such a device by
//! address even though `btleplug`'s own scan never discovered it, so discovery and connectability
//! are fixed independently but end up covering the same gap.
//!
//! Tied to the same `Scan`/`StopScan` lifecycle as `btleplug`'s own watcher, not left running for
//! the life of the process: an advertisement watcher left scanning in the background indefinitely
//! would keep the radio in continuous active-scan mode even while a GATT connection is established
//! and in use - a real source of connection-interval jitter on some BLE controllers - for a feature
//! that's only useful during an actual scan window anyway.

use std::collections::HashMap;
use std::str::FromStr;
use std::sync::Mutex;

use btleplug::api::BDAddr;
use serde_json::{json, Value};
use tokio::sync::mpsc::UnboundedSender;
use uuid::Uuid;
use windows::Devices::Bluetooth::Advertisement::{
	BluetoothLEAdvertisementReceivedEventArgs, BluetoothLEAdvertisementWatcher, BluetoothLEScanningMode,
};
use windows::Devices::Bluetooth::{BluetoothDeviceId, BluetoothLEDevice};
use windows::Devices::Bluetooth::GenericAttributeProfile::{
	GattCharacteristic, GattCharacteristicProperties, GattClientCharacteristicConfigurationDescriptorValue,
	GattCommunicationStatus, GattDeviceService, GattSession, GattValueChangedEventArgs,
};
use windows::Foundation::TypedEventHandler;
use windows::Storage::Streams::{DataReader, DataWriter, IBuffer};
use windows::core::{GUID, Ref};

use crate::hex_encode;

/// Constructed once and then just Start()/Stop()ped from there on, exactly mirroring how
/// `btleplug`'s own `BLEWatcher` (see `winrtble::ble::watcher`) wraps one persistent
/// `BluetoothLEAdvertisementWatcher`.
static SCAN_WATCHER: Mutex<Option<BluetoothLEAdvertisementWatcher>> = Mutex::new(None);

/// Starts (or resumes, if already constructed) the supplementary, filter-free advertisement
/// watcher, forwarding every peripheral it sees as a `device_found` event on `tx`. Call once per
/// `Scan` command; pair with `stop_scan_watcher` on `StopScan`/timeout. See the module doc comment
/// ("Supplementary scan watcher") for why this exists.
pub fn ensure_scan_watcher(tx: UnboundedSender<Value>) -> Result<(), String> {
	let mut guard = SCAN_WATCHER.lock().unwrap();
	if let Some(watcher) = guard.as_ref() {
		return watcher.Start().map_err(|e| e.to_string());
	}
	let watcher = BluetoothLEAdvertisementWatcher::new().map_err(|e| e.to_string())?;
	watcher.SetScanningMode(BluetoothLEScanningMode::Active).map_err(|e| e.to_string())?;
	// Deliberately does NOT set AllowExtendedAdvertisements/UseCodedPhy (unlike btleplug's own
	// watcher) and sets no AdvertisementFilter - see the module doc comment for why both matter.
	// Explicit type on `handler` (not the closure params) - same pattern btleplug's own
	// BLEWatcher::start uses, so inference doesn't depend on the later Received() call below.
	let handler: TypedEventHandler<BluetoothLEAdvertisementWatcher, BluetoothLEAdvertisementReceivedEventArgs> = TypedEventHandler::new(
		move |_sender, args: Ref<BluetoothLEAdvertisementReceivedEventArgs>| {
			if let Ok(args) = args.ok() {
				if let (Ok(raw_address), Ok(advertisement)) = (args.BluetoothAddress(), args.Advertisement()) {
					if let Ok(address) = BDAddr::try_from(raw_address) {
						let name = advertisement.LocalName().ok().map(|h| h.to_string()).filter(|s| !s.is_empty());
						let rssi = args.RawSignalStrengthInDBm().ok();
						let _ = tx.send(json!({
							"type": "device_found",
							"address": address.to_string(),
							"name": name,
							"rssi": rssi,
						}));
					}
				}
			}
			Ok(())
		},
	);
	watcher.Received(&handler).map_err(|e| e.to_string())?;
	watcher.Start().map_err(|e| e.to_string())?;
	*guard = Some(watcher);
	Ok(())
}

/// Stops the supplementary watcher (if it was ever started). A no-op if it was never started. Call
/// from `StopScan` and from the scan-timeout auto-stop path, mirroring `Adapter::stop_scan`.
pub fn stop_scan_watcher() {
	if let Some(watcher) = SCAN_WATCHER.lock().unwrap().as_ref() {
		let _ = watcher.Stop();
	}
}

/// Holds the characteristic a live subscription's `ValueChanged` handler is registered on, and the
/// token needed to remove that handler on unsubscribe. Keyed by "address|service_uuid|char_uuid".
struct WinSubscription {
	characteristic: GattCharacteristic,
	notify_token: i64,
}

static SUBSCRIPTIONS: Mutex<Option<HashMap<String, WinSubscription>>> = Mutex::new(None);

fn subscription_key(address: &str, service_uuid: &str, char_uuid: &str) -> String {
	format!("{}|{}|{}", address, service_uuid, char_uuid)
}

/// One resolved `(BluetoothLEDevice, GattDeviceService)` pair per (address, service_uuid), kept
/// alive for the life of the process - see the module doc comment for why sharing this across
/// every characteristic on the service (rather than resolving an independent one per call) matters.
static SERVICES: Mutex<Option<HashMap<String, (BluetoothLEDevice, GattDeviceService)>>> = Mutex::new(None);

fn service_key(address: &str, service_uuid: &str) -> String {
	format!("{}|{}", address, service_uuid)
}

/// One `GattSession` per address, kept alive (with `MaintainConnection` set) for the life of the
/// process. Windows only negotiates the ATT MTU past the 23-byte default while a `GattSession` for
/// the device is held open - without this, every GATT write here stays capped at ~20 usable bytes
/// regardless of what the peripheral requests, which silently breaks any write longer than that
/// (plenty of GATT peripherals expect a larger MTU - e.g. up to 512 bytes - for anything beyond
/// small fixed-size commands, which is exactly what continues to work fine even without this).
/// There's no direct "request MTU" call on Windows (unlike Android's `requestMtu()`/iOS's
/// `maximumWriteValueLength`) - simply holding an active session is what makes Windows negotiate a
/// larger `MaxPduSize` at all.
static SESSIONS: Mutex<Option<HashMap<String, GattSession>>> = Mutex::new(None);

async fn ensure_session(address: &str) -> Result<(), String> {
	{
		let guard = SESSIONS.lock().unwrap();
		if guard.as_ref().map_or(false, |m| m.contains_key(address)) {
			return Ok(());
		}
	}
	let device = open_device(address).await?;
	let device_id = device.BluetoothDeviceId().map_err(|e| e.to_string())?;
	let session = GattSession::FromDeviceIdAsync(&device_id)
		.map_err(|e| e.to_string())?
		.await
		.map_err(|e| format!("GattSession::FromDeviceIdAsync failed for {}: {}", address, e))?;
	session.SetMaintainConnection(true).map_err(|e| e.to_string())?;
	let mut guard = SESSIONS.lock().unwrap();
	guard.get_or_insert_with(HashMap::new).entry(address.to_string()).or_insert(session);
	Ok(())
}

async fn resolve_service(address: &str, service_uuid: &str) -> Result<(BluetoothLEDevice, GattDeviceService), String> {
	ensure_session(address).await?;
	let key = service_key(address, service_uuid);
	{
		let guard = SERVICES.lock().unwrap();
		if let Some(found) = guard.as_ref().and_then(|m| m.get(&key)) {
			return Ok(found.clone());
		}
		// guard drops here, before the awaits below - a std MutexGuard must not be held across one.
	}
	let device = open_device(address).await?;
	let service = find_service(&device, service_uuid).await?;
	let mut guard = SERVICES.lock().unwrap();
	let map = guard.get_or_insert_with(HashMap::new);
	// If another call resolved the same service concurrently in the meantime, keep that one and
	// drop this redundant lookup rather than replacing an entry a live subscription may depend on.
	Ok(map.entry(key).or_insert_with(|| (device, service)).clone())
}

fn to_guid(uuid: &Uuid) -> GUID {
	let (data1, data2, data3, data4) = uuid.as_fields();
	GUID::from_values(data1, data2, data3, data4.to_owned())
}

/// windows-rs's `GUID` Debug-formats as a braced GUID string (`{xxxxxxxx-...}`), which the `uuid`
/// crate's parser accepts - round-trips exactly like btleplug's own `winrtble::utils::to_uuid`.
fn from_guid(guid: &GUID) -> Uuid {
	Uuid::from_str(&format!("{:?}", guid)).unwrap_or_default()
}

fn to_vec(buffer: &IBuffer) -> Result<Vec<u8>, String> {
	let reader = DataReader::FromBuffer(buffer).map_err(|e| e.to_string())?;
	let len = reader.UnconsumedBufferLength().map_err(|e| e.to_string())? as usize;
	let mut data = vec![0u8; len];
	reader.ReadBytes(&mut data).map_err(|e| e.to_string())?;
	Ok(data)
}

fn char_props_to_strings(props: GattCharacteristicProperties) -> Vec<String> {
	let mut v = Vec::new();
	if props & GattCharacteristicProperties::Read == GattCharacteristicProperties::Read {
		v.push("READ".to_string());
	}
	if props & GattCharacteristicProperties::Write == GattCharacteristicProperties::Write {
		v.push("WRITE".to_string());
	}
	if props & GattCharacteristicProperties::WriteWithoutResponse == GattCharacteristicProperties::WriteWithoutResponse {
		v.push("WRITE_WITHOUT_RESPONSE".to_string());
	}
	if props & GattCharacteristicProperties::Notify == GattCharacteristicProperties::Notify {
		v.push("NOTIFY".to_string());
	}
	if props & GattCharacteristicProperties::Indicate == GattCharacteristicProperties::Indicate {
		v.push("INDICATE".to_string());
	}
	v
}

/// Opens a fresh `BluetoothLEDevice` proxy for `address` - see the module doc comment for why a
/// fresh one is used for every call rather than a cached, long-lived handle.
async fn open_device(address: &str) -> Result<BluetoothLEDevice, String> {
	let addr: btleplug::api::BDAddr = address.parse().map_err(|e| format!("{:?}", e))?;
	let addr_u64: u64 = addr.into();
	BluetoothLEDevice::FromBluetoothAddressAsync(addr_u64)
		.map_err(|e| e.to_string())?
		.await
		.map_err(|e| format!("FromBluetoothAddressAsync failed for {}: {}", address, e))
}

async fn find_service(device: &BluetoothLEDevice, service_uuid: &str) -> Result<GattDeviceService, String> {
	let su = Uuid::parse_str(service_uuid).map_err(|e| e.to_string())?;
	let result = device
		.GetGattServicesForUuidAsync(to_guid(&su))
		.map_err(|e| e.to_string())?
		.await
		.map_err(|e| e.to_string())?;
	let status = result.Status().map_err(|e| e.to_string())?;
	if status != GattCommunicationStatus::Success {
		return Err(format!("GetGattServicesForUuidAsync({}) failed: {:?}", service_uuid, status));
	}
	result
		.Services()
		.map_err(|e| e.to_string())?
		.into_iter()
		.next()
		.ok_or_else(|| format!("service {} not found", service_uuid))
}

async fn find_characteristic_on(service: &GattDeviceService, char_uuid: &str) -> Result<GattCharacteristic, String> {
	let cu = Uuid::parse_str(char_uuid).map_err(|e| e.to_string())?;
	let result = service
		.GetCharacteristicsForUuidAsync(to_guid(&cu))
		.map_err(|e| e.to_string())?
		.await
		.map_err(|e| e.to_string())?;
	let status = result.Status().map_err(|e| e.to_string())?;
	if status != GattCommunicationStatus::Success {
		return Err(format!("GetCharacteristicsForUuidAsync({}) failed: {:?}", char_uuid, status));
	}
	result
		.Characteristics()
		.map_err(|e| e.to_string())?
		.into_iter()
		.next()
		.ok_or_else(|| format!("characteristic {} not found on service", char_uuid))
}

/// Resolves (and caches, see `resolve_service`) the service, then does a targeted, UUID-scoped
/// characteristic lookup on it - the core of the fix; see the module doc comment.
async fn find_characteristic(address: &str, service_uuid: &str, char_uuid: &str) -> Result<GattCharacteristic, String> {
	let (_device, service) = resolve_service(address, service_uuid).await?;
	find_characteristic_on(&service, char_uuid).await
}

pub async fn discover_services(address: &str) -> Result<Vec<Value>, String> {
	let device = open_device(address).await?;
	let services_result = device.GetGattServicesAsync().map_err(|e| e.to_string())?.await.map_err(|e| e.to_string())?;
	let status = services_result.Status().map_err(|e| e.to_string())?;
	if status != GattCommunicationStatus::Success {
		return Err(format!("GetGattServicesAsync failed: {:?}", status));
	}
	// Collect into an owned Vec before the loop below - IVectorView's iterator is not Send (see
	// btleplug's own device.rs, which does the same for this exact reason), and the loop awaits.
	let services: Vec<_> = services_result.Services().map_err(|e| e.to_string())?.into_iter().collect();

	let mut result = Vec::new();
	for service in services {
		let service_uuid = service.Uuid().map_err(|e| e.to_string())?;
		let chars_result = service.GetCharacteristicsAsync().map_err(|e| e.to_string())?.await.map_err(|e| e.to_string())?;
		let characteristics = if chars_result.Status().map_err(|e| e.to_string())? == GattCommunicationStatus::Success {
			chars_result
				.Characteristics()
				.map_err(|e| e.to_string())?
				.into_iter()
				.map(|c| {
					let props = c.CharacteristicProperties().unwrap_or(GattCharacteristicProperties::None);
					json!({
						"uuid": from_guid(&c.Uuid().unwrap_or_default()).to_string(),
						"properties": char_props_to_strings(props),
					})
				})
				.collect::<Vec<_>>()
		} else {
			Vec::new()
		};
		result.push(json!({
			"uuid": from_guid(&service_uuid).to_string(),
			"characteristics": characteristics,
		}));
	}
	Ok(result)
}

pub async fn read(address: &str, service_uuid: &str, char_uuid: &str) -> Result<Vec<u8>, String> {
	let characteristic = find_characteristic(address, service_uuid, char_uuid).await?;
	let result = characteristic.ReadValueAsync().map_err(|e| e.to_string())?.await.map_err(|e| e.to_string())?;
	let status = result.Status().map_err(|e| e.to_string())?;
	if status != GattCommunicationStatus::Success {
		return Err(format!("read failed: {:?}", status));
	}
	to_vec(&result.Value().map_err(|e| e.to_string())?)
}

pub async fn write(address: &str, service_uuid: &str, char_uuid: &str, bytes: &[u8], with_response: bool) -> Result<(), String> {
	let characteristic = find_characteristic(address, service_uuid, char_uuid).await?;
	let write_option = if with_response {
		windows::Devices::Bluetooth::GenericAttributeProfile::GattWriteOption::WriteWithResponse
	} else {
		windows::Devices::Bluetooth::GenericAttributeProfile::GattWriteOption::WriteWithoutResponse
	};
	// DataWriter/IBuffer aren't Send, and (having a Drop impl) stay part of this async fn's state
	// until their scope ends even though neither is used again - not just until their last real
	// use - so both must be dropped explicitly before the await below, not just left to fall out
	// of scope at the end of the function.
	let operation = {
		let writer = DataWriter::new().map_err(|e| e.to_string())?;
		writer.WriteBytes(bytes).map_err(|e| e.to_string())?;
		let buffer = writer.DetachBuffer().map_err(|e| e.to_string())?;
		let operation = characteristic.WriteValueWithOptionAsync(&buffer, write_option).map_err(|e| e.to_string())?;
		drop(buffer);
		drop(writer);
		operation
	};
	let status = operation.await.map_err(|e| e.to_string())?;
	if status != GattCommunicationStatus::Success {
		return Err(format!("write failed: {:?}", status));
	}
	Ok(())
}

pub async fn subscribe(tx: &UnboundedSender<Value>, address: &str, service_uuid: &str, char_uuid: &str) -> Result<(), String> {
	let characteristic = find_characteristic(address, service_uuid, char_uuid).await?;

	let props = characteristic.CharacteristicProperties().map_err(|e| e.to_string())?;
	let config = if props & GattCharacteristicProperties::Indicate == GattCharacteristicProperties::Indicate {
		GattClientCharacteristicConfigurationDescriptorValue::Indicate
	} else if props & GattCharacteristicProperties::Notify == GattCharacteristicProperties::Notify {
		GattClientCharacteristicConfigurationDescriptorValue::Notify
	} else {
		return Err("characteristic does not support notify or indicate".to_string());
	};

	let addr = address.to_string();
	let char_uuid_owned = char_uuid.to_string();
	let tx = tx.clone();
	// TypedEventHandler isn't Send, so it must not still be in scope at the await below (see the
	// DataWriter/IBuffer comment in write() for why this matters even though it's not used again)
	// - drop it as soon as registration is done, same as btleplug's own subscribe().
	let notify_token = {
		let value_handler = TypedEventHandler::new(
			move |_sender: Ref<GattCharacteristic>, args: Ref<GattValueChangedEventArgs>| {
				if let Ok(args) = args.ok() {
					if let Ok(value) = args.CharacteristicValue() {
						if let Ok(data) = to_vec(&value) {
							let _ = tx.send(json!({
								"type": "notification",
								"address": addr,
								"char_uuid": char_uuid_owned,
								"value_hex": hex_encode(&data),
							}));
						}
					}
				}
				Ok(())
			},
		);
		characteristic.ValueChanged(&value_handler).map_err(|e| e.to_string())?
	};

	let status = match characteristic.WriteClientCharacteristicConfigurationDescriptorAsync(config) {
		Ok(op) => op.await,
		Err(e) => {
			let _ = characteristic.RemoveValueChanged(notify_token);
			return Err(e.to_string());
		}
	};
	let status = match status {
		Ok(s) => s,
		Err(e) => {
			let _ = characteristic.RemoveValueChanged(notify_token);
			return Err(e.to_string());
		}
	};
	if status != GattCommunicationStatus::Success {
		let _ = characteristic.RemoveValueChanged(notify_token);
		return Err(format!("subscribe failed: {:?}", status));
	}

	let key = subscription_key(address, service_uuid, char_uuid);
	let mut guard = SUBSCRIPTIONS.lock().unwrap();
	let map = guard.get_or_insert_with(HashMap::new);
	// Replacing an existing subscription (re-subscribe): drop the old one so its handler is
	// removed, matching a WinSubscription's Drop.
	map.insert(key, WinSubscription { characteristic, notify_token });
	Ok(())
}

pub async fn unsubscribe(address: &str, service_uuid: &str, char_uuid: &str) -> Result<(), String> {
	let key = subscription_key(address, service_uuid, char_uuid);
	let sub = { SUBSCRIPTIONS.lock().unwrap().as_mut().and_then(|m| m.remove(&key)) };
	let Some(sub) = sub else {
		return Ok(()); // already unsubscribed / never subscribed - nothing to do
	};

	let config = GattClientCharacteristicConfigurationDescriptorValue::None;
	let status = sub
		.characteristic
		.WriteClientCharacteristicConfigurationDescriptorAsync(config)
		.map_err(|e| e.to_string())?
		.await
		.map_err(|e| e.to_string())?;
	// sub's Drop removes the ValueChanged handler either way.
	if status != GattCommunicationStatus::Success {
		return Err(format!("unsubscribe failed: {:?}", status));
	}
	Ok(())
}

impl Drop for WinSubscription {
	fn drop(&mut self) {
		let _ = self.characteristic.RemoveValueChanged(self.notify_token);
	}
}
