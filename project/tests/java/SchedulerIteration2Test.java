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

import static org.junit.jupiter.api.Assertions.*;

public class SchedulerIteration2Test {

    private Thread schedulerThread;

    private Scheduler startScheduler(Map<Integer, Zone> zoneMap) {
        Scheduler scheduler = new Scheduler(null, zoneMap, 1);
        schedulerThread = new Thread(scheduler, "scheduler-test-thread");
        schedulerThread.start();
        return scheduler;
    }

    @AfterEach
    void tearDown() {
        if (schedulerThread != null) {
            schedulerThread.interrupt();
        }
    }

    @Test
    void dispatchesMissionAndCompletesThenReturnsToBase() {
        Map<Integer, Zone> zoneMap = new HashMap<>();
        zoneMap.put(1, new Zone("1", "(0;0)", "(100;100)"));

        Scheduler scheduler = startScheduler(zoneMap);
        FireRequest req = new FireRequest(LocalTime.of(12, 0), 1, EventType.FIRE_DETECTED, Severity.LOW);
        scheduler.putRequest(req);

        DispatchCommand cmd = scheduler.getDispatchCommandForDrone(1);
        assertNotNull(cmd);
        assertFalse(cmd.isReturnToBase());
        assertNotNull(cmd.getMission());
        assertEquals(1, cmd.getMission().getZoneId());

        int missionId = cmd.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(new DroneStatusUpdate(1, DroneState.EN_ROUTE, missionId, 12.5, 590, "en route"));
        scheduler.sendDroneStatusUpdate(
                new DroneStatusUpdate(1, DroneState.DROPPING_AGENT, missionId, 12.5, 580, "arrived"));
        scheduler.sendDroneStatusUpdate(new DroneStatusUpdate(1, DroneState.IDLE, missionId, 2.5, 570, "complete"));

        FireRequest ack = scheduler.getCompletion();
        assertNotNull(ack);
        assertTrue(ack.isResolved());

        DispatchCommand returnCmd = scheduler.getDispatchCommandForDrone(1);
        assertNotNull(returnCmd);
        assertTrue(returnCmd.isReturnToBase());
    }

    @Test
    void ignoresDuplicateActiveFireInSameZone() {
        Map<Integer, Zone> zoneMap = new HashMap<>();
        zoneMap.put(1, new Zone("1", "(0;0)", "(100;100)"));

        Scheduler scheduler = startScheduler(zoneMap);
        FireRequest first = new FireRequest(LocalTime.of(12, 0), 1, EventType.FIRE_DETECTED, Severity.LOW);
        FireRequest second = new FireRequest(LocalTime.of(12, 0, 5), 1, EventType.FIRE_DETECTED, Severity.HIGH);

        scheduler.putRequest(first);
        scheduler.putRequest(second);

        DispatchCommand cmd = scheduler.getDispatchCommandForDrone(1);
        assertNotNull(cmd);
        assertFalse(cmd.isReturnToBase());
        assertEquals(1, cmd.getMission().getZoneId());

        // One mission in progress, duplicate should not be queued as active.
        assertEquals(1, scheduler.getActiveFires());
    }
}
