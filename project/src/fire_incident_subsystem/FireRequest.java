package fire_incident_subsystem;

import types.EventType;
import types.Severity;
import java.time.LocalTime;

public class FireRequest {

    private final LocalTime time;
    private final int zoneId;
    private final EventType type;
    private final Severity severity;

    // --- Fields for Simulation Logic (Used by Drone) ---
    private double requiredFoam; // How much water/foam is needed
    private boolean isResolved; // Is the fire out?
    private boolean inProgress; // Is a drone working on it?

    public FireRequest(LocalTime time, int zoneId, EventType type, Severity severity) {
        this.time = time;
        this.zoneId = zoneId;
        this.type = type;
        this.severity = severity;

        // Initialize simulation state
        this.isResolved = false;
        this.inProgress = false;

        // Calculate foam needed based on Severity Enum
        // Make sure your Severity enum has a .getLitersNeeded() method!
        // If not, default to 10.0 or similar.
        this.requiredFoam = severity.getLitersNeeded();
    }

    // --- Getters for Identification ---
    public int getZoneId() {
        return zoneId;
    }

    public LocalTime getTime() {
        return time;
    }

    public EventType getType() {
        return type;
    }

    public Severity getSeverity() {
        return severity;
    }

    // --- Methods for Drone Logic ---

    public double getRequiredFoam() {
        return requiredFoam;
    }

    /**
     * Reduces the foam required as the drone dumps water.
     * 
     * @param amount The amount of foam dumped (e.g., 12.5L)
     */
    public void updateRequiredFoam(double amount) {
        this.requiredFoam -= amount;
        if (this.requiredFoam < 0) {
            this.requiredFoam = 0;
        }
    }

    public boolean isResolved() {
        return isResolved;
    }

    public void setResolved(boolean resolved) {
        this.isResolved = resolved;
    }

    public void setInProgress(boolean inProgress) {
        this.inProgress = inProgress;
    }

    @Override
    public String toString() {
        return String.format("Request[Zone %d | %s | %s | Foam Needed: %.1f]",
                zoneId, type, severity, requiredFoam);
    }
}