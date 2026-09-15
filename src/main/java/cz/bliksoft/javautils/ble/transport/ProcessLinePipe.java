package cz.bliksoft.javautils.ble.transport;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import cz.bliksoft.javautils.ble.BleSidecarException;

/**
 * {@link BleLinePipe} backed by a locally-spawned {@code ble-bridge} sidecar
 * process, speaking newline-delimited JSON over its stdin/stdout. This is the
 * transport {@code BleAdapter} uses for local BLE sessions; extracted from what
 * used to be inline process handling in {@code BleAdapter} itself.
 */
public class ProcessLinePipe implements BleLinePipe {

	private static final Logger LOG = Logger.getLogger(ProcessLinePipe.class.getName());

	private final Process process;
	private final OutputStream stdin;
	private final Object writeLock = new Object();
	private volatile Consumer<String> lineHandler;
	private volatile Consumer<String> closeHandler;
	private volatile boolean alive = true;
	private volatile int exitCode = -1;

	public ProcessLinePipe(File binary) throws BleSidecarException {
		try {
			ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
			pb.redirectErrorStream(false);
			this.process = pb.start();
		} catch (IOException e) {
			throw new BleSidecarException("failed to launch ble-bridge sidecar", e);
		}
		this.stdin = process.getOutputStream();

		Thread reader = new Thread(this::readLoop, "ble-bridge-reader");
		reader.setDaemon(true);
		reader.start();

		Thread stderrPump = new Thread(this::stderrLoop, "ble-bridge-stderr");
		stderrPump.setDaemon(true);
		stderrPump.start();

		Thread exitWatcher = new Thread(this::watchExit, "ble-bridge-exit-watcher");
		exitWatcher.setDaemon(true);
		exitWatcher.start();
	}

	@Override
	public void send(String jsonLine) throws IOException {
		byte[] bytes = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
		synchronized (writeLock) {
			stdin.write(bytes);
			stdin.flush();
		}
	}

	@Override
	public void onLine(Consumer<String> handler) {
		this.lineHandler = handler;
	}

	@Override
	public void onClose(Consumer<String> reasonHandler) {
		this.closeHandler = reasonHandler;
	}

	@Override
	public boolean isAlive() {
		return alive && process.isAlive();
	}

	/**
	 * The sidecar process's exit code, or {@code -1} if it hasn't exited yet (or
	 * this is read racing right after {@link #close()} returns, before the exit
	 * watcher thread has observed it). {@code 0} means {@code ble-bridge} returned
	 * from {@code main()} normally - which happens both for a graceful shutdown
	 * (stdin closed) <em>and</em> for some of its own startup failures that don't
	 * panic, so this alone doesn't distinguish "asked to close" from "unexpectedly
	 * gone" - see {@code BleAdapter}'s own {@code closing} flag for that. Useful
	 * for diagnostics: a nonzero code (e.g. 101, Rust's default panic exit code)
	 * means the sidecar actually crashed rather than returning normally.
	 */
	public int getExitCode() {
		return exitCode;
	}

	@Override
	public void close() {
		alive = false;
		try {
			stdin.close();
		} catch (IOException ignored) {
			// sidecar may already be gone
		}
		try {
			if (!process.waitFor(3, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

	private void readLoop() {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.trim().isEmpty()) {
					continue;
				}
				Consumer<String> handler = lineHandler;
				if (handler != null) {
					handler.accept(line);
				}
			}
		} catch (IOException e) {
			LOG.log(Level.FINE, "ble-bridge stdout closed", e);
		}
	}

	private void stderrLoop() {
		try (BufferedReader err = new BufferedReader(
				new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = err.readLine()) != null) {
				LOG.fine("[ble-bridge] " + line);
			}
		} catch (IOException ignored) {
			// process gone
		}
	}

	private void watchExit() {
		try {
			exitCode = process.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		alive = false;
		LOG.fine("ble-bridge process exited with code " + exitCode);
		Consumer<String> handler = closeHandler;
		if (handler != null) {
			handler.accept("sidecar_crashed");
		}
	}
}
