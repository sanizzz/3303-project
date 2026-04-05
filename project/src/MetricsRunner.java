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
 * This class runs the simulation 30 times without the Swing GUI and prints the timing metrics
 * required by Iteration 5.
 */
public class MetricsRunner {

    private static final int RUN_COUNT = 30;
    private static final int FLEET_SIZE = 10;
    private static final int DEFAULT_TIME_SCALE = 200;
    private static final double DEFAULT_CAPACITY_LITERS = 15.0;
    private static final double T_CRITICAL_95_DF_29 = 2.04523;

    /**
     * This is the headless entry point used for the grading metrics pass.
     */
    public static void main(String[] args) throws Exception {
        String zoneCsv = getArg(args, "--zoneCsv", Main.resolveExistingPath(
                "project/sampleData/sample_zone_file.csv",
                "sampleData/sample_zone_file.csv",
                "out/production/3303 project/sample_zone_file.csv"));
        String eventCsv = getArg(args, "--eventCsv", Main.resolveExistingPath(
                "project/sampleData/test_mixed_scenario.csv",
                "sampleData/test_mixed_scenario.csv",
                "out/production/3303 project/test_mixed_scenario.csv"));
        int timeScale = parseIntArg(args, "--timeScale", DEFAULT_TIME_SCALE);
        double capacity = parseDoubleArg(args, "--capacity", DEFAULT_CAPACITY_LITERS);

        List<RunResult> results = new ArrayList<>();
        for (int runNumber = 1; runNumber <= RUN_COUNT; runNumber++) {
            RunResult result = executeSingleRun(runNumber, zoneCsv, eventCsv, timeScale, capacity);
            results.add(result);
            System.out.printf("Run %02d | Total Time: %.3fs | Avg Idle: %.3fs | Avg Flight: %.3fs | Avg Detect->Extinguish: %.3fs%n",
                    result.runNumber,
                    result.totalProcessingSeconds,
                    result.averageIdleSeconds,
                    result.averageFlightSeconds,
                    result.averageDetectionToExtinguishmentSeconds);
        }

        printSummary(results);
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
     * This prints the sample mean, sample standard deviation, and the 95% confidence interval
     * for the 30 total-processing-time measurements.
     */
    private static void printSummary(List<RunResult> results) {
        double[] totals = new double[results.size()];
        double[] detectionToExtinguishmentAverages = new double[results.size()];
        for (int i = 0; i < results.size(); i++) {
            totals[i] = results.get(i).totalProcessingSeconds;
            detectionToExtinguishmentAverages[i] = results.get(i).averageDetectionToExtinguishmentSeconds;
        }

        double mean = calculateSampleMean(totals);
        double sampleStandardDeviation = calculateSampleStandardDeviation(totals, mean);
        double[] confidenceInterval = calculateConfidenceInterval(
                mean,
                sampleStandardDeviation,
                results.size(),
                T_CRITICAL_95_DF_29);
        double meanDetectionToExtinguishment = calculateSampleMean(detectionToExtinguishmentAverages);

        System.out.println();
        System.out.printf("Sample Mean Total Processing Time: %.3fs%n", mean);
        System.out.printf("Sample Standard Deviation: %.3fs%n", sampleStandardDeviation);
        System.out.printf("95%% Confidence Interval: [%.3fs, %.3fs]%n",
                confidenceInterval[0],
                confidenceInterval[1]);
        System.out.printf("Sample Mean Detection-to-Extinguishment Time: %.3fs%n",
                meanDetectionToExtinguishment);
    }

    /**
     * This computes the arithmetic mean used by the sample statistics summary.
     * It is package-private so the JUnit math test can validate the rubric calculation
     * directly without starting the simulation threads.
     */
    static double calculateSampleMean(double[] values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return values.length == 0 ? 0.0 : total / values.length;
    }

    /**
     * This computes the sample standard deviation using the n-1 denominator required by the rubric.
     * It is package-private so the JUnit math test can validate the calculation in isolation.
     */
    static double calculateSampleStandardDeviation(double[] values, double mean) {
        if (values.length <= 1) {
            return 0.0;
        }

        double squaredDifferenceTotal = 0.0;
        for (double value : values) {
            double difference = value - mean;
            squaredDifferenceTotal += difference * difference;
        }
        return Math.sqrt(squaredDifferenceTotal / (values.length - 1));
    }

    /**
     * This computes the confidence-interval bounds for a sample mean using whichever
     * critical value the caller provides.
     */
    static double[] calculateConfidenceInterval(double mean, double sampleStandardDeviation,
            int sampleSize, double criticalValue) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize must be positive.");
        }

        double halfWidth = criticalValue * sampleStandardDeviation / Math.sqrt(sampleSize);
        return new double[] { mean - halfWidth, mean + halfWidth };
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
