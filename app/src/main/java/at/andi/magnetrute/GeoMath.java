package at.andi.magnetrute;

/** WGS84 coordinates and Web Mercator/XYZ tile pixels. Never mixes latitude/longitude order. */
public final class GeoMath {
    public static final double EARTH_RADIUS_METERS = 6_378_137.0;
    public static final double MAX_MERCATOR_LATITUDE = 85.0511287798066;
    public static final int TILE_SIZE = 256;

    private GeoMath() { }

    public static boolean valid(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
            && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    /** Haversine distance in metres; the shortest arc also works across the date line. */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        if (!valid(lat1, lon1) || !valid(lat2, lon2)) throw new IllegalArgumentException("Ungültige WGS84-Koordinate");
        double aLat = Math.toRadians(lat1), bLat = Math.toRadians(lat2);
        double deltaLat = bLat - aLat, deltaLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
            + Math.cos(aLat) * Math.cos(bLat) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.max(0, Math.min(1, h))));
    }

    /** Global Web Mercator pixel position at integer zoom; return order is x,y. */
    public static double[] world(double lat, double lon, int zoom) {
        if (!valid(lat, lon)) throw new IllegalArgumentException("Ungültige WGS84-Koordinate");
        double size = size(zoom);
        double clipped = Math.max(-MAX_MERCATOR_LATITUDE, Math.min(MAX_MERCATOR_LATITUDE, lat));
        double latitude = Math.toRadians(clipped);
        double x = (lon + 180) / 360 * size;
        double y = (1 - Math.log(Math.tan(Math.PI / 4 + latitude / 2)) / Math.PI) / 2 * size;
        return new double[] {x, Math.max(0, Math.min(size, y))};
    }

    /** Inverse global pixel projection. X wraps at the date line; y clamps at polar map edges. */
    public static double[] coordinate(double x, double y, int zoom) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Ungültige Kartenposition");
        double size = size(zoom);
        double wrappedX = ((x % size) + size) % size;
        // Preserve the positive date-line edge for the exact endpoint, useful for tile bounds.
        double lon = x == size ? 180 : wrappedX / size * 360 - 180;
        double clampedY = Math.max(0, Math.min(size, y));
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * clampedY / size))));
        return new double[] {lat, lon};
    }

    /** Ground resolution near latitude; this does not improve GPS accuracy. */
    public static double metersPerPixel(double lat, int zoom) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) throw new IllegalArgumentException("Ungültige Breite");
        return 2 * Math.PI * EARTH_RADIUS_METERS * Math.cos(Math.toRadians(
            Math.max(-MAX_MERCATOR_LATITUDE, Math.min(MAX_MERCATOR_LATITUDE, lat)))) / size(zoom);
    }

    private static double size(int zoom) {
        if (zoom < 0 || zoom > 30) throw new IllegalArgumentException("Zoom muss zwischen 0 und 30 liegen");
        return Math.scalb((double) TILE_SIZE, zoom);
    }
}
