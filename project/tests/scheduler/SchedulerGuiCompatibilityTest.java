package scheduler;

import Scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import support.SchedulerTestSupport;
import types.DispatchCommand;
import types.DroneState;
import types.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SchedulerGuiCompatibilityTest extends SchedulerTestSupport {

    @Test
    void exposesDroneStateAndActiveFireCountNeededByGui() {
        // GUI compatibility under test:
        // the GUI still reads scheduler state through getDroneState() and getActiveFires(),
        // so those values must continue to reflect real Iteration 3 mission progress.
        Scheduler scheduler = startScheduler(new Scheduler(null, buildNominalZones(), 1));
        scheduler.putRequest(request(1, Severity.MODERATE, 0));

        waitUntil(() -> scheduler.getActiveFires() == 1, DEFAULT_TIMEOUT_MS,
                "Expected active fire count to increase after request submission.");

        DispatchCommand dispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(dispatch.isReturnToBase());

        waitUntil(() -> scheduler.getDroneState() == DroneState.EN_ROUTE, DEFAULT_TIMEOUT_MS,
                "Expected drone state to reflect an in-flight mission.");

        int missionId = dispatch.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(
                1,
                DroneState.DROPPING_AGENT,
                missionId,
                12.5,
                585.0,
                50.0,
                50.0,
                "arrived"));
        scheduler.sendDroneStatusUpdate(status(
                1,
                DroneState.IDLE,
                missionId,
                2.0,
                570.0,
                50.0,
                50.0,
                "Mission complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        waitUntil(() -> scheduler.getActiveFires() == 0, DEFAULT_TIMEOUT_MS,
                "Expected active fire count to return to zero after completion.");
        waitUntil(() -> scheduler.getDroneState() == DroneState.RETURNING, DEFAULT_TIMEOUT_MS,
                "Expected drone state to move to RETURNING after the queue becomes empty.");
        assertEquals(DroneState.RETURNING, scheduler.getDroneState());
    }
}
