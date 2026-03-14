package types;

/**
 * UDP configuration used by the Iteration 3 subsystem launchers.
 * Default loopback ports are provided for single-machine multi-process tests.
 */
public class UdpConfig {
    public static final int FIRE_TO_SCHED_PORT = 5001;
    public static final int DRONE_STATUS_PORT = 5002;
    public static final int SCHED_TO_DRONE_PORT = 5003;
    public static final int DRONE_COMMAND_BASE_PORT = SCHED_TO_DRONE_PORT;
    public static final int SCHED_TO_FIRE_PORT = 5004;
    public static final int PROGRESS_STATUS_EVERY_N_TICKS = 10;
    public static final boolean LOG_EVERY_STATUS_UPDATE = false;

    private UdpConfig() {
    }

    public static int commandPortForDrone(int droneId) {
        return commandPortForDrone(droneId, DRONE_COMMAND_BASE_PORT);
    }

    public static int commandPortForDrone(int droneId, int basePort) {
        return basePort + Math.max(0, droneId - 1);
    }
}
