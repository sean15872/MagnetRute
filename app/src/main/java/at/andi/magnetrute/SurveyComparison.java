package at.andi.magnetrute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compares signed field differences in occupied cells, with equal weight per traversal. */
public final class SurveyComparison {
    private SurveyComparison() { }

    public static final class ComparisonCell {
        public final double lat, lon, median, spread, accuracy, cellMeters;
        public final int count, independentPasses, agreeingPasses;
        /** At least two passes exceed the requested anomaly threshold in the same direction. */
        public final boolean repeated;
        /** All passes agree and their spread remains within the documented tolerance. */
        public final boolean consistent;

        private ComparisonCell(FieldGrid.Cell cell, double spread, int agreeingPasses,
                               boolean repeated, boolean consistent) {
            lat = cell.lat; lon = cell.lon; median = cell.value; accuracy = cell.accuracy;
            cellMeters = cell.cellMeters; count = cell.count; independentPasses = cell.passes;
            this.spread = spread; this.agreeingPasses = agreeingPasses;
            this.repeated = repeated; this.consistent = consistent;
        }
    }

    /**
     * Values must be signed deltas to each survey's recorded reference, never absolute field
     * values or point counts. passId identifies an independent session/traversal. FieldGrid
     * excludes invalid GPS and uncertain calibration samples before making per-pass medians.
     *
     * A consistent repetition requires at least two passes, every pass's median strictly above
     * the threshold in the same direction, and max-min spread no greater than the larger of
     * the threshold and half the absolute overall median. This is evidence consistency rather
     * than a confidence interval or a claim about a physical underground object.
     */
    public static List<ComparisonCell> compare(List<FieldGrid.Sample> samples, double cellMeters,
                                               double anomalyThreshold) {
        if (!Double.isFinite(anomalyThreshold) || anomalyThreshold < 0)
            throw new IllegalArgumentException("Vergleichsschwelle muss endlich und nicht negativ sein.");
        List<FieldGrid.Cell> occupied = FieldGrid.aggregate(samples, cellMeters);
        Map<String, List<FieldGrid.Sample>> traversals = new LinkedHashMap<>();
        for (FieldGrid.Sample sample : samples) {
            if (sample == null || sample.passId == null || sample.passId.trim().isEmpty()) continue;
            List<FieldGrid.Sample> pass = traversals.get(sample.passId);
            if (pass == null) { pass = new ArrayList<>(); traversals.put(sample.passId, pass); }
            pass.add(sample);
        }
        Map<Key, List<Double>> perPassValues = new HashMap<>();
        for (List<FieldGrid.Sample> pass : traversals.values()) {
            for (FieldGrid.Cell cell : FieldGrid.aggregate(pass, cellMeters)) {
                Key key = new Key(cell.lat, cell.lon);
                List<Double> values = perPassValues.get(key);
                if (values == null) { values = new ArrayList<>(); perPassValues.put(key, values); }
                values.add(cell.value);
            }
        }
        List<ComparisonCell> result = new ArrayList<>();
        for (FieldGrid.Cell cell : occupied) {
            List<Double> medians = perPassValues.get(new Key(cell.lat, cell.lon));
            int positive = 0, negative = 0;
            double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            for (double value : medians) {
                if (value > anomalyThreshold) positive++;
                if (value < -anomalyThreshold) negative++;
                minimum = Math.min(minimum, value); maximum = Math.max(maximum, value);
            }
            double spread = maximum - minimum;
            if (!Double.isFinite(spread)) spread = Double.MAX_VALUE;
            int agreeing = Math.max(positive, negative);
            boolean repeated = agreeing >= 2;
            double tolerance = Math.max(anomalyThreshold, Math.abs(cell.value) * .5);
            boolean consistent = repeated && agreeing == cell.passes && spread <= tolerance;
            result.add(new ComparisonCell(cell, spread, agreeing, repeated, consistent));
        }
        return Collections.unmodifiableList(result);
    }

    /** FieldGrid computes identical anchored centres for a cell in every subset. */
    private static final class Key {
        final long lat, lon;
        Key(double latitude, double longitude) {
            lat = Double.doubleToLongBits(latitude); lon = Double.doubleToLongBits(longitude);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return lat == key.lat && lon == key.lon;
        }
        @Override public int hashCode() { return 31 * Long.hashCode(lat) + Long.hashCode(lon); }
    }
}
