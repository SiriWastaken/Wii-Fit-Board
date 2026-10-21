import java.io.IOException;

/** A source of calibrated sensor samples: the real board, or a simulation. */
interface SensorSource extends AutoCloseable {

    /** Connects and initializes; only returns normally if valid sensor reports can be read. */
    void open() throws IOException;

    /**
     * Blocks for a short time (well under a second) waiting for a report.
     *
     * @return the sample, or {@code null} if no sensor report arrived in time
     * @throws IOException if the connection is lost
     */
    Sample read() throws IOException;

    /** Releases all resources; safe to call more than once. */
    @Override
    void close();
}
