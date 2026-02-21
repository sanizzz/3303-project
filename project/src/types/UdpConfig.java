package types;

/**
 * Minimal UDP configuration for Iteration 2.
 * Default loopback ports are provided for single-machine multi-process tests.
 */
public class UdpConfig {
    public static final int FIRE_TO_SCHED_PORT = 5001;
    public static final int DRONE_STATUS_PORT = 5002;
    public static final int SCHED_TO_DRONE_PORT = 5003;
    public static final int SCHED_TO_FIRE_PORT = 5004;

    private UdpConfig() {
    }
}
