import Drone_subsystem.Drone;
import Drone_subsystem.DroneSubsystem;
import Drone_subsystem.Zone;
import Scheduler.Scheduler;
import fire_incident_subsystem.FireIncidentSubsystem;
import types.Mission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class runs one headless simulation sample and prints the Iteration 5 timing metrics.
 */
public class MetricsRunner {

    private static final int FLEET_SIZE = 3;
    private static final int DEFAULT_TIME_SCALE = 200;
    private static final double DEFAULT_CAPACITY_LITERS = 15.0;

    /**
     * This is the headless entry point used for the grading metrics pass.
     */
    public static void main(String[] args) throws Exception {
        String zoneCsv = getArg(args, "--zoneCsv", Main.resolveExistingPath(
                "project/sampleData/Final_zone_file_w26.csv",
                "sampleData/Final_zone_file_w26.csv",
                "project/sampleData/sample_zone_file.csv",
                "sampleData/sample_zone_file.csv",
                "out/production/3303 project/sample_zone_file.csv"));
        String eventCsv = getArg(args, "--eventCsv", Main.resolveExistingPath(
                "project/sampleData/Final_event_file_w26.csv",
                "sampleData/Final_event_file_w26.csv",
                "project/sampleData/test_mixed_scenario.csv",
                "sampleData/test_mixed_scenario.csv",
                "out/production/3303 project/test_mixed_scenario.csv"));
        int timeScale = parseIntArg(args, "--timeScale", DEFAULT_TIME_SCALE);
        double capacity = parseDoubleArg(args, "--capacity", DEFAULT_CAPACITY_LITERS);

        RunResult result = executeSingleRun(1, zoneCsv, eventCsv, timeScale, capacity);
        System.out.printf("Total Time to Extinguish All Fires: %.3fs%n", result.totalProcessingSeconds);
        System.out.printf("Average Drone Idle Time: %.3fs%n", result.averageIdleSeconds);
        System.out.printf("Average Drone Flight Time: %.3fs%n", result.averageFlightSeconds);
        System.out.printf("Average Detection-to-Extinguishment Time: %.3fs%n",
                result.averageDetectionToExtinguishmentSeconds);
    }

    /**
     * This executes one completely fresh simulation sample and tears its threads down before
     * the next sample begins.
     */
    private static RunResult executeSingleRun(int runNumber, String zoneCsv, String eventCsv,
            int timeScale, double capacity) throws Exception {
        Drone.resetDefaults();
        Mission.resetIds();
        Drone.configure(capacity, Drone.getSpeed(), Drone.getFullBattery(), Drone.estimateDropTimeSeconds(capacity));

        Map<Integer, Zone> zoneMap = Zone.loadZones(zoneCsv);
        Scheduler scheduler = new Scheduler(null, zoneMap, FLEET_SIZE, timeScale);
        FireIncidentSubsystem fireSubsystem = new FireIncidentSubsystem(scheduler, null, eventCsv, timeScale);

        Thread schedulerThread = new Thread(scheduler, "metrics-scheduler-" + runNumber);
        Thread fireThread = new Thread(fireSubsystem, "metrics-fire-" + runNumber);
        List<Thread> droneThreads = new ArrayList<>();
        List<DroneSubsystem> droneSubsystems = new ArrayList<>();

        for (int droneId = 1; droneId <= FLEET_SIZE; droneId++) {
            DroneSubsystem droneSubsystem = new DroneSubsystem(droneId, scheduler, zoneMap, null, timeScale);
            Thread droneThread = new Thread(droneSubsystem, "metrics-drone-" + runNumber + "-" + droneId);
            droneSubsystems.add(droneSubsystem);
            droneThreads.add(droneThread);
        }

        long startNs = System.nanoTime();
        schedulerThread.start();
        for (Thread droneThread : droneThreads) {
            droneThread.start();
        }
        fireThread.start();

        fireThread.join();
        scheduler.waitUntilQuiescent();
        long endNs = System.nanoTime();

        shutdownThread(schedulerThread, "scheduler");
        for (Thread droneThread : droneThreads) {
            shutdownThread(droneThread, droneThread.getName());
        }

        double totalSeconds = nanosToSeconds(endNs - startNs);
        double averageIdle = averageIdleSeconds(droneSubsystems);
        double averageFlight = averageFlightSeconds(droneSubsystems);
        double averageDetectionToExtinguishment = fireSubsystem.getAverageDetectionToExtinguishmentSeconds();
        return new RunResult(runNumber, totalSeconds, averageIdle, averageFlight, averageDetectionToExtinguishment);
    }

    /**
     * This interrupts and joins one thread so the next run starts with no survivor threads
     * from the previous sample.
     */
    private static void shutdownThread(Thread thread, String role) throws InterruptedException {
        if (thread == null) {
            return;
        }
        thread.interrupt();
        thread.join(5000);
        if (thread.isAlive()) {
            throw new IllegalStateException("Timed out while stopping " + role + " thread: " + thread.getName());
        }
    }

    /**
     * This averages the per-drone idle timing counters for one simulation run.
     */
    private static double averageIdleSeconds(List<DroneSubsystem> droneSubsystems) {
        double total = 0.0;
        for (DroneSubsystem droneSubsystem : droneSubsystems) {
            total += droneSubsystem.getIdleSeconds();
        }
        return droneSubsystems.isEmpty() ? 0.0 : total / droneSubsystems.size();
    }

    /**
     * This averages the per-drone flight timing counters for one simulation run.
     */
    private static double averageFlightSeconds(List<DroneSubsystem> droneSubsystems) {
        double total = 0.0;
        for (DroneSubsystem droneSubsystem : droneSubsystems) {
            total += droneSubsystem.getFlightSeconds();
        }
        return droneSubsystems.isEmpty() ? 0.0 : total / droneSubsystems.size();
    }

    /**
     * This converts the nanosecond stopwatch values into seconds for printed metrics.
     */
    private static double nanosToSeconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    /**
     * This reads an optional command-line argument while keeping a safe default for grading.
     */
    private static String getArg(String[] args, String key, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equalsIgnoreCase(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    /**
     * This reads an optional integer argument for the headless runner.
     */
    private static int parseIntArg(String[] args, String key, int defaultValue) {
        try {
            return Integer.parseInt(getArg(args, key, String.valueOf(defaultValue)));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    /**
     * This reads an optional floating-point argument for drone capacity.
     */
    private static double parseDoubleArg(String[] args, String key, double defaultValue) {
        try {
            return Double.parseDouble(getArg(args, key, String.valueOf(defaultValue)));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    /**
     * This stores the measurements produced by one simulation sample.
     */
    private static class RunResult {
        private final int runNumber;
        private final double totalProcessingSeconds;
        private final double averageIdleSeconds;
        private final double averageFlightSeconds;
        private final double averageDetectionToExtinguishmentSeconds;

        private RunResult(int runNumber, double totalProcessingSeconds,
                double averageIdleSeconds, double averageFlightSeconds,
                double averageDetectionToExtinguishmentSeconds) {
            this.runNumber = runNumber;
            this.totalProcessingSeconds = totalProcessingSeconds;
            this.averageIdleSeconds = averageIdleSeconds;
            this.averageFlightSeconds = averageFlightSeconds;
            this.averageDetectionToExtinguishmentSeconds = averageDetectionToExtinguishmentSeconds;
        }
    }
}
