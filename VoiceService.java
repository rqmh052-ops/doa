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
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
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

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusBandwidth;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;

public class VoiceService extends Service {

    static final String HOST = "dody-production-18b9735d.up.railway.app";
    static final String KEY = "fu7777-k3x9q";
    static final int RATE = 16000;                 // 16 kHz mono 16-bit PCM
    static final int FRAME_MS = 40;
    static final int FRAME = RATE * FRAME_MS / 1000; // 640 samples = 40 ms = one Opus packet

    // ---- audio source ----
    static final int AUDIO_SRC = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    static final int AUDIO_SRC_FALLBACK = MediaRecorder.AudioSource.MIC;

    // ---- Opus encoder (tune here) ----
    static final int OPUS_BITRATE = 12000;         // bps; raise to 14000-16000 if speech sounds bad
    static final int COMPLEXITY = 5;               // 0-10; pure-Java codec, keep CPU low
    static final boolean USE_DTX = true;           // codec built-in DTX only; set false if quiet speech is clipped
    static final boolean USE_FEC = false;          // TCP never loses packets, FEC would waste bits
    static final int DTX_MAX_BYTES = 2;            // packet <= this = silence/DTX packet
    static final long DTX_MIN_INTERVAL_MS = 400;   // at most one silence packet per this time
    static final int MAX_PACKET = 400;             // encoder output buffer

    // ---- software AGC ----
    static final float AGC_TARGET_RMS = 3000f;     // about -20 dBFS
    static final float AGC_MAX_GAIN = 8f;          // +18 dB
    static final float AGC_MIN_GAIN = 1f;
    static final float AGC_ATTACK = 0.7f;          // gain falls: 70% of the way per frame (1-2 frames)
    static final float AGC_RELEASE_STEP = 1.0471f; // gain rises +0.4 dB per 40 ms frame = +1 dB / 100 ms
    static final float AGC_NOISE_FLOOR = 40f;      // frame RMS below this: hold gain (do not amplify hiss)
    static final int LIMIT = 30000;                // hard limiter

    // ---- playback / jitter buffer ----
    static final int JB_START_FRAMES = 2;          // start after 80 ms queued...
    static final int JB_START_MS = 150;            // ...or 150 ms after the first frame
    static final int JB_MAX_FRAMES = 8;            // 320 ms: above this drop oldest...
    static final int JB_CATCHUP_FRAMES = 4;        // ...down to this
    static final int MAX_PLC = 3;                  // conceal up to 3 missing packets, else reset decoder
    static final long GAP_RESET_MS = 1000;         // gap longer than this: reset decoder
    static final int TRACK_FRAMES = 6;             // AudioTrack buffer (>= 4 frames)

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
    volatile boolean decReset = false;  // set on reconnect: playback thread resets the Opus decoder
    String lastStatus = "";

    OkHttpClient client;
    volatile WebSocket ws;
    volatile int gen = 0;
    String devId;
    Thread micThread, playThread;
    final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

    AutomaticGainControl fxAgc;
    NoiseSuppressor fxNs;
    AcousticEchoCanceler fxAec;

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
            decReset = true;
            queue.clear();
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
            byte[] d = b.toByteArray();
            if (d.length < 1 || d.length > MAX_PACKET + 1) return;
            queue.offer(d);                          // decoding happens on the playback thread
            while (queue.size() > 40) queue.poll();  // safety only
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

    void attachEffects(int sid) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                fxAgc = AutomaticGainControl.create(sid);
                if (fxAgc != null && !fxAgc.getEnabled()) fxAgc.setEnabled(true);
            }
        } catch (Exception e) { }
        try {
            if (NoiseSuppressor.isAvailable()) {
                fxNs = NoiseSuppressor.create(sid);
                if (fxNs != null && !fxNs.getEnabled()) fxNs.setEnabled(true);
            }
        } catch (Exception e) { }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                fxAec = AcousticEchoCanceler.create(sid);
                if (fxAec != null && !fxAec.getEnabled()) fxAec.setEnabled(true);
            }
        } catch (Exception e) { }
    }

    void releaseEffects() {
        try { if (fxAgc != null) fxAgc.release(); } catch (Exception e) { }
        try { if (fxNs != null) fxNs.release(); } catch (Exception e) { }
        try { if (fxAec != null) fxAec.release(); } catch (Exception e) { }
        fxAgc = null;
        fxNs = null;
        fxAec = null;
    }

    OpusEncoder newEncoder() throws Exception {
        OpusEncoder e = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        e.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        e.setBitrate(OPUS_BITRATE);
        e.setUseVBR(true);
        e.setUseConstrainedVBR(false);
        e.setMaxBandwidth(OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND);
        e.setComplexity(COMPLEXITY);
        e.setUseDTX(USE_DTX);
        e.setUseInbandFEC(USE_FEC);
        return e;
    }

    // software AGC + hard limiter, in place. Returns the new gain.
    static float applyAgc(short[] b, float g) {
        long sum = 0;
        for (int i = 0; i < b.length; i++) sum += (long) b[i] * b[i];
        float rms = (float) Math.sqrt(sum / (double) b.length);
        if (rms >= AGC_NOISE_FLOOR) {
            float want = Math.max(AGC_MIN_GAIN, Math.min(AGC_MAX_GAIN, AGC_TARGET_RMS / rms));
            if (want < g) g = g + (want - g) * AGC_ATTACK;           // fast down
            else g = Math.min(want, g * AGC_RELEASE_STEP);            // slow up
        } // else: too quiet, hold gain
        for (int i = 0; i < b.length; i++) {
            int v = Math.round(b[i] * g);
            if (v > LIMIT) v = LIMIT; else if (v < -LIMIT) v = -LIMIT;
            b[i] = (short) v;
        }
        return g;
    }

    void micLoop() {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min, FRAME * 2 * 4);
            rec = openRecord(AUDIO_SRC, size);
            if (rec == null) rec = openRecord(AUDIO_SRC_FALLBACK, size);
            if (rec == null) {
                micFailed();
                return;
            }
            attachEffects(rec.getAudioSessionId());
            OpusEncoder enc = newEncoder();
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                micFailed();
                return;
            }
            short[] buf = new short[FRAME];
            byte[] out = new byte[MAX_PACKET];
            int seq = 0;
            float gain = 1f;
            long lastSmall = 0;
            long statStart = SystemClock.elapsedRealtime();
            long statBytes = 0, statPk = 0;
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

                gain = applyAgc(buf, gain);
                int len = enc.encode(buf, 0, FRAME, out, 0, out.length);
                if (len <= 0) continue;
                long now = SystemClock.elapsedRealtime();
                if (USE_DTX && len <= DTX_MAX_BYTES) { // silence packet: at most one per 400 ms
                    if (now - lastSmall < DTX_MIN_INTERVAL_MS) continue;
                    lastSmall = now;
                }
                if (sendPacket(seq, out, len)) {       // speech packets are always sent
                    seq = (seq + 1) & 0xFF;
                    statBytes += len + 1;
                    statPk++;
                }
                if (now - statStart >= 60000) {
                    Log.d("VoiceData", "last minute: " + statBytes + " B payload, " + statPk + " packets");
                    statStart = now;
                    statBytes = 0;
                    statPk = 0;
                }
            }
        } catch (Exception e) {
            if (micRun) micFailed();
        } finally {
            releaseEffects();
            if (rec != null) {
                try { rec.stop(); } catch (Exception e) { }
                rec.release(); // this is what really closes the microphone
            }
            micRun = false;
        }
    }

    // wire format: [1 byte seq][opus packet]
    boolean sendPacket(int seq, byte[] p, int len) {
        WebSocket w = ws;
        if (w == null || !connected || w.queueSize() >= 65536) return false;
        byte[] m = new byte[len + 1];
        m[0] = (byte) seq;
        System.arraycopy(p, 0, m, 1, len);
        return w.send(ByteString.of(m));
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
        decReset = true;
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
                .setBufferSizeInBytes(Math.max(min, FRAME * 2 * TRACK_FRAMES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    void playLoop() {
        OpusDecoder dec = null;
        short[] pcm = new short[FRAME];
        while (playRun) {
            AudioTrack t = null;
            try {
                if (dec == null) dec = new OpusDecoder(RATE, 1);
                dec.resetState();
                t = newTrack();
                t.play();
                boolean primed = false, bad = false;
                int waited = 0, expect = -1;
                long lastAt = 0;
                while (playRun && !bad) {
                    if (!primed) { // small cushion before each burst so it doesn't stutter
                        if (queue.isEmpty()) waited = 0; else waited += 10;
                        if (queue.size() >= JB_START_FRAMES || (waited >= JB_START_MS && !queue.isEmpty())) primed = true;
                        else {
                            Thread.sleep(10);
                            continue;
                        }
                    }
                    if (queue.size() > JB_MAX_FRAMES) { // catch-up: drop oldest
                        while (queue.size() > JB_CATCHUP_FRAMES) queue.poll();
                    }
                    byte[] f = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (f == null) {
                        primed = false;
                        waited = 0;
                        continue;
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (decReset || (lastAt != 0 && now - lastAt > GAP_RESET_MS)) {
                        decReset = false;
                        dec.resetState();
                        expect = -1;
                    }
                    lastAt = now;

                    int seq = f[0] & 0xFF;
                    if (expect >= 0) {
                        int miss = (seq - expect) & 0xFF;
                        if (miss >= 1 && miss <= MAX_PLC) {
                            for (int k = 0; k < miss && !bad; k++) { // conceal lost frames
                                try {
                                    dec.decode(null, 0, 0, pcm, 0, FRAME, false);
                                    if (t.write(pcm, 0, FRAME) < 0) bad = true;
                                } catch (Exception e) { }
                            }
                        } else if (miss != 0) {
                            dec.resetState(); // too many missing: do not conceal
                        }
                    }
                    expect = (seq + 1) & 0xFF;
                    if (bad) break;

                    int n;
                    try {
                        n = dec.decode(f, 1, f.length - 1, pcm, 0, FRAME, false);
                    } catch (Exception e) {
                        continue; // corrupt packet: skip
                    }
                    if (n > 0 && t.write(pcm, 0, n) < 0) bad = true; // audio device changed: rebuild the track
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
        dec = null; // pure Java: nothing else to release
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
