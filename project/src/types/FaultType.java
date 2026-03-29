package types;

/**
 * This enum stores the fault types used in Iteration 4.
 * It makes the code easier to read than passing fault names around as plain strings.
 */
public enum FaultType {
    NONE(false, "No Fault"),
    STUCK_MID_FLIGHT(false, "Stuck Mid-Flight"),
    NOZZLE_JAMMED(true, "Nozzle Jammed"),
    PACKET_LOSS(false, "Packet Loss");

    private final boolean hardFault;
    private final String displayName;

    FaultType(boolean hardFault, String displayName) {
        this.hardFault = hardFault;
        this.displayName = displayName;
    }

    /**
     * This tells us if the fault is a hard fault or a soft fault.
     */
    public boolean isHardFault() {
        return hardFault;
    }

    /**
     * This converts the text from the CSV file into a fault type.
     * It also accepts a few common spelling variations.
     */
    public static FaultType fromText(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return NONE;
        }

        String normalized = rawValue.trim()
                .toUpperCase()
                .replace('-', '_')
                .replace(' ', '_');

        if ("NONE".equals(normalized)) {
            return NONE;
        }
        if ("STUCK".equals(normalized) || "STUCK_MIDFLIGHT".equals(normalized)) {
            return STUCK_MID_FLIGHT;
        }
        if ("NOZZLE_JAM".equals(normalized) || "JAMMED_NOZZLE".equals(normalized)
                || "BAY_DOORS_JAMMED".equals(normalized)) {
            return NOZZLE_JAMMED;
        }
        if ("PACKET_LOSS".equals(normalized) || "CORRUPTED_MESSAGES".equals(normalized)
                || "COMMUNICATION_FAILURE".equals(normalized)) {
            return PACKET_LOSS;
        }
        return FaultType.valueOf(normalized);
    }

    /**
     * This returns a nicer label for the GUI and log output.
     */
    public String getDisplayName() {
        return displayName;
    }
}
