package fire_incident_subsystem;

import types.UdpConfig;

/**
 * Separate-process launcher for Fire Incident subsystem (UDP mode).
 */
public class FireIncidentSubsystemMain {

    public static void main(String[] args) {
        String csv = getArg(args, "--csv", "sampleData/test_mixed_scenario.csv");
        String schedulerHost = getArg(args, "--schedulerHost", "localhost");
        int schedulerPort = parseIntArg(args, "--schedulerPort", UdpConfig.FIRE_TO_SCHED_PORT);
        int completionPort = parseIntArg(args, "--completionPort", UdpConfig.SCHED_TO_FIRE_PORT);
        int timeScale = parseIntArg(args, "--timeScale", 1);

        FireIncidentSubsystem fire = new FireIncidentSubsystem(
                null,
                null,
                csv,
                timeScale,
                true,
                schedulerHost,
                schedulerPort,
                completionPort);

        System.out.println("[FireMain] Starting with CSV: " + csv);
        fire.run();
    }

    private static String getArg(String[] args, String key, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equalsIgnoreCase(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }

    private static int parseIntArg(String[] args, String key, int def) {
        try {
            return Integer.parseInt(getArg(args, key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }
}
