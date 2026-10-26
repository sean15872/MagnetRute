package at.andi.magnetrute;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate offline plan workspace; measurement/GPS recording remains in MainActivity. */
public final class PlanActivity extends Activity {
    private static final int CAMERA=3101,PHOTO=3102,EXPORT=3103,IMPORT=3104;
    // Serialize storage across Activity recreation so an old save cannot race a new load.
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private PlanArchive.Document document=new PlanArchive.Document(Collections.emptyList());
    private LinearLayout root,body,actions;
    private TextView status;
    private Bitmap bitmap;
    private PlanEditorView editor;
    private String stage="list",selectedId;
    private boolean busy,loadFailed;
    private File archive() { return new File(getFilesDir(),"floor-plans.zip"); }
    private File working() { return new File(getCacheDir(),"plan-editor.jpg"); }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(14),dp(12),dp(14),dp(12));
        root.setBackgroundColor(0xff0a1020);
        TextView title=text("Grundriss-Scanner · v0.3",22);root.addView(title);
        status=text("Grundrisse werden geladen …",13);root.addView(status);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        runJob(() -> {
            PlanArchive.Document loaded=PlanArchive.load(archive());validateImages(loaded);
            document=loaded;
            Bitmap restored=null;
            if(saved!=null) {
                String oldStage=saved.getString("stage","list");
                if("preview".equals(oldStage) || "scale".equals(oldStage)) {
                    for(PlanArchive.Area a:loaded.areas) if(a.id.equals(saved.getString("selectedId"))) restored=PlanImages.area(a);
                } else if(("corners".equals(oldStage) || "crop".equals(oldStage)) && working().exists()) {
                    // A cache eviction/corrupt work photo must not block the saved plan library.
                    try { restored=PlanImages.decode(working()); } catch(IOException ignored) { restored=null; }
                }
            }
            Bitmap finalRestored=restored;
            return () -> {
                if(finalRestored!=null) {
                    bitmap=finalRestored;selectedId=saved.getString("selectedId");
                    showEditor(saved.getString("stage"),saved.getDoubleArray("points"));
                } else showList();
            };
        });
    }
    private TextView text(String value,int size) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xffeaf3ff);
        t.setPadding(0,dp(4),0,dp(6));return t;
    }
    private void button(String label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v -> { if(!busy) action.run(); });
        (actions==null?body:actions).addView(b,new LinearLayout.LayoutParams(-1,-2));
    }
    private void message(String text) { status.setText(text); }
    private void error(Exception e) {
        if(isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("Vorgang fehlgeschlagen")
            .setMessage(e.getMessage()==null?"Datei konnte nicht verarbeitet werden.":e.getMessage()).setPositiveButton("OK",null).show();
    }
    private interface Job { Runnable run() throws Exception; }
    private void runJob(Job job) {
        busy=true;message("Bitte warten …");
        WORKER.execute(() -> {
            try {
                Runnable done=job.run();
                runOnUiThread(() -> { if(!isDestroyed()) { busy=false;done.run(); } });
            } catch(Exception e) {
                runOnUiThread(() -> {
                    if(isDestroyed()) return;
                    busy=false;
                    if(body.getChildCount()==0) { loadFailed=true;showList(); }
                    message("Vorgang abgebrochen. Bestehende Grundrisse bleiben erhalten.");error(e);
                });
            }
        });
    }
    private void showList() {
        stage="list";editor=null;selectedId=null;bitmap=null;actions=null;body.removeAllViews();
        message(loadFailed?"Lokales Archiv nicht lesbar. Zuerst ein gültiges Backup wiederherstellen.":
            document.areas.size()+" Bereiche · nur auf diesem Gerät gespeichert");
        if(!loadFailed) {
            button("Papierplan fotografieren",this::capture);
            button("Planfoto aus Dateien öffnen",this::pickPhoto);
            button("Grundrisse als ZIP sichern",this::exportZip);
        }
        button("Grundrisse aus ZIP wiederherstellen",this::importZip);
        button("Zurück zum Messlabor",this::finish);
        ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        for(PlanArchive.Area a:document.areas) {
            Button b=new Button(this);b.setAllCaps(false);b.setText(a.floor+" · "+a.name+"\n"+scaleText(a));
            b.setOnClickListener(v -> { if(!busy) openArea(a); });list.addView(b);
        }
        if(document.areas.isEmpty()) list.addView(text("Fotografiere einen möglichst flachen, gut beleuchteten Plan. "
            +"Nach dem Entzerren kannst du mehrere Bereiche desselben Fotos ausschneiden. "
            +"Grundrisse sind unabhängig von GPS und Messlogs; es gibt keine automatische Positionierung.",14));
    }
    private static String scaleText(PlanArchive.Area a) {
        return a.metersPerPixel==0?"Maßstab noch nicht kalibriert":String.format(Locale.GERMANY,
            "%.2f × %.2f m · %.4f m/Pixel",a.width*a.metersPerPixel,a.height*a.metersPerPixel,a.metersPerPixel);
    }
    private void capture() {
        try {
            File f=new File(getCacheDir(),"capture.jpg");
            if(f.exists() && !f.delete()) throw new IOException("Altes Kamerafoto konnte nicht entfernt werden.");
            Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);i.putExtra(MediaStore.EXTRA_OUTPUT,PlanCaptureProvider.URI);
            i.setClipData(ClipData.newRawUri("Planfoto",PlanCaptureProvider.URI));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i,CAMERA);
        } catch(Exception e) { error(e); }
    }
    private void pickPhoto() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");
        startActivityForResult(i,PHOTO);
    }
    private void importZip() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
        // Some file providers label ZIPs as application/octet-stream.
        i.setType("*/*");startActivityForResult(i,IMPORT);
    }
    private void exportZip() {
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE,"MagnetRute_v0.3_Grundrisse.zip");startActivityForResult(i,EXPORT);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==CAMERA) revokeUriPermission(PlanCaptureProvider.URI,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if(result!=RESULT_OK) return;
        if(request==CAMERA) { loadPhoto(new File(getCacheDir(),"capture.jpg"),null);return; }
        if(data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if(request==PHOTO) loadPhoto(new File(getCacheDir(),"plan-input"),uri);
        else if(request==EXPORT) runJob(() -> {
            try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {
                if(out==null) throw new IOException("Keine Schreibberechtigung.");PlanArchive.write(document,out);
            }
            return () -> { showList();message("ZIP-Sicherung gespeichert."); };
        });
        else if(request==IMPORT) runJob(() -> {
            PlanArchive.Document imported;
            try(InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null) throw new IOException("Keine Leseberechtigung.");imported=PlanArchive.read(in);
            }
            validateImages(imported);
            return () -> new AlertDialog.Builder(this).setTitle("Grundrisse wiederherstellen?")
                .setMessage(imported.areas.size()+" Bereiche ersetzen alle aktuell gespeicherten Grundrisse. Messlogs bleiben erhalten.")
                .setNegativeButton("Abbrechen",(d,w) -> showList())
                .setPositiveButton("Ersetzen",(d,w) -> persist(imported,() -> { loadFailed=false;showList();message("ZIP erfolgreich wiederhergestellt."); })).show();
        });
    }
    private static void validateImages(PlanArchive.Document doc) throws IOException {
        for(PlanArchive.Area a:doc.areas) { Bitmap b=PlanImages.area(a);b.recycle(); }
    }
    private void loadPhoto(File file,Uri source) {
        runJob(() -> {
            if(source!=null) {
                try(InputStream in=getContentResolver().openInputStream(source);OutputStream out=new FileOutputStream(file)) {
                    if(in==null) throw new IOException("Foto nicht lesbar.");
                    byte[] buffer=new byte[8192];int n,total=0;
                    while((n=in.read(buffer))!=-1) { total+=n;if(total>30*1024*1024) throw new IOException("Fotodatei ist größer als 30 MB.");out.write(buffer,0,n); }
                }
            }
            if(file.length()>30*1024*1024) throw new IOException("Fotodatei ist größer als 30 MB.");
            Bitmap b=PlanImages.decode(file);saveWorking(b);
            return () -> { bitmap=b;showEditor("corners",null); };
        });
    }
    private void saveWorking(Bitmap b) throws IOException {
        try(FileOutputStream out=new FileOutputStream(working())) { out.write(PlanImages.jpeg(b)); }
    }
    private void showEditor(String next,double[] points) {
        stage=next;body.removeAllViews();actions=null;
        PlanEditorView.Mode mode="corners".equals(stage)?PlanEditorView.Mode.CORNERS:
            "crop".equals(stage)?PlanEditorView.Mode.CROP:"scale".equals(stage)?PlanEditorView.Mode.SCALE:PlanEditorView.Mode.PREVIEW;
        message(mode==PlanEditorView.Mode.CORNERS?"1 · Vier Ecken im Uhrzeigersinn: oben links → oben rechts → unten rechts → unten links":
            mode==PlanEditorView.Mode.CROP?"2 · Rechteck ziehen und Bereich speichern. Weitere Ausschnitte sind danach möglich.":
            mode==PlanEditorView.Mode.SCALE?"3 · Endpunkte einer bekannten Strecke ziehen, dann Länge in Metern eingeben.":scaleText(selected()));
        editor=new PlanEditorView(this,bitmap,mode,points);body.addView(editor,new LinearLayout.LayoutParams(-1,0,3));
        // Keep the image usable in landscape and let large-font action labels scroll.
        ScrollView commands=new ScrollView(this);
        actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);commands.addView(actions);
        body.addView(commands,new LinearLayout.LayoutParams(-1,0,2));
        if(mode==PlanEditorView.Mode.CORNERS) {
            button("Perspektive entzerren",() -> {
                double[] quad=editor.points();
                if(!PlanGeometry.validQuad(quad)) { error(new IOException("Vier Ecken müssen ein konvexes Viereck bilden."));return; }
                runJob(() -> { Bitmap b=PlanImages.rectify(bitmap,quad);saveWorking(b);return () -> { bitmap=b;showEditor("crop",null); }; });
            });
            button("Foto 90° drehen",() -> runJob(() -> {
                android.graphics.Matrix m=new android.graphics.Matrix();m.setRotate(90);
                Bitmap b=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);saveWorking(b);
                return () -> { bitmap=b;showEditor("corners",null); };
            }));
        } else if(mode==PlanEditorView.Mode.CROP) button("Ausschnitt als Bereich speichern",this::saveCrop);
        else if(mode==PlanEditorView.Mode.SCALE) button("Bekannte Länge eingeben",this::calibrate);
        else {
            button("Maßstab kalibrieren",() -> showEditor("scale",null));
            button("Bereich umbenennen",() -> rename(false));
            button("Stockwerk umbenennen",() -> rename(true));
            button("Bereich löschen",this::deleteArea);
        }
        button("Zur Bereichsliste",this::leaveEditor);
    }
    private PlanArchive.Area selected() {
        for(PlanArchive.Area a:document.areas) if(a.id.equals(selectedId)) return a;
        throw new IllegalStateException("Bereich nicht mehr vorhanden.");
    }
    private void openArea(PlanArchive.Area a) {
        runJob(() -> { Bitmap b=PlanImages.area(a);saveWorking(b);return () -> { bitmap=b;selectedId=a.id;showEditor("preview",null); }; });
    }
    private void saveCrop() {
        Rect crop=editor.crop();
        if(crop.width()<16 || crop.height()<16) { error(new IOException("Ausschnitt muss mindestens 16 × 16 Pixel groß sein."));return; }
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(18),0,dp(18),0);
        EditText floor=new EditText(this);floor.setSingleLine();floor.setHint("Stockwerk, z. B. Erdgeschoss");
        floor.setText(getPreferences(MODE_PRIVATE).getString("floor","Erdgeschoss"));fields.addView(floor);
        EditText name=new EditText(this);name.setSingleLine();name.setHint("Bereich, z. B. Küche");fields.addView(name);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Ausschnitt speichern").setView(fields)
            .setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String f=floor.getText().toString().trim(),n=name.getText().toString().trim();
            if(!validName(f) || !validName(n)) { name.setError("Stockwerk und Bereich benötigen je 1–120 Zeichen.");return; }
            dialog.dismiss();
            runJob(() -> {
                Bitmap cut=Bitmap.createBitmap(bitmap,crop.left,crop.top,crop.width(),crop.height());
                byte[] image=PlanImages.jpeg(cut);if(cut!=bitmap) cut.recycle();
                PlanArchive.Area area=new PlanArchive.Area(UUID.randomUUID().toString(),f,n,crop.width(),crop.height(),0,image);
                ArrayList<PlanArchive.Area> areas=new ArrayList<>(document.areas);areas.add(area);
                PlanArchive.Document updated=new PlanArchive.Document(areas);PlanArchive.save(updated,archive());document=updated;
                return () -> { getPreferences(MODE_PRIVATE).edit().putString("floor",f).apply();showEditor("crop",null);
                    message("Bereich gespeichert. Weiteren Ausschnitt wählen oder zur Bereichsliste wechseln."); };
            });
        }));dialog.show();
    }
    private static boolean validName(String name) {
        if(name.isEmpty() || name.length()>120) return false;
        for(int i=0;i<name.length();i++) if(Character.isISOControl(name.charAt(i))) return false;
        return true;
    }
    private void calibrate() {
        EditText value=new EditText(this);value.setHint("Länge in Metern, z. B. 4,25");
        value.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Maßstab kalibrieren").setView(value)
            .setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                double meters=Double.parseDouble(value.getText().toString().trim().replace(',','.'));double[] p=editor.points();
                if(Math.hypot(p[2]-p[0],p[3]-p[1])<2) throw new IllegalArgumentException("Strecke ist zu kurz.");
                double scale=PlanGeometry.metersPerPixel(p[0],p[1],p[2],p[3],meters);
                PlanArchive.Area a=selected();ArrayList<PlanArchive.Area> areas=new ArrayList<>(document.areas);
                areas.set(areas.indexOf(a),new PlanArchive.Area(a.id,a.floor,a.name,a.width,a.height,scale,a.image));
                dialog.dismiss();persist(new PlanArchive.Document(areas),() -> showEditor("preview",null));
            } catch(IllegalArgumentException e) { value.setError("Positive, endliche Länge und zwei verschiedene Punkte erforderlich."); }
        }));dialog.show();
    }
    private void rename(boolean wholeFloor) {
        PlanArchive.Area a=selected();EditText name=new EditText(this);name.setSingleLine();name.setText(wholeFloor?a.floor:a.name);
        AlertDialog d=new AlertDialog.Builder(this).setTitle(wholeFloor?"Stockwerk für alle zugehörigen Bereiche umbenennen":"Bereich umbenennen")
            .setView(name).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n=name.getText().toString().trim();if(!validName(n)) { name.setError("1–120 Zeichen erforderlich.");return; }
            ArrayList<PlanArchive.Area> areas=new ArrayList<>();
            for(PlanArchive.Area old:document.areas) areas.add((wholeFloor?old.floor.equals(a.floor):old.id.equals(a.id))?
                new PlanArchive.Area(old.id,wholeFloor?n:old.floor,wholeFloor?old.name:n,old.width,old.height,old.metersPerPixel,old.image):old);
            d.dismiss();persist(new PlanArchive.Document(areas),() -> showEditor("preview",null));
        }));d.show();
    }
    private void deleteArea() {
        PlanArchive.Area a=selected();new AlertDialog.Builder(this).setTitle("Bereich löschen?").setMessage(a.floor+" · "+a.name)
            .setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(d,w) -> {
                ArrayList<PlanArchive.Area> areas=new ArrayList<>(document.areas);areas.remove(a);
                persist(new PlanArchive.Document(areas),this::showList);
            }).show();
    }
    private void persist(PlanArchive.Document updated,Runnable done) {
        runJob(() -> { PlanArchive.save(updated,archive());document=updated;return done; });
    }
    private void leaveEditor() {
        if("corners".equals(stage) || "crop".equals(stage)) new AlertDialog.Builder(this).setTitle("Foto schließen?")
            .setMessage("Gespeicherte Bereiche bleiben erhalten. Das aktuelle Arbeitsfoto wird geschlossen.")
            .setNegativeButton("Weiter bearbeiten",null).setPositiveButton("Schließen",(d,w) -> showList()).show();
        else showList();
    }
    @Override public void onBackPressed() { if(busy) return;if(!"list".equals(stage)) leaveEditor();else super.onBackPressed(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);out.putString("stage",busy?"list":stage);out.putString("selectedId",selectedId);
        if(editor!=null) out.putDoubleArray("points",editor.points());
    }
}
