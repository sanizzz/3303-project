package gui;

import Drone_subsystem.Zone;

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
import java.util.Map;

public class SimulationGUI extends JFrame {

    private final MapPanel mapPanel;
    private final JTextArea logArea;
    private final JTextField dronesField;
    private final JTextField capacityField;
    private final JTextField csvField;
    private final JButton startButton;
    private String selectedCsvFile;

    // Iteration 3 status display labels
    private final JLabel droneStateLabel;
    private final JLabel activeFiresLabel;

    public SimulationGUI(Map<Integer, Zone> zoneMap) {
        setTitle("Drone Fire Simulation (Iteration 3)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        dronesField = new JTextField("1", 4);
        capacityField = new JTextField("15", 5);
        csvField = new JTextField(35);
        csvField.setEditable(false);
        startButton = new JButton("Start");

        // Initialize status labels
        droneStateLabel = new JLabel("IDLE");
        droneStateLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        droneStateLabel.setForeground(new Color(0, 100, 0));

        activeFiresLabel = new JLabel("0");
        activeFiresLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        activeFiresLabel.setForeground(new Color(180, 0, 0));

        add(buildTopPanel(), BorderLayout.NORTH);

        mapPanel = new MapPanel(zoneMap);
        add(mapPanel, BorderLayout.CENTER);

        logArea = new JTextArea(10, 80);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Simulation Log"));
        add(scrollPane, BorderLayout.SOUTH);

        setSize(1100, 850);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private JPanel buildTopPanel() {
        JPanel topContainer = new JPanel(new BorderLayout());

        // Row 1: Configuration controls
        JPanel configPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        configPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 8));

        configPanel.add(new JLabel("Drones:"));
        configPanel.add(dronesField);
        configPanel.add(new JLabel("Capacity:"));
        configPanel.add(capacityField);
        configPanel.add(new JLabel("CSV:"));
        configPanel.add(csvField);

        JButton loadButton = new JButton("Load CSV");
        loadButton.addActionListener(e -> chooseCsvFile());
        configPanel.add(loadButton);
        configPanel.add(startButton);

        // Row 2: runtime status display
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 4));
        statusPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));

        statusPanel.add(createStatusBlock("Drone State:", droneStateLabel));
        statusPanel.add(createSeparator());
        statusPanel.add(createStatusBlock("Active Fires:", activeFiresLabel));

        topContainer.add(configPanel, BorderLayout.NORTH);
        topContainer.add(statusPanel, BorderLayout.SOUTH);

        return topContainer;
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

    private void chooseCsvFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Incident CSV");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));

        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            selectedCsvFile = file.getAbsolutePath();
            csvField.setText(selectedCsvFile);
            log("Loaded CSV file: " + selectedCsvFile);
        }
    }

    public void setStartAction(Runnable action) {
        startButton.addActionListener(e -> action.run());
    }

    public String getSelectedCsvFile() {
        return selectedCsvFile;
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

    // ==================== THREAD-SAFE STATUS UPDATES ====================

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

    // ==================== MAP PANEL ====================

    private static class MapPanel extends JPanel {
        private static final int GRID_STEP = 100;
        private static final int PADDING = 24;
        private static final double WORLD_PADDING_RATIO = 0.20;
        private static final int MIN_WORLD_PADDING = GRID_STEP * 2;
        private static final int GRID_LABEL_STEP = GRID_STEP * 2;
        private final Map<Integer, Zone> zoneMap;

        MapPanel(Map<Integer, Zone> zoneMap) {
            this.zoneMap = zoneMap;
            setBackground(Color.WHITE);
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
                String zoneLabel = "Z" + zone.getZoneID();
                if (zState == Zone.ZoneState.ON_FIRE) {
                    zoneLabel += " [FIRE]";
                } else if (zState == Zone.ZoneState.EXTINGUISHED) {
                    zoneLabel += " [DONE]";
                }
                g2.drawString(zoneLabel, drawX + 6, drawY + 16);
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
