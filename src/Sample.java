/** One decoded sensor report: raw values, calibrated per-sensor kg, and their total. */
final class Sample {

    final int[] raw;
    final double[] kg;
    final double totalKg;

    Sample(int[] raw, double[] kg) {
        this.raw = raw;
        this.kg = kg;
        double sum = 0;
        for (double v : kg) {
            sum += v;
        }
        this.totalKg = sum;
    }
}
