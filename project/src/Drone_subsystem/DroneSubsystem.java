package Drone_subsystem;

import Scheduler.Scheduler;
import fire_incident_subsystem.FireRequest;
import gui.SimulationGUI;
import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.Mission;

import java.util.Map;

/**
 * Drone subsystem state machine and mission execution.
 */
public class DroneSubsystem implements Runnable {

    private final int droneId;
    private final Scheduler scheduler;
    private final Drone drone;
    private final Map<Integer, Zone> zoneMap;
    private final SimulationGUI gui;
    private final int timeScale;

    private DroneState currentState = DroneState.IDLE;

    public DroneSubsystem(Scheduler scheduler, Map<Integer, Zone> zoneMap, SimulationGUI gui) {
        this(1, scheduler, zoneMap, gui, 1);
    }

    public DroneSubsystem(Scheduler scheduler, Map<Integer, Zone> zoneMap, SimulationGUI gui, int timeScale) {
        this(1, scheduler, zoneMap, gui, timeScale);
    }

    public DroneSubsystem(int droneId, Scheduler scheduler, Map<Integer, Zone> zoneMap, SimulationGUI gui,
            int timeScale) {
        this.droneId = Math.max(1, droneId);
        this.scheduler = scheduler;
        this.zoneMap = zoneMap;
        this.gui = gui;
        this.timeScale = Math.max(1, timeScale);
        this.drone = new Drone();
    }

    @Override
    public void run() {
        log("[Drone-" + droneId + "] Subsystem started. State: " + currentState + ". Waiting for dispatch...");

        while (!Thread.currentThread().isInterrupted()) {
            DispatchCommand cmd = scheduler.getDispatchCommandForDrone(droneId);
            if (cmd == null) {
                break;
            }

            if (cmd.isReturnToBase()) {
                handleReturnToBase(0);
                continue;
            }

            Mission mission = cmd.getMission();
            if (mission != null) {
                executeMission(mission);
            }
        }

        log("[Drone-" + droneId + "] Subsystem shutting down.");
    }

    private void executeMission(Mission mission) {
        FireRequest req = mission.getFireRequest();
        if (req == null) {
            return;
        }

        int zoneId = req.getZoneId();
        Zone targetZone = zoneMap.get(zoneId);
        if (targetZone == null) {
            log("[Drone-" + droneId + "] ERROR: Zone " + zoneId + " not found in map.");
            sendStatusUpdate(DroneState.IDLE, mission.getMissionId(), "Zone not found, mission aborted");
            return;
        }

        targetZone.setZoneState(Zone.ZoneState.ON_FIRE);
        req.setInProgress(true);

        int targetX = targetZone.getMiddleX();
        int targetY = targetZone.getMiddleY();

        log(String.format("[Drone-%d] Accepted Mission %d for Zone %d (foam needed: %.1fL)",
                droneId, mission.getMissionId(), zoneId, req.getRequiredFoam()));

        sendStatusUpdate(DroneState.EN_ROUTE, mission.getMissionId(),
                "Dispatch accepted, en route to Zone " + zoneId);

        try {
            while (req.getRequiredFoam() > 0) {
                transitionTo(DroneState.EN_ROUTE, "DISPATCH", mission.getMissionId());

                double travelTime = drone.travelTimeTo(targetX, targetY);
                drone.useBattery(travelTime);
                log(String.format("[Drone-%d] Traveling to Zone %d. Distance %.1f, Travel %.1fs",
                        droneId, zoneId, drone.distanceTo(targetX, targetY), travelTime));
                Thread.sleep(scaleDelay((long) (travelTime * 1000)));

                drone.setPosition(targetX, targetY);
                sendStatusUpdate(DroneState.DROPPING_AGENT, mission.getMissionId(),
                        "Arrived at Zone " + zoneId + ", beginning agent drop");
                transitionTo(DroneState.DROPPING_AGENT, "ARRIVED", mission.getMissionId());

                Thread.sleep(scaleDelay((long) (Drone.getTotalExtinguishingTime() * 1000)));

                double dropped = drone.useAgent(Drone.getLoadCapacity());
                req.updateRequiredFoam(dropped);
                log(String.format("[Drone-%d] Dropped %.1fL. Remaining foam %.1fL. Agent left %.1fL",
                        droneId, dropped, req.getRequiredFoam(), drone.getRemainingAgent()));
                sendStatusUpdate(currentState, mission.getMissionId(),
                        String.format("Drop complete, remaining foam needed=%.1fL", req.getRequiredFoam()));

                if (req.getRequiredFoam() > 0 && drone.getRemainingAgent() <= 0) {
                    log("[Drone-" + droneId + "] Tank empty, fire still active. Returning to base.");
                    transitionTo(DroneState.RETURNING, "TANK_EMPTY", mission.getMissionId());
                    double returnTime = drone.travelTimeToHome();
                    drone.useBattery(returnTime);
                    Thread.sleep(scaleDelay((long) (returnTime * 1000)));

                    drone.refill();
                    log(String.format("[Drone-%d] At base. Instant refill complete: agent=%.1fL, battery=%.1fs",
                            droneId, Drone.getLoadCapacity(), Drone.getFullBattery()));
                    sendStatusUpdate(DroneState.IDLE, mission.getMissionId(),
                            "Refilled at base, ready for next trip to Zone " + zoneId);
                }
            }

            req.setResolved(true);
            req.setInProgress(false);
            targetZone.setZoneState(Zone.ZoneState.EXTINGUISHED);
            transitionTo(DroneState.IDLE, "MISSION_COMPLETE", mission.getMissionId());
            log(String.format("[Drone-%d] Mission %d COMPLETE. Zone %d extinguished.",
                    droneId, mission.getMissionId(), zoneId));
            sendStatusUpdate(DroneState.IDLE, mission.getMissionId(),
                    "Mission complete, fire extinguished in Zone " + zoneId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("[Drone-" + droneId + "] Mission interrupted.");
        }
    }

    private void handleReturnToBase(int missionId) {
        transitionTo(DroneState.RETURNING, "RETURN_TO_BASE_CMD", missionId);
        double returnTime = drone.travelTimeToHome();
        log(String.format("[Drone-%d] Returning to base. Travel time %.1fs", droneId, returnTime));

        try {
            Thread.sleep(scaleDelay((long) (returnTime * 1000)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        drone.refill();
        transitionTo(DroneState.IDLE, "AT_BASE_REFILLED", missionId);
        log(String.format("[Drone-%d] At base. Instant refill complete: agent=%.1fL, battery=%.1fs",
                droneId, Drone.getLoadCapacity(), Drone.getFullBattery()));
        sendStatusUpdate(DroneState.IDLE, missionId, "Returned to base, refilled and ready");
    }

    private void transitionTo(DroneState newState, String event, int missionId) {
        DroneState oldState = currentState;
        currentState = newState;
        log(String.format("[Drone-%d] %s --(%s)--> %s [Mission %d]",
                droneId, oldState, event, newState, missionId));
        if (gui != null) {
            gui.setDroneState(newState.toString());
        }
    }

    private void sendStatusUpdate(DroneState state, int missionId, String message) {
        DroneStatusUpdate update = new DroneStatusUpdate(
                droneId,
                state,
                missionId,
                drone.getRemainingAgent(),
                drone.getRemainingBattery(),
                message);
        scheduler.sendDroneStatusUpdate(update);
    }

    private void log(String message) {
        System.out.println(message);
        if (gui != null) {
            gui.log(message);
        }
    }

    private long scaleDelay(long millis) {
        return Math.max(1L, millis / timeScale);
    }
}
