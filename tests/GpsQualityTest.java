import at.andi.magnetrute.GpsQuality;

public final class GpsQualityTest {
    private static int checks;
    private static final long T=20_000_000_000L;
    public static void main(String[] args) {
        GpsQuality quality=new GpsQuality();
        GpsQuality.Fix first=fix(47,14,4,T,"gps");
        GpsQuality.Decision a=quality.inspect(first,T,10);
        rejection(a,"gps_acquiring");
        check(quality.current(T)==null,"first inaccurate acquisition does not advertise GPS");
        check(quality.current(T+GpsQuality.MAX_AGE_NS)==null,"acquiring fix is not a live location");
        check(quality.current(T+GpsQuality.MAX_AGE_NS+1)==null,"stale current not advertised");
        check(quality.current(T-1)==null,"fix cannot precede a sensor timestamp");
        GpsQuality.Fix second=fix(47.00001,14,4,T+1_000_000_000L,"gps");
        check(quality.inspect(second,second.elapsedNs,10).accepted,"second mutually compatible GPS fix accepted");
        check(quality.current(second.elapsedNs)==second,"accepted fix is the second stable reading");
        check(quality.current(second.elapsedNs+GpsQuality.MAX_AGE_NS)==second,"five second inclusive age");
        GpsQuality.Fix poorNetwork=fix(48,15,900,T+2_000_000_000L,"network");
        rejection(quality.inspect(poorNetwork,poorNetwork.elapsedNs,1000),"network_accuracy");
        check(quality.current(poorNetwork.elapsedNs)==second,"rejection never replaces current good fix");
        GpsQuality.Fix third=fix(47.00002,14,4,T+3_000_000_000L,"gps");
        a=quality.inspect(third,third.elapsedNs,10);
        check(a.accepted&&a.breakTrack,"accepted fix after bad network starts a new segment");
        GpsQuality.Fix fourth=fix(47.00003,14,4,T+4_000_000_000L,"gps");
        a=quality.inspect(fourth,fourth.elapsedNs,10);
        check(a.accepted&&!a.breakTrack,"continuous good fixes draw track");
        GpsQuality.Fix jump=fix(47.1,14,4,T+5_000_000_000L,"gps");
        rejection(quality.inspect(jump,jump.elapsedNs,10),"position_jump");
        GpsQuality.Fix jumpAfterGap=fix(47.1,14,4,T+11_000_000_000L,"gps");
        rejection(quality.inspect(jumpAfterGap,jumpAfterGap.elapsedNs,10),"position_jump");
        rejection(quality.inspect(fourth,fourth.elapsedNs+1,10),"non_monotonic_fix");
        GpsQuality.Fix stale=fix(47,14,4,T,"gps");
        rejection(quality.inspect(stale,T+6_000_000_000L,10),"stale_fix");
        rejection(quality.inspect(fix(47,14,4,T,"gps"),T-1,10),"future_fix");
        rejection(quality.inspect(fix(47,14,11,T,"gps"),T,10),"accuracy");
        rejection(quality.inspect(fix(47,14,1000,T,"network"),T,10),"network_accuracy");
        rejection(quality.inspect(new GpsQuality.Fix(47,14,4,T,1,"gps",13),T,10),"speed");
        rejection(quality.inspect(new GpsQuality.Fix(47,14,4,T,1,"gps",-1),T,10),"invalid_fix");
        rejection(quality.inspect(fix(Double.NaN,14,4,T,"gps"),T,10),"invalid_fix");
        rejection(quality.inspect(fix(47,14,Double.NaN,T,"gps"),T,10),"invalid_fix");
        rejection(quality.inspect(fix(47,14,0,T,"gps"),T,10),"invalid_fix");
        rejection(quality.inspect(fix(47,14,4,0,"gps"),T,10),"invalid_fix");
        rejection(quality.inspect(fix(47,14,4,T,null),T,10),"invalid_fix");
        rejection(quality.inspect(null,T,10),"missing_fix");
        invalid(()->quality.inspect(first,T,Double.NaN));
        invalid(()->quality.inspect(first,T,0));
        quality.reset();
        check(quality.current(T)==null,"reset clears obsolete fix");
        a=quality.inspect(fix(48,15,4,T,"gps"),T,10);
        rejection(a,"gps_acquiring");
        a=quality.inspect(fix(48.000001,15,4,T+1_000_000_000L,"gps"),T+1_000_000_000L,10);
        check(a.accepted&&a.breakTrack,"reset starts independent session after stabilization");
        quality.reset();
        quality.inspect(fix(47,14,4,T,"gps"),T,10);
        a=quality.inspect(fix(47.0001,14,4,T+10_000_000_000L,"gps"),T+10_000_000_000L,10);
        rejection(a,"gps_acquiring");
        GpsQuality.Fix network=fix(47.0001,14,5,T+11_000_000_000L,"network");
        check(quality.inspect(network,network.elapsedNs,10).accepted,"accurate network fix stabilizes pending GPS acquisition");
        System.out.println("GpsQuality: "+checks+" checks OK");
    }
    private static GpsQuality.Fix fix(double lat,double lon,double accuracy,long time,String provider) {
        return new GpsQuality.Fix(lat,lon,accuracy,time,1_790_000_000_000L,provider,Double.NaN);
    }
    private static void rejection(GpsQuality.Decision d,String reason) {check(!d.accepted&&d.breakTrack&&reason.equals(d.reason),reason+": "+d.reason);}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void invalid(Runnable action){try{action.run();throw new AssertionError("Invalid threshold accepted");}catch(IllegalArgumentException expected){checks++;}}
}
