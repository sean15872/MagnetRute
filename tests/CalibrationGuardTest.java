import at.andi.magnetrute.CalibrationGuard;

public final class CalibrationGuardTest {
    private static int checks;
    private static final long T=20_000_000_000L;
    public static void main(String[] args) {
        CalibrationGuard guard=new CalibrationGuard();
        CalibrationGuard.State s=guard.observe(T,3,0,0,0);
        check(!s.uncertain&&!s.jump&&!s.statusChanged,"initial stable sensor");
        s=guard.observe(T+1,3,.2,.3,.1);
        check(!s.uncertain&&!s.jump,"small bias change stable");
        s=guard.observe(T+2,3,10,0,0);
        check(s.uncertain&&s.jump&&!s.statusChanged&&s.reason.equals("calibration_bias_jump"),"bias jump flagged");
        check(s.untilNs==T+2+CalibrationGuard.DEFAULT_COOLDOWN_NS,"cooldown monotonic deadline");
        s=guard.observe(T+1_000_000_000L,3,10,0,0);
        check(s.uncertain&&!s.jump&&s.reason.equals("calibration_cooldown"),"jump does not repeat for same offset");
        s=guard.observe(T+6_000_000_000L,3,10,0,0);
        check(!s.uncertain&&!s.jump,"cooldown returns stable");
        s=guard.observe(T+7_000_000_000L,0,10,0,0);
        check(s.uncertain&&s.statusChanged&&!s.jump,"accuracy drop flagged independently");
        s=guard.observe(T+20_000_000_000L,0,10,0,0);
        check(s.uncertain&&s.reason.equals("sensor_unreliable"),"unreliable accuracy remains uncertain");
        s=guard.observe(T+21_000_000_000L,3,Double.NaN,Double.NaN,Double.NaN);
        check(s.uncertain&&s.statusChanged&&!s.jump,"missing bias does not invent a jump");
        s=guard.observe(T+30_000_000_000L,3,Double.NaN,Double.NaN,Double.NaN);
        check(!s.uncertain&&!s.jump,"high accuracy without bias supported after cooldown");
        s=guard.observe(T+29_000_000_000L,3,0,0,0);
        check(s.uncertain&&s.reason.equals("invalid_sensor_time"),"out of order timestamps do not reset state");
        s=guard.observe(T+31_000_000_000L,99,0,0,0);
        check(s.uncertain&&s.reason.equals("invalid_sensor_accuracy"),"invalid accuracy rejected");
        guard.reset();
        s=guard.observe(T,3,99,99,99);
        check(!s.uncertain&&!s.jump,"reset accepts independent sensor bias");
        s=guard.observe(T+1,-1,99,99,99);
        check(s.uncertain,"unknown accuracy is not confirmed anomaly");
        invalid(()->new CalibrationGuard(0,1));invalid(()->new CalibrationGuard(Double.NaN,1));invalid(()->new CalibrationGuard(3,-1));
        System.out.println("CalibrationGuard: "+checks+" checks OK");
    }
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void invalid(Runnable action){try{action.run();throw new AssertionError("Invalid guard accepted");}catch(IllegalArgumentException expected){checks++;}}
}
