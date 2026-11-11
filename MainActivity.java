package com.fu.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);

        Button on = new Button(this);
        on.setText("تفعيل");
        Button off = new Button(this);
        off.setText("إيقاف");

        on.setOnClickListener(v -> activate());
        off.setOnClickListener(v -> stopService(new Intent(this, VoiceService.class)));

        l.addView(on);
        l.addView(off);
        setContentView(l);

        askMic();
    }

    void askMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
        }
    }

    void activate() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل العرض فوق التطبيقات ثم ارجع واضغط تفعيل", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        startForegroundService(new Intent(this, VoiceService.class));
        finish();
    }
}
