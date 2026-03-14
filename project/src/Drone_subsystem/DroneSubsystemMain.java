package Drone_subsystem;

import fire_incident_subsystem.FireRequest;
import types.DispatchCommand;
import types.EventType;
import types.Mission;
import types.Severity;
import types.UdpConfig;
import types.UdpUtil;

import java.net.DatagramSocket;
import java.time.LocalTime;
import java.util.Map;

/**
 * Separate-process launcher for Drone subsystem (UDP mode).
 */
public class DroneSubsystemMain {

    public static void main(String[] args) {
        int droneId = parseIntArg(args, "--droneId", 1);
        int commandPort = parseIntArg(args, "--commandPort", UdpConfig.commandPortForDrone(droneId));
        String schedulerHost = getArg(args, "--schedulerHost", "localhost");
        int schedulerStatusPort = parseIntArg(args, "--schedulerStatusPort", UdpConfig.DRONE_STATUS_PORT);
        String zoneCsv = getArg(args, "--zoneCsv", "sampleData/sample_zone_file.csv");
        int timeScale = parseIntArg(args, "--timeScale", 1);

        double capacity = parseDoubleArg(args, "--capacity", Drone.getLoadCapacity());
        int speed = parseIntArg(args, "--speed", Drone.getSpeed());
        double battery = parseDoubleArg(args, "--battery", Drone.getFullBattery());
        double dropTime = parseDoubleArg(args, "--dropTime", Drone.getTotalExtinguishingTime());
        Drone.configure(capacity, speed, battery, dropTime);

        Map<Integer, Zone> zoneMap = Zone.loadZones(zoneCsv);
        DroneExecutionEngine engine = new DroneExecutionEngine(
                droneId,
                zoneMap,
                timeScale,
                update -> sendStatus(schedulerHost, schedulerStatusPort, update),
                msg -> log(droneId, msg));

        Thread commandListener = new Thread(() -> listenForCommands(droneId, commandPort, engine),
                "drone-udp-command-listener-" + droneId);
        commandListener.setDaemon(true);
        commandListener.start();

        log(droneId, "DroneSubsystemMain running.");
        log(droneId, "Command listen port: " + commandPort);
        log(droneId, String.format("Config: capacity=%.1fL speed=%d battery=%.1fs drop=%.1fs",
                Drone.getLoadCapacity(), Drone.getSpeed(), Drone.getFullBattery(), Drone.getTotalExtinguishingTime()));

        try {
            engine.run();
        } finally {
            commandListener.interrupt();
        }
    }

    private static void listenForCommands(int droneId, int commandPort, DroneExecutionEngine engine) {
        try (DatagramSocket socket = new DatagramSocket(commandPort)) {
            while (!Thread.currentThread().isInterrupted()) {
                String msg = UdpUtil.receive(socket);
                DispatchCommand command = decodeCommand(msg, droneId);
                if (command != null) {
                    engine.submitCommand(command);
                }
            }
        } catch (Exception e) {
            log(droneId, "Listener stopped: " + e.getMessage());
        }
    }

    private static DispatchCommand decodeCommand(String msg, int droneId) {
        if (msg == null || !msg.startsWith("CMD|")) {
            return null;
        }

        String[] p = msg.split("\\|");
        if (p.length < 3) {
            return null;
        }

        String cmdType = p[1];
        int targetDroneId = Integer.parseInt(p[2]);
        if (targetDroneId != 0 && targetDroneId != droneId) {
            return null;
        }

        if ("RETURN".equals(cmdType)) {
            return DispatchCommand.returnToBase(droneId);
        }

        if ("DISPATCH".equals(cmdType) && p.length >= 8) {
            int missionId = Integer.parseInt(p[3]);
            int zoneId = Integer.parseInt(p[4]);
            LocalTime time = LocalTime.parse(p[5]);
            EventType type = EventType.valueOf(p[6]);
            Severity severity = Severity.valueOf(p[7]);

            FireRequest req = new FireRequest(time, zoneId, type, severity);
            Mission mission = new Mission(missionId, req);
            return DispatchCommand.dispatch(droneId, mission);
        }

        return null;
    }

    private static void sendStatus(String host, int port, types.DroneStatusUpdate update) {
        String payload = String.format("STATUS|%d|%s|%d|%.3f|%.3f|%.3f|%.3f|%s",
                update.getDroneId(),
                update.getDroneState().name(),
                update.getMissionId(),
                update.getRemainingAgent(),
                update.getRemainingBattery(),
                update.getPositionX(),
                update.getPositionY(),
                update.getMessage());
        UdpUtil.send(host, port, payload);
    }

    private static String getArg(String[] args, String key, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equalsIgnoreCase(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }

    private static int parseIntArg(String[] args, String key, int def) {
        try {
            return Integer.parseInt(getArg(args, key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseDoubleArg(String[] args, String key, double def) {
        try {
            return Double.parseDouble(getArg(args, key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    private static void log(int droneId, String msg) {
        System.out.println("[DroneMain-" + droneId + "] " + msg);
    }
}
