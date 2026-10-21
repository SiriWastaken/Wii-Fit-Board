import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tunable settings. Every public field can be overridden by a key of the same
 * name in a properties file (see scale.properties). Weights are in kilograms,
 * durations in milliseconds. Defaults are starting points, not hardware-tuned.
 */
public final class Config {

    // Step-on / step-off detection, in kg above the unloaded baseline.
    public double enterKg = 5.0;
    public double exitKg = 2.5; // below enterKg so the state cannot chatter at the edge

    // A reading is accepted once the last stableWindowMs have a standard deviation <= stableStdKg.
    public long stableWindowMs = 1500;
    public double stableStdKg = 0.15;
    public long minStabilizeMs = 1000;
    public long stabilizeTimeoutMs = 12000;

    // Zeroing: the empty board must be quiet and near zero for zeroWindowMs.
    public long zeroWindowMs = 1000;
    public double zeroStdKg = 0.2;
    public double maxBaselineKg = 3.0;
    public double baselineAlpha = 0.002; // per-sample drift tracking while the board is empty

    // Samples outside this range are dropped; above the max is reported as overload.
    public double minPlausibleKg = -5;
    public double maxPlausibleKg = 200;
    public int medianSamples = 5; // odd; rejects isolated single-sample spikes

    // Connection management.
    public long staleMs = 3000; // no sensor report for this long = connection considered lost
    public long reconnectMinMs = 1000;
    public long reconnectMaxMs = 30000;

    // Output.
    public String logLevel = "INFO";
    public boolean diag = false;
    public boolean fullscreen = false;

    /** Loads a properties file over the defaults and validates it. */
    public static Config load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return from(p);
    }

    /** Applies the given properties over the defaults; unknown keys are an error. */
    public static Config from(Properties p) {
        Config c = new Config();
        for (String key : p.stringPropertyNames()) {
            try {
                Field f = Config.class.getField(key);
                String v = p.getProperty(key).trim();
                Class<?> t = f.getType();
                if (Modifier.isStatic(f.getModifiers())) {
                    throw new NoSuchFieldException(key);
                } else if (t == double.class) {
                    f.setDouble(c, Double.parseDouble(v));
                } else if (t == long.class) {
                    f.setLong(c, Long.parseLong(v));
                } else if (t == int.class) {
                    f.setInt(c, Integer.parseInt(v));
                } else if (t == boolean.class) {
                    f.setBoolean(c, Boolean.parseBoolean(v));
                } else {
                    f.set(c, v);
                }
            } catch (NoSuchFieldException e) {
                throw new IllegalArgumentException("Unknown setting: " + key);
            } catch (NumberFormatException | IllegalAccessException e) {
                throw new IllegalArgumentException("Bad value for " + key + ": " + p.getProperty(key));
            }
        }
        c.validate();
        return c;
    }

    /** @throws IllegalArgumentException if the settings are inconsistent */
    public void validate() {
        require(enterKg > 0, "enterKg must be > 0");
        require(exitKg > 0 && exitKg < enterKg, "exitKg must be > 0 and < enterKg");
        require(stableWindowMs > 0 && minStabilizeMs >= 0, "stableWindowMs must be > 0, minStabilizeMs >= 0");
        require(stableStdKg > 0 && zeroStdKg > 0, "stableStdKg and zeroStdKg must be > 0");
        require(stabilizeTimeoutMs > Math.max(stableWindowMs, minStabilizeMs),
                "stabilizeTimeoutMs must exceed stableWindowMs and minStabilizeMs");
        require(zeroWindowMs > 0 && maxBaselineKg > 0, "zeroWindowMs and maxBaselineKg must be > 0");
        require(baselineAlpha >= 0 && baselineAlpha < 1, "baselineAlpha must be in [0, 1)");
        require(minPlausibleKg < 0 && maxPlausibleKg > enterKg, "minPlausibleKg must be < 0, maxPlausibleKg > enterKg");
        require(medianSamples >= 1 && medianSamples % 2 == 1, "medianSamples must be odd and >= 1");
        require(staleMs > 0 && reconnectMinMs > 0 && reconnectMaxMs >= reconnectMinMs,
                "staleMs > 0 and 0 < reconnectMinMs <= reconnectMaxMs");
        try {
            Log.Level.valueOf(logLevel.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("logLevel must be DEBUG, INFO, WARN or ERROR");
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
