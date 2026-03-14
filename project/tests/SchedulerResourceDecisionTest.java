import Scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import types.DispatchCommand;
import types.DroneState;
import types.Severity;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerResourceDecisionTest extends SchedulerTestSupport {

    @Test
    void returnsDroneToBaseWhenPendingMissionCannotBeServedWithRemainingBattery() {
        // After completing the first mission, the drone does not have enough battery budget to safely
        // handle the next queued mission and still satisfy the return-home requirement.
        Scheduler scheduler = startScheduler(new Scheduler(null, buildBatteryStressZones(), 1));
        scheduler.putRequest(request(1, Severity.LOW, 0));
        scheduler.putRequest(request(2, Severity.LOW, 1));

        DispatchCommand first = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        int missionId = first.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(1, DroneState.DROPPING_AGENT, missionId,
                12.5, 60.0, 50.0, 50.0, "arrived"));
        scheduler.sendDroneStatusUpdate(status(1, DroneState.IDLE, missionId,
                11.0, 20.0, 50.0, 50.0, "Mission complete"));
        awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        DispatchCommand decision = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertTrue(decision.isReturnToBase());
    }
}
