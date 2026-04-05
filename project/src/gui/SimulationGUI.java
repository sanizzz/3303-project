package gui;

import Drone_subsystem.Zone;
import types.FaultType;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class SimulationGUI extends JFrame {

    private final MapPanel mapPanel;
    private final JTextArea logArea;
    private final JTextField dronesField;
    private final JTextField capacityField;
    private final JTextField csvField;
    private final JTextField zoneCsvField;
    private final JButton startButton;
    private String selectedCsvFile;
    private String selectedZoneCsvFile;

    // Iteration 3 status display labels
    private final JLabel droneStateLabel;
    private final JLabel activeFiresLabel;
    private final JLabel fleetSummaryLabel;
    private final JPanel droneFleetPanel;
    private final JPanel zonePanel;
    private final Map<Integer, JLabel> droneStatusById;
    private final Map<Integer, JLabel> zoneStatusById;

    public SimulationGUI(Map<Integer, Zone> zoneMap) {
        this(zoneMap, null);
    }

    public SimulationGUI(Map<Integer, Zone> zoneMap, String initialZoneCsvPath) {
        setTitle("Drone Fire Simulation (Iteration 5 - Capacity, Rerouting, Metrics)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        dronesField = new JTextField("10", 4);
        dronesField.setEditable(false);
        capacityField = new JTextField("15", 5);
        csvField = new JTextField(35);
        csvField.setEditable(false);
        zoneCsvField = new JTextField(35);
        zoneCsvField.setEditable(false);
        startButton = new JButton("Start");
        selectedZoneCsvFile = initialZoneCsvPath;
        if (initialZoneCsvPath != null) {
            zoneCsvField.setText(initialZoneCsvPath);
        }

        // Initialize status labels
        droneStateLabel = new JLabel("IDLE");
        droneStateLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        droneStateLabel.setForeground(new Color(0, 100, 0));

        activeFiresLabel = new JLabel("0");
        activeFiresLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        activeFiresLabel.setForeground(new Color(180, 0, 0));

        fleetSummaryLabel = new JLabel("Healthy: 10 | Faulty: 0 | Ready Queue: 0");
        fleetSummaryLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        fleetSummaryLabel.setForeground(new Color(50, 50, 50));

        droneFleetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        droneFleetPanel.setBorder(BorderFactory.createTitledBorder("Drone Fleet Status"));
        droneStatusById = new LinkedHashMap<>();
        zonePanel = new JPanel();
        zonePanel.setLayout(new javax.swing.BoxLayout(zonePanel, javax.swing.BoxLayout.Y_AXIS));
        zoneStatusById = new LinkedHashMap<>();

        add(buildTopPanel(), BorderLayout.NORTH);

        mapPanel = new MapPanel(zoneMap);
        add(mapPanel, BorderLayout.CENTER);
        add(buildSidePanel(zoneMap), BorderLayout.EAST);

        logArea = new JTextArea(10, 80);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Simulation Log"));
        add(scrollPane, BorderLayout.SOUTH);

        setSize(1100, 850);
        setLocationRelativeTo(null);
        setVisible(true);
        setConfiguredDroneCount(10);
    }

    /**
     * This builds the top part of the GUI.
     * I added the drone fleet panel here so faults are easy to see during the demo.
     */
    private JPanel buildTopPanel() {
        JPanel topContainer = new JPanel();
        topContainer.setLayout(new javax.swing.BoxLayout(topContainer, javax.swing.BoxLayout.Y_AXIS));

        // Row 1: Configuration controls
        JPanel configPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        configPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 8));

        configPanel.add(new JLabel("Fleet:"));
        configPanel.add(dronesField);
        configPanel.add(new JLabel("Capacity:"));
        configPanel.add(capacityField);
        configPanel.add(new JLabel("Events:"));
        configPanel.add(csvField);

        JButton loadButton = new JButton("Load Events");
        loadButton.addActionListener(e -> chooseIncidentCsvFile());
        configPanel.add(loadButton);
        configPanel.add(new JLabel("Zones:"));
        configPanel.add(zoneCsvField);

        JButton loadZoneButton = new JButton("Load Zones");
        loadZoneButton.addActionListener(e -> chooseZoneCsvFile());
        configPanel.add(loadZoneButton);
        configPanel.add(startButton);

        // Row 2: runtime status display
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 4));
        statusPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));

        statusPanel.add(createStatusBlock("Drone State:", droneStateLabel));
        statusPanel.add(createSeparator());
        statusPanel.add(createStatusBlock("Active Fires:", activeFiresLabel));
        statusPanel.add(createSeparator());
        statusPanel.add(createStatusBlock("Fleet:", fleetSummaryLabel));

        topContainer.add(configPanel);
        topContainer.add(statusPanel);
        topContainer.add(droneFleetPanel);

        return topContainer;
    }

    /**
     * This creates the right-hand zone status panel so every zone can be monitored alongside
     * the map without overwhelming the top status strip.
     */
    private JPanel buildSidePanel(Map<Integer, Zone> zoneMap) {
        JPanel sidePanel = new JPanel(new BorderLayout(6, 6));
        sidePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

        JPanel zoneContainer = new JPanel(new BorderLayout());
        zoneContainer.setBorder(BorderFactory.createTitledBorder("Zone Status"));
        JScrollPane zoneScroll = new JScrollPane(zonePanel);
        zoneScroll.setPreferredSize(new Dimension(330, 0));
        zoneContainer.add(zoneScroll, BorderLayout.CENTER);

        for (Zone zone : zoneMap.values()) {
            ensureZoneLabel(zone.getZoneID());
        }

        sidePanel.add(zoneContainer, BorderLayout.CENTER);
        return sidePanel;
    }

    private JPanel createStatusBlock(String labelText, JLabel valueLabel) {
        JPanel block = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JLabel title = new JLabel(labelText);
        title.setFont(new Font("SansSerif", Font.PLAIN, 13));
        block.add(title);
        block.add(valueLabel);
        return block;
    }

    private JPanel createSeparator() {
        JPanel sep = new JPanel();
        sep.setPreferredSize(new Dimension(2, 20));
        sep.setBackground(Color.LIGHT_GRAY);
        return sep;
    }

    private void chooseIncidentCsvFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Incident CSV");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));

        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            selectedCsvFile = file.getAbsolutePath();
            csvField.setText(selectedCsvFile);
            log("Loaded incident CSV file: " + selectedCsvFile);
        }
    }

    private void chooseZoneCsvFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Zone CSV");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));

        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            selectedZoneCsvFile = file.getAbsolutePath();
            zoneCsvField.setText(selectedZoneCsvFile);
            log("Loaded zone CSV file: " + selectedZoneCsvFile);
        }
    }

    public void setStartAction(Runnable action) {
        startButton.addActionListener(e -> action.run());
    }

    public String getSelectedCsvFile() {
        return selectedCsvFile;
    }

    public String getSelectedZoneCsvFile() {
        return selectedZoneCsvFile;
    }

    public int getNumDrones() {
        try {
            return Integer.parseInt(dronesField.getText().trim());
        } catch (Exception ex) {
            log("Invalid Drones value. Using default 1.");
            return 1;
        }
    }

    public double getCapacity() {
        try {
            return Double.parseDouble(capacityField.getText().trim());
        } catch (Exception ex) {
            log("Invalid Capacity value. Using default 15.");
            return 15.0;
        }
    }

    public void refresh() {
        mapPanel.repaint();
    }

    /**
     * This replaces the zone map before the simulation starts so the GUI can render whichever
     * zone CSV the user selected at launch time.
     */
    public void setZoneMap(Map<Integer, Zone> zoneMap) {
        Runnable task = () -> {
            mapPanel.setZoneMap(zoneMap);
            zoneStatusById.clear();
            zonePanel.removeAll();
            for (Zone zone : zoneMap.values()) {
                ensureZoneLabel(zone.getZoneID());
            }
            zonePanel.revalidate();
            zonePanel.repaint();
            refresh();
        };

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    // ==================== THREAD-SAFE STATUS UPDATES ====================

    /**
     * This updates the number of drone status labels shown on the screen.
     * It is thread-safe because scheduler updates do not always come from the Swing thread.
     */
    public void setConfiguredDroneCount(int count) {
        Runnable task = () -> {
            int safeCount = Math.max(1, count);
            for (int droneId = 1; droneId <= safeCount; droneId++) {
                ensureDroneLabel(droneId);
            }

            droneStatusById.entrySet().removeIf(entry -> entry.getKey() > safeCount);
            droneFleetPanel.removeAll();
            for (int droneId = 1; droneId <= safeCount; droneId++) {
                droneFleetPanel.add(droneStatusById.get(droneId));
            }
            droneFleetPanel.revalidate();
            droneFleetPanel.repaint();
        };

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * This updates one drone label with its current state and fault.
     * The colors match the assignment requirement for different faults.
     */
    public void updateDroneStatus(int droneId, String state, FaultType faultType, String note) {
        updateDroneTelemetry(droneId, state, faultType, note, 0.0, 0.0, 0.0, 0.0, -1);
    }

    /**
     * This updates the full live telemetry line for one drone and mirrors the same data onto
     * the map panel so position, resources, and fault state stay in sync.
     */
    public void updateDroneTelemetry(int droneId, String state, FaultType faultType, String note,
            double remainingAgent, double remainingBattery, double positionX, double positionY, int zoneId) {
        Runnable task = () -> {
            JLabel label = ensureDroneLabel(droneId);
            FaultType safeFault = faultType == null ? FaultType.NONE : faultType;
            String safeNote = (note == null || note.isBlank()) ? "Ready" : note;
            String zoneText = zoneId > 0 ? ("Zone " + zoneId) : "Zone -";
            label.setText(String.format(
                    "Drone %d | %s | %s | Agent %.1fL | Battery %.1fs | Pos (%.1f, %.1f) | %s",
                    droneId,
                    state,
                    zoneText,
                    remainingAgent,
                    remainingBattery,
                    positionX,
                    positionY,
                    safeNote));
            applyDroneLabelColors(label, state, safeFault);
            mapPanel.updateDroneTelemetry(droneId,
                    new DroneTelemetry(state, safeFault, remainingAgent, remainingBattery, positionX, positionY));
        };

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * This updates one zone status row and mirrors the same fire severity/remaining-load data
     * to the map so the text panel and map always match.
     */
    public void updateZoneStatus(int zoneId, String zoneState, String severity, double remainingFoam) {
        Runnable task = () -> {
            JLabel label = ensureZoneLabel(zoneId);
            label.setText(String.format("Zone %d | %s | Severity %s | Remaining %.1fL",
                    zoneId, zoneState, severity, remainingFoam));
            label.setForeground("ON_FIRE".equals(zoneState) ? new Color(180, 0, 0) : new Color(40, 40, 40));
            mapPanel.updateZoneTelemetry(zoneId, new ZoneTelemetry(zoneState, severity, remainingFoam));
        };

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * This updates the fleet health summary so the GUI clearly distinguishes normal drones
     * from faulty ones during marking.
     */
    public void updateFleetSummary(int healthyCount, int faultyCount, int readyQueueCount) {
        Runnable task = () -> fleetSummaryLabel.setText(String.format(
                "Healthy: %d | Faulty: %d | Ready Queue: %d",
                healthyCount,
                faultyCount,
                readyQueueCount));

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * This creates a label for a drone if it does not already exist.
     */
    private JLabel ensureDroneLabel(int droneId) {
        JLabel label = droneStatusById.get(droneId);
        if (label != null) {
            return label;
        }

        JLabel created = new JLabel("Drone " + droneId + " | IDLE | Ready");
        created.setOpaque(true);
        created.setFont(new Font("SansSerif", Font.BOLD, 12));
        created.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(180, 180, 180)),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        applyDroneLabelColors(created, "IDLE", FaultType.NONE);
        droneStatusById.put(droneId, created);
        return created;
    }

    /**
     * This creates a zone label if the side panel has not seen that zone before.
     */
    private JLabel ensureZoneLabel(int zoneId) {
        JLabel label = zoneStatusById.get(zoneId);
        if (label != null) {
            return label;
        }

        JLabel created = new JLabel(String.format("Zone %d | IDLE | Severity NONE | Remaining 0.0L", zoneId));
        created.setOpaque(true);
        created.setFont(new Font("SansSerif", Font.PLAIN, 12));
        created.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        created.setBackground(Color.WHITE);
        zoneStatusById.put(zoneId, created);
        zonePanel.add(created);
        zonePanel.revalidate();
        zonePanel.repaint();
        return created;
    }

    /**
     * This sets the colour of the drone label based on its state or fault.
     */
    private void applyDroneLabelColors(JLabel label, String state, FaultType faultType) {
        if (faultType == FaultType.NOZZLE_JAMMED) {
            label.setBackground(new Color(198, 40, 40));
            label.setForeground(Color.WHITE);
            return;
        }
        if (faultType == FaultType.STUCK_MID_FLIGHT) {
            label.setBackground(new Color(245, 124, 0));
            label.setForeground(Color.WHITE);
            return;
        }
        if (faultType == FaultType.PACKET_LOSS) {
            label.setBackground(new Color(253, 216, 53));
            label.setForeground(Color.BLACK);
            return;
        }

        if ("OFFLINE".equals(state)) {
            label.setBackground(new Color(198, 40, 40));
            label.setForeground(Color.WHITE);
        } else if ("RESETTING".equals(state)) {
            label.setBackground(new Color(255, 183, 77));
            label.setForeground(Color.BLACK);
        } else if ("EN ROUTE".equals(state)) {
            label.setBackground(new Color(187, 222, 251));
            label.setForeground(Color.BLACK);
        } else if ("DROPPING AGENT".equals(state)) {
            label.setBackground(new Color(255, 224, 178));
            label.setForeground(Color.BLACK);
        } else if ("RETURNING".equals(state)) {
            label.setBackground(new Color(225, 190, 231));
            label.setForeground(Color.BLACK);
        } else {
            label.setBackground(new Color(200, 230, 201));
            label.setForeground(Color.BLACK);
        }
    }

    /**
     * Updates the drone state display label. Thread-safe.
     */
    public void setDroneState(String state) {
        Runnable task = () -> {
            droneStateLabel.setText(state);
            // Color-code the state
            switch (state) {
                case "IDLE":
                    droneStateLabel.setForeground(new Color(0, 100, 0));
                    break;
                case "EN ROUTE":
                    droneStateLabel.setForeground(new Color(0, 0, 180));
                    break;
                case "DROPPING AGENT":
                    droneStateLabel.setForeground(new Color(200, 120, 0));
                    break;
                case "RETURNING":
                    droneStateLabel.setForeground(new Color(128, 0, 128));
                    break;
                case "RESETTING":
                    droneStateLabel.setForeground(new Color(230, 120, 0));
                    break;
                case "OFFLINE":
                    droneStateLabel.setForeground(new Color(180, 0, 0));
                    break;
                default:
                    droneStateLabel.setForeground(Color.DARK_GRAY);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * Updates the active fires count display. Thread-safe.
     */
    public void setActiveFires(int count) {
        Runnable task = () -> {
            activeFiresLabel.setText(String.valueOf(count));
            activeFiresLabel.setForeground(count > 0 ? new Color(180, 0, 0) : new Color(0, 100, 0));
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    // ==================== LOGGING ====================

    public void log(String message) {
        Runnable addLogTask = () -> {
            logArea.append(message + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        };

        if (SwingUtilities.isEventDispatchThread()) {
            addLogTask.run();
        } else {
            SwingUtilities.invokeLater(addLogTask);
        }
    }

    /**
     * This immutable view-model carries one drone's latest scheduler snapshot into the map.
     */
    private static class DroneTelemetry {
        private final String state;
        private final FaultType faultType;
        private final double remainingAgent;
        private final double remainingBattery;
        private final double positionX;
        private final double positionY;

        private DroneTelemetry(String state, FaultType faultType, double remainingAgent,
                double remainingBattery, double positionX, double positionY) {
            this.state = state;
            this.faultType = faultType;
            this.remainingAgent = remainingAgent;
            this.remainingBattery = remainingBattery;
            this.positionX = positionX;
            this.positionY = positionY;
        }
    }

    /**
     * This immutable view-model carries one zone's fire severity and remaining load into the map.
     */
    private static class ZoneTelemetry {
        private final String state;
        private final String severity;
        private final double remainingFoam;

        private ZoneTelemetry(String state, String severity, double remainingFoam) {
            this.state = state;
            this.severity = severity;
            this.remainingFoam = remainingFoam;
        }
    }

    // ==================== MAP PANEL ====================

    private static class MapPanel extends JPanel {
        private static final int GRID_STEP = 100;
        private static final int PADDING = 24;
        private static final double WORLD_PADDING_RATIO = 0.20;
        private static final int MIN_WORLD_PADDING = GRID_STEP * 2;
        private static final int GRID_LABEL_STEP = GRID_STEP * 2;
        private Map<Integer, Zone> zoneMap;
        private final Map<Integer, DroneTelemetry> droneTelemetryById;
        private final Map<Integer, ZoneTelemetry> zoneTelemetryById;

        MapPanel(Map<Integer, Zone> zoneMap) {
            this.zoneMap = zoneMap;
            this.droneTelemetryById = new LinkedHashMap<>();
            this.zoneTelemetryById = new LinkedHashMap<>();
            setBackground(Color.WHITE);
        }

        /**
         * This swaps in a new zone map before startup so the renderer matches the selected
         * zone CSV without rebuilding the whole frame.
         */
        void setZoneMap(Map<Integer, Zone> zoneMap) {
            this.zoneMap = zoneMap;
            zoneTelemetryById.clear();
            repaint();
        }

        /**
         * This stores the latest drone telemetry so the map can draw live drone markers.
         */
        void updateDroneTelemetry(int droneId, DroneTelemetry telemetry) {
            droneTelemetryById.put(droneId, telemetry);
            repaint();
        }

        /**
         * This stores the latest zone telemetry so the map can show severity and remaining load.
         */
        void updateZoneTelemetry(int zoneId, ZoneTelemetry telemetry) {
            zoneTelemetryById.put(zoneId, telemetry);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                WorldBounds bounds = computeWorldBounds();

                int innerWidth = Math.max(1, getWidth() - (2 * PADDING));
                int innerHeight = Math.max(1, getHeight() - (2 * PADDING));
                double scale = Math.min(innerWidth / (double) bounds.getWidth(),
                        innerHeight / (double) bounds.getHeight());

                int mapWidth = (int) Math.round(bounds.getWidth() * scale);
                int mapHeight = (int) Math.round(bounds.getHeight() * scale);
                int offsetX = (getWidth() - mapWidth) / 2;
                int offsetY = (getHeight() - mapHeight) / 2;

                drawGrid(g2, bounds, scale, offsetX, offsetY, mapWidth, mapHeight);
                drawZones(g2, bounds, scale, offsetX, offsetY);
                drawBase(g2, bounds, scale, offsetX, offsetY);
                drawDrones(g2, bounds, scale, offsetX, offsetY);
            } finally {
                g2.dispose();
            }
        }

        private void drawGrid(Graphics2D g2, WorldBounds bounds, double scale, int offsetX, int offsetY,
                int mapWidth, int mapHeight) {
            g2.setColor(new Color(245, 245, 245));
            g2.fillRect(offsetX, offsetY, mapWidth, mapHeight);

            g2.setColor(new Color(220, 220, 220));
            g2.setStroke(new BasicStroke(1f));

            for (int x = bounds.minX; x <= bounds.maxX; x += GRID_STEP) {
                int drawX = offsetX + (int) Math.round((x - bounds.minX) * scale);
                g2.drawLine(drawX, offsetY, drawX, offsetY + mapHeight);

                if ((x - bounds.minX) % GRID_LABEL_STEP == 0) {
                    g2.setColor(Color.GRAY);
                    g2.drawString(Integer.toString(x), drawX + 2, offsetY + 14);
                    g2.setColor(new Color(220, 220, 220));
                }
            }
            for (int y = bounds.minY; y <= bounds.maxY; y += GRID_STEP) {
                int drawY = offsetY + (int) Math.round((y - bounds.minY) * scale);
                g2.drawLine(offsetX, drawY, offsetX + mapWidth, drawY);

                if ((y - bounds.minY) % GRID_LABEL_STEP == 0) {
                    g2.setColor(Color.GRAY);
                    g2.drawString(Integer.toString(y), offsetX + 4, drawY - 2);
                    g2.setColor(new Color(220, 220, 220));
                }
            }

            g2.setColor(Color.GRAY);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(offsetX, offsetY, mapWidth, mapHeight);
        }

        private void drawZones(Graphics2D g2, WorldBounds bounds, double scale, int offsetX, int offsetY) {
            g2.setFont(new Font("SansSerif", Font.BOLD, 12));

            for (Zone zone : zoneMap.values()) {
                int zoneMinX = Math.min(zone.startX, zone.endX);
                int zoneMaxX = Math.max(zone.startX, zone.endX);
                int zoneMinY = Math.min(zone.startY, zone.endY);
                int zoneMaxY = Math.max(zone.startY, zone.endY);

                int drawX = offsetX + (int) Math.round((zoneMinX - bounds.minX) * scale);
                int drawY = offsetY + (int) Math.round((zoneMinY - bounds.minY) * scale);
                int drawW = Math.max(1, (int) Math.round((zoneMaxX - zoneMinX) * scale));
                int drawH = Math.max(1, (int) Math.round((zoneMaxY - zoneMinY) * scale));

                // Color-code zones by state
                Zone.ZoneState zState = zone.getZoneState();
                switch (zState) {
                    case ON_FIRE:
                        g2.setColor(new Color(255, 200, 200));
                        break;
                    case EXTINGUISHED:
                        g2.setColor(new Color(200, 255, 200));
                        break;
                    default:
                        g2.setColor(new Color(245, 245, 245));
                }
                g2.fillRect(drawX, drawY, drawW, drawH);

                g2.setColor(Color.GRAY);
                g2.setStroke(new BasicStroke(2f));
                g2.drawRect(drawX, drawY, drawW, drawH);

                g2.setColor(Color.DARK_GRAY);
                ZoneTelemetry telemetry = zoneTelemetryById.get(zone.getZoneID());
                String severity = telemetry == null ? "NONE" : telemetry.severity;
                double remainingFoam = telemetry == null ? 0.0 : telemetry.remainingFoam;
                g2.drawString("Z" + zone.getZoneID() + " | " + severity, drawX + 6, drawY + 16);
                g2.drawString(String.format("%s | %.1fL",
                        zState.name(),
                        remainingFoam), drawX + 6, drawY + 32);
            }
        }

        /**
         * This draws the home base at the origin so return-to-base and refill behaviour is visible.
         */
        private void drawBase(Graphics2D g2, WorldBounds bounds, double scale, int offsetX, int offsetY) {
            int drawX = offsetX + (int) Math.round((0 - bounds.minX) * scale);
            int drawY = offsetY + (int) Math.round((0 - bounds.minY) * scale);
            g2.setColor(new Color(33, 150, 243));
            g2.fillOval(drawX - 8, drawY - 8, 16, 16);
            g2.setColor(Color.WHITE);
            g2.drawString("B", drawX - 4, drawY + 4);
        }

        /**
         * This draws every drone on the map using a different colour for faulty versus normal
         * drones so collision avoidance, rerouting, and failures are visible to the marker.
         */
        private void drawDrones(Graphics2D g2, WorldBounds bounds, double scale, int offsetX, int offsetY) {
            g2.setFont(new Font("SansSerif", Font.BOLD, 11));
            for (Map.Entry<Integer, DroneTelemetry> entry : droneTelemetryById.entrySet()) {
                DroneTelemetry telemetry = entry.getValue();
                int droneId = entry.getKey();
                int drawX = offsetX + (int) Math.round((telemetry.positionX - bounds.minX) * scale);
                int drawY = offsetY + (int) Math.round((telemetry.positionY - bounds.minY) * scale);

                boolean faulty = telemetry.faultType != null && telemetry.faultType != FaultType.NONE;
                if ("OFFLINE".equals(telemetry.state)) {
                    faulty = true;
                }

                g2.setColor(faulty ? new Color(198, 40, 40) : new Color(46, 125, 50));
                g2.fillOval(drawX - 7, drawY - 7, 14, 14);
                g2.setColor(Color.BLACK);
                g2.drawString(String.format("D%d %.1fL %.0fs",
                        droneId,
                        telemetry.remainingAgent,
                        telemetry.remainingBattery), drawX + 10, drawY - 6);
                g2.drawString(telemetry.state, drawX + 10, drawY + 8);
            }
        }

        private WorldBounds computeWorldBounds() {
            int minX = 0;
            int minY = 0;
            int maxX = GRID_STEP;
            int maxY = GRID_STEP;
            boolean hasZones = false;

            for (Zone zone : zoneMap.values()) {
                int zoneMinX = Math.min(zone.startX, zone.endX);
                int zoneMaxX = Math.max(zone.startX, zone.endX);
                int zoneMinY = Math.min(zone.startY, zone.endY);
                int zoneMaxY = Math.max(zone.startY, zone.endY);

                if (!hasZones) {
                    minX = zoneMinX;
                    maxX = zoneMaxX;
                    minY = zoneMinY;
                    maxY = zoneMaxY;
                    hasZones = true;
                } else {
                    minX = Math.min(minX, zoneMinX);
                    maxX = Math.max(maxX, zoneMaxX);
                    minY = Math.min(minY, zoneMinY);
                    maxY = Math.max(maxY, zoneMaxY);
                }
            }

            int width = Math.max(1, maxX - minX);
            int height = Math.max(1, maxY - minY);
            int padX = Math.max(MIN_WORLD_PADDING, (int) Math.ceil(width * WORLD_PADDING_RATIO));
            int padY = Math.max(MIN_WORLD_PADDING, (int) Math.ceil(height * WORLD_PADDING_RATIO));

            int worldMinX = floorToStep(minX - padX, GRID_STEP);
            int worldMaxX = ceilToStep(maxX + padX, GRID_STEP);
            int worldMinY = floorToStep(minY - padY, GRID_STEP);
            int worldMaxY = ceilToStep(maxY + padY, GRID_STEP);

            return new WorldBounds(worldMinX, worldMaxX, worldMinY, worldMaxY);
        }

        private int floorToStep(int value, int step) {
            return Math.floorDiv(value, step) * step;
        }

        private int ceilToStep(int value, int step) {
            return -Math.floorDiv(-value, step) * step;
        }

        private static class WorldBounds {
            private final int minX;
            private final int maxX;
            private final int minY;
            private final int maxY;

            private WorldBounds(int minX, int maxX, int minY, int maxY) {
                this.minX = minX;
                this.maxX = maxX;
                this.minY = minY;
                this.maxY = maxY;
            }

            private int getWidth() {
                return Math.max(1, maxX - minX);
            }

            private int getHeight() {
                return Math.max(1, maxY - minY);
            }
        }
    }
}
