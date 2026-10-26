package at.andi.magnetrute;

/** Mathematischer Kern ohne Android-Abhaengigkeiten, getrennt testbar. */
public final class SensorMath {
    private SensorMath() { }
    public static final class Components {
        public final double total, vertical, horizontal;
        public final boolean gravityValid;
        Components(double total, double vertical, double horizontal, boolean valid) {
            this.total = total;
            this.vertical = vertical;
            this.horizontal = horizontal;
            this.gravityValid = valid;
        }
    }

    /**
     * Vertikal = Projektion des Magnetfeldvektors auf Schwerkraftrichtung.
     * Horizontal = Norm der zur Schwerkraft senkrechten Komponente.
     * Beide sind rotationsinvariant unter gleichzeitiger Rotation beider Sensorachsen.
     * Vorzeichen der Vertikalkomponente zeigt hier in Schwerkraftrichtung ("unten").
     */
    public static Components components(double mx, double my, double mz,
                                        double gx, double gy, double gz,
                                        boolean hasGravity) {
        double total = Math.sqrt(mx * mx + my * my + mz * mz);
        double g = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (!hasGravity || !Double.isFinite(g) || g < 1.0 || !Double.isFinite(total)) {
            return new Components(total, Double.NaN, Double.NaN, false);
        }
        double vertical = (mx * gx + my * gy + mz * gz) / g;
        double horizontal = Math.sqrt(Math.max(0.0, total * total - vertical * vertical));
        return new Components(total, vertical, horizontal, true);
    }

    public static double clamp(double a, double min, double max) {
        return Math.max(min, Math.min(max, a));
    }
}
