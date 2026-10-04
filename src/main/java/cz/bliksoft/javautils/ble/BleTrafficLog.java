package cz.bliksoft.javautils.ble;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes the JSON lines exchanged with the {@code ble-bridge} sidecar as one
 * file each: {@code <timestamp>-<address>-<name>-<kind>.json}, {@code kind}
 * being {@code req}, {@code resp} or {@code evt}. Failures are logged (once)
 * and swallowed - traffic logging must never break the protocol itself.
 */
class BleTrafficLog {

	private static final Logger LOG = Logger.getLogger(BleTrafficLog.class.getName());
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
	/** Process-wide, keeps files written within the same millisecond ordered. */
	private static final AtomicLong SEQ = new AtomicLong();

	private static volatile boolean failureReported = false;

	private BleTrafficLog() {
	}

	static void write(Path dir, ObjectMapper mapper, String address, String name, String kind, JsonNode node) {
		if (dir == null) {
			return;
		}
		String fileName = String.format("%s-%06d-%s-%s-%s.json", TS.format(LocalDateTime.now()),
				SEQ.incrementAndGet() % 1000000, sanitize(address == null ? "adapter" : address), sanitize(name), kind);
		try {
			Files.createDirectories(dir);
			Files.write(dir.resolve(fileName),
					mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			if (!failureReported) {
				failureReported = true;
				LOG.log(Level.WARNING, "BLE traffic log write to " + dir + " failed (further failures not reported)",
						e);
			}
		}
	}

	static String sanitize(String s) {
		if (s == null || s.isEmpty()) {
			return "unknown";
		}
		return s.replaceAll("[\\\\/:*?\"<>|\\s]", "-");
	}
}
