package types;

/**
 * Represents the possible states of a Drone in the system.
 * Used by DroneSubsystem and Scheduler for state machine transitions.
 */
public enum DroneState {
    IDLE,
    EN_ROUTE,
    DROPPING_AGENT,
    RETURNING;

    @Override
    public String toString() {
        return name().replace('_', ' ');
    }
}
