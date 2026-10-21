/** Immutable view of the scale for a display: everything a UI needs, nothing it can change. */
final class Snapshot {

    static final Snapshot INITIAL = new Snapshot(MeasurementTracker.State.DISCONNECTED, "Starting", 0, Double.NaN, 0);

    final MeasurementTracker.State state;
    /** Human-readable status or instruction, e.g. "Step on the board". */
    final String detail;
    /** Filtered load above the empty-board baseline, kg. */
    final double liveKg;
    /** Accepted measurement for the current step-on, kg, or NaN. */
    final double resultKg;
    /** Measured sensor report rate, Hz. */
    final double reportHz;

    Snapshot(MeasurementTracker.State state, String detail, double liveKg, double resultKg, double reportHz) {
        this.state = state;
        this.detail = detail;
        this.liveKg = liveKg;
        this.resultKg = resultKg;
        this.reportHz = reportHz;
    }
}
