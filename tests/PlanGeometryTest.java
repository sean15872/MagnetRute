import at.andi.magnetrute.PlanGeometry;

/** Exercises perspective geometry without Android or additional test dependencies. */
public final class PlanGeometryTest {
    private static int checks;
    private static void ok(boolean b,String message) {checks++; if(!b) throw new AssertionError(message);}
    private static void eq(double a,double b,double eps,String message) {ok(Double.isFinite(a)&&Math.abs(a-b)<=eps,message+": "+a+" != "+b);}
    private static void rejects(Runnable r,String message) {boolean rejected=false;try{r.run();}catch(IllegalArgumentException e){rejected=true;}ok(rejected,message);}
    public static void main(String[] args) {
        double[] trapezoid={100,50, 900,90, 1100,800, 40,850};
        double[] rectangle={0,0, 1200,0, 1200,900, 0,900};
        ok(PlanGeometry.validQuad(trapezoid),"photographed plan accepted");
        double[] h=PlanGeometry.homography(trapezoid,rectangle);
        for(int i=0;i<4;i++) {
            double[] p=PlanGeometry.map(h,trapezoid[2*i],trapezoid[2*i+1]);
            eq(p[0],rectangle[2*i],1e-8,"corner x");eq(p[1],rectangle[2*i+1],1e-8,"corner y");
        }
        double[] inverse=PlanGeometry.homography(rectangle,trapezoid);
        double[] p=PlanGeometry.map(h,450,400),q=PlanGeometry.map(inverse,p[0],p[1]);
        eq(q[0],450,1e-8,"inverse x");eq(q[1],400,1e-8,"inverse y");
        eq(PlanGeometry.map(h,100+(900-100)*.3,50+(90-50)*.3)[1],0,1e-8,"straight paper edge remains straight");
        double[] large={100000000,200000000,100001000,200000000,100001000,200000500,100000000,200000500};
        double[] largeH=PlanGeometry.homography(large,rectangle);
        double[] largeCenter=PlanGeometry.map(largeH,100000500,200000250);
        eq(largeCenter[0],600,1e-5,"normalized large-coordinate x");eq(largeCenter[1],450,1e-5,"normalized large-coordinate y");
        ok(!PlanGeometry.validQuad(new double[]{0,0,100,100,100,0,0,100}),"self intersection rejected");
        ok(!PlanGeometry.validQuad(new double[]{0,0,100,0,40,20,0,100}),"concavity rejected");
        ok(!PlanGeometry.validQuad(new double[]{0,0,100,0,100,0,0,100}),"duplicate corners rejected");
        ok(!PlanGeometry.validQuad(new double[]{0,0,1,0,1,1,0,1}),"subpixel/tiny paper rejected");
        ok(!PlanGeometry.validQuad(new double[]{0,0,100,0,100,Double.NaN,0,100}),"non-finite rejected");
        ok(!PlanGeometry.validQuad(null),"null rejected");
        rejects(()->PlanGeometry.homography(new double[]{0,0,1,0,2,0,3,0},rectangle),"singular homography rejected");
        rejects(()->PlanGeometry.map(new double[]{1,0,0,0,1,0,0,0,0},1,1),"point at infinity rejected");
        int[] size=PlanGeometry.outputSize(new double[]{0,0,4000,0,4000,2000,0,2000},2400);
        ok(size[0]==2400&&size[1]==1200,"bounded aspect-correct output");
        int[] same=PlanGeometry.outputSize(new double[]{0,0,100,0,100,80,0,80},2400);
        ok(same[0]==100&&same[1]==80,"small image not upscaled");
        rejects(()->PlanGeometry.outputSize(rectangle,1),"invalid edge limit");
        eq(PlanGeometry.metersPerPixel(0,0,300,400,5),.01,1e-12,"3-4-5 calibration");
        rejects(()->PlanGeometry.metersPerPixel(1,1,1,1,3),"coincident calibration points");
        rejects(()->PlanGeometry.metersPerPixel(0,0,10,10,0),"zero meters");
        rejects(()->PlanGeometry.metersPerPixel(0,0,10,10,-1),"negative meters");
        rejects(()->PlanGeometry.metersPerPixel(0,0,10,10,Double.NaN),"nonfinite meters");
        rejects(()->PlanGeometry.metersPerPixel(0,0,Double.POSITIVE_INFINITY,10,1),"nonfinite points");
        System.out.println("PlanGeometry: "+checks+" checks OK");
    }
}
