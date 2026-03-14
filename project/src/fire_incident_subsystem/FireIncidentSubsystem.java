package fire_incident_subsystem;

import Scheduler.Scheduler;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import gui.SimulationGUI;
import types.EventType;
import types.Severity;
import types.UdpConfig;
import types.UdpUtil;

import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.DatagramSocket;
import java.time.LocalTime;

/**
 * Reads fire events from CSV and submits them to the Scheduler.
 * In UDP mode the subsystem waits for completion acknowledgements after all events are sent.
 */
public class FireIncidentSubsystem implements Runnable {

    private final Scheduler scheduler;
    private final SimulationGUI gui;
    private volatile String csvFile;
    private final int timeScale;
    private int totalEventsSent = 0;
    private final boolean useUdp;
    private final String schedulerHost;
    private final int schedulerPort;
    private final int completionPort;

    public FireIncidentSubsystem(Scheduler scheduler, SimulationGUI gui) {
        this(scheduler, gui, null, 1, false, "localhost", UdpConfig.FIRE_TO_SCHED_PORT, UdpConfig.SCHED_TO_FIRE_PORT);
    }

    public FireIncidentSubsystem(Scheduler scheduler, SimulationGUI gui, String csvFile) {
        this(scheduler, gui, csvFile, 1, false, "localhost", UdpConfig.FIRE_TO_SCHED_PORT, UdpConfig.SCHED_TO_FIRE_PORT);
    }

    public FireIncidentSubsystem(Scheduler scheduler, SimulationGUI gui, String csvFile, int timeScale) {
        this(scheduler, gui, csvFile, timeScale, false, "localhost", UdpConfig.FIRE_TO_SCHED_PORT,
                UdpConfig.SCHED_TO_FIRE_PORT);
    }

    public FireIncidentSubsystem(Scheduler scheduler, SimulationGUI gui, String csvFile, int timeScale, boolean useUdp,
            String schedulerHost, int schedulerPort, int completionPort) {
        this.scheduler = scheduler;
        this.gui = gui;
        this.csvFile = csvFile;
        this.timeScale = Math.max(1, timeScale);
        this.useUdp = useUdp;
        this.schedulerHost = schedulerHost;
        this.schedulerPort = schedulerPort;
        this.completionPort = completionPort;
    }

    public void setCsvFile(String filename) {
        this.csvFile = filename;
    }

    public void setCsvPath(String csvPath) {
        setCsvFile(csvPath);
    }

    @Override
    public void run() {
        log("[Fire] Subsystem started. Time scale x" + timeScale);

        String currentPath = csvFile;
        if (currentPath == null || currentPath.isBlank()) {
            log("[Fire] No incident CSV selected. Set csv file before starting Fire subsystem.");
            return;
        }

        try {
            InputStream in = FireIncidentSubsystem.class.getClassLoader().getResourceAsStream(currentPath);
            Reader sourceReader = (in != null) ? new InputStreamReader(in) : new FileReader(currentPath);
            LocalTime previousTime = null;

            try (Reader reader = sourceReader;
                    CSVReader csvReader = new CSVReaderBuilder(reader).withSkipLines(1).build()) {
                String[] row;
                while ((row = csvReader.readNext()) != null) {
                    LocalTime time = LocalTime.parse(row[0].trim());
                    int zoneId = Integer.parseInt(row[1].trim());
                    EventType type = EventType.valueOf(row[2].trim().toUpperCase());
                    Severity severity = Severity.valueOf(row[3].trim().toUpperCase());

                    if (previousTime != null) {
                        long delaySeconds = java.time.Duration.between(previousTime, time).getSeconds();
                        if (delaySeconds > 0) {
                            long delayMs = (delaySeconds * 1000) / timeScale;
                            log(String.format("[Fire] Waiting %.1fs (scaled) before next event...", delayMs / 1000.0));
                            Thread.sleep(Math.max(1, delayMs));
                        }
                    }
                    previousTime = time;

                    FireRequest req = new FireRequest(time, zoneId, type, severity);
                    log(String.format("[Fire] Fire detected at %s in Zone %d (%s severity)", time, zoneId, severity));
                    if (useUdp) {
                        String payload = String.format("REQ|%s|%d|%s|%s", time, zoneId, type, severity);
                        UdpUtil.send(schedulerHost, schedulerPort, payload);
                    } else {
                        scheduler.putRequest(req);
                    }
                    totalEventsSent++;
                }
            }

            log("[Fire] All " + totalEventsSent + " fire events submitted to Scheduler.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("[Fire] Interrupted while sending events.");
            return;
        } catch (Exception e) {
            log("[Fire] Error reading CSV: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        int completionsReceived = 0;
        if (useUdp) {
            try (DatagramSocket socket = new DatagramSocket(completionPort)) {
                while (completionsReceived < totalEventsSent && !Thread.currentThread().isInterrupted()) {
                    String msg = UdpUtil.receive(socket);
                    if (msg.startsWith("COMP|")) {
                        completionsReceived++;
                        log("[Fire] " + describeCompletionMessage(msg, completionsReceived, totalEventsSent));
                    }
                }
            } catch (Exception e) {
                log("[Fire] UDP completion listener error: " + e.getMessage());
            }
        } else {
            while (completionsReceived < totalEventsSent && !Thread.currentThread().isInterrupted()) {
                FireRequest ack = scheduler.getCompletion();
                if (ack == null) {
                    break;
                }
                completionsReceived++;
                if (ack.isResolved()) {
                    log(String.format("[Fire] Confirmation received: Zone %d fire extinguished. (%d/%d)",
                            ack.getZoneId(), completionsReceived, totalEventsSent));
                } else {
                    log(String.format("[Fire] Zone %d fire not resolved. (%d/%d)",
                            ack.getZoneId(), completionsReceived, totalEventsSent));
                }
            }
        }

        log("[Fire] All missions acknowledged. Fire subsystem complete.");
    }

    private void log(String message) {
        System.out.println(message);
        if (gui != null) {
            gui.log(message);
        }
    }

    private String describeCompletionMessage(String msg, int receivedCount, int expectedCount) {
        String[] parts = msg.split("\\|");
        if (parts.length >= 3) {
            return String.format("Completion received: Zone %s resolved=%s (%d/%d)",
                    parts[1], parts[2], receivedCount, expectedCount);
        }
        return "Completion received via UDP: " + msg;
    }
}
