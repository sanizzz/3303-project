import Drone_subsystem.Drone;
import Drone_subsystem.DroneSubsystem;
import Drone_subsystem.Zone;
import Scheduler.Scheduler;
import fire_incident_subsystem.FireRequest;
import org.junit.jupiter.api.AfterEach;
import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.EventType;
import types.Severity;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

abstract class SchedulerTestSupport {

    protected static final LocalTime BASE_TIME = LocalTime.of(12, 0);
    protected static final long DEFAULT_TIMEOUT_MS = 4000;

    private final List<Thread> threads = new ArrayList<>();

    @AfterEach
    void tearDownThreads() {
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
        Drone.resetDefaults();
    }

    protected Scheduler startScheduler(Scheduler scheduler) {
        Thread thread = new Thread(scheduler, "scheduler-test-thread-" + threads.size());
        threads.add(thread);
        thread.start();
        return scheduler;
    }

    protected void startDroneSubsystem(int droneId, Scheduler scheduler, Map<Integer, Zone> zoneMap, int timeScale) {
        DroneSubsystem droneSubsystem = new DroneSubsystem(droneId, scheduler, zoneMap, null, timeScale);
        Thread thread = new Thread(droneSubsystem, "drone-test-thread-" + droneId);
        threads.add(thread);
        thread.start();
    }

    protected FireRequest request(int zoneId, Severity severity, int offsetSeconds) {
        return new FireRequest(BASE_TIME.plusSeconds(offsetSeconds), zoneId, EventType.FIRE_DETECTED, severity);
    }

    protected DroneStatusUpdate status(int droneId, DroneState state, int missionId,
            double remainingAgent, double remainingBattery, double positionX, double positionY, String message) {
        return new DroneStatusUpdate(droneId, state, missionId, remainingAgent, remainingBattery, positionX, positionY,
                message);
    }

    protected DispatchCommand awaitDispatchCommand(Scheduler scheduler, int droneId, long timeoutMs) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<DispatchCommand> future = executor.submit(() -> scheduler.getDispatchCommandForDrone(droneId));
        try {
            DispatchCommand cmd = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            assertNotNull(cmd);
            return cmd;
        } catch (TimeoutException e) {
            future.cancel(true);
            fail("Timed out waiting for dispatch command.");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for dispatch command.");
            return null;
        } catch (ExecutionException e) {
            fail("Dispatch command retrieval failed: " + e.getCause());
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    protected FireRequest awaitCompletion(Scheduler scheduler, long timeoutMs) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<FireRequest> future = executor.submit(scheduler::getCompletion);
        try {
            FireRequest completion = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            assertNotNull(completion);
            return completion;
        } catch (TimeoutException e) {
            future.cancel(true);
            fail("Timed out waiting for completion update.");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for completion update.");
            return null;
        } catch (ExecutionException e) {
            fail("Completion retrieval failed: " + e.getCause());
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    protected void waitUntil(BooleanSupplier condition, long timeoutMs, String timeoutMessage) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for condition.");
            }
        }
        fail(timeoutMessage);
    }

    protected Map<Integer, Zone> buildNominalZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        zones.put(2, new Zone("2", "(120;0)", "(220;100)"));
        zones.put(3, new Zone("3", "(240;0)", "(340;100)"));
        return zones;
    }

    protected Map<Integer, Zone> buildLineZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        zones.put(2, new Zone("2", "(100;0)", "(200;100)"));
        zones.put(3, new Zone("3", "(300;0)", "(400;100)"));
        return zones;
    }

    protected Map<Integer, Zone> buildBatteryStressZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        zones.put(2, new Zone("2", "(2000;0)", "(2100;100)"));
        return zones;
    }

    protected Map<Integer, Zone> buildEqualDistanceZones() {
        Map<Integer, Zone> zones = new HashMap<>();
        zones.put(1, new Zone("1", "(0;0)", "(100;100)"));
        zones.put(2, new Zone("2", "(0;120)", "(100;220)"));
        zones.put(3, new Zone("3", "(220;0)", "(320;100)"));
        return zones;
    }
}
