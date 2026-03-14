import Drone_subsystem.Drone;
import Scheduler.Scheduler;
import fire_incident_subsystem.FireRequest;
import org.junit.jupiter.api.Test;
import types.Severity;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DroneSubsystemIntegrationTest extends SchedulerTestSupport {

    @Test
    void runsStableLocalEndToEndFlowWithTwoIndependentDrones() {
        // In-process integration demo:
        // two drone subsystem threads must operate independently while the scheduler splits
        // work instead of funnelling every mission through a single drone.
        Drone.configure(12.5, 80, 900.0, 2.0);

        Scheduler scheduler = startScheduler(new Scheduler(null, buildNominalZones(), 2));
        startDroneSubsystem(1, scheduler, buildNominalZones(), 100);
        startDroneSubsystem(2, scheduler, buildNominalZones(), 100);

        scheduler.putRequest(request(1, Severity.LOW, 0));
        scheduler.putRequest(request(2, Severity.LOW, 1));

        FireRequest first = awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);
        FireRequest second = awaitCompletion(scheduler, DEFAULT_TIMEOUT_MS);

        Set<Integer> completedZones = Set.of(first.getZoneId(), second.getZoneId());
        assertEquals(Set.of(1, 2), completedZones);
        waitUntil(() -> scheduler.getActiveFires() == 0, DEFAULT_TIMEOUT_MS,
                "Expected all local integration missions to complete.");
    }
}
