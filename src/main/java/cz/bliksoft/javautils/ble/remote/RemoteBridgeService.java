package cz.bliksoft.javautils.ble.remote;

import java.io.Closeable;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embeds a {@link RemoteAdapterClient} as an app-lifetime background service: while running, dials out
 * to a {@link RemoteAdapterServer} (or any RFC 6455-compliant equivalent, e.g. a Spring-hosted one - see
 * {@code StorageManagerServer}'s {@code BleRemoteWebSocketHandler}) at {@code .../ble-remote} using this
 * machine's own BLE radio, so the remote host can route a BLE print job through this machine instead of
 * needing its own local BLE hardware. The client-side mirror of running the standalone
 * {@code common-java-utils-ble ... --remote} jar by hand (see {@code Main}'s {@code --remote} flag) -
 * {@link RemoteAdapterClient#start()} already owns its own reconnect/backoff loop entirely, so this class
 * only needs to translate a plain server URL into the right WebSocket URI and forward lifecycle calls.
 *
 * <p>
 * Deliberately no {@code FileObject}/XmlFilesystem coupling here, unlike an app-framework-facing wrapper
 * around this class might have (see {@code BSToolbox-jfx-print}'s own {@code RemoteBleBridgeService},
 * which adapts a {@code /services} config block into this constructor) - this class stays usable by any
 * plain-Java consumer, not just apps built on that framework.
 */
public class RemoteBridgeService implements Closeable {

	private static final Logger LOG = Logger.getLogger(RemoteBridgeService.class.getName());

	private final String name;
	private final RemoteAdapterClient client;

	public RemoteBridgeService(String serverUrl, String name, String token) {
		this.name = name;
		URI wsUri = toWebSocketUri(serverUrl);
		this.client = new RemoteAdapterClient(wsUri, name, token);
		client.setOnFatalError(e -> LOG.log(Level.WARNING, "BLE remote bridge '" + name + "' failed: " + e.getMessage(), e));
		client.start();
		LOG.info("Started BLE remote bridge '" + name + "' -> " + wsUri);
	}

	/**
	 * {@code https://<host>[:port][/path]} -&gt; {@code wss://<host>[:port][/path]/ble-remote} (and
	 * {@code http} -&gt; {@code ws}, for local/dev servers not running behind TLS) - accepting a plain
	 * server URL rather than requiring the caller to already know the raw WebSocket path/URI.
	 */
	private static URI toWebSocketUri(String serverUrl) {
		if (serverUrl == null || serverUrl.trim().isEmpty()) {
			throw new IllegalArgumentException("RemoteBridgeService has no server URL configured");
		}
		String trimmed = serverUrl.trim();
		if (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		String wsBase;
		if (trimmed.startsWith("https://")) {
			wsBase = "wss://" + trimmed.substring("https://".length());
		} else if (trimmed.startsWith("http://")) {
			wsBase = "ws://" + trimmed.substring("http://".length());
		} else if (trimmed.startsWith("wss://") || trimmed.startsWith("ws://")) {
			wsBase = trimmed;
		} else {
			throw new IllegalArgumentException(
					"RemoteBridgeService server URL must start with http(s):// or ws(s)://, got: " + serverUrl);
		}
		return URI.create(wsBase + "/ble-remote");
	}

	@Override
	public void close() {
		LOG.info("Stopping BLE remote bridge '" + name + "'");
		client.close();
	}
}
