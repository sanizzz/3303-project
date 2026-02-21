import Drone_subsystem.Drone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import types.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DroneConfigAndSeverityTest {

    @AfterEach
    void resetDroneDefaults() {
        Drone.resetDefaults();
    }

    @Test
    void appliesRuntimeDroneConfiguration() {
        Drone.configure(20.0, 25, 800.0, 12.0);

        assertEquals(20.0, Drone.getLoadCapacity(), 0.0001);
        assertEquals(25, Drone.getSpeed());
        assertEquals(800.0, Drone.getFullBattery(), 0.0001);
        assertEquals(12.0, Drone.getTotalExtinguishingTime(), 0.0001);
    }

    @Test
    void severityLitersMatchIteration2Assumptions() {
        assertEquals(10.0, Severity.LOW.getLitersNeeded(), 0.0001);
        assertEquals(20.0, Severity.MODERATE.getLitersNeeded(), 0.0001);
        assertEquals(30.0, Severity.HIGH.getLitersNeeded(), 0.0001);
    }
}
