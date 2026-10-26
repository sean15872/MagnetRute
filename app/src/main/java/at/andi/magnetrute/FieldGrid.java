package at.andi.magnetrute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Robust field values in actually measured metric grid cells, never a point-density heatmap.
 * Latitude bands have a fixed height in metres. Each band uses its centre latitude to
 * convert longitude to metres, avoiding Web Mercator's area inflation at Austrian latitudes.
 * The equator/Greenwich origin makes cell membership stable across sessions and subsets.
 */
public final class FieldGrid {
    private static final double R=6378137.0;
    private static final double LIMIT=85.0511287798066;

    private FieldGrid() { }

    public static final class Sample {
        public final double lat,lon,accuracy,value;
        /** Must identify an independent traversal, rather than individual sensor samples. */
        public final String passId;
        public final long timeNs;
        /** Includes calibration jumps, unreliable sensor status, or unaccepted GPS association. */
        public final boolean uncertain;

        public Sample(double lat,double lon,double accuracy,double value,String passId,long timeNs,boolean uncertain) {
            this.lat=lat;this.lon=lon;this.accuracy=accuracy;this.value=value;
            this.passId=passId;this.timeNs=timeNs;this.uncertain=uncertain;
        }
    }

    public static final class Cell {
        /** Centre of a measured cell, not a claimed exact position of a magnetic source. */
        public final double lat,lon,value,accuracy,cellMeters;
        /** Accepted raw sample count and independent traversal count remain separate. */
        public final int count,passes;
        /** True only if optional spatial smoothing combined neighbouring measured cells. */
        public final boolean smoothed;

        private Cell(double lat,double lon,double value,double accuracy,double cellMeters,int count,int passes,boolean smoothed) {
            this.lat=lat;this.lon=lon;this.value=value;this.accuracy=accuracy;
            this.cellMeters=cellMeters;this.count=count;this.passes=passes;this.smoothed=smoothed;
        }
    }

    private static final class Key implements Comparable<Key> {
        final long row,column;
        Key(long row,long column) { this.row=row;this.column=column; }
        @Override public int compareTo(Key other) {
            int r=Long.compare(row,other.row);return r==0?Long.compare(column,other.column):r;
        }
    }

    private static final class Bucket {
        final Map<String,List<Double>> visits=new LinkedHashMap<>();
        int count;
        double worstAccuracy;
        void add(Sample sample) {
            List<Double> values=visits.get(sample.passId);
            if(values==null) { values=new ArrayList<>();visits.put(sample.passId,values); }
            values.add(sample.value);count++;worstAccuracy=Math.max(worstAccuracy,sample.accuracy);
        }
    }

    /**
     * Median within each independent pass, then median between passes. A long stationary
     * dwell therefore cannot outweigh a different traversal. Caller defines the displayed
     * physical value (absolute µT, signed delta, or absolute delta) in Sample.value.
     * Cells smaller than reported GPS uncertainty exclude that sample instead of implying
     * false precision; unknown accuracy and unreliable/calibration-transition samples stay empty.
     */
    public static List<Cell> aggregate(List<Sample> samples,double cellMeters) {
        if(!Double.isFinite(cellMeters) || cellMeters<1 || cellMeters>100000)
            throw new IllegalArgumentException("Rastergröße muss zwischen 1 und 100000 Metern liegen.");
        if(samples==null) throw new IllegalArgumentException("Messwerte fehlen.");
        TreeMap<Key,Bucket> buckets=new TreeMap<>();
        for(Sample sample:samples) {
            if(!usable(sample,cellMeters)) continue;
            long row=(long)Math.floor(R*Math.toRadians(sample.lat)/cellMeters);
            double latitude=Math.toDegrees((row+.5)*cellMeters/R);
            // A terminal band may cross the renderable latitude; use its clipped centre.
            latitude=Math.max(-LIMIT,Math.min(LIMIT,latitude));
            double longitudeMeters=R*Math.toRadians(sample.lon)*Math.cos(Math.toRadians(latitude));
            long column=(long)Math.floor(longitudeMeters/cellMeters);
            Key key=new Key(row,column);Bucket bucket=buckets.get(key);
            if(bucket==null) { bucket=new Bucket();buckets.put(key,bucket); }
            bucket.add(sample);
        }
        List<Cell> cells=new ArrayList<>();
        for(Map.Entry<Key,Bucket> entry:buckets.entrySet()) {
            Key key=entry.getKey();Bucket bucket=entry.getValue();List<Double> passValues=new ArrayList<>();
            for(List<Double> values:bucket.visits.values()) passValues.add(median(values));
            double latitude=Math.toDegrees((key.row+.5)*cellMeters/R);
            latitude=Math.max(-LIMIT,Math.min(LIMIT,latitude));
            double longitude=Math.toDegrees((key.column+.5)*cellMeters/(R*Math.cos(Math.toRadians(latitude))));
            longitude=Math.max(-180,Math.min(180,longitude));
            cells.add(new Cell(latitude,longitude,median(passValues),bucket.worstAccuracy,
                cellMeters,bucket.count,bucket.visits.size(),false));
        }
        return Collections.unmodifiableList(cells);
    }

    /** Optional median smoothing changes only occupied cells; never fills an unmeasured gap. */
    public static List<Cell> smooth(List<Cell> cells,double radiusMeters) {
        if(cells==null || !Double.isFinite(radiusMeters) || radiusMeters<0 || radiusMeters>100000)
            throw new IllegalArgumentException("Ungültiger Glättungsradius.");
        if(radiusMeters==0) return Collections.unmodifiableList(new ArrayList<>(cells));
        List<Cell> result=new ArrayList<>();
        for(Cell cell:cells) {
            if(cell==null) throw new IllegalArgumentException("Rasterzelle fehlt.");
            List<Double> neighbouringValues=new ArrayList<>();double accuracy=cell.accuracy;
            for(Cell neighbour:cells) {
                if(neighbour==null) throw new IllegalArgumentException("Rasterzelle fehlt.");
                if(distance(cell.lat,cell.lon,neighbour.lat,neighbour.lon)<=radiusMeters) {
                    neighbouringValues.add(neighbour.value);accuracy=Math.max(accuracy,neighbour.accuracy);
                }
            }
            result.add(new Cell(cell.lat,cell.lon,median(neighbouringValues),accuracy,cell.cellMeters,
                cell.count,cell.passes,neighbouringValues.size()>1));
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean usable(Sample sample,double cellMeters) {
        return sample!=null && Double.isFinite(sample.lat) && Math.abs(sample.lat)<=LIMIT
            && Double.isFinite(sample.lon) && Math.abs(sample.lon)<=180
            && Double.isFinite(sample.value) && Double.isFinite(sample.accuracy)
            && sample.accuracy>0 && sample.accuracy<=cellMeters && !sample.uncertain
            && sample.passId!=null && !sample.passId.trim().isEmpty() && sample.timeNs>0;
    }

    private static double median(List<Double> values) {
        List<Double> sorted=new ArrayList<>(values);Collections.sort(sorted);int middle=sorted.size()/2;
        if(sorted.size()%2==1) return sorted.get(middle);
        // Half first avoids overflow for finite extreme positive/negative sensor values.
        return sorted.get(middle-1)/2+sorted.get(middle)/2;
    }

    private static double distance(double lat1,double lon1,double lat2,double lon2) {
        double a=Math.sin(Math.toRadians(lat2-lat1)/2),b=Math.sin(Math.toRadians(lon2-lon1)/2);
        double h=a*a+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*b*b;
        return 2*R*Math.asin(Math.sqrt(Math.min(1,Math.max(0,h))));
    }
}
