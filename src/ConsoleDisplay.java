/** Headless display: prints a line when the state or status text changes, and the result of each weighing. */
final class ConsoleDisplay implements Display {

    private MeasurementTracker.State state;
    private String detail;
    private double result = Double.NaN;

    @Override
    public synchronized void update(Snapshot s) {
        if (s.state != state || !s.detail.equals(detail)) {
            state = s.state;
            detail = s.detail;
            System.out.println(s.state + ": " + s.detail);
        }
        if (!Double.isNaN(s.resultKg) && Double.isNaN(result)) {
            System.out.printf("Weight: %.1f kg%n", s.resultKg);
        }
        result = s.resultKg;
    }
}
