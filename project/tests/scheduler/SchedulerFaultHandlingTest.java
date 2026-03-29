package scheduler;

import Drone_subsystem.Zone;
import Scheduler.Scheduler;
import fire_incident_subsystem.FireRequest;
import org.junit.jupiter.api.Test;
import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.EventType;
import types.FaultType;
import types.Severity;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SchedulerFaultHandlingTest {

    @Test
    void addsMissionBackToReadyQueueAfterHardFault() throws Exception {
        // This checks the scheduler idea that unfinished work should go back to the ready queue
        // when the only working drone reports a hard fault and goes offline.
        Scheduler scheduler = new Scheduler(null, buildZones(), 1);
        Thread schedulerThread = new Thread(scheduler, "scheduler-fault-test");

        try {
            schedulerThread.start();

            FireRequest request = new FireRequest(
                    LocalTime.of(12, 0),
                    1,
                    EventType.FIRE_DETECTED,
                    Severity.HIGH,
                    FaultType.NOZZLE_JAMMED,
                    0);
            scheduler.putRequest(request);

            Thread.sleep(300);

            DispatchCommand initialDispatch = scheduler.getDispatchCommandForDrone(1);
            assertNotNull(initialDispatch);
            assertFalse(initialDispatch.isReturnToBase());
            assertEquals(0, scheduler.getQueuedMissionCount());
            assertEquals(1, scheduler.getInProgressMissionCount());

            scheduler.sendDroneStatusUpdate(new DroneStatusUpdate(
                    1,
                    DroneState.OFFLINE,
                    initialDispatch.getMission().getMissionId(),
                    12.5,
                    590.0,
                    50.0,
                    50.0,
                    "Nozzle jam detected at Zone 1. Drone offline for remainder of run.",
                    FaultType.NOZZLE_JAMMED));

            Thread.sleep(300);

            assertEquals(1, scheduler.getQueuedMissionCount());
            assertEquals(0, scheduler.getInProgressMissionCount());
            assertEquals(DroneState.OFFLINE, scheduler.getDroneState(1));
        } finally {
            schedulerThread.interrupt();
            schedulerThread.join(1000);
        }
    }

    @Test
    void reroutesHardFaultMissionToAnotherAvailableDrone() throws Exception {
        // This checks the OS/concurrency idea that when one worker thread fails with a hard fault,
        // the scheduler should give the unfinished mission to another available drone.
        Scheduler scheduler = new Scheduler(null, buildTwoZones(), 2);
        Thread schedulerThread = new Thread(scheduler, "scheduler-reroute-test");

        try {
            schedulerThread.start();

            FireRequest request = new FireRequest(
                    LocalTime.of(12, 0),
                    1,
                    EventType.FIRE_DETECTED,
                    Severity.HIGH,
                    FaultType.NOZZLE_JAMMED,
                    0);
            scheduler.putRequest(request);

            Thread.sleep(300);

            DispatchCommand firstDispatch = scheduler.getDispatchCommandForDrone(1);
            assertNotNull(firstDispatch);
            assertEquals(1, firstDispatch.getMission().getZoneId());

            scheduler.sendDroneStatusUpdate(new DroneStatusUpdate(
                    1,
                    DroneState.OFFLINE,
                    firstDispatch.getMission().getMissionId(),
                    12.5,
                    590.0,
                    50.0,
                    50.0,
                    "Nozzle jam detected at Zone 1. Drone offline for remainder of run.",
                    FaultType.NOZZLE_JAMMED));

            Thread.sleep(300);

            DispatchCommand reroutedDispatch = scheduler.getDispatchCommandForDrone(2);
            assertNotNull(reroutedDispatch);
            assertEquals(1, reroutedDispatch.getMission().getZoneId());
            assertEquals(FaultType.NONE, reroutedDispatch.getFaultType());
            assertEquals(DroneState.OFFLINE, scheduler.getDroneState(1));
            assertEquals(DroneState.EN_ROUTE, scheduler.getDroneState(2));
        } finally {
            schedulerThread.interrupt();
            schedulerThread.join(1000);
        }
    }

    @Test
    void keepsSoftFaultMissionInProgressInsteadOfRequeueingIt() throws Exception {
        // This checks the OS/concurrency idea that a soft fault should not send work back to
        // the ready queue because the same drone is expected to recover and continue.
        Scheduler scheduler = new Scheduler(null, buildZones(), 1);
        Thread schedulerThread = new Thread(scheduler, "scheduler-soft-fault-test");

        try {
            schedulerThread.start();

            FireRequest request = new FireRequest(
                    LocalTime.of(12, 0),
                    1,
                    EventType.FIRE_DETECTED,
                    Severity.HIGH,
                    FaultType.STUCK_MID_FLIGHT,
                    0);
            scheduler.putRequest(request);

            Thread.sleep(300);

            DispatchCommand initialDispatch = scheduler.getDispatchCommandForDrone(1);
            assertNotNull(initialDispatch);

            scheduler.sendDroneStatusUpdate(new DroneStatusUpdate(
                    1,
                    DroneState.RESETTING,
                    initialDispatch.getMission().getMissionId(),
                    12.5,
                    590.0,
                    25.0,
                    25.0,
                    "Stuck mid-flight detected. Drone resetting before retry.",
                    FaultType.STUCK_MID_FLIGHT));

            Thread.sleep(200);

            assertEquals(0, scheduler.getQueuedMissionCount());
            assertEquals(1, scheduler.getInProgressMissionCount());
            assertEquals(DroneState.RESETTING, scheduler.getDroneState(1));
        } finally {
            schedulerThread.interrupt();
            schedulerThread.join(1000);
        }
    }

    @Test
    void doesNotAssignNewMissionToDroneAfterItGoesOffline() throws Exception {
        // This checks the OS/concurrency idea that a failed worker should stay unavailable,
        // so later work must wait or go to some other drone instead of reusing the offline one.
        Scheduler scheduler = new Scheduler(null, buildTwoZones(), 2);
        Thread schedulerThread = new Thread(scheduler, "scheduler-offline-drone-test");

        try {
            schedulerThread.start();

            FireRequest firstRequest = new FireRequest(
                    LocalTime.of(12, 0),
                    1,
                    EventType.FIRE_DETECTED,
                    Severity.HIGH,
                    FaultType.NOZZLE_JAMMED,
                    0);
            scheduler.putRequest(firstRequest);

            Thread.sleep(300);

            DispatchCommand firstDispatch = scheduler.getDispatchCommandForDrone(1);
            assertNotNull(firstDispatch);

            scheduler.sendDroneStatusUpdate(new DroneStatusUpdate(
                    1,
                    DroneState.OFFLINE,
                    firstDispatch.getMission().getMissionId(),
                    12.5,
                    590.0,
                    50.0,
                    50.0,
                    "Nozzle jam detected at Zone 1. Drone offline for remainder of run.",
                    FaultType.NOZZLE_JAMMED));

            Thread.sleep(300);

            DispatchCommand reroutedDispatch = scheduler.getDispatchCommandForDrone(2);
            assertNotNull(reroutedDispatch);
            assertEquals(1, reroutedDispatch.getMission().getZoneId());

            FireRequest secondRequest = new FireRequest(
                    LocalTime.of(12, 0, 5),
                    2,
                    EventType.FIRE_DETECTED,
                    Severity.MODERATE);
            scheduler.putRequest(secondRequest);

            Thread.sleep(300);

            assertEquals(1, scheduler.getQueuedMissionCount());
            assertEquals(DroneState.OFFLINE, scheduler.getDroneState(1));
            assertEquals(DroneState.EN_ROUTE, scheduler.getDroneState(2));
        } finally {
            schedulerThread.interrupt();
            schedulerThread.join(1000);
        }
    }

    private Map<Integer, Zone> buildZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        return zones;
    }

    private Map<Integer, Zone> buildTwoZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        zones.put(2, new Zone("2", "(200;0)", "(300;100)"));
        return zones;
    }
}
