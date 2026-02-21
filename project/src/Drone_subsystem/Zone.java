package Drone_subsystem;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;

import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Zone {

    // Enum used by GUI later
    public enum ZoneState {
        IDLE, ON_FIRE, EXTINGUISHED
    }

    public static final int FIRE_SIZE = 30;

    // Coordinates (Public final so they can be read but not changed)
    public final int startX, endX;
    public final int startY, endY;

    private final int zoneID;
    private volatile ZoneState zoneState;

    /**
     * Constructor using String inputs from the CSV
     * Input Examples: "1", "(0;0)", "(700;600)"
     */
    public Zone(String zoneID, String start, String end) {
        // 1. Parse ID
        this.zoneID = Integer.parseInt(zoneID.trim());

        // 2. Parse Start "(0;0)" -> Remove parens -> Split
        start = start.trim().substring(1, start.length() - 1);
        String[] startVals = start.split(";");
        this.startX = Integer.parseInt(startVals[0]);
        this.startY = Integer.parseInt(startVals[1]);

        // 3. Parse End "(700;600)"
        end = end.trim().substring(1, end.length() - 1);
        String[] endVals = end.split(";");
        this.endX = Integer.parseInt(endVals[0]);
        this.endY = Integer.parseInt(endVals[1]);

        this.zoneState = ZoneState.IDLE;
    }

    // --- STATIC LOADER (Call this from Main) ---
    public static Map<Integer, Zone> loadZones(String filename) {
        Map<Integer, Zone> zoneMap = new HashMap<>();
        try (CSVReader reader = new CSVReaderBuilder(new FileReader(filename)).withSkipLines(1).build()) {
            List<String[]> rows = reader.readAll();
            for (String[] row : rows) {
                // Ensure row has at least 3 columns: ID, Start, End
                if (row.length >= 3) {
                    try {
                        Zone newZone = new Zone(row[0], row[1], row[2]);
                        zoneMap.put(newZone.getZoneID(), newZone);
                    } catch (RuntimeException ex) {
                        System.err.println("[Zone] Skipping malformed row: " + String.join(",", row));
                    }
                }
            }
            System.out.println("[Zone] Loaded " + zoneMap.size() + " zones.");
        } catch (IOException | CsvException e) {
            System.err.println("[Zone] Error loading map: " + e.getMessage());
            e.printStackTrace();
        }
        return zoneMap;
    }

    // --- METHODS REQUIRED BY DRONE.JAVA ---

    // Preferred naming for center coordinates in Iteration 1 docs.
    public int getMiddleX() {
        return startX + (this.endX - this.startX) / 2;
    }

    public int getMiddleY() {
        return startY + (this.endY - this.startY) / 2;
    }

    // Backward-compatible aliases.
    public int getX() {
        return getMiddleX();
    }

    public int getY() {
        return getMiddleY();
    }

    // --- Standard Getters ---
    public int getZoneID() {
        return this.zoneID;
    }

    public ZoneState getZoneState() {
        return this.zoneState;
    }

    public void setZoneState(ZoneState state) {
        this.zoneState = state;
    }

    @Override
    public String toString() {
        return "Zone " + zoneID + " [" + startX + "," + startY + " to " + endX + "," + endY + "]";
    }
}
