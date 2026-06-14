/*
 * TODO put header
 */
package eu.lighthouselabs.obd.reader.io;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.makeThreadSafe;
import static org.powermock.api.easymock.PowerMock.createMock;
import static org.powermock.api.easymock.PowerMock.replay;
import static org.powermock.api.easymock.PowerMock.verify;
import static org.powermock.api.support.membermodification.MemberModifier.suppress;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.testng.PowerMockTestCase;
import org.powermock.reflect.Whitebox;
import org.testng.annotations.Test;

import android.app.Service;
import android.bluetooth.BluetoothSocket;
import android.util.Log;
import eu.lighthouselabs.obd.commands.ObdCommand;
import eu.lighthouselabs.obd.reader.IPostListener;

/**
 * Unit tests for the shutdown / queue lifecycle of {@link ObdGatewayService}.
 *
 * These are plain JVM tests (no device / instrumentation). Because
 * {@code ObdGatewayService} extends {@link android.app.Service}, instances are
 * created with {@link Whitebox#newInstance(Class)} to bypass the Android stub
 * constructor, internal state is injected with {@code Whitebox.setInternalState},
 * and the unavoidable Android stub calls ({@link Log}, {@link Service#stopSelf()})
 * are suppressed via PowerMock. The notification manager is deliberately left
 * {@code null} -- {@code stopService()} guards against that.
 *
 * Targets PowerMock 1.4.10 + EasyMock 3.0 + TestNG (same stack as obd-api).
 */
@PrepareForTest({ Log.class, Service.class, BluetoothSocket.class })
public class ObdGatewayServiceTest extends PowerMockTestCase {

	/** Suppress the Android stub methods that would otherwise throw "Stub!". */
	private static void suppressAndroidStubs() throws Exception {
		suppress(Log.class.getDeclaredMethods());
		suppress(Service.class.getDeclaredMethod("stopSelf"));
	}

	/**
	 * Build a service instance with its internal collaborators injected. Fields
	 * are set explicitly because Whitebox.newInstance() skips field initializers.
	 */
	private static ObdGatewayService newService(BlockingQueue<ObdCommandJob> queue,
	        BluetoothSocket sock, IPostListener callback, AtomicBoolean running,
	        AtomicBoolean queueRunning) {
		ObdGatewayService svc = Whitebox.newInstance(ObdGatewayService.class);
		Whitebox.setInternalState(svc, "_queue", queue);
		Whitebox.setInternalState(svc, "_isRunning", running);
		Whitebox.setInternalState(svc, "_isQueueRunning", queueRunning);
		Whitebox.setInternalState(svc, "_callback", callback);
		if (sock != null) {
			Whitebox.setInternalState(svc, "_sock", sock);
		}
		// _notifManager intentionally left null: stopService() is null-guarded.
		return svc;
	}

	/**
	 * Stopping a service that never established a connection (e.g. no Bluetooth
	 * device selected, or connect() failed before _sock was assigned) must not
	 * throw -- it used to NPE on _sock.close().
	 */
	@Test
	public void testStopWithoutConnection() throws Exception {
		suppressAndroidStubs();

		BlockingQueue<ObdCommandJob> queue = new LinkedBlockingQueue<ObdCommandJob>();
		AtomicBoolean running = new AtomicBoolean(true);
		AtomicBoolean queueRunning = new AtomicBoolean(false);
		RecordingListener listener = new RecordingListener();
		ObdGatewayService svc = newService(queue, null, listener, running,
		        queueRunning);

		svc.stopService();

		assertFalse(running.get(), "service should be marked stopped");
		assertFalse(queueRunning.get(), "queue should be marked stopped");
		assertTrue(queue.isEmpty());
		assertEquals(listener.updates.get(), 0);
	}

	/**
	 * Stopping with jobs still queued must drop them and never call back.
	 */
	@Test
	public void testStopClearsNonEmptyQueue() throws Exception {
		suppressAndroidStubs();

		BlockingQueue<ObdCommandJob> queue = new LinkedBlockingQueue<ObdCommandJob>();
		queue.put(new ObdCommandJob(new FakeCommand()));
		queue.put(new ObdCommandJob(new FakeCommand()));
		queue.put(new ObdCommandJob(new FakeCommand()));
		AtomicBoolean running = new AtomicBoolean(true);
		AtomicBoolean queueRunning = new AtomicBoolean(false);
		RecordingListener listener = new RecordingListener();
		ObdGatewayService svc = newService(queue, null, listener, running,
		        queueRunning);

		svc.stopService();

		assertTrue(queue.isEmpty(), "pending jobs must be dropped");
		assertEquals(listener.updates.get(), 0, "no callbacks for dropped jobs");
		assertFalse(running.get());
	}

	/**
	 * A job that is in the middle of running when stopService() is called must
	 * NOT notify the (now detached) listener once it finishes.
	 */
	@Test
	public void testStopDuringExecutionSuppressesCallback() throws Exception {
		suppressAndroidStubs();

		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);

		BluetoothSocket sock = createMock(BluetoothSocket.class);
		makeThreadSafe(sock, true);
		expect(sock.getInputStream()).andReturn(new ByteArrayInputStream(new byte[0]));
		expect(sock.getOutputStream()).andReturn(new ByteArrayOutputStream());
		sock.close();
		expectLastCall().anyTimes();
		replay(sock);

		final BlockingQueue<ObdCommandJob> queue = new LinkedBlockingQueue<ObdCommandJob>();
		queue.put(new ObdCommandJob(new FakeCommand(started, release)));
		final AtomicBoolean running = new AtomicBoolean(true);
		final AtomicBoolean queueRunning = new AtomicBoolean(false);
		final RecordingListener listener = new RecordingListener();
		final ObdGatewayService svc = newService(queue, sock, listener, running,
		        queueRunning);

		final Throwable[] error = new Throwable[1];
		Thread worker = new Thread(new Runnable() {
			public void run() {
				try {
					Whitebox.invokeMethod(svc, "_executeQueue");
				} catch (Throwable t) {
					error[0] = t;
				}
			}
		});
		worker.start();

		// Wait until the job is actually running, then stop mid-execution.
		assertTrue(started.await(5, TimeUnit.SECONDS), "job should have started");
		svc.stopService();
		// Allow the in-flight job to complete now that the service is stopped.
		release.countDown();
		worker.join(5000);

		assertFalse(worker.isAlive(), "queue loop should have exited");
		assertNull(error[0], "executeQueue must not throw: " + error[0]);
		assertEquals(listener.updates.get(), 0,
		        "in-flight job must not notify the listener after stop");
		assertFalse(running.get());
		assertTrue(queue.isEmpty());
		verify(sock);
	}

	/**
	 * Calling stopService() repeatedly must be safe and close the socket only
	 * once (the reference is released after the first call).
	 */
	@Test
	public void testRepeatedStopIsIdempotent() throws Exception {
		suppressAndroidStubs();

		BluetoothSocket sock = createMock(BluetoothSocket.class);
		sock.close();
		expectLastCall().once();
		replay(sock);

		BlockingQueue<ObdCommandJob> queue = new LinkedBlockingQueue<ObdCommandJob>();
		AtomicBoolean running = new AtomicBoolean(true);
		AtomicBoolean queueRunning = new AtomicBoolean(false);
		ObdGatewayService svc = newService(queue, sock, new RecordingListener(),
		        running, queueRunning);

		svc.stopService();
		svc.stopService();

		verify(sock); // close() invoked exactly once across both calls
		assertFalse(running.get());
		assertTrue(queue.isEmpty());
	}

	/**
	 * After a stop, a fresh connection can be installed and the queue processed
	 * again -- references released on stop must not block a restart.
	 */
	@Test
	public void testRestartAfterStop() throws Exception {
		suppressAndroidStubs();

		BluetoothSocket first = createMock(BluetoothSocket.class);
		first.close();
		expectLastCall().once();
		replay(first);

		BlockingQueue<ObdCommandJob> queue = new LinkedBlockingQueue<ObdCommandJob>();
		AtomicBoolean running = new AtomicBoolean(true);
		AtomicBoolean queueRunning = new AtomicBoolean(false);
		RecordingListener firstListener = new RecordingListener();
		ObdGatewayService svc = newService(queue, first, firstListener, running,
		        queueRunning);

		svc.stopService();
		verify(first);
		assertFalse(running.get());

		// Restart: install a new socket + listener, re-arm the flags, enqueue.
		BluetoothSocket second = createMock(BluetoothSocket.class);
		expect(second.getInputStream()).andReturn(new ByteArrayInputStream(new byte[0]));
		expect(second.getOutputStream()).andReturn(new ByteArrayOutputStream());
		replay(second);

		RecordingListener secondListener = new RecordingListener();
		Whitebox.setInternalState(svc, "_sock", second);
		Whitebox.setInternalState(svc, "_callback", secondListener);
		running.set(true);
		queueRunning.set(false);
		queue.put(new ObdCommandJob(new FakeCommand()));

		Whitebox.invokeMethod(svc, "_executeQueue");

		verify(second);
		assertEquals(secondListener.updates.get(), 1,
		        "restarted service should process and report the job");
		assertTrue(queue.isEmpty());
	}

	/**
	 * Records listener callbacks so tests can assert whether (and how often) the
	 * service reported job updates.
	 */
	private static final class RecordingListener implements IPostListener {
		final AtomicInteger updates = new AtomicInteger(0);
		volatile ObdCommandJob last = null;

		public void stateUpdate(ObdCommandJob job) {
			updates.incrementAndGet();
			last = job;
		}
	}

	/**
	 * Test command whose run() can optionally block on a latch, allowing a test
	 * to interleave stopService() with an in-flight job.
	 */
	private static final class FakeCommand extends ObdCommand {
		private final CountDownLatch started;
		private final CountDownLatch release;

		FakeCommand() {
			this(null, null);
		}

		FakeCommand(CountDownLatch started, CountDownLatch release) {
			super("TEST");
			this.started = started;
			this.release = release;
		}

		@Override
		public void run(InputStream in, OutputStream out) throws IOException,
		        InterruptedException {
			if (started != null) {
				started.countDown();
			}
			if (release != null) {
				release.await(5, TimeUnit.SECONDS);
			}
		}

		@Override
		public String getFormattedResult() {
			return "TEST";
		}

		@Override
		public String getName() {
			return "TEST";
		}
	}

}
