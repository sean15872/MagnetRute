import at.andi.magnetrute.FieldGrid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Geographic cells aggregate physical field values independently of sample dwell density. */
public final class FieldGridTest {
    private static int checks;
    private static final double LAT=47.1668,LON=14.0062;
    private static FieldGrid.Sample sample(double lat,double lon,double accuracy,double value,String pass,long time,boolean uncertain) {
        return new FieldGrid.Sample(lat,lon,accuracy,value,pass,time,uncertain);
    }
    private static FieldGrid.Sample sample(double value,String pass) {return sample(LAT,LON,4,value,pass,1,false);}
    private static void ok(boolean value,String name) {checks++;if(!value)throw new AssertionError(name);}
    private static void eq(double a,double b,double epsilon,String name) {ok(Double.isFinite(a)&&Math.abs(a-b)<=epsilon,name+": "+a+" != "+b);}
    private static void rejects(Runnable run,String name) {boolean rejected=false;try{run.run();}catch(IllegalArgumentException expected){rejected=true;}ok(rejected,name);}

    public static void main(String[] args) {
        List<FieldGrid.Sample> dwell=new ArrayList<>();
        for(int i=0;i<1000;i++)dwell.add(sample(10,"pass-a"));
        dwell.add(sample(20,"pass-b"));
        List<FieldGrid.Cell> cells=FieldGrid.aggregate(dwell,10);
        ok(cells.size()==1,"same physical location occupies one cell");
        FieldGrid.Cell cell=cells.get(0);
        eq(cell.value,15,1e-12,"stationary thousand samples do not outweigh another pass");
        ok(cell.count==1001 && cell.passes==2,"sample count and independent passes are distinct");
        eq(cell.accuracy,4,0,"reported positional uncertainty retained");
        eq(cell.cellMeters,10,0,"cell dimensions retained");
        ok(!cell.smoothed,"raw measured grid has no spatial interpolation");

        List<FieldGrid.Sample> robust=Arrays.asList(sample(-5,"a"),sample(-4,"a"),sample(800,"a"),sample(-3,"b"),sample(1000,"c"));
        eq(FieldGrid.aggregate(robust,10).get(0).value,-3,0,"medians robust within and across passes");
        List<FieldGrid.Sample> reversed=new ArrayList<>(robust);Collections.reverse(reversed);
        FieldGrid.Cell reversedCell=FieldGrid.aggregate(reversed,10).get(0);
        eq(reversedCell.value,-3,0,"aggregation independent of sample ordering");
        eq(reversedCell.lat,cell.lat,0,"cell latitude stable across subsets");
        eq(reversedCell.lon,cell.lon,0,"cell longitude stable across subsets");
        ok(distance(LAT,LON,cell.lat,cell.lon)<8,"cell centre within half a cell diagonal in Austria");

        List<FieldGrid.Sample> invalid=Arrays.asList(null,sample(1,""),sample(1,null),sample(Double.NaN,"a"),
            sample(LAT,LON,11,1,"a",1,false),sample(LAT,LON,900,1,"a",1,false),sample(LAT,LON,0,1,"a",1,false),
            sample(LAT,LON,-1,1,"a",1,false),sample(LAT,LON,Double.NaN,1,"a",1,false),
            sample(LAT,LON,4,1,"a",0,false),sample(LAT,LON,4,1,"a",1,true),
            sample(90,LON,4,1,"a",1,false),sample(Double.NaN,LON,4,1,"a",1,false),sample(LAT,181,4,1,"a",1,false));
        ok(FieldGrid.aggregate(invalid,10).isEmpty(),"invalid fixes, stale association and calibration transitions stay transparent");
        ok(FieldGrid.aggregate(Collections.singletonList(sample(LAT,LON,10,1,"a",1,false)),10).size()==1,"accuracy boundary accepted");
        ok(FieldGrid.aggregate(Collections.singletonList(sample(1,"a")),3).isEmpty(),"cannot paint a cell smaller than positional uncertainty");
        List<FieldGrid.Sample> uncertain=new ArrayList<>(dwell);uncertain.add(sample(LAT,LON,9,10000,"z",1,true));
        eq(FieldGrid.aggregate(uncertain,10).get(0).value,15,0,"uncertain calibration jump never becomes hot spot");
        eq(FieldGrid.aggregate(Arrays.asList(sample(1,"a"),sample(LAT,LON,8,1,"b",1,false)),10).get(0).accuracy,8,0,"worst accepted accuracy conservatively retained");

        List<FieldGrid.Sample> separated=Arrays.asList(sample(10,"a"),sample(LAT+.001,LON,4,30,"a",1,false));
        List<FieldGrid.Cell> separateCells=FieldGrid.aggregate(separated,10);
        ok(separateCells.size()==2,"empty intervening cells never generated");
        List<FieldGrid.Cell> smoothed=FieldGrid.smooth(separateCells,200);
        ok(smoothed.size()==2,"smoothing preserves unmeasured gaps");
        for(FieldGrid.Cell smoothedCell:smoothed){eq(smoothedCell.value,20,0,"only measured neighbours smoothed");ok(smoothedCell.smoothed,"smoothing explicit");}
        List<FieldGrid.Cell> smallRadius=FieldGrid.smooth(separateCells,5);
        eq(smallRadius.get(0).value,separateCells.get(0).value,0,"small radius preserves isolated value");
        ok(!smallRadius.get(0).smoothed,"isolated cell is not marked as spatial estimate");
        eq(FieldGrid.smooth(separateCells,0).get(0).value,separateCells.get(0).value,0,"zero radius disables smoothing");
        ok(FieldGrid.aggregate(Collections.emptyList(),10).isEmpty(),"no measurements means no heatmap");
        rejects(()->FieldGrid.aggregate(dwell,0),"zero cell size rejected");
        rejects(()->FieldGrid.aggregate(dwell,.1),"centimetre precision rejected");
        rejects(()->FieldGrid.aggregate(dwell,Double.NaN),"NaN cell size rejected");
        rejects(()->FieldGrid.aggregate(null,10),"missing samples rejected");
        rejects(()->FieldGrid.smooth(cells,-1),"negative smoothing rejected");
        rejects(()->FieldGrid.smooth(cells,Double.NaN),"invalid smoothing rejected");
        System.out.println("FieldGrid: "+checks+" checks OK");
    }
    private static double distance(double a,double b,double c,double d) {
        double x=Math.toRadians(d-b)*6378137*Math.cos(Math.toRadians((a+c)/2));
        double y=Math.toRadians(c-a)*6378137;return Math.hypot(x,y);
    }
}
