import java.util.ArrayList;

/**
 * Entry point for the Wii Fit Board scale project.
 *
 * <p>
 * Connects to a Nintendo Wii Balance Board and continuously receives
 * HID reports from the board. The eventual goal is to decode these
 * reports into weight measurements stored in {@link ArrayList}.
 * </p>
 *
 * @author sriganty
 * @version 0.1 - PRE
 */
public class Main {

    /**
     * Starts the Wii Fit Board scale.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║              Wii Fit Board Scale                ║");
        System.out.println("║                   Team 610                      ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();

        // Stores weight measurements as they are decoded.
        ArrayList<Double> weightData = new ArrayList<>();

        // Create the Wii Balance Board interface.
        WiiBoard board = new WiiBoard();

        System.out.println("  Connecting to Wii Balance Board...");

        board.connect();

        // Do not attempt to read from the board if connection failed.
        if (!board.isConnected()) {
            System.out.println();
            System.out.println("  ✗ Could not connect to Wii Balance Board.");
            System.out.println("    Make sure the board is powered on.");
            return;
        }

        System.out.println("  ✓ Wii Balance Board connected!");
        System.out.println();
        System.out.println("  Waiting for sensor data...");
        System.out.println();

        try {
            while (true) {

                // Wait for the next HID input report.
                byte[] report = board.readReport();

                if (report == null || report.length == 0) {
                    continue;
                }

                /*
                 * The Balance Board sends different types of HID reports.
                 *
                 * For now, print the raw report so we can verify exactly
                 * what the board sends before implementing the decoder.
                 */
                System.out.print("  Report: ");

                for (byte value : report) {
                    System.out.printf("%02X ", value & 0xFF);
                }

                System.out.println();
            }

        } finally {
            // Always release the HID device when the program exits.
            board.disconnect();
        }
    }
}
