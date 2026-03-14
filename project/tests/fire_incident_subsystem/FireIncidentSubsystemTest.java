package fire_incident_subsystem;

import Scheduler.Scheduler;
import fire_incident_subsystem.FireIncidentSubsystem;
import fire_incident_subsystem.FireRequest;
import org.junit.jupiter.api.Test;
import types.UdpUtil;

import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class FireIncidentSubsystemTest {

    @Test
    void readsIncidentCsvAndSubmitsEventsToLocalSchedulerInCsvOrder() {
        // the incident reader must preserve the CSV order and submit the exact fire details
        // the scheduler expects, then wait for acknowledgements before terminating.
        RecordingScheduler scheduler = new RecordingScheduler();
        FireIncidentSubsystem subsystem = new FireIncidentSubsystem(
                scheduler,
                null,
                resolveSampleCsv("test_mixed_scenario.csv"),
                1000);

        subsystem.run();

        List<FireRequest> submitted = scheduler.getSubmittedRequests();
        assertEquals(3, submitted.size());

        assertEquals(LocalTime.of(12, 0), submitted.get(0).getTime());
        assertEquals(1, submitted.get(0).getZoneId());
        assertEquals("MODERATE", submitted.get(0).getSeverity().name());

        assertEquals(LocalTime.of(12, 0, 10), submitted.get(1).getTime());
        assertEquals(3, submitted.get(1).getZoneId());
        assertEquals("LOW", submitted.get(1).getSeverity().name());

        assertEquals(LocalTime.of(12, 1), submitted.get(2).getTime());
        assertEquals(2, submitted.get(2).getZoneId());
        assertEquals("HIGH", submitted.get(2).getSeverity().name());
    }

    @Test
    void sendsUdpFireRequestsAndWaitsForAllCompletionAcknowledgements() throws Exception {
        // the fire subsystem must emit one REQUEST packet per incident and remain alive until
        //it receives one completion acknowledgement for every submitted fire.
        List<String> receivedRequests = new ArrayList<>();
        int completionPort = reserveEphemeralPort();

        try (DatagramSocket schedulerSocket = new DatagramSocket(0)) {
            schedulerSocket.setSoTimeout(3000);
            int schedulerPort = schedulerSocket.getLocalPort();
            CountDownLatch receivedAllRequests = new CountDownLatch(1);
            AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();

            Thread schedulerListener = new Thread(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        receivedRequests.add(UdpUtil.receive(schedulerSocket));
                    }
                    receivedAllRequests.countDown();

                    Thread.sleep(250);
                    UdpUtil.send("localhost", completionPort, "COMP|1|true");
                    UdpUtil.send("localhost", completionPort, "COMP|2|true");
                    UdpUtil.send("localhost", completionPort, "COMP|3|true");
                } catch (Exception e) {
                    backgroundFailure.set(e);
                }
            }, "fire-udp-scheduler-listener");
            schedulerListener.start();

            FireIncidentSubsystem subsystem = new FireIncidentSubsystem(
                    null,
                    null,
                    resolveSampleCsv("test_consecutive_missions.csv"),
                    100,
                    true,
                    "localhost",
                    schedulerPort,
                    completionPort);

            Thread fireThread = new Thread(subsystem, "fire-subsystem-udp-test");
            fireThread.start();

            assertTrue(receivedAllRequests.await(4, TimeUnit.SECONDS),
                    "Expected the fire subsystem to send all UDP REQ packets.");

            fireThread.join(5000);
            schedulerListener.join(3000);

            Throwable listenerFailure = backgroundFailure.get();
            if (listenerFailure instanceof SocketTimeoutException) {
                fail("Timed out while waiting for REQ packets from the fire subsystem.", listenerFailure);
            }
            if (listenerFailure != null) {
                fail("UDP scheduler-side test harness failed: " + listenerFailure.getMessage(), listenerFailure);
            }

            assertFalse(fireThread.isAlive(),
                    "Fire subsystem should exit after all UDP completion acknowledgements arrive.");
            assertFalse(schedulerListener.isAlive(),
                    "Scheduler-side UDP harness should finish after sending all completion acknowledgements.");

            assertEquals(List.of(
                    "REQ|12:00|1|FIRE_DETECTED|LOW",
                    "REQ|12:00:05|2|FIRE_DETECTED|LOW",
                    "REQ|12:00:10|3|FIRE_DETECTED|LOW"),
                    receivedRequests);
        }
    }

    private String resolveSampleCsv(String fileName) {
        List<Path> candidates = List.of(
                Path.of("project", "sampleData", fileName),
                Path.of("sampleData", fileName));

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toString();
            }
        }

        fail("Could not resolve sample CSV path for " + fileName);
        return fileName;
    }

    private int reserveEphemeralPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static class RecordingScheduler extends Scheduler {
        private final List<FireRequest> submittedRequests = new ArrayList<>();
        private final LinkedBlockingQueue<FireRequest> completions = new LinkedBlockingQueue<>();

        @Override
        public synchronized void putRequest(FireRequest req) {
            submittedRequests.add(req);

            FireRequest ack = new FireRequest(req.getTime(), req.getZoneId(), req.getType(), req.getSeverity());
            ack.setResolved(true);
            completions.add(ack);
        }

        @Override
        public synchronized FireRequest getCompletion() {
            try {
                return completions.poll(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        private List<FireRequest> getSubmittedRequests() {
            return List.copyOf(submittedRequests);
        }
    }
}
