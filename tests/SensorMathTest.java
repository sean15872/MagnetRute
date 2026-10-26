import at.andi.magnetrute.SensorMath;

/** Ohne JUnit, fuer lokale Java-17-Tests ohne Android SDK. */
public final class SensorMathTest {
    static void eq(double actual, double expected, double eps, String message) {
        if(Math.abs(actual-expected)>eps) throw new AssertionError(message+": "+actual+" != "+expected);
    }
    public static void main(String[] args) {
        SensorMath.Components a=SensorMath.components(30,40,0,0,0,9.81,true);
        eq(a.total,50,1e-10,"B total");
        eq(a.vertical,0,1e-10,"vertical");
        eq(a.horizontal,50,1e-10,"horizontal");
        // 90-grad-Rotation beider Vektoren: gleiche Komponenten.
        SensorMath.Components b=SensorMath.components(-40,30,0,0,0,9.81,true);
        eq(b.total,a.total,1e-10,"rotation total");
        eq(b.vertical,a.vertical,1e-10,"rotation vertical");
        eq(b.horizontal,a.horizontal,1e-10,"rotation horizontal");
        SensorMath.Components c=SensorMath.components(3,4,12,0,0,9.81,true);
        eq(c.total,13,1e-10,"total 13");
        eq(c.vertical,12,1e-10,"vertical 12");
        eq(c.horizontal,5,1e-10,"horizontal 5");
        SensorMath.Components d=SensorMath.components(3,4,12,0,0,0,false);
        if(d.gravityValid || !Double.isNaN(d.vertical) || !Double.isNaN(d.horizontal)) {
            throw new AssertionError("Invalid gravity must produce NaN projections");
        }
        System.out.println("SensorMath: 4 tests OK");
    }
}
