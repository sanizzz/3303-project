package types;

/**
 * A command sent by Scheduler to Drone subsystem.
 * Either dispatches a mission or asks drone to return to base.
 */
public class DispatchCommand {
    private final Integer targetDroneId; // null = any available drone
    private final Mission mission; // non-null for DISPATCH
    private final boolean returnToBase;
    private final FaultType faultType;
    private final int faultTriggerSeconds;

    /**
     * This constructor stores one command from the scheduler to a drone.
     * The fault is kept in the command so it is only used for that dispatch.
     */
    private DispatchCommand(Integer targetDroneId, Mission mission, boolean returnToBase,
            FaultType faultType, int faultTriggerSeconds) {
        this.targetDroneId = targetDroneId;
        this.mission = mission;
        this.returnToBase = returnToBase;
        this.faultType = faultType == null ? FaultType.NONE : faultType;
        this.faultTriggerSeconds = Math.max(0, faultTriggerSeconds);
    }

    /**
     * This version is used when there is no injected fault.
     */
    public static DispatchCommand dispatch(Integer targetDroneId, Mission mission) {
        return dispatch(targetDroneId, mission, FaultType.NONE, 0);
    }

    /**
     * This version is used when the scheduler wants to send a mission with a fault.
     */
    public static DispatchCommand dispatch(Integer targetDroneId, Mission mission,
            FaultType faultType, int faultTriggerSeconds) {
        return new DispatchCommand(targetDroneId, mission, false, faultType, faultTriggerSeconds);
    }

    /**
     * This creates a return-to-base command.
     */
    public static DispatchCommand returnToBase(Integer targetDroneId) {
        return new DispatchCommand(targetDroneId, null, true, FaultType.NONE, 0);
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

    /**
     * Returns the fault linked to this command.
     */
    public FaultType getFaultType() {
        return faultType;
    }

    /**
     * Returns after how many simulated seconds the fault should happen.
     */
    public int getFaultTriggerSeconds() {
        return faultTriggerSeconds;
    }
}
