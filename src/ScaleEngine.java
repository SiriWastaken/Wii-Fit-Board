import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the single worker thread: connects (with exponential backoff), reads
 * samples, filters them, drives the {@link MeasurementTracker} and publishes
 * {@link Snapshot}s. All HID access happens on this thread.
 */
final class ScaleEngine {

    private static final long PUBLISH_INTERVAL_MS = 50;
    private static final long STOP_JOIN_MS = 5000;

    private final Config cfg;
    private final Supplier<SensorSource> factory;
    private final Consumer<Snapshot> out;
    private final MeasurementTracker tracker;

    private volatile boolean running;
    private volatile Thread thread;

    // Touched only by the engine thread (plus tests after stop()).
    private boolean sawData;
    private double reportHz;
    private long valid, invalid, noReport, connectAttempts;
    private long lastPublishMs;
    private MeasurementTracker.State lastState;
    private String lastDetail;

    ScaleEngine(Config cfg, Supplier<SensorSource> factory, Consumer<Snapshot> out) {
        this.cfg = cfg;
        this.factory = factory;
        this.out = out;
        this.tracker = new MeasurementTracker(cfg, kg -> Log.info("Measurement: %.2f kg", kg));
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "scale-engine");
        thread.start();
    }

    /** Stops the worker, which closes the HID device before exiting. */
    synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    /** Blocks until the worker thread ends. */
    void await() throws InterruptedException {
        Thread t = thread;
        if (t != null) {
            t.join();
        }
    }

    private void run() {
        long backoffMs = cfg.reconnectMinMs;
        while (running) {
            connectAttempts++;
            tracker.onConnecting();
            publish(true);
            sawData = false;
            String why;
            SensorSource source = null;
            try {
                source = factory.get();
                source.open();
                Log.info("Board opened (attempt %d), waiting for valid data", connectAttempts);
                pump(source);
                why = "Stopped";
            } catch (IOException e) {
                why = e.getMessage();
            } catch (RuntimeException e) {
                Log.error("Unexpected error: %s", e);
                why = e.toString();
            } finally {
                if (source != null) {
                    source.close();
                }
            }
            if (!running) {
                break;
            }
            if (sawData) {
                backoffMs = cfg.reconnectMinMs; // it worked for a while, so start the backoff over
            }
            Log.warn("Disconnected: %s (retry in %d ms)", why, backoffMs);
            tracker.onDisconnected(why + ". Retrying in " + Math.max(1, backoffMs / 1000) + " s");
            publish(true);
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                break;
            }
            backoffMs = Math.min(backoffMs * 2, cfg.reconnectMaxMs);
        }
        tracker.onDisconnected("Stopped");
        publish(true);
    }

    /** Reads until the connection fails (IOException) or the engine is stopped. */
    private void pump(SensorSource source) throws IOException {
        Median median = new Median(cfg.medianSamples);
        long lastReportMs = nowMs();
        long rateStartMs = lastReportMs;
        int rateCount = 0;
        while (running) {
            Sample s = source.read();
            long t = nowMs();
            if (s == null) {
                noReport++;
                if (t - lastReportMs > cfg.staleMs) {
                    throw new IOException("No sensor data for " + (t - lastReportMs) + " ms");
                }
                continue;
            }
            lastReportMs = t;
            rateCount++;
            if (t - rateStartMs >= 1000) {
                reportHz = rateCount * 1000.0 / (t - rateStartMs);
                rateCount = 0;
                rateStartMs = t;
                if (cfg.diag) {
                    Log.info("diag: %.1f Hz valid=%d invalid=%d noReport=%d state=%s raw=%s kg=%s total=%.3f",
                            reportHz, valid, invalid, noReport, tracker.state(), Arrays.toString(s.raw),
                            Arrays.toString(s.kg), s.totalKg);
                }
            }
            if (Double.isNaN(s.totalKg) || s.totalKg < cfg.minPlausibleKg) {
                invalid++;
                Log.debug("Dropped implausible reading %.2f kg", s.totalKg);
            } else if (s.totalKg > cfg.maxPlausibleKg) {
                invalid++;
                tracker.onOverload();
            } else {
                valid++;
                sawData = true;
                tracker.onSample(t, median.add(s.totalKg));
            }
            publish(false);
        }
    }

    private void publish(boolean force) {
        long t = nowMs();
        boolean changed = tracker.state() != lastState || !tracker.detail().equals(lastDetail);
        if (!force && !changed && t - lastPublishMs < PUBLISH_INTERVAL_MS) {
            return;
        }
        if (changed) {
            Log.info("State %s: %s", tracker.state(), tracker.detail());
            lastState = tracker.state();
            lastDetail = tracker.detail();
        }
        lastPublishMs = t;
        out.accept(new Snapshot(tracker.state(), tracker.detail(), tracker.liveKg(), tracker.resultKg(), reportHz));
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    /** Sliding median of the last n samples. */
    private static final class Median {
        private final double[] ring;
        private int count;

        Median(int n) {
            ring = new double[n];
        }

        double add(double v) {
            ring[count++ % ring.length] = v;
            double[] sorted = Arrays.copyOf(ring, Math.min(count, ring.length));
            Arrays.sort(sorted);
            return sorted[sorted.length / 2];
        }
    }
}
