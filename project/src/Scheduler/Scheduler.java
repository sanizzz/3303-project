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

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * Scheduler state machine for Iteration 2.
 * Handles mission queueing, dispatch decisions, and drone status updates.
 */
public class Scheduler implements Runnable {

    private SchedulerState state = SchedulerState.IDLE;

    // --- Mission Queues ---
    private final LinkedList<Mission> missionQueue = new LinkedList<>();
    private final Map<Integer, Mission> inProgressByMissionId = new HashMap<>();

    // --- Communication Queues ---
    private final LinkedList<FireRequest> incomingFireRequests = new LinkedList<>();
    private final LinkedList<DispatchCommand> dispatchQueue = new LinkedList<>();
    private final LinkedList<DroneStatusUpdate> statusUpdateQueue = new LinkedList<>();
    private final LinkedList<FireRequest> completionQueue = new LinkedList<>();

    // --- Drone Tracking (multi-drone ready) ---
    private final Map<Integer, DroneState> droneStateById = new HashMap<>();
    private final Map<Integer, Double> droneAgentById = new HashMap<>();
    private final Map<Integer, Double> droneBatteryById = new HashMap<>();
    private final Map<Integer, Double> dronePosXById = new HashMap<>();
    private final Map<Integer, Double> dronePosYById = new HashMap<>();
    private int configuredDroneCount = 1;

    // --- References ---
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
        }
    }

    @Override
    public void run() {
        log("[Scheduler] State machine started. State: " + state);
        updateGui();

        while (!Thread.currentThread().isInterrupted()) {
            synchronized (this) {
                while (!incomingFireRequests.isEmpty()) {
                    FireRequest req = incomingFireRequests.removeFirst();
                    enqueueMission(req);
                }

                while (!statusUpdateQueue.isEmpty()) {
                    DroneStatusUpdate update = statusUpdateQueue.removeFirst();
                    handleDroneStatusUpdate(update);
                }

                dispatchNextIfPossible();

                try {
                    wait(300);
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
        updateGui();
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
        while (!missionQueue.isEmpty()) {
            Mission next = missionQueue.peek();
            Integer droneId = chooseIdleDroneForMission(next);
            if (droneId == null) {
                return;
            }

            Mission mission = missionQueue.removeFirst();
            mission.setStatus(MissionStatus.IN_PROGRESS);
            inProgressByMissionId.put(mission.getMissionId(), mission);

            SchedulerState old = state;
            state = SchedulerState.DISPATCHING;
            logTransition(old, state, "DISPATCH_MISSION(Zone " + mission.getZoneId() + ", Drone " + droneId + ")");

            dispatchQueue.add(DispatchCommand.dispatch(droneId, mission));
            droneStateById.put(droneId, DroneState.EN_ROUTE);
            notifyAll();

            old = state;
            state = SchedulerState.WAITING_FOR_DRONE;
            logTransition(old, state, "DISPATCH_SENT");
            updateGui();
        }
    }

    private Integer chooseIdleDroneForMission(Mission mission) {
        for (int droneId = 1; droneId <= configuredDroneCount; droneId++) {
            DroneState dState = droneStateById.getOrDefault(droneId, DroneState.IDLE);
            if (dState != DroneState.IDLE) {
                continue;
            }

            double agent = droneAgentById.getOrDefault(droneId, Drone.getLoadCapacity());
            double x = dronePosXById.getOrDefault(droneId, 0.0);
            double y = dronePosYById.getOrDefault(droneId, 0.0);

            if (agent <= 0 && Math.abs(x) < 0.0001 && Math.abs(y) < 0.0001) {
                droneAgentById.put(droneId, Drone.getLoadCapacity());
                droneBatteryById.put(droneId, Drone.getFullBattery());
                log("[Scheduler] Drone " + droneId + " refilled instantly at base.");
            }

            if (!hasBatteryForMission(mission, droneId)) {
                log(String.format(
                        "[Scheduler] Drone %d battery %.1fs insufficient for Zone %d trip budget. Commanding return to base.",
                        droneId,
                        droneBatteryById.getOrDefault(droneId, Drone.getFullBattery()),
                        mission.getZoneId()));
                sendReturnToBase(droneId);
                continue;
            }

            return droneId;
        }
        return null;
    }

    private void handleDroneStatusUpdate(DroneStatusUpdate update) {
        int droneId = update.getDroneId();
        initDroneTracking(Math.max(configuredDroneCount, droneId));

        droneStateById.put(droneId, update.getDroneState());
        droneAgentById.put(droneId, update.getRemainingAgent());
        droneBatteryById.put(droneId, update.getRemainingBattery());

        log("[Scheduler] Received DroneStatusUpdate: " + update);

        Mission mission = inProgressByMissionId.get(update.getMissionId());
        if (update.getDroneState() == DroneState.DROPPING_AGENT && mission != null) {
            if (isArrivalUpdate(update)) {
                Zone z = zoneMap != null ? zoneMap.get(mission.getZoneId()) : null;
                if (z != null) {
                    dronePosXById.put(droneId, (double) z.getMiddleX());
                    dronePosYById.put(droneId, (double) z.getMiddleY());
                }
                log("[Scheduler] Drone " + droneId + " arrived at Zone " + mission.getZoneId() + ".");
            }
        } else if (update.getDroneState() == DroneState.IDLE) {
            if (mission != null) {
                if (isMissionCompletionUpdate(update)) {
                    completeMission(droneId, mission);
                    makeSchedulingDecision(droneId);
                } else {
                    // Drone may report IDLE at base while refilling mid-mission.
                    // Keep it reserved to the active mission so queued work is not dispatched early.
                    droneStateById.put(droneId, DroneState.EN_ROUTE);
                    log("[Scheduler] Drone " + droneId + " refilled and continuing Mission "
                            + mission.getMissionId() + ".");
                }
            } else {
                // Returned to base without mission completion
                droneAgentById.put(droneId, Drone.getLoadCapacity());
                droneBatteryById.put(droneId, Drone.getFullBattery());
                dronePosXById.put(droneId, 0.0);
                dronePosYById.put(droneId, 0.0);
                log("[Scheduler] Drone " + droneId + " at base. Instant refill/recharge complete.");
                SchedulerState old = state;
                state = SchedulerState.IDLE;
                if (old != state) {
                    logTransition(old, state, "DRONE_AT_BASE");
                }
                dispatchNextIfPossible();
            }
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

    private void completeMission(int droneId, Mission mission) {
        mission.setStatus(MissionStatus.COMPLETED);
        mission.getFireRequest().setResolved(true);
        mission.getFireRequest().setInProgress(false);
        inProgressByMissionId.remove(mission.getMissionId());

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

    private void makeSchedulingDecision(int droneId) {
        if (missionQueue.isEmpty()) {
            SchedulerState old = state;
            state = SchedulerState.IDLE;
            logTransition(old, state, "QUEUE_EMPTY");
            log("[Scheduler] Decision: No queued missions. Commanding Drone " + droneId + " to return to base.");
            sendReturnToBase(droneId);
            return;
        }

        Mission next = missionQueue.peek();
        double agent = droneAgentById.getOrDefault(droneId, Drone.getLoadCapacity());
        if (agent <= 0) {
            log(String.format("[Scheduler] Decision: Drone %d agent EMPTY. Returning to base.", droneId));
            sendReturnToBase(droneId);
            return;
        }

        if (!hasBatteryForMission(next, droneId)) {
            log(String.format("[Scheduler] Decision: Drone %d battery insufficient for next mission. Returning to base.",
                    droneId));
            sendReturnToBase(droneId);
            return;
        }

        log(String.format("[Scheduler] Decision: Dispatch next mission (Zone %d) to available idle drone.",
                next.getZoneId()));
        dispatchNextIfPossible();
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

        return availableBattery >= (requiredTravelSeconds + 10.0);
    }

    private double distance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ==================== PUBLIC API ====================

    public synchronized void putRequest(FireRequest req) {
        incomingFireRequests.add(req);
        log("[Scheduler] Received fire request for Zone " + req.getZoneId());
        notifyAll();
    }

    /**
     * For DroneSubsystem threads in same JVM mode.
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
     * For UDP launcher mode where scheduler forwards commands externally.
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
     * Backward-compatible helper for older callers.
     */
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

    // Iteration 1 compatibility methods
    public synchronized FireRequest getRequest() {
        return null;
    }

    public synchronized void acknowledgeCompletion(FireRequest req) {
    }

    public synchronized int getActiveFires() {
        return missionQueue.size() + inProgressByMissionId.size();
    }

    public synchronized DroneState getDroneState() {
        return droneStateById.getOrDefault(1, DroneState.IDLE);
    }

    public synchronized SchedulerState getSchedulerState() {
        return state;
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
}
