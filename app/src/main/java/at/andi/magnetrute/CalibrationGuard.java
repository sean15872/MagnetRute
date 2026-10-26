package at.andi.magnetrute;

/** Flags sensor recalibration evidence without changing any measured physical values. */
public final class CalibrationGuard {
    public static final double DEFAULT_BIAS_JUMP_UT = 3;
    public static final long DEFAULT_COOLDOWN_NS = 5_000_000_000L;
    private final double threshold;
    private final long cooldown;
    private double bx = Double.NaN, by = Double.NaN, bz = Double.NaN;
    private int previousAccuracy = -1;
    private long lastTime, until;

    public CalibrationGuard() { this(DEFAULT_BIAS_JUMP_UT, DEFAULT_COOLDOWN_NS); }
    public CalibrationGuard(double biasJumpThreshold, long cooldownNs) {
        if (!Double.isFinite(biasJumpThreshold) || biasJumpThreshold <= 0 || cooldownNs < 0)
            throw new IllegalArgumentException("Ungültige Kalibrierungsgrenze");
        threshold = biasJumpThreshold; cooldown = cooldownNs;
    }

    public static final class State {
        public final boolean uncertain, jump, statusChanged;
        public final String reason;
        public final long untilNs;
        private State(boolean uncertain, boolean jump, boolean statusChanged, String reason, long untilNs) {
            this.uncertain = uncertain; this.jump = jump; this.statusChanged = statusChanged;
            this.reason = reason; this.untilNs = untilNs;
        }
    }

    /** Bias can be NaN when no uncalibrated sensor is available. Accuracy uses Android values -1..3. */
    public synchronized State observe(long timestampNs, int accuracy, double biasX, double biasY, double biasZ) {
        if (timestampNs <= 0 || timestampNs < lastTime)
            return new State(true, false, false, "invalid_sensor_time", until);
        if (accuracy < -1 || accuracy > 3)
            return new State(true, false, false, "invalid_sensor_accuracy", until);
        boolean available = finite(biasX, biasY, biasZ);
        boolean jump = available && finite(bx, by, bz)
            && Math.sqrt(square(biasX - bx) + square(biasY - by) + square(biasZ - bz)) > threshold;
        boolean changed = previousAccuracy >= 0 && accuracy >= 0 && accuracy != previousAccuracy;
        if (jump || changed) until = timestampNs > Long.MAX_VALUE - cooldown ? Long.MAX_VALUE : timestampNs + cooldown;
        if (available) { bx = biasX; by = biasY; bz = biasZ; }
        previousAccuracy = accuracy;
        lastTime = timestampNs;
        boolean unreliable = accuracy < 2;
        boolean cooling = timestampNs < until;
        String reason = jump ? "calibration_bias_jump" : changed ? "sensor_accuracy_change"
            : unreliable ? "sensor_unreliable" : cooling ? "calibration_cooldown" : "stable";
        return new State(unreliable || cooling, jump, changed, reason, until);
    }

    public synchronized void reset() {
        bx = by = bz = Double.NaN; previousAccuracy = -1; lastTime = until = 0;
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
    private static double square(double n) { return n * n; }
}
