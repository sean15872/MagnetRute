import at.andi.magnetrute.FieldGrid;
import at.andi.magnetrute.SurveyComparison;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SurveyComparisonTest {
    private static int checks;
    private static final double LAT=47.1668,LON=14.0062;
    public static void main(String[] args) {
        List<FieldGrid.Sample> forwardAndReturn=new ArrayList<>();
        for(int i=0;i<1000;i++) forwardAndReturn.add(sample(10,"session-a:forward"));
        forwardAndReturn.add(sample(12,"session-a:return"));
        SurveyComparison.ComparisonCell c=one(forwardAndReturn,3);
        near(c.median,11,"unequal dwell preserves equal traversal weight");
        near(c.spread,2,"spread is per-pass max minus min");
        check(c.independentPasses==2&&c.agreeingPasses==2&&c.count==1001,"passes distinct from raw sample count");
        check(c.repeated&&c.consistent,"positive forward/return anomaly consistent");
        near(c.accuracy,4,"GPS uncertainty retained");
        near(c.cellMeters,10,"physical cell size retained");
        c=one(Arrays.asList(sample(-10,"out"),sample(-12,"back")),3);
        check(c.repeated&&c.consistent,"negative anomaly can repeat");near(c.median,-11,"negative signed median");

        c=one(Arrays.asList(sample(10,"a"),sample(-10,"b")),3);
        check(!c.repeated&&!c.consistent,"opposite signs are not repeated evidence");near(c.median,0,"contradictory median");
        c=one(Arrays.asList(sample(10,"a"),sample(11,"b"),sample(-10,"c")),3);
        check(c.repeated&&!c.consistent&&c.agreeingPasses==2,"repeated but contradictory observations remain uncertain");
        c=one(Arrays.asList(sample(10,"a"),sample(100,"b")),3);
        check(c.repeated&&!c.consistent,"same sign with huge spread is not consistent");
        c=one(Arrays.asList(sample(4,"a"),sample(6,"b")),3);
        check(c.consistent,"absolute threshold provides tolerance floor");
        c=one(Arrays.asList(sample(10,"a"),sample(15,"b")),1);
        check(c.consistent,"relative half-median tolerance supports strong agreeing anomalies");
        c=one(Arrays.asList(sample(3,"a"),sample(3,"b")),3);
        check(!c.repeated&&!c.consistent,"threshold boundary is not an exceedance");
        c=one(Arrays.asList(sample(1,"a"),sample(1,"b")),3);
        check(!c.repeated&&!c.consistent,"repeated neutral values do not become anomalies");
        c=one(Collections.singletonList(sample(10,"only")),3);
        check(c.independentPasses==1&&!c.repeated&&!c.consistent,"one pass is never confirmed");
        c=one(Arrays.asList(sample(10,"same"),sample(10,"same"),sample(10,"same")),3);
        check(c.independentPasses==1&&!c.repeated,"one traversal cannot masquerade as several passes");
        c=one(Arrays.asList(sample(9,"a"),sample(10,"a"),sample(1000,"a"),sample(10,"b")),3);
        near(c.median,10,"within-pass outlier reduced by median");check(c.consistent,"robust pass medians compared");

        List<FieldGrid.Sample> doubtful=new ArrayList<>(forwardAndReturn);
        doubtful.add(new FieldGrid.Sample(LAT,LON,900,-100,"bad-gps",1,false));
        doubtful.add(new FieldGrid.Sample(LAT,LON,4,-100,"calibration",1,true));
        doubtful.add(new FieldGrid.Sample(LAT,LON,4,Double.NaN,"invalid",1,false));
        c=one(doubtful,3);
        check(c.consistent&&c.independentPasses==2&&c.count==1001,"uncertain fixes and calibration jumps excluded consistently");
        check(SurveyComparison.compare(Collections.singletonList(new FieldGrid.Sample(LAT,LON,20,9,"a",1,false)),10,3).isEmpty(),
            "cell smaller than GPS uncertainty stays empty");
        List<FieldGrid.Sample> separated=Arrays.asList(sample(10,"a"),sample(10,"b"),
            new FieldGrid.Sample(LAT+.001,LON,4,-10,"a",1,false));
        List<SurveyComparison.ComparisonCell> cells=SurveyComparison.compare(separated,10,3);
        check(cells.size()==2,"unmeasured spatial cells never invented");
        int consistent=0;for(SurveyComparison.ComparisonCell cell:cells) if(cell.consistent) consistent++;
        check(consistent==1,"one-pass neighbouring cell cannot borrow confirmation");
        List<FieldGrid.Sample> reversed=new ArrayList<>(forwardAndReturn);Collections.reverse(reversed);
        SurveyComparison.ComparisonCell reordered=one(reversed,3),original=one(forwardAndReturn,3);
        near(reordered.lat,original.lat,"stable latitude across ordering");near(reordered.lon,original.lon,"stable longitude across ordering");
        near(reordered.median,original.median,"ordering does not influence evidence");
        check(SurveyComparison.compare(Collections.emptyList(),10,3).isEmpty(),"no samples means no comparison");
        c=one(Arrays.asList(sample(1,"a"),sample(1,"b")),0);check(c.consistent,"explicit zero threshold supported");
        invalid(()->SurveyComparison.compare(forwardAndReturn,10,-1));
        invalid(()->SurveyComparison.compare(forwardAndReturn,10,Double.NaN));
        invalid(()->SurveyComparison.compare(forwardAndReturn,10,Double.POSITIVE_INFINITY));
        invalid(()->SurveyComparison.compare(forwardAndReturn,0,3));
        invalid(()->SurveyComparison.compare(null,10,3));
        System.out.println("SurveyComparison: "+checks+" checks OK");
    }
    private static FieldGrid.Sample sample(double value,String pass) {return new FieldGrid.Sample(LAT,LON,4,value,pass,1,false);}
    private static SurveyComparison.ComparisonCell one(List<FieldGrid.Sample> samples,double threshold){
        List<SurveyComparison.ComparisonCell> result=SurveyComparison.compare(samples,10,threshold);
        if(result.size()!=1)throw new AssertionError("Expected one measured cell, got "+result.size());return result.get(0);
    }
    private static void near(double actual,double expected,String label){check(Double.isFinite(actual)&&Math.abs(actual-expected)<1e-10,label);}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void invalid(Runnable action){try{action.run();throw new AssertionError("Invalid input accepted");}catch(IllegalArgumentException expected){checks++;}}
}
