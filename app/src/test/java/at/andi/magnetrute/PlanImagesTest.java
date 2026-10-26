package at.andi.magnetrute;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;

/** Uses native Skia pixels and real JPEGs, exercising the Android image/archive boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PlanImagesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private static Bitmap quadrants(int width, int height) {
        Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(b);
        Paint paint=new Paint();
        paint.setColor(Color.RED); canvas.drawRect(0,0,width/2f,height/2f,paint);
        paint.setColor(Color.GREEN); canvas.drawRect(width/2f,0,width,height/2f,paint);
        paint.setColor(Color.BLUE); canvas.drawRect(0,height/2f,width/2f,height,paint);
        paint.setColor(Color.YELLOW); canvas.drawRect(width/2f,height/2f,width,height,paint);
        return b;
    }

    private static void colorNear(int expected, int actual) {
        assertEquals(Color.red(expected),Color.red(actual),15);
        assertEquals(Color.green(expected),Color.green(actual),15);
        assertEquals(Color.blue(expected),Color.blue(actual),15);
    }

    private static PlanArchive.Area area(String floor,String name,Bitmap b,double scale) throws IOException {
        return new PlanArchive.Area(UUID.randomUUID().toString(),floor,name,
                b.getWidth(),b.getHeight(),scale,PlanImages.jpeg(b));
    }

    @Test public void trapezoidCornersMapToRectifiedPixelQuadrants() {
        Bitmap input=quadrants(800,600);
        double[] quad={120,70, 680,110, 740,540, 50,500};
        Bitmap result=PlanImages.rectify(input,quad);
        int[] expected=PlanGeometry.outputSize(quad,PlanImages.MAX_EDGE);
        assertEquals(expected[0],result.getWidth());
        assertEquals(expected[1],result.getHeight());
        colorNear(Color.RED,result.getPixel(30,30));
        colorNear(Color.GREEN,result.getPixel(result.getWidth()-31,30));
        colorNear(Color.BLUE,result.getPixel(30,result.getHeight()-31));
        colorNear(Color.YELLOW,result.getPixel(result.getWidth()-31,result.getHeight()-31));
        result.recycle(); input.recycle();
    }

    @Test public void twoCropsFromSameRectifiedPlanRemainIndependent() throws IOException {
        Bitmap input=quadrants(800,600);
        Bitmap corrected=PlanImages.rectify(input,new double[]{0,0,799,0,799,599,0,599});
        Bitmap left=Bitmap.createBitmap(corrected,10,10,200,180);
        Bitmap right=Bitmap.createBitmap(corrected,450,350,200,180);
        PlanArchive.Area first=area("Erdgeschoss","West",left,.025);
        PlanArchive.Area second=area("Dachgeschoss","Süd",right,.04);
        Bitmap restoredLeft=PlanImages.area(first),restoredRight=PlanImages.area(second);
        colorNear(Color.RED,restoredLeft.getPixel(100,90));
        colorNear(Color.YELLOW,restoredRight.getPixel(100,90));
        assertEquals(200,restoredLeft.getWidth());
        assertEquals(180,restoredRight.getHeight());
        restoredLeft.recycle(); restoredRight.recycle(); left.recycle(); right.recycle(); corrected.recycle(); input.recycle();
    }

    @Test public void largeJpegDecodeIsBoundedAndPreservesAspectAndPixels() throws IOException {
        Bitmap original=quadrants(4200,2400);
        File file=temporary.newFile("large.jpg");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(PlanImages.jpeg(original));}
        original.recycle();
        Bitmap result=PlanImages.decode(file);
        assertTrue(result.getWidth()<=PlanImages.MAX_EDGE);
        assertEquals(4200.0/2400,result.getWidth()/(double)result.getHeight(),.01);
        colorNear(Color.RED,result.getPixel(100,100));
        colorNear(Color.YELLOW,result.getPixel(result.getWidth()-100,result.getHeight()-100));
        result.recycle();
    }

    @Test public void cameraExifRotationIsAppliedBeforeEditing() throws IOException {
        Bitmap original=quadrants(400,200);
        File file=temporary.newFile("camera.jpg");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(PlanImages.jpeg(original));}
        original.recycle();
        ExifInterface exif=new ExifInterface(file.getPath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        Bitmap result=PlanImages.decode(file);
        assertEquals(200,result.getWidth()); assertEquals(400,result.getHeight());
        colorNear(Color.BLUE,result.getPixel(30,30));
        colorNear(Color.RED,result.getPixel(170,30));
        colorNear(Color.YELLOW,result.getPixel(30,370));
        colorNear(Color.GREEN,result.getPixel(170,370));
        result.recycle();
    }

    @Test public void pngFromDocumentPickerRemainsReadableWithoutExifMetadata() throws IOException {
        Bitmap original=quadrants(320,200);
        File file=temporary.newFile("plan.png");
        try(FileOutputStream out=new FileOutputStream(file)) {
            assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,out));
        }
        original.recycle();
        Bitmap decoded=PlanImages.decode(file);
        assertEquals(320,decoded.getWidth());assertEquals(200,decoded.getHeight());
        colorNear(Color.RED,decoded.getPixel(20,20));
        colorNear(Color.YELLOW,decoded.getPixel(300,180));decoded.recycle();
    }

    @Test public void actualJpegBackupRoundTripRestoresImagesNamesAndCalibration() throws IOException {
        Bitmap original=quadrants(320,200);
        PlanArchive.Area first=area("Keller Süd","Technik",original,.015);
        Bitmap crop=Bitmap.createBitmap(original,180,120,100,60);
        PlanArchive.Area second=area("1. Stock","Zimmer 2",crop,0);
        File backup=temporary.newFile("Grundrisse.zip");
        PlanArchive.save(new PlanArchive.Document(Arrays.asList(first,second)),backup);
        PlanArchive.Document restored=PlanArchive.load(backup);
        assertEquals(2,restored.areas.size());
        assertEquals("Keller Süd",restored.areas.get(0).floor);
        assertEquals("Technik",restored.areas.get(0).name);
        assertEquals(.015,restored.areas.get(0).metersPerPixel,0);
        assertEquals(0,restored.areas.get(1).metersPerPixel,0);
        assertArrayEquals(first.image,restored.areas.get(0).image);
        Bitmap restoredFirst=PlanImages.area(restored.areas.get(0));
        Bitmap restoredSecond=PlanImages.area(restored.areas.get(1));
        colorNear(Color.GREEN,restoredFirst.getPixel(260,30));
        colorNear(Color.YELLOW,restoredSecond.getPixel(50,30));
        restoredFirst.recycle();restoredSecond.recycle();crop.recycle();original.recycle();
    }

    @Test public void metadataMismatchAndDamagedJpegAreRejected() throws IOException {
        Bitmap bitmap=quadrants(100,80);
        byte[] jpeg=PlanImages.jpeg(bitmap);bitmap.recycle();
        PlanArchive.Area wrong=new PlanArchive.Area(UUID.randomUUID().toString(),"EG","Test",101,80,0,jpeg);
        assertThrows(IOException.class,()->PlanImages.area(wrong));
        PlanArchive.Area damaged=new PlanArchive.Area(UUID.randomUUID().toString(),"EG","Test",100,80,0,
                new byte[]{(byte)255,(byte)216,(byte)255,(byte)217});
        assertThrows(IOException.class,()->PlanImages.area(damaged));
        File invalid=temporary.newFile("invalid.jpg");
        assertThrows(IOException.class,()->PlanImages.decode(invalid));
    }

    @Test public void invalidPerspectiveCannotReadOutsideSourceImage() {
        Bitmap bitmap=quadrants(100,80);
        assertThrows(IllegalArgumentException.class,()->PlanImages.rectify(bitmap,new double[]{-1,0,99,0,99,79,0,79}));
        assertThrows(IllegalArgumentException.class,()->PlanImages.rectify(bitmap,new double[]{0,0,99,79,99,0,0,79}));
        bitmap.recycle();
    }

    @Test public void scannerWorkflowPersistsTwoAreasCalibrationRenameAndConfirmedRestore() throws Exception {
        Bitmap source=quadrants(640,480);
        File photo=temporary.newFile("paper.jpg");
        try(FileOutputStream out=new FileOutputStream(photo)){out.write(PlanImages.jpeg(source));}
        source.recycle();
        ActivityController<PlanActivity> controller=Robolectric.buildActivity(PlanActivity.class).create().start().resume().visible();
        PlanActivity activity=controller.get();
        File local=new File(activity.getFilesDir(),"floor-plans.zip");
        try {
            awaitWorker(activity);
            // The document picker hands the selected image to the same callback as on a device.
            activity.onActivityResult(3102,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(photo)));
            awaitWorker(activity);
            click(activity,"Perspektive entzerren");awaitWorker(activity);
            saveArea(activity,"Nordflügel","Küche");awaitWorker(activity);
            saveArea(activity,"Nordflügel","Bad");awaitWorker(activity);
            PlanArchive.Document saved=PlanArchive.load(local);
            assertEquals(2,saved.areas.size());
            assertEquals("Küche",saved.areas.get(0).name);
            assertEquals("Bad",saved.areas.get(1).name);
            click(activity,"Zur Bereichsliste");
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            click(activity,"Nordflügel · Küche");awaitWorker(activity);
            click(activity,"Maßstab kalibrieren");click(activity,"Bekannte Länge eingeben");
            AlertDialog calibration=ShadowAlertDialog.getLatestAlertDialog();
            edits(calibration.getWindow().getDecorView()).get(0).setText("4,25");
            calibration.getButton(AlertDialog.BUTTON_POSITIVE).performClick();awaitWorker(activity);
            assertTrue(PlanArchive.load(local).areas.get(0).metersPerPixel>0);
            click(activity,"Stockwerk umbenennen");
            AlertDialog rename=ShadowAlertDialog.getLatestAlertDialog();
            edits(rename.getWindow().getDecorView()).get(0).setText("Galerie");
            rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick();awaitWorker(activity);
            PlanArchive.Document renamed=PlanArchive.load(local);
            assertEquals("Galerie",renamed.areas.get(0).floor);
            assertEquals("Galerie",renamed.areas.get(1).floor);
            assertEquals(0,renamed.areas.get(1).metersPerPixel,0);
            // Export via SAF callback, then restore over changed local data only after confirmation.
            File backup=temporary.newFile("backup.zip");
            activity.onActivityResult(3103,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(backup)));
            awaitWorker(activity);
            assertEquals(2,PlanArchive.load(backup).areas.size());
            PlanArchive.save(new PlanArchive.Document(java.util.Collections.emptyList()),local);
            activity.onActivityResult(3104,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(backup)));
            awaitWorker(activity);
            assertEquals(0,PlanArchive.load(local).areas.size());
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            awaitWorker(activity);
            PlanArchive.Document restored=PlanArchive.load(local);
            assertEquals(2,restored.areas.size());
            assertEquals("Galerie",restored.areas.get(0).floor);
            assertEquals(renamed.areas.get(0).metersPerPixel,restored.areas.get(0).metersPerPixel,0);
            Bitmap image=PlanImages.area(restored.areas.get(1));
            assertTrue(image.getWidth()>16 && image.getHeight()>16);image.recycle();
        } finally { controller.pause().stop().destroy(); }
    }

    private static void saveArea(PlanActivity activity,String floor,String name) {
        click(activity,"Ausschnitt als Bereich speichern");
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        List<EditText> fields=edits(dialog.getWindow().getDecorView());
        assertEquals(2,fields.size());fields.get(0).setText(floor);fields.get(1).setText(name);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    }

    @Test public void scaleEditorRecreationKeepsArchivePixelCoordinatesAbovePhotoDecodeLimit() throws Exception {
        Bitmap image=quadrants(2400,200);
        PlanArchive.Area original=area("DG","Galerie",image,0);image.recycle();
        File archive=new File(RuntimeEnvironment.getApplication().getFilesDir(),"floor-plans.zip");
        PlanArchive.save(new PlanArchive.Document(java.util.Collections.singletonList(original)),archive);
        ActivityController<PlanActivity> first=Robolectric.buildActivity(PlanActivity.class).create().start().resume().visible();
        awaitWorker(first.get());click(first.get(),"DG · Galerie");awaitWorker(first.get());
        click(first.get(),"Maßstab kalibrieren");
        PlanEditorView previous=ReflectionHelpers.getField(first.get(),"editor");
        double[] points=previous.points();
        Bundle state=new Bundle();first.get().onSaveInstanceState(state);
        first.pause().stop().destroy();
        ActivityController<PlanActivity> second=Robolectric.buildActivity(PlanActivity.class).create(state).start().resume().visible();
        try {
            awaitWorker(second.get());
            Bitmap restoredImage=ReflectionHelpers.getField(second.get(),"bitmap");
            assertEquals(2400,restoredImage.getWidth());
            PlanEditorView restoredEditor=ReflectionHelpers.getField(second.get(),"editor");
            assertArrayEquals(points,restoredEditor.points(),0);
            click(second.get(),"Bekannte Länge eingeben");
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            edits(dialog.getWindow().getDecorView()).get(0).setText("6");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();awaitWorker(second.get());
            double expected=PlanGeometry.metersPerPixel(points[0],points[1],points[2],points[3],6);
            assertEquals(expected,PlanArchive.load(archive).areas.get(0).metersPerPixel,1e-12);
        } finally {second.pause().stop().destroy();}
    }

    @Test public void corruptEvictedWorkingPhotoFallsBackToSavedLibrary() throws Exception {
        Bitmap image=quadrants(200,100);
        PlanArchive.Area original=area("EG","Flur",image,.02);image.recycle();
        File archive=new File(RuntimeEnvironment.getApplication().getFilesDir(),"floor-plans.zip");
        PlanArchive.save(new PlanArchive.Document(java.util.Collections.singletonList(original)),archive);
        File cache=new File(RuntimeEnvironment.getApplication().getCacheDir(),"plan-editor.jpg");
        try(FileOutputStream out=new FileOutputStream(cache)){out.write(new byte[]{1,2,3,4});}
        Bundle state=new Bundle();state.putString("stage","crop");
        ActivityController<PlanActivity> controller=Robolectric.buildActivity(PlanActivity.class).create(state).start().resume().visible();
        try {
            awaitWorker(controller.get());
            assertNotNull(findButton(controller.get().getWindow().getDecorView(),"EG · Flur"));
            assertEquals(1,PlanArchive.load(archive).areas.size());
            assertEquals(.02,PlanArchive.load(archive).areas.get(0).metersPerPixel,0);
        } finally {controller.pause().stop().destroy();}
    }

    private static List<EditText> edits(View root) {
        List<EditText> result=new ArrayList<>();
        if(root instanceof EditText) result.add((EditText)root);
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++)
            result.addAll(edits(((ViewGroup)root).getChildAt(i)));
        return result;
    }

    private static Button findButton(View root,String prefix) {
        if(root instanceof Button && ((Button)root).getText().toString().startsWith(prefix)) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            Button b=findButton(((ViewGroup)root).getChildAt(i),prefix);if(b!=null) return b;
        }
        return null;
    }

    private static void click(PlanActivity activity,String prefix) {
        Button button=findButton(activity.getWindow().getDecorView(),prefix);
        assertNotNull("Missing scanner action: "+prefix,button);button.performClick();
        // AlertDialog installs custom validation listeners from its queued onShow callback.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void awaitWorker(PlanActivity activity) throws InterruptedException {
        long deadline=System.nanoTime()+15_000_000_000L;
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!ReflectionHelpers.<Boolean>getField(activity,"busy")) return;
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        fail("Scanner worker did not finish");
    }
}
