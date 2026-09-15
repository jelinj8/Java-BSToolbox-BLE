package cz.bliksoft.javautils.ble.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.BlePeripheral;
import cz.bliksoft.javautils.ble.transport.FakeLinePipe;

/**
 * Wires two in-memory {@link FakeLinePipe}s together to stand in for a client
 * and server side of one remote connection, without any real networking, and
 * exercises {@link DefaultRemoteAdapterRegistry}'s register/adapterFor/drop and
 * {@link RemoteAdapterRegistry#requestNewSession} session-sentinel round trip.
 */
class DefaultRemoteAdapterRegistryTest {

	private static final String NAME = "device-1";
	private static final String ADDRESS = "AA:BB:CC:DD:EE:FF";
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void adapterForThrowsWhenNothingRegistered() {
		DefaultRemoteAdapterRegistry registry = new DefaultRemoteAdapterRegistry();
		assertThrows(BleException.class, () -> registry.adapterFor(NAME));
	}

	@Test
	void registeredPipeIsUsableAsAnAdapter() throws Exception {
		DefaultRemoteAdapterRegistry registry = new DefaultRemoteAdapterRegistry();
		FakeLinePipe serverSidePipe = new FakeLinePipe();
		registry.register(NAME, serverSidePipe);

		BleAdapter adapter = registry.adapterFor(NAME);
		assertSame(adapter, registry.adapterFor(NAME));

		// A command issued through the registry-backed adapter reaches the pipe.
		BlePeripheral peripheral = adapter.getPeripheral(ADDRESS);
		Thread connectThread = new Thread(() -> {
			try {
				peripheral.connect();
			} catch (BleException ignored) {
				// asserted via peripheral.isConnected() below
			}
		});
		connectThread.start();
		waitUntil(() -> !serverSidePipe.getSent().isEmpty());
		String id = mapper.readTree(serverSidePipe.getSent().get(0)).get("id").asText();
		serverSidePipe.deliverLine("{\"type\":\"response\",\"id\":\"" + id + "\",\"ok\":true}");
		connectThread.join(2000);

		assertTrue(peripheral.isConnected());
	}

	@Test
	void registeringUnderSameNameReplacesAndClosesPrevious() throws Exception {
		DefaultRemoteAdapterRegistry registry = new DefaultRemoteAdapterRegistry();
		FakeLinePipe firstPipe = new FakeLinePipe();
		registry.register(NAME, firstPipe);
		BleAdapter firstAdapter = registry.adapterFor(NAME);

		FakeLinePipe secondPipe = new FakeLinePipe();
		registry.register(NAME, secondPipe);

		assertTrue(firstPipe.wasClosedByLocal());
		assertNotSame(firstAdapter, registry.adapterFor(NAME));
		assertFalse(firstAdapter.isAlive());
	}

	@Test
	void droppedConnectionClearsRegistrationAndFiresDisconnect() throws Exception {
		DefaultRemoteAdapterRegistry registry = new DefaultRemoteAdapterRegistry();
		FakeLinePipe pipe = new FakeLinePipe();
		registry.register(NAME, pipe);
		BleAdapter adapter = registry.adapterFor(NAME);
		BlePeripheral peripheral = adapter.getPeripheral(ADDRESS);
		List<String> reasons = new CopyOnWriteArrayList<>();
		peripheral.setDisconnectListener(reasons::add);

		pipe.closePeer("remote_pipe_closed");

		assertEquals(List.of("remote_pipe_closed"), reasons);
		assertThrows(BleException.class, () -> registry.adapterFor(NAME));
	}

	@Test
	void requestNewSessionRoundTripsControlSentinelAndReplacesAdapter() throws Exception {
		DefaultRemoteAdapterRegistry registry = new DefaultRemoteAdapterRegistry();
		FakeLinePipe pipe = new FakeLinePipe();
		registry.register(NAME, pipe);
		BleAdapter oldAdapter = registry.adapterFor(NAME);

		Thread resetThread = new Thread(() -> {
			try {
				registry.requestNewSession(NAME);
			} catch (BleException ignored) {
				// asserted via registry state below
			}
		});
		resetThread.start();

		// The registry should have sent a session_reset control line - not a
		// ble-bridge command - directly over the pipe.
		waitUntil(() -> !pipe.getSent().isEmpty());
		assertEquals(SessionControl.SESSION_RESET, SessionControl.typeOf(pipe.getSent().get(0)));

		// Client-side ack.
		pipe.deliverLine(SessionControl.line(SessionControl.SESSION_READY));
		resetThread.join(2000);

		assertFalse(oldAdapter.isAlive());
		assertNotSame(oldAdapter, registry.adapterFor(NAME));

		// The underlying transport itself must survive a session reset - it's the same
		// still-open
		// connection being re-wrapped, not replaced (see
		// DefaultRemoteAdapterRegistry#register's own
		// comment). A prior bug had register()'s internal unregister-then-replace path
		// close it as a
		// side effect, which severed the connection and left the "fresh" adapter
		// unusable.
		assertFalse(pipe.wasClosedByLocal());
		assertTrue(pipe.isAlive());

		// And the new adapter must actually work: a real request reaches the still-open
		// pipe.
		BleAdapter newAdapter = registry.adapterFor(NAME);
		BlePeripheral peripheral = newAdapter.getPeripheral(ADDRESS);
		Thread connectThread = new Thread(() -> {
			try {
				peripheral.connect();
			} catch (BleException ignored) {
				// asserted via peripheral.isConnected() below
			}
		});
		connectThread.start();
		waitUntil(() -> pipe.getSent().size() > 1);
		String id = mapper.readTree(pipe.getSent().get(pipe.getSent().size() - 1)).get("id").asText();
		pipe.deliverLine("{\"type\":\"response\",\"id\":\"" + id + "\",\"ok\":true}");
		connectThread.join(2000);

		assertTrue(peripheral.isConnected());
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
