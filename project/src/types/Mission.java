package types;

import fire_incident_subsystem.FireRequest;

/**
 * Wraps a FireRequest with mission-level tracking information.
 * Used by the Scheduler to manage the mission queue.
 */
public class Mission {

    private static int nextId = 1;

    private final int missionId;
    private final FireRequest fireRequest;
    private MissionStatus status;

    public Mission(FireRequest fireRequest) {
        this.missionId = nextId++;
        this.fireRequest = fireRequest;
        this.status = MissionStatus.QUEUED;
    }

    public int getMissionId() {
        return missionId;
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

    public int getZoneId() {
        return fireRequest.getZoneId();
    }

    @Override
    public String toString() {
        return String.format("Mission[id=%d, zone=%d, status=%s, foam=%.1f]",
                missionId, getZoneId(), status, fireRequest.getRequiredFoam());
    }
}
