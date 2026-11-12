package com.fu.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;

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

        askPermissions();
    }

    boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    void askPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    void activate() {
        if (!hasMic()) {
            askPermissions();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل العرض فوق التطبيقات ثم ارجع واضغط تفعيل", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        // keeps the connection alive when the screen is off (asked once only)
        SharedPreferences sp = getSharedPreferences("p", MODE_PRIVATE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()) && !sp.getBoolean("bat", false)) {
            sp.edit().putBoolean("bat", true).apply();
            Toast.makeText(this, "اسمح بتجاهل توفير البطارية ثم ارجع واضغط تفعيل", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception e) {
                // device without this screen: just continue
            }
        }
        startForegroundService(new Intent(this, VoiceService.class));
        finish();
    }
}
