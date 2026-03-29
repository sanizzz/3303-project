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
    private final FaultType faultType;

    /**
     * This keeps the older constructor working for the old code and tests.
     */
    public DroneStatusUpdate(int droneId, DroneState droneState, int missionId,
            double remainingAgent, double remainingBattery, String message) {
        this(droneId, droneState, missionId, remainingAgent, remainingBattery,
                Double.NaN, Double.NaN, message, FaultType.NONE);
    }

    /**
     * This version includes position values but still assumes there is no fault.
     */
    public DroneStatusUpdate(int droneId, DroneState droneState, int missionId,
            double remainingAgent, double remainingBattery, double positionX, double positionY, String message) {
        this(droneId, droneState, missionId, remainingAgent, remainingBattery,
                positionX, positionY, message, FaultType.NONE);
    }

    /**
     * This constructor stores the full update, including a fault if one happened.
     */
    public DroneStatusUpdate(int droneId, DroneState droneState, int missionId,
            double remainingAgent, double remainingBattery, double positionX, double positionY,
            String message, FaultType faultType) {
        this.droneId = droneId;
        this.droneState = droneState;
        this.missionId = missionId;
        this.remainingAgent = remainingAgent;
        this.remainingBattery = remainingBattery;
        this.positionX = positionX;
        this.positionY = positionY;
        this.message = message;
        this.faultType = faultType == null ? FaultType.NONE : faultType;
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

    /**
     * Returns the fault type for this update.
     */
    public FaultType getFaultType() {
        return faultType;
    }

    /**
     * This is a quick check to see if the update is reporting a fault.
     */
    public boolean hasFault() {
        return faultType != FaultType.NONE;
    }

    @Override
    public String toString() {
        return String.format(
                "DroneStatus[drone=%d, state=%s, mission=%d, agent=%.1fL, battery=%.1fs, pos=(%.1f,%.1f), fault=%s, msg=%s]",
                droneId, droneState, missionId, remainingAgent, remainingBattery, positionX, positionY, faultType,
                message);
    }
}
