package cz.bliksoft.javautils.ble.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.BlePeripheral;

/**
 * Real end-to-end test: a genuine {@code java.net.http.WebSocket} client (the same implementation
 * {@link RemoteAdapterClient} uses) against a real {@link RemoteAdapterServer} bound to a real loopback
 * socket, with a real {@link DefaultRemoteAdapterRegistry} - proves the hand-rolled handshake/frame codec
 * actually interoperates with a spec-compliant client in both directions, not just that it doesn't crash.
 * Mirrors {@link DefaultRemoteAdapterRegistryTest}'s own "issue a real BleAdapter command, deliver a
 * matching response" pattern, just over a real socket instead of a {@code FakeLinePipe}.
 */
class RemoteAdapterServerTest {

	private static final String TOKEN = "s3cr3t";
	private static final String NAME = "bridge-1";
	private static final String ADDRESS = "AA:BB:CC:DD:EE:FF";

	private final ObjectMapper mapper = new ObjectMapper();
	private RemoteAdapterServer server;

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.close();
		}
	}

	@Test
	void acceptsHandshakeAndTracksConnectionLifecycle() throws Exception {
		DefaultRemoteAdapterRegistry registry = new DefaultRemoteAdapterRegistry();
		server = new RemoteAdapterServer(0, TOKEN, registry);
		server.start();

		WebSocket ws = connect(server.getLocalPort(), NAME, TOKEN);
		waitUntil(() -> isRegistered(registry, NAME));

		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
		waitUntil(() -> !isRegistered(registry, NAME));
	}

	@Test
	void roundTripsRealBleAdapterCommandAndResponse() throws Exception {
		DefaultRemoteAdapterRegistry registry = new DefaultRemoteAdapterRegistry();
		server = new RemoteAdapterServer(0, TOKEN, registry);
		server.start();

		LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
		StringBuilder assembling = new StringBuilder();
		WebSocket ws = connectWithListener(server.getLocalPort(), NAME, TOKEN, (data, last) -> {
			assembling.append(data);
			if (last) {
				received.add(assembling.toString());
				assembling.setLength(0);
			}
		});
		try {
			waitUntil(() -> isRegistered(registry, NAME));

			BleAdapter adapter = registry.adapterFor(NAME);
			BlePeripheral peripheral = adapter.getPeripheral(ADDRESS);
			Thread connectThread = new Thread(() -> {
				try {
					peripheral.connect();
				} catch (BleException ignored) {
					// asserted via peripheral.isConnected() below
				}
			});
			connectThread.start();

			// Server -> client: BleAdapter wrote a real "connect" command through the registry-backed
			// pipe - if it arrives here as a well-formed WebSocket text frame a real client can decode,
			// the server's hand-rolled frame writer/masking is correct.
			String commandLine = received.poll(5, TimeUnit.SECONDS);
			assertEquals("connect", mapper.readTree(commandLine).get("cmd").asText());
			String id = mapper.readTree(commandLine).get("id").asText();

			// Client -> server: java.net.http.WebSocket always masks outgoing frames per RFC 6455 - if
			// the server's frame reader unmasks correctly, this response reaches BleAdapter and
			// completes the pending connect().
			ws.sendText("{\"type\":\"response\",\"id\":\"" + id + "\",\"ok\":true}", true).get(2, TimeUnit.SECONDS);
			connectThread.join(2000);

			assertTrue(peripheral.isConnected());
		} finally {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
		}
	}

	@Test
	void rejectsMissingNameHeader() {
		DefaultRemoteAdapterRegistry registry = new DefaultRemoteAdapterRegistry();
		server = new RemoteAdapterServer(0, TOKEN, registry);
		ExecutionException ex = assertThrows(ExecutionException.class, () -> {
			server.start();
			HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer " + TOKEN)
					.buildAsync(URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/ble-remote"),
							new WebSocket.Listener() {
							})
					.get(5, TimeUnit.SECONDS);
		});
		assertTrue(ex.getCause() instanceof WebSocketHandshakeException);
		assertEquals(400, ((WebSocketHandshakeException) ex.getCause()).getResponse().statusCode());
	}

	@Test
	void rejectsWrongToken() throws Exception {
		DefaultRemoteAdapterRegistry registry = new DefaultRemoteAdapterRegistry();
		server = new RemoteAdapterServer(0, TOKEN, registry);
		server.start();

		ExecutionException ex = assertThrows(ExecutionException.class,
				() -> HttpClient.newHttpClient().newWebSocketBuilder()
						.header(RemoteAdapterServer.NAME_HEADER, NAME).header("Authorization", "Bearer wrong-token")
						.buildAsync(URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/ble-remote"),
								new WebSocket.Listener() {
								})
						.get(5, TimeUnit.SECONDS));
		assertTrue(ex.getCause() instanceof WebSocketHandshakeException);
		assertEquals(401, ((WebSocketHandshakeException) ex.getCause()).getResponse().statusCode());
	}

	private static boolean isRegistered(DefaultRemoteAdapterRegistry registry, String name) {
		try {
			registry.adapterFor(name);
			return true;
		} catch (BleException e) {
			return false;
		}
	}

	private static WebSocket connect(int port, String name, String token)
			throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
		return connectWithListener(port, name, token, (data, last) -> {
		});
	}

	private interface TextHandler {
		void onText(CharSequence data, boolean last);
	}

	private static WebSocket connectWithListener(int port, String name, String token, TextHandler handler)
			throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
		HttpClient client = HttpClient.newHttpClient();
		WebSocket.Builder builder = client.newWebSocketBuilder().header(RemoteAdapterServer.NAME_HEADER, name)
				.header("Authorization", "Bearer " + token);
		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				handler.onText(data, last);
				webSocket.request(1);
				return null;
			}
		};
		return builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/ble-remote"), listener).get(5,
				TimeUnit.SECONDS);
	}

	private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 3000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("condition not met within timeout");
			}
			Thread.sleep(20);
		}
	}
}
