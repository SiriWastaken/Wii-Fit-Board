import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * A Nintendo Wii Balance Board: connection, protocol, calibration and the weighing cycle.
 *
 * <p>
 * Protocol per WiiBrew, "Wii Balance Board". Three separate operations:
 * </p>
 * <ul>
 * <li><b>Calibration</b> ({@link #readCalibration}): the board's factory raw values at 0/17/34 kg
 * per sensor. Read once per Bluetooth connection, then reused for every weighing.</li>
 * <li><b>Tare</b> ({@link #tare}): the empty-board baseline in kg. Taken when connected and the
 * board is quiet and unloaded; afterwards it only follows slow drift while empty.</li>
 * <li><b>Measurement reset</b> ({@link #resetMeasurement}): clears the last result when the person
 * steps off. Touches neither calibration nor baseline.</li>
 * </ul>
 *
 * <p>
 * One thread must call {@link #poll()} in a loop; it owns the HID device, reconnects with
 * backoff when the link is genuinely lost, and runs the weighing cycle. The getters may be
 * read from any thread.
 * </p>
 */
public class WiiBoard {

    /** OFFLINE, then ZEROING until the empty baseline is set; READY, MEASURING, DONE repeat per person. */
    public enum State { OFFLINE, ZEROING, READY, MEASURING, DONE, FAILED }

    private static final int VENDOR_ID = 0x057E;
    /** The Wii Remote has the same VID/PID; only the product name tells them apart. */
    private static final int PRODUCT_ID = 0x0306;

    private static final byte REPORT_SET_MODE = 0x12;
    /**
     * Report mode 0x32 = buttons + 8 sensor bytes. First byte 0x00 = send on change only (the
     * setting verified on the test board); 0x04 would request continuous reports.
     */
    private static final byte[] MODE_SENSORS = { 0x00, 0x32 };
    private static final int CALIBRATION_ADDRESS = 0x0024;
    private static final int CALIBRATION_BYTES = 24;
    private static final int SENSORS = 4;

    private static final int READ_TIMEOUT_MS = 100;
    private static final int SCANS = 4;

    /** No report of any kind for this long = the link is lost. A short gap is not a disconnect. */
    static final long LOST_MS = 10_000;
    static final long RETRY_MIN_MS = 1_000;
    static final long RETRY_MAX_MS = 30_000;
    /** A connection must deliver reports this long before the retry delay is reset. */
    private static final long HEALTHY_MS = 30_000;

    // Weighing, kg and ms. Untuned starting values.
    static final double ENTER_KG = 5.0; // step-on: load above baseline
    static final double EXIT_KG = 2.5; // step-off, lower than ENTER_KG so the state cannot chatter
    static final long STABLE_MS = 1500; // window the reading must be steady over
    static final double STABLE_STD_KG = 0.15;
    static final long MIN_MEASURE_MS = 1000; // shortest time from step-on to result
    static final long TIMEOUT_MS = 12_000; // give up if the person never settles
    static final long ZERO_MS = 1000;
    static final double ZERO_STD_KG = 0.2;
    static final double MAX_BASELINE_KG = 3.0; // an "empty" board reading beyond this is not empty
    static final double DRIFT_ALPHA = 0.002;
    static final double MIN_KG = -5, MAX_KG = 200;
    private static final int MEDIAN = 5;
    private static final int MIN_STABLE_SAMPLES = 10;
    private static final double MIN_WINDOW_FILL = 0.9;

    private static final boolean DEBUG = Boolean.getBoolean("scale.debug");

    private HidServices hidServices;
    private HidDevice device;

    private boolean connected;
    int[][] calibration; // [0 = 0 kg, 1 = 17 kg, 2 = 34 kg][sensor]; set once per connection
    private long connectedAtMs, lastReportMs, nextAttemptMs;
    private long retryDelayMs = RETRY_MIN_MS;

    private volatile State state = State.OFFLINE;
    private volatile String status = "Starting";
    private volatile double liveKg;
    private volatile double resultKg = Double.NaN;

    private double baselineKg;
    private double heldKg; // latest filtered total; reused when no new report arrives (board sends on change)
    private boolean haveSample;
    private final double[] recent = new double[MEDIAN];
    private int recentCount;
    private final ArrayDeque<double[]> window = new ArrayDeque<>(); // {timeMs, kg}
    private long measureStartMs;

    // --debug counters
    private long reports, dropped, calibrationReads;
    private long rateStartMs;
    private int rateCount;

    public State state() {
        return state;
    }

    /** Human-readable status or instruction for the current state. */
    public String status() {
        return status;
    }

    /** Filtered load above the empty-board baseline, kg. */
    public double liveKg() {
        return liveKg;
    }

    /** Accepted weight for the person currently on the board, kg, or NaN. */
    public double resultKg() {
        return resultKg;
    }

    // ---------------------------------------------------------------- polling

    /** Runs one step (up to ~100 ms): reconnect if needed, otherwise read and process one report. */
    public void poll() {
        if (!connected) {
            if (now() < nextAttemptMs) {
                pause(READ_TIMEOUT_MS);
            } else if (!connect()) {
                nextAttemptMs = now() + retryDelayMs;
                log("Retry in %d s", retryDelayMs / 1000);
                retryDelayMs = Math.min(retryDelayMs * 2, RETRY_MAX_MS);
            }
            return;
        }
        try {
            byte[] report = readRaw();
            long t = now();
            if (report != null) {
                lastReportMs = t; // any report proves the link is alive, even a non-sensor one
                if (t - connectedAtMs > HEALTHY_MS) {
                    retryDelayMs = RETRY_MIN_MS;
                }
                int[] raw = sensors(report);
                if (raw != null) {
                    accept(raw, t);
                }
            } else if (t - lastReportMs > LOST_MS) {
                lose("no reports for " + (t - lastReportMs) / 1000 + " s");
                return;
            }
            if (haveSample) {
                step(t, heldKg);
            }
        } catch (RuntimeException e) {
            lose(e.toString());
        }
    }

    /** Closes the HID device. Call once when the application exits. */
    public void disconnect() {
        connected = false;
        state = State.OFFLINE;
        closeDevice();
    }

    // ------------------------------------------------------------- connection

    /** Opens and initializes the board once. @return whether it is now ready to read */
    boolean connect() {
        status = "Connecting to the board";
        try {
            String error = openDevice();
            if (error == null && !write(REPORT_SET_MODE, MODE_SENSORS)) {
                error = "could not set the report mode";
            }
            if (error == null) {
                calibration = readCalibration();
                if (calibration == null) {
                    error = "no consistent calibration data from the board";
                }
            }
            if (error != null) {
                fail(error);
                return false;
            }
        } catch (RuntimeException | LinkageError e) {
            fail(e.toString());
            return false;
        }
        connected = true;
        connectedAtMs = lastReportMs = now();
        baselineKg = 0;
        haveSample = false;
        recentCount = 0;
        window.clear();
        liveKg = 0;
        resultKg = Double.NaN;
        state = State.ZEROING;
        status = "Zeroing: keep the board empty";
        log("Connected, calibration %s", Arrays.deepToString(calibration));
        return true;
    }

    private void fail(String why) {
        closeDevice();
        calibration = null;
        state = State.OFFLINE;
        status = "Not connected: " + why;
        log("Connect failed: %s", why);
    }

    private void lose(String why) {
        log("Connection lost: %s", why);
        closeDevice();
        connected = false;
        state = State.OFFLINE;
        status = "Connection lost, reconnecting";
        resultKg = Double.NaN;
        liveKg = 0;
        nextAttemptMs = now() + retryDelayMs;
        retryDelayMs = Math.min(retryDelayMs * 2, RETRY_MAX_MS);
    }

    /** Finds and opens the board. @return null on success, otherwise why it failed */
    String openDevice() {
        hidServices = HidManager.getHidServices();
        // hid4java enumerates in the background, so rescan until the board shows up.
        for (int i = 0; i < SCANS && device == null; i++) {
            hidServices.scan();
            for (HidDevice d : hidServices.getAttachedHidDevices()) {
                String name = d.getProduct();
                if (name != null ? name.contains("RVL-WBC")
                        : d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID) {
                    device = d;
                    break;
                }
            }
            if (device == null) {
                pause(500);
            }
        }
        if (device == null) {
            return "board not found (press the red SYNC button, connect it in bluetoothctl)";
        }
        // Opening can fail briefly right after the board connects, so retry.
        for (int i = 0; i < 5; i++) {
            if (device.open()) {
                return null;
            }
            pause(500);
        }
        return "could not open the board (Linux: hidraw permissions, see README)";
    }

    void closeDevice() {
        if (device != null) {
            device.close();
            device = null;
        }
        if (hidServices != null) {
            hidServices.shutdown();
            hidServices = null;
        }
    }

    boolean write(byte reportId, byte[] data) {
        return device.write(data, data.length, reportId) >= 0;
    }

    /** @return the next report as received, or {@code null} if none arrived within the timeout */
    byte[] readRaw() {
        byte[] buffer = new byte[64];
        int n = device.read(buffer, READ_TIMEOUT_MS);
        if (n < 0) {
            pause(50); // read error: don't spin; LOST_MS decides whether the link is really gone
            return null;
        }
        return n == 0 ? null : Arrays.copyOf(buffer, n);
    }

    long now() {
        return System.nanoTime() / 1_000_000;
    }

    void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ calibration

    /**
     * Reads the factory calibration. Done twice and compared, so one garbled read cannot skew every weight.
     *
     * @return the validated calibration, or null
     */
    int[][] readCalibration() {
        byte[] previous = null;
        for (int round = 0; round < 5; round++) {
            byte[] mem = requestCalibration();
            if (mem != null && Arrays.equals(mem, previous)) {
                return parseCalibration(mem);
            }
            previous = mem;
        }
        return null;
    }

    private byte[] requestCalibration() {
        calibrationReads++;
        // Enable the extension registers, then read 24 bytes at 0xA40024.
        // Report 0x16 (write memory) must be its full 21 bytes, so pad with zeros.
        if (!write((byte) 0x16, Arrays.copyOf(new byte[] { 0x04, (byte) 0xA4, 0x00, (byte) 0xF0, 0x01, 0x55 }, 21))
                || !write((byte) 0x16, Arrays.copyOf(new byte[] { 0x04, (byte) 0xA4, 0x00, (byte) 0xFB, 0x01, 0x00 }, 21))
                || !write((byte) 0x17, new byte[] { 0x04, (byte) 0xA4, 0x00, 0x24, 0x00, CALIBRATION_BYTES })) {
            return null;
        }
        // Replies carry up to 16 data bytes each: 16 + 8.
        byte[] mem = new byte[CALIBRATION_BYTES];
        boolean[] seen = new boolean[CALIBRATION_BYTES];
        for (int tries = 0; tries < 50; tries++) {
            copyMemoryReply(readRaw(), mem, seen);
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

    /**
     * Copies the data of a memory-read reply (0x21: ID, 2 button bytes, size/error, 16-bit address, data)
     * into {@code mem}, where {@code mem[0]} is address {@link #CALIBRATION_ADDRESS}.
     */
    static void copyMemoryReply(byte[] r, byte[] mem, boolean[] seen) {
        if (r == null || r.length < 6 || (r[0] & 0xFF) != 0x21 || (r[3] & 0x0F) != 0) {
            return;
        }
        int len = ((r[3] & 0xF0) >> 4) + 1;
        int addr = ((r[4] & 0xFF) << 8) | (r[5] & 0xFF);
        for (int i = 0; i < len && 6 + i < r.length; i++) {
            int idx = addr + i - CALIBRATION_ADDRESS;
            if (idx >= 0 && idx < mem.length) {
                mem[idx] = r[6 + i];
                seen[idx] = true;
            }
        }
    }

    /**
     * Three groups (0, 17, 34 kg) of four big-endian 16-bit raw values.
     *
     * @return the calibration, or null if it is not strictly increasing for every sensor
     */
    static int[][] parseCalibration(byte[] b) {
        if (b == null || b.length < CALIBRATION_BYTES) {
            return null;
        }
        int[][] p = new int[3][SENSORS];
        for (int i = 0; i < 12; i++) {
            p[i / SENSORS][i % SENSORS] = ((b[2 * i] & 0xFF) << 8) | (b[2 * i + 1] & 0xFF);
        }
        for (int s = 0; s < SENSORS; s++) {
            if (!(p[0][s] < p[1][s] && p[1][s] < p[2][s])) {
                return null;
            }
        }
        return p;
    }

    // ----------------------------------------------------------- sensor math

    /**
     * Extracts the four raw load-cell values (big-endian 16-bit; top-right, bottom-right, top-left,
     * bottom-left) from a data report, or returns null if it is not a sensor report.
     */
    static int[] sensors(byte[] r) {
        if (r == null || r.length == 0) {
            return null;
        }
        // 0x32/0x34: report ID + 2 button bytes before the sensors; 0x35 adds 3 accelerometer bytes.
        int id = r[0] & 0xFF;
        int offset = (id == 0x32 || id == 0x34) ? 3 : (id == 0x35) ? 6 : -1;
        if (offset < 0 || r.length < offset + 2 * SENSORS) {
            return null;
        }
        int[] raw = new int[SENSORS];
        for (int s = 0; s < SENSORS; s++) {
            raw[s] = ((r[offset + 2 * s] & 0xFF) << 8) | (r[offset + 2 * s + 1] & 0xFF);
        }
        return raw;
    }

    /** Total load in kg: each sensor interpolated between its 0/17/34 kg calibration points, then summed. */
    static double totalKg(int[][] cal, int[] raw) {
        double total = 0;
        for (int s = 0; s < SENSORS; s++) {
            int c0 = cal[0][s], c17 = cal[1][s], c34 = cal[2][s];
            total += raw[s] < c17
                    ? 17.0 * (raw[s] - c0) / (c17 - c0)
                    : 17.0 + 17.0 * (raw[s] - c17) / (c34 - c17);
        }
        return total;
    }

    private void accept(int[] raw, long t) {
        double total = totalKg(calibration, raw);
        reports++;
        if (DEBUG) {
            rateCount++;
            if (t - rateStartMs >= 1000) {
                log("%.1f Hz reports=%d dropped=%d calReads=%d state=%s raw=%s total=%.3f held=%.3f baseline=%.3f live=%.3f",
                        rateCount * 1000.0 / (t - rateStartMs), reports, dropped, calibrationReads, state,
                        Arrays.toString(raw), total, heldKg, baselineKg, liveKg);
                rateCount = 0;
                rateStartMs = t;
            }
        }
        if (Double.isNaN(total) || total < MIN_KG) {
            dropped++;
            return;
        }
        if (total > MAX_KG) {
            dropped++;
            if (state != State.ZEROING) {
                state = State.FAILED;
                status = "Over capacity";
                resultKg = Double.NaN;
                heldKg = total; // keeps FAILED until a normal reading arrives; not added to the median
                haveSample = true;
            }
            return;
        }
        // Median of the last few totals removes single-report spikes without lagging real changes.
        recent[recentCount++ % MEDIAN] = total;
        double[] sorted = Arrays.copyOf(recent, Math.min(recentCount, MEDIAN));
        Arrays.sort(sorted);
        heldKg = sorted[sorted.length / 2];
        haveSample = true;
    }

    // -------------------------------------------------------------- weighing

    /** Advances the weighing cycle with the latest filtered total (not baseline-corrected). */
    void step(long t, double kg) {
        window.addLast(new double[] { t, kg });
        long keep = state == State.ZEROING ? ZERO_MS : STABLE_MS;
        while (window.getFirst()[0] < t - keep) {
            window.removeFirst();
        }
        double net = kg - baselineKg;
        if (state != State.ZEROING) {
            liveKg = net;
        }
        switch (state) {
            case ZEROING -> tare();
            case READY -> {
                if (net >= ENTER_KG) {
                    state = State.MEASURING;
                    status = "Hold still";
                    measureStartMs = t;
                    window.clear();
                    window.addLast(new double[] { t, kg });
                } else if (Math.abs(net) < EXIT_KG) {
                    baselineKg += DRIFT_ALPHA * net; // follow slow drift of the empty board
                }
            }
            case MEASURING -> {
                if (net < EXIT_KG) {
                    log("Stepped off before the reading was stable");
                    resetMeasurement();
                } else if (t - measureStartMs >= TIMEOUT_MS) {
                    log("Not stable after %d s (std %.3f kg)", (t - measureStartMs) / 1000, stats()[1]);
                    state = State.FAILED;
                    status = "Couldn't settle: step off and try again";
                } else if (isStable(t)) {
                    resultKg = Math.max(0, stats()[0] - baselineKg);
                    state = State.DONE;
                    status = "Done: step off to weigh again";
                    log("Measurement: %.2f kg", resultKg);
                }
            }
            case DONE, FAILED -> {
                if (net < EXIT_KG) {
                    resetMeasurement();
                }
            }
            default -> { }
        }
    }

    /** Sets the empty-board baseline once the board has been quiet and near zero for ZERO_MS. */
    private void tare() {
        double[] s = stats();
        if (s[2] < MIN_WINDOW_FILL * ZERO_MS) {
            return;
        }
        if (Math.abs(s[0]) > MAX_BASELINE_KG) {
            status = "Step off the board to zero it";
        } else if (s[1] > ZERO_STD_KG) {
            status = "Zeroing: keep the board still";
        } else {
            baselineKg = s[0];
            log("Zeroed, baseline %.3f kg", baselineKg);
            resetMeasurement();
        }
    }

    /** Clears the previous person's result and waits for the next step-on. Keeps calibration and baseline. */
    void resetMeasurement() {
        state = State.READY;
        status = "Step on the board";
        resultKg = Double.NaN;
        window.clear();
    }

    private boolean isStable(long t) {
        double[] s = stats();
        return window.size() >= MIN_STABLE_SAMPLES
                && t - measureStartMs >= MIN_MEASURE_MS
                && s[2] >= MIN_WINDOW_FILL * STABLE_MS
                && s[1] <= STABLE_STD_KG;
    }

    /** @return {mean kg, standard deviation kg, time span ms} of the current window */
    private double[] stats() {
        double sum = 0;
        for (double[] p : window) {
            sum += p[1];
        }
        double mean = sum / window.size();
        double sq = 0;
        for (double[] p : window) {
            sq += (p[1] - mean) * (p[1] - mean);
        }
        return new double[] { mean, Math.sqrt(sq / window.size()), window.getLast()[0] - window.getFirst()[0] };
    }

    private static void log(String format, Object... args) {
        System.err.printf("%tT %s%n", System.currentTimeMillis(), String.format(format, args));
    }
}
