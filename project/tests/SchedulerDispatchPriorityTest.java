import Scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import types.DispatchCommand;
import types.DroneState;
import types.Severity;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SchedulerDispatchPriorityTest extends SchedulerTestSupport {

    @Test
    void distributesConcurrentMissionsAcrossMultipleDrones() {
        // Two equivalent requests should be split across the two available drones instead of serializing everything
        // onto Drone 1.
        Scheduler scheduler = startScheduler(new Scheduler(null, buildNominalZones(), 2));
        scheduler.putRequest(request(1, Severity.MODERATE, 0));
        scheduler.putRequest(request(2, Severity.MODERATE, 1));

        DispatchCommand drone1 = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        DispatchCommand drone2 = awaitDispatchCommand(scheduler, 2, DEFAULT_TIMEOUT_MS);

        assertFalse(drone1.isReturnToBase());
        assertFalse(drone2.isReturnToBase());

        Set<Integer> zones = new HashSet<>();
        zones.add(drone1.getMission().getZoneId());
        zones.add(drone2.getMission().getZoneId());
        assertEquals(Set.of(1, 2), zones);
        assertEquals(1, scheduler.getAssignedCount(1));
        assertEquals(1, scheduler.getAssignedCount(2));
    }

    @Test
    void prioritizesHigherSeverityWhenMultipleRequestsAreWaiting() {
        // This is the explicit priority rule the TA is expecting:
        // if a LOW request and a HIGH request are both waiting when dispatch occurs,
        // the Scheduler must send the HIGH mission first.
        Scheduler scheduler = new Scheduler(null, buildNominalZones(), 1);
        scheduler.putRequest(request(1, Severity.LOW, 0));
        scheduler.putRequest(request(2, Severity.HIGH, 1));
        startScheduler(scheduler);

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(first.isReturnToBase());
        assertEquals(2, first.getMission().getZoneId());
    }

    @Test
    void recordsDroneLocationsFromStatusUpdates() {
        // The Scheduler must track drone location because Iteration 3 routing decisions depend on it.
        Scheduler scheduler = startScheduler(new Scheduler(null, buildNominalZones(), 1));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.EN_ROUTE, -1, 12.5, 590.0, 125.0, 225.0, "en route"));

        waitUntil(() -> Math.abs(scheduler.getDroneX(1) - 125.0) < 0.001, DEFAULT_TIMEOUT_MS,
                "Scheduler did not record drone X position.");
        assertEquals(225.0, scheduler.getDroneY(1), 0.001);
    }
}
