import Scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import types.DispatchCommand;
import types.DroneState;
import types.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SchedulerWaitingTimeOptimizationTest extends SchedulerTestSupport {

    @Test
    void givesNextMissionToDroneThatCanReachItSoonerAfterCompletingCurrentWork() {
        // Requirement under test:
        // The scheduler should minimize waiting time.
        // After Drone 1 completes a nearby mission, it should be chosen for the next nearby fire
        // instead of an idle drone sitting farther away at base.
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
        // Tie-break policy under test:
        // if two waiting requests have the same severity and no meaningful ETA advantage,
        // the older request should go first so waiting time is fair.
        Scheduler scheduler = new Scheduler(null, buildEqualDistanceZones(), 1);
        scheduler.putRequest(request(1, Severity.MODERATE, 0));
        scheduler.putRequest(request(2, Severity.MODERATE, 5));
        startScheduler(scheduler);

        DispatchCommand firstDispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(firstDispatch.isReturnToBase());
        assertEquals(1, firstDispatch.getMission().getZoneId());
    }
}
