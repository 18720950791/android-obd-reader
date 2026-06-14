/*
 * Tests for ObdGatewayService stop/restart bug fixes.
 *
 * The Android SDK jar contains only compilation stubs (all methods throw
 * "Stub!"), making it impossible to instantiate Service subclasses at test
 * time without a full Android runtime.  Therefore, these tests verify the
 * correctness of the fixed stop/queue logic by exercising the same data
 * structures and algorithms that ObdGatewayService uses internally, via a
 * lightweight harness that replicates the service's state management.
 *
 * Covers:
 *   1. stopService() with null socket (NPE fix)
 *   2. stopService() with pending jobs in queue (queue drain + QUEUE_ERROR)
 *   3. stopService() during queue execution (no stale callback after stop)
 *   4. Repeated stopService() calls (idempotency)
 *   5. Service state clean enough to restart after stop
 */
package eu.lighthouselabs.obd.reader.io;

import static org.easymock.EasyMock.createMock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import eu.lighthouselabs.obd.reader.IPostListener;
import eu.lighthouselabs.obd.reader.io.ObdCommandJob.ObdCommandJobState;

public class ObdGatewayServiceTest {

    /**
     * Lightweight harness that replicates ObdGatewayService's stop / queue
     * state management, allowing us to test the exact same logic without
     * depending on the Android runtime.
     */
    static class ServiceStateHarness {
        final BlockingQueue<ObdCommandJob> queue =
                new LinkedBlockingQueue<ObdCommandJob>();
        final AtomicBoolean isRunning = new AtomicBoolean(false);
        final AtomicBoolean isQueueRunning = new AtomicBoolean(false);
        IPostListener callback;
        Object sock; // stands in for BluetoothSocket
        Object dev; // stands in for BluetoothDevice
        long queueCounter = 0L;

        /** Mirrors the fixed stopService() logic. */
        void stopService() {
            if (!isRunning.compareAndSet(true, false)) {
                closeSocketQuietly();
                return;
            }

            isQueueRunning.set(false);

            ArrayList<ObdCommandJob> drained = new ArrayList<ObdCommandJob>();
            queue.drainTo(drained);
            for (ObdCommandJob job : drained) {
                job.setState(ObdCommandJobState.QUEUE_ERROR);
            }

            callback = null;
            closeSocketQuietly();
        }

        /** Mirrors the fixed closeSocketQuietly() logic. */
        void closeSocketQuietly() {
            try {
                if (sock != null) {
                    // In production: _sock.close()
                    // Here we just verify the null-check path works.
                }
            } catch (Exception e) {
                // ignored
            }
            sock = null;
            dev = null;
        }

        /** Mirrors the fixed queueJob() logic. */
        Long queueJob(ObdCommandJob job) {
            if (!isRunning.get()) {
                job.setState(ObdCommandJobState.QUEUE_ERROR);
                return null;
            }
            queueCounter++;
            job.setId(queueCounter);
            try {
                queue.put(job);
            } catch (InterruptedException e) {
                job.setState(ObdCommandJobState.QUEUE_ERROR);
            }
            return queueCounter;
        }

        /**
         * Mirrors the fixed _executeQueue() loop body: checks _isRunning
         * before processing and before invoking callback.
         */
        void executeQueueIteration() {
            isQueueRunning.set(true);
            while (isRunning.get()) {
                ObdCommandJob job = null;
                try {
                    job = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (job == null) continue;
                    if (!isRunning.get()) {
                        job.setState(ObdCommandJobState.QUEUE_ERROR);
                        break;
                    }
                    if (job.getState().equals(ObdCommandJobState.NEW)) {
                        job.setState(ObdCommandJobState.RUNNING);
                        job.getCommand().run(null, null);
                    }
                } catch (Exception e) {
                    if (job != null) {
                        job.setState(ObdCommandJobState.EXECUTION_ERROR);
                    }
                }
                if (job != null) {
                    job.setState(ObdCommandJobState.FINISHED);
                    IPostListener cb = callback;
                    if (isRunning.get() && cb != null) {
                        cb.stateUpdate(job);
                    }
                }
            }
            isQueueRunning.set(false);
        }
    }

    private ServiceStateHarness h;

    @BeforeMethod
    public void setUp() {
        h = new ServiceStateHarness();
    }

    // ----------------------------------------------------------------
    // 1. Null socket — stop must not throw NPE
    // ----------------------------------------------------------------

    @Test
    public void testStopWithNullSocket() {
        h.isRunning.set(true);
        h.sock = null;
        h.stopService(); // must NOT throw
        assertFalse(h.isRunning.get());
    }

    @Test
    public void testStopWithNullSocketNeverStarted() {
        // isRunning is false, sock is null
        h.stopService();
        assertFalse(h.isRunning.get());
        assertNull(h.sock);
    }

    @Test
    public void testStopWithNullSocketResourcesReleased() {
        h.isRunning.set(true);
        h.sock = new Object(); // simulate non-null socket
        h.dev = new Object();
        h.callback = createMock(IPostListener.class);

        h.stopService();

        assertFalse(h.isRunning.get());
        assertTrue(h.queue.isEmpty());
        assertNull(h.callback);
        assertNull(h.sock);
        assertNull(h.dev);
    }

    // ----------------------------------------------------------------
    // 2. Non-empty queue — drained and marked QUEUE_ERROR
    // ----------------------------------------------------------------

    @Test
    public void testStopWithNonEmptyQueue() {
        h.isRunning.set(true);

        ObdCommandJob job1 = createDummyJob();
        ObdCommandJob job2 = createDummyJob();
        ObdCommandJob job3 = createDummyJob();
        h.queue.add(job1);
        h.queue.add(job2);
        h.queue.add(job3);
        assertEquals(h.queue.size(), 3);

        h.stopService();

        assertTrue(h.queue.isEmpty(), "Queue should be drained");
        assertEquals(job1.getState(), ObdCommandJobState.QUEUE_ERROR);
        assertEquals(job2.getState(), ObdCommandJobState.QUEUE_ERROR);
        assertEquals(job3.getState(), ObdCommandJobState.QUEUE_ERROR);
    }

    @Test
    public void testStopWithEmptyQueue() {
        h.isRunning.set(true);
        h.stopService();

        assertTrue(h.queue.isEmpty());
        assertFalse(h.isRunning.get());
        assertFalse(h.isQueueRunning.get());
    }

    // ----------------------------------------------------------------
    // 3. Mid-execution stop — no stale callback after stop
    // ----------------------------------------------------------------

    @Test
    public void testStopDuringQueueExecutionNoStaleCallback()
            throws Exception {
        h.isRunning.set(true);

        final boolean[] callbackInvoked = {false};
        h.callback = new IPostListener() {
            @Override
            public void stateUpdate(ObdCommandJob job) {
                callbackInvoked[0] = true;
            }
        };

        // Use a concrete slow command so the queue thread blocks during
        // execution, giving us a reliable window to call stopService().
        ObdCommandJob job = createSlowJob(500);
        h.queue.add(job);

        // Start queue execution in a background thread
        Thread queueThread = new Thread(new Runnable() {
            @Override
            public void run() {
                h.executeQueueIteration();
            }
        });
        queueThread.start();

        // Wait for the job to be picked up and start executing
        Thread.sleep(100);

        // Now stop the service while the command is still running
        h.stopService();

        // Wait for the queue thread to finish
        queueThread.join(3000);
        assertFalse(queueThread.isAlive(),
                "Queue thread should have exited after stop");

        // The callback should NOT have been invoked after stop
        assertFalse(callbackInvoked[0],
                "Callback must not be invoked after stopService");
    }

    @Test
    public void testExecuteQueueExitsWhenNotRunning() throws Exception {
        // isRunning is false (default)
        ObdCommandJob job = createDummyJob();
        h.queue.add(job);

        // Execute in background thread
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                h.executeQueueIteration();
            }
        });
        t.start();
        t.join(2000);

        // Queue thread should have exited immediately
        assertFalse(t.isAlive());
        assertFalse(h.queue.isEmpty(),
                "Job should remain in queue when not running");
        assertEquals(job.getState(), ObdCommandJobState.NEW);
        assertFalse(h.isQueueRunning.get());
    }

    // ----------------------------------------------------------------
    // 4. Repeated stop — must be idempotent
    // ----------------------------------------------------------------

    @Test
    public void testRepeatedStopIdempotent() {
        h.isRunning.set(true);
        h.stopService();
        h.stopService();
        h.stopService();
        assertFalse(h.isRunning.get());
    }

    @Test
    public void testRepeatedStopNeverStarted() {
        h.stopService();
        h.stopService();
        h.stopService();
        assertFalse(h.isRunning.get());
        assertNull(h.sock);
    }

    // ----------------------------------------------------------------
    // 5. Restart after stop — clean state for new connections
    // ----------------------------------------------------------------

    @Test
    public void testRestartAfterStop() {
        h.isRunning.set(true);
        h.sock = new Object();
        h.dev = new Object();
        h.callback = createMock(IPostListener.class);
        h.queue.add(createDummyJob());
        h.queue.add(createDummyJob());

        h.stopService();

        // Verify clean state
        assertFalse(h.isRunning.get());
        assertTrue(h.queue.isEmpty());
        assertNull(h.sock);
        assertNull(h.dev);
        assertNull(h.callback);

        // Simulate restart
        h.isRunning.set(true);

        // Verify service accepts new work
        ObdCommandJob newJob = createDummyJob();
        Long id = h.queueJob(newJob);
        assertNotNull(id);
        assertEquals(id, Long.valueOf(1L));
        assertEquals(h.queue.size(), 1);
        assertEquals(newJob.getState(), ObdCommandJobState.NEW);
    }

    @Test
    public void testQueueJobRejectedWhenNotRunning() {
        ObdCommandJob job = createDummyJob();
        Long result = h.queueJob(job);

        assertNull(result, "queueJob should return null when not running");
        assertEquals(job.getState(), ObdCommandJobState.QUEUE_ERROR);
        assertTrue(h.queue.isEmpty());
    }

    @Test
    public void testQueueJobAcceptedWhenRunning() {
        h.isRunning.set(true);
        ObdCommandJob job = createDummyJob();
        Long result = h.queueJob(job);

        assertNotNull(result);
        assertEquals(result, Long.valueOf(1L));
        assertEquals(job.getState(), ObdCommandJobState.NEW);
        assertEquals(h.queue.size(), 1);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private ObdCommandJob createDummyJob() {
        eu.lighthouselabs.obd.commands.ObdCommand mockCmd =
                createMock(eu.lighthouselabs.obd.commands.ObdCommand.class);
        return new ObdCommandJob(mockCmd);
    }

    /**
     * Create a job with a concrete command that sleeps for the given
     * duration, allowing us to test stop-during-execution reliably.
     */
    private ObdCommandJob createSlowJob(final long sleepMs) {
        eu.lighthouselabs.obd.commands.ObdCommand slowCmd =
                new eu.lighthouselabs.obd.commands.ObdCommand("") {
            @Override
            public void run(java.io.InputStream in, java.io.OutputStream out)
                    throws IOException, InterruptedException {
                Thread.sleep(sleepMs);
            }
            @Override
            public String getFormattedResult() { return ""; }
            @Override
            public String getName() { return "SlowTestCommand"; }
        };
        return new ObdCommandJob(slowCmd);
    }
}
