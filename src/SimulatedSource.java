import java.io.IOException;
import java.util.Random;

/** Fake board for running without hardware: a 16 s loop of empty, step on, hold, step off. */
final class SimulatedSource implements SensorSource {

    private static final double PERSON_KG = 72.4;
    private static final double[] SHARE = { 0.28, 0.22, 0.28, 0.22 }; // load split over the four sensors
    private static final long CYCLE_MS = 16000;

    private final Random random = new Random(1);
    private long startMs;

    @Override
    public void open() {
        startMs = System.currentTimeMillis();
    }

    @Override
    public Sample read() throws IOException {
        try {
            Thread.sleep(10); // ~100 Hz
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        double s = ((System.currentTimeMillis() - startMs) % CYCLE_MS) / 1000.0;
        double level = s < 3 ? 0 : s < 4 ? (s - 3) : s < 11 ? 1 : s < 12 ? (12 - s) : 0;
        double total = level * PERSON_KG;
        int[] raw = new int[Calibration.SENSORS];
        double[] kg = new double[Calibration.SENSORS];
        for (int i = 0; i < kg.length; i++) {
            kg[i] = total * SHARE[i] + random.nextGaussian() * 0.03;
            raw[i] = (int) (kg[i] * 100); // arbitrary scale, only for diagnostics
        }
        return new Sample(raw, kg);
    }

    @Override
    public void close() {
    }
}
