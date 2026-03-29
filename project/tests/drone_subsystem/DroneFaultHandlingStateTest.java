package drone_subsystem;

import Drone_subsystem.Drone;
import Drone_subsystem.DroneExecutionEngine;
import Drone_subsystem.Zone;
import fire_incident_subsystem.FireRequest;
import org.junit.jupiter.api.Test;
import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.EventType;
import types.FaultType;
import types.Mission;
import types.Severity;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroneFaultHandlingStateTest {

    @Test
    void changesToResettingStateForStuckMidFlightFault() throws Exception {
        // This checks the OS/concurrency idea that a soft fault should pause the drone thread
        // and move it into a recovery state instead of ending the mission completely.
        Drone.configure(12.5, 20, 900.0, 2.0);

        List<DroneStatusUpdate> updates = new ArrayList<>();
        DroneExecutionEngine engine = new DroneExecutionEngine(
                1,
                buildZones(),
                20,
                update -> {
                    synchronized (updates) {
                        updates.add(update);
                    }
                },
                msg -> {
                });
        Thread droneThread = new Thread(engine, "drone-stuck-test");

        try {
            droneThread.start();
            engine.submitCommand(buildDispatchCommand(FaultType.STUCK_MID_FLIGHT, 0));

            Thread.sleep(200);

            assertEquals(DroneState.RESETTING, engine.getCurrentState());
            assertTrue(containsFault(updates, FaultType.STUCK_MID_FLIGHT));
        } finally {
            droneThread.interrupt();
            droneThread.join(1000);
            Drone.resetDefaults();
        }
    }

    @Test
    void returnsToEnRouteAfterStuckMidFlightReset() throws Exception {
        // This checks the OS/concurrency idea that a soft fault should let the same thread recover
        // and continue the same mission instead of being treated like a permanent failure.
        Drone.configure(12.5, 20, 900.0, 2.0);

        List<DroneStatusUpdate> updates = new ArrayList<>();
        DroneExecutionEngine engine = new DroneExecutionEngine(
                1,
                buildFarZones(),
                20,
                update -> {
                    synchronized (updates) {
                        updates.add(update);
                    }
                },
                msg -> {
                });
        Thread droneThread = new Thread(engine, "drone-stuck-recovery-test");

        try {
            droneThread.start();
            engine.submitCommand(buildDispatchCommand(FaultType.STUCK_MID_FLIGHT, 0));

            Thread.sleep(500);

            assertEquals(DroneState.EN_ROUTE, engine.getCurrentState());
            assertTrue(containsMessage(updates, "Reset complete"));
        } finally {
            droneThread.interrupt();
            droneThread.join(1000);
            Drone.resetDefaults();
        }
    }

    @Test
    void changesToOfflineStateForNozzleJamFault() throws Exception {
        // This checks the OS/concurrency idea that a hard fault should remove the drone from
        // service so the scheduler can stop giving it more work.
        Drone.configure(12.5, 20, 900.0, 2.0);

        List<DroneStatusUpdate> updates = new ArrayList<>();
        DroneExecutionEngine engine = new DroneExecutionEngine(
                1,
                buildZones(),
                100,
                update -> {
                    synchronized (updates) {
                        updates.add(update);
                    }
                },
                msg -> {
                });
        Thread droneThread = new Thread(engine, "drone-jam-test");

        try {
            droneThread.start();
            engine.submitCommand(buildDispatchCommand(FaultType.NOZZLE_JAMMED, 0));

            Thread.sleep(400);

            assertEquals(DroneState.OFFLINE, engine.getCurrentState());
            assertTrue(containsFault(updates, FaultType.NOZZLE_JAMMED));
        } finally {
            droneThread.interrupt();
            droneThread.join(1000);
            Drone.resetDefaults();
        }
    }

    @Test
    void sendsPacketLossUpdateBeforeRetryingMessage() throws Exception {
        // This checks the OS/concurrency idea that a communication fault should be reported,
        // then the same drone thread should retry the message and continue running.
        Drone.configure(12.5, 20, 900.0, 2.0);

        List<DroneStatusUpdate> updates = new ArrayList<>();
        DroneExecutionEngine engine = new DroneExecutionEngine(
                1,
                buildZones(),
                100,
                update -> {
                    synchronized (updates) {
                        updates.add(update);
                    }
                },
                msg -> {
                });
        Thread droneThread = new Thread(engine, "drone-packet-loss-test");

        try {
            droneThread.start();
            engine.submitCommand(buildDispatchCommand(FaultType.PACKET_LOSS, 0));

            Thread.sleep(250);

            assertTrue(containsFault(updates, FaultType.PACKET_LOSS));
            assertTrue(containsRetryMessage(updates));
        } finally {
            droneThread.interrupt();
            droneThread.join(1000);
            Drone.resetDefaults();
        }
    }

    @Test
    void staysEnRouteAfterPacketLossRetry() throws Exception {
        // This checks the OS/concurrency idea that packet loss is a soft fault, so the drone
        // should keep doing the mission after retrying instead of going offline.
        Drone.configure(12.5, 1, 900.0, 2.0);

        List<DroneStatusUpdate> updates = new ArrayList<>();
        DroneExecutionEngine engine = new DroneExecutionEngine(
                1,
                buildFarZones(),
                100,
                update -> {
                    synchronized (updates) {
                        updates.add(update);
                    }
                },
                msg -> {
                });
        Thread droneThread = new Thread(engine, "drone-packet-loss-state-test");

        try {
            droneThread.start();
            engine.submitCommand(buildDispatchCommand(FaultType.PACKET_LOSS, 0));

            Thread.sleep(1200);

            assertEquals(DroneState.EN_ROUTE, engine.getCurrentState());
            assertTrue(containsFault(updates, FaultType.PACKET_LOSS));
            assertTrue(containsRetryMessage(updates));
        } finally {
            droneThread.interrupt();
            droneThread.join(1000);
            Drone.resetDefaults();
        }
    }

    private DispatchCommand buildDispatchCommand(FaultType faultType, int faultDelaySeconds) {
        FireRequest request = new FireRequest(
                LocalTime.of(12, 0),
                1,
                EventType.FIRE_DETECTED,
                Severity.LOW,
                faultType,
                faultDelaySeconds);
        Mission mission = new Mission(1, request);
        return DispatchCommand.dispatch(1, mission, faultType, faultDelaySeconds);
    }

    private boolean containsFault(List<DroneStatusUpdate> updates, FaultType faultType) {
        synchronized (updates) {
            for (DroneStatusUpdate update : updates) {
                if (update.getFaultType() == faultType) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsRetryMessage(List<DroneStatusUpdate> updates) {
        synchronized (updates) {
            for (DroneStatusUpdate update : updates) {
                if (update.getMessage() != null && update.getMessage().contains("retried after packet loss")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsMessage(List<DroneStatusUpdate> updates, String text) {
        synchronized (updates) {
            for (DroneStatusUpdate update : updates) {
                if (update.getMessage() != null && update.getMessage().contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<Integer, Zone> buildZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        return zones;
    }

    private Map<Integer, Zone> buildFarZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(1000;1000)"));
        return zones;
    }
}
