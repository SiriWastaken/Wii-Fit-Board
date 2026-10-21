/**
 * Converts raw load-cell values to kilograms using the board's factory calibration.
 *
 * <p>
 * The board stores, per sensor, the raw value at 0 kg, 17 kg and 34 kg
 * (24 bytes at 0xA40024: three groups of four big-endian 16-bit values; sensor
 * order top-right, bottom-right, top-left, bottom-left). Values are interpolated
 * linearly between the points and extrapolated outside them. See WiiBrew,
 * "Wii Balance Board". Temperature compensation is not applied.
 * </p>
 */
public final class Calibration {

    public static final int SENSORS = 4;
    public static final int BYTES = 24;
    private static final double REFERENCE_KG = 17.0;

    private final int[][] points; // [0 = 0 kg, 1 = 17 kg, 2 = 34 kg][sensor]

    private Calibration(int[][] points) {
        this.points = points;
    }

    /**
     * @param b the 24 calibration bytes from the board
     * @throws IllegalArgumentException if the data is too short or not strictly increasing per sensor
     */
    public static Calibration fromBytes(byte[] b) {
        if (b == null || b.length < BYTES) {
            throw new IllegalArgumentException("Calibration needs " + BYTES + " bytes");
        }
        int[][] p = new int[3][SENSORS];
        for (int i = 0; i < 12; i++) {
            p[i / SENSORS][i % SENSORS] = ((b[2 * i] & 0xFF) << 8) | (b[2 * i + 1] & 0xFF);
        }
        for (int s = 0; s < SENSORS; s++) {
            if (!(p[0][s] < p[1][s] && p[1][s] < p[2][s])) {
                throw new IllegalArgumentException("Invalid calibration for sensor " + s);
            }
        }
        return new Calibration(p);
    }

    /** @return per-sensor load in kg for four raw sensor values */
    public double[] toKg(int[] raw) {
        double[] kg = new double[SENSORS];
        for (int s = 0; s < SENSORS; s++) {
            int c0 = points[0][s], c17 = points[1][s], c34 = points[2][s];
            kg[s] = raw[s] < c17
                    ? REFERENCE_KG * (raw[s] - c0) / (c17 - c0)
                    : REFERENCE_KG + REFERENCE_KG * (raw[s] - c17) / (c34 - c17);
        }
        return kg;
    }
}
