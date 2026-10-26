package at.andi.magnetrute;

/** Quality decisions for cartography only. Callers must persist rejected raw fixes unchanged. */
public final class GpsQuality {
    public static final long MAX_AGE_NS = 5_000_000_000L;
    public static final double DEFAULT_ACCURACY_METERS = 10;
    public static final double MAX_WALK_SPEED_METERS_PER_SECOND = 3.5;
    public static final double MAX_NETWORK_ACCURACY_METERS = 50;

    public static final class Fix {
        public final double lat, lon, accuracy, speed;
        public final long elapsedNs, unixMs;
        public final String provider;

        public Fix(double lat, double lon, double accuracy, long elapsedNs, long unixMs,
                   String provider, double speed) {
            this.lat = lat; this.lon = lon; this.accuracy = accuracy;
            this.elapsedNs = elapsedNs; this.unixMs = unixMs; this.provider = provider; this.speed = speed;
        }
    }

    public static final class Decision {
        public final boolean accepted, breakTrack;
        public final String reason;
        public final Fix fix;

        private Decision(Fix fix, boolean accepted, String reason, boolean breakTrack) {
            this.fix = fix; this.accepted = accepted; this.reason = reason; this.breakTrack = breakTrack;
        }
    }

    private Fix accepted;
    private Fix candidate;
    private boolean gap = true;

    /**
     * Uses monotonic times to bind GPS to sensor measurements. An accepted sample after any
     * rejected fix starts a fresh segment; no line should bridge the intervening uncertain gap.
     */
    public synchronized Decision inspect(Fix fix, long nowNs, double maxAccuracy) {
        if (!Double.isFinite(maxAccuracy) || maxAccuracy <= 0)
            throw new IllegalArgumentException("GPS-Genauigkeitsgrenze muss positiv sein");
        if (fix == null) return reject(null, "missing_fix");
        if (!GeoMath.valid(fix.lat, fix.lon) || !Double.isFinite(fix.accuracy) || fix.accuracy <= 0
                || fix.elapsedNs <= 0 || fix.unixMs <= 0 || fix.provider == null || fix.provider.trim().isEmpty()
                || (!Double.isNaN(fix.speed) && (!Double.isFinite(fix.speed) || fix.speed < 0)))
            return reject(fix, "invalid_fix");
        if (nowNs < fix.elapsedNs) return reject(fix, "future_fix");
        if (nowNs - fix.elapsedNs > MAX_AGE_NS) return reject(fix, "stale_fix");
        if ("network".equalsIgnoreCase(fix.provider) && fix.accuracy > MAX_NETWORK_ACCURACY_METERS)
            return reject(fix, "network_accuracy");
        if (fix.accuracy > maxAccuracy) return reject(fix, "accuracy");
        if (fix.speed > MAX_WALK_SPEED_METERS_PER_SECOND) return reject(fix, "speed");
        // The first GPS fix after a cold acquisition may be displaced by tens
        // of metres, even when the reported accuracy is deceptively small.
        // Use two mutually compatible fixes to initialize a walking segment.
        if (accepted == null) {
            if (candidate == null || fix.elapsedNs <= candidate.elapsedNs ||
                    fix.elapsedNs - candidate.elapsedNs > MAX_AGE_NS) {
                candidate=fix;
                return reject(fix,"gps_acquiring");
            }
            double elapsed=(fix.elapsedNs-candidate.elapsedNs)/1_000_000_000.0;
            double moved=GeoMath.distance(candidate.lat,candidate.lon,fix.lat,fix.lon);
            if (moved>candidate.accuracy+fix.accuracy+elapsed*MAX_WALK_SPEED_METERS_PER_SECOND) {
                candidate=fix;
                return reject(fix,"gps_acquiring");
            }
            candidate=null;accepted=fix;gap=false;
            return new Decision(fix,true,"accepted",true);
        }

        if (accepted != null && fix.elapsedNs <= accepted.elapsedNs) return reject(fix, "non_monotonic_fix");
        boolean stalePrevious = accepted == null || fix.elapsedNs - accepted.elapsedNs > MAX_AGE_NS;
        if (accepted != null) {
            double seconds = (fix.elapsedNs - accepted.elapsedNs) / 1_000_000_000.0;
            double travelled = GeoMath.distance(accepted.lat, accepted.lon, fix.lat, fix.lon);
            // The two accuracy radii can account for apparent movement without inventing a path.
            if (travelled > accepted.accuracy + fix.accuracy + seconds * MAX_WALK_SPEED_METERS_PER_SECOND)
                return reject(fix, "position_jump");
        }
        boolean breakTrack = gap || stalePrevious;
        accepted = fix;
        gap = false;
        return new Decision(fix, true, "accepted", breakTrack);
    }

    /** A historic accepted fix is never advertised as a current position after five seconds. */
    public synchronized Fix current(long timestampNs) {
        if (accepted == null || timestampNs < accepted.elapsedNs || timestampNs - accepted.elapsedNs > MAX_AGE_NS)
            return null;
        return accepted;
    }

    /** Pause/resume and reference-session boundaries must explicitly break a track. */
    public synchronized void reset() { accepted = null; candidate = null; gap = true; }

    private Decision reject(Fix fix, String reason) {
        gap = true;
        return new Decision(fix, false, reason, true);
    }
}
