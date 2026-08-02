//! ble-bridge: a BLE sidecar process for cz.bliksoft.java:common-java-utils-ble.
//!
//! Speaks newline-delimited JSON on stdin (commands, one object per line) / stdout
//! (responses and events, one object per line). stderr is free for human-readable logs.
//! Runs as a separate OS process specifically so that a native/driver-level BLE fault
//! can never take the parent JVM down with it - see BSToolbox-BLE's README for why.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use btleplug::api::{Central, CentralEvent, CharPropFlags, Manager as _, Peripheral as _, ScanFilter, WriteType};
use btleplug::platform::{Adapter, Manager, Peripheral};
use futures::stream::StreamExt;
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::mpsc::{self, UnboundedSender};
use uuid::Uuid;

#[derive(Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
enum Command {
	Scan { id: String, filter_service_uuid: Option<String>, timeout_ms: u64 },
	StopScan { id: String },
	Connect { id: String, address: String },
	Disconnect { id: String, address: String },
	DiscoverServices { id: String, address: String },
	Read { id: String, address: String, service_uuid: String, char_uuid: String },
	Write { id: String, address: String, service_uuid: String, char_uuid: String, value_hex: String, with_response: bool },
	Subscribe { id: String, address: String, service_uuid: String, char_uuid: String },
	Unsubscribe { id: String, address: String, service_uuid: String, char_uuid: String },
}

struct AppState {
	adapter: Adapter,
	peripherals: Mutex<HashMap<String, Peripheral>>,
	notif_started: Mutex<HashSet<String>>,
}

#[tokio::main]
async fn main() {
	let (tx, mut rx) = mpsc::unbounded_channel::<Value>();

	// Writer task: the only thing allowed to touch stdout, so lines never interleave.
	tokio::spawn(async move {
		let mut stdout = tokio::io::stdout();
		while let Some(v) = rx.recv().await {
			if let Ok(line) = serde_json::to_string(&v) {
				let _ = stdout.write_all(line.as_bytes()).await;
				let _ = stdout.write_all(b"\n").await;
				let _ = stdout.flush().await;
			}
		}
	});

	let manager = match Manager::new().await {
		Ok(m) => m,
		Err(e) => {
			let _ = tx.send(json!({"type": "fatal", "message": format!("could not init BLE manager: {}", e)}));
			return;
		}
	};
	let adapters = match manager.adapters().await {
		Ok(a) => a,
		Err(e) => {
			let _ = tx.send(json!({"type": "fatal", "message": format!("could not list adapters: {}", e)}));
			return;
		}
	};
	let adapter = match adapters.into_iter().next() {
		Some(a) => a,
		None => {
			let _ = tx.send(json!({"type": "fatal", "message": "no BLE adapter found"}));
			return;
		}
	};

	let state = Arc::new(AppState {
		adapter,
		peripherals: Mutex::new(HashMap::new()),
		notif_started: Mutex::new(HashSet::new()),
	});

	// Forward adapter-level discovery/connect/disconnect events for as long as the process runs.
	{
		let state = state.clone();
		let tx = tx.clone();
		tokio::spawn(async move {
			if let Ok(mut events) = state.adapter.events().await {
				while let Some(evt) = events.next().await {
					handle_central_event(&state, &tx, evt).await;
				}
			}
		});
	}

	let stdin = tokio::io::stdin();
	let mut lines = BufReader::new(stdin).lines();
	loop {
		match lines.next_line().await {
			Ok(Some(line)) => {
				if line.trim().is_empty() {
					continue;
				}
				let state = state.clone();
				let tx = tx.clone();
				tokio::spawn(async move {
					handle_line(state, tx, line).await;
				});
			}
			Ok(None) => break, // stdin closed - parent process is gone, exit cleanly
			Err(_) => break,
		}
	}
}

async fn handle_line(state: Arc<AppState>, tx: UnboundedSender<Value>, line: String) {
	let cmd: Command = match serde_json::from_str(&line) {
		Ok(c) => c,
		Err(e) => {
			let _ = tx.send(json!({"type": "error", "message": format!("bad command: {}", e)}));
			return;
		}
	};

	match cmd {
		Command::Scan { id, filter_service_uuid, timeout_ms } => {
			let mut filter = ScanFilter::default();
			if let Some(u) = filter_service_uuid {
				match Uuid::parse_str(&u) {
					Ok(uuid) => filter = ScanFilter { services: vec![uuid] },
					Err(e) => {
						let _ = tx.send(err_response(&id, e.to_string()));
						return;
					}
				}
			}
			if let Err(e) = state.adapter.start_scan(filter).await {
				let _ = tx.send(err_response(&id, e.to_string()));
				return;
			}
			let state2 = state.clone();
			let tx2 = tx.clone();
			tokio::spawn(async move {
				tokio::time::sleep(Duration::from_millis(timeout_ms)).await;
				let _ = state2.adapter.stop_scan().await;
				let _ = tx2.send(ok_response(&id, json!({})));
			});
		}
		Command::StopScan { id } => respond(&tx, &id, state.adapter.stop_scan().await.map(|_| json!({}))),
		Command::Connect { id, address } => match get_peripheral(&state, &address).await {
			Ok(p) => respond(&tx, &id, p.connect().await.map(|_| json!({}))),
			Err(e) => tx.send(err_response(&id, e)).ok().unwrap_or(()),
		},
		Command::Disconnect { id, address } => match get_peripheral(&state, &address).await {
			Ok(p) => respond(&tx, &id, p.disconnect().await.map(|_| json!({}))),
			Err(e) => tx.send(err_response(&id, e)).ok().unwrap_or(()),
		},
		Command::DiscoverServices { id, address } => match get_peripheral(&state, &address).await {
			Ok(p) => {
				if let Err(e) = p.discover_services().await {
					let _ = tx.send(err_response(&id, e.to_string()));
					return;
				}
				let services: Vec<Value> = p
					.services()
					.into_iter()
					.map(|s| {
						json!({
							"uuid": s.uuid.to_string(),
							"characteristics": s.characteristics.iter().map(|c| json!({
								"uuid": c.uuid.to_string(),
								"properties": char_props_to_strings(c.properties),
							})).collect::<Vec<_>>(),
						})
					})
					.collect();
				let _ = tx.send(ok_response(&id, json!({ "services": services })));
			}
			Err(e) => {
				let _ = tx.send(err_response(&id, e));
			}
		},
		Command::Read { id, address, service_uuid, char_uuid } => {
			match find_characteristic(&state, &address, &service_uuid, &char_uuid).await {
				Ok((p, c)) => match p.read(&c).await {
					Ok(bytes) => {
						let _ = tx.send(ok_response(&id, json!({ "value_hex": hex_encode(&bytes) })));
					}
					Err(e) => {
						let _ = tx.send(err_response(&id, e.to_string()));
					}
				},
				Err(e) => {
					let _ = tx.send(err_response(&id, e));
				}
			}
		}
		Command::Write { id, address, service_uuid, char_uuid, value_hex, with_response } => {
			let bytes = match hex_decode(&value_hex) {
				Ok(b) => b,
				Err(e) => {
					let _ = tx.send(err_response(&id, e));
					return;
				}
			};
			match find_characteristic(&state, &address, &service_uuid, &char_uuid).await {
				Ok((p, c)) => {
					let wt = if with_response { WriteType::WithResponse } else { WriteType::WithoutResponse };
					respond(&tx, &id, p.write(&c, &bytes, wt).await.map(|_| json!({})));
				}
				Err(e) => {
					let _ = tx.send(err_response(&id, e));
				}
			}
		}
		Command::Subscribe { id, address, service_uuid, char_uuid } => {
			match find_characteristic(&state, &address, &service_uuid, &char_uuid).await {
				Ok((p, c)) => respond(&tx, &id, p.subscribe(&c).await.map(|_| json!({}))),
				Err(e) => {
					let _ = tx.send(err_response(&id, e));
				}
			}
		}
		Command::Unsubscribe { id, address, service_uuid, char_uuid } => {
			match find_characteristic(&state, &address, &service_uuid, &char_uuid).await {
				Ok((p, c)) => respond(&tx, &id, p.unsubscribe(&c).await.map(|_| json!({}))),
				Err(e) => {
					let _ = tx.send(err_response(&id, e));
				}
			}
		}
	}
}

async fn handle_central_event(state: &Arc<AppState>, tx: &UnboundedSender<Value>, evt: CentralEvent) {
	match evt {
		CentralEvent::DeviceDiscovered(id) | CentralEvent::DeviceUpdated(id) => {
			if let Ok(p) = state.adapter.peripheral(&id).await {
				if let Ok(Some(props)) = p.properties().await {
					let addr = props.address.to_string().to_uppercase();
					state.peripherals.lock().unwrap().insert(addr.clone(), p.clone());
					let _ = tx.send(json!({
						"type": "device_found",
						"address": addr,
						"name": props.local_name,
						"rssi": props.rssi,
					}));
				}
			}
		}
		CentralEvent::DeviceConnected(id) => {
			if let Ok(p) = state.adapter.peripheral(&id).await {
				if let Ok(Some(props)) = p.properties().await {
					let addr = props.address.to_string().to_uppercase();
					state.peripherals.lock().unwrap().insert(addr.clone(), p.clone());
					let _ = tx.send(json!({"type": "connected", "address": addr}));
					spawn_notification_forwarder(state.clone(), tx.clone(), addr, p);
				}
			}
		}
		CentralEvent::DeviceDisconnected(id) => {
			if let Ok(p) = state.adapter.peripheral(&id).await {
				if let Ok(Some(props)) = p.properties().await {
					let addr = props.address.to_string().to_uppercase();
					state.notif_started.lock().unwrap().remove(&addr);
					let _ = tx.send(json!({"type": "disconnected", "address": addr, "reason": "peripheral_disconnected"}));
				}
			}
		}
		_ => {}
	}
}

/// One forwarder task per connected peripheral, started on first connect and stopped when its
/// notification stream ends (which btleplug does on disconnect). Individual characteristic
/// subscribe/unsubscribe calls just gate which characteristics feed that shared stream.
fn spawn_notification_forwarder(state: Arc<AppState>, tx: UnboundedSender<Value>, addr: String, peripheral: Peripheral) {
	{
		let mut started = state.notif_started.lock().unwrap();
		if started.contains(&addr) {
			return;
		}
		started.insert(addr.clone());
	}
	tokio::spawn(async move {
		if let Ok(mut stream) = peripheral.notifications().await {
			while let Some(v) = stream.next().await {
				let _ = tx.send(json!({
					"type": "notification",
					"address": addr,
					"char_uuid": v.uuid.to_string(),
					"value_hex": hex_encode(&v.value),
				}));
			}
		}
		state.notif_started.lock().unwrap().remove(&addr);
	});
}

async fn get_peripheral(state: &AppState, address: &str) -> Result<Peripheral, String> {
	let key = address.to_uppercase();
	if let Some(p) = state.peripherals.lock().unwrap().get(&key) {
		return Ok(p.clone());
	}
	// Not seen via an active scan (e.g. already bonded at the OS level) - check the adapter's
	// own cache of previously-seen peripherals before giving up.
	let list = state.adapter.peripherals().await.map_err(|e| e.to_string())?;
	for p in list {
		if let Ok(Some(props)) = p.properties().await {
			if props.address.to_string().to_uppercase() == key {
				state.peripherals.lock().unwrap().insert(key.clone(), p.clone());
				return Ok(p);
			}
		}
	}
	Err(format!("unknown peripheral address {} - scan for it first", address))
}

async fn find_characteristic(
	state: &AppState,
	address: &str,
	service_uuid: &str,
	char_uuid: &str,
) -> Result<(Peripheral, btleplug::api::Characteristic), String> {
	let p = get_peripheral(state, address).await?;
	let su = Uuid::parse_str(service_uuid).map_err(|e| e.to_string())?;
	let cu = Uuid::parse_str(char_uuid).map_err(|e| e.to_string())?;

	let mut chars = p.characteristics();
	if chars.is_empty() {
		p.discover_services().await.map_err(|e| e.to_string())?;
		chars = p.characteristics();
	}
	chars
		.into_iter()
		.find(|c| c.uuid == cu && c.service_uuid == su)
		.map(|c| (p, c))
		.ok_or_else(|| format!("characteristic {} not found on service {}", char_uuid, service_uuid))
}

fn respond(tx: &UnboundedSender<Value>, id: &str, result: Result<Value, btleplug::Error>) {
	let _ = match result {
		Ok(extra) => tx.send(ok_response(id, extra)),
		Err(e) => tx.send(err_response(id, e.to_string())),
	};
}

fn ok_response(id: &str, extra: Value) -> Value {
	let mut obj = serde_json::Map::new();
	obj.insert("type".into(), json!("response"));
	obj.insert("id".into(), json!(id));
	obj.insert("ok".into(), json!(true));
	if let Value::Object(map) = extra {
		for (k, v) in map {
			obj.insert(k, v);
		}
	}
	Value::Object(obj)
}

fn err_response(id: &str, message: String) -> Value {
	json!({"type": "response", "id": id, "ok": false, "error": message})
}

fn char_props_to_strings(props: CharPropFlags) -> Vec<String> {
	let mut v = Vec::new();
	if props.contains(CharPropFlags::READ) {
		v.push("READ".to_string());
	}
	if props.contains(CharPropFlags::WRITE) {
		v.push("WRITE".to_string());
	}
	if props.contains(CharPropFlags::WRITE_WITHOUT_RESPONSE) {
		v.push("WRITE_WITHOUT_RESPONSE".to_string());
	}
	if props.contains(CharPropFlags::NOTIFY) {
		v.push("NOTIFY".to_string());
	}
	if props.contains(CharPropFlags::INDICATE) {
		v.push("INDICATE".to_string());
	}
	v
}

fn hex_encode(bytes: &[u8]) -> String {
	let mut s = String::with_capacity(bytes.len() * 2);
	for b in bytes {
		s.push_str(&format!("{:02x}", b));
	}
	s
}

fn hex_decode(s: &str) -> Result<Vec<u8>, String> {
	let s = s.trim();
	if s.len() % 2 != 0 {
		return Err("odd-length hex string".to_string());
	}
	let bytes = s.as_bytes();
	let mut out = Vec::with_capacity(s.len() / 2);
	let mut i = 0;
	while i < bytes.len() {
		let hi = (bytes[i] as char).to_digit(16).ok_or("invalid hex digit")?;
		let lo = (bytes[i + 1] as char).to_digit(16).ok_or("invalid hex digit")?;
		out.push(((hi << 4) | lo) as u8);
		i += 2;
	}
	Ok(out)
}
