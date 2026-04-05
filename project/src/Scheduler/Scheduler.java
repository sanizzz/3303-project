package Scheduler;

import Drone_subsystem.Drone;
import Drone_subsystem.Zone;
import fire_incident_subsystem.FireRequest;
import gui.SimulationGUI;
import types.DispatchCommand;
import types.DroneState;
import types.DroneStatusUpdate;
import types.FaultType;
import types.LogUtil;
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
 * This is the main scheduler for Iteration 4.
 * It receives fire requests, sends missions to drones, and handles faults.
 */
public class Scheduler implements Runnable {

    private static final double BATTERY_MARGIN_SECONDS = 10.0;
    private static final double ON_PATH_TOLERANCE = 75.0;
    private static final long LOOP_WAIT_MS = 200L;
    private static final int DEFAULT_TIME_SCALE = 20;
    private static final double SAME_ZONE_DISPATCH_DELAY_SIM_SECONDS = 7.0;

    private SchedulerState state = SchedulerState.IDLE;

    private final LinkedList<Mission> missionQueue = new LinkedList<>();
    private final Map<Integer, Mission> inProgressByMissionId = new HashMap<>();
    private final Map<Integer, Mission> activeMissionByDroneId = new HashMap<>();
    private final Map<Integer, Mission> pendingReleasedMissionByDroneId = new HashMap<>();

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
    private final Map<Integer, FaultType> droneFaultById = new HashMap<>();
    private final Map<Integer, String> droneFaultMessageById = new HashMap<>();
    private final Map<Integer, Integer> activeIncidentByZoneId = new HashMap<>();
    private final Map<Integer, Severity> zoneSeverityById = new HashMap<>();
    private final Map<Integer, Double> zoneRemainingAgentById = new HashMap<>();
    private final int timeScale;
    private int configuredDroneCount = 1;

    private SimulationGUI gui;
    private Map<Integer, Zone> zoneMap;

    public Scheduler() {
        this(null, null, 1, DEFAULT_TIME_SCALE);
    }

    public Scheduler(SimulationGUI gui, Map<Integer, Zone> zoneMap) {
        this(gui, zoneMap, 1, DEFAULT_TIME_SCALE);
    }

    public Scheduler(SimulationGUI gui, Map<Integer, Zone> zoneMap, int configuredDroneCount) {
        this(gui, zoneMap, configuredDroneCount, DEFAULT_TIME_SCALE);
    }

    public Scheduler(SimulationGUI gui, Map<Integer, Zone> zoneMap, int configuredDroneCount, int timeScale) {
        this.gui = gui;
        this.zoneMap = zoneMap;
        this.timeScale = Math.max(1, timeScale);
        initDroneTracking(configuredDroneCount);
        initZoneTracking();
    }

    public synchronized void setGui(SimulationGUI gui) {
        this.gui = gui;
    }

    public synchronized void setZoneMap(Map<Integer, Zone> zoneMap) {
        this.zoneMap = zoneMap;
        initZoneTracking();
    }

    public synchronized void setConfiguredDroneCount(int count) {
        initDroneTracking(count);
    }

    /**
     * This sets up the maps that store the current information for each drone.
     */
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
            droneFaultById.putIfAbsent(i, FaultType.NONE);
            droneFaultMessageById.putIfAbsent(i, "Ready");
        }
    }

    /**
     * This seeds the zone status maps so the GUI can always render every zone even before
     * the first fire arrives.
     */
    private void initZoneTracking() {
        if (zoneMap == null) {
            return;
        }
        for (Zone zone : zoneMap.values()) {
            zoneSeverityById.putIfAbsent(zone.getZoneID(), null);
            zoneRemainingAgentById.putIfAbsent(zone.getZoneID(), 0.0);
        }
    }

    /**
     * This is the main scheduler loop.
     * It keeps checking for new fire requests, drone updates, and dispatch decisions.
     */
    @Override
    public void run() {
        log("[Scheduler] State machine started. State: " + state);
        updateGui();

        while (!Thread.currentThread().isInterrupted()) {
            synchronized (this) {
                while (!statusUpdateQueue.isEmpty()) {
                    handleDroneStatusUpdate(statusUpdateQueue.removeFirst());
                }

                while (!incomingFireRequests.isEmpty()) {
                    enqueueMission(incomingFireRequests.removeFirst());
                }

                dispatchNextIfPossible();

                try {
                    wait(computeSchedulerWaitMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log("[Scheduler] Shutting down.");
    }

    /**
     * This takes a fire request and turns it into a mission unless that zone is already active.
     */
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

        req.setResolved(false);
        req.setInProgress(true);

        int incidentId = Mission.allocateIncidentId();
        activeIncidentByZoneId.put(req.getZoneId(), incidentId);
        zoneSeverityById.put(req.getZoneId(), req.getSeverity());
        zoneRemainingAgentById.put(req.getZoneId(), req.getRequiredFoam());
        markZoneState(req.getZoneId(), Zone.ZoneState.ON_FIRE);

        SchedulerState old = state;
        if (state == SchedulerState.IDLE) {
            state = SchedulerState.DISPATCHING;
            logTransition(old, state, "NEW_FIRE");
        }

        int segmentCount = enqueueMissionSegments(req, incidentId);
        log(String.format("[Scheduler] Enqueued Zone %d as %d mission segment(s) | Severity=%s | TotalAgent=%.1fL | Fault=%s @ %ds | Pending=%s",
                req.getZoneId(),
                segmentCount,
                req.getSeverity(),
                req.getRequiredFoam(),
                req.getInjectedFaultType(),
                req.getFaultTriggerSeconds(),
                req.hasPendingFault()));

        updateGui();
    }

    /**
     * This breaks one fire into one or more capacity-sized missions so multiple drones can be
     * launched against the same zone without abandoning the existing monitor-based scheduler.
     */
    private int enqueueMissionSegments(FireRequest req, int incidentId) {
        double remainingAgent = req.getRequiredFoam();
        int segmentIndex = 0;
        Mission firstMission = null;

        while (remainingAgent > 0.0001) {
            double assignedAgent = Math.min(Drone.getLoadCapacity(), remainingAgent);
            long earliestDispatchTimeMs = System.currentTimeMillis()
                    + (segmentIndex * scaledMillisForSimulationSeconds(SAME_ZONE_DISPATCH_DELAY_SIM_SECONDS));
            Mission mission = new Mission(req, incidentId, assignedAgent, earliestDispatchTimeMs);
            missionQueue.add(mission);
            if (firstMission == null) {
                firstMission = mission;
            }
            remainingAgent -= assignedAgent;
            segmentIndex++;
        }

        if (firstMission != null) {
            attemptRerouteForNewMission(firstMission);
        }
        return Math.max(1, segmentIndex);
    }

    /**
     * This handles rerouting when a better mission appears while a drone is already flying.
     */
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
        pendingReleasedMissionByDroneId.put(reroute.droneId, previousMission);

        newMission.setStatus(MissionStatus.IN_PROGRESS);
        inProgressByMissionId.put(newMission.getMissionId(), newMission);
        activeMissionByDroneId.put(reroute.droneId, newMission);
        dispatchQueue.add(buildDispatchCommand(reroute.droneId, newMission));
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

            if (currentMission.getIncidentId() == newMission.getIncidentId()) {
                continue;
            }

            if (!canDroneTakeMissionNow(newMission, droneId)) {
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
        return activeIncidentByZoneId.containsKey(zoneId);
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
        long now = System.currentTimeMillis();
        for (Mission mission : missionQueue) {
            if (mission.getEarliestDispatchTimeMs() > now) {
                continue;
            }
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

    /**
     * This sends a mission to a drone and marks it as in progress.
     */
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
                "[Scheduler] Dispatch decision: Drone %d -> Zone %d | Severity=%s | SegmentLoad=%.1fL | ETA=%.1fs | AssignedLoads=%d",
                candidate.droneId,
                candidate.mission.getZoneId(),
                candidate.mission.getFireRequest().getSeverity(),
                candidate.mission.getAssignedAgentLiters(),
                candidate.estimatedTravelSeconds,
                newAssignedCount));
        if (!droneStatusSeenById.getOrDefault(candidate.droneId, false)) {
            log(String.format(
                    "[Scheduler] Warning: Drone %d has not reported any UDP status yet. Verify that its process is running and listening on the expected command port.",
                    candidate.droneId));
        }

        dispatchQueue.add(buildDispatchCommand(candidate.droneId, candidate.mission));
        droneStateById.put(candidate.droneId, DroneState.EN_ROUTE);
        notifyAll();

        old = state;
        state = SchedulerState.WAITING_FOR_DRONE;
        logTransition(old, state, "DISPATCH_SENT");
        updateGui();
    }

    /**
     * This builds the dispatch command and attaches the fault if the request still has one.
     */
    private DispatchCommand buildDispatchCommand(int droneId, Mission mission) {
        FireRequest request = mission.getFireRequest();
        FaultType faultType = request.consumePendingFault();
        int faultTriggerSeconds = faultType == FaultType.NONE ? 0 : request.getFaultTriggerSeconds();

        if (faultType != FaultType.NONE) {
            log(String.format("[Scheduler] Dispatching Drone %d with injected fault %s at +%ds simulated.",
                    droneId, faultType, faultTriggerSeconds));
        }

        return DispatchCommand.dispatch(droneId, mission, faultType, faultTriggerSeconds);
    }

    /**
     * This computes the next monitor wait so delayed support missions wake up promptly without
     * spinning the scheduler thread.
     */
    private long computeSchedulerWaitMs() {
        long waitMs = LOOP_WAIT_MS;
        long now = System.currentTimeMillis();
        for (Mission mission : missionQueue) {
            if (mission.getEarliestDispatchTimeMs() > now) {
                waitMs = Math.min(waitMs, Math.max(1L, mission.getEarliestDispatchTimeMs() - now));
            }
        }
        return Math.max(1L, waitMs);
    }

    private long scaledMillisForSimulationSeconds(double simulationSeconds) {
        return Math.max(1L, Math.round((simulationSeconds * 1000.0) / timeScale));
    }

    /**
     * This handles one status update sent by a drone.
     * It is where the scheduler notices mission progress and fault events.
     */
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

        if (update.hasFault()) {
            droneFaultById.put(droneId, update.getFaultType());
            droneFaultMessageById.put(droneId, update.getMessage());
        } else if (update.getDroneState() != DroneState.OFFLINE) {
            clearDroneFault(droneId, update.getMessage());
        }

        if (shouldLogStatusUpdate(update)) {
            log("[Scheduler] Received DroneStatusUpdate: " + update);
        }

        Mission mission = inProgressByMissionId.get(update.getMissionId());
        releasePendingReroutedMissionIfAcknowledged(droneId, update);
        if (update.getFaultType() == FaultType.NOZZLE_JAMMED && update.getDroneState() == DroneState.OFFLINE) {
            handleHardFault(droneId, mission, update);
            updateGui();
            return;
        }

        if (update.getFaultType() == FaultType.STUCK_MID_FLIGHT) {
            log(String.format("[Scheduler] Drone %d reported soft fault: %s", droneId, update.getMessage()));
        } else if (update.getFaultType() == FaultType.PACKET_LOSS) {
            log(String.format("[Scheduler] Drone %d reported packet loss. Waiting for retry.", droneId));
        }

        if (update.getDroneState() == DroneState.DROPPING_AGENT && mission != null && isArrivalUpdate(update)) {
            Zone zone = zoneMap != null ? zoneMap.get(mission.getZoneId()) : null;
            if (zone != null) {
                dronePosXById.put(droneId, (double) zone.getMiddleX());
                dronePosYById.put(droneId, (double) zone.getMiddleY());
            }
            markZoneState(mission.getZoneId(), Zone.ZoneState.ON_FIRE);
            log("[Scheduler] Drone " + droneId + " arrived at Zone " + mission.getZoneId() + ".");
        } else if (update.getDroneState() == DroneState.RESETTING) {
            log(String.format("[Scheduler] Drone %d temporarily unavailable while resetting for Mission %d.",
                    droneId, update.getMissionId()));
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

    /**
     * This clears the fault shown for a drone after it has recovered.
     */
    private void clearDroneFault(int droneId, String latestMessage) {
        droneFaultById.put(droneId, FaultType.NONE);
        if (latestMessage == null || latestMessage.isBlank()) {
            droneFaultMessageById.put(droneId, "Ready");
        } else {
            droneFaultMessageById.put(droneId, latestMessage);
        }
    }

    /**
     * This handles a hard fault.
     * The drone is marked offline and the mission is put back in the queue.
     */
    private void handleHardFault(int droneId, Mission mission, DroneStatusUpdate update) {
        droneStateById.put(droneId, DroneState.OFFLINE);
        droneFaultById.put(droneId, FaultType.NOZZLE_JAMMED);
        droneFaultMessageById.put(droneId, update.getMessage());

        Mission mappedActiveMission = activeMissionByDroneId.remove(droneId);
        Mission deferredMission = pendingReleasedMissionByDroneId.remove(droneId);

        boolean requeuedAnyMission = false;
        if (mission != null) {
            requeueMissionForRedispatch(mission, true);
            requeuedAnyMission = true;
        } else if (mappedActiveMission != null) {
            requeueMissionForRedispatch(mappedActiveMission, true);
            requeuedAnyMission = true;
        }

        if (deferredMission != null && deferredMission != mission && deferredMission != mappedActiveMission) {
            requeueMissionForRedispatch(deferredMission, true);
            requeuedAnyMission = true;
        }

        if (!requeuedAnyMission) {
            log(String.format("[Scheduler] Drone %d marked offline, but no active mission was mapped.", droneId));
            return;
        }

        SchedulerState old = state;
        state = SchedulerState.DISPATCHING;
        if (old != state) {
            logTransition(old, state, "HARD_FAULT_REQUEUE");
        }
        if (deferredMission != null && deferredMission != mission && deferredMission != mappedActiveMission) {
            log(String.format(
                    "[Scheduler] Hard fault on Drone %d. Active mission and deferred reroute mission re-queued; drone marked OFFLINE.",
                    droneId));
        } else {
            Mission loggedMission = mission != null ? mission : mappedActiveMission;
            log(String.format(
                    "[Scheduler] Hard fault on Drone %d. Mission %d for Zone %d re-queued and drone marked OFFLINE.",
                    droneId, loggedMission.getMissionId(), loggedMission.getZoneId()));
        }
        notifyAll();
        dispatchNextIfPossible();
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

    /**
     * This decides which drone updates are important enough to print in the log.
     */
    private boolean shouldLogStatusUpdate(DroneStatusUpdate update) {
        if (UdpConfig.LOG_EVERY_STATUS_UPDATE) {
            return true;
        }

        if (update.hasFault()) {
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

    /**
     * This finishes a mission after the scheduler gets the completion update from the drone.
     */
    private void completeMission(int droneId, Mission mission) {
        mission.setStatus(MissionStatus.COMPLETED);
        inProgressByMissionId.remove(mission.getMissionId());
        activeMissionByDroneId.remove(droneId);
        pendingReleasedMissionByDroneId.remove(droneId);
        clearDroneFault(droneId, "Mission complete");

        FireRequest request = mission.getFireRequest();
        request.updateRequiredFoam(mission.getAssignedAgentLiters());
        double remainingFireLoad = request.getRequiredFoam();
        zoneRemainingAgentById.put(mission.getZoneId(), remainingFireLoad);

        Zone zone = zoneMap != null ? zoneMap.get(mission.getZoneId()) : null;
        if (zone != null) {
            dronePosXById.put(droneId, (double) zone.getMiddleX());
            dronePosYById.put(droneId, (double) zone.getMiddleY());
        }

        if (remainingFireLoad <= 0.0001) {
            request.markExtinguishedAtIfUnset(System.nanoTime());
            request.setResolved(true);
            request.setInProgress(false);
            activeIncidentByZoneId.remove(mission.getZoneId());
            zoneRemainingAgentById.put(mission.getZoneId(), 0.0);
            markZoneState(mission.getZoneId(), Zone.ZoneState.EXTINGUISHED);
            completionQueue.add(request);
        } else {
            request.setResolved(false);
            request.setInProgress(hasOutstandingWorkForIncident(mission.getIncidentId()));
            markZoneState(mission.getZoneId(), Zone.ZoneState.ON_FIRE);
        }
        notifyAll();

        if (remainingFireLoad <= 0.0001) {
            log(String.format("[Scheduler] Mission %d completed by Drone %d for Zone %d. Fire fully extinguished. Agent: %.1fL, Battery: %.1fs",
                    mission.getMissionId(),
                    droneId,
                    mission.getZoneId(),
                    droneAgentById.getOrDefault(droneId, Drone.getLoadCapacity()),
                    droneBatteryById.getOrDefault(droneId, Drone.getFullBattery())));
        } else {
            log(String.format("[Scheduler] Mission %d completed by Drone %d for Zone %d. Fire still needs %.1fL.",
                    mission.getMissionId(),
                    droneId,
                    mission.getZoneId(),
                    remainingFireLoad));
        }
    }

    /**
     * This decides what an available drone should do next.
     */
    private void handleDroneAvailability(int droneId) {
        if (droneStateById.getOrDefault(droneId, DroneState.IDLE) == DroneState.OFFLINE) {
            return;
        }

        releaseDeferredMissionForIdleDrone(droneId);

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
            if (!isDroneAtBase(droneId)) {
                log(String.format("[Scheduler] Drone %d cannot serve pending work with current resources. Returning to base.",
                        droneId));
                sendReturnToBase(droneId);
            }
            return;
        }

        dispatchNextIfPossible();
    }

    /**
     * This resets the drone values when it gets back to base.
     */
    private void resetDroneAtBase(int droneId) {
        droneAgentById.put(droneId, Drone.getLoadCapacity());
        droneBatteryById.put(droneId, Drone.getFullBattery());
        dronePosXById.put(droneId, 0.0);
        dronePosYById.put(droneId, 0.0);
        droneStateById.put(droneId, DroneState.IDLE);
        clearDroneFault(droneId, "At base and ready");
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

        if (mission.getEarliestDispatchTimeMs() > System.currentTimeMillis()) {
            return false;
        }

        if (droneAgentById.getOrDefault(droneId, Drone.getLoadCapacity()) <= 0 && !isDroneAtBase(droneId)) {
            return false;
        }

        double availableAgent = droneAgentById.getOrDefault(droneId, Drone.getLoadCapacity());
        if (isDroneAtBase(droneId)) {
            availableAgent = Drone.getLoadCapacity();
        }
        if (availableAgent + 0.0001 < mission.getRemainingAgentLiters()) {
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

    /**
     * This sends a return-to-base command if the drone is allowed to go back.
     */
    private void sendReturnToBase(Integer targetDroneId) {
        if (targetDroneId != null) {
            DroneState current = droneStateById.getOrDefault(targetDroneId, DroneState.IDLE);
            if (current == DroneState.RETURNING || current == DroneState.OFFLINE) {
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

    /**
     * This checks whether a fire still has queued or in-progress mission segments so the
     * scheduler knows whether the shared fire request should remain active.
     */
    private boolean hasOutstandingWorkForIncident(int incidentId) {
        for (Mission queued : missionQueue) {
            if (queued.getIncidentId() == incidentId) {
                return true;
            }
        }
        for (Mission inProgress : inProgressByMissionId.values()) {
            if (inProgress.getIncidentId() == incidentId) {
                return true;
            }
        }
        return false;
    }

    private void releasePendingReroutedMissionIfAcknowledged(int droneId, DroneStatusUpdate update) {
        Mission deferredMission = pendingReleasedMissionByDroneId.get(droneId);
        Mission activeMission = activeMissionByDroneId.get(droneId);
        if (deferredMission == null || activeMission == null) {
            return;
        }

        if (update.getMissionId() != activeMission.getMissionId()) {
            return;
        }

        if (update.getDroneState() == DroneState.OFFLINE) {
            return;
        }

        pendingReleasedMissionByDroneId.remove(droneId);
        requeueMissionForRedispatch(deferredMission, true);
        log(String.format(
                "[Scheduler] Drone %d acknowledged reroute to Mission %d. Previous Mission %d returned to ready queue.",
                droneId,
                activeMission.getMissionId(),
                deferredMission.getMissionId()));
        notifyAll();
    }

    private void releaseDeferredMissionForIdleDrone(int droneId) {
        Mission deferredMission = pendingReleasedMissionByDroneId.remove(droneId);
        if (deferredMission == null) {
            return;
        }

        requeueMissionForRedispatch(deferredMission, true);
        log(String.format(
                "[Scheduler] Drone %d became idle before reroute confirmation. Deferred Mission %d returned to queue.",
                droneId,
                deferredMission.getMissionId()));
        notifyAll();
    }

    private void requeueMissionForRedispatch(Mission mission, boolean addFirst) {
        if (mission == null) {
            return;
        }

        inProgressByMissionId.remove(mission.getMissionId());
        mission.setStatus(MissionStatus.QUEUED);
        mission.getFireRequest().setResolved(false);
        mission.getFireRequest().setInProgress(true);
        if (!missionQueue.contains(mission)) {
            if (addFirst) {
                missionQueue.addFirst(mission);
            } else {
                missionQueue.add(mission);
            }
        }
        markZoneState(mission.getZoneId(), Zone.ZoneState.ON_FIRE);
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

    /**
     * This keeps zone state changes in one helper so GUI and completion accounting stay aligned
     * even when several mission segments target the same zone.
     */
    private void markZoneState(int zoneId, Zone.ZoneState newState) {
        if (zoneMap == null) {
            return;
        }
        Zone zone = zoneMap.get(zoneId);
        if (zone != null) {
            zone.setZoneState(newState);
        }
    }

    /**
     * This adds a new fire request into the scheduler input queue.
     */
    public synchronized void putRequest(FireRequest req) {
        req.markDetectedAtIfUnset(System.nanoTime());
        incomingFireRequests.add(req);
        log(String.format("[Scheduler] Received fire request for Zone %d with fault=%s @ %ds",
                req.getZoneId(), req.getInjectedFaultType(), req.getFaultTriggerSeconds()));
        notifyAll();
    }

    /**
     * This waits until there is a command ready for a specific drone.
     */
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

    /**
     * This returns the next dispatch command for the UDP version of the system.
     */
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

    /**
     * This is a helper used by some older tests.
     */
    public synchronized Mission getDispatchedMission() {
        DispatchCommand cmd = getDispatchCommandForDrone(1);
        if (cmd == null || cmd.isReturnToBase()) {
            return null;
        }
        return cmd.getMission();
    }

    /**
     * This adds a new drone status update into the scheduler queue.
     */
    public synchronized void sendDroneStatusUpdate(DroneStatusUpdate update) {
        statusUpdateQueue.add(update);
        notifyAll();
    }

    /**
     * This waits until a completion is ready to be sent back to the fire subsystem.
     */
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

    /**
     * This returns how many missions are currently waiting in the ready queue.
     */
    public synchronized int getQueuedMissionCount() {
        return missionQueue.size();
    }

    /**
     * This returns how many missions are currently being worked on.
     */
    public synchronized int getInProgressMissionCount() {
        return inProgressByMissionId.size();
    }

    public synchronized DroneState getDroneState() {
        return getDroneState(1);
    }

    public synchronized DroneState getDroneState(int droneId) {
        return droneStateById.getOrDefault(droneId, DroneState.IDLE);
    }

    /**
     * Returns the current fault type for a drone.
     */
    public synchronized FaultType getDroneFaultType(int droneId) {
        return droneFaultById.getOrDefault(droneId, FaultType.NONE);
    }

    /**
     * Returns the current status message for a drone.
     */
    public synchronized String getDroneFaultMessage(int droneId) {
        return droneFaultMessageById.getOrDefault(droneId, "Ready");
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

    /**
     * This lets MetricsRunner wait on the scheduler monitor until all ready, active, and
     * status queues are drained so a previous sample cannot leak work into the next run.
     */
    public synchronized void waitUntilQuiescent() {
        while (!isQuiescent()) {
            try {
                wait(LOOP_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * This captures the scheduler-side notion of "no work left" for the headless metrics loop.
     */
    public synchronized boolean isQuiescent() {
        return incomingFireRequests.isEmpty()
                && missionQueue.isEmpty()
                && inProgressByMissionId.isEmpty()
                && statusUpdateQueue.isEmpty()
                && dispatchQueue.isEmpty()
                && completionQueue.isEmpty()
                && !hasBusyDrones();
    }

    /**
     * This treats any flying, dropping, or resetting drone as still active work for metrics.
     */
    private boolean hasBusyDrones() {
        for (DroneState droneState : droneStateById.values()) {
            if (droneState == DroneState.EN_ROUTE
                    || droneState == DroneState.DROPPING_AGENT
                    || droneState == DroneState.RETURNING
                    || droneState == DroneState.RESETTING) {
                return true;
            }
        }
        return false;
    }

    /**
     * This updates the GUI with the latest scheduler and drone information.
     */
    private void updateGui() {
        if (gui != null) {
            gui.setConfiguredDroneCount(configuredDroneCount);
            gui.setDroneState(getDroneState().toString());
            gui.setActiveFires(getActiveFires());
            for (int droneId = 1; droneId <= configuredDroneCount; droneId++) {
                Mission activeMission = activeMissionByDroneId.get(droneId);
                gui.updateDroneTelemetry(
                        droneId,
                        droneStateById.getOrDefault(droneId, DroneState.IDLE).toString(),
                        droneFaultById.getOrDefault(droneId, FaultType.NONE),
                        droneFaultMessageById.getOrDefault(droneId, "Ready"),
                        droneAgentById.getOrDefault(droneId, Drone.getLoadCapacity()),
                        droneBatteryById.getOrDefault(droneId, Drone.getFullBattery()),
                        dronePosXById.getOrDefault(droneId, 0.0),
                        dronePosYById.getOrDefault(droneId, 0.0),
                        activeMission == null ? -1 : activeMission.getZoneId());
            }
            if (zoneMap != null) {
                for (Zone zone : zoneMap.values()) {
                    Severity severity = zoneSeverityById.get(zone.getZoneID());
                    gui.updateZoneStatus(
                            zone.getZoneID(),
                            zone.getZoneState().name(),
                            severity == null ? "NONE" : severity.name(),
                            zoneRemainingAgentById.getOrDefault(zone.getZoneID(), 0.0));
                }
            }
            gui.updateFleetSummary(countHealthyDrones(), countFaultyDrones(), missionQueue.size());
        }
    }

    /**
     * This counts drones still operating normally so the GUI can distinguish fleet health
     * from total fleet size.
     */
    private int countHealthyDrones() {
        int healthy = 0;
        for (int droneId = 1; droneId <= configuredDroneCount; droneId++) {
            if (droneFaultById.getOrDefault(droneId, FaultType.NONE) == FaultType.NONE
                    && droneStateById.getOrDefault(droneId, DroneState.IDLE) != DroneState.OFFLINE) {
                healthy++;
            }
        }
        return healthy;
    }

    /**
     * This counts drones currently showing a fault or permanent offline state for the GUI.
     */
    private int countFaultyDrones() {
        int faulty = 0;
        for (int droneId = 1; droneId <= configuredDroneCount; droneId++) {
            if (droneFaultById.getOrDefault(droneId, FaultType.NONE) != FaultType.NONE
                    || droneStateById.getOrDefault(droneId, DroneState.IDLE) == DroneState.OFFLINE) {
                faulty++;
            }
        }
        return faulty;
    }

    /**
     * This writes state changes in a simple scheduler log format.
     */
    private void logTransition(SchedulerState from, SchedulerState to, String event) {
        log(String.format("[Scheduler] %s --(%s)--> %s", from, event, to));
    }

    /**
     * This adds a timestamp and prints the scheduler log message.
     */
    private void log(String message) {
        String stamped = LogUtil.stamp(message);
        System.out.println(stamped);
        if (gui != null) {
            gui.log(stamped);
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
