package cz.bliksoft.javautils.ble.remote;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A single physical remote connection carries both real {@code ble-bridge}
 * NDJSON lines and session-control actions (currently just "discard the current
 * sidecar and spawn a fresh one", see
 * {@link RemoteAdapterRegistry#requestNewSession}). Control lines use a
 * reserved field no {@code ble-bridge} command/response/event shape uses, so
 * both ends can tell them apart from real protocol lines on the same channel.
 * Used by {@link ControlAwarePipe} on the server side and by the client's
 * remote CLI - never by {@code BleLinePipe}/{@code BleAdapter} themselves,
 * which stay protocol-agnostic and never see these lines.
 */
final class SessionControl {

	static final String CONTROL_FIELD = "__ble_remote_control__";
	static final String SESSION_RESET = "session_reset";
	static final String SESSION_READY = "session_ready";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SessionControl() {
	}

	static String line(String type) {
		return MAPPER.createObjectNode().put(CONTROL_FIELD, type).toString();
	}

	/**
	 * Returns the control type of {@code line}, or {@code null} if it isn't one.
	 */
	static String typeOf(String line) {
		try {
			JsonNode node = MAPPER.readTree(line);
			JsonNode control = node.path(CONTROL_FIELD);
			return control.isMissingNode() ? null : control.asText();
		} catch (IOException e) {
			return null;
		}
	}
}
