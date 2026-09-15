package cz.bliksoft.javautils.ble;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cz.bliksoft.javautils.ble.transport.FakeLinePipe;

/**
 * Exercises {@link BleAdapter} against an in-memory {@link FakeLinePipe} to pin
 * down the request/response correlation and event-dispatch behavior that the
 * {@code BleLinePipe} extraction must preserve for local (process-backed) usage
 * and must also hold for a future remote pipe.
 */
class BleAdapterTest {

	private static final String ADDRESS = "AA:BB:CC:DD:EE:FF";
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void responsesCorrelateByIdEvenOutOfOrder() throws Exception {
		FakeLinePipe pipe = new FakeLinePipe();
		BleAdapter adapter = new BleAdapter(pipe);

		CompletableFuture<JsonNode> f1 = adapter.sendRequestAsync(command("adapter_state"), 5000);
		CompletableFuture<JsonNode> f2 = adapter.sendRequestAsync(command("adapter_state"), 5000);

		String id1 = sentId(pipe, 0);
		String id2 = sentId(pipe, 1);
		assertNotEquals(id1, id2);

		// Reply out of order: second request's response arrives first.
		pipe.deliverLine("{\"type\":\"response\",\"id\":\"" + id2 + "\",\"ok\":true,\"state\":\"second\"}");
		pipe.deliverLine("{\"type\":\"response\",\"id\":\"" + id1 + "\",\"ok\":true,\"state\":\"first\"}");

		assertEquals("first", f1.get(1, TimeUnit.SECONDS).path("state").asText());
		assertEquals("second", f2.get(1, TimeUnit.SECONDS).path("state").asText());
	}

	@Test
	void deviceFoundDispatchesToScanListener() throws Exception {
		FakeLinePipe pipe = new FakeLinePipe();
		BleAdapter adapter = new BleAdapter(pipe);
		List<String> found = new CopyOnWriteArrayList<>();

		Thread scanThread = new Thread(() -> {
			try {
				adapter.scan(null, 1000, (address, name, rssi) -> found.add(address));
			} catch (BleException ignored) {
				// asserted via `found` below
			}
		});
		scanThread.start();

		waitUntil(() -> !pipe.getSent().isEmpty());
		String scanId = sentId(pipe, 0);
		pipe.deliverLine("{\"type\":\"device_found\",\"address\":\"" + ADDRESS + "\",\"name\":\"Foo\",\"rssi\":-50}");
		pipe.deliverLine("{\"type\":\"response\",\"id\":\"" + scanId + "\",\"ok\":true}");
		scanThread.join(2000);

		assertEquals(List.of(ADDRESS), found);
	}

	@Test
	void exactAddressFilterAutoStopsOnFirstMatchRegardlessOfName() throws Exception {
		FakeLinePipe pipe = new FakeLinePipe();
		BleAdapter adapter = new BleAdapter(pipe);
		List<String> found = new CopyOnWriteArrayList<>();

		Thread scanThread = new Thread(() -> {
			try {
				adapter.scan(new ScanFilter().withAddress(ADDRESS), 5000, (address, name, rssi) -> found.add(address));
			} catch (BleException ignored) {
				// asserted via `found`/pipe.getSent() below
			}
		});
		scanThread.start();

		waitUntil(() -> !pipe.getSent().isEmpty());
		// No "name" field at all - a bare, nameless first advertisement.
		pipe.deliverLine("{\"type\":\"device_found\",\"address\":\"" + ADDRESS + "\",\"rssi\":-50}");

		waitUntil(() -> sentCommands(pipe).contains("stop_scan"));
		scanThread.join(2000);

		assertEquals(List.of(ADDRESS), found);
	}

	@Test
	void exactAddressFilterWithRequireNameWaitsForNamedEventBeforeAutoStopping() throws Exception {
		FakeLinePipe pipe = new FakeLinePipe();
		BleAdapter adapter = new BleAdapter(pipe);
		List<String> foundNames = new CopyOnWriteArrayList<>();

		Thread scanThread = new Thread(() -> {
			try {
				adapter.scan(new ScanFilter().withAddress(ADDRESS).requireName(), 5000,
						(address, name, rssi) -> foundNames.add(name));
			} catch (BleException ignored) {
				// asserted via `foundNames`/pipe.getSent() below
			}
		});
		scanThread.start();
		waitUntil(() -> !pipe.getSent().isEmpty());

		// First packet: address matches, but no name yet - must NOT auto-stop.
		pipe.deliverLine("{\"type\":\"device_found\",\"address\":\"" + ADDRESS + "\",\"rssi\":-50}");
		// Give the (intentionally wrong) old behavior a chance to fire before asserting
		// its absence.
		Thread.sleep(200);
		assertFalse(sentCommands(pipe).contains("stop_scan"),
				"a nameless match must not auto-stop the scan when requireName() is set");
		assertEquals(java.util.Collections.singletonList(null), foundNames,
				"the nameless event is still reported to the listener");

		// Second packet, same address: this time the name is populated - must auto-stop
		// now.
		pipe.deliverLine("{\"type\":\"device_found\",\"address\":\"" + ADDRESS
				+ "\",\"name\":\"M2_H-I814050044\",\"rssi\":-50}");
		waitUntil(() -> sentCommands(pipe).contains("stop_scan"));
		scanThread.join(2000);

		assertEquals(java.util.Arrays.asList(null, "M2_H-I814050044"), foundNames);
	}

	@Test
	void notificationDispatchesToSubscribedListener() throws Exception {
		FakeLinePipe pipe = new FakeLinePipe();
		BleAdapter adapter = new BleAdapter(pipe);
		BlePeripheral peripheral = adapter.getPeripheral(ADDRESS);
		List<byte[]> received = new CopyOnWriteArrayList<>();

		Thread subscribeThread = new Thread(() -> {
			try {
				peripheral.subscribe("180D", "2A37", (charUuid, value) -> received.add(value));
			} catch (BleException ignored) {
				// asserted via `received` below
			}
		});
		subscribeThread.start();
		waitUntil(() -> !pipe.getSent().isEmpty());
		pipe.deliverLine("{\"type\":\"response\",\"id\":\"" + sentId(pipe, 0) + "\",\"ok\":true}");
		subscribeThread.join(2000);

		pipe.deliverLine("{\"type\":\"notification\",\"address\":\"" + ADDRESS
				+ "\",\"char_uuid\":\"2A37\",\"value_hex\":\"0102\"}");

		assertEquals(1, received.size());
		assertArrayEquals(new byte[] { 1, 2 }, received.get(0));
	}

	@Test
	void disconnectedEventFiresListenerWithSidecarReason() {
		FakeLinePipe pipe = new FakeLinePipe();
		BleAdapter adapter = new BleAdapter(pipe);
		BlePeripheral peripheral = adapter.getPeripheral(ADDRESS);
		List<String> reasons = new CopyOnWriteArrayList<>();
		peripheral.setDisconnectListener(reasons::add);

		pipe.deliverLine("{\"type\":\"disconnected\",\"address\":\"" + ADDRESS + "\",\"reason\":\"remote_dropped\"}");

		assertEquals(List.of("remote_dropped"), reasons);
	}

	@Test
	void closeSuppressesDisconnectFromLateExitNotification() {
		FakeLinePipe pipe = new FakeLinePipe();
		BleAdapter adapter = new BleAdapter(pipe);
		BlePeripheral peripheral = adapter.getPeripheral(ADDRESS);
		List<String> reasons = new CopyOnWriteArrayList<>();
		peripheral.setDisconnectListener(reasons::add);

		adapter.close();
		// Simulates the pipe's exit-watcher noticing the death close() itself caused.
		pipe.closePeer("sidecar_crashed");

		assertTrue(reasons.isEmpty());
		assertTrue(pipe.wasClosedByLocal());
	}

	@Test
	void unexpectedPipeCloseFiresDisconnectAndFailsPending() throws Exception {
		FakeLinePipe pipe = new FakeLinePipe();
		BleAdapter adapter = new BleAdapter(pipe);
		BlePeripheral peripheral = adapter.getPeripheral(ADDRESS);
		List<String> reasons = new CopyOnWriteArrayList<>();
		peripheral.setDisconnectListener(reasons::add);

		CompletableFuture<JsonNode> pending = adapter.sendRequestAsync(command("adapter_state"), 5000);

		pipe.closePeer("sidecar_crashed");

		assertEquals(List.of("sidecar_crashed"), reasons);
		assertFalse(adapter.isAlive());
		ExecutionException ex = assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
		assertInstanceOf(BleSidecarException.class, ex.getCause());
	}

	private ObjectNode command(String cmd) {
		ObjectNode node = mapper.createObjectNode();
		node.put("cmd", cmd);
		return node;
	}

	private String sentId(FakeLinePipe pipe, int index) throws Exception {
		return mapper.readTree(pipe.getSent().get(index)).get("id").asText();
	}

	/**
	 * Every {@code cmd} value sent so far, in order - lets a test assert
	 * whether/when a {@code "stop_scan"} was actually issued without racing its
	 * exact index.
	 */
	private List<String> sentCommands(FakeLinePipe pipe) {
		return pipe.getSent().stream().map(line -> {
			try {
				return mapper.readTree(line).path("cmd").asText(null);
			} catch (Exception e) {
				return null;
			}
		}).collect(java.util.stream.Collectors.toList());
	}

	private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 2000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("condition not met within timeout");
			}
			Thread.sleep(10);
		}
	}
}
