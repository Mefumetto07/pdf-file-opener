package com.dosyaacici;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

public class ViewerActivity extends Activity {
    static final List<String> IMAGES = Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "heic", "heif");
    static final List<String> MEDIA = Arrays.asList("mp4", "mkv", "webm", "3gp", "mov", "avi", "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "amr");
    static final List<String> OFFICE = Arrays.asList("docx", "docm", "dotx", "xlsx", "xlsm", "pptx", "ppsx", "odt", "ods", "odp");
    static final List<String> ARCHIVES = Arrays.asList("zip", "jar", "apk", "epub");

    Uri uri;
    String mime, ext;
    PdfRenderer pdf;
    ParcelFileDescriptor pfd;
    float zoom = 1f;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        String name = displayName();
        setTitle(name);
        int dot = name.lastIndexOf('.');
        ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        mime = getContentResolver().getType(uri);
        if (mime == null) mime = "";

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

    String displayName() {
        String n = null;
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) { if (c.moveToFirst()) n = c.getString(0); c.close(); }
        } catch (Exception ignored) { }
        if (n == null) n = uri.getLastPathSegment();
        return n == null ? "Dosya" : n;
    }

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
        if (OFFICE.contains(ext)) return Convert.page(Convert.office(data, ext));
        if (ARCHIVES.contains(ext) || mime.equals("application/zip")) {
            StringBuilder sb = new StringBuilder("<h3>Arşiv içeriği</h3><pre>");
            for (String e : Convert.zipList(data)) sb.append(Convert.esc(e)).append('\n');
            return Convert.page(sb.append("</pre>").toString());
        }
        String t = Convert.text(data);
        if (t == null) return null;
        if (ext.equals("html") || ext.equals("htm") || mime.equals("text/html")) return t;
        return Convert.page("<pre>" + Convert.esc(t) + "</pre>");
    }

    void show(Object r) {
        if (r instanceof File) showPdf((File) r);
        else if (r instanceof Uri) showMedia();
        else if (r instanceof String) showHtml((String) r);
        else showMessage(r instanceof Throwable ? "Dosya açılamadı: " + ((Throwable) r).getMessage()
                : "Bu dosya türü burada gösterilemiyor.");
    }

    void showHtml(String html) {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        w.setBackgroundColor(Color.WHITE);
        w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        setContentView(w);
    }

    void showMedia() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Color.BLACK);
        VideoView v = new VideoView(this);
        f.addView(v, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        MediaController mc = new MediaController(this);
        mc.setAnchorView(v);
        v.setMediaController(mc);
        v.setVideoURI(uri);
        v.start();
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
        final ListView list = new ListView(this);
        list.setDividerHeight((int) (8 * getResources().getDisplayMetrics().density));
        final BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return pdf.getPageCount(); }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }
            public View getView(int i, View old, ViewGroup parent) {
                ImageView iv = old != null ? (ImageView) old : new ImageView(ViewerActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                PdfRenderer.Page p = pdf.openPage(i);
                int w = (int) (screenW * zoom);
                int hgt = w * p.getHeight() / p.getWidth();
                // cap bitmap size to keep memory bounded; ImageView stretches it to the zoomed width
                int rw = Math.min(w, 2000), rh = rw * p.getHeight() / p.getWidth();
                Bitmap bm = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
                bm.eraseColor(Color.WHITE);
                p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
                iv.setImageBitmap(bm);
                iv.setLayoutParams(new AbsListView.LayoutParams(w, hgt));
                return iv;
            }
        };
        list.setAdapter(adapter);
        h.addView(list, new FrameLayout.LayoutParams(screenW, -1));
        setContentView(h);

        final ScaleGestureDetector sg = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            float start;
            public boolean onScaleBegin(ScaleGestureDetector d) { start = zoom; return true; }
            public boolean onScale(ScaleGestureDetector d) {
                list.setScaleX(d.getScaleFactor() * zoom / start);
                return true;
            }
            public void onScaleEnd(ScaleGestureDetector d) {
                zoom = Math.max(1f, Math.min(4f, zoom * list.getScaleX()));
                list.setScaleX(1f);
                list.getLayoutParams().width = (int) (screenW * zoom);
                list.requestLayout();
                adapter.notifyDataSetChanged();
            }
        });
        pinch = sg;
    }

    ScaleGestureDetector pinch;

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (pinch != null) pinch.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    void openWith() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime.length() > 0 ? mime : "*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "Şununla aç")); } catch (Exception ignored) { }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add("Başka uygulamayla aç");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        openWith();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (pdf != null) pdf.close(); if (pfd != null) pfd.close(); } catch (Exception ignored) { }
    }
}
