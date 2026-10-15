import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;

import java.util.Arrays;
import java.util.List;

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

    /** Number of times connect() looks for the board before giving up. */
    private static final int CONNECT_ATTEMPTS = 20;

    /** Data report mode: buttons + 4 load-cell values. */
    private static final byte REPORT_WEIGHT = 0x32;

    /** Raw sensor values at 0, 17 and 34 kg for each of the 4 sensors. */
    private int[][] calibration;

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

        if (isConnected) {
            return;
        }

        hidServices = HidManager.getHidServices();

        /*
         * hid4java enumerates devices in the background, so the board may
         * not be listed right after startup. Rescan a few times and match on
         * VID/PID ourselves (getHidDevice() with a null serial can miss it).
         */
        for (int attempt = 0; attempt < CONNECT_ATTEMPTS && board == null; attempt++) {
            hidServices.scan();
            for (HidDevice device : hidServices.getAttachedHidDevices()) {
                String name = device.getProduct();
                if ((device.getVendorId() == VENDOR_ID && device.getProductId() == PRODUCT_ID)
                        || (name != null && name.contains("RVL-WBC"))) {
                    board = device;
                    break;
                }
            }
            if (board == null) {
                sleep(500);
            }
        }

        if (board == null) {
            System.out.println("  ✗ Wii Balance Board not found. Press the red SYNC button"
                    + " (battery bay), pair it in Bluetooth settings, then rerun.");
            return;
        }

        System.out.println("  Found: " + board.getProduct());

        // Opening can fail transiently right after the board connects, so retry.
        boolean opened = false;
        for (int i = 0; i < 5 && !opened; i++) {
            opened = board.open();
            if (!opened) {
                sleep(500);
            }
        }
        if (!opened) {
            System.out.println("  ✗ Could not open Wii Balance Board"
                    + " (macOS: grant Input Monitoring; Linux: hidraw permissions).");
            board = null;
            return;
        }

        isConnected = true;

        // Without this the board only sends status reports, never weights.
        board.write(new byte[] { 0x00, REPORT_WEIGHT }, 2, (byte) 0x12);

        readCalibration();
    }

    /**
     * Reads the factory calibration (0, 17 and 34 kg raw values per sensor)
     * from the board. Falls back to uncalibrated if the board does not answer.
     */
    private void readCalibration() {
        for (int round = 0; round < 3 && calibration == null; round++) {
            requestCalibration();
        }
        if (calibration == null) {
            System.out.println("  ! Calibration not received; weights will be unavailable.");
        }
    }

    private void requestCalibration() {
        // Enable the extension registers, then request 24 bytes at 0xA40024.
        // Report 0x16 (write memory) must be its full 21 bytes, so pad with zeros.
        board.write(Arrays.copyOf(new byte[] { 0x04, (byte) 0xA4, 0x00, (byte) 0xF0, 0x01, 0x55 }, 21), 21, (byte) 0x16);
        board.write(Arrays.copyOf(new byte[] { 0x04, (byte) 0xA4, 0x00, (byte) 0xFB, 0x01, 0x00 }, 21), 21, (byte) 0x16);
        board.write(new byte[] { 0x04, (byte) 0xA4, 0x00, 0x24, 0x00, 0x18 }, 6, (byte) 0x17);

        // Replies (report 0x21) carry up to 16 data bytes each: 16 + 8.
        int[][] cal = new int[3][4];
        int got = 0;
        for (int tries = 0; tries < 50 && got < 24; tries++) {
            byte[] r = readReport();
            if (r == null || r[0] != 0x21 || r.length < 6 + 8) {
                continue;
            }
            int start = r[5] & 0xFF; // low byte of the data's address
            int len = ((r[3] & 0xFF) >> 4) + 1;
            for (int i = 0; i < len && start + i - 0x24 < 24; i++) {
                int idx = start + i - 0x24;
                int v = r[6 + i] & 0xFF;
                // Each value is 16-bit big-endian; groups: 0 kg, 17 kg, 34 kg.
                int word = idx / 2;
                cal[word / 4][word % 4] |= (idx % 2 == 0) ? v << 8 : v;
            }
            got += len;
        }
        if (got >= 24) {
            calibration = cal;
        }
    }

    /**
     * Decodes a raw HID report into total weight in kilograms.
     *
     * @param report bytes of one HID input report (report ID first)
     * @return weight in kg, or {@code null} if the report holds no sensor
     *         data or the board is not calibrated
     */
    public Double decodeWeight(List<Byte> report) {

        if (calibration == null || report.isEmpty()) {
            return null;
        }

        // Offset of the 8 sensor bytes: 0x32/0x34 after buttons, 0x35 after accel too.
        int id = report.get(0) & 0xFF;
        int offset = (id == 0x32 || id == 0x34) ? 3 : (id == 0x35) ? 6 : -1;
        if (offset < 0 || report.size() < offset + 8) {
            return null;
        }

        double total = 0;
        for (int s = 0; s < 4; s++) {
            int raw = ((report.get(offset + 2 * s) & 0xFF) << 8)
                    | (report.get(offset + 2 * s + 1) & 0xFF);
            int c0 = calibration[0][s], c17 = calibration[1][s], c34 = calibration[2][s];
            if (c17 == c0 || c34 == c17) {
                return null; // bad calibration data
            }
            total += raw < c17
                    ? 17.0 * (raw - c0) / (c17 - c0)
                    : 17.0 + 17.0 * (raw - c17) / (c34 - c17);
        }
        return total;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
