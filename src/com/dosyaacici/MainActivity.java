package com.dosyaacici;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PICK = 1;
    ListView list;
    TextView header;
    final List<Map<String, String>> rows = new ArrayList<Map<String, String>>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding((int) (24 * dp), (int) (32 * dp), (int) (24 * dp), 0);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Dosya Açıcı");
        title.setTextSize(30);
        title.setTextColor(0xFF1E88E5);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("PDF, Word, Excel, PowerPoint, resim, video, ses, metin ve daha fazlası");
        sub.setTextSize(15);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));
        root.addView(sub);

        Button open = new Button(this);
        open.setText("Dosya Seç");
        open.setTextSize(20);
        open.setPadding((int) (40 * dp), (int) (16 * dp), (int) (40 * dp), (int) (16 * dp));
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, PICK);
            }
        });
        root.addView(open);

        header = new TextView(this);
        header.setText("Son açılanlar");
        header.setTextSize(17);
        header.setTextColor(0xFF555555);
        header.setPadding(0, (int) (28 * dp), 0, (int) (4 * dp));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setAdapter(new SimpleAdapter(this, rows, android.R.layout.simple_list_item_2,
            new String[]{"name", "where"}, new int[]{android.R.id.text1, android.R.id.text2}));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Intent i = new Intent(MainActivity.this, ViewerActivity.class);
                i.setData(Uri.parse(rows.get(pos).get("uri")));
                startActivity(i);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences("dosya", MODE_PRIVATE);
        JSONArray a = ViewerActivity.recent(prefs);
        rows.clear();
        for (int i = 0; i < a.length(); i++) {
            JSONArray e = a.optJSONArray(i);
            if (e == null) continue;
            Map<String, String> r = new HashMap<String, String>();
            r.put("uri", e.optString(0));
            r.put("name", e.optString(1));
            String where = prefs.getString("where:" + e.optString(0), null);
            r.put("where", where == null ? "" : "Kaldığınız yer: " + where);
            rows.add(r);
        }
        header.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        ((SimpleAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        // keep access after restart so the file can be reopened from "Son açılanlar" and saved in place
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        }
        Intent v = new Intent(this, ViewerActivity.class);
        v.setData(u); // same app: the picker's grant already covers ViewerActivity
        startActivity(v);
    }
}
