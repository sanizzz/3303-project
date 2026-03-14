package Drone_subsystem;

import Scheduler.Scheduler;
import gui.SimulationGUI;
import types.DispatchCommand;
import types.DroneStatusUpdate;

import java.util.Map;

/**
 * In-process drone subsystem used by GUI mode.
 * Commands are still consumed through the Scheduler queue, but execution supports reroutes while travelling.
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

    private void forwardStatus(DroneStatusUpdate update) {
        scheduler.sendDroneStatusUpdate(update);
        if (gui != null) {
            gui.setDroneState(update.getDroneState().toString());
        }
    }

    private void log(String message) {
        System.out.println(message);
        if (gui != null) {
            gui.log(message);
        }
    }
}
