package Drone_subsystem;

import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.Mission;
import types.UdpConfig;

import java.util.LinkedList;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shared drone execution loop used by both in-process and UDP launcher modes.
 * Commands can arrive while the drone is travelling, which allows safe reroutes.
 */
public class DroneExecutionEngine implements Runnable {

    private static final long COMMAND_WAIT_MS = 100L;
    private static final long TICK_MS = 100L;

    private final int droneId;
    private final Map<Integer, Zone> zoneMap;
    private final Drone drone;
    private final int timeScale;
    private final Consumer<DroneStatusUpdate> statusSink;
    private final Consumer<String> logSink;

    private final Object commandLock = new Object();
    private final LinkedList<DispatchCommand> commandQueue = new LinkedList<>();

    private DroneState currentState = DroneState.IDLE;
    private Mission activeMission;
    private double remainingDropSeconds;
    private int progressTickCounter;

    public DroneExecutionEngine(int droneId, Map<Integer, Zone> zoneMap, int timeScale,
            Consumer<DroneStatusUpdate> statusSink, Consumer<String> logSink) {
        this.droneId = Math.max(1, droneId);
        this.zoneMap = zoneMap;
        this.timeScale = Math.max(1, timeScale);
        this.statusSink = statusSink;
        this.logSink = logSink;
        this.drone = new Drone();
    }

    public void submitCommand(DispatchCommand command) {
        if (command == null) {
            return;
        }
        synchronized (commandLock) {
            commandQueue.add(command);
            commandLock.notifyAll();
        }
    }

    public DroneState getCurrentState() {
        return currentState;
    }

    @Override
    public void run() {
        log(String.format("[Drone-%d] Iteration 3 drone engine started. State=%s", droneId, currentState));

        while (!Thread.currentThread().isInterrupted()) {
            DispatchCommand command = waitForCommandIfIdle();
            if (command != null) {
                handleCommand(command);
                drainExtraCommands();
            }

            if (hasActiveWork()) {
                advanceOneTick();
                try {
                    Thread.sleep(TICK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log(String.format("[Drone-%d] Drone engine shutting down.", droneId));
    }

    private DispatchCommand waitForCommandIfIdle() {
        synchronized (commandLock) {
            if (commandQueue.isEmpty() && !hasActiveWork()) {
                try {
                    commandLock.wait(COMMAND_WAIT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            if (commandQueue.isEmpty()) {
                return null;
            }
            return commandQueue.removeFirst();
        }
    }

    private void drainExtraCommands() {
        while (true) {
            DispatchCommand extra;
            synchronized (commandLock) {
                if (commandQueue.isEmpty()) {
                    return;
                }
                extra = commandQueue.removeFirst();
            }
            handleCommand(extra);
        }
    }

    private void handleCommand(DispatchCommand command) {
        if (command.isReturnToBase()) {
            if (isAtHome() && activeMission == null) {
                sendStatus(DroneState.IDLE, -1, "Already at base and ready for dispatch");
                return;
            }

            log(String.format("[Drone-%d] Command update: return to base.", droneId));
            transitionTo(DroneState.RETURNING, "RETURN_TO_BASE_CMD", currentMissionId());
            if (activeMission == null) {
                sendStatus(DroneState.RETURNING, -1, "Returning to base");
            } else {
                sendStatus(DroneState.RETURNING, activeMission.getMissionId(),
                        "Returning to base for refill before continuing mission");
            }
            return;
        }

        Mission mission = command.getMission();
        if (mission == null) {
            return;
        }

        Zone targetZone = zoneMap.get(mission.getZoneId());
        if (targetZone == null) {
            sendStatus(DroneState.IDLE, mission.getMissionId(), "Zone not found");
            return;
        }

        if (activeMission != null && activeMission.getMissionId() != mission.getMissionId()) {
            log(String.format(
                    "[Drone-%d] Command update: reroute from Mission %d (Zone %d) to Mission %d (Zone %d).",
                    droneId,
                    activeMission.getMissionId(),
                    activeMission.getZoneId(),
                    mission.getMissionId(),
                    mission.getZoneId()));
        } else {
            log(String.format("[Drone-%d] Dispatch accepted: Mission %d -> Zone %d.",
                    droneId, mission.getMissionId(), mission.getZoneId()));
        }

        activeMission = mission;
        activeMission.getFireRequest().setInProgress(true);
        activeMission.getFireRequest().setResolved(false);
        targetZone.setZoneState(Zone.ZoneState.ON_FIRE);
        remainingDropSeconds = Drone.getTotalExtinguishingTime();

        transitionTo(DroneState.EN_ROUTE, "DISPATCH", mission.getMissionId());
        progressTickCounter = 0;
        sendStatus(DroneState.EN_ROUTE, mission.getMissionId(),
                "Dispatch accepted, en route to Zone " + mission.getZoneId());
    }

    private void advanceOneTick() {
        if (currentState == DroneState.EN_ROUTE) {
            advanceTravelToMission();
            return;
        }
        if (currentState == DroneState.DROPPING_AGENT) {
            advanceDrop();
            return;
        }
        if (currentState == DroneState.RETURNING) {
            advanceReturnToBase();
        }
    }

    private void advanceTravelToMission() {
        if (activeMission == null) {
            transitionTo(DroneState.IDLE, "NO_ACTIVE_MISSION", -1);
            return;
        }

        Zone targetZone = zoneMap.get(activeMission.getZoneId());
        if (targetZone == null) {
            sendStatus(DroneState.IDLE, activeMission.getMissionId(), "Zone not found");
            activeMission = null;
            transitionTo(DroneState.IDLE, "MISSION_ABORTED", -1);
            return;
        }

        boolean arrived = moveTowards(targetZone.getMiddleX(), targetZone.getMiddleY(), simulationSecondsPerTick());
        sendProgressStatus(DroneState.EN_ROUTE, activeMission.getMissionId(),
                "En route to Zone " + activeMission.getZoneId());

        if (arrived) {
            remainingDropSeconds = Drone.getTotalExtinguishingTime();
            transitionTo(DroneState.DROPPING_AGENT, "ARRIVED", activeMission.getMissionId());
            sendStatus(DroneState.DROPPING_AGENT, activeMission.getMissionId(),
                    "Arrived at Zone " + activeMission.getZoneId() + ", beginning agent drop");
            log(String.format("[Drone-%d] Arrived at Zone %d. Starting suppression pass.",
                    droneId, activeMission.getZoneId()));
        }
    }

    private void advanceDrop() {
        if (activeMission == null) {
            transitionTo(DroneState.IDLE, "NO_ACTIVE_MISSION", -1);
            return;
        }

        remainingDropSeconds -= simulationSecondsPerTick();
        if (remainingDropSeconds > 0) {
            return;
        }

        double dropped = drone.useAgent(Drone.getLoadCapacity());
        activeMission.getFireRequest().updateRequiredFoam(dropped);
        sendStatus(DroneState.DROPPING_AGENT, activeMission.getMissionId(),
                String.format("Drop complete, remaining foam needed=%.1fL",
                        activeMission.getFireRequest().getRequiredFoam()));

        if (activeMission.getFireRequest().getRequiredFoam() <= 0) {
            completeMission();
            return;
        }

        if (drone.getRemainingAgent() <= 0) {
            transitionTo(DroneState.RETURNING, "TANK_EMPTY", activeMission.getMissionId());
            sendStatus(DroneState.RETURNING, activeMission.getMissionId(),
                    "Tank empty, returning to base for refill");
            log(String.format("[Drone-%d] Agent depleted during Mission %d. Returning to base for refill.",
                    droneId, activeMission.getMissionId()));
            return;
        }

        remainingDropSeconds = Drone.getTotalExtinguishingTime();
    }

    private void completeMission() {
        if (activeMission == null) {
            return;
        }

        Zone zone = zoneMap.get(activeMission.getZoneId());
        if (zone != null) {
            zone.setZoneState(Zone.ZoneState.EXTINGUISHED);
        }

        activeMission.getFireRequest().setResolved(true);
        activeMission.getFireRequest().setInProgress(false);
        int missionId = activeMission.getMissionId();
        int zoneId = activeMission.getZoneId();
        activeMission = null;

        transitionTo(DroneState.IDLE, "MISSION_COMPLETE", missionId);
        sendStatus(DroneState.IDLE, missionId, "Mission complete, fire extinguished in Zone " + zoneId);
        log(String.format("[Drone-%d] Mission %d complete. Zone %d extinguished.",
                droneId, missionId, zoneId));
    }

    private void advanceReturnToBase() {
        int missionId = currentMissionId();
        boolean atBase = moveTowards(Drone.getHomeX(), Drone.getHomeY(), simulationSecondsPerTick());
        sendProgressStatus(DroneState.RETURNING, missionId, "Returning to base");

        if (!atBase) {
            return;
        }

        drone.refill();

        if (activeMission != null && activeMission.getFireRequest().getRequiredFoam() > 0) {
            transitionTo(DroneState.IDLE, "REFILLED_FOR_CONTINUATION", activeMission.getMissionId());
            sendStatus(DroneState.IDLE, activeMission.getMissionId(),
                    "Refilled at base, ready for next trip to Zone " + activeMission.getZoneId());
            log(String.format("[Drone-%d] Refilled at base. Resuming Mission %d to Zone %d.",
                    droneId, activeMission.getMissionId(), activeMission.getZoneId()));
            transitionTo(DroneState.EN_ROUTE, "RESUME_MISSION", activeMission.getMissionId());
            sendStatus(DroneState.EN_ROUTE, activeMission.getMissionId(),
                    "Resuming trip to Zone " + activeMission.getZoneId());
            return;
        }

        transitionTo(DroneState.IDLE, "AT_BASE_REFILLED", missionId);
        sendStatus(DroneState.IDLE, missionId, "Returned to base, refilled and ready");
        log(String.format("[Drone-%d] At base. Refilled and ready for the next dispatch.", droneId));
    }

    private boolean moveTowards(double targetX, double targetY, double simulationSeconds) {
        double distance = drone.distanceTo(targetX, targetY);
        if (distance <= 0.0001) {
            drone.setPosition(targetX, targetY);
            return true;
        }

        double stepDistance = Drone.getSpeed() * simulationSeconds;
        if (stepDistance >= distance) {
            double travelSeconds = distance / Drone.getSpeed();
            drone.useBattery(travelSeconds);
            drone.setPosition(targetX, targetY);
            return true;
        }

        double ratio = stepDistance / distance;
        double nextX = drone.positionX + ((targetX - drone.positionX) * ratio);
        double nextY = drone.positionY + ((targetY - drone.positionY) * ratio);
        drone.useBattery(simulationSeconds);
        drone.setPosition(nextX, nextY);
        return false;
    }

    private double simulationSecondsPerTick() {
        return (TICK_MS / 1000.0) * timeScale;
    }

    private boolean hasActiveWork() {
        return activeMission != null || currentState == DroneState.RETURNING;
    }

    private boolean isAtHome() {
        return Math.abs(drone.positionX - Drone.getHomeX()) < 0.0001
                && Math.abs(drone.positionY - Drone.getHomeY()) < 0.0001;
    }

    private int currentMissionId() {
        return activeMission == null ? -1 : activeMission.getMissionId();
    }

    private void transitionTo(DroneState newState, String event, int missionId) {
        DroneState oldState = currentState;
        currentState = newState;
        log(String.format("[Drone-%d] %s --(%s)--> %s [Mission %d]",
                droneId, oldState, event, newState, missionId));
    }

    private void sendStatus(DroneState state, int missionId, String message) {
        statusSink.accept(new DroneStatusUpdate(
                droneId,
                state,
                missionId,
                drone.getRemainingAgent(),
                drone.getRemainingBattery(),
                drone.positionX,
                drone.positionY,
                message));
    }

    private void sendProgressStatus(DroneState state, int missionId, String message) {
        progressTickCounter++;
        int interval = Math.max(1, UdpConfig.PROGRESS_STATUS_EVERY_N_TICKS);
        if (progressTickCounter % interval != 0) {
            return;
        }
        sendStatus(state, missionId, message);
    }

    private void log(String message) {
        logSink.accept(message);
    }
}
