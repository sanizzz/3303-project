import Drone_subsystem.Drone;
import Scheduler.Scheduler;
import fire_incident_subsystem.FireRequest;
import org.junit.jupiter.api.Test;
import types.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroneLifecycleStateTest extends SchedulerTestSupport {

    @Test
    void completesHighSeverityMissionAfterRefillAndResumeCycle() {
        // Drone lifecycle requirement under test:
        // a single fire can require more suppressant than one tank, so the drone must
        // drop its first load, return to base, refill, resume the same mission, and only
        // then report completion back through the scheduler.
        Drone.configure(10.0, 80, 900.0, 2.0);

        Scheduler scheduler = startScheduler(new Scheduler(null, buildNominalZones(), 1));
        startDroneSubsystem(1, scheduler, buildNominalZones(), 100);

        scheduler.putRequest(request(1, Severity.HIGH, 0));

        FireRequest completion = awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);
        assertEquals(1, completion.getZoneId());
        assertTrue(completion.isResolved());

        waitUntil(() -> scheduler.getActiveFires() == 0, DEFAULT_TIMEOUT_MS,
                "Expected the high-severity mission to complete after refill and resume.");
    }
}
