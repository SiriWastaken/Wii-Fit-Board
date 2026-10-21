import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Wii Fit Board scale.
 *
 * <pre>
 * java -jar scale.jar [--config FILE] [--headless] [--simulate] [--diag]
 * </pre>
 *
 * @author sriganty
 * @version 0.3
 */
public class Main {

    private static final Path DEFAULT_CONFIG = Path.of("scale.properties");

    public static void main(String[] args) throws Exception {
        boolean headless = false;
        boolean simulate = false;
        boolean diag = false;
        Path configFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--headless" -> headless = true;
                case "--simulate" -> simulate = true;
                case "--diag" -> diag = true;
                case "--config" -> {
                    if (++i == args.length) {
                        usage();
                    }
                    configFile = Path.of(args[i]);
                }
                default -> usage();
            }
        }

        Config cfg;
        try {
            if (configFile == null && Files.exists(DEFAULT_CONFIG)) {
                configFile = DEFAULT_CONFIG;
            }
            cfg = configFile == null ? new Config() : Config.load(configFile);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Config error: " + e.getMessage());
            System.exit(2);
            return;
        }
        cfg.diag |= diag;
        Log.setLevel(cfg.logLevel);

        Display display = headless || GraphicsEnvironment.isHeadless() ? new ConsoleDisplay() : new SwingDisplay(cfg);
        Supplier<SensorSource> source = simulate ? SimulatedSource::new : WiiBoard::new;
        ScaleEngine engine = new ScaleEngine(cfg, source, display::update);

        // Closes the HID device and ends the worker on Ctrl+C, SIGTERM or window close.
        Runtime.getRuntime().addShutdownHook(new Thread(engine::stop, "shutdown"));
        engine.start();
        engine.await();
    }

    private static void usage() {
        System.err.println("Usage: java -jar scale.jar [--config FILE] [--headless] [--simulate] [--diag]");
        System.exit(2);
    }
}
