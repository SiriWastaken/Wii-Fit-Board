import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;

import java.io.IOException;
import java.util.Arrays;

/**
 * HID access to a Nintendo Wii Balance Board (protocol per WiiBrew, "Wii Balance Board").
 *
 * <p>
 * {@link #open()} finds the board, opens it, selects the sensor report mode and
 * reads the factory calibration; it fails (and releases everything) unless all of
 * that succeeds. {@link #read()} then returns calibrated samples.
 * </p>
 */
public class WiiBoard implements SensorSource {

    /** Nintendo's vendor ID. */
    private static final int VENDOR_ID = 0x057E;

    /** Product ID of the board. The Wii Remote uses the same one, so the name is checked too. */
    private static final int PRODUCT_ID = 0x0306;

    /** Scans for the board this many times (500 ms apart) per open(); the engine does the long-term retrying. */
    private static final int CONNECT_ATTEMPTS = 4;

    private static final int READ_TIMEOUT_MS = 250;

    /** Output report 0x12: set data reporting mode. */
    private static final byte REPORT_SET_MODE = 0x12;

    /** Continuous flag (0x04) so reports keep coming when the load is unchanged; mode 0x32 = buttons + 8 sensor bytes. */
    private static final byte[] MODE_SENSORS_CONTINUOUS = { 0x04, 0x32 };

    private static final int CALIBRATION_ADDRESS = 0x0024;

    private HidServices hidServices;
    private HidDevice board;
    private Calibration calibration;

    @Override
    public void open() throws IOException {
        try {
            connect();
        } catch (IOException e) {
            close();
            throw e;
        } catch (RuntimeException | LinkageError e) {
            close();
            throw new IOException("HID error: " + e, e);
        }
    }

    private void connect() throws IOException {
        hidServices = HidManager.getHidServices();

        // hid4java enumerates in the background, so rescan until the board shows up.
        for (int attempt = 0; attempt < CONNECT_ATTEMPTS && board == null; attempt++) {
            hidServices.scan();
            for (HidDevice device : hidServices.getAttachedHidDevices()) {
                if (isBalanceBoard(device)) {
                    board = device;
                    break;
                }
            }
            if (board == null) {
                pause(500);
            }
        }
        if (board == null) {
            throw new IOException("Board not found (press the red SYNC button, connect it in bluetoothctl)");
        }
        Log.info("Found %s", board.getProduct());

        // Opening can fail transiently right after the board connects, so retry.
        boolean opened = false;
        for (int i = 0; i < 5 && !opened; i++) {
            opened = board.open();
            if (!opened) {
                pause(500);
            }
        }
        if (!opened) {
            throw new IOException("Could not open the board (Linux: hidraw permissions, see README)");
        }

        send(REPORT_SET_MODE, MODE_SENSORS_CONTINUOUS);
        calibration = readCalibration();
    }

    private static boolean isBalanceBoard(HidDevice d) {
        String name = d.getProduct();
        // Only the product name tells a Balance Board from a Wii Remote (same VID/PID).
        return name != null
                ? name.contains("RVL-WBC")
                : d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID;
    }

    private Calibration readCalibration() throws IOException {
        // Read twice and require identical bytes: a garbled read would otherwise skew every weight until reconnect.
        byte[] previous = null;
        for (int round = 0; round < 5; round++) {
            byte[] mem = requestCalibration();
            if (mem != null && Arrays.equals(mem, previous)) {
                try {
                    Calibration c = Calibration.fromBytes(mem);
                    Log.info("Calibration: %s", c);
                    return c;
                } catch (IllegalArgumentException e) {
                    throw new IOException(e.getMessage(), e);
                }
            }
            previous = mem;
        }
        throw new IOException("No consistent calibration data received from the board");
    }

    private byte[] requestCalibration() throws IOException {
        // Enable the extension registers, then read 24 bytes at 0xA40024.
        // Report 0x16 (write memory) must be its full 21 bytes, so pad with zeros.
        send((byte) 0x16, Arrays.copyOf(new byte[] { 0x04, (byte) 0xA4, 0x00, (byte) 0xF0, 0x01, 0x55 }, 21));
        send((byte) 0x16, Arrays.copyOf(new byte[] { 0x04, (byte) 0xA4, 0x00, (byte) 0xFB, 0x01, 0x00 }, 21));
        send((byte) 0x17, new byte[] { 0x04, (byte) 0xA4, 0x00, 0x24, 0x00, Calibration.BYTES });

        // Replies carry up to 16 data bytes each: 16 + 8.
        byte[] mem = new byte[Calibration.BYTES];
        boolean[] seen = new boolean[Calibration.BYTES];
        for (int tries = 0; tries < 50; tries++) {
            Reports.copyMemoryReply(readRaw(), CALIBRATION_ADDRESS, mem, seen);
            boolean complete = true;
            for (boolean s : seen) {
                complete &= s;
            }
            if (complete) {
                return mem;
            }
        }
        return null;
    }

    private void send(byte reportId, byte[] data) throws IOException {
        if (board.write(data, data.length, reportId) < 0) {
            throw new IOException("HID write failed (report 0x" + Integer.toHexString(reportId & 0xFF) + ")");
        }
    }

    /** @return the next report as received, or {@code null} on timeout */
    private byte[] readRaw() throws IOException {
        byte[] buffer = new byte[64];
        int n = board.read(buffer, READ_TIMEOUT_MS);
        if (n < 0) {
            throw new IOException("HID read failed");
        }
        return n == 0 ? null : Arrays.copyOf(buffer, n);
    }

    @Override
    public Sample read() throws IOException {
        if (board == null || calibration == null) {
            throw new IOException("Board is not open");
        }
        int[] raw = Reports.sensors(readRaw());
        return raw == null ? null : new Sample(raw, calibration.toKg(raw));
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    @Override
    public void close() {
        if (board != null) {
            board.close();
            board = null;
        }
        if (hidServices != null) {
            hidServices.shutdown();
            hidServices = null;
        }
        calibration = null;
    }
}
