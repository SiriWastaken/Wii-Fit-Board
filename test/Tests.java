import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Hardware-free tests (no JUnit dependency). Run with ./test.sh.
 * They prove the decoding, calibration reuse, reconnect and weighing logic against synthetic
 * input; they do NOT prove Bluetooth/HID behavior or real-world accuracy.
 */
public class Tests {

    private static int failures;
    private static int checks;

    /** WiiBoard with the HID layer and the clock replaced, so polling is deterministic and instant. */
    private static final class Fake extends WiiBoard {
        long clock = 1_000_000;
        final ArrayDeque<byte[]> inbox = new ArrayDeque<>();
        final List<Integer> writes = new ArrayList<>(); // report IDs written
        final List<Long> openAttempts = new ArrayList<>();
        boolean boardPresent = true;
        boolean garbleCalibration;
        int opens, closes;
        int[] raw = new int[4];

        @Override
        String openDevice() {
            openAttempts.add(clock);
            if (!boardPresent) {
                return "board not found";
            }
            opens++;
            return null;
        }

        @Override
        void closeDevice() {
            closes++;
        }

        @Override
        boolean write(byte id, byte[] data) {
            writes.add(id & 0xFF);
            if (id == 0x17) { // calibration read request: queue the two memory replies
                byte[] cal = calBytes();
                if (garbleCalibration) {
                    cal[3] ^= (byte) (opens + writes.size());
                }
                inbox.addFirst(reply(0x24, cal, 0, 16));
                inbox.addFirst(reply(0x34, cal, 16, 8));
            }
            return true;
        }

        @Override
        byte[] readRaw() {
            if (inbox.isEmpty()) {
                clock += 100; // a read timeout
                return null;
            }
            clock += 10;
            return inbox.pollLast();
        }

        @Override
        long now() {
            return clock;
        }

        @Override
        void pause(long ms) {
            clock += ms;
        }

        int count(int reportId) {
            return (int) writes.stream().filter(w -> w == reportId).count();
        }

        /** Delivers one report with the given total load spread evenly over the four sensors. */
        void report(double kg) {
            inbox.addFirst(sensorReport(rawFor(kg)));
        }

        /** Feeds {@code ms} of reports at {@code kg} + noise, one every 10 ms. */
        void hold(double kg, long ms, double noise, Random rnd) {
            for (long end = clock + ms; clock < end; ) {
                report(kg + rnd.nextGaussian() * noise);
                poll();
            }
        }

        void ramp(double from, double to, long ms, Random rnd) {
            long start = clock;
            for (long end = clock + ms; clock < end; ) {
                report(from + (to - from) * (clock - start) / ms);
                poll();
            }
        }
    }

    public static void main(String[] args) {
        decoding();
        calibrationReuse();
        weighing();
        connection();
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

    /** Four sensors: raw 1000+10s at 0 kg, 2700+10s at 17 kg, 4400+10s at 34 kg (big-endian). */
    private static byte[] calBytes() {
        byte[] b = new byte[24];
        for (int i = 0; i < 12; i++) {
            int v = 1000 + (i / 4) * 1700 + (i % 4) * 10;
            b[2 * i] = (byte) (v >> 8);
            b[2 * i + 1] = (byte) v;
        }
        return b;
    }

    private static byte[] reply(int address, byte[] cal, int from, int len) {
        byte[] r = new byte[6 + len];
        r[0] = 0x21;
        r[3] = (byte) ((len - 1) << 4);
        r[4] = (byte) (address >> 8);
        r[5] = (byte) address;
        System.arraycopy(cal, from, r, 6, len);
        return r;
    }

    /** Raw values for a total load spread equally: 1.7 raw units/kg/sensor... inverse of the calibration above. */
    private static int[] rawFor(double totalKg) {
        int[] raw = new int[4];
        for (int s = 0; s < 4; s++) {
            double kg = totalKg / 4;
            raw[s] = 1000 + s * 10 + (int) Math.round(kg * 100); // 100 raw per kg (1700 per 17 kg)
        }
        return raw;
    }

    private static byte[] sensorReport(int[] raw) {
        byte[] r = new byte[11];
        r[0] = 0x32;
        for (int s = 0; s < 4; s++) {
            r[3 + 2 * s] = (byte) (raw[s] >> 8);
            r[4 + 2 * s] = (byte) raw[s];
        }
        return r;
    }

    private static Fake ready() {
        Fake b = new Fake();
        Random rnd = new Random(7);
        b.poll(); // connects
        b.hold(0.8, 1500, 0.03, rnd);
        return b;
    }

    // ---- decoding and calibration ----

    private static void decoding() {
        int[][] cal = WiiBoard.parseCalibration(calBytes());
        check("calibration parses", cal != null && cal[1][0] == 2700 && cal[2][3] == 4430);
        check("0 kg raw -> 0 kg", near(WiiBoard.totalKg(cal, new int[] { 1000, 1010, 1020, 1030 }), 0, 1e-9));
        check("17 kg each -> 68 kg", near(WiiBoard.totalKg(cal, new int[] { 2700, 2710, 2720, 2730 }), 68, 1e-9));
        check("34 kg each -> 136 kg", near(WiiBoard.totalKg(cal, new int[] { 4400, 4410, 4420, 4430 }), 136, 1e-9));
        check("midpoint interpolates", near(WiiBoard.totalKg(cal, new int[] { 1850, 1860, 1870, 1880 }), 34, 1e-9));
        check("below the zero point is negative", WiiBoard.totalKg(cal, new int[] { 900, 910, 920, 930 }) < 0);
        check("short calibration rejected", WiiBoard.parseCalibration(new byte[23]) == null);
        byte[] bad = calBytes();
        bad[8] = bad[0];
        bad[9] = bad[1]; // sensor 0: 17 kg point == 0 kg point
        check("non-increasing calibration rejected", WiiBoard.parseCalibration(bad) == null);

        byte[] r32 = { 0x32, 0, 0, 0x03, (byte) 0xE8, 0x00, 0x10, (byte) 0xFF, (byte) 0xFF, 0x01, 0x00 };
        int[] raw = WiiBoard.sensors(r32);
        check("0x32 decodes big-endian in sensor order",
                raw != null && raw[0] == 1000 && raw[1] == 16 && raw[2] == 65535 && raw[3] == 256);
        byte[] r35 = new byte[6 + 8 + 10];
        r35[0] = 0x35;
        r35[6] = 0x12;
        r35[7] = 0x34;
        check("0x35 skips the accelerometer bytes", WiiBoard.sensors(r35)[0] == 0x1234);
        check("short report rejected", WiiBoard.sensors(new byte[] { 0x32, 0, 0, 1, 2 }) == null);
        check("status report is not a sensor report", WiiBoard.sensors(new byte[11]) == null);
        check("null/empty rejected", WiiBoard.sensors(null) == null && WiiBoard.sensors(new byte[0]) == null);

        byte[] mem = new byte[24];
        boolean[] seen = new boolean[24];
        WiiBoard.copyMemoryReply(reply(0x24, calBytes(), 0, 16), mem, seen);
        WiiBoard.copyMemoryReply(reply(0x34, calBytes(), 16, 8), mem, seen);
        check("memory replies reassemble", Arrays.equals(mem, calBytes()));
        byte[] err = reply(0x24, calBytes(), 0, 16);
        err[3] |= 0x07;
        byte[] mem2 = new byte[24];
        WiiBoard.copyMemoryReply(err, mem2, new boolean[24]);
        check("memory reply with error flag ignored", Arrays.equals(mem2, new byte[24]));
    }

    // ---- calibration is read once per connection ----

    private static void calibrationReuse() {
        Fake b = new Fake();
        Random rnd = new Random(1);
        b.poll();
        check("connect initializes: mode set, calibration read",
                b.count(0x12) == 1 && b.count(0x17) == 2 && b.calibration != null); // 2 = read twice and compared
        int[][] cal = b.calibration;
        b.hold(0.8, 1500, 0.03, rnd);
        check("zeroing reaches READY", b.state() == WiiBoard.State.READY);

        for (double kg : new double[] { 70, 55, 82 }) {
            b.ramp(0.8, kg + 0.8, 500, rnd);
            b.hold(kg + 0.8, 4000, 0.05, rnd);
            check("weighing " + kg + " done", b.state() == WiiBoard.State.DONE && near(b.resultKg(), kg, 0.3));
            b.ramp(kg + 0.8, 0.8, 300, rnd);
            b.hold(0.8, 800, 0.03, rnd);
            check("step-off resets", b.state() == WiiBoard.State.READY && Double.isNaN(b.resultKg()));
        }
        check("calibration read once for three weighings", b.count(0x17) == 2 && b.count(0x12) == 1 && b.opens == 1);
        check("same calibration object reused", b.calibration == cal);
        check("no reconnect", b.closes == 0);

        b.resetMeasurement();
        check("resetMeasurement keeps calibration", b.calibration == cal);

        Fake g = new Fake();
        g.garbleCalibration = true;
        g.poll();
        check("inconsistent calibration reads rejected", g.calibration == null && g.state() == WiiBoard.State.OFFLINE);
    }

    // ---- step-on / step-off logic ----

    private static void weighing() {
        Random rnd = new Random(3);
        Fake b = ready();
        check("noise on an empty board stays READY", near(b.liveKg(), 0, 0.3) && b.state() == WiiBoard.State.READY);
        b.hold(0.8, 5000, 0.05, rnd);
        check("no spurious step-on from noise", b.state() == WiiBoard.State.READY);

        b.ramp(0.8, 70.8, 500, rnd);
        b.hold(70.8, 3000, 0.05, rnd);
        double first = b.resultKg();
        check("first weighing ~70 kg (baseline subtracted once)", near(first, 70, 0.3));
        b.hold(70.8, 8000, 0.05, rnd);
        check("result not re-measured while standing", b.resultKg() == first);

        // The next person must not inherit the first person's weight.
        b.ramp(70.8, 0.8, 300, rnd);
        b.hold(0.8, 800, 0.03, rnd);
        b.ramp(0.8, 45.8, 400, rnd);
        b.hold(45.8, 3000, 0.05, rnd);
        check("second weighing ~45 kg, not contaminated by the first", near(b.resultKg(), 45, 0.3));

        // Stepping off before the reading is stable gives no result.
        Fake e = ready();
        e.hold(70.8, 500, 0.05, rnd);
        e.hold(0.8, 800, 0.03, rnd);
        check("early step-off: READY, no result", e.state() == WiiBoard.State.READY && Double.isNaN(e.resultKg()));

        // Hysteresis: wobbling between 4 and 6 kg (around ENTER_KG, above EXIT_KG) must not flip back to READY.
        Fake h = ready();
        for (int i = 0; i < 300; i++) {
            h.hold(0.8 + (i % 2 == 0 ? 6 : 4), 20, 0, rnd);
        }
        check("hysteresis: stays MEASURING, no result", h.state() == WiiBoard.State.MEASURING && Double.isNaN(h.resultKg()));

        // A person who never settles times out into FAILED and recovers on step-off.
        Fake s = ready();
        for (int i = 0; i < 80; i++) {
            s.hold(70.8 + (i % 2 == 0 ? 0 : 6), 200, 0, rnd);
        }
        check("unsettled load -> FAILED", s.state() == WiiBoard.State.FAILED && Double.isNaN(s.resultKg()));
        s.hold(0.8, 800, 0.03, rnd);
        check("FAILED recovers after step-off", s.state() == WiiBoard.State.READY);

        // Standing on it while it connects must not be taken as zero.
        Fake on = new Fake();
        on.poll();
        on.hold(70, 2500, 0.05, rnd);
        check("loaded at connect: not zeroed", on.state() == WiiBoard.State.ZEROING && on.status().contains("Step off"));
        on.hold(0, 1500, 0.03, rnd);
        check("zeroes once the board is empty", on.state() == WiiBoard.State.READY);

        // Over capacity.
        Fake o = ready();
        o.hold(300, 200, 0, rnd);
        check("over capacity -> FAILED", o.state() == WiiBoard.State.FAILED);

        // Malformed and non-sensor reports change nothing.
        Fake m = ready();
        m.inbox.addFirst(new byte[] { 0x32, 1 });
        m.inbox.addFirst(new byte[] { 0x21, 0, 0, 0 });
        m.inbox.addFirst(new byte[0]);
        for (int i = 0; i < 20; i++) {
            m.poll();
        }
        check("malformed reports ignored", m.state() == WiiBoard.State.READY && m.closes == 0);
    }

    // ---- connection management ----

    private static void connection() {
        Random rnd = new Random(5);

        // A long gap in reports is normal for a still board (it only sends on change): no reconnect.
        Fake gap = ready();
        for (long end = gap.clock + WiiBoard.LOST_MS - 1000; gap.clock < end; ) {
            gap.poll();
        }
        check("report gap shorter than LOST_MS does not reconnect", gap.closes == 0 && gap.opens == 1 && gap.state() == WiiBoard.State.READY);
        gap.hold(0.8, 500, 0.03, rnd);
        check("reports resume normally after a gap", gap.state() == WiiBoard.State.READY && gap.opens == 1);

        // A status report (no sensor data) still proves the link is alive.
        Fake alive = ready();
        for (long end = alive.clock + 3 * WiiBoard.LOST_MS; alive.clock < end; ) {
            alive.inbox.addFirst(new byte[] { 0x20, 0, 0, 0, 0, 0 });
            alive.poll();
            alive.poll();
        }
        check("any report keeps the link alive", alive.closes == 0 && alive.opens == 1);

        // Genuine loss: silence past LOST_MS closes, then reconnects and re-initializes exactly once.
        Fake lost = ready();
        int reads = lost.count(0x17);
        long silentUntil = lost.clock + WiiBoard.LOST_MS + 1000;
        while (lost.clock < silentUntil && lost.state() != WiiBoard.State.OFFLINE) {
            lost.poll();
        }
        check("genuine loss detected", lost.state() == WiiBoard.State.OFFLINE && lost.closes == 1);
        check("measurement cleared on loss", Double.isNaN(lost.resultKg()));
        while (lost.opens < 2) {
            lost.poll();
        }
        check("reconnect re-initializes and re-reads calibration", lost.count(0x12) == 2 && lost.count(0x17) == reads + 2);
        check("zeroing again after reconnect", lost.state() == WiiBoard.State.ZEROING);
        lost.hold(0.8, 1500, 0.03, rnd);
        check("works again after recovery", lost.state() == WiiBoard.State.READY);

        // Board absent: attempts back off exponentially up to the maximum, one at a time.
        Fake absent = new Fake();
        absent.boardPresent = false;
        while (absent.openAttempts.size() < 9) {
            absent.poll();
        }
        List<Long> a = absent.openAttempts;
        boolean growing = true;
        for (int i = 2; i < a.size(); i++) {
            long prev = a.get(i - 1) - a.get(i - 2);
            long gapMs = a.get(i) - a.get(i - 1);
            growing &= gapMs >= prev && gapMs <= WiiBoard.RETRY_MAX_MS + 200;
        }
        check("retries back off and stay bounded", growing && a.get(8) - a.get(7) >= WiiBoard.RETRY_MAX_MS - 200);
        check("never claims connected while absent", absent.state() == WiiBoard.State.OFFLINE && absent.opens == 0);
        absent.boardPresent = true;
        while (absent.opens == 0) {
            absent.poll();
        }
        check("connects when the board appears", absent.state() == WiiBoard.State.ZEROING);

        Fake closing = ready();
        closing.disconnect();
        check("disconnect releases the device", closing.closes == 1 && closing.state() == WiiBoard.State.OFFLINE);
    }
}
