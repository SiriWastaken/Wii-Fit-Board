import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;

/**
 * Represents a Nintendo Wii Balance Board connected through HID.
 *
 * <p>
 * This class is responsible for finding, opening, reading from, and
 * closing the Balance Board. Decoding the raw sensor reports into
 * weight measurements will be implemented separately.
 * </p>
 *
 * <p>
 * The Wii Balance Board uses Nintendo's USB vendor ID (VID) and
 * Balance Board product ID (PID) to identify the device.
 * </p>
 *
 * @author FRC Team 610
 */
public class WiiBoard {

    /**
     * Nintendo's USB vendor ID.
     */
    private static final int VENDOR_ID = 0x057E;

    /**
     * Wii Balance Board's product ID.
     */
    private static final int PRODUCT_ID = 0x0306;

    /**
     * hid4java service manager.
     */
    private HidServices hidServices;

    /**
     * HID device representing the Wii Balance Board.
     */
    private HidDevice board;

    /**
     * Whether the board is currently connected and open.
     */
    private boolean isConnected;

    /**
     * Creates a Wii Balance Board interface.
     */
    public WiiBoard() {
        isConnected = false;
    }

    /**
     * Searches for the Wii Balance Board and opens it.
     *
     * <p>
     * The board is identified using its USB vendor ID and product ID.
     * If the board cannot be found or opened, {@link #isConnected}
     * remains false.
     * </p>
     */
    public void connect() {

        hidServices = HidManager.getHidServices();

        // Search for the Wii Balance Board using its VID and PID.
        board = hidServices.getHidDevice(
                VENDOR_ID,
                PRODUCT_ID,
                null);

        if (board == null) {
            System.out.println("  ✗ Wii Balance Board not found.");
            return;
        }

        System.out.println("  Found: " + board.getProduct());

        // Open the HID device so reports can be read.
        if (!board.open()) {
            System.out.println("  ✗ Could not open Wii Balance Board.");
            board = null;
            return;
        }

        isConnected = true;
    }

    /**
     * Reads the next HID input report from the Balance Board.
     *
     * <p>
     * This method currently returns the raw bytes exactly as received.
     * The report decoder will eventually convert these bytes into the
     * four load-cell measurements used to calculate weight.
     * </p>
     *
     * @return raw HID report, or {@code null} if no report was received
     */
    public byte[] readReport() {

        if (!isConnected || board == null) {
            throw new IllegalStateException("Board is not connected.");
        }

        /*
         * HID reports from the Balance Board are small, so a 64-byte
         * buffer is more than sufficient for the reports we expect.
         */
        byte[] buffer = new byte[64];

        // Wait up to one second for an input report.
        int bytesRead = board.read(buffer, 1000);

        if (bytesRead <= 0) {
            return null;
        }

        /*
         * The buffer may be larger than the actual report, so return
         * only the bytes that were actually received.
         */
        byte[] report = new byte[bytesRead];

        System.arraycopy(
                buffer,
                0,
                report,
                0,
                bytesRead);

        return report;
    }

    /**
     * Closes the Wii Balance Board and releases hid4java resources.
     */
    public void disconnect() {

        if (board != null) {
            board.close();
            board = null;
        }

        if (hidServices != null) {
            hidServices.shutdown();
            hidServices = null;
        }

        isConnected = false;
    }

    /**
     * Checks whether the Wii Balance Board is currently connected.
     *
     * @return {@code true} if the board is connected and open
     */
    public boolean isConnected() {
        return isConnected;
    }
}
