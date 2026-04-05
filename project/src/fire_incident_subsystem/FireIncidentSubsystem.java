package fire_incident_subsystem;

import Scheduler.Scheduler;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import gui.SimulationGUI;
import types.EventType;
import types.FaultType;
import types.LogUtil;
import types.Severity;
import types.UdpConfig;
import types.UdpUtil;

import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.DatagramSocket;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * This class reads the fire events from the CSV file and sends them to the scheduler.
 * In UDP mode it also waits until all completions come back.
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
    private final List<FireRequest> submittedRequests = new ArrayList<>();

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

    /**
     * This is the main loop for the fire incident subsystem.
     * It acts like the producer by creating requests and sending them at the right time.
     */
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
                    FireRequest req = parseRequestRow(row);
                    LocalTime time = req.getTime();

                    if (previousTime != null) {
                        long delaySeconds = java.time.Duration.between(previousTime, time).getSeconds();
                        if (delaySeconds > 0) {
                            long delayMs = (delaySeconds * 1000) / timeScale;
                            log(String.format("[Fire] Waiting %.1fs (scaled) before next event...", delayMs / 1000.0));
                            Thread.sleep(Math.max(1, delayMs));
                        }
                    }
                    previousTime = time;

                    // I log the event right when it is created so it is easier to match the
                    // fire subsystem output with the scheduler and drone output.
                    log(String.format("[Fire] Fire detected at %s in Zone %d (%s severity, fault=%s @ %ds)",
                            req.getTime(),
                            req.getZoneId(),
                            req.getSeverity(),
                            req.getInjectedFaultType(),
                            req.getFaultTriggerSeconds()));
                    req.markDetectedAtIfUnset(System.nanoTime());
                    recordSubmittedRequest(req);
                    if (useUdp) {
                        String payload = buildRequestPayload(req);
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
                        markCompletedRequestFromUdp(msg);
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

    /**
     * This reads one row from the CSV file.
     * The older 4-column format still works, and the last 2 fault columns are optional.
     */
    private FireRequest parseRequestRow(String[] row) {
        LocalTime time = LocalTime.parse(row[0].trim());
        int zoneId = Integer.parseInt(row[1].trim());
        EventType type = EventType.valueOf(row[2].trim().toUpperCase());
        Severity severity = Severity.valueOf(row[3].trim().toUpperCase());
        FaultType faultType = row.length >= 5 ? FaultType.fromText(row[4]) : FaultType.NONE;
        int faultTriggerSeconds = row.length >= 6 && !row[5].isBlank() ? Integer.parseInt(row[5].trim()) : 0;
        return new FireRequest(time, zoneId, type, severity, faultType, faultTriggerSeconds);
    }

    /**
     * This builds the UDP request message.
     * If there is no fault, it keeps the old simpler format.
     */
    private String buildRequestPayload(FireRequest req) {
        if (req.getInjectedFaultType() == FaultType.NONE) {
            return String.format("REQ|%s|%d|%s|%s",
                    req.getTime(), req.getZoneId(), req.getType(), req.getSeverity());
        }

        return String.format("REQ|%s|%d|%s|%s|%s|%d",
                req.getTime(),
                req.getZoneId(),
                req.getType(),
                req.getSeverity(),
                req.getInjectedFaultType().name(),
                req.getFaultTriggerSeconds());
    }

    /**
     * This sends one timestamped log message to the console and GUI.
     */
    private void log(String message) {
        String stamped = LogUtil.stamp(message);
        System.out.println(stamped);
        if (gui != null) {
            gui.log(stamped);
        }
    }

    /**
     * This turns the completion message into a readable log line.
     */
    private String describeCompletionMessage(String msg, int receivedCount, int expectedCount) {
        String[] parts = msg.split("\\|");
        if (parts.length >= 3) {
            return String.format("Completion received: Zone %s resolved=%s (%d/%d)",
                    parts[1], parts[2], receivedCount, expectedCount);
        }
        return "Completion received via UDP: " + msg;
    }

    private synchronized void recordSubmittedRequest(FireRequest request) {
        submittedRequests.add(request);
    }

    /**
     * This reports the average end-to-end latency from incident detection to extinguishment
     * across the requests seen by this subsystem during one run.
     */
    public synchronized double getAverageDetectionToExtinguishmentSeconds() {
        double total = 0.0;
        int measuredCount = 0;
        for (FireRequest request : submittedRequests) {
            double latencySeconds = request.getDetectionToExtinguishmentSeconds();
            if (latencySeconds >= 0.0) {
                total += latencySeconds;
                measuredCount++;
            }
        }
        return measuredCount == 0 ? 0.0 : total / measuredCount;
    }

    private synchronized void markCompletedRequestFromUdp(String msg) {
        String[] parts = msg.split("\\|");
        if (parts.length < 3) {
            return;
        }

        boolean resolved = Boolean.parseBoolean(parts[2]);
        if (!resolved) {
            return;
        }

        int zoneId;
        try {
            zoneId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return;
        }

        for (FireRequest request : submittedRequests) {
            if (request.getZoneId() == zoneId && request.getDetectionToExtinguishmentSeconds() < 0.0) {
                request.markExtinguishedAtIfUnset(System.nanoTime());
                return;
            }
        }
    }
}
