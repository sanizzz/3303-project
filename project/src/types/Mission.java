package types;

import fire_incident_subsystem.FireRequest;

/**
 * Wraps a FireRequest with mission-level tracking information.
 * Used by the Scheduler to manage the mission queue.
 */
public class Mission {

    private static int nextId = 1;
    private static int nextIncidentId = 1;

    private final int missionId;
    private final int incidentId;
    private final FireRequest fireRequest;
    private final double assignedAgentLiters;
    private double remainingAgentLiters;
    private final long earliestDispatchTimeMs;
    private MissionStatus status;

    public Mission(FireRequest fireRequest) {
        this(nextId++, allocateIncidentId(), fireRequest,
                fireRequest == null ? 0.0 : fireRequest.getRequiredFoam(),
                0L);
    }

    /**
     * This constructor lets the scheduler split one fire into several capacity-sized missions
     * while still treating them as the same incident for completion accounting.
     */
    public Mission(FireRequest fireRequest, int incidentId, double assignedAgentLiters, long earliestDispatchTimeMs) {
        this(nextId++, incidentId, fireRequest, assignedAgentLiters, earliestDispatchTimeMs);
    }

    public Mission(int missionId, FireRequest fireRequest) {
        this(missionId, missionId, fireRequest,
                fireRequest == null ? 0.0 : fireRequest.getRequiredFoam(),
                0L);
    }

    /**
     * This constructor keeps the older tests working while also carrying the Iteration 5
     * incident grouping and staggered-dispatch metadata.
     */
    public Mission(int missionId, int incidentId, FireRequest fireRequest,
            double assignedAgentLiters, long earliestDispatchTimeMs) {
        this.missionId = missionId;
        this.incidentId = Math.max(1, incidentId);
        this.fireRequest = fireRequest;
        this.assignedAgentLiters = Math.max(0.0, assignedAgentLiters);
        this.remainingAgentLiters = Math.max(0.0, assignedAgentLiters);
        this.earliestDispatchTimeMs = Math.max(0L, earliestDispatchTimeMs);
        this.status = MissionStatus.QUEUED;
        if (missionId >= nextId) {
            nextId = missionId + 1;
        }
        if (this.incidentId >= nextIncidentId) {
            nextIncidentId = this.incidentId + 1;
        }
    }

    /**
     * This allocates one shared incident id so several missions can still be recognised
     * as one fire by the scheduler.
     */
    public static synchronized int allocateIncidentId() {
        return nextIncidentId++;
    }

    /**
     * This resets the mission/incident counters between MetricsRunner samples so each run
     * starts from a clean state instead of inheriting ids from the previous run.
     */
    public static synchronized void resetIds() {
        nextId = 1;
        nextIncidentId = 1;
    }

    public int getMissionId() {
        return missionId;
    }

    /**
     * This exposes the shared incident id used to aggregate several mission segments back
     * into one fire completion.
     */
    public int getIncidentId() {
        return incidentId;
    }

    public FireRequest getFireRequest() {
        return fireRequest;
    }

    public MissionStatus getStatus() {
        return status;
    }

    public void setStatus(MissionStatus status) {
        this.status = status;
    }

    /**
     * This returns how much firefighting agent was assigned to this mission segment.
     */
    public double getAssignedAgentLiters() {
        return assignedAgentLiters;
    }

    /**
     * This returns how much of the assigned suppression load still remains on this mission.
     */
    public synchronized double getRemainingAgentLiters() {
        return remainingAgentLiters;
    }

    /**
     * This subtracts the amount a drone actually discharged so refill/resume cycles can
     * continue the same mission segment safely.
     */
    public synchronized double consumeAssignedAgent(double amount) {
        double used = Math.min(Math.max(0.0, amount), remainingAgentLiters);
        remainingAgentLiters -= used;
        if (remainingAgentLiters < 0.0) {
            remainingAgentLiters = 0.0;
        }
        return used;
    }

    /**
     * This helper lets the scheduler and drone check whether the assigned segment is done.
     */
    public synchronized boolean isSegmentComplete() {
        return remainingAgentLiters <= 0.0001;
    }

    /**
     * This stores the earliest wall-clock time when the scheduler may release this mission,
     * which is how the same-zone launch delay is enforced without extra concurrency tools.
     */
    public long getEarliestDispatchTimeMs() {
        return earliestDispatchTimeMs;
    }

    public int getZoneId() {
        return fireRequest.getZoneId();
    }

    @Override
    public String toString() {
        return String.format("Mission[id=%d, incident=%d, zone=%d, status=%s, assigned=%.1fL, remaining=%.1fL]",
                missionId,
                incidentId,
                getZoneId(),
                status,
                assignedAgentLiters,
                getRemainingAgentLiters());
    }
}
