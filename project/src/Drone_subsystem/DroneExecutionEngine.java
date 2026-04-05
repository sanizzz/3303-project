package Drone_subsystem;

import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.FaultType;
import types.LogUtil;
import types.Mission;
import types.UdpConfig;

import java.util.LinkedList;
import java.util.Map;
import java.util.function.Consumer;

/**
 * This class is the main drone loop.
 * It runs the drone state changes and now also handles the Iteration 4 faults.
 */
public class DroneExecutionEngine implements Runnable {

    private static final long COMMAND_WAIT_MS = 100L;
    private static final long TICK_MS = 100L;
    private static final double SOFT_RESET_SECONDS = 6.0;
    private static final double PACKET_RETRY_SECONDS = 2.0;

    private final int droneId;
    private final Map<Integer, Zone> zoneMap;
    private final Drone drone;
    private final int timeScale;
    private final Consumer<DroneStatusUpdate> statusSink;
    private final Consumer<String> logSink;

    private final Object commandLock = new Object();
    private final Object stateWaitLock = new Object();
    private final Object metricsLock = new Object();
    private final LinkedList<DispatchCommand> commandQueue = new LinkedList<>();

    private DroneState currentState = DroneState.IDLE;
    private Mission activeMission;
    private double remainingDropSeconds;
    private int progressTickCounter;

    private FaultType activeFaultType = FaultType.NONE;
    private long faultDeadlineMs = -1L;
    private boolean faultTriggered;
    private long resetCompleteAtMs = -1L;
    private long stateEnteredAtMs = System.currentTimeMillis();
    private long totalIdleMs;
    private long totalFlightMs;

    /**
     * This sets up one drone engine with the map, time scale, and callback methods.
     */
    public DroneExecutionEngine(int droneId, Map<Integer, Zone> zoneMap, int timeScale,
            Consumer<DroneStatusUpdate> statusSink, Consumer<String> logSink) {
        this.droneId = Math.max(1, droneId);
        this.zoneMap = zoneMap;
        this.timeScale = Math.max(1, timeScale);
        this.statusSink = statusSink;
        this.logSink = logSink;
        this.drone = new Drone();
    }

    /**
     * This adds a new command into the drone's queue and wakes the drone thread up.
     */
    public void submitCommand(DispatchCommand command) {
        if (command == null) {
            return;
        }
        synchronized (commandLock) {
            commandQueue.add(command);
            commandLock.notifyAll();
        }
    }

    /**
     * Returns the current drone state.
     */
    public DroneState getCurrentState() {
        return currentState;
    }

    /**
     * This is the main drone loop.
     * It keeps checking for commands and moving the drone through its states.
     */
    @Override
    public void run() {
        log(String.format("[Drone-%d] Iteration 4 drone engine started. State=%s", droneId, currentState));

        try {
            while (!Thread.currentThread().isInterrupted()) {
                DispatchCommand command = waitForCommandIfIdle();
                if (command != null) {
                    handleCommand(command);
                    drainExtraCommands();
                }

                if (!hasActiveWork()) {
                    continue;
                }

                waitForNextSimulationStep();
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                if (hasActiveWork()) {
                    advanceOneTick();
                }
            }
        } finally {
            accumulateElapsedForCurrentState();
        }

        log(String.format("[Drone-%d] Drone engine shutting down.", droneId));
    }

    /**
     * If the drone is idle, it waits here for the next command.
     */
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

    /**
     * This processes any extra commands that were queued up, like reroutes.
     */
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

    /**
     * This handles a command sent by the scheduler.
     * It can start a mission, reroute the drone, or send it back to base.
     */
    private void handleCommand(DispatchCommand command) {
        if (currentState == DroneState.OFFLINE) {
            sendStatus(DroneState.OFFLINE, -1,
                    "Hard fault already latched. Drone remains offline until simulation end.",
                    FaultType.NOZZLE_JAMMED);
            return;
        }

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
            clearFaultWindow();
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

        // I copy the fault into the drone when it gets dispatched so the same mission
        // does not keep failing again and again after being reassigned.
        armFaultWindow(command);
        activeMission = mission;
        remainingDropSeconds = computeDropDurationForCurrentMission();

        transitionTo(DroneState.EN_ROUTE, "DISPATCH", mission.getMissionId());
        progressTickCounter = 0;
        sendStatus(DroneState.EN_ROUTE, mission.getMissionId(),
                "Dispatch accepted, en route to Zone " + mission.getZoneId());
    }

    /**
     * This moves the drone forward by one step in the simulation.
     */
    private void advanceOneTick() {
        if (currentState == DroneState.RESETTING) {
            advanceResetRecovery();
            return;
        }

        if (currentState == DroneState.EN_ROUTE && shouldTriggerStuckFault()) {
            triggerStuckMidFlightFault();
            return;
        }

        if (currentState == DroneState.EN_ROUTE) {
            advanceTravelToMission();
            return;
        }
        if (currentState == DroneState.DROPPING_AGENT) {
            if (shouldTriggerNozzleJammedFault()) {
                triggerNozzleJammedFault();
                return;
            }
            advanceDrop();
            return;
        }
        if (currentState == DroneState.RETURNING) {
            advanceReturnToBase();
        }
    }

    /**
     * This handles the short reset time after the stuck mid-flight soft fault.
     */
    private void advanceResetRecovery() {
        if (activeMission == null) {
            clearFaultWindow();
            transitionTo(DroneState.IDLE, "RESET_WITHOUT_MISSION", -1);
            return;
        }

        if (System.currentTimeMillis() < resetCompleteAtMs) {
            return;
        }

        resetCompleteAtMs = -1L;
        clearFaultWindow();
        transitionTo(DroneState.EN_ROUTE, "RESET_COMPLETE", activeMission.getMissionId());
        sendStatus(DroneState.EN_ROUTE, activeMission.getMissionId(),
                "Reset complete, resuming trip to Zone " + activeMission.getZoneId());
        log(String.format("[Drone-%d] Soft fault reset complete. Resuming Mission %d.",
                droneId, activeMission.getMissionId()));
    }

    /**
     * This moves the drone toward the fire zone and sends progress updates.
     */
    private void advanceTravelToMission() {
        if (activeMission == null) {
            clearFaultWindow();
            transitionTo(DroneState.IDLE, "NO_ACTIVE_MISSION", -1);
            return;
        }

        Zone targetZone = zoneMap.get(activeMission.getZoneId());
        if (targetZone == null) {
            sendStatus(DroneState.IDLE, activeMission.getMissionId(), "Zone not found");
            activeMission = null;
            clearFaultWindow();
            transitionTo(DroneState.IDLE, "MISSION_ABORTED", -1);
            return;
        }

        boolean arrived = moveTowards(targetZone.getMiddleX(), targetZone.getMiddleY(), simulationSecondsPerTick());
        sendProgressStatus(DroneState.EN_ROUTE, activeMission.getMissionId(),
                "En route to Zone " + activeMission.getZoneId());

        if (arrived) {
            remainingDropSeconds = computeDropDurationForCurrentMission();
            transitionTo(DroneState.DROPPING_AGENT, "ARRIVED", activeMission.getMissionId());
            sendStatus(DroneState.DROPPING_AGENT, activeMission.getMissionId(),
                    "Arrived at Zone " + activeMission.getZoneId() + ", beginning agent drop");
            log(String.format("[Drone-%d] Arrived at Zone %d. Starting suppression pass.",
                    droneId, activeMission.getZoneId()));
        }
    }

    /**
     * This handles the water or foam drop once the drone reaches the zone.
     */
    private void advanceDrop() {
        if (activeMission == null) {
            clearFaultWindow();
            transitionTo(DroneState.IDLE, "NO_ACTIVE_MISSION", -1);
            return;
        }

        remainingDropSeconds -= simulationSecondsPerTick();
        if (remainingDropSeconds > 0) {
            return;
        }

        double plannedDrop = activeMission.getRemainingAgentLiters();
        double dropped = drone.useAgent(plannedDrop);
        activeMission.consumeAssignedAgent(dropped);
        sendStatus(DroneState.DROPPING_AGENT, activeMission.getMissionId(),
                String.format("Drop complete, segment load remaining=%.1fL",
                        activeMission.getRemainingAgentLiters()));

        if (activeMission.isSegmentComplete()) {
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

        remainingDropSeconds = computeDropDurationForCurrentMission();
    }

    /**
     * This finishes the mission and tells the scheduler the fire is done.
     */
    private void completeMission() {
        if (activeMission == null) {
            return;
        }

        int missionId = activeMission.getMissionId();
        int zoneId = activeMission.getZoneId();
        activeMission = null;
        clearFaultWindow();

        transitionTo(DroneState.IDLE, "MISSION_COMPLETE", missionId);
        sendStatus(DroneState.IDLE, missionId, "Mission complete, fire extinguished in Zone " + zoneId);
        log(String.format("[Drone-%d] Mission %d complete. Zone %d extinguished.",
                droneId, missionId, zoneId));
    }

    /**
     * This sends the drone back to base and refills it if needed.
     */
    private void advanceReturnToBase() {
        int missionId = currentMissionId();
        boolean atBase = moveTowards(Drone.getHomeX(), Drone.getHomeY(), simulationSecondsPerTick());
        sendProgressStatus(DroneState.RETURNING, missionId, "Returning to base");

        if (!atBase) {
            return;
        }

        drone.refill();

        if (activeMission != null && !activeMission.isSegmentComplete()) {
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

        clearFaultWindow();
        transitionTo(DroneState.IDLE, "AT_BASE_REFILLED", missionId);
        sendStatus(DroneState.IDLE, missionId, "Returned to base, refilled and ready");
        log(String.format("[Drone-%d] At base. Refilled and ready for the next dispatch.", droneId));
    }

    /**
     * This sets up the fault timer for the current mission.
     */
    private void armFaultWindow(DispatchCommand command) {
        activeFaultType = command.getFaultType() == null ? FaultType.NONE : command.getFaultType();
        faultTriggered = activeFaultType == FaultType.NONE;
        resetCompleteAtMs = -1L;
        if (activeFaultType == FaultType.NONE) {
            faultDeadlineMs = -1L;
            return;
        }

        faultDeadlineMs = System.currentTimeMillis() + scaledMillisForSimulationSeconds(command.getFaultTriggerSeconds());
        log(String.format("[Drone-%d] Fault armed for Mission %d: %s at +%ds simulated.",
                droneId,
                command.getMission() == null ? -1 : command.getMission().getMissionId(),
                activeFaultType,
                command.getFaultTriggerSeconds()));
    }

    /**
     * This clears the current fault information after the mission changes state.
     */
    private void clearFaultWindow() {
        activeFaultType = FaultType.NONE;
        faultDeadlineMs = -1L;
        faultTriggered = false;
        resetCompleteAtMs = -1L;
    }

    /**
     * This decides how long the drone thread should wait before the next step.
     */
    private long computeNextWaitMs() {
        if (currentState == DroneState.RESETTING && resetCompleteAtMs > 0) {
            return Math.max(1L, resetCompleteAtMs - System.currentTimeMillis());
        }

        if (currentState == DroneState.EN_ROUTE
                && activeFaultType == FaultType.STUCK_MID_FLIGHT
                && !faultTriggered
                && faultDeadlineMs > 0) {
            long remainingFaultMs = faultDeadlineMs - System.currentTimeMillis();
            if (remainingFaultMs <= 0) {
                return 0L;
            }
            return Math.min(TICK_MS, remainingFaultMs);
        }

        return TICK_MS;
    }

    /**
     * This is the timed wait used in the drone loop.
     * I used wait(timeout) here so the fault timing works without freezing the scheduler.
     */
    private void waitForNextSimulationStep() {
        long waitMs = computeNextWaitMs();
        if (waitMs <= 0) {
            return;
        }
        timedStateWait(waitMs);
    }

    /**
     * Checks if the stuck mid-flight fault should happen now.
     */
    private boolean shouldTriggerStuckFault() {
        return activeMission != null
                && currentState == DroneState.EN_ROUTE
                && activeFaultType == FaultType.STUCK_MID_FLIGHT
                && !faultTriggered
                && faultDeadlineReached();
    }

    /**
     * This runs the stuck mid-flight soft fault.
     * The drone resets and then keeps working on the same mission.
     */
    private void triggerStuckMidFlightFault() {
        if (activeMission == null) {
            return;
        }

        faultTriggered = true;
        resetCompleteAtMs = System.currentTimeMillis() + scaledMillisForSimulationSeconds(SOFT_RESET_SECONDS);
        transitionTo(DroneState.RESETTING, "STUCK_MID_FLIGHT", activeMission.getMissionId());
        sendStatus(DroneState.RESETTING, activeMission.getMissionId(),
                "Stuck mid-flight detected. Drone resetting before retry.", FaultType.STUCK_MID_FLIGHT);
        log(String.format("[Drone-%d] Soft fault triggered during Mission %d. Resetting in place.",
                droneId, activeMission.getMissionId()));
    }

    /**
     * Checks if the nozzle jam hard fault should happen now.
     */
    private boolean shouldTriggerNozzleJammedFault() {
        return activeMission != null
                && currentState == DroneState.DROPPING_AGENT
                && activeFaultType == FaultType.NOZZLE_JAMMED
                && !faultTriggered
                && faultDeadlineReached();
    }

    /**
     * This runs the nozzle jam hard fault.
     * The drone goes offline and the scheduler can give the mission to another drone.
     */
    private void triggerNozzleJammedFault() {
        if (activeMission == null) {
            return;
        }

        int missionId = activeMission.getMissionId();
        int zoneId = activeMission.getZoneId();
        faultTriggered = true;

        transitionTo(DroneState.OFFLINE, "NOZZLE_JAMMED", missionId);
        sendStatus(DroneState.OFFLINE, missionId,
                "Nozzle jam detected at Zone " + zoneId + ". Drone offline for remainder of run.",
                FaultType.NOZZLE_JAMMED);
        log(String.format("[Drone-%d] Hard fault triggered on Mission %d. Drone is now offline.",
                droneId, missionId));

        activeMission = null;
        remainingDropSeconds = 0;
        clearFaultWindow();
    }

    /**
     * This moves the drone a small amount toward a target position.
     */
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

    /**
     * Converts one real tick into simulated time.
     */
    private double simulationSecondsPerTick() {
        return (TICK_MS / 1000.0) * timeScale;
    }

    /**
     * This computes how long the current suppression pass should take for the remaining
     * mission segment instead of always assuming a full-tank discharge.
     */
    private double computeDropDurationForCurrentMission() {
        if (activeMission == null) {
            return 0.0;
        }
        return Drone.estimateDropTimeSeconds(activeMission.getRemainingAgentLiters());
    }

    /**
     * Converts simulated seconds into the real wait time.
     */
    private long scaledMillisForSimulationSeconds(double simulationSeconds) {
        return Math.max(1L, Math.round((simulationSeconds * 1000.0) / timeScale));
    }

    /**
     * Checks if the drone still has work to do.
     */
    private boolean hasActiveWork() {
        return activeMission != null
                || currentState == DroneState.RETURNING
                || currentState == DroneState.RESETTING;
    }

    /**
     * Checks if the drone is already back at base.
     */
    private boolean isAtHome() {
        return Math.abs(drone.positionX - Drone.getHomeX()) < 0.0001
                && Math.abs(drone.positionY - Drone.getHomeY()) < 0.0001;
    }

    /**
     * Returns the current mission id, or -1 if there is no mission.
     */
    private int currentMissionId() {
        return activeMission == null ? -1 : activeMission.getMissionId();
    }

    /**
     * Checks if the fault timer has reached its limit.
     */
    private boolean faultDeadlineReached() {
        return faultDeadlineMs >= 0 && System.currentTimeMillis() >= faultDeadlineMs;
    }

    /**
     * This updates the drone state and writes it to the log.
     */
    private void transitionTo(DroneState newState, String event, int missionId) {
        accumulateElapsedForCurrentState();
        DroneState oldState = currentState;
        currentState = newState;
        log(String.format("[Drone-%d] %s --(%s)--> %s [Mission %d]",
                droneId, oldState, event, newState, missionId));
    }

    /**
     * This charges elapsed wall-clock time to the state being left so MetricsRunner can
     * measure how long each drone thread spent idle versus actually flying.
     */
    private void accumulateElapsedForCurrentState() {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0L, now - stateEnteredAtMs);
        // This monitor keeps the timing counters consistent while another thread snapshots
        // them for the metrics report.
        synchronized (metricsLock) {
            if (currentState == DroneState.IDLE) {
                totalIdleMs += elapsed;
            } else if (currentState == DroneState.EN_ROUTE || currentState == DroneState.RETURNING) {
                totalFlightMs += elapsed;
            }
            stateEnteredAtMs = now;
        }
    }

    /**
     * This snapshots accumulated idle time for one drone so the metrics runner can average
     * waiting behaviour across the full fleet.
     */
    public double getIdleSeconds() {
        // This monitor is used so the metrics reader sees one coherent idle-time snapshot.
        synchronized (metricsLock) {
            long liveIdleMs = totalIdleMs;
            if (currentState == DroneState.IDLE) {
                liveIdleMs += Math.max(0L, System.currentTimeMillis() - stateEnteredAtMs);
            }
            return liveIdleMs / 1000.0;
        }
    }

    /**
     * This snapshots accumulated flight time for one drone so MetricsRunner can report how
     * much real execution time the fleet spent airborne.
     */
    public double getFlightSeconds() {
        // This monitor is used so the metrics reader sees one coherent flight-time snapshot.
        synchronized (metricsLock) {
            long liveFlightMs = totalFlightMs;
            if (currentState == DroneState.EN_ROUTE || currentState == DroneState.RETURNING) {
                liveFlightMs += Math.max(0L, System.currentTimeMillis() - stateEnteredAtMs);
            }
            return liveFlightMs / 1000.0;
        }
    }

    /**
     * Sends a normal status update.
     */
    private void sendStatus(DroneState state, int missionId, String message) {
        sendStatus(state, missionId, message, FaultType.NONE);
    }

    /**
     * Sends a status update, and if needed, it also triggers the packet loss fault.
     */
    private void sendStatus(DroneState state, int missionId, String message, FaultType faultType) {
        if (faultType == FaultType.NONE && shouldInjectPacketLoss()) {
            injectPacketLossAndRetry(state, missionId, message);
            return;
        }
        emitStatus(state, missionId, message, faultType);
    }

    /**
     * This creates the status object and sends it to the scheduler.
     */
    private void emitStatus(DroneState state, int missionId, String message, FaultType faultType) {
        statusSink.accept(new DroneStatusUpdate(
                droneId,
                state,
                missionId,
                drone.getRemainingAgent(),
                drone.getRemainingBattery(),
                drone.positionX,
                drone.positionY,
                message,
                faultType));
    }

    /**
     * Checks if the packet loss fault should happen on the next message.
     */
    private boolean shouldInjectPacketLoss() {
        return activeMission != null
                && activeFaultType == FaultType.PACKET_LOSS
                && !faultTriggered
                && faultDeadlineReached();
    }

    /**
     * This simulates packet loss by sending a fault update first and then retrying.
     */
    private void injectPacketLossAndRetry(DroneState state, int missionId, String message) {
        faultTriggered = true;
        emitStatus(state, missionId,
                "Packet loss/corruption detected. Retrying previous message after timeout.",
                FaultType.PACKET_LOSS);
        timedStateWait(scaledMillisForSimulationSeconds(PACKET_RETRY_SECONDS));
        emitStatus(state, missionId, message + " [retried after packet loss]", FaultType.NONE);
        log(String.format("[Drone-%d] Packet loss injected for Mission %d, retry completed.",
                droneId, missionId));
        clearFaultWindow();
    }

    /**
     * This only sends travel updates every few ticks so the logs do not get too noisy.
     */
    private void sendProgressStatus(DroneState state, int missionId, String message) {
        progressTickCounter++;
        int interval = Math.max(1, UdpConfig.PROGRESS_STATUS_EVERY_N_TICKS);
        if (progressTickCounter % interval != 0) {
            return;
        }
        sendStatus(state, missionId, message);
    }

    /**
     * This is the helper used for timed waiting inside the drone thread.
     */
    private void timedStateWait(long waitMs) {
        synchronized (stateWaitLock) {
            try {
                stateWaitLock.wait(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * This adds a timestamp to drone log messages.
     */
    private void log(String message) {
        logSink.accept(LogUtil.stamp(message));
    }
}
