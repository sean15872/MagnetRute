package at.andi.magnetrute;

/** Android-independent geometry for four-corner plan rectification and scale calibration. */
public final class PlanGeometry {
    private PlanGeometry() { }

    /** Four consecutive corners in image pixels (normally top-left, top-right, bottom-right, bottom-left). */
    public static boolean validQuad(double[] xy) {
        if (!convex(xy)) return false;
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            if (Math.hypot(xy[2*i] - xy[2*next], xy[2*i+1] - xy[2*next+1]) < 2) return false;
        }
        double area = Math.abs(cross(xy[2]-xy[0], xy[3]-xy[1], xy[4]-xy[0], xy[5]-xy[1])
                + cross(xy[4]-xy[0], xy[5]-xy[1], xy[6]-xy[0], xy[7]-xy[1])) / 2;
        return Double.isFinite(area) && area >= 4;
    }

    private static boolean convex(double[] xy) {
        if (xy == null || xy.length != 8) return false;
        for (double v : xy) if (!Double.isFinite(v)) return false;
        double span = 0;
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            span = Math.max(span, Math.hypot(xy[2*i]-xy[2*next], xy[2*i+1]-xy[2*next+1]));
        }
        if (!Double.isFinite(span) || span == 0) return false;
        double sign = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i+1)%4, k = (i+2)%4;
            double c = cross((xy[2*j]-xy[2*i])/span, (xy[2*j+1]-xy[2*i+1])/span,
                    (xy[2*k]-xy[2*j])/span, (xy[2*k+1]-xy[2*j+1])/span);
            if (!Double.isFinite(c) || Math.abs(c) <= 1e-10) return false;
            if (i == 0) sign = Math.signum(c);
            else if (Math.signum(c) != sign) return false;
        }
        return true;
    }

    private static double cross(double ax, double ay, double bx, double by) {
        return ax*by - ay*bx;
    }

    /** Opposite edges are averaged; the aspect ratio is retained within rounding, with neither edge above maxEdge. */
    public static int[] outputSize(double[] xy, int maxEdge) {
        if (!validQuad(xy) || maxEdge < 2) throw new IllegalArgumentException("Ungültige Planecken oder Bildgröße");
        double width = (distance(xy, 0, 1) + distance(xy, 3, 2)) / 2;
        double height = (distance(xy, 0, 3) + distance(xy, 1, 2)) / 2;
        double scale = Math.min(1, maxEdge / Math.max(width, height));
        return new int[] {Math.max(2, Math.min(maxEdge, (int)Math.round(width*scale))),
                Math.max(2, Math.min(maxEdge, (int)Math.round(height*scale)))};
    }

    private static double distance(double[] xy, int a, int b) {
        return Math.hypot(xy[2*a]-xy[2*b], xy[2*a+1]-xy[2*b+1]);
    }

    public static double metersPerPixel(double x1, double y1, double x2, double y2, double meters) {
        if (!Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(x2) || !Double.isFinite(y2)
                || !Double.isFinite(meters) || meters <= 0) {
            throw new IllegalArgumentException("Eine positive Länge und zwei gültige Messpunkte sind erforderlich");
        }
        double pixels = Math.hypot(x2-x1, y2-y1);
        double value = meters/pixels;
        if (!Double.isFinite(pixels) || pixels <= 1e-9 || !Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("Die Messpunkte müssen verschieden sein");
        }
        return value;
    }

    /** A row-major 3x3 homography mapping source corners to destination corners, normalized so h[8] is 1. */
    public static double[] homography(double[] source, double[] destination) {
        if (!convex(source) || !convex(destination)) throw new IllegalArgumentException("Ungültiges Viereck");
        double[] sn = normalization(source), dn = normalization(destination);
        double[] s = transform(source, sn), d = transform(destination, dn);
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x=s[2*i], y=s[2*i+1], u=d[2*i], v=d[2*i+1];
            a[2*i] = new double[] {x,y,1, 0,0,0, -u*x,-u*y,u};
            a[2*i+1] = new double[] {0,0,0, x,y,1, -v*x,-v*y,v};
        }
        // Partial pivoting after coordinate normalization keeps photographed plans well conditioned.
        for (int col = 0; col < 8; col++) {
            int pivot = col;
            for (int row = col+1; row < 8; row++) if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            if (Math.abs(a[pivot][col]) < 1e-12) throw new IllegalArgumentException("Entzerrung ist nicht eindeutig");
            double[] swap=a[col]; a[col]=a[pivot]; a[pivot]=swap;
            double divisor=a[col][col];
            for (int j=col; j<9; j++) a[col][j]/=divisor;
            for (int row=0; row<8; row++) if (row!=col) {
                double factor=a[row][col];
                for (int j=col; j<9; j++) a[row][j]-=factor*a[col][j];
            }
        }
        double[] h=new double[9];
        for (int i=0; i<8; i++) h[i]=a[i][8];
        h[8]=1;
        double[] inverseDn={1/dn[0],0,-dn[2]/dn[0], 0,1/dn[4],-dn[5]/dn[4], 0,0,1};
        h=multiply(multiply(inverseDn,h),sn);
        double denominator=h[8];
        if (!Double.isFinite(denominator) || Math.abs(denominator)<1e-15) {
            throw new IllegalArgumentException("Entzerrung ist nicht darstellbar");
        }
        for (int i=0; i<9; i++) {
            h[i]/=denominator;
            if (!Double.isFinite(h[i])) throw new IllegalArgumentException("Ungültige Entzerrung");
        }
        return h;
    }

    private static double[] normalization(double[] xy) {
        double cx=0,cy=0;
        for(int i=0;i<4;i++){cx+=xy[2*i]/4; cy+=xy[2*i+1]/4;}
        double mean=0;
        for(int i=0;i<4;i++) mean+=Math.hypot(xy[2*i]-cx,xy[2*i+1]-cy)/4;
        double scale=Math.sqrt(2)/mean;
        return new double[] {scale,0,-scale*cx, 0,scale,-scale*cy, 0,0,1};
    }

    private static double[] transform(double[] xy, double[] h) {
        double[] r=new double[8];
        for(int i=0;i<4;i++) {
            double[] p=map(h,xy[2*i],xy[2*i+1]);
            r[2*i]=p[0]; r[2*i+1]=p[1];
        }
        return r;
    }

    private static double[] multiply(double[] a,double[] b) {
        double[] c=new double[9];
        for(int i=0;i<3;i++) for(int j=0;j<3;j++) for(int k=0;k<3;k++) c[3*i+j]+=a[3*i+k]*b[3*k+j];
        return c;
    }

    public static double[] map(double[] h, double x, double y) {
        if(h==null || h.length!=9 || !Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Ungültiger Punkt oder Transformationsmatrix");
        }
        for(double v:h) if(!Double.isFinite(v)) throw new IllegalArgumentException("Ungültige Transformationsmatrix");
        double z=h[6]*x+h[7]*y+h[8];
        double u=(h[0]*x+h[1]*y+h[2])/z, v=(h[3]*x+h[4]*y+h[5])/z;
        if(z==0 || !Double.isFinite(u) || !Double.isFinite(v)) throw new IllegalArgumentException("Punkt liegt auf der projektiven Unendlichkeitsgeraden");
        return new double[] {u,v};
    }
}
