import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Hardware-free tests (no JUnit dependency). Run with ./test.sh.
 * These prove the decoding and measurement logic, NOT Bluetooth/HID behavior.
 */
public class Tests {

    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        calibration();
        reports();
        config();
        tracker();
        engine();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- helpers ----

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    private static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    // ---- calibration and report decoding ----

    /** Four sensors: 0 kg at 1000+10s, 17 kg at 2700+10s, 34 kg at 4400+10s (big-endian). */
    private static byte[] calBytes() {
        byte[] b = new byte[24];
        for (int i = 0; i < 12; i++) {
            int v = 1000 + (i / 4) * 1700 + (i % 4) * 10;
            b[2 * i] = (byte) (v >> 8);
            b[2 * i + 1] = (byte) v;
        }
        return b;
    }

    private static void calibration() {
        Calibration c = Calibration.fromBytes(calBytes());
        double[] kg = c.toKg(new int[] { 1000, 1010, 1020, 1030 });
        check("0 kg raw -> 0 kg", near(kg[0] + kg[1] + kg[2] + kg[3], 0, 1e-9));
        kg = c.toKg(new int[] { 2700, 2710, 2720, 2730 });
        check("17 kg raw -> 17 kg each", near(kg[0], 17, 1e-9) && near(kg[3], 17, 1e-9));
        kg = c.toKg(new int[] { 4400, 4410, 4420, 4430 });
        check("34 kg raw -> 34 kg each", near(kg[2], 34, 1e-9));
        kg = c.toKg(new int[] { 1850, 1860, 1870, 1880 });
        check("midpoint interpolates", near(kg[0], 8.5, 1e-9));
        kg = c.toKg(new int[] { 900, 910, 920, 930 });
        check("below zero point extrapolates negative", kg[0] < 0);
        check("short calibration rejected", throwsIae(() -> Calibration.fromBytes(new byte[23])));
        byte[] bad = calBytes();
        bad[8] = bad[0]; // 17 kg point of sensor 0 equals its 0 kg point
        bad[9] = bad[1];
        check("non-increasing calibration rejected", throwsIae(() -> Calibration.fromBytes(bad)));
    }

    private static void reports() {
        byte[] r32 = { 0x32, 0, 0, 0x03, (byte) 0xE8, 0x00, 0x10, (byte) 0xFF, (byte) 0xFF, 0x01, 0x00 };
        int[] raw = Reports.sensors(r32);
        check("0x32 decodes big-endian", raw != null && raw[0] == 1000 && raw[1] == 16 && raw[2] == 65535 && raw[3] == 256);
        byte[] r35 = new byte[6 + 8 + 10];
        r35[0] = 0x35;
        r35[6] = 0x12;
        r35[7] = 0x34;
        check("0x35 skips accelerometer bytes", Reports.sensors(r35)[0] == 0x1234);
        check("short report rejected", Reports.sensors(new byte[] { 0x32, 0, 0, 1, 2 }) == null);
        check("status report is not a sensor report", Reports.sensors(new byte[] { 0x20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }) == null);
        check("empty/null rejected", Reports.sensors(new byte[0]) == null && Reports.sensors(null) == null);

        // Memory reply assembly: 16 bytes at 0x24, then 8 at 0x34.
        byte[] cal = calBytes();
        byte[] mem = new byte[24];
        boolean[] seen = new boolean[24];
        byte[] a = new byte[6 + 16];
        a[0] = 0x21;
        a[3] = (byte) (15 << 4);
        a[5] = 0x24;
        System.arraycopy(cal, 0, a, 6, 16);
        byte[] b = new byte[6 + 8];
        b[0] = 0x21;
        b[3] = (byte) (7 << 4);
        b[5] = 0x34;
        System.arraycopy(cal, 16, b, 6, 8);
        check("memory replies accepted", Reports.copyMemoryReply(a, 0x24, mem, seen) && Reports.copyMemoryReply(b, 0x24, mem, seen));
        check("memory replies reassembled", java.util.Arrays.equals(mem, cal));
        byte[] err = a.clone();
        err[3] |= 0x07; // error flag set
        check("memory reply with error rejected", !Reports.copyMemoryReply(err, 0x24, new byte[24], new boolean[24]));
    }

    private static void config() {
        new Config().validate();
        Properties p = new Properties();
        p.setProperty("enterKg", "8");
        p.setProperty("diag", "true");
        Config c = Config.from(p);
        check("properties override defaults", c.enterKg == 8 && c.diag && c.exitKg == new Config().exitKg);
        p.setProperty("exitKg", "9");
        check("exit >= enter rejected", throwsIae(() -> Config.from(p)));
        Properties typo = new Properties();
        typo.setProperty("enterKG", "5");
        check("unknown key rejected", throwsIae(() -> Config.from(typo)));
        Properties nan = new Properties();
        nan.setProperty("staleMs", "soon");
        check("bad number rejected", throwsIae(() -> Config.from(nan)));
        Properties even = new Properties();
        even.setProperty("medianSamples", "4");
        check("even median window rejected", throwsIae(() -> Config.from(even)));
    }

    // ---- measurement state machine (synthetic, deterministic time) ----

    private static final int STEP_MS = 10;

    private static final class Rig {
        final MeasurementTracker t;
        final List<Double> results = new ArrayList<>();
        final Random rnd = new Random(42);
        long now;

        Rig(Config cfg) {
            t = new MeasurementTracker(cfg, results::add);
            t.onConnecting();
        }

        /** Feeds {@code ms} of samples at {@code kg} plus Gaussian noise. */
        void hold(double kg, long ms, double noise) {
            for (long end = now + ms; now < end; now += STEP_MS) {
                t.onSample(now, kg + rnd.nextGaussian() * noise);
            }
        }

        void ramp(double from, double to, long ms) {
            long start = now;
            for (long end = now + ms; now < end; now += STEP_MS) {
                t.onSample(now, from + (to - from) * (now - start) / ms);
            }
        }

        MeasurementTracker.State state() {
            return t.state();
        }
    }

    private static void tracker() {
        Config cfg = new Config();

        Rig r = new Rig(cfg);
        r.hold(0.8, 1200, 0.03); // empty board with an offset
        check("zeroing reaches READY", r.state() == MeasurementTracker.State.READY);

        r.hold(0.8, 2000, 0.03);
        check("noise on empty board stays READY", r.state() == MeasurementTracker.State.READY && r.results.isEmpty());

        r.ramp(0.8, 70.8, 600);
        check("step-on enters STABILIZING or later", r.state() != MeasurementTracker.State.READY);
        r.hold(70.8, 4000, 0.05);
        check("stable load completes", r.state() == MeasurementTracker.State.MEASUREMENT_COMPLETE);
        check("one result, baseline-corrected", r.results.size() == 1 && near(r.results.get(0), 70.0, 0.2));
        r.hold(70.8, 10000, 0.05);
        check("no repeat completion while still standing", r.results.size() == 1);

        r.ramp(70.8, 0.8, 300);
        r.hold(0.8, 500, 0.03);
        check("step-off returns to READY and clears result",
                r.state() == MeasurementTracker.State.READY && Double.isNaN(r.t.resultKg()));

        r.ramp(0.8, 55.8, 400);
        r.hold(55.8, 3500, 0.05);
        check("second measurement without restart", r.results.size() == 2 && near(r.results.get(1), 55.0, 0.2));

        // Stepping off before the reading is stable gives no result.
        Rig early = new Rig(cfg);
        early.hold(0, 1200, 0.03);
        early.hold(70, 500, 0.05);
        early.hold(0, 500, 0.03);
        check("early step-off: READY, no result", early.state() == MeasurementTracker.State.READY && early.results.isEmpty());

        // Hysteresis: a signal wobbling between 4 and 6 kg (around enterKg=5, above exitKg=2.5) must not drop out.
        Rig hyst = new Rig(cfg);
        hyst.hold(0, 1200, 0.03);
        for (int i = 0; i < 200; i++) {
            hyst.hold(i % 2 == 0 ? 6 : 4, 20, 0);
        }
        check("hysteresis: stays STABILIZING, no result", hyst.state() == MeasurementTracker.State.STABILIZING && hyst.results.isEmpty());

        // Someone shifting their weight never settles: times out into ERROR, then recovers on step-off.
        Rig shift = new Rig(cfg);
        shift.hold(0, 1200, 0.03);
        for (int i = 0; i < 80; i++) {
            shift.hold(i % 2 == 0 ? 70 : 76, 200, 0);
        }
        check("unstable load times out into ERROR", shift.state() == MeasurementTracker.State.ERROR && shift.results.isEmpty());
        shift.hold(0, 500, 0.03);
        check("ERROR recovers after step-off", shift.state() == MeasurementTracker.State.READY);

        // Standing on it while it connects: must not take that as zero.
        Rig on = new Rig(cfg);
        on.hold(70, 2000, 0.05);
        check("loaded at connect stays CONNECTING", on.state() == MeasurementTracker.State.CONNECTING
                && on.t.detail().contains("Step off"));
        on.hold(0, 1500, 0.03);
        check("zeroes once the board is empty", on.state() == MeasurementTracker.State.READY);

        // Connection loss mid-measurement.
        Rig loss = new Rig(cfg);
        loss.hold(0, 1200, 0.03);
        loss.hold(70, 800, 0.05);
        loss.t.onDisconnected("lost");
        loss.hold(70, 100, 0.05);
        check("disconnect: no result, samples ignored", loss.state() == MeasurementTracker.State.DISCONNECTED && loss.results.isEmpty());
        loss.t.onConnecting();
        loss.hold(0, 1200, 0.03);
        check("recovers after reconnect", loss.state() == MeasurementTracker.State.READY);

        Rig over = new Rig(cfg);
        over.hold(0, 1200, 0.03);
        over.t.onOverload();
        check("overload -> ERROR", over.state() == MeasurementTracker.State.ERROR);
        over.hold(0, 200, 0.03);
        check("overload clears when load removed", over.state() == MeasurementTracker.State.READY);
    }

    // ---- engine: reconnect, loss, recovery, cleanup (fake sources, real time, tiny timeouts) ----

    private static Config fastConfig() {
        Config c = new Config();
        c.reconnectMinMs = 5;
        c.reconnectMaxMs = 20;
        c.staleMs = 100;
        c.zeroWindowMs = 100;
        c.stableWindowMs = 200;
        c.minStabilizeMs = 100;
        c.stabilizeTimeoutMs = 5000;
        c.validate();
        return c;
    }

    private interface Script {
        /** Returns the next sample, null for "no report", or throws to simulate a lost connection. */
        Sample next(int i) throws IOException;
    }

    private static final class Fake implements SensorSource {
        final Script script;
        final String openError;
        final AtomicInteger closes;
        int i;

        Fake(Script script, String openError, AtomicInteger closes) {
            this.script = script;
            this.openError = openError;
            this.closes = closes;
        }

        @Override
        public void open() throws IOException {
            if (openError != null) {
                throw new IOException(openError);
            }
        }

        @Override
        public Sample read() throws IOException {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return script.next(i++);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static Sample load(double totalKg) {
        double q = totalKg / 4;
        return new Sample(new int[4], new double[] { q, q, q, q });
    }

    private static boolean waitFor(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        for (long end = System.currentTimeMillis() + timeoutMs; System.currentTimeMillis() < end; ) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return cond.getAsBoolean();
    }

    private static void engine() throws Exception {
        // 1. Two failed opens, then a board that is empty, then stepped on: full cycle after retries.
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        List<Snapshot> seen = new CopyOnWriteArrayList<>();
        ScaleEngine e1 = new ScaleEngine(fastConfig(), () -> {
            int n = opens.incrementAndGet();
            if (n <= 2) {
                return new Fake(i -> null, "not found", closes);
            }
            return new Fake(i -> load(i < 100 ? 0 : 70), null, closes);
        }, seen::add);
        e1.start();
        check("engine measures after failed opens", waitFor(() -> lastResult(seen) > 0, 8000));
        check("measured value ~70 kg", near(lastResult(seen), 70, 0.5));
        check("retried until it worked", opens.get() == 3);
        e1.stop();
        check("every source closed (incl. failed opens)", closes.get() == 3);
        check("worker thread gone after stop", !threadAlive("scale-engine"));
        check("last snapshot is DISCONNECTED", seen.get(seen.size() - 1).state == MeasurementTracker.State.DISCONNECTED);

        // 2. Connection lost mid-measurement, then a second board session recovers and measures again.
        AtomicInteger opens2 = new AtomicInteger();
        AtomicInteger closes2 = new AtomicInteger();
        List<Snapshot> seen2 = new CopyOnWriteArrayList<>();
        ScaleEngine e2 = new ScaleEngine(fastConfig(), () -> {
            if (opens2.incrementAndGet() == 1) {
                return new Fake(i -> {
                    if (i > 120) {
                        throw new IOException("read failed");
                    }
                    return load(i < 100 ? 0 : 70);
                }, null, closes2);
            }
            return new Fake(i -> load(i < 100 ? 0 : 40), null, closes2);
        }, seen2::add);
        e2.start();
        check("recovers and measures after mid-measurement loss", waitFor(() -> near(lastResult(seen2), 40, 0.5), 8000));
        check("a DISCONNECTED snapshot occurred", seen2.stream().anyMatch(s -> s.state == MeasurementTracker.State.DISCONNECTED));
        check("no result published from the interrupted measurement",
                seen2.stream().noneMatch(s -> near(s.resultKg, 70, 1)));
        e2.stop();
        check("both sessions closed", closes2.get() == 2);

        // 3. A silent board (no reports) is detected as stale and reopened.
        AtomicInteger opens3 = new AtomicInteger();
        AtomicInteger closes3 = new AtomicInteger();
        ScaleEngine e3 = new ScaleEngine(fastConfig(), () -> {
            opens3.incrementAndGet();
            return new Fake(i -> null, null, closes3);
        }, s -> { });
        e3.start();
        check("stale connection is reopened", waitFor(() -> opens3.get() >= 3, 8000));
        e3.stop();
        check("stale sessions closed", closes3.get() >= 2 && !threadAlive("scale-engine"));
    }

    private static double lastResult(List<Snapshot> snaps) {
        for (int i = snaps.size() - 1; i >= 0; i--) {
            if (!Double.isNaN(snaps.get(i).resultKg)) {
                return snaps.get(i).resultKg;
            }
        }
        return 0;
    }

    private static boolean threadAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals(name) && t.isAlive());
    }
}
