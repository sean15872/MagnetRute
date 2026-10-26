package at.andi.magnetrute;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** Bounded image decoding and perspective rendering; originals remain outside the archive. */
public final class PlanImages {
    public static final int MAX_EDGE=2000;
    private PlanImages() { }

    public static Bitmap decode(File file) throws IOException {
        BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getPath(),o);
        if (o.outWidth<=0 || o.outHeight<=0 || (long)o.outWidth*o.outHeight>120_000_000L)
            throw new IOException("Bild ist ungültig oder zu groß (max. 120 Megapixel).");
        o.inSampleSize=1;
        while (Math.max(o.outWidth,o.outHeight)/o.inSampleSize>MAX_EDGE) o.inSampleSize*=2;
        o.inJustDecodeBounds=false;
        Bitmap b=BitmapFactory.decodeFile(file.getPath(),o);
        if (b==null) throw new IOException("Bild konnte nicht gelesen werden.");
        Matrix m=new Matrix();
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation=new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
        } catch(IOException unsupportedExif) {
            // BitmapFactory has already validated the raster. EXIF is optional (e.g. PNG).
        }
        switch(orientation) {
            case 2: m.setScale(-1,1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1,-1); break;
            case 5: m.setRotate(90); m.postScale(-1,1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(270); m.postScale(-1,1); break;
            case 8: m.setRotate(270); break;
            default: break;
        }
        Bitmap rotated=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);
        if (rotated!=b) b.recycle();
        double ratio=Math.min(1,MAX_EDGE/(double)Math.max(rotated.getWidth(),rotated.getHeight()));
        if (ratio<1) {
            b=Bitmap.createScaledBitmap(rotated,Math.max(1,(int)Math.round(rotated.getWidth()*ratio)),
                Math.max(1,(int)Math.round(rotated.getHeight()*ratio)),true);
            if (b!=rotated) rotated.recycle();
            return b;
        }
        return rotated;
    }

    public static Bitmap rectify(Bitmap source,double[] quad) {
        int[] size=PlanGeometry.outputSize(quad,MAX_EDGE);
        float[] src=new float[8];
        for(int i=0;i<8;i++) {
            if(quad[i]<0 || quad[i]>(i%2==0?source.getWidth()-1:source.getHeight()-1))
                throw new IllegalArgumentException("Planecken liegen außerhalb des Fotos.");
            src[i]=(float)quad[i];
        }
        float[] dst={0,0,size[0]-1,0,size[0]-1,size[1]-1,0,size[1]-1};
        Matrix matrix=new Matrix();
        if (!matrix.setPolyToPoly(src,0,dst,0,4)) throw new IllegalArgumentException("Ungültige Planecken.");
        Bitmap out=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out); c.drawColor(Color.WHITE);
        c.drawBitmap(source,matrix,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    public static byte[] jpeg(Bitmap bitmap) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!bitmap.compress(Bitmap.CompressFormat.JPEG,92,out)) throw new IOException("JPEG konnte nicht gespeichert werden.");
        return out.toByteArray();
    }

    public static Bitmap area(PlanArchive.Area area) throws IOException {
        BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(area.image,0,area.image.length,o);
        if(o.outWidth!=area.width || o.outHeight!=area.height || !"image/jpeg".equals(o.outMimeType))
            throw new IOException("Bild und Metadaten stimmen nicht überein.");
        Bitmap b=BitmapFactory.decodeByteArray(area.image,0,area.image.length);
        if(b==null) throw new IOException("Beschädigtes Grundrissbild.");
        return b;
    }
}
