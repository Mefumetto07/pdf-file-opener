package com.dosyaacici;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int PICK = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding((int) (32 * dp), 0, (int) (32 * dp), 0);
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
        sub.setPadding(0, (int) (12 * dp), 0, (int) (40 * dp));
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
        setContentView(root);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Intent v = new Intent(this, ViewerActivity.class);
        v.setData(data.getData());
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(v);
    }
}
