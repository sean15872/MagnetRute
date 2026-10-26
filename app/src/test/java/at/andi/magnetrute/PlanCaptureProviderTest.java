package at.andi.magnetrute;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

/** Tests the full-size camera handoff while keeping plan archives inaccessible to the provider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PlanCaptureProviderTest {
    private static PlanCaptureProvider provider(Context context) {
        PlanCaptureProvider provider=new PlanCaptureProvider();
        ProviderInfo info=new ProviderInfo();
        info.authority=PlanCaptureProvider.URI.getAuthority();
        info.exported=false;info.grantUriPermissions=true;
        provider.attachInfo(context,info);
        return provider;
    }

    @Test public void grantedCaptureUriAcceptsFullSizeJpegAndReportsFileMetadata() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        PlanCaptureProvider provider=provider(context);
        Bitmap image=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.GREEN);
        byte[] jpeg=PlanImages.jpeg(image);image.recycle();
        // Camera applications use any of these documented ParcelFileDescriptor modes.
        for(String mode:new String[]{"w","wt","rw","rwt"}) {
            try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(provider.openFile(PlanCaptureProvider.URI,mode))) {
                out.write(jpeg);
            }
            try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(PlanCaptureProvider.URI,"r"))) {
                Bitmap decoded=BitmapFactory.decodeStream(in);
                assertNotNull(decoded);
                assertEquals(320,decoded.getWidth());assertEquals(180,decoded.getHeight());
                assertTrue(Color.green(decoded.getPixel(160,90))>240);decoded.recycle();
            }
        }
        assertEquals("image/jpeg",provider.getType(PlanCaptureProvider.URI));
        try(Cursor cursor=provider.query(PlanCaptureProvider.URI,null,null,null,null)) {
            assertNotNull(cursor);assertTrue(cursor.moveToFirst());
            assertEquals("capture.jpg",cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(jpeg.length,cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
            assertFalse(cursor.moveToNext());
        }
    }

    @Test public void siblingTraversalAndUnsupportedModesCannotAlterStoredPlans() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        PlanCaptureProvider provider=provider(context);
        File plans=new File(context.getFilesDir(),"floor-plans.zip");
        byte[] privatePlans={80,75,1,2,3,4,5};Files.write(plans.toPath(),privatePlans);
        File capture=new File(context.getCacheDir(),"capture.jpg");
        byte[] existingCapture={9,8,7,6};Files.write(capture.toPath(),existingCapture);
        for(String uri:new String[]{"content://at.andi.magnetrute.capture/floor-plans.zip",
                "content://at.andi.magnetrute.capture/../files/floor-plans.zip",
                "content://at.andi.magnetrute.capture/%2e%2e%2ffiles%2ffloor-plans.zip",
                "content://at.andi.magnetrute.capture/capture.jpg/../floor-plans.zip",
                "content://at.andi.magnetrute.capture/capture.jpg?file=floor-plans.zip",
                "content://at.andi.magnetrute.export/capture.jpg"}) {
            Uri other=Uri.parse(uri);
            assertNull(provider.getType(other));
            assertNull(provider.query(other,null,null,null,null));
            assertThrows(FileNotFoundException.class,()->provider.openFile(other,"r"));
            assertThrows(FileNotFoundException.class,()->provider.openFile(other,"wt"));
        }
        for(String mode:new String[]{"wa","a","", "write"})
            assertThrows(FileNotFoundException.class,()->provider.openFile(PlanCaptureProvider.URI,mode));
        assertArrayEquals(privatePlans,Files.readAllBytes(plans.toPath()));
        assertArrayEquals(existingCapture,Files.readAllBytes(capture.toPath()));
    }

    @Test public void scannerStartsCameraWithOutputUriClipDataAndReadWriteGrant() throws Exception {
        ActivityController<PlanActivity> controller=Robolectric.buildActivity(PlanActivity.class).create().start().resume().visible();
        PlanActivity activity=controller.get();
        try {
            awaitWorker(activity);
            File stale=new File(activity.getCacheDir(),"capture.jpg");Files.write(stale.toPath(),new byte[]{1,2,3});
            Button camera=findButton(activity.getWindow().getDecorView(),"Papierplan fotografieren");
            assertNotNull(camera);camera.performClick();
            Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);assertEquals(MediaStore.ACTION_IMAGE_CAPTURE,intent.getAction());
            assertEquals(PlanCaptureProvider.URI,intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT));
            int grants=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            assertEquals(grants,intent.getFlags()&grants);
            assertNotNull(intent.getClipData());assertEquals(1,intent.getClipData().getItemCount());
            assertEquals(PlanCaptureProvider.URI,intent.getClipData().getItemAt(0).getUri());
            assertFalse("Old image must not be mistaken for a fresh camera result",stale.exists());
        } finally {controller.pause().stop().destroy();}
    }

    private static Button findButton(View root,String text) {
        if(root instanceof Button && text.contentEquals(((Button)root).getText())) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            Button b=findButton(((ViewGroup)root).getChildAt(i),text);if(b!=null)return b;
        }
        return null;
    }
    private static void awaitWorker(PlanActivity activity) throws InterruptedException {
        long deadline=System.nanoTime()+15_000_000_000L;
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(!ReflectionHelpers.<Boolean>getField(activity,"busy")) return;
            Thread.sleep(10);
        }while(System.nanoTime()<deadline);
        fail("Scanner worker did not finish");
    }
}
