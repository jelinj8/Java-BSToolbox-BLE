package cz.bliksoft.javautils.ble.remote;

import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.transport.BleLinePipe;

/**
 * Registry a host application registers named remote connections into, so it
 * can obtain a {@link BleAdapter} for BLE hardware attached to some other
 * machine instead of the one this JVM runs on.
 * <p>
 * The host owns the actual transport (WebSocket session, raw socket, ...) and
 * is responsible for authenticating a connection <em>before</em> calling
 * {@link #register}; this registry has no auth policy of its own. At most one
 * connection may be registered per name at a time - registering a new
 * {@link BleLinePipe} under a name already in use replaces (and disconnects)
 * the previous one.
 * <p>
 * A dropped connection tears down its {@code BleAdapter} exactly like a local
 * sidecar crash does (fires disconnects, fails pending requests) and clears the
 * registration - the host must call {@link #adapterFor} again after a reconnect
 * to get a new {@code BleAdapter}; this registry never transparently rebinds
 * one.
 * <p>
 * <b>Concurrency:</b> implementations only guarantee their own name-&gt;adapter
 * bookkeeping stays consistent under concurrent {@link #register}/
 * {@link #unregister}/{@link #requestNewSession} calls - they do <em>not</em>
 * protect a caller mid-operation on an adapter obtained from
 * {@link #adapterFor} from another thread concurrently replacing or resetting
 * that same name. A {@code register} (new connection under an in-use name) or
 * {@code requestNewSession} call can tear down the exact {@code BleAdapter} a
 * different thread is actively using via {@code BlePeripheral} at any moment,
 * surfacing as a normal disconnect/{@code BleSidecarException} to that thread's
 * in-flight call - the same way an actual network drop would. If a host needs
 * to prevent that "stolen mid-operation" race for a given name, it must add its
 * own coordination (e.g. serializing registration-changing calls and adapter
 * usage for that name through the same executor/lock); this registry does not
 * do it for you.
 */
public interface RemoteAdapterRegistry {

	/**
	 * Registers {@code pipe} under {@code name}, replacing (and closing) any
	 * connection currently registered under that name.
	 */
	void register(String name, BleLinePipe pipe);

	/** Closes and removes the connection registered under {@code name}, if any. */
	void unregister(String name);

	/**
	 * Returns the {@code BleAdapter} backed by the connection currently registered
	 * under {@code name}, for use exactly like a locally-spawned one. The same
	 * instance is returned across calls until the connection drops or
	 * {@link #unregister} is called.
	 *
	 * @throws BleException if nothing is currently registered under that name
	 */
	BleAdapter adapterFor(String name) throws BleException;

	/**
	 * Asks the client currently registered under {@code name} to discard its local
	 * sidecar and spawn a fresh one, without dropping the underlying connection -
	 * the remote-session equivalent of closing a local {@code BleAdapter} and
	 * constructing a new one. Tears down the cached {@code BleAdapter} for
	 * {@code name} exactly like a dropped connection once the client acknowledges;
	 * the host must call {@link #adapterFor} again afterward to obtain the new one.
	 *
	 * @throws BleException if nothing is currently registered under that name, or
	 *                      the client doesn't acknowledge in time
	 */
	void requestNewSession(String name) throws BleException;
}
