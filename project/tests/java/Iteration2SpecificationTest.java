package java;

import Drone_subsystem.Drone;
import Drone_subsystem.Zone;
import Scheduler.Scheduler;
import fire_incident_subsystem.FireRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.EventType;
import types.Severity;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class Iteration2SpecificationTest {

    private static final LocalTime BASE_TIME = LocalTime.of(12, 0);
    private static final long DEFAULT_TIMEOUT_MS = 2500;
    private Thread schedulerThread;

    @AfterEach
    void tearDown() {
        if (schedulerThread != null) {
            schedulerThread.interrupt();
            try {
                schedulerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Drone.resetDefaults();
    }

    @Test
    void dispatchesSingleIncidentAndReturnsToBaseAfterCompletion() {
        Scheduler scheduler = startScheduler(buildNominalZones());
        scheduler.putRequest(request(1, Severity.LOW, 0));

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(first.isReturnToBase());
        assertNotNull(first.getMission());
        assertEquals(1, first.getMission().getZoneId());

        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.EN_ROUTE, missionId, 12.5, 590, "en route"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 12.5, 580, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 2.5, 570, "complete"));

        FireRequest completion = awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);
        assertNotNull(completion);
        assertTrue(completion.isResolved());
        assertEquals(1, completion.getZoneId());

        DispatchCommand returnCmd = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertTrue(returnCmd.isReturnToBase());
    }

    @Test
    void queuesAdditionalIncidentsWhileDroneIsBusy() {
        Scheduler scheduler = startScheduler(buildNominalZones());
        scheduler.putRequest(request(1, Severity.MODERATE, 0));
        scheduler.putRequest(request(2, Severity.HIGH, 1));
        scheduler.putRequest(request(3, Severity.LOW, 2));

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(first.isReturnToBase());
        assertEquals(1, first.getMission().getZoneId());

        waitUntil(() -> scheduler.getActiveFires() == 3, DEFAULT_TIMEOUT_MS,
                "Expected 3 active incidents (1 in progress + 2 queued).");

        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 10.0, 560, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 8.0, 550, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand second = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(second.isReturnToBase());
        assertEquals(2, second.getMission().getZoneId());
    }

    @Test
    void marksArrivalBeforeCompletingOnlyOnIdleUpdate() {
        Scheduler scheduler = startScheduler(buildNominalZones());
        scheduler.putRequest(request(1, Severity.HIGH, 0));

        DispatchCommand dispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        int missionId = dispatch.getMission().getMissionId();

        scheduler.sendDroneStatusUpdate(status(1, DroneState.EN_ROUTE, missionId, 12.5, 590, "en route"));
        waitUntil(() -> scheduler.getDroneState() == DroneState.EN_ROUTE, DEFAULT_TIMEOUT_MS,
                "Drone did not transition to EN_ROUTE.");

        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 12.5, 580, "arrived"));
        waitUntil(() -> scheduler.getDroneState() == DroneState.DROPPING_AGENT, DEFAULT_TIMEOUT_MS,
                "Drone did not transition to DROPPING_AGENT.");

        assertNoCompletionYet(scheduler, 300);

        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 0.0, 560, "complete"));
        FireRequest completion = awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);
        assertTrue(completion.isResolved());
    }

    @Test
    void dispatchesNextQueuedMissionAfterCompletionWhenResourcesAllow() {
        Scheduler scheduler = startScheduler(buildNominalZones());
        scheduler.putRequest(request(1, Severity.LOW, 0));
        scheduler.putRequest(request(2, Severity.MODERATE, 1));

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 12.5, 590, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 6.0, 580, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand second = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(second.isReturnToBase());
        assertEquals(2, second.getMission().getZoneId());
    }

    @Test
    void returnsToBaseWhenNoQueuedMissionsRemain() {
        Scheduler scheduler = startScheduler(buildNominalZones());
        scheduler.putRequest(request(1, Severity.MODERATE, 0));

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 12.5, 580, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 1.0, 560, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand returnCmd = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertTrue(returnCmd.isReturnToBase());
    }

    @Test
    void returnsToBaseWhenBatteryCannotCoverNextMission() {
        Scheduler scheduler = startScheduler(buildBatteryStressZones());
        scheduler.putRequest(request(1, Severity.LOW, 0));
        scheduler.putRequest(request(2, Severity.LOW, 1));

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 12.5, 60, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 11.0, 20, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand decision = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertTrue(decision.isReturnToBase());
    }

    @Test
    void returnsToBaseWhenAgentIsDepletedForNextMission() {
        Scheduler scheduler = startScheduler(buildNominalZones());
        scheduler.putRequest(request(1, Severity.HIGH, 0));
        scheduler.putRequest(request(2, Severity.LOW, 1));

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 1.0, 560, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 0.0, 550, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand decision = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertTrue(decision.isReturnToBase());
    }

    @Test
    void dispatchesQueuedMissionImmediatelyAfterBaseRefill() {
        Scheduler scheduler = startScheduler(buildNominalZones());
        scheduler.putRequest(request(1, Severity.HIGH, 0));
        scheduler.putRequest(request(2, Severity.LOW, 1));

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 0.5, 560, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 0.0, 540, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand returnCmd = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertTrue(returnCmd.isReturnToBase());

        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, -1, 0.0, 0.0, "at base"));

        DispatchCommand secondMission = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(secondMission.isReturnToBase());
        assertEquals(2, secondMission.getMission().getZoneId());
    }

    @Test
    void ignoresDuplicateFireRequestsForSameZone() {
        Scheduler scheduler = startScheduler(buildNominalZones());
        scheduler.putRequest(request(1, Severity.LOW, 0));
        scheduler.putRequest(request(1, Severity.HIGH, 1));

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertEquals(1, first.getMission().getZoneId());
        waitUntil(() -> scheduler.getActiveFires() == 1, DEFAULT_TIMEOUT_MS,
                "Duplicate same-zone incident should not become active.");

        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 12.5, 590, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 2.5, 580, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand postCompletion = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertTrue(postCompletion.isReturnToBase());
    }

    @Test
    void updatesGuiTrackingForStateAndActiveFireCount() {
        Scheduler scheduler = startScheduler(buildNominalZones());
        scheduler.putRequest(request(1, Severity.MODERATE, 0));
        waitUntil(() -> scheduler.getActiveFires() == 1, DEFAULT_TIMEOUT_MS,
                "Active fire count did not increment.");

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertNotNull(first.getMission());
        waitUntil(() -> scheduler.getDroneState() == DroneState.EN_ROUTE, DEFAULT_TIMEOUT_MS,
                "Drone state did not update to EN_ROUTE.");

        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId, 12.5, 585, "arrived"));
        waitUntil(() -> scheduler.getDroneState() == DroneState.DROPPING_AGENT, DEFAULT_TIMEOUT_MS,
                "Drone state did not update to DROPPING_AGENT.");

        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId, 3.0, 570, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);
        waitUntil(() -> scheduler.getActiveFires() == 0, DEFAULT_TIMEOUT_MS,
                "Active fire count did not decrement after completion.");
    }

    @Test
    void runsEndToEndFlowWithContinueReturnAndRedispatch() {
        Scheduler scheduler = startScheduler(buildEndToEndZones());
        scheduler.putRequest(request(1, Severity.LOW, 0));
        scheduler.putRequest(request(2, Severity.MODERATE, 1));
        scheduler.putRequest(request(3, Severity.HIGH, 2));

        DispatchCommand mission1 = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertEquals(1, mission1.getMission().getZoneId());
        int mission1Id = mission1.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, mission1Id, 12.5, 590, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, mission1Id, 8.0, 560, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand mission2 = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(mission2.isReturnToBase());
        assertEquals(2, mission2.getMission().getZoneId());
        int mission2Id = mission2.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, mission2Id, 8.0, 60, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, mission2Id, 2.0, 18, "complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand returnCmd = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertTrue(returnCmd.isReturnToBase());

        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, -1, 0.0, 0.0, "at base"));

        DispatchCommand mission3 = awaitMissionDispatchCommand(scheduler, 1, 3);
        assertFalse(mission3.isReturnToBase());
        assertEquals(3, mission3.getMission().getZoneId());
    }

    private Scheduler startScheduler(Map<Integer, Zone> zoneMap) {
        Scheduler scheduler = new Scheduler(null, zoneMap, 1);
        schedulerThread = new Thread(scheduler, "scheduler-iteration2-spec-test");
        schedulerThread.start();
        return scheduler;
    }

    private FireRequest request(int zoneId, Severity severity, int offsetSeconds) {
        return new FireRequest(BASE_TIME.plusSeconds(offsetSeconds), zoneId, EventType.FIRE_DETECTED, severity);
    }

    private DroneStatusUpdate status(int droneId, DroneState state, int missionId,
            double remainingAgent, double remainingBattery, String message) {
        return new DroneStatusUpdate(droneId, state, missionId, remainingAgent, remainingBattery, message);
    }

    private DispatchCommand awaitDispatchCommand(Scheduler scheduler, int droneId, long timeoutMs) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<DispatchCommand> future = executor.submit(() -> scheduler.getDispatchCommandForDrone(droneId));
        try {
            DispatchCommand cmd = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            assertNotNull(cmd);
            return cmd;
        } catch (TimeoutException e) {
            future.cancel(true);
            fail("Timed out waiting for dispatch command.");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for dispatch command.");
            return null;
        } catch (ExecutionException e) {
            fail("Dispatch command retrieval failed: " + e.getCause());
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    private DispatchCommand awaitMissionDispatchCommand(Scheduler scheduler, int droneId, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            DispatchCommand cmd = awaitDispatchCommand(scheduler, droneId, DEFAULT_TIMEOUT_MS);
            if (!cmd.isReturnToBase()) {
                return cmd;
            }
        }
        fail("Expected a mission dispatch command but only received return-to-base commands.");
        return null;
    }

    private FireRequest awaitCompletion(Scheduler scheduler, long timeoutMs) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<FireRequest> future = executor.submit(scheduler::getCompletion);
        try {
            FireRequest completion = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            assertNotNull(completion);
            return completion;
        } catch (TimeoutException e) {
            future.cancel(true);
            fail("Timed out waiting for completion update.");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for completion update.");
            return null;
        } catch (ExecutionException e) {
            fail("Completion retrieval failed: " + e.getCause());
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertNoCompletionYet(Scheduler scheduler, long timeoutMs) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<FireRequest> future = executor.submit(scheduler::getCompletion);
        try {
            FireRequest completion = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            fail("Unexpected completion received: " + completion);
        } catch (TimeoutException expected) {
            future.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while verifying absence of completion update.");
        } catch (ExecutionException e) {
            fail("Completion check failed: " + e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private void waitUntil(BooleanSupplier condition, long timeoutMs, String timeoutMessage) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for condition.");
            }
        }
        fail(timeoutMessage);
    }

    private Map<Integer, Zone> buildNominalZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        zones.put(2, new Zone("2", "(120;0)", "(220;100)"));
        zones.put(3, new Zone("3", "(240;0)", "(340;100)"));
        return zones;
    }

    private Map<Integer, Zone> buildBatteryStressZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        zones.put(2, new Zone("2", "(2000;0)", "(2100;100)"));
        return zones;
    }

    private Map<Integer, Zone> buildEndToEndZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        zones.put(2, new Zone("2", "(300;0)", "(400;100)"));
        zones.put(3, new Zone("3", "(2200;0)", "(2300;100)"));
        return zones;
    }
}
