import java.util.ArrayDeque;
import java.util.function.DoubleConsumer;

/**
 * Turns a stream of filtered total-load samples into the weighing cycle:
 * zero the empty board, detect a step-on, wait for a stable reading, report it
 * once, then wait for the step-off.
 *
 * <p>
 * Not thread-safe: all calls come from the engine thread. Time is passed in, so
 * the logic is deterministic and testable.
 * </p>
 */
final class MeasurementTracker {

    enum State { DISCONNECTED, CONNECTING, READY, STABILIZING, MEASUREMENT_COMPLETE, ERROR }

    private static final int MIN_STABLE_SAMPLES = 10;
    /** The window may not hold samples older than its length, so require it to be nearly full. */
    private static final double MIN_WINDOW_FILL = 0.9;

    private final Config cfg;
    private final DoubleConsumer onResult;
    private final ArrayDeque<double[]> window = new ArrayDeque<>(); // {timeMs, kg}

    private State state = State.DISCONNECTED;
    private String detail = "Not connected";
    private double baselineKg;
    private double liveKg;
    private double resultKg = Double.NaN;
    private long enteredAtMs;

    MeasurementTracker(Config cfg, DoubleConsumer onResult) {
        this.cfg = cfg;
        this.onResult = onResult;
    }

    State state() {
        return state;
    }

    String detail() {
        return detail;
    }

    /** Load above the baseline, in kg (may be slightly negative). */
    double liveKg() {
        return liveKg;
    }

    /** The accepted measurement for the current step-on, or NaN. */
    double resultKg() {
        return resultKg;
    }

    void onConnecting() {
        reset(State.CONNECTING, "Zeroing: keep the board empty");
    }

    void onDisconnected(String why) {
        reset(State.DISCONNECTED, why);
    }

    /** A reading above the plausible range arrived. */
    void onOverload() {
        if (state != State.DISCONNECTED && state != State.CONNECTING) {
            state = State.ERROR;
            detail = "Over capacity";
            resultKg = Double.NaN;
        }
    }

    private void reset(State s, String d) {
        state = s;
        detail = d;
        window.clear();
        liveKg = 0;
        resultKg = Double.NaN;
        baselineKg = 0; // re-zeroed on every (re)connect: someone may be standing on it
    }

    private void toReady() {
        state = State.READY;
        detail = "Step on the board";
        resultKg = Double.NaN;
        window.clear();
    }

    /** @param kg total filtered load (not baseline-corrected) at {@code timeMs} */
    void onSample(long timeMs, double kg) {
        if (state == State.DISCONNECTED) {
            return;
        }
        window.addLast(new double[] { timeMs, kg });
        long keepMs = state == State.CONNECTING ? cfg.zeroWindowMs : cfg.stableWindowMs;
        while (window.getFirst()[0] < timeMs - keepMs) {
            window.removeFirst();
        }
        double net = kg - baselineKg;

        switch (state) {
            case CONNECTING -> zero();
            case READY -> {
                liveKg = net;
                if (net >= cfg.enterKg) {
                    state = State.STABILIZING;
                    detail = "Hold still";
                    enteredAtMs = timeMs;
                    window.clear();
                    window.addLast(new double[] { timeMs, kg });
                } else if (Math.abs(net) < cfg.exitKg) {
                    baselineKg += cfg.baselineAlpha * net; // follow slow drift of the empty board
                }
            }
            case STABILIZING -> {
                liveKg = net;
                if (net < cfg.exitKg) {
                    Log.info("Rejected: stepped off after %d ms, before the reading was stable", timeMs - enteredAtMs);
                    toReady();
                } else if (timeMs - enteredAtMs >= cfg.stabilizeTimeoutMs) {
                    Log.warn("Rejected: not stable after %d ms (std %.3f kg)", timeMs - enteredAtMs, stats()[1]);
                    state = State.ERROR;
                    detail = "Couldn't settle: step off and try again";
                } else if (isStable(timeMs)) {
                    resultKg = Math.max(0, stats()[0] - baselineKg);
                    state = State.MEASUREMENT_COMPLETE;
                    detail = "Done: step off to weigh again";
                    onResult.accept(resultKg);
                }
            }
            case MEASUREMENT_COMPLETE, ERROR -> {
                liveKg = net;
                if (net < cfg.exitKg) {
                    toReady();
                }
            }
            default -> { }
        }
    }

    private void zero() {
        double[] s = stats();
        if (s[2] < MIN_WINDOW_FILL * cfg.zeroWindowMs) {
            return;
        }
        if (Math.abs(s[0]) > cfg.maxBaselineKg) {
            detail = "Step off the board to zero it";
        } else if (s[1] > cfg.zeroStdKg) {
            detail = "Zeroing: keep the board still";
        } else {
            baselineKg = s[0];
            Log.info("Zeroed, baseline %.3f kg", baselineKg);
            toReady();
        }
    }

    private boolean isStable(long timeMs) {
        double[] s = stats();
        return window.size() >= MIN_STABLE_SAMPLES
                && timeMs - enteredAtMs >= cfg.minStabilizeMs
                && s[2] >= MIN_WINDOW_FILL * cfg.stableWindowMs
                && s[1] <= cfg.stableStdKg;
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
}
