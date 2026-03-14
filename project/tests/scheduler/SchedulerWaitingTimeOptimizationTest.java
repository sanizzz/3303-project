package scheduler;

import Scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import support.SchedulerTestSupport;
import types.DispatchCommand;
import types.DroneState;
import types.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SchedulerWaitingTimeOptimizationTest extends SchedulerTestSupport {

    @Test
    void givesNextMissionToDroneThatCanReachItSoonerAfterCompletingCurrentWork() {
        // Waiting-time
        // after Drone 1 finishes a nearby mission, it should receive the next nearby fire
        // instead of leaving that work to a farther idle drone at base.
        Scheduler scheduler = startScheduler(new Scheduler(null, buildNominalZones(), 2));

        scheduler.putRequest(request(1, Severity.MODERATE, 0));
        scheduler.putRequest(request(3, Severity.MODERATE, 1));

        DispatchCommand firstDispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        DispatchCommand secondDispatch = awaitDispatchCommand(scheduler, 2, DEFAULT_TIMEOUT_MS);
        assertFalse(firstDispatch.isReturnToBase());
        assertFalse(secondDispatch.isReturnToBase());

        int missionId = firstDispatch.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(
                1,
                DroneState.DROPPING_AGENT,
                missionId,
                12.5,
                580.0,
                50.0,
                50.0,
                "arrived"));
        scheduler.sendDroneStatusUpdate(status(
                1,
                DroneState.IDLE,
                missionId,
                2.5,
                570.0,
                50.0,
                50.0,
                "Mission complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        scheduler.putRequest(request(2, Severity.MODERATE, 2));

        DispatchCommand nextDispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(nextDispatch.isReturnToBase());
        assertEquals(2, nextDispatch.getMission().getZoneId());
    }

    @Test
    void prefersOlderRequestWhenSeverityAndTravelTimeAreEffectivelyEqual() {
        // Fairness tie-break under test:
        // if two waiting requests have the same severity and no meaningful ETA difference,
        // the older request should be served first.
        Scheduler scheduler = new Scheduler(null, buildEqualDistanceZones(), 1);
        scheduler.putRequest(request(1, Severity.MODERATE, 0));
        scheduler.putRequest(request(2, Severity.MODERATE, 5));
        startScheduler(scheduler);

        DispatchCommand firstDispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(firstDispatch.isReturnToBase());
        assertEquals(1, firstDispatch.getMission().getZoneId());
    }
}
