package cz.bliksoft.javautils.ble.remote;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.transport.BleLinePipe;

/**
 * Default {@link RemoteAdapterRegistry} implementation: strictly one active
 * connection per name, no auth policy of its own (see the interface doc).
 */
public class DefaultRemoteAdapterRegistry implements RemoteAdapterRegistry {

	private static final long SESSION_RESET_TIMEOUT_MS = 15000;

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	private static final class Entry {
		final BleLinePipe rawPipe;
		final BleAdapter adapter;
		volatile CompletableFuture<Void> sessionReadyFuture;

		Entry(BleLinePipe rawPipe, BleAdapter adapter) {
			this.rawPipe = rawPipe;
			this.adapter = adapter;
		}
	}

	@Override
	public synchronized void register(String name, BleLinePipe pipe) {
		// Not a plain unregister(name): that always calls entry.adapter.close(), which
		// cascades
		// (BleAdapter.close() -> ControlAwarePipe.close() -> raw.close()) all the way
		// down to the
		// *transport* - correct when replacing one connection with a genuinely
		// different one, but
		// requestNewSession's re-registration below hands back the exact same
		// still-open rawPipe it
		// just got a session_ready ack over, specifically to keep reusing it. Closing
		// that pipe here
		// would kill the very connection register() is about to re-wrap, before the new
		// BleAdapter
		// even gets a chance to use it - confirmed on real hardware (every
		// requestNewSession call
		// silently severed the underlying WebSocket, so the "fresh" adapter handed back
		// was already
		// dead). So: only fully close() the old adapter (and its pipe) when it's a
		// genuinely
		// different pipe; for the identical-pipe reuse case, invalidate() the old
		// adapter instead -
		// same "no longer alive, pending requests fail" outcome for anyone still
		// holding it, without
		// touching the transport the new adapter is about to take over.
		Entry old = entries.remove(name);
		if (old != null) {
			if (old.rawPipe != pipe) {
				old.adapter.close();
			} else {
				old.adapter.invalidate();
			}
		}
		Entry[] holder = new Entry[1];
		ControlAwarePipe wrapped = new ControlAwarePipe(pipe, reason -> entries.remove(name, holder[0]), () -> {
			Entry current = holder[0];
			CompletableFuture<Void> ready = current != null ? current.sessionReadyFuture : null;
			if (ready != null) {
				ready.complete(null);
			}
		});
		Entry entry = new Entry(pipe, new BleAdapter(wrapped));
		holder[0] = entry;
		entries.put(name, entry);
	}

	@Override
	public synchronized void unregister(String name) {
		Entry entry = entries.remove(name);
		if (entry != null) {
			entry.adapter.close();
		}
	}

	@Override
	public BleAdapter adapterFor(String name) throws BleException {
		Entry entry = entries.get(name);
		if (entry == null) {
			throw new BleException("no remote adapter currently registered under name '" + name + "'");
		}
		return entry.adapter;
	}

	@Override
	public synchronized void requestNewSession(String name) throws BleException {
		Entry entry = entries.get(name);
		if (entry == null) {
			throw new BleException("no remote adapter currently registered under name '" + name + "'");
		}
		CompletableFuture<Void> ready = new CompletableFuture<>();
		entry.sessionReadyFuture = ready;
		try {
			entry.rawPipe.send(SessionControl.line(SessionControl.SESSION_RESET));
		} catch (IOException e) {
			throw new BleException("failed to send session_reset to '" + name + "'", e);
		}
		try {
			ready.get(SESSION_RESET_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			throw new BleException("remote client did not acknowledge session reset for '" + name + "'", e);
		}
		// Re-wrap the same still-open connection as a fresh session - register()
		// recognizes this is
		// the identical rawPipe being handed back and leaves the transport itself
		// untouched (see its
		// own comment), only discarding the now-stale BleAdapter/ControlAwarePipe
		// wrapper.
		register(name, entry.rawPipe);
	}
}
