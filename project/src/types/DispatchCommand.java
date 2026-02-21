package types;

/**
 * A command sent by Scheduler to Drone subsystem.
 * Either dispatches a mission or asks drone to return to base.
 */
public class DispatchCommand {
    private final Integer targetDroneId; // null = any available drone
    private final Mission mission; // non-null for DISPATCH
    private final boolean returnToBase;

    private DispatchCommand(Integer targetDroneId, Mission mission, boolean returnToBase) {
        this.targetDroneId = targetDroneId;
        this.mission = mission;
        this.returnToBase = returnToBase;
    }

    public static DispatchCommand dispatch(Integer targetDroneId, Mission mission) {
        return new DispatchCommand(targetDroneId, mission, false);
    }

    public static DispatchCommand returnToBase(Integer targetDroneId) {
        return new DispatchCommand(targetDroneId, null, true);
    }

    public Integer getTargetDroneId() {
        return targetDroneId;
    }

    public Mission getMission() {
        return mission;
    }

    public boolean isReturnToBase() {
        return returnToBase;
    }
}
