package Drone_subsystem;

import fire_incident_subsystem.FireRequest;

/**
 * Represents drone hardware model and resource tracking.
 * Parameters are configurable for Iteration 2 experiments.
 */
public class Drone {

    // --- Default Spec Parameters ---
    private static final double DEFAULT_LOAD_CAPACITY = 12.5; // liters
    private static final int DEFAULT_SPEED = 17; // units/sec
    private static final double DEFAULT_FULL_BATTERY = 600.0; // sec of travel budget
    private static final int NOZZLE_OPEN_CLOSE_TIME = 4;
    private static final double ASCEND_DESCEND_SPEED = 2.8;
    private static final int DISCHARGE_RATE = 2; // liters/sec

    private static final int HOME_X = 0;
    private static final int HOME_Y = 0;

    // --- Configurable Runtime Parameters ---
    private static double loadCapacity = DEFAULT_LOAD_CAPACITY;
    private static int speed = DEFAULT_SPEED;
    private static double fullBattery = DEFAULT_FULL_BATTERY;
    private static double totalExtinguishingTime = computeDefaultDropTime(DEFAULT_LOAD_CAPACITY);

    // --- State Variables ---
    public double positionX, positionY;
    private double remainingAgent;
    private double remainingBattery;
    private FireRequest currentEvent;

    public Drone() {
        positionX = HOME_X;
        positionY = HOME_Y;
        currentEvent = null;
        remainingAgent = loadCapacity;
        remainingBattery = fullBattery;
    }

    public static synchronized void configure(double newLoadCapacity, int newSpeed,
            double newFullBattery, double newDropTimeSeconds) {
        if (newLoadCapacity > 0) {
            loadCapacity = newLoadCapacity;
        }
        if (newSpeed > 0) {
            speed = newSpeed;
        }
        if (newFullBattery > 0) {
            fullBattery = newFullBattery;
        }
        if (newDropTimeSeconds > 0) {
            totalExtinguishingTime = newDropTimeSeconds;
        }
    }

    public static synchronized void resetDefaults() {
        loadCapacity = DEFAULT_LOAD_CAPACITY;
        speed = DEFAULT_SPEED;
        fullBattery = DEFAULT_FULL_BATTERY;
        totalExtinguishingTime = computeDefaultDropTime(DEFAULT_LOAD_CAPACITY);
    }

    private static double computeDefaultDropTime(double capacity) {
        return (capacity / DISCHARGE_RATE)
                + (2 * NOZZLE_OPEN_CLOSE_TIME)
                + (2 * ASCEND_DESCEND_SPEED);
    }

    public void setDroneDestination(double x, double y) {
        // No-op in Iteration 2. Mission travel is managed by DroneSubsystem.
    }

    public void refill() {
        remainingAgent = loadCapacity;
        remainingBattery = fullBattery;
        positionX = HOME_X;
        positionY = HOME_Y;
    }

    public double useAgent(double amount) {
        double used = Math.min(amount, remainingAgent);
        remainingAgent -= used;
        if (remainingAgent < 0) {
            remainingAgent = 0;
        }
        return used;
    }

    public double useBattery(double travelTimeSeconds) {
        double used = Math.min(travelTimeSeconds, remainingBattery);
        remainingBattery -= used;
        if (remainingBattery < 0) {
            remainingBattery = 0;
        }
        return used;
    }

    public double distanceTo(double x, double y) {
        double dx = x - positionX;
        double dy = y - positionY;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public double travelTimeTo(double x, double y) {
        return distanceTo(x, y) / speed;
    }

    public double travelTimeToHome() {
        return travelTimeTo(HOME_X, HOME_Y);
    }

    public double getRemainingAgent() {
        return remainingAgent;
    }

    public double getRemainingBattery() {
        return remainingBattery;
    }

    public FireRequest getCurrentEvent() {
        return currentEvent;
    }

    public void setCurrentEvent(FireRequest event) {
        this.currentEvent = event;
    }

    public static synchronized double getLoadCapacity() {
        return loadCapacity;
    }

    public static synchronized int getSpeed() {
        return speed;
    }

    public static synchronized double getFullBattery() {
        return fullBattery;
    }

    public static synchronized double getTotalExtinguishingTime() {
        return totalExtinguishingTime;
    }

    public static int getHomeX() {
        return HOME_X;
    }

    public static int getHomeY() {
        return HOME_Y;
    }

    public void setPosition(double x, double y) {
        this.positionX = x;
        this.positionY = y;
    }
}
