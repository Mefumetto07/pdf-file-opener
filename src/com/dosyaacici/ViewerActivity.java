package com.dosyaacici;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ViewerActivity extends Activity {
    static final List<String> IMAGES = Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "heic", "heif");
    static final List<String> MEDIA = Arrays.asList("mp4", "mkv", "webm", "3gp", "mov", "avi", "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "amr");
    static final List<String> OFFICE = Arrays.asList("docx", "docm", "dotx", "xlsx", "xlsm", "pptx", "ppsx", "odt", "ods", "odp", "doc", "dot", "xls", "xlt");
    static final List<String> EDITABLE_OFFICE = Arrays.asList("docx", "docm", "dotx", "pptx", "ppsx");
    static final List<String> ARCHIVES = Arrays.asList("zip", "jar", "apk", "epub");
    static final int SAVE_AS = 2, MENU_EDIT = 1, MENU_SAVE = 2, MENU_OPEN_WITH = 3, MENU_DONE = 4;
    static final String TEXT_KEY = "text#0";

    Uri uri;
    String mime, ext, name, key;
    SharedPreferences prefs;
    float startPos;                // where the user left off: page, scroll ratio or milliseconds

    PdfRenderer pdf;
    ParcelFileDescriptor pfd;
    float zoom = 1f;
    ListView pdfList;
    BaseAdapter pdfAdapter;
    WebView web;
    VideoView video;

    byte[] original;               // docx/pptx bytes, rewritten on save
    List<String> editKeys;         // editable text runs; null = this file is view-only
    boolean editing;
    byte[] pendingSave;            // bytes waiting for a "save as" location
    final List<Note> notes = new ArrayList<Note>();  // PDF cover-and-retype boxes
    boolean notesDirty;
    float[] drag;                  // PDF selection in progress: page, x0, y0, x1, y1 (fractions)

    static class Note { int page; float l, t, r, b; String text; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        name = displayName();
        setTitle(name);
        int dot = name.lastIndexOf('.');
        ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        mime = getContentResolver().getType(uri);
        if (mime == null) mime = "";
        prefs = getSharedPreferences("dosya", MODE_PRIVATE);
        key = uri.toString();
        startPos = prefs.getFloat("pos:" + key, 0);
        remember();

        FrameLayout loading = new FrameLayout(this);
        loading.setBackgroundColor(Color.WHITE);
        loading.addView(new ProgressBar(this), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(loading);

        new Thread() {
            public void run() {
                Object r;
                try { r = load(); } catch (Throwable e) { r = e; }
                final Object res = r;
                runOnUiThread(new Runnable() { public void run() { if (!isFinishing()) show(res); } });
            }
        }.start();
    }

    // ---------------- recent files and reading position ----------------

    static JSONArray recent(SharedPreferences p) {
        try { return new JSONArray(p.getString("recent", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** Moves this file to the top of the recent list (max 20). */
    void remember() {
        JSONArray old = recent(prefs), a = new JSONArray();
        a.put(new JSONArray().put(key).put(name));
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < old.length(); i++) {
            JSONArray e = old.optJSONArray(i);
            if (e == null || key.equals(e.optString(0))) continue;
            if (a.length() < 20) { a.put(e); continue; }
            // dropped off the list: free the persisted grant (Android caps them per app) and its position
            ed.remove("pos:" + e.optString(0)).remove("where:" + e.optString(0));
            Uri dropped = Uri.parse(e.optString(0));
            try {
                getContentResolver().releasePersistableUriPermission(dropped,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception rw) {
                try { getContentResolver().releasePersistableUriPermission(dropped, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            }
        }
        ed.putString("recent", a.toString()).apply();
    }

    void forget() {
        JSONArray old = recent(prefs), a = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONArray e = old.optJSONArray(i);
            if (e != null && !key.equals(e.optString(0))) a.put(e);
        }
        prefs.edit().putString("recent", a.toString()).remove("pos:" + key).remove("where:" + key).apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        float pos = -1;
        String where = null;
        if (pdfList != null) {
            pos = pdfList.getFirstVisiblePosition();
            where = "Sayfa " + ((int) pos + 1) + " / " + pdf.getPageCount();
        } else if (web != null) {
            float h = web.getContentHeight() * web.getScale();
            if (h > 0) { pos = web.getScrollY() / h; where = "%" + Math.round(pos * 100); }
        } else if (video != null) {
            pos = video.getCurrentPosition();
            int s = (int) pos / 1000;
            where = String.format("%d:%02d", s / 60, s % 60);
        }
        if (pos >= 0 && prefs != null) prefs.edit().putFloat("pos:" + key, pos).putString("where:" + key, where).apply();
    }

    String displayName() {
        String n = null;
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) { if (c.moveToFirst()) n = c.getString(0); c.close(); }
        } catch (Exception ignored) { }
        if (n == null) n = uri.getLastPathSegment();
        return n == null ? "Dosya" : n;
    }

    // ---------------- loading ----------------

    /** Runs off the UI thread. Returns File (PDF), Uri (media), String (HTML) or null (unsupported). */
    Object load() throws Exception {
        if (ext.equals("pdf") || mime.equals("application/pdf")) {
            // copy so PdfRenderer always gets a seekable file, whatever the provider hands us
            File f = new File(getCacheDir(), "belge.pdf");
            InputStream in = getContentResolver().openInputStream(uri);
            OutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close(); out.close();
            return f;
        }
        if (mime.startsWith("video/") || mime.startsWith("audio/") || MEDIA.contains(ext)) return uri;

        InputStream in = getContentResolver().openInputStream(uri);
        byte[] data;
        try { data = Convert.read(in); } finally { in.close(); }

        if (mime.startsWith("image/") || IMAGES.contains(ext)) {
            String m = mime.startsWith("image/") ? mime : ext.equals("svg") ? "image/svg+xml" : "image/" + ext;
            // shortcut: whole image as a data URI (30 MB cap); fine for photos, switch to a tiled view for huge scans
            return Convert.page("<img src='data:" + m + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP) + "'>");
        }
        if (EDITABLE_OFFICE.contains(ext)) {
            List<String> keys = new ArrayList<String>();
            String html = Convert.page(Convert.office(data, ext, keys));
            original = data;
            editKeys = keys;
            return html;
        }
        if (OFFICE.contains(ext)) return Convert.page(Convert.office(data, ext));
        if (ARCHIVES.contains(ext) || mime.equals("application/zip")) {
            StringBuilder sb = new StringBuilder("<h3>Arşiv içeriği</h3><pre>");
            for (String e : Convert.zipList(data)) sb.append(Convert.esc(e)).append('\n');
            return Convert.page(sb.append("</pre>").toString());
        }
        String t = Convert.text(data);
        if (t == null) return null;
        if (ext.equals("html") || ext.equals("htm") || mime.equals("text/html")) return t;
        editKeys = Arrays.asList(TEXT_KEY);
        return Convert.page("<pre><span data-k=\"" + TEXT_KEY + "\">" + Convert.esc(t) + "</span></pre>");
    }

    void show(Object r) {
        if (r instanceof File) showPdf((File) r);
        else if (r instanceof Uri) showMedia();
        else if (r instanceof String) showHtml((String) r);
        else if (r instanceof SecurityException || r instanceof FileNotFoundException) {
            forget();
            showMessage("Bu dosyaya artık erişilemiyor (taşınmış, silinmiş veya izin süresi dolmuş olabilir). Lütfen dosyayı yeniden seçin.");
        } else showMessage(r instanceof Throwable ? "Dosya açılamadı: " + ((Throwable) r).getMessage()
                : "Bu dosya türü burada gösterilemiyor.");
        invalidateOptionsMenu();
    }

    void showHtml(String html) {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        // JS only for our own escaped document HTML (needed to read edits); never for the user's .html files
        s.setJavaScriptEnabled(editKeys != null);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        web.setBackgroundColor(Color.WHITE);
        web.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; }
            public void onPageFinished(final WebView v, String url) {
                if (startPos <= 0) return;
                v.postDelayed(new Runnable() {
                    public void run() { v.scrollTo(0, (int) (startPos * v.getContentHeight() * v.getScale())); startPos = 0; }
                }, 300);
            }
        });
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        setContentView(web);
    }

    void showMedia() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        f.addView(video, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        video.setMediaController(mc);
        video.setVideoURI(uri);
        if (startPos > 0) video.seekTo((int) startPos);
        video.start();
        setContentView(f);
    }

    void showMessage(String msg) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding((int) (32 * dp), 0, (int) (32 * dp), 0);
        l.setBackgroundColor(Color.WHITE);
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, (int) (24 * dp));
        l.addView(t);
        Button b = new Button(this);
        b.setText("Başka uygulamayla aç");
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openWith(); } });
        l.addView(b);
        setContentView(l);
    }

    // ---------------- PDF ----------------

    void showPdf(File f) {
        try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            pdf = new PdfRenderer(pfd);
        } catch (Exception e) {
            showMessage("PDF açılamadı (şifreli veya bozuk olabilir).");
            return;
        }
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final HorizontalScrollView h = new HorizontalScrollView(this);
        h.setBackgroundColor(0xFFE0E0E0);
        pdfList = new ListView(this);
        pdfList.setDividerHeight((int) (8 * getResources().getDisplayMetrics().density));
        pdfAdapter = new BaseAdapter() {
            public int getCount() { return pdf.getPageCount(); }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View old, ViewGroup parent) {
                ImageView iv = old != null ? (ImageView) old : pageView();
                iv.setTag(i);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                int w = (int) (screenW * zoom);
                Bitmap bm;
                int hgt;
                synchronized (pdf) {
                    PdfRenderer.Page p = pdf.openPage(i);
                    hgt = w * p.getHeight() / p.getWidth();
                    // cap bitmap size to keep memory bounded; ImageView stretches it to the zoomed width
                    int rw = Math.min(w, 2000), rh = rw * p.getHeight() / p.getWidth();
                    bm = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
                    bm.eraseColor(Color.WHITE);
                    p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    p.close();
                }
                drawNotes(new Canvas(bm), i, bm.getWidth(), bm.getHeight());
                iv.setImageBitmap(bm);
                iv.setLayoutParams(new AbsListView.LayoutParams(w, hgt));
                return iv;
            }
        };
        pdfList.setAdapter(pdfAdapter);
        pdfList.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) { }
            public void onScroll(AbsListView v, int first, int visible, int total) {
                if (getActionBar() != null) getActionBar().setSubtitle("Sayfa " + (first + 1) + " / " + total);
            }
        });
        if (startPos > 0) pdfList.setSelection((int) startPos);
        h.addView(pdfList, new FrameLayout.LayoutParams(screenW, -1));
        setContentView(h);

        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            float start;
            public boolean onScaleBegin(ScaleGestureDetector d) { start = zoom; return !editing; }
            public boolean onScale(ScaleGestureDetector d) {
                pdfList.setScaleX(d.getScaleFactor() * zoom / start);
                return true;
            }
            public void onScaleEnd(ScaleGestureDetector d) {
                zoom = Math.max(1f, Math.min(4f, zoom * pdfList.getScaleX()));
                pdfList.setScaleX(1f);
                pdfList.getLayoutParams().width = (int) (screenW * zoom);
                pdfList.requestLayout();
                pdfAdapter.notifyDataSetChanged();
            }
        });
    }

    /** A page image that, in edit mode, lets the user drag a box over text to replace it, or tap a box to change it. */
    ImageView pageView() {
        final ImageView iv = new ImageView(this) {
            final Paint sel = new Paint();
            @Override
            protected void onDraw(Canvas c) {
                super.onDraw(c);
                if (drag == null || drag[0] != (Integer) getTag()) return;
                sel.setColor(0x551E88E5);
                c.drawRect(Math.min(drag[1], drag[3]) * getWidth(), Math.min(drag[2], drag[4]) * getHeight(),
                    Math.max(drag[1], drag[3]) * getWidth(), Math.max(drag[2], drag[4]) * getHeight(), sel);
            }
        };
        iv.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (!editing) return false;
                v.getParent().requestDisallowInterceptTouchEvent(true); // no scrolling while selecting
                float x = Math.max(0, Math.min(1, e.getX() / v.getWidth())), y = Math.max(0, Math.min(1, e.getY() / v.getHeight()));
                int page = (Integer) v.getTag();
                if (e.getAction() == MotionEvent.ACTION_DOWN) drag = new float[]{page, x, y, x, y};
                else if (drag != null) { drag[3] = x; drag[4] = y; }
                if (drag != null && (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)) {
                    float[] d = drag;
                    drag = null;
                    boolean tap = Math.abs(d[3] - d[1]) < 0.02f && Math.abs(d[4] - d[2]) < 0.01f;
                    Note n = null;
                    if (tap) { for (Note o : notes) if (o.page == page && x >= o.l && x <= o.r && y >= o.t && y <= o.b) n = o; }
                    else if (e.getAction() == MotionEvent.ACTION_UP) {
                        n = new Note();
                        n.page = page;
                        n.l = Math.min(d[1], d[3]); n.r = Math.max(d[1], d[3]);
                        n.t = Math.min(d[2], d[4]); n.b = Math.max(d[2], d[4]);
                    }
                    if (n != null) editNote(n);
                    else if (tap) Toast.makeText(ViewerActivity.this, "Değiştirmek istediğiniz metnin üzerini parmağınızla çizerek seçin", Toast.LENGTH_SHORT).show();
                }
                v.invalidate();
                return true;
            }
        });
        return iv;
    }

    void editNote(final Note n) {
        final EditText input = new EditText(this);
        input.setText(n.text);
        input.setSelection(input.getText().length());
        AlertDialog.Builder d = new AlertDialog.Builder(this)
            .setTitle("Yeni metin")
            .setView(input)
            .setPositiveButton("Tamam", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) {
                    n.text = input.getText().toString();
                    if (!notes.contains(n)) notes.add(n);
                    notesDirty = true;
                    pdfAdapter.notifyDataSetChanged();
                }
            })
            .setNegativeButton("İptal", null);
        if (notes.contains(n)) d.setNeutralButton("Sil", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface di, int w) {
                notes.remove(n);
                notesDirty = true;
                pdfAdapter.notifyDataSetChanged();
            }
        });
        d.show();
    }

    /** White box over the selected area, new text fitted inside it. Used for screen and for the saved PDF. */
    void drawNotes(Canvas c, int page, float w, float h) {
        Paint bg = new Paint();
        bg.setColor(Color.WHITE);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.BLACK);
        for (Note n : notes) {
            if (n.page != page) continue;
            float l = n.l * w, t = n.t * h, bw = (n.r - n.l) * w, bh = (n.b - n.t) * h;
            c.drawRect(l, t, l + bw, t + bh, bg);
            if (n.text == null || n.text.length() == 0) continue;
            String[] lines = n.text.split("\n");
            float size = bh / lines.length / 1.15f;
            tp.setTextSize(size);
            float widest = 0;
            for (String s : lines) widest = Math.max(widest, tp.measureText(s));
            if (widest > bw) { size *= bw / widest; tp.setTextSize(size); }
            for (int i = 0; i < lines.length; i++) c.drawText(lines[i], l, t + size * (1.15f * i + 0.95f), tp);
        }
    }

    /** Writes every page (as an image) plus the boxes into a new PDF. Original text stays only as pixels. */
    void exportPdf(final Uri target) {
        Toast.makeText(this, "PDF kaydediliyor…", Toast.LENGTH_SHORT).show();
        new Thread() {
            public void run() {
                String msg = "Kaydedildi";
                PdfDocument doc = new PdfDocument();
                try {
                    Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
                    for (int i = 0; i < pdf.getPageCount(); i++) {
                        Bitmap bm;
                        int pw, ph;
                        synchronized (pdf) {
                            PdfRenderer.Page p = pdf.openPage(i);
                            pw = p.getWidth(); ph = p.getHeight();
                            bm = Bitmap.createBitmap(pw * 2, ph * 2, Bitmap.Config.ARGB_8888); // 144 dpi
                            bm.eraseColor(Color.WHITE);
                            p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            p.close();
                        }
                        PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, i + 1).create());
                        Canvas c = page.getCanvas();
                        c.save();
                        c.scale(0.5f, 0.5f);
                        c.drawBitmap(bm, 0, 0, smooth);
                        c.restore();
                        drawNotes(c, i, pw, ph);
                        doc.finishPage(page);
                        bm.recycle();
                    }
                    OutputStream o = getContentResolver().openOutputStream(target);
                    doc.writeTo(o);
                    o.close();
                    notesDirty = false;
                } catch (Throwable e) {
                    msg = "Kaydedilemedi: " + e.getMessage();
                } finally {
                    doc.close();
                }
                final String m = msg;
                runOnUiThread(new Runnable() { public void run() { Toast.makeText(ViewerActivity.this, m, Toast.LENGTH_LONG).show(); } });
            }
        }.start();
    }

    ScaleGestureDetector pinch;

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (pinch != null && !editing) pinch.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    // ---------------- editing text / Word / PowerPoint ----------------

    void setEditing(boolean on) {
        editing = on;
        if (web != null) web.evaluateJavascript(on ? Convert.JS_EDIT_ON : Convert.JS_EDIT_OFF, null);
        if (on) Toast.makeText(this, pdf != null
            ? "Değiştirmek istediğiniz metnin üzerini parmağınızla çizerek seçin"
            : "Sarı alanlardaki metne dokunup düzenleyin", Toast.LENGTH_LONG).show();
        invalidateOptionsMenu();
    }

    void save() {
        if (pdf != null) {
            if (notes.isEmpty()) { Toast.makeText(this, "Henüz değişiklik yok", Toast.LENGTH_SHORT).show(); return; }
            pendingSave = null;
            // always a new file: the original PDF keeps its selectable text
            saveAs(name.replaceAll("(?i)\\.pdf$", "") + "-duzenlenmis.pdf", "application/pdf");
            return;
        }
        web.evaluateJavascript(Convert.JS_COLLECT, new ValueCallback<String>() {
            public void onReceiveValue(String v) {
                try {
                    JSONObject o = new JSONObject(new JSONArray("[" + v + "]").getString(0));
                    final Map<String, String> edits = new HashMap<String, String>();
                    for (String k : editKeys) edits.put(k, o.optString(k, "")); // a run the user emptied is ""
                    new Thread() {
                        public void run() {
                            byte[] bytes;
                            try {
                                bytes = original != null ? Convert.applyEdits(original, edits)
                                    : edits.get(TEXT_KEY).getBytes("UTF-8");
                            } catch (final Exception e) {
                                runOnUiThread(new Runnable() { public void run() {
                                    Toast.makeText(ViewerActivity.this, "Kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show(); } });
                                return;
                            }
                            final byte[] b = bytes;
                            runOnUiThread(new Runnable() { public void run() { write(b); } });
                        }
                    }.start();
                } catch (Exception e) {
                    Toast.makeText(ViewerActivity.this, "Kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** Overwrites the opened file; if the source app does not allow writing, asks where to save a copy. */
    void write(byte[] bytes) {
        try {
            OutputStream o = getContentResolver().openOutputStream(uri, "wt");
            o.write(bytes);
            o.close();
            if (original != null) original = bytes;
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show();
            setEditing(false);
        } catch (Exception e) {
            pendingSave = bytes;
            Toast.makeText(this, "Bu dosyanın üzerine yazılamıyor, kopya olarak kaydedin", Toast.LENGTH_LONG).show();
            saveAs(name, mime.length() > 0 ? mime : "application/octet-stream");
        }
    }

    void saveAs(String fileName, String type) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        i.putExtra(Intent.EXTRA_TITLE, fileName);
        startActivityForResult(i, SAVE_AS);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != SAVE_AS || res != RESULT_OK || data == null || data.getData() == null) return;
        if (pdf != null) { exportPdf(data.getData()); setEditing(false); return; }
        if (pendingSave == null) return;
        try {
            OutputStream o = getContentResolver().openOutputStream(data.getData(), "wt");
            o.write(pendingSave);
            o.close();
            pendingSave = null;
            Toast.makeText(this, "Kopya kaydedildi", Toast.LENGTH_SHORT).show();
            setEditing(false);
        } catch (Exception e) {
            Toast.makeText(this, "Kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        boolean unsaved = pdf != null ? notesDirty : editing;
        if (!unsaved) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this)
            .setMessage("Değişiklikler kaydedilmedi. Yine de çıkılsın mı?")
            .setPositiveButton("Çık", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { finish(); }
            })
            .setNegativeButton("İptal", null)
            .show();
    }

    // ---------------- menu ----------------

    void openWith() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime.length() > 0 ? mime : "*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "Şununla aç")); } catch (Exception ignored) { }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        m.clear();
        if (pdf != null || (web != null && editKeys != null))
            m.add(0, editing ? MENU_SAVE : MENU_EDIT, 0, editing ? "Kaydet" : "Düzenle").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        // PDF edit mode captures touches for selection; "Bitti" returns to scrolling and keeps the boxes
        if (pdf != null && editing) m.add(0, MENU_DONE, 0, "Bitti").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, MENU_OPEN_WITH, 0, "Başka uygulamayla aç");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_EDIT) setEditing(true);
        else if (item.getItemId() == MENU_SAVE) save();
        else if (item.getItemId() == MENU_DONE) setEditing(false);
        else if (item.getItemId() == MENU_OPEN_WITH) openWith();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (pdf != null) synchronized (pdf) { pdf.close(); } if (pfd != null) pfd.close(); } catch (Exception ignored) { }
    }
}
