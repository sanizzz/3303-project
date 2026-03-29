package fire_incident_subsystem;

import types.EventType;
import types.FaultType;
import types.Severity;
import java.time.LocalTime;

public class FireRequest {

    private final LocalTime time;
    private final int zoneId;
    private final EventType type;
    private final Severity severity;
    private final FaultType injectedFaultType;
    private final int faultTriggerSeconds;
    private boolean faultPending;

    // --- Fields for Simulation Logic (Used by Drone) ---
    private double requiredFoam; // How much water/foam is needed
    private boolean isResolved; // Is the fire out?
    private boolean inProgress; // Is a drone working on it?

    /**
     * This constructor keeps the older no-fault format working.
     */
    public FireRequest(LocalTime time, int zoneId, EventType type, Severity severity) {
        this(time, zoneId, type, severity, FaultType.NONE, 0);
    }

    /**
     * This constructor stores the fire event and any optional fault from the input file.
     */
    public FireRequest(LocalTime time, int zoneId, EventType type, Severity severity,
            FaultType injectedFaultType, int faultTriggerSeconds) {
        this.time = time;
        this.zoneId = zoneId;
        this.type = type;
        this.severity = severity;
        this.injectedFaultType = injectedFaultType == null ? FaultType.NONE : injectedFaultType;
        this.faultTriggerSeconds = Math.max(0, faultTriggerSeconds);
        this.faultPending = this.injectedFaultType != FaultType.NONE;

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

    /**
     * Returns the fault set on this request.
     */
    public FaultType getInjectedFaultType() {
        return injectedFaultType;
    }

    /**
     * Returns when the fault should happen in simulated seconds.
     */
    public int getFaultTriggerSeconds() {
        return faultTriggerSeconds;
    }

    /**
     * This makes sure the fault is only used once.
     * It is synchronized because the scheduler and drone flow use shared request objects.
     */
    public synchronized FaultType consumePendingFault() {
        if (!faultPending) {
            return FaultType.NONE;
        }
        faultPending = false;
        return injectedFaultType;
    }

    /**
     * This tells us if the request still has a fault waiting to be used.
     */
    public synchronized boolean hasPendingFault() {
        return faultPending;
    }

    // --- Methods for Drone Logic ---

    public double getRequiredFoam() {
        return requiredFoam;
    }

    /**
     * This lowers the amount of foam still needed for the fire.
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
        return String.format("Request[Zone %d | %s | %s | Foam Needed: %.1f | Fault=%s@%ds | Pending=%s]",
                zoneId, type, severity, requiredFoam, injectedFaultType, faultTriggerSeconds, faultPending);
    }
}
