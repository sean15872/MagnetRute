import at.andi.magnetrute.GeoMath;

public final class GeoMathTest {
    private static int checks;
    public static void main(String[] args) {
        check(GeoMath.valid(47.1, 14.1), "Austrian WGS84 coordinate");
        check(!GeoMath.valid(Double.NaN, 14), "NaN latitude");
        check(!GeoMath.valid(91, 14), "latitude bound");
        check(!GeoMath.valid(47, 181), "longitude bound");
        near(0, GeoMath.distance(47,14,47,14), 0, "identical distance");
        near(111319.4908, GeoMath.distance(0,0,0,1), .01, "metres per equatorial degree");
        near(22263.898, GeoMath.distance(0,179.9,0,-179.9), .01, "date line shortest arc");
        near(GeoMath.distance(47,14,48,15),GeoMath.distance(48,15,47,14),.00001,"symmetric distance");
        double[] center=GeoMath.world(0,0,0);
        near(128,center[0],1e-9,"zoom0 center x");near(128,center[1],1e-9,"zoom0 center y");
        for(int zoom:new int[]{0,8,15,19,30}) {
            double[] world=GeoMath.world(47.17654,14.00321,zoom);
            double[] back=GeoMath.coordinate(world[0],world[1],zoom);
            near(47.17654,back[0],1e-9,"Austrian latitude roundtrip z"+zoom);
            near(14.00321,back[1],1e-9,"Austrian longitude roundtrip z"+zoom);
        }
        double[] pole=GeoMath.world(90,0,18);
        check(Double.isFinite(pole[1]) && pole[1]>=0,"polar latitude clamps instead of infinity");
        near(GeoMath.MAX_MERCATOR_LATITUDE,GeoMath.coordinate(pole[0],pole[1],18)[0],1e-9,"polar clamp inverse");
        double[] wrapped=GeoMath.coordinate(-1,128,0);
        near(178.59375,wrapped[1],1e-9,"negative pixels wrap date line");
        near(180,GeoMath.coordinate(256,128,0)[1],0,"positive date line endpoint");
        near(GeoMath.MAX_MERCATOR_LATITUDE,GeoMath.coordinate(128,-999,0)[0],1e-9,"north pixel clamp");
        near(GeoMath.metersPerPixel(0,17)/2,GeoMath.metersPerPixel(0,18),1e-10,"zoom resolution doubles");
        check(GeoMath.metersPerPixel(47,18)<GeoMath.metersPerPixel(0,18),"latitude-aware ground resolution");
        invalid(()->GeoMath.world(0,0,31));invalid(()->GeoMath.coordinate(Double.NaN,0,0));
        invalid(()->GeoMath.distance(0,0,91,0));invalid(()->GeoMath.metersPerPixel(Double.NaN,0));
        System.out.println("GeoMath: "+checks+" checks OK");
    }
    private static void near(double expected,double value,double tolerance,String label) {
        check(Double.isFinite(value)&&Math.abs(expected-value)<=tolerance,label+": "+value);
    }
    private static void check(boolean ok,String label) { checks++;if(!ok)throw new AssertionError(label); }
    private static void invalid(Runnable action) { try {action.run();throw new AssertionError("Invalid value accepted");}catch(IllegalArgumentException expected){checks++;} }
}
