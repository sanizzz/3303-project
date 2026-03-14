package Scheduler;

import Drone_subsystem.Drone;
import Drone_subsystem.Zone;
import fire_incident_subsystem.FireRequest;
import gui.SimulationGUI;
import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.Mission;
import types.MissionStatus;
import types.SchedulerState;
import types.Severity;
import types.UdpConfig;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * Scheduler state machine for Iteration 3.
 * Supports multiple drones, workload-aware dispatching, rerouting, and UDP launcher mode.
 */
public class Scheduler implements Runnable {

    private static final double BATTERY_MARGIN_SECONDS = 10.0;
    private static final double ON_PATH_TOLERANCE = 75.0;

    private SchedulerState state = SchedulerState.IDLE;

    private final LinkedList<Mission> missionQueue = new LinkedList<>();
    private final Map<Integer, Mission> inProgressByMissionId = new HashMap<>();
    private final Map<Integer, Mission> activeMissionByDroneId = new HashMap<>();

    private final LinkedList<FireRequest> incomingFireRequests = new LinkedList<>();
    private final LinkedList<DispatchCommand> dispatchQueue = new LinkedList<>();
    private final LinkedList<DroneStatusUpdate> statusUpdateQueue = new LinkedList<>();
    private final LinkedList<FireRequest> completionQueue = new LinkedList<>();

    private final Map<Integer, DroneState> droneStateById = new HashMap<>();
    private final Map<Integer, Double> droneAgentById = new HashMap<>();
    private final Map<Integer, Double> droneBatteryById = new HashMap<>();
    private final Map<Integer, Double> dronePosXById = new HashMap<>();
    private final Map<Integer, Double> dronePosYById = new HashMap<>();
    private final Map<Integer, Integer> assignedCountByDroneId = new HashMap<>();
    private final Map<Integer, Boolean> droneStatusSeenById = new HashMap<>();
    private int configuredDroneCount = 1;

    private SimulationGUI gui;
    private Map<Integer, Zone> zoneMap;

    public Scheduler() {
        initDroneTracking(1);
    }

    public Scheduler(SimulationGUI gui, Map<Integer, Zone> zoneMap) {
        this(gui, zoneMap, 1);
    }

    public Scheduler(SimulationGUI gui, Map<Integer, Zone> zoneMap, int configuredDroneCount) {
        this.gui = gui;
        this.zoneMap = zoneMap;
        initDroneTracking(configuredDroneCount);
    }

    public synchronized void setGui(SimulationGUI gui) {
        this.gui = gui;
    }

    public synchronized void setZoneMap(Map<Integer, Zone> zoneMap) {
        this.zoneMap = zoneMap;
    }

    public synchronized void setConfiguredDroneCount(int count) {
        initDroneTracking(count);
    }

    private void initDroneTracking(int count) {
        int safeCount = Math.max(1, count);
        configuredDroneCount = safeCount;
        for (int i = 1; i <= safeCount; i++) {
            droneStateById.putIfAbsent(i, DroneState.IDLE);
            droneAgentById.putIfAbsent(i, Drone.getLoadCapacity());
            droneBatteryById.putIfAbsent(i, Drone.getFullBattery());
            dronePosXById.putIfAbsent(i, 0.0);
            dronePosYById.putIfAbsent(i, 0.0);
            assignedCountByDroneId.putIfAbsent(i, 0);
            droneStatusSeenById.putIfAbsent(i, false);
        }
    }

    @Override
    public void run() {
        log("[Scheduler] State machine started. State: " + state);
        updateGui();

        while (!Thread.currentThread().isInterrupted()) {
            synchronized (this) {
                while (!incomingFireRequests.isEmpty()) {
                    enqueueMission(incomingFireRequests.removeFirst());
                }

                while (!statusUpdateQueue.isEmpty()) {
                    handleDroneStatusUpdate(statusUpdateQueue.removeFirst());
                }

                dispatchNextIfPossible();

                try {
                    wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log("[Scheduler] Shutting down.");
    }

    private void enqueueMission(FireRequest req) {
        if (isZoneAlreadyActive(req.getZoneId())) {
            req.setResolved(false);
            req.setInProgress(false);
            completionQueue.add(req);
            notifyAll();
            log("[Scheduler] Ignored duplicate active fire in Zone " + req.getZoneId()
                    + " (one-fire-per-zone assumption).");
            return;
        }

        Mission mission = new Mission(req);
        missionQueue.add(mission);

        SchedulerState old = state;
        if (state == SchedulerState.IDLE) {
            state = SchedulerState.DISPATCHING;
            logTransition(old, state, "NEW_FIRE");
        }

        log(String.format("[Scheduler] Enqueued %s | Queue size: %d", mission, missionQueue.size()));

        attemptRerouteForNewMission(mission);
        updateGui();
    }

    private boolean attemptRerouteForNewMission(Mission newMission) {
        RerouteCandidate reroute = findBestRerouteCandidate(newMission);
        if (reroute == null) {
            return false;
        }

        Mission previousMission = activeMissionByDroneId.get(reroute.droneId);
        if (previousMission == null) {
            return false;
        }

        missionQueue.remove(newMission);
        inProgressByMissionId.remove(previousMission.getMissionId());
        previousMission.setStatus(MissionStatus.QUEUED);
        missionQueue.add(previousMission);

        newMission.setStatus(MissionStatus.IN_PROGRESS);
        inProgressByMissionId.put(newMission.getMissionId(), newMission);
        activeMissionByDroneId.put(reroute.droneId, newMission);
        dispatchQueue.add(DispatchCommand.dispatch(reroute.droneId, newMission));
        notifyAll();

        log(buildRerouteMessage(reroute.droneId, previousMission, newMission));
        return true;
    }

    private RerouteCandidate findBestRerouteCandidate(Mission newMission) {
        if (zoneMap == null) {
            return null;
        }

        Zone newZone = zoneMap.get(newMission.getZoneId());
        if (newZone == null) {
            return null;
        }

        RerouteCandidate best = null;
        for (int droneId = 1; droneId <= configuredDroneCount; droneId++) {
            if (droneStateById.getOrDefault(droneId, DroneState.IDLE) != DroneState.EN_ROUTE) {
                continue;
            }

            Mission currentMission = activeMissionByDroneId.get(droneId);
            if (currentMission == null) {
                continue;
            }

            Zone currentZone = zoneMap.get(currentMission.getZoneId());
            if (currentZone == null) {
                continue;
            }

            double fromX = dronePosXById.getOrDefault(droneId, 0.0);
            double fromY = dronePosYById.getOrDefault(droneId, 0.0);
            double currentDistance = distance(fromX, fromY, currentZone.getMiddleX(), currentZone.getMiddleY());
            double newDistance = distance(fromX, fromY, newZone.getMiddleX(), newZone.getMiddleY());
            int currentSeverityRank = severityRank(currentMission.getFireRequest().getSeverity());
            int newSeverityRank = severityRank(newMission.getFireRequest().getSeverity());

            boolean higherSeverityReroute = newSeverityRank > currentSeverityRank;
            boolean sameSeverityOnPathReroute = newSeverityRank == currentSeverityRank
                    && newDistance < currentDistance
                    && isPointOnSegment(
                            fromX, fromY,
                            currentZone.getMiddleX(), currentZone.getMiddleY(),
                            newZone.getMiddleX(), newZone.getMiddleY());

            if (!higherSeverityReroute && !sameSeverityOnPathReroute) {
                continue;
            }

            RerouteCandidate candidate = new RerouteCandidate(droneId, newDistance, newSeverityRank - currentSeverityRank);
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate;
            }
        }

        return best;
    }

    private boolean isZoneAlreadyActive(int zoneId) {
        for (Mission queued : missionQueue) {
            if (queued.getZoneId() == zoneId) {
                return true;
            }
        }
        for (Mission inProgress : inProgressByMissionId.values()) {
            if (inProgress.getZoneId() == zoneId) {
                return true;
            }
        }
        return false;
    }

    private void dispatchNextIfPossible() {
        while (true) {
            DispatchCandidate candidate = findBestDispatchCandidate();
            if (candidate == null) {
                return;
            }
            assignMission(candidate);
        }
    }

    private DispatchCandidate findBestDispatchCandidate() {
        DispatchCandidate best = null;
        for (Mission mission : missionQueue) {
            for (int droneId = 1; droneId <= configuredDroneCount; droneId++) {
                if (!isDroneIdle(droneId)) {
                    continue;
                }

                refreshBaseResourcesIfNeeded(droneId);
                if (!canDroneTakeMissionNow(mission, droneId)) {
                    continue;
                }

                DispatchCandidate candidate = new DispatchCandidate(
                        mission,
                        droneId,
                        severityRank(mission.getFireRequest().getSeverity()),
                        estimateTravelTimeToMission(mission, droneId),
                        mission.getFireRequest().getTime(),
                        assignedCountByDroneId.getOrDefault(droneId, 0));

                if (best == null || candidate.isBetterThan(best)) {
                    best = candidate;
                }
            }
        }

        return best;
    }

    private void assignMission(DispatchCandidate candidate) {
        missionQueue.remove(candidate.mission);
        candidate.mission.setStatus(MissionStatus.IN_PROGRESS);
        inProgressByMissionId.put(candidate.mission.getMissionId(), candidate.mission);
        activeMissionByDroneId.put(candidate.droneId, candidate.mission);
        int newAssignedCount = assignedCountByDroneId.getOrDefault(candidate.droneId, 0) + 1;
        assignedCountByDroneId.put(candidate.droneId, newAssignedCount);

        SchedulerState old = state;
        state = SchedulerState.DISPATCHING;
        logTransition(old, state,
                "DISPATCH_MISSION(Zone " + candidate.mission.getZoneId() + ", Drone " + candidate.droneId + ")");
        log(String.format(
                "[Scheduler] Dispatch decision: Drone %d -> Zone %d | Severity=%s | ETA=%.1fs | AssignedLoads=%d",
                candidate.droneId,
                candidate.mission.getZoneId(),
                candidate.mission.getFireRequest().getSeverity(),
                candidate.estimatedTravelSeconds,
                newAssignedCount));
        if (!droneStatusSeenById.getOrDefault(candidate.droneId, false)) {
            log(String.format(
                    "[Scheduler] Warning: Drone %d has not reported any UDP status yet. Verify that its process is running and listening on the expected command port.",
                    candidate.droneId));
        }

        dispatchQueue.add(DispatchCommand.dispatch(candidate.droneId, candidate.mission));
        droneStateById.put(candidate.droneId, DroneState.EN_ROUTE);
        notifyAll();

        old = state;
        state = SchedulerState.WAITING_FOR_DRONE;
        logTransition(old, state, "DISPATCH_SENT");
        updateGui();
    }

    private void handleDroneStatusUpdate(DroneStatusUpdate update) {
        int droneId = update.getDroneId();
        initDroneTracking(Math.max(configuredDroneCount, droneId));

        droneStateById.put(droneId, update.getDroneState());
        droneAgentById.put(droneId, update.getRemainingAgent());
        droneBatteryById.put(droneId, update.getRemainingBattery());
        droneStatusSeenById.put(droneId, true);
        if (update.hasPosition()) {
            dronePosXById.put(droneId, update.getPositionX());
            dronePosYById.put(droneId, update.getPositionY());
        }

        if (shouldLogStatusUpdate(update)) {
            log("[Scheduler] Received DroneStatusUpdate: " + update);
        }

        Mission mission = inProgressByMissionId.get(update.getMissionId());
        if (update.getDroneState() == DroneState.DROPPING_AGENT && mission != null && isArrivalUpdate(update)) {
            Zone zone = zoneMap != null ? zoneMap.get(mission.getZoneId()) : null;
            if (zone != null) {
                dronePosXById.put(droneId, (double) zone.getMiddleX());
                dronePosYById.put(droneId, (double) zone.getMiddleY());
                zone.setZoneState(Zone.ZoneState.ON_FIRE);
            }
            log("[Scheduler] Drone " + droneId + " arrived at Zone " + mission.getZoneId() + ".");
        } else if (update.getDroneState() == DroneState.IDLE) {
            if (mission != null) {
                if (isMissionCompletionUpdate(update)) {
                    completeMission(droneId, mission);
                } else {
                    droneStateById.put(droneId, DroneState.EN_ROUTE);
                    log("[Scheduler] Drone " + droneId + " refilled and continuing Mission "
                            + mission.getMissionId() + ".");
                    updateGui();
                    return;
                }
            } else {
                resetDroneAtBase(droneId);
            }
            handleDroneAvailability(droneId);
        }

        updateGui();
    }

    private boolean isMissionCompletionUpdate(DroneStatusUpdate update) {
        String msg = update.getMessage();
        if (msg == null) {
            return false;
        }
        String normalized = msg.trim().toLowerCase();
        return "complete".equals(normalized)
                || normalized.startsWith("mission complete")
                || normalized.contains("fire extinguished");
    }

    private boolean isArrivalUpdate(DroneStatusUpdate update) {
        String msg = update.getMessage();
        if (msg == null) {
            return false;
        }
        return msg.trim().toLowerCase().startsWith("arrived");
    }

    private boolean shouldLogStatusUpdate(DroneStatusUpdate update) {
        if (UdpConfig.LOG_EVERY_STATUS_UPDATE) {
            return true;
        }

        DroneState state = update.getDroneState();
        if (state == DroneState.EN_ROUTE || state == DroneState.RETURNING) {
            return false;
        }

        String message = update.getMessage();
        if (message == null) {
            return true;
        }

        String normalized = message.trim().toLowerCase();
        return !normalized.startsWith("en route")
                && !normalized.equals("returning to base");
    }

    private void completeMission(int droneId, Mission mission) {
        mission.setStatus(MissionStatus.COMPLETED);
        mission.getFireRequest().setResolved(true);
        mission.getFireRequest().setInProgress(false);
        inProgressByMissionId.remove(mission.getMissionId());
        activeMissionByDroneId.remove(droneId);

        if (zoneMap != null) {
            Zone zone = zoneMap.get(mission.getZoneId());
            if (zone != null) {
                zone.setZoneState(Zone.ZoneState.EXTINGUISHED);
                dronePosXById.put(droneId, (double) zone.getMiddleX());
                dronePosYById.put(droneId, (double) zone.getMiddleY());
            }
        }

        completionQueue.add(mission.getFireRequest());
        notifyAll();

        log(String.format("[Scheduler] Mission %d completed by Drone %d for Zone %d. Agent: %.1fL, Battery: %.1fs",
                mission.getMissionId(),
                droneId,
                mission.getZoneId(),
                droneAgentById.getOrDefault(droneId, Drone.getLoadCapacity()),
                droneBatteryById.getOrDefault(droneId, Drone.getFullBattery())));
    }

    private void handleDroneAvailability(int droneId) {
        if (missionQueue.isEmpty()) {
            if (!isDroneAtBase(droneId)) {
                SchedulerState old = state;
                state = inProgressByMissionId.isEmpty() ? SchedulerState.IDLE : SchedulerState.WAITING_FOR_DRONE;
                logTransition(old, state, inProgressByMissionId.isEmpty() ? "QUEUE_EMPTY" : "QUEUE_EMPTY_WAITING_FOR_ACTIVE");
                sendReturnToBase(droneId);
            } else {
                SchedulerState old = state;
                state = inProgressByMissionId.isEmpty() ? SchedulerState.IDLE : SchedulerState.WAITING_FOR_DRONE;
                if (old != state) {
                    logTransition(old, state,
                            inProgressByMissionId.isEmpty() ? "QUEUE_EMPTY_AT_BASE" : "QUEUE_EMPTY_AT_BASE_WAITING_FOR_ACTIVE");
                }
            }
            return;
        }

        if (!canDroneServeAnyPendingMission(droneId)) {
            log(String.format("[Scheduler] Drone %d cannot serve pending work with current resources. Returning to base.",
                    droneId));
            sendReturnToBase(droneId);
            return;
        }

        dispatchNextIfPossible();
    }

    private void resetDroneAtBase(int droneId) {
        droneAgentById.put(droneId, Drone.getLoadCapacity());
        droneBatteryById.put(droneId, Drone.getFullBattery());
        dronePosXById.put(droneId, 0.0);
        dronePosYById.put(droneId, 0.0);
        droneStateById.put(droneId, DroneState.IDLE);
        log("[Scheduler] Drone " + droneId + " at base. Instant refill/recharge complete.");
    }

    private void refreshBaseResourcesIfNeeded(int droneId) {
        if (isDroneAtBase(droneId)) {
            droneAgentById.put(droneId, Drone.getLoadCapacity());
            droneBatteryById.put(droneId, Drone.getFullBattery());
        }
    }

    private boolean canDroneServeAnyPendingMission(int droneId) {
        refreshBaseResourcesIfNeeded(droneId);
        if (droneAgentById.getOrDefault(droneId, Drone.getLoadCapacity()) <= 0 && !isDroneAtBase(droneId)) {
            return false;
        }
        for (Mission mission : missionQueue) {
            if (canDroneTakeMissionNow(mission, droneId)) {
                return true;
            }
        }
        return false;
    }

    private boolean canDroneTakeMissionNow(Mission mission, int droneId) {
        if (mission == null) {
            return false;
        }

        if (droneAgentById.getOrDefault(droneId, Drone.getLoadCapacity()) <= 0 && !isDroneAtBase(droneId)) {
            return false;
        }

        return hasBatteryForMission(mission, droneId);
    }

    private boolean isDroneIdle(int droneId) {
        return droneStateById.getOrDefault(droneId, DroneState.IDLE) == DroneState.IDLE
                && !activeMissionByDroneId.containsKey(droneId);
    }

    private boolean isDroneAtBase(int droneId) {
        return Math.abs(dronePosXById.getOrDefault(droneId, 0.0)) < 0.0001
                && Math.abs(dronePosYById.getOrDefault(droneId, 0.0)) < 0.0001;
    }

    private void sendReturnToBase(Integer targetDroneId) {
        if (targetDroneId != null) {
            DroneState current = droneStateById.getOrDefault(targetDroneId, DroneState.IDLE);
            if (current == DroneState.RETURNING) {
                return;
            }
        }

        for (DispatchCommand queued : dispatchQueue) {
            if (queued.isReturnToBase() && sameTargetDrone(queued.getTargetDroneId(), targetDroneId)) {
                return;
            }
        }

        dispatchQueue.add(DispatchCommand.returnToBase(targetDroneId));
        if (targetDroneId != null) {
            droneStateById.put(targetDroneId, DroneState.RETURNING);
        }
        notifyAll();
    }

    private boolean sameTargetDrone(Integer a, Integer b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }

    private boolean hasBatteryForMission(Mission mission, int droneId) {
        if (mission == null || zoneMap == null) {
            return true;
        }

        Zone target = zoneMap.get(mission.getZoneId());
        if (target == null) {
            return true;
        }

        double fromX = dronePosXById.getOrDefault(droneId, 0.0);
        double fromY = dronePosYById.getOrDefault(droneId, 0.0);
        double targetX = target.getMiddleX();
        double targetY = target.getMiddleY();

        double distToTarget = distance(fromX, fromY, targetX, targetY);
        double distToHome = distance(targetX, targetY, 0, 0);
        double requiredTravelSeconds = (distToTarget + distToHome) / Drone.getSpeed();
        double availableBattery = droneBatteryById.getOrDefault(droneId, Drone.getFullBattery());

        return availableBattery >= (requiredTravelSeconds + BATTERY_MARGIN_SECONDS);
    }

    private double estimateTravelTimeToMission(Mission mission, int droneId) {
        if (mission == null || zoneMap == null) {
            return 0.0;
        }

        Zone target = zoneMap.get(mission.getZoneId());
        if (target == null) {
            return 0.0;
        }

        double fromX = dronePosXById.getOrDefault(droneId, 0.0);
        double fromY = dronePosYById.getOrDefault(droneId, 0.0);
        return distance(fromX, fromY, target.getMiddleX(), target.getMiddleY()) / Drone.getSpeed();
    }

    private int severityRank(Severity severity) {
        if (severity == null) {
            return 0;
        }
        switch (severity) {
            case HIGH:
                return 3;
            case MODERATE:
                return 2;
            case LOW:
            default:
                return 1;
        }
    }

    private boolean isPointOnSegment(double startX, double startY, double endX, double endY, double pointX,
            double pointY) {
        double segDx = endX - startX;
        double segDy = endY - startY;
        double segLengthSquared = (segDx * segDx) + (segDy * segDy);
        if (segLengthSquared <= 0.0001) {
            return false;
        }

        double projection = (((pointX - startX) * segDx) + ((pointY - startY) * segDy)) / segLengthSquared;
        if (projection < 0.0 || projection > 1.0) {
            return false;
        }

        double projectedX = startX + (projection * segDx);
        double projectedY = startY + (projection * segDy);
        return distance(projectedX, projectedY, pointX, pointY) <= ON_PATH_TOLERANCE;
    }

    private double distance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private String buildRerouteMessage(int droneId, Mission previousMission, Mission newMission) {
        Severity previousSeverity = previousMission.getFireRequest().getSeverity();
        Severity newSeverity = newMission.getFireRequest().getSeverity();
        String reason = severityRank(newSeverity) > severityRank(previousSeverity)
                ? "higher priority fire"
                : "same severity fire earlier on current path";

        return String.format(
                "[Scheduler] Rerouting Drone %d from Zone %d (%s) to Zone %d (%s) because of %s.",
                droneId,
                previousMission.getZoneId(),
                previousSeverity,
                newMission.getZoneId(),
                newSeverity,
                reason);
    }

    public synchronized void putRequest(FireRequest req) {
        incomingFireRequests.add(req);
        log("[Scheduler] Received fire request for Zone " + req.getZoneId());
        notifyAll();
    }

    public synchronized DispatchCommand getDispatchCommandForDrone(int droneId) {
        while (true) {
            for (int i = 0; i < dispatchQueue.size(); i++) {
                DispatchCommand cmd = dispatchQueue.get(i);
                Integer target = cmd.getTargetDroneId();
                if (target == null || target == droneId) {
                    dispatchQueue.remove(i);
                    return cmd;
                }
            }

            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    public synchronized DispatchCommand getNextDispatchCommand() {
        while (dispatchQueue.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return dispatchQueue.removeFirst();
    }

    public synchronized Mission getDispatchedMission() {
        DispatchCommand cmd = getDispatchCommandForDrone(1);
        if (cmd == null || cmd.isReturnToBase()) {
            return null;
        }
        return cmd.getMission();
    }

    public synchronized void sendDroneStatusUpdate(DroneStatusUpdate update) {
        statusUpdateQueue.add(update);
        notifyAll();
    }

    public synchronized FireRequest getCompletion() {
        while (completionQueue.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return completionQueue.removeFirst();
    }

    public synchronized FireRequest getRequest() {
        return null;
    }

    public synchronized void acknowledgeCompletion(FireRequest req) {
    }

    public synchronized int getActiveFires() {
        return missionQueue.size() + inProgressByMissionId.size();
    }

    public synchronized DroneState getDroneState() {
        return getDroneState(1);
    }

    public synchronized DroneState getDroneState(int droneId) {
        return droneStateById.getOrDefault(droneId, DroneState.IDLE);
    }

    public synchronized SchedulerState getSchedulerState() {
        return state;
    }

    public synchronized double getDroneX(int droneId) {
        return dronePosXById.getOrDefault(droneId, 0.0);
    }

    public synchronized double getDroneY(int droneId) {
        return dronePosYById.getOrDefault(droneId, 0.0);
    }

    public synchronized int getAssignedCount(int droneId) {
        return assignedCountByDroneId.getOrDefault(droneId, 0);
    }

    private void updateGui() {
        if (gui != null) {
            gui.setDroneState(getDroneState().toString());
            gui.setActiveFires(getActiveFires());
        }
    }

    private void logTransition(SchedulerState from, SchedulerState to, String event) {
        log(String.format("[Scheduler] %s --(%s)--> %s", from, event, to));
    }

    private void log(String message) {
        System.out.println(message);
        if (gui != null) {
            gui.log(message);
        }
    }

    private static class RerouteCandidate {
        private final int droneId;
        private final double distanceToNewZone;
        private final int severityUpgrade;

        private RerouteCandidate(int droneId, double distanceToNewZone, int severityUpgrade) {
            this.droneId = droneId;
            this.distanceToNewZone = distanceToNewZone;
            this.severityUpgrade = severityUpgrade;
        }

        private boolean isBetterThan(RerouteCandidate other) {
            if (severityUpgrade != other.severityUpgrade) {
                return severityUpgrade > other.severityUpgrade;
            }
            return distanceToNewZone < other.distanceToNewZone;
        }
    }

    private static class DispatchCandidate {
        private final Mission mission;
        private final int droneId;
        private final int severityRank;
        private final double estimatedTravelSeconds;
        private final LocalTime requestTime;
        private final int assignedCount;

        private DispatchCandidate(Mission mission, int droneId, int severityRank, double estimatedTravelSeconds,
                LocalTime requestTime, int assignedCount) {
            this.mission = mission;
            this.droneId = droneId;
            this.severityRank = severityRank;
            this.estimatedTravelSeconds = estimatedTravelSeconds;
            this.requestTime = requestTime;
            this.assignedCount = assignedCount;
        }

        private boolean isBetterThan(DispatchCandidate other) {
            if (severityRank != other.severityRank) {
                return severityRank > other.severityRank;
            }
            if (Math.abs(estimatedTravelSeconds - other.estimatedTravelSeconds) > 0.0001) {
                return estimatedTravelSeconds < other.estimatedTravelSeconds;
            }
            if (assignedCount != other.assignedCount) {
                return assignedCount < other.assignedCount;
            }
            if (requestTime != null && other.requestTime != null && !requestTime.equals(other.requestTime)) {
                return requestTime.isBefore(other.requestTime);
            }
            return mission.getMissionId() < other.mission.getMissionId();
        }
    }
}
