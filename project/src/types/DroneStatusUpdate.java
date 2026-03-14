package types;

/**
 * Status update sent from DroneSubsystem to Scheduler at key state transitions.
 * Carries current drone state, resource levels, and mission context.
 */
public class DroneStatusUpdate {

    private final int droneId;
    private final DroneState droneState;
    private final int missionId;
    private final double remainingAgent; // liters of foam remaining
    private final double remainingBattery; // estimated travel budget in seconds
    private final double positionX;
    private final double positionY;
    private final String message;

    public DroneStatusUpdate(int droneId, DroneState droneState, int missionId,
            double remainingAgent, double remainingBattery, String message) {
        this(droneId, droneState, missionId, remainingAgent, remainingBattery, Double.NaN, Double.NaN, message);
    }

    public DroneStatusUpdate(int droneId, DroneState droneState, int missionId,
            double remainingAgent, double remainingBattery, double positionX, double positionY, String message) {
        this.droneId = droneId;
        this.droneState = droneState;
        this.missionId = missionId;
        this.remainingAgent = remainingAgent;
        this.remainingBattery = remainingBattery;
        this.positionX = positionX;
        this.positionY = positionY;
        this.message = message;
    }

    public int getDroneId() {
        return droneId;
    }

    public DroneState getDroneState() {
        return droneState;
    }

    public int getMissionId() {
        return missionId;
    }

    public double getRemainingAgent() {
        return remainingAgent;
    }

    public double getRemainingBattery() {
        return remainingBattery;
    }

    public double getPositionX() {
        return positionX;
    }

    public double getPositionY() {
        return positionY;
    }

    public boolean hasPosition() {
        return !Double.isNaN(positionX) && !Double.isNaN(positionY);
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return String.format(
                "DroneStatus[drone=%d, state=%s, mission=%d, agent=%.1fL, battery=%.1fs, pos=(%.1f,%.1f), msg=%s]",
                droneId, droneState, missionId, remainingAgent, remainingBattery, positionX, positionY, message);
    }
}
