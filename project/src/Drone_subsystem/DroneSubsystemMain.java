package Drone_subsystem;

import fire_incident_subsystem.FireRequest;
import types.DroneState;
import types.EventType;
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
        int commandPort = parseIntArg(args, "--commandPort", UdpConfig.SCHED_TO_DRONE_PORT);
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
        Drone drone = new Drone();
        DroneState currentState = DroneState.IDLE;

        log(droneId, "DroneSubsystemMain running.");
        log(droneId, "Command listen port: " + commandPort);
        log(droneId, String.format("Config: capacity=%.1fL speed=%d battery=%.1fs drop=%.1fs",
                Drone.getLoadCapacity(), Drone.getSpeed(), Drone.getFullBattery(), Drone.getTotalExtinguishingTime()));

        try (DatagramSocket socket = new DatagramSocket(commandPort)) {
            while (!Thread.currentThread().isInterrupted()) {
                String msg = UdpUtil.receive(socket);
                if (!msg.startsWith("CMD|")) {
                    continue;
                }

                String[] p = msg.split("\\|");
                if (p.length < 3) {
                    continue;
                }

                String cmdType = p[1];
                int targetDroneId = Integer.parseInt(p[2]);
                if (targetDroneId != 0 && targetDroneId != droneId) {
                    continue;
                }

                if ("RETURN".equals(cmdType)) {
                    currentState = handleReturnToBase(droneId, 0, drone, currentState, schedulerHost, schedulerStatusPort,
                            timeScale);
                    continue;
                }

                if ("DISPATCH".equals(cmdType) && p.length >= 8) {
                    int missionId = Integer.parseInt(p[3]);
                    int zoneId = Integer.parseInt(p[4]);
                    LocalTime time = LocalTime.parse(p[5]);
                    EventType type = EventType.valueOf(p[6]);
                    Severity severity = Severity.valueOf(p[7]);
                    FireRequest req = new FireRequest(time, zoneId, type, severity);
                    currentState = executeMission(
                            droneId, missionId, req, zoneMap, drone, currentState,
                            schedulerHost, schedulerStatusPort, timeScale);
                }
            }
        } catch (Exception e) {
            log(droneId, "Listener stopped: " + e.getMessage());
        }
    }

    private static DroneState executeMission(int droneId, int missionId, FireRequest req,
            Map<Integer, Zone> zoneMap, Drone drone, DroneState currentState,
            String schedulerHost, int schedulerStatusPort, int timeScale) {
        int zoneId = req.getZoneId();
        Zone targetZone = zoneMap.get(zoneId);
        if (targetZone == null) {
            sendStatus(schedulerHost, schedulerStatusPort, droneId, DroneState.IDLE, missionId, drone, "Zone not found");
            return DroneState.IDLE;
        }

        targetZone.setZoneState(Zone.ZoneState.ON_FIRE);
        req.setInProgress(true);
        int targetX = targetZone.getMiddleX();
        int targetY = targetZone.getMiddleY();

        sendStatus(schedulerHost, schedulerStatusPort, droneId, DroneState.EN_ROUTE, missionId, drone,
                "Dispatch accepted, en route to Zone " + zoneId);

        try {
            while (req.getRequiredFoam() > 0) {
                currentState = transitionTo(droneId, currentState, DroneState.EN_ROUTE, "DISPATCH", missionId);
                double travelTime = drone.travelTimeTo(targetX, targetY);
                drone.useBattery(travelTime);
                Thread.sleep(scaleDelay((long) (travelTime * 1000), timeScale));

                drone.setPosition(targetX, targetY);
                sendStatus(schedulerHost, schedulerStatusPort, droneId, DroneState.DROPPING_AGENT, missionId, drone,
                        "Arrived at Zone " + zoneId + ", beginning agent drop");

                currentState = transitionTo(droneId, currentState, DroneState.DROPPING_AGENT, "ARRIVED", missionId);
                Thread.sleep(scaleDelay((long) (Drone.getTotalExtinguishingTime() * 1000), timeScale));

                double dropped = drone.useAgent(Drone.getLoadCapacity());
                req.updateRequiredFoam(dropped);
                sendStatus(schedulerHost, schedulerStatusPort, droneId, currentState, missionId, drone,
                        String.format("Drop complete, remaining foam needed=%.1fL", req.getRequiredFoam()));

                if (req.getRequiredFoam() > 0 && drone.getRemainingAgent() <= 0) {
                    currentState = transitionTo(droneId, currentState, DroneState.RETURNING, "TANK_EMPTY", missionId);
                    double returnTime = drone.travelTimeToHome();
                    drone.useBattery(returnTime);
                    Thread.sleep(scaleDelay((long) (returnTime * 1000), timeScale));

                    drone.refill();
                    sendStatus(schedulerHost, schedulerStatusPort, droneId, DroneState.IDLE, missionId, drone,
                            "Refilled at base, ready for next trip to Zone " + zoneId);
                    currentState = DroneState.IDLE;
                }
            }

            req.setResolved(true);
            req.setInProgress(false);
            targetZone.setZoneState(Zone.ZoneState.EXTINGUISHED);
            currentState = transitionTo(droneId, currentState, DroneState.IDLE, "MISSION_COMPLETE", missionId);
            sendStatus(schedulerHost, schedulerStatusPort, droneId, DroneState.IDLE, missionId, drone,
                    "Mission complete, fire extinguished in Zone " + zoneId);
            return currentState;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return currentState;
        }
    }

    private static DroneState handleReturnToBase(int droneId, int missionId, Drone drone, DroneState currentState,
            String schedulerHost, int schedulerStatusPort, int timeScale) {
        currentState = transitionTo(droneId, currentState, DroneState.RETURNING, "RETURN_TO_BASE_CMD", missionId);
        double returnTime = drone.travelTimeToHome();
        try {
            Thread.sleep(scaleDelay((long) (returnTime * 1000), timeScale));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return currentState;
        }

        drone.refill();
        currentState = transitionTo(droneId, currentState, DroneState.IDLE, "AT_BASE_REFILLED", missionId);
        sendStatus(schedulerHost, schedulerStatusPort, droneId, DroneState.IDLE, missionId, drone,
                "Returned to base, refilled and ready");
        return currentState;
    }

    private static DroneState transitionTo(int droneId, DroneState oldState, DroneState newState, String event,
            int missionId) {
        log(droneId, String.format("%s --(%s)--> %s [Mission %d]", oldState, event, newState, missionId));
        return newState;
    }

    private static void sendStatus(String host, int port, int droneId, DroneState state, int missionId, Drone drone,
            String message) {
        String payload = String.format("STATUS|%d|%s|%d|%.3f|%.3f|%s",
                droneId, state.name(), missionId, drone.getRemainingAgent(), drone.getRemainingBattery(), message);
        UdpUtil.send(host, port, payload);
    }

    private static long scaleDelay(long millis, int timeScale) {
        return Math.max(1L, millis / Math.max(1, timeScale));
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
