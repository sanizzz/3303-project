import Drone_subsystem.DroneSubsystem;
import Drone_subsystem.Drone;
import Drone_subsystem.Zone;
import Scheduler.Scheduler;
import fire_incident_subsystem.FireIncidentSubsystem;
import gui.SimulationGUI;
import types.LogUtil;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.Arrays;
import java.util.Map;

public class Main {
    public static final int TIME_SCALE = 20;
    public static final int FLEET_SIZE = 10;

    public static void main(String[] args) {
        System.out.println(LogUtil.stamp("[System] DEMO MODE ENABLED: Speed x" + TIME_SCALE));

        String zoneCsvPath = resolveExistingPath(
                "project/sampleData/sample_zone_file.csv",
                "sampleData/sample_zone_file.csv",
                "out/production/3303 project/sample_zone_file.csv");

        Map<Integer, Zone> zoneMap = Zone.loadZones(zoneCsvPath);
        if (zoneMap.isEmpty()) {
            throw new IllegalStateException("Zone map did not load. Check CSV path and file format.");
        }

        final SimulationGUI[] guiRef = new SimulationGUI[1];
        try {
            SwingUtilities.invokeAndWait(() -> guiRef[0] = new SimulationGUI(zoneMap, zoneCsvPath));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create GUI", e);
        }

        SimulationGUI gui = guiRef[0];

        final boolean[] started = { false };
        gui.setStartAction(() -> {
            if (started[0]) {
                gui.log("Simulation already started.");
                return;
            }

            String csvFile = gui.getSelectedCsvFile();
            if (csvFile == null || csvFile.isBlank()) {
                gui.log("Please load a CSV file first.");
                return;
            }

            String selectedZoneCsvPath = gui.getSelectedZoneCsvFile();
            if (selectedZoneCsvPath == null || selectedZoneCsvPath.isBlank()) {
                selectedZoneCsvPath = zoneCsvPath;
            }

            Map<Integer, Zone> selectedZoneMap = Zone.loadZones(selectedZoneCsvPath);
            if (selectedZoneMap.isEmpty()) {
                gui.log("Zone CSV did not load. Check the selected file.");
                return;
            }

            gui.setZoneMap(selectedZoneMap);

            int drones = FLEET_SIZE;
            double capacity = gui.getCapacity();
            gui.log("[Main] Start pressed.");
            gui.log("[Main] Config -> Drones=" + drones + ", Capacity=" + capacity + "L");
            gui.log("[Main] Zone map -> " + selectedZoneCsvPath);

            Scheduler scheduler = new Scheduler(gui, selectedZoneMap, FLEET_SIZE, TIME_SCALE);
            FireIncidentSubsystem fireSubsystem = new FireIncidentSubsystem(scheduler, gui, csvFile, TIME_SCALE);

            scheduler.setConfiguredDroneCount(drones);
            gui.setConfiguredDroneCount(drones);
            Drone.configure(capacity, Drone.getSpeed(), Drone.getFullBattery(), Drone.estimateDropTimeSeconds(capacity));

            Thread schedulerThread = new Thread(scheduler, "scheduler-thread");
            Thread fireThread = new Thread(fireSubsystem, "fire-thread");

            schedulerThread.start();
            for (int i = 1; i <= drones; i++) {
                DroneSubsystem droneSubsystem = new DroneSubsystem(i, scheduler, selectedZoneMap, gui, TIME_SCALE);
                Thread droneThread = new Thread(droneSubsystem, "drone-thread-" + i);
                droneThread.start();
            }
            fireThread.start();

            started[0] = true;
        });

        SwingUtilities.invokeLater(() -> new javax.swing.Timer(250, e -> gui.refresh()).start());
        gui.log("GUI ready. Load event CSV and optional zone CSV, then press Start.");
    }

    /**
     * This resolves bundled sample-data paths for both the GUI entry point and the metrics runner.
     */
    public static String resolveExistingPath(String... candidates) {
        for (String candidate : candidates) {
            if (new File(candidate).exists()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to find required input file. Tried: " + Arrays.toString(candidates));
    }
}
