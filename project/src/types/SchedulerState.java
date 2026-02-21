package types;

/**
 * Represents the possible states of the Scheduler state machine.
 */
public enum SchedulerState {
    IDLE,
    WAITING_FOR_DRONE,
    DISPATCHING;

    @Override
    public String toString() {
        return name().replace('_', ' ');
    }
}
