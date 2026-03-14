package scheduler;

import Scheduler.Scheduler;
import fire_incident_subsystem.FireRequest;
import org.junit.jupiter.api.Test;
import support.SchedulerTestSupport;
import types.DispatchCommand;
import types.DroneState;
import types.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerReroutePolicyTest extends SchedulerTestSupport {

    @Test
    void reroutesDroneToHigherSeverityFireWhileAlreadyEnRoute() {
        // once a drone is travelling to a lower-priority zone, a newly reported higher-priority
        // fire should cause a reroute instead of forcing the drone to finish the old trip first.
        Scheduler scheduler = startScheduler(new Scheduler(null, buildNominalZones(), 1));
        scheduler.putRequest(request(1, Severity.LOW, 0));

        DispatchCommand initialDispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(initialDispatch.isReturnToBase());
        assertEquals(1, initialDispatch.getMission().getZoneId());

        scheduler.sendDroneStatusUpdate(status(
                1,
                DroneState.EN_ROUTE,
                initialDispatch.getMission().getMissionId(),
                12.5,
                590.0,
                30.0,
                30.0,
                "en route"));

        scheduler.putRequest(request(2, Severity.HIGH, 1));

        DispatchCommand rerouteDispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(rerouteDispatch.isReturnToBase());
        assertEquals(2, rerouteDispatch.getMission().getZoneId());
    }

    @Test
    void reroutesDroneToSameSeverityFireThatAppearsEarlierOnItsPath() {
        // if the new fire has the same severity but lies earlier on the current route,
        // the scheduler should let the drone service that earlier zone first.
        Scheduler scheduler = startScheduler(new Scheduler(null, buildLineZones(), 1));
        scheduler.putRequest(request(3, Severity.HIGH, 0));

        DispatchCommand initialDispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(initialDispatch.isReturnToBase());
        assertEquals(3, initialDispatch.getMission().getZoneId());

        scheduler.sendDroneStatusUpdate(status(
                1,
                DroneState.EN_ROUTE,
                initialDispatch.getMission().getMissionId(),
                12.5,
                590.0,
                100.0,
                50.0,
                "en route"));

        scheduler.putRequest(request(2, Severity.HIGH, 1));

        DispatchCommand rerouteDispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(rerouteDispatch.isReturnToBase());
        assertEquals(2, rerouteDispatch.getMission().getZoneId());

        int reroutedMissionId = rerouteDispatch.getMission().getMissionId();
        scheduler.sendDroneStatusUpdate(status(
                1,
                DroneState.DROPPING_AGENT,
                reroutedMissionId,
                12.5,
                580.0,
                150.0,
                50.0,
                "arrived"));
        scheduler.sendDroneStatusUpdate(status(
                1,
                DroneState.IDLE,
                reroutedMissionId,
                2.5,
                570.0,
                150.0,
                50.0,
                "Mission complete"));

        FireRequest completion = awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);
        assertEquals(2, completion.getZoneId());
        assertTrue(completion.isResolved());

        DispatchCommand resumedDispatch = awaitDispatchCommand(scheduler, 1, DEFAULT_TIMEOUT_MS);
        assertFalse(resumedDispatch.isReturnToBase());
        assertEquals(3, resumedDispatch.getMission().getZoneId());
    }
}
