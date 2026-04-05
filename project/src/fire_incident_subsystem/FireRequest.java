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
    private long detectedAtNs = -1L;
    private long extinguishedAtNs = -1L;

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

    /**
     * This records when the incident first entered the system for metrics collection.
     * It only writes once so the same request can safely pass through several subsystems.
     */
    public synchronized void markDetectedAtIfUnset(long timestampNs) {
        if (detectedAtNs < 0L) {
            detectedAtNs = timestampNs;
        }
    }

    /**
     * This records when the incident was fully extinguished.
     * It only writes once so duplicate completions cannot skew the measurement.
     */
    public synchronized void markExtinguishedAtIfUnset(long timestampNs) {
        if (extinguishedAtNs < 0L) {
            extinguishedAtNs = timestampNs;
        }
    }

    /**
     * This exposes the end-to-end incident handling time required by the Iteration 5 metrics.
     * It returns -1 until the request has both timestamps.
     */
    public synchronized double getDetectionToExtinguishmentSeconds() {
        if (detectedAtNs < 0L || extinguishedAtNs < 0L || extinguishedAtNs < detectedAtNs) {
            return -1.0;
        }
        return (extinguishedAtNs - detectedAtNs) / 1_000_000_000.0;
    }

    // --- Methods for Drone Logic ---

    /**
     * This returns the fire-level remaining agent requirement.
     * It is synchronized because several mission segments can now report against the same fire.
     */
    public synchronized double getRequiredFoam() {
        return requiredFoam;
    }

    /**
     * This lowers the amount of foam still needed for the fire.
     */
    public synchronized void updateRequiredFoam(double amount) {
        this.requiredFoam -= amount;
        if (this.requiredFoam < 0) {
            this.requiredFoam = 0;
        }
    }

    /**
     * This exposes the shared completion flag across scheduler, drones, and metrics runs.
     */
    public synchronized boolean isResolved() {
        return isResolved;
    }

    /**
     * This changes the resolved flag under the request monitor so concurrent status/completion
     * updates cannot race with GUI or metrics reads.
     */
    public synchronized void setResolved(boolean resolved) {
        this.isResolved = resolved;
    }

    /**
     * This tracks whether the fire still has any active work associated with it.
     */
    public synchronized void setInProgress(boolean inProgress) {
        this.inProgress = inProgress;
    }

    /**
     * This lets the scheduler decide whether a zone still has active drone work attached to it.
     */
    public synchronized boolean isInProgress() {
        return inProgress;
    }

    @Override
    public String toString() {
        return String.format("Request[Zone %d | %s | %s | Foam Needed: %.1f | Fault=%s@%ds | Pending=%s]",
                zoneId, type, severity, getRequiredFoam(), injectedFaultType, faultTriggerSeconds, faultPending);
    }
}
