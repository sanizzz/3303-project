package Drone_subsystem;

import Scheduler.Scheduler;
import gui.SimulationGUI;
import types.DispatchCommand;
import types.DroneStatusUpdate;

import java.util.Map;

/**
 * This is the local drone subsystem used when everything runs in the same program.
 * It still gets commands from the scheduler and sends status updates back.
 */
public class DroneSubsystem implements Runnable {

    private final int droneId;
    private final Scheduler scheduler;
    private final SimulationGUI gui;
    private final DroneExecutionEngine engine;

    public DroneSubsystem(Scheduler scheduler, Map<Integer, Zone> zoneMap, SimulationGUI gui) {
        this(1, scheduler, zoneMap, gui, 1);
    }

    public DroneSubsystem(Scheduler scheduler, Map<Integer, Zone> zoneMap, SimulationGUI gui, int timeScale) {
        this(1, scheduler, zoneMap, gui, timeScale);
    }

    public DroneSubsystem(int droneId, Scheduler scheduler, Map<Integer, Zone> zoneMap, SimulationGUI gui,
            int timeScale) {
        this.droneId = Math.max(1, droneId);
        this.scheduler = scheduler;
        this.gui = gui;
        this.engine = new DroneExecutionEngine(
                this.droneId,
                zoneMap,
                timeScale,
                this::forwardStatus,
                this::log);
    }

    @Override
    public void run() {
        Thread commandReceiver = new Thread(this::receiveCommands, "drone-command-receiver-" + droneId);
        commandReceiver.setDaemon(true);
        commandReceiver.start();

        try {
            engine.run();
        } finally {
            commandReceiver.interrupt();
            try {
                commandReceiver.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void receiveCommands() {
        while (!Thread.currentThread().isInterrupted()) {
            DispatchCommand command = scheduler.getDispatchCommandForDrone(droneId);
            if (command == null) {
                return;
            }
            engine.submitCommand(command);
        }
    }

    /**
     * This sends the drone update back to the scheduler.
     * I keep the scheduler in charge of the GUI updates so all drone info is handled in one place.
     */
    private void forwardStatus(DroneStatusUpdate update) {
        scheduler.sendDroneStatusUpdate(update);
    }

    private void log(String message) {
        System.out.println(message);
        if (gui != null) {
            gui.log(message);
        }
    }
}
