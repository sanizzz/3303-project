package Scheduler;

import Drone_subsystem.Zone;
import fire_incident_subsystem.FireRequest;
import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.EventType;
import types.Mission;
import types.Severity;
import types.UdpConfig;
import types.UdpUtil;

import java.net.DatagramSocket;
import java.time.LocalTime;
import java.util.Map;

/**
 * Separate-process launcher for Scheduler subsystem (UDP mode).
 */
public class SchedulerMain {

    public static void main(String[] args) {
        String zoneCsv = getArg(args, "--zoneCsv", "sampleData/sample_zone_file.csv");
        int drones = parseIntArg(args, "--drones", 1);

        int firePort = parseIntArg(args, "--firePort", UdpConfig.FIRE_TO_SCHED_PORT);
        int droneStatusPort = parseIntArg(args, "--droneStatusPort", UdpConfig.DRONE_STATUS_PORT);

        String droneHost = getArg(args, "--droneHost", "localhost");
        int droneCommandBasePort = parseIntArg(args, "--droneCommandBasePort",
                parseIntArg(args, "--droneCommandPort", UdpConfig.DRONE_COMMAND_BASE_PORT));

        String fireHost = getArg(args, "--fireHost", "localhost");
        int completionPort = parseIntArg(args, "--completionPort", UdpConfig.SCHED_TO_FIRE_PORT);

        Map<Integer, Zone> zoneMap = Zone.loadZones(zoneCsv);
        Scheduler scheduler = new Scheduler(null, zoneMap, drones);
        Thread schedulerThread = new Thread(scheduler, "scheduler-core");
        schedulerThread.start();

        Thread fireListener = new Thread(() -> runFireListener(scheduler, firePort), "scheduler-fire-listener");
        Thread droneStatusListener = new Thread(() -> runDroneStatusListener(scheduler, droneStatusPort),
                "scheduler-drone-status-listener");
        Thread dispatchForwarder = new Thread(
                () -> runDispatchForwarder(scheduler, droneHost, droneCommandBasePort),
                "scheduler-dispatch-forwarder");
        Thread completionForwarder = new Thread(
                () -> runCompletionForwarder(scheduler, fireHost, completionPort),
                "scheduler-completion-forwarder");

        fireListener.start();
        droneStatusListener.start();
        dispatchForwarder.start();
        completionForwarder.start();

        log("SchedulerMain running.");
        log("Fire listen port: " + firePort);
        log("Drone status listen port: " + droneStatusPort);
        log("Drone command base target: " + droneHost + ":" + droneCommandBasePort);
        log("Completion target: " + fireHost + ":" + completionPort);
        for (int droneId = 1; droneId <= Math.max(1, drones); droneId++) {
            log("Expected Drone " + droneId + " command port: "
                    + UdpConfig.commandPortForDrone(droneId, droneCommandBasePort));
        }
    }

    private static void runFireListener(Scheduler scheduler, int firePort) {
        try (DatagramSocket socket = new DatagramSocket(firePort)) {
            while (!Thread.currentThread().isInterrupted()) {
                String msg = UdpUtil.receive(socket);
                if (!msg.startsWith("REQ|")) {
                    continue;
                }

                String[] p = msg.split("\\|");
                if (p.length < 5) {
                    log("[SchedulerMain] Bad REQ payload: " + msg);
                    continue;
                }

                FireRequest req = new FireRequest(
                        LocalTime.parse(p[1]),
                        Integer.parseInt(p[2]),
                        EventType.valueOf(p[3]),
                        Severity.valueOf(p[4]));
                scheduler.putRequest(req);
            }
        } catch (Exception e) {
            log("[SchedulerMain] Fire listener stopped: " + e.getMessage());
        }
    }

    private static void runDroneStatusListener(Scheduler scheduler, int droneStatusPort) {
        try (DatagramSocket socket = new DatagramSocket(droneStatusPort)) {
            while (!Thread.currentThread().isInterrupted()) {
                String msg = UdpUtil.receive(socket);
                if (!msg.startsWith("STATUS|")) {
                    continue;
                }

                String[] p = msg.split("\\|", 9);
                if (p.length < 7) {
                    log("[SchedulerMain] Bad STATUS payload: " + msg);
                    continue;
                }

                int droneId = Integer.parseInt(p[1]);
                DroneState state = DroneState.valueOf(p[2]);
                int missionId = Integer.parseInt(p[3]);
                double remainingAgent = Double.parseDouble(p[4]);
                double remainingBattery = Double.parseDouble(p[5]);

                DroneStatusUpdate update;
                if (p.length >= 9) {
                    double positionX = Double.parseDouble(p[6]);
                    double positionY = Double.parseDouble(p[7]);
                    String message = p[8];
                    update = new DroneStatusUpdate(
                            droneId,
                            state,
                            missionId,
                            remainingAgent,
                            remainingBattery,
                            positionX,
                            positionY,
                            message);
                } else {
                    update = new DroneStatusUpdate(
                            droneId,
                            state,
                            missionId,
                            remainingAgent,
                            remainingBattery,
                            p[6]);
                }
                scheduler.sendDroneStatusUpdate(update);
            }
        } catch (Exception e) {
            log("[SchedulerMain] Drone status listener stopped: " + e.getMessage());
        }
    }

    private static void runDispatchForwarder(Scheduler scheduler, String droneHost, int droneCommandBasePort) {
        while (!Thread.currentThread().isInterrupted()) {
            DispatchCommand cmd = scheduler.getNextDispatchCommand();
            if (cmd == null) {
                return;
            }

            String payload;
            Integer targetDroneId = cmd.getTargetDroneId() == null ? 0 : cmd.getTargetDroneId();

            if (cmd.isReturnToBase()) {
                payload = "CMD|RETURN|" + targetDroneId;
            } else {
                Mission mission = cmd.getMission();
                if (mission == null || mission.getFireRequest() == null) {
                    continue;
                }
                FireRequest req = mission.getFireRequest();
                payload = String.format(
                        "CMD|DISPATCH|%d|%d|%d|%s|%s|%s",
                        targetDroneId,
                        mission.getMissionId(),
                        req.getZoneId(),
                        req.getTime(),
                        req.getType(),
                        req.getSeverity());
            }

            int targetPort = targetDroneId == 0
                    ? droneCommandBasePort
                    : UdpConfig.commandPortForDrone(targetDroneId, droneCommandBasePort);
            UdpUtil.send(droneHost, targetPort, payload);
        }
    }

    private static void runCompletionForwarder(Scheduler scheduler, String fireHost, int completionPort) {
        while (!Thread.currentThread().isInterrupted()) {
            FireRequest ack = scheduler.getCompletion();
            if (ack == null) {
                return;
            }
            String payload = String.format("COMP|%d|%s", ack.getZoneId(), ack.isResolved());
            UdpUtil.send(fireHost, completionPort, payload);
        }
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

    private static void log(String msg) {
        System.out.println("[SchedulerMain] " + msg);
    }
}
