package com.fu.voice;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class VoiceService extends Service {

    static final String HOST = "dody-production.up.railway.app";
    static final String KEY = "fu7777-k3x9q";
    static final int RATE = 16000;
    static final int FRAME = 320; // 20 ms of 16 kHz mono

    static final short[] ULAW = new short[256];

    static {
        for (int i = 0; i < 256; i++) ULAW[i] = ulawDecode((byte) i);
    }

    WindowManager wm;
    NotificationManager nm;
    LinearLayout box;
    IconView mic, spk;
    WindowManager.LayoutParams boxLp;
    final Handler main = new Handler(Looper.getMainLooper());

    volatile boolean micOn = false;     // mic button state
    volatile boolean listenOn = true;   // speaker button state (default: always listening)
    volatile boolean destroyed = false;
    volatile boolean connected = false;
    volatile boolean peerOn = false;
    volatile boolean micRun = false;    // mic thread alive == AudioRecord open
    volatile boolean playRun = false;   // play thread alive == AudioTrack open
    volatile long lastRx = 0;
    volatile long lastConnectAt = 0;
    volatile int attempt = 0;
    String lastStatus = "";

    OkHttpClient client;
    volatile WebSocket ws;
    volatile int gen = 0;
    String devId;
    Thread micThread, playThread;
    final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

    PowerManager.WakeLock cpuLock;
    WifiManager.WifiLock wifiLock;
    ConnectivityManager cm;
    ConnectivityManager.NetworkCallback netCb;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        if (!destroyed) goForeground(); // every startForegroundService() call must be answered
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("v", "Voice", NotificationManager.IMPORTANCE_LOW));
        if (!goForeground()) {
            destroyed = true;
            stopSelf();
            return;
        }

        SharedPreferences sp = getSharedPreferences("p", MODE_PRIVATE);
        devId = sp.getString("id", null);
        if (devId == null) {
            devId = UUID.randomUUID().toString().substring(0, 8);
            sp.edit().putString("id", devId).apply();
        }

        // keep CPU and Wi-Fi awake so the connection survives screen-off
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voice:cpu");
            cpuLock.setReferenceCounted(false);
            cpuLock.acquire();
        } catch (Exception e) { }
        try {
            WifiManager wf = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wf.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "voice:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception e) { }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            addButtons();
        } catch (Exception e) { // overlay permission missing
            destroyed = true;
            stopSelf();
            return;
        }

        client = new OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)   // dead socket is noticed in ~10-20 s
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();

        connect();
        startPlay();   // listening is on from the start, mic stays closed
        main.postDelayed(watchdog, 5000);

        // reconnect immediately when the phone gets a (new) network
        try {
            cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    main.post(VoiceService.this::netChanged);
                }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception e) { }
    }

    // ---------- foreground + notification (doubles as a status line) ----------
    boolean goForeground() {
        try {
            Notification n = note(lastStatus.isEmpty() ? "..." : lastStatus);
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(1, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    Notification note(String text) {
        Notification.Builder b = new Notification.Builder(this, "v")
                .setContentTitle("Voice")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    void status() {
        String s = !connected ? "لا يوجد اتصال بالسيرفر" : (peerOn ? "الصاحب متصل" : "في انتظار الصاحب");
        if (!s.equals(lastStatus)) {
            lastStatus = s;
            try { nm.notify(1, note(s)); } catch (Exception e) { }
        }
        main.post(this::refresh);
    }

    // ---------- network (WebSocket relay) with fast auto-reconnect ----------
    final Runnable reconnect = this::connect;

    final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            long now = System.currentTimeMillis();
            if (connected && now - lastRx > 15000) {          // server heartbeat stopped: socket is dead
                attempt = 0;
                connect();
            } else if (!connected && now - lastConnectAt > 12000) { // safety net
                connect();
            }
            main.postDelayed(this, 5000);
        }
    };

    void netChanged() {
        if (destroyed) return;
        if (System.currentTimeMillis() - lastConnectAt < 2500) return; // just connected
        attempt = 0;
        connect();
    }

    void connect() {
        if (destroyed) return;
        main.removeCallbacks(reconnect);
        long now = System.currentTimeMillis();
        lastConnectAt = now;
        lastRx = now;
        int g = ++gen;
        WebSocket old = ws;
        if (old != null) old.cancel();
        connected = false;
        peerOn = false;
        status();
        Request r = new Request.Builder()
                .url("https://" + HOST + "/?k=" + KEY + "&id=" + devId)
                .build();
        ws = client.newWebSocket(r, new Listener(g));
    }

    void lost(int g) {
        if (g != gen || destroyed) return;
        connected = false;
        peerOn = false;
        status();
        long d = Math.min(300L << Math.min(attempt, 4), 3000L); // 0.3 s, 0.6, 1.2, 2.4, 3 s ...
        attempt++;
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect, d);
    }

    void send(String s) {
        WebSocket w = ws;
        if (w != null) w.send(s);
    }

    class Listener extends WebSocketListener {
        final int g;

        Listener(int g) {
            this.g = g;
        }

        @Override
        public void onOpen(WebSocket w, Response r) {
            if (g != gen) return;
            connected = true;
            attempt = 0;
            lastRx = System.currentTimeMillis();
            w.send(listenOn ? "L1" : "L0"); // server always learns our current listen state
            status();
        }

        @Override
        public void onMessage(WebSocket w, String t) {
            if (g != gen) return;
            lastRx = System.currentTimeMillis();
            if (t.equals("peer:1")) {
                if (!peerOn) { peerOn = true; status(); }
            } else if (t.equals("peer:0")) {
                if (peerOn) { peerOn = false; status(); }
            }
        }

        @Override
        public void onMessage(WebSocket w, ByteString b) {
            if (g != gen) return;
            lastRx = System.currentTimeMillis();
            if (!listenOn) return;
            byte[] u = b.toByteArray();
            byte[] pcm = new byte[u.length * 2];
            for (int i = 0; i < u.length; i++) {
                short s = ULAW[u[i] & 0xFF];
                pcm[2 * i] = (byte) (s & 0xff);
                pcm[2 * i + 1] = (byte) ((s >> 8) & 0xff);
            }
            while (queue.size() > 25) queue.poll(); // after a stall, jump forward instead of lagging
            queue.offer(pcm);
        }

        @Override
        public void onClosing(WebSocket w, int code, String reason) {
            w.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket w, int code, String reason) {
            lost(g);
        }

        @Override
        public void onFailure(WebSocket w, Throwable t, Response r) {
            lost(g);
        }
    }

    // ---------- microphone: really open only while the mic button is ON ----------
    void startMic() {
        if (micRun) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micFailed();
            return;
        }
        Thread prev = micThread;
        if (prev != null && prev.isAlive()) {
            try { prev.join(400); } catch (InterruptedException e) { }
        }
        micRun = true;
        micThread = new Thread(this::micLoop, "voice-mic");
        micThread.start();
    }

    AudioRecord openRecord(int source, int size) {
        try {
            AudioRecord r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size);
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Exception e) { }
        return null;
    }

    void micLoop() {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min, FRAME * 2 * 8);
            rec = openRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, size);
            if (rec == null) rec = openRecord(MediaRecorder.AudioSource.MIC, size);
            if (rec == null) {
                micFailed();
                return;
            }
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                micFailed();
                return;
            }
            short[] buf = new short[FRAME];
            byte[] cur = new byte[FRAME];
            byte[][] pre = new byte[3][FRAME]; // last 60 ms before speech, so word starts are not clipped
            int preCount = 0, preIdx = 0, hang = 0;
            while (micRun) {
                int n = 0;
                while (n < FRAME && micRun) {
                    int r = rec.read(buf, n, FRAME - n);
                    if (r < 0) {
                        micFailed();
                        return;
                    }
                    if (r == 0) {
                        Thread.sleep(2);
                        continue;
                    }
                    n += r;
                }
                if (n < FRAME) break;

                long sum = 0;
                for (int i = 0; i < FRAME; i++) sum += (long) buf[i] * buf[i];
                double rms = Math.sqrt(sum / (double) FRAME);
                for (int i = 0; i < FRAME; i++) cur[i] = ulawEncode(buf[i]);

                boolean speech = rms > 120;
                if (speech || hang > 0) {
                    if (hang == 0) { // speech just started: send the cushion first
                        int start = (preIdx - preCount + 3) % 3;
                        for (int k = 0; k < preCount; k++) sendFrame(pre[(start + k) % 3]);
                        preCount = 0;
                    }
                    hang = speech ? 25 : hang - 1; // keep sending 500 ms after speech
                    sendFrame(cur);
                } else {
                    System.arraycopy(cur, 0, pre[preIdx], 0, FRAME);
                    preIdx = (preIdx + 1) % 3;
                    if (preCount < 3) preCount++;
                }
            }
        } catch (Exception e) {
            if (micRun) micFailed();
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception e) { }
                rec.release(); // this is what really closes the microphone
            }
            micRun = false;
        }
    }

    void sendFrame(byte[] f) {
        WebSocket w = ws;
        if (w != null && connected && w.queueSize() < 65536) w.send(ByteString.of(f, 0, FRAME));
    }

    void stopMic() {
        micRun = false;
        Thread t = micThread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(700); } catch (InterruptedException e) { }
        }
    }

    void micFailed() {
        micRun = false;
        main.post(() -> {
            micOn = false;
            refresh();
        });
    }

    // ---------- speaker: really open only while the listen button is ON ----------
    void startPlay() {
        if (playRun) return;
        Thread prev = playThread;
        if (prev != null && prev.isAlive()) {
            try { prev.join(400); } catch (InterruptedException e) { }
        }
        playRun = true;
        queue.clear();
        playThread = new Thread(this::playLoop, "voice-play");
        playThread.start();
    }

    AudioTrack newTrack() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, FRAME * 2 * 10))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    void playLoop() {
        while (playRun) {
            AudioTrack t = null;
            try {
                t = newTrack();
                t.play();
                boolean primed = false, bad = false;
                int waited = 0;
                while (playRun && !bad) {
                    if (!primed) { // small cushion (80 ms) before each burst so it doesn't stutter
                        if (queue.isEmpty()) waited = 0; else waited += 10;
                        if (queue.size() >= 4 || (waited >= 120 && !queue.isEmpty())) primed = true;
                        else {
                            Thread.sleep(10);
                            continue;
                        }
                    }
                    byte[] f = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (f == null) {
                        primed = false;
                        waited = 0;
                        continue;
                    }
                    if (t.write(f, 0, f.length) < 0) bad = true; // audio device changed: rebuild the track
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                try { Thread.sleep(300); } catch (InterruptedException ie) { break; }
            } finally {
                if (t != null) {
                    try { t.stop(); } catch (Exception e) { }
                    t.release();
                }
            }
        }
        playRun = false;
    }

    void stopPlay() {
        playRun = false;
        Thread t = playThread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException e) { }
        }
        queue.clear();
    }

    // ---------- buttons ----------
    void toggleMic() {
        if (micOn) {
            micOn = false;
            stopMic();            // AudioRecord is released before we return
        } else {
            if (!listenOn) {      // mic on always means listening on: never talk deaf
                listenOn = true;
                startPlay();
                send("L1");
            }
            micOn = true;
            startMic();
        }
        refresh();
    }

    void toggleListen() {
        if (listenOn) {
            listenOn = false;
            micOn = false;        // closing listen closes the mic completely
            stopMic();
            stopPlay();
            send("L0");
        } else {
            listenOn = true;
            startPlay();
            send("L1");
        }
        refresh();
    }

    void addButtons() {
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        spk = new IconView(this, false);
        mic = new IconView(this, true);
        box.addView(spk, btnLp());
        box.addView(mic, btnLp());
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

    LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.bottomMargin = dp(8);
        return lp;
    }

    void refresh() {
        if (spk == null || mic == null) return;
        int dot = !connected ? 0xFFFF5252 : (peerOn ? 0xFF69F0AE : 0xFFFFC107);
        spk.set(listenOn, dot);
        mic.set(micOn, 0);
    }

    int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density);
    }

    // round icon drawn in code (no image files): speaker (with a status dot) and microphone
    static class IconView extends View {
        final boolean isMic;
        boolean on;
        int dot;
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path body = new Path();
        final RectF rc = new RectF();

        IconView(Context c, boolean isMic) {
            super(c);
            this.isMic = isMic;
            fill.setColor(0xFFFFFFFF);
            line.setColor(0xFFFFFFFF);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2f);
            line.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(0xFF1E1E1E);
            body.moveTo(3, 9);
            body.lineTo(7, 9);
            body.lineTo(12, 4);
            body.lineTo(12, 20);
            body.lineTo(7, 15);
            body.lineTo(3, 15);
            body.close();
        }

        void set(boolean on, int dot) {
            if (this.on != on || this.dot != dot) {
                this.on = on;
                this.dot = dot;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            bg.setColor(on ? (isMic ? 0xE6D32F2F : 0xE62E7D32) : 0xCC4A4A4F);
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, bg);

            float s = Math.min(w, h) * 0.56f / 24f;
            c.save();
            c.translate((w - 24 * s) / 2f, (h - 24 * s) / 2f);
            c.scale(s, s);
            if (isMic) drawMic(c); else drawSpeaker(c);
            c.restore();

            if (dot != 0) {
                float r = w * 0.12f;
                float cx = w - r - w * 0.02f, cy = r + h * 0.02f;
                c.drawCircle(cx, cy, r + w * 0.035f, ring);
                dotP.setColor(dot);
                c.drawCircle(cx, cy, r, dotP);
            }
        }

        void drawSpeaker(Canvas c) {
            c.drawPath(body, fill);
            if (on) {
                rc.set(7, 7, 17, 17);
                c.drawArc(rc, -45, 90, false, line);
                rc.set(4, 4, 20, 20);
                c.drawArc(rc, -45, 90, false, line);
            } else {
                c.drawLine(15, 9, 21, 15, line);
                c.drawLine(21, 9, 15, 15, line);
            }
        }

        void drawMic(Canvas c) {
            rc.set(9, 2.5f, 15, 13.5f);
            c.drawRoundRect(rc, 3, 3, fill);
            rc.set(5.5f, 4.5f, 18.5f, 17.5f);
            c.drawArc(rc, 0, 180, false, line);
            c.drawLine(12, 17.5f, 12, 21, line);
            c.drawLine(8.5f, 21, 15.5f, 21, line);
            if (!on) c.drawLine(4, 3.5f, 20, 21, line);
        }
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
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        boxLp.x = Math.max(0, Math.min(sx + (int) mx, dm.widthPixels - box.getWidth()));
                        boxLp.y = Math.max(0, Math.min(sy + (int) my, dm.heightPixels - box.getHeight()));
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

    // ---------- G.711 mu-law (halves the data: 16 KB/s instead of 32 KB/s) ----------
    static byte ulawEncode(short pcm) {
        int sign = (pcm >> 8) & 0x80;
        int s = pcm;
        if (sign != 0) s = -s;
        if (s > 32635) s = 32635;
        s += 0x84;
        int exp = 7;
        for (int mask = 0x4000; (s & mask) == 0 && exp > 0; exp--, mask >>= 1) { }
        int mant = (s >> (exp + 3)) & 0x0F;
        return (byte) ~(sign | (exp << 4) | mant);
    }

    static short ulawDecode(byte u) {
        int v = ~u & 0xFF;
        int sign = v & 0x80;
        int exp = (v >> 4) & 7;
        int mant = v & 0x0F;
        int s = (((mant << 3) + 0x84) << exp) - 0x84;
        return (short) (sign != 0 ? -s : s);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        micOn = false;
        micRun = false;
        gen++;
        stopPlay();
        Thread mt = micThread;
        if (mt != null) {
            try { mt.join(700); } catch (InterruptedException e) { }
        }
        try { if (ws != null) ws.cancel(); } catch (Exception e) { }
        try {
            if (client != null) {
                client.dispatcher().executorService().shutdown();
                client.connectionPool().evictAll();
            }
        } catch (Exception e) { }
        try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); } catch (Exception e) { }
        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (Exception e) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception e) { }
        try { if (box != null) wm.removeView(box); } catch (Exception e) { }
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE); } catch (Exception e) { }
        try { nm.cancel(1); } catch (Exception e) { }
        super.onDestroy();
    }
}
