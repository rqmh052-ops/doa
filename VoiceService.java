package com.fu.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class VoiceService extends Service {

    WindowManager wm;
    WebView web;
    LinearLayout box;
    TextView mic, spk;
    WindowManager.LayoutParams boxLp;
    boolean micOn = false;
    boolean listenOn = true;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationChannel ch = new NotificationChannel("v", "Voice", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "v")
                .setContentTitle("Voice")
                .setContentText("ON")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addWeb();
        addButtons();
    }

    // ---------- hidden WebView (1x1) that runs the audio logic ----------
    void addWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest r) {
                r.grant(r.getResources());
            }
        });
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        wm.addView(web, p);
        web.loadDataWithBaseURL("https://voice.local/", readAsset("index.html"), "text/html", "utf-8", null);
    }

    String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- floating buttons ----------
    void addButtons() {
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        spk = circle("🔊");
        mic = circle("🎤");
        box.addView(spk);
        box.addView(mic);
        refresh();

        boxLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        boxLp.gravity = Gravity.TOP | Gravity.START;
        boxLp.x = dp(8);
        boxLp.y = dp(200);
        wm.addView(box, boxLp);

        spk.setOnTouchListener(new Drag(this::toggleListen));
        mic.setOnTouchListener(new Drag(this::toggleMic));
    }

    TextView circle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(22);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    void paint(TextView v, boolean on, int onColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? onColor : 0xCC555555);
        v.setBackground(g);
    }

    void refresh() {
        paint(mic, micOn, 0xCCD32F2F);
        paint(spk, listenOn, 0xCC2E7D32);
        spk.setText(listenOn ? "🔊" : "🔇");
    }

    void toggleMic() {
        micOn = !micOn;
        js("setMic(" + micOn + ")");
        refresh();
    }

    void toggleListen() {
        listenOn = !listenOn;
        js("setListen(" + listenOn + ")");
        if (!listenOn && micOn) {
            micOn = false;
            js("setMic(false)");
        }
        refresh();
    }

    void js(String s) {
        web.evaluateJavascript(s, null);
    }

    int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density);
    }

    // tap = toggle, drag = move the buttons
    class Drag implements View.OnTouchListener {
        Runnable tap;
        float dx, dy;
        int sx, sy;
        boolean moved;

        Drag(Runnable t) {
            tap = t;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dx = e.getRawX();
                    dy = e.getRawY();
                    sx = boxLp.x;
                    sy = boxLp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float mx = e.getRawX() - dx;
                    float my = e.getRawY() - dy;
                    if (Math.abs(mx) > dp(8) || Math.abs(my) > dp(8)) moved = true;
                    if (moved) {
                        boxLp.x = sx + (int) mx;
                        boxLp.y = sy + (int) my;
                        wm.updateViewLayout(box, boxLp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) tap.run();
                    return true;
                default:
                    return true;
            }
        }
    }

    @Override
    public void onDestroy() {
        try { wm.removeView(box); } catch (Exception e) { }
        try { wm.removeView(web); } catch (Exception e) { }
        try { web.destroy(); } catch (Exception e) { }
        super.onDestroy();
    }
}
