package types;

public enum Severity {
    LOW(10.0),      // Spec v2.1: 10L
    MODERATE(20.0), // Spec v2.1: 20L
    HIGH(30.0);     // Spec v2.1: 30L

    private final double litersNeeded;

    Severity(double litersNeeded) {
        this.litersNeeded = litersNeeded;
    }

    public double getLitersNeeded() {
        return litersNeeded;
    }
}
