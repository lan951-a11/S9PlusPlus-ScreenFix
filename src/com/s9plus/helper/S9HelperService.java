package com.s9plus.helper;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.Display;

class ServiceEventReceiver extends BroadcastReceiver {
    private final S9HelperService svc;
    ServiceEventReceiver(S9HelperService svc) {
        this.svc = svc;
    }
    @Override
    public void onReceive(Context context, Intent intent) {
        svc.handleBroadcastIntent(intent);
    }
}

class AodActionRunnable implements Runnable {
    static final int ACT_START_CYCLE = 1;
    static final int ACT_WAKE_FROM_DOZE = 2;
    static final int ACT_TIMEOUT = 3;
    private final S9HelperService svc;
    private final int action;

    AodActionRunnable(S9HelperService svc, int action) {
        this.svc = svc;
        this.action = action;
    }

    @Override
    public void run() {
        svc.handleAodAction(action);
    }
}

class DirectDozeDoneRunnable implements Runnable {
    private final S9HelperService svc;
    private final long elapsedMs;

    DirectDozeDoneRunnable(S9HelperService svc, long elapsedMs) {
        this.svc = svc;
        this.elapsedMs = elapsedMs;
    }

    @Override
    public void run() {
        svc.onDirectDozeComplete(elapsedMs);
    }
}

class DirectDozeWorker implements Runnable {
    private final S9HelperService svc;
    private final boolean warmUpOnly;
    private final int holdMs;

    DirectDozeWorker(S9HelperService svc, boolean warmUpOnly, int holdMs) {
        this.svc = svc;
        this.warmUpOnly = warmUpOnly;
        this.holdMs = holdMs;
    }

    @Override
    public void run() {
        String apkPath = svc.getPackageCodePath();
        if (warmUpOnly) {
            boolean ok = RootShell.ensureDozeDaemon(apkPath);
            Log.i("S9Helper", "DirectDozeDaemon warm-up: " + ok);
            return;
        }
        long elapsed = RootShell.pulseHardwareDoze(apkPath, "DOZE_ALPM", holdMs);
        svc.postDirectDozeComplete(elapsed);
    }
}

class VuPollWorker implements Runnable {
    private final S9HelperService svc;
    private final int captureSize;

    VuPollWorker(S9HelperService svc, int captureSize) {
        this.svc = svc;
        this.captureSize = captureSize;
    }

    @Override
    public void run() {
        svc.runVuPollLoop(captureSize);
    }
}

class BrightnessObserver extends ContentObserver {
    private final S9HelperService svc;

    BrightnessObserver(Handler handler, S9HelperService svc) {
        super(handler);
        this.svc = svc;
    }

    @Override
    public void onChange(boolean selfChange) {
        svc.onSystemBrightnessChanged();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        svc.onSystemBrightnessChanged();
    }
}

class HbmReassertRunnable implements Runnable {
    private final S9HelperService svc;
    private final boolean watchdog;

    HbmReassertRunnable(S9HelperService svc, boolean watchdog) {
        this.svc = svc;
        this.watchdog = watchdog;
    }

    @Override
    public void run() {
        svc.onHbmReassertTick(watchdog);
    }
}

class HbmThermalRunnable implements Runnable {
    private final S9HelperService svc;

    HbmThermalRunnable(S9HelperService svc) {
        this.svc = svc;
    }

    @Override
    public void run() {
        svc.onThermalPollTick();
    }
}

class HbmCommandRunnable implements Runnable {
    static final int ACT_INIT_ROOT = 1;
    static final int ACT_REFRESH_HBM = 2;
    static final int ACT_SET_FORCE_HBM = 3;
    static final int ACT_TOGGLE_FORCE_HBM = 4;
    static final int ACT_ENABLE_SLIDER_UNLOCK = 5;
    static final int ACT_SIM_THERMAL = 6;
    static final int ACT_SIM_WATT = 7;
    static final int ACT_APPLY_SCREEN_TIMEOUT = 8;
    static final int ACT_DISPLAY_OFF_RESET = 9;
    static final int ACT_REFRESH_PREFS_LED = 10;
    static final int ACT_SCREEN_OFF_LED = 11;

    private final S9HelperService svc;
    private final int action;
    private final boolean flag;
    private final int value;

    HbmCommandRunnable(S9HelperService svc, int action, boolean flag, int value) {
        this.svc = svc;
        this.action = action;
        this.flag = flag;
        this.value = value;
    }

    @Override
    public void run() {
        svc.handleHbmBgCommand(action, flag, value);
    }
}

public class S9HelperService extends Service implements DisplayManager.DisplayListener {
    public static final String ACTION_STATE_UPDATED = "com.s9plus.helper.STATE_UPDATED";
    public static final String ACTION_REFRESH_MEDIA_SOURCE = "com.s9plus.helper.REFRESH_MEDIA_SOURCE";

    private static final int CYCLE_IDLE = 0;
    private static final int CYCLE_WAITING_DOZE = 1;
    private static final int CYCLE_HOLDING_DOZE = 2;
    private static final int CYCLE_WAKING_UP = 3;
    private static final int TIMEOUT_NEVER_MS = Integer.MAX_VALUE;

    public static volatile int currentColor = Color.rgb(70, 95, 235);
    public static volatile String currentTrackTitle = "Waiting for music...";
    public static volatile String activeMediaPackage = "";
    public static volatile Bitmap currentBlurredBg = null;
    public static volatile boolean isPlaying = false;
    public static volatile float currentVolumeLevel = 1.0f;
    public static volatile long lastFixDurationMs = -1L;
    public static volatile boolean hbmActiveNow = false;
    public static volatile int lastAppliedHwBright = -1;
    public static volatile int lastActualNits = -1;
    public static volatile int lastBattTemp10 = -1;
    public static volatile int lastApTemp10 = -1;
    public static volatile int lastThermalStage = 0;
    public static volatile int lastEffectiveCapNits = 720;
    public static volatile int lastPanelMw = -1;
    public static volatile boolean lastWattThrottling = false;
    public static volatile int lastWattCapNits = 720;

    private SharedPreferences prefs;
    private Visualizer visualizer;
    private final Object visLock = new Object();
    private volatile boolean vuRunning = false;
    private Thread vuThread = null;
    private DisplayManager displayManager;
    private Display defaultDisplay;
    private Handler mainHandler;
    private HandlerThread hbmThread;
    private Handler hbmBgHandler;
    private volatile int lastDisplayState = Display.STATE_UNKNOWN;
    private int aodCycleState = CYCLE_IDLE;
    private long lastCycleFinishUptime = 0L;
    private AodActionRunnable startCycleRunnable;
    private AodActionRunnable wakeFromDozeRunnable;
    private AodActionRunnable timeoutRunnable;
    private BrightnessObserver brightnessObserver;
    private HbmReassertRunnable hbmSettleRunnable;
    private HbmReassertRunnable hbmWatchdogRunnable;
    private HbmThermalRunnable thermalRunnable;
    private int debugSimTemp10 = -1;
    private int debugSimWattMw = -1;
    private boolean lastDisableAclApplied = false;
    private int wattCapNits = 720;
    private boolean lastWattAclForceOn = false;
    private long lastHwWriteUptime = 0L;
    private boolean thermalPollArmed = false;
    private ServiceEventReceiver receiver;

    // Smooth analog VU meter state
    private float vuRefDb = -14.0f;
    private long lastVuTimeMs = 0L;

    public static int nitsToHw(int nits) {
        if (nits <= 223) {
            return Math.max(8000, Math.round((Math.max(120, nits) / 223.0f) * 15000));
        }
        if (nits <= 286) {
            return 15000 + Math.round(((nits - 223) / 63.0f) * (19300 - 15000));
        }
        if (nits <= 400) {
            return 19300 + Math.round(((nits - 286) / 114.0f) * (RootShell.HW_NORMAL_MAX - 19300));
        }
        if (nits <= 700) {
            return RootShell.HW_NORMAL_MAX + Math.round(((nits - 400) / 300.0f) * (44700 - RootShell.HW_NORMAL_MAX));
        }
        return 44700 + Math.round(((Math.min(720, nits) - 700) / 20.0f) * (RootShell.HW_HBM_MAX - 44700));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("s9_prefs", MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        hbmThread = new HandlerThread("S9HbmWorker");
        hbmThread.start();
        hbmBgHandler = new Handler(hbmThread.getLooper());
        startCycleRunnable = new AodActionRunnable(this, AodActionRunnable.ACT_START_CYCLE);
        wakeFromDozeRunnable = new AodActionRunnable(this, AodActionRunnable.ACT_WAKE_FROM_DOZE);
        timeoutRunnable = new AodActionRunnable(this, AodActionRunnable.ACT_TIMEOUT);
        hbmSettleRunnable = new HbmReassertRunnable(this, false);
        hbmWatchdogRunnable = new HbmReassertRunnable(this, true);
        thermalRunnable = new HbmThermalRunnable(this);

        // Cancel any lingering foreground notification from previous version
        try {
            stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancelAll();
                nm.deleteNotificationChannel("s9_helper_fg");
            }
        } catch (Exception ignored) {}

        receiver = new ServiceEventReceiver(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction("com.maxmpz.audioplayer.TRACK_CHANGED");
        filter.addAction("com.maxmpz.audioplayer.AA_CHANGED");
        filter.addAction("com.maxmpz.audioplayer.STATUS_CHANGED");
        registerReceiver(receiver, filter);

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (defaultDisplay != null) {
                lastDisplayState = defaultDisplay.getState();
            }
            displayManager.registerDisplayListener(this, mainHandler);
        }

        brightnessObserver = new BrightnessObserver(hbmBgHandler, this);
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver);
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, brightnessObserver);
        } catch (Exception ignored) {}

        hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_INIT_ROOT, false, 0));
        new Thread(new DirectDozeWorker(this, true, 0)).start();

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null && am.isMusicActive()) {
            isPlaying = true;
            updateVisualizerState();
        }
    }

    private void setSystemIntFast(String key, int val) {
        try {
            Settings.System.putInt(getContentResolver(), key, val);
        } catch (Exception e) {
            RootShell.execFast("cmd settings put system " + key + " " + val + " >/dev/null 2>&1");
        }
    }

    private void applyScreenTimeoutPref() {
        boolean neverOff = prefs.getBoolean("no_screen_timeout", false);
        if (neverOff) {
            int cur = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600000);
            if (cur >= 15000 && cur <= 3600000) {
                prefs.edit().putInt("prev_screen_off_timeout", cur).apply();
            }
            setSystemIntFast(Settings.System.SCREEN_OFF_TIMEOUT, TIMEOUT_NEVER_MS);
            RootShell.execFast("cmd settings put global stay_on_while_plugged_in 7 >/dev/null 2>&1");
        } else {
            int prev = Math.max(15000, Math.min(600000, prefs.getInt("prev_screen_off_timeout", 600000)));
            setSystemIntFast(Settings.System.SCREEN_OFF_TIMEOUT, prev);
            RootShell.execFast("cmd settings put global stay_on_while_plugged_in 0 >/dev/null 2>&1");
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY || defaultDisplay == null) return;
        int newState = defaultDisplay.getState();
        if (newState == lastDisplayState) return;
        int prevState = lastDisplayState;
        lastDisplayState = newState;

        if (newState == Display.STATE_ON) {
            cancelHbmRunnables();
            if (hbmBgHandler != null) {
                hbmBgHandler.postDelayed(hbmSettleRunnable, 150L);
                hbmBgHandler.postDelayed(hbmWatchdogRunnable, 500L);
            }
        } else {
            cancelHbmRunnables();
            if (hbmBgHandler != null) {
                hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_DISPLAY_OFF_RESET, false, 0));
            }
        }

        if (aodCycleState == CYCLE_WAITING_DOZE
                && (newState == Display.STATE_DOZE || newState == Display.STATE_DOZE_SUSPEND)) {
            mainHandler.removeCallbacks(timeoutRunnable);
            aodCycleState = CYCLE_HOLDING_DOZE;
            int holdMs = Math.max(0, prefs.getInt("aod_precharge_ms", 50));
            // Hardware display_state: OFF -> DOZE takes ~203ms after STATE_DOZE is dispatched
            mainHandler.postDelayed(wakeFromDozeRunnable, 220L + holdMs);
            mainHandler.postDelayed(timeoutRunnable, 2500L);
        } else if (aodCycleState == CYCLE_WAKING_UP && newState == Display.STATE_ON) {
            mainHandler.removeCallbacks(timeoutRunnable);
            aodCycleState = CYCLE_IDLE;
            lastCycleFinishUptime = SystemClock.uptimeMillis();
            setSystemIntFast("aod_tap_to_show_mode", 1);
        } else if (aodCycleState == CYCLE_IDLE
                && prevState == Display.STATE_OFF
                && newState == Display.STATE_ON) {
            if (SystemClock.uptimeMillis() - lastCycleFinishUptime < 2500L) return;
            if (!prefs.getBoolean("aod_fix_enabled", true)) return;
            // 60ms lets the 53ms hardware OFF -> ON transition settle before sleeping into AOD
            mainHandler.removeCallbacks(startCycleRunnable);
            mainHandler.postDelayed(startCycleRunnable, 60L);
        } else if (aodCycleState == CYCLE_IDLE
                && (prevState == Display.STATE_DOZE || prevState == Display.STATE_DOZE_SUSPEND)
                && newState == Display.STATE_ON) {
            // Already woke from AOD -> colors are already full gamut; ensure tap-to-show is restored
            setSystemIntFast("aod_tap_to_show_mode", 1);
        }
    }

    void handleAodAction(int action) {
        if (action == AodActionRunnable.ACT_START_CYCLE) {
            if (aodCycleState == CYCLE_IDLE && lastDisplayState == Display.STATE_ON) {
                triggerAutomaticAodCycle();
            }
        } else if (action == AodActionRunnable.ACT_WAKE_FROM_DOZE) {
            if (aodCycleState == CYCLE_HOLDING_DOZE) {
                aodCycleState = CYCLE_WAKING_UP;
                RootShell.powerWake(SystemClock.uptimeMillis());
            }
        } else if (action == AodActionRunnable.ACT_TIMEOUT) {
            aodCycleState = CYCLE_IDLE;
            lastCycleFinishUptime = SystemClock.uptimeMillis();
            setSystemIntFast("aod_tap_to_show_mode", 1);
        }
    }

    void handleBroadcastIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            if (hbmBgHandler != null) {
                hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_SCREEN_OFF_LED, false, 0));
            }
        } else if (action.startsWith("com.maxmpz.audioplayer.")) {
            handlePowerampBroadcast(intent);
        }
    }

    public void triggerAutomaticAodCycle() {
        mainHandler.removeCallbacks(startCycleRunnable);
        mainHandler.removeCallbacks(wakeFromDozeRunnable);
        mainHandler.removeCallbacks(timeoutRunnable);

        if (prefs.getBoolean("aod_direct_doze", true)) {
            aodCycleState = CYCLE_HOLDING_DOZE;
            int holdMs = Math.max(0, prefs.getInt("aod_precharge_ms", 50));
            mainHandler.postDelayed(timeoutRunnable, 3500L);
            new Thread(new DirectDozeWorker(this, false, holdMs)).start();
            return;
        }
        triggerFullRealAodCycle();
    }

    private void triggerFullRealAodCycle() {
        aodCycleState = CYCLE_WAITING_DOZE;
        setSystemIntFast("aod_mode", 1);
        setSystemIntFast("aod_tap_to_show_mode", 0);
        RootShell.powerSleep(SystemClock.uptimeMillis());
        mainHandler.postDelayed(timeoutRunnable, 3500L);
    }

    void postDirectDozeComplete(long elapsedMs) {
        if (mainHandler != null) {
            mainHandler.post(new DirectDozeDoneRunnable(this, elapsedMs));
        }
    }

    void onDirectDozeComplete(long elapsedMs) {
        mainHandler.removeCallbacks(timeoutRunnable);
        if (elapsedMs >= 0L) {
            aodCycleState = CYCLE_IDLE;
            lastCycleFinishUptime = SystemClock.uptimeMillis();
            lastFixDurationMs = elapsedMs;
            Log.i("S9Helper", "DirectDoze pulse complete in " + elapsedMs + " ms");
            cancelHbmRunnables();
            if (hbmBgHandler != null) {
                hbmBgHandler.postDelayed(hbmSettleRunnable, 80L);
                hbmBgHandler.postDelayed(hbmWatchdogRunnable, 350L);
            }
            sendBroadcast(new Intent(ACTION_STATE_UPDATED));
        } else {
            Log.w("S9Helper", "DirectDoze failed; falling back to Full Real AOD Cycle");
            triggerFullRealAodCycle();
        }
    }

    private void handlePowerampBroadcast(Intent intent) {
        boolean powerampOnly = prefs.getBoolean("led_poweramp_only", false);
        boolean otherAppPlaying = !powerampOnly
                && isPlaying
                && !activeMediaPackage.isEmpty()
                && !"com.maxmpz.audioplayer".equals(activeMediaPackage);
        String action = intent.getAction();
        if ("com.maxmpz.audioplayer.TRACK_CHANGED".equals(action)) {
            if (otherAppPlaying) return;
            Bundle track = intent.getBundleExtra("track");
            if (track != null) {
                String title = track.getString("title", "Unknown Track");
                String artist = track.getString("artist", "");
                currentTrackTitle = artist.isEmpty() ? title : (artist + " - " + title);
            }
        } else if ("com.maxmpz.audioplayer.STATUS_CHANGED".equals(action)) {
            int state = intent.getIntExtra("state", -1);
            boolean paused = intent.getBooleanExtra("paused", false);
            boolean nowPlaying = (state == 1) && !paused;
            if (!nowPlaying && otherAppPlaying) {
                return;
            }
            isPlaying = nowPlaying;
            if (nowPlaying) {
                activeMediaPackage = "com.maxmpz.audioplayer";
            }
            updateVisualizerState();
            if (!isPlaying || !prefs.getBoolean("led_enabled", false)) {
                RootShell.turnOffLed();
            } else {
                RootShell.clearLedPattern();
                applyLedColor(currentColor, 1.0f);
            }
        } else if (otherAppPlaying) {
            return;
        }

        Bitmap bmp = null;
        try {
            bmp = intent.getParcelableExtra("aaBitmap");
            if (bmp == null) {
                String aaPath = intent.getStringExtra("aaPath");
                if (aaPath != null) {
                    bmp = BitmapFactory.decodeFile(aaPath);
                }
            }
        } catch (Exception ignored) {}

        if (bmp != null) {
            activeMediaPackage = "com.maxmpz.audioplayer";
            onNewAlbumArt(currentTrackTitle, bmp, isPlaying);
        } else {
            sendBroadcast(new Intent(ACTION_STATE_UPDATED));
        }
    }

    public void onNewAlbumArt(String title, Bitmap art, boolean playing) {
        if (title != null && !title.isEmpty()) {
            currentTrackTitle = title;
        }
        isPlaying = playing;
        if (art != null) {
            currentColor = AlbumColorExtractor.extractPowerampBlurColor(art);
            currentBlurredBg = null;
            vuRefDb = -14.0f;
            lastVuTimeMs = 0L;
        }
        updateVisualizerState();
        if (isPlaying && prefs.getBoolean("led_enabled", false)) {
            RootShell.clearLedPattern();
            applyLedColor(currentColor, 1.0f);
        } else {
            RootShell.turnOffLed();
        }
        sendBroadcast(new Intent(ACTION_STATE_UPDATED));
    }

    private void updateVisualizerState() {
        boolean wantPulse = prefs.getBoolean("volume_pulse_enabled", true) && prefs.getBoolean("led_enabled", false);
        if (isPlaying && wantPulse) {
            startAudioVisualizer();
        } else {
            stopAudioVisualizer();
        }
    }

    private void startAudioVisualizer() {
        synchronized (visLock) {
            if (visualizer != null && vuRunning) return;
            try {
                int captureSize = Visualizer.getCaptureSizeRange()[0];
                visualizer = new Visualizer(0);
                visualizer.setCaptureSize(captureSize);
                try {
                    visualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
                } catch (Exception ignored) {}
                visualizer.setEnabled(true);
                vuRunning = true;
                lastVuTimeMs = 0L;
                vuThread = new Thread(new VuPollWorker(this, captureSize), "S9VuPoll");
                vuThread.start();
            } catch (Exception e) {
                vuRunning = false;
                Log.w("S9Helper", "startAudioVisualizer failed", e);
                try {
                    if (visualizer != null) {
                        visualizer.release();
                    }
                } catch (Exception ignored) {}
                visualizer = null;
            }
        }
    }

    void runVuPollLoop(int captureSize) {
        byte[] buf = new byte[captureSize];
        while (vuRunning) {
            int hz = Math.max(2, Math.min(60, prefs.getInt("led_vu_hz", 20)));
            long periodMs = Math.max(16L, Math.round(1000.0 / hz));
            long loopStart = SystemClock.uptimeMillis();

            if (isPlaying && prefs.getBoolean("led_enabled", false)) {
                int res = -1;
                synchronized (visLock) {
                    if (visualizer != null && vuRunning) {
                        try {
                            res = visualizer.getWaveForm(buf);
                        } catch (Exception ignored) {
                            res = -1;
                        }
                    }
                }
                if (res == Visualizer.SUCCESS) {
                    processVuWaveform(buf);
                }
            }

            long elapsed = SystemClock.uptimeMillis() - loopStart;
            long sleepMs = periodMs - elapsed;
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    if (!vuRunning) break;
                }
            }
        }
    }

    private void processVuWaveform(byte[] waveform) {
        double sumSq = 0.0;
        for (int i = 0; i < waveform.length; i++) {
            float sample = ((waveform[i] & 0xFF) - 128) / 128.0f;
            sumSq += sample * sample;
        }
        float rms = (float) Math.sqrt(sumSq / Math.max(1, waveform.length));

        long now = SystemClock.uptimeMillis();
        float dtMs = (lastVuTimeMs == 0L) ? 50.0f : Math.max(10.0f, Math.min(1000.0f, (float) (now - lastVuTimeMs)));
        lastVuTimeMs = now;

        int reactPct = Math.max(50, Math.min(1000, prefs.getInt("led_reactivity_pct", 250)));
        float t = (reactPct - 50.0f) / 950.0f; // 0.0 at 50% .. 1.0 at 1000%

        boolean absMode = prefs.getBoolean("led_abs_loudness", false);
        float targetLevel;
        if (rms < 0.0035f) {
            targetLevel = 0.0f;
        } else if (absMode) {
            float db = (float) (20.0 * Math.log10(rms));
            float absTopDb = -5.0f;
            float absRangeDb = 34.0f - (t * 14.0f); // 34 dB @ 50% .. 20 dB @ 1000%
            float norm = Math.max(0.0f, Math.min(1.0f, (db - (absTopDb - absRangeDb)) / absRangeDb));
            float gamma = 1.35f + (t * 0.95f);
            targetLevel = (float) Math.pow(norm, gamma);
        } else {
            float db = (float) (20.0 * Math.log10(rms));
            // Frame-rate-independent auto-leveling ceiling so 2 Hz and 60 Hz match musically
            float refRiseAlpha = 1.0f - (float) Math.exp(-dtMs / 475.0f);
            float refFallTauMs = 8000.0f - (t * 6750.0f);
            float refFallAlpha = 1.0f - (float) Math.exp(-dtMs / refFallTauMs);
            vuRefDb += (db > vuRefDb) ? ((db - vuRefDb) * refRiseAlpha) : ((db - vuRefDb) * refFallAlpha);
            vuRefDb = Math.max(-22.0f, Math.min(-2.5f, vuRefDb));

            float rangeDb = Math.max(1.8f, 28.0f / (float) Math.pow(reactPct / 50.0f, 0.90));
            float norm = Math.max(0.0f, Math.min(1.0f, (db - (vuRefDb - rangeDb)) / rangeDb));

            float gamma = 1.6f + (t * 1.6f);
            float floor = Math.max(0.01f, 0.05f - (t * 0.04f));
            targetLevel = floor + (1.0f - floor) * (float) Math.pow(norm, gamma);
        }

        // Smooth analog VU needle ballistics
        float tauRise = 70.0f - (t * 30.0f);
        float tauFall = 220.0f - (t * 75.0f);
        float tauMs = (targetLevel > currentVolumeLevel) ? tauRise : tauFall;
        float alpha = 1.0f - (float) Math.exp(-dtMs / tauMs);
        currentVolumeLevel += (targetLevel - currentVolumeLevel) * alpha;
        if (targetLevel == 0.0f && currentVolumeLevel < 0.01f) {
            currentVolumeLevel = 0.0f;
        }

        applyLedColor(currentColor, currentVolumeLevel);
    }

    private void stopAudioVisualizer() {
        vuRunning = false;
        Thread t = vuThread;
        vuThread = null;
        if (t != null) {
            t.interrupt();
        }
        synchronized (visLock) {
            if (visualizer != null) {
                try {
                    visualizer.setEnabled(false);
                    visualizer.release();
                } catch (Exception ignored) {}
                visualizer = null;
            }
        }
    }

    public void applyLedColor(int color, float volumeMultiplier) {
        if (!prefs.getBoolean("led_enabled", false)) {
            RootShell.turnOffLed();
            return;
        }
        boolean absMode = prefs.getBoolean("volume_pulse_enabled", true)
                && prefs.getBoolean("led_abs_loudness", false);
        float totalMult;
        if (absMode) {
            int absMinPct = Math.max(0, Math.min(95, prefs.getInt("led_abs_min_pct", 0)));
            int absMaxPct = Math.max(5, Math.min(100, prefs.getInt("led_abs_max_pct", 100)));
            if (absMinPct > absMaxPct) absMinPct = absMaxPct;
            float minF = absMinPct / 100.0f;
            float maxF = absMaxPct / 100.0f;
            float clampedVol = Math.max(0.0f, Math.min(1.0f, volumeMultiplier));
            totalMult = Math.max(0.0f, Math.min(1.0f, minF + clampedVol * (maxF - minF)));
        } else {
            int userMaxPct = prefs.getInt("led_brightness_pct", 100);
            totalMult = Math.max(0.0f, Math.min(1.0f, volumeMultiplier * (userMaxPct / 100.0f)));
        }

        int r = Math.max(0, Math.min(255, Math.round(Color.red(color) * totalMult)));
        int g = Math.max(0, Math.min(255, Math.round(Color.green(color) * totalMult)));
        int b = Math.max(0, Math.min(255, Math.round(Color.blue(color) * totalMult)));

        RootShell.setS9RgbLed(r, g, b);
    }

    private boolean lastThermalAclForceOn = false;

    private void resetWattGovernorState(int userCapNits) {
        wattCapNits = userCapNits;
        lastWattCapNits = userCapNits;
        lastWattThrottling = false;
        lastWattAclForceOn = false;
    }

    private void cancelHbmRunnables() {
        thermalPollArmed = false;
        if (hbmBgHandler != null) {
            if (hbmSettleRunnable != null) hbmBgHandler.removeCallbacks(hbmSettleRunnable);
            if (hbmWatchdogRunnable != null) hbmBgHandler.removeCallbacks(hbmWatchdogRunnable);
            if (thermalRunnable != null) hbmBgHandler.removeCallbacks(thermalRunnable);
        }
    }

    private void scheduleHbmSettles(boolean includeWatchdog) {
        if (hbmBgHandler == null) return;
        if (hbmSettleRunnable != null) hbmBgHandler.removeCallbacks(hbmSettleRunnable);
        if (hbmWatchdogRunnable != null) hbmBgHandler.removeCallbacks(hbmWatchdogRunnable);
        hbmBgHandler.postDelayed(hbmSettleRunnable, 120L);
        hbmBgHandler.postDelayed(hbmWatchdogRunnable, 350L);
        hbmBgHandler.postDelayed(hbmSettleRunnable, 700L);
        hbmBgHandler.postDelayed(hbmSettleRunnable, 1450L);
        if (includeWatchdog) {
            hbmBgHandler.postDelayed(hbmWatchdogRunnable, 1500L);
        }
    }

    private void scheduleThermalPollIfNeeded(boolean rescheduleIfArmed, boolean fastFirst) {
        if (hbmBgHandler == null || thermalRunnable == null) return;
        boolean thermOn = prefs.getBoolean("hbm_thermal_enabled", true);
        boolean wattOn = prefs.getBoolean("hbm_watt_enabled", true);
        boolean forceMax = prefs.getBoolean("hbm_force_max", false);
        if (lastDisplayState != Display.STATE_ON || (!thermOn && !wattOn)
                || (!forceMax && !hbmActiveNow && lastThermalStage == 0 && !lastWattThrottling)) {
            hbmBgHandler.removeCallbacks(thermalRunnable);
            thermalPollArmed = false;
            return;
        }
        if (!rescheduleIfArmed && thermalPollArmed) {
            return;
        }
        hbmBgHandler.removeCallbacks(thermalRunnable);
        long delayMs = wattOn ? (fastFirst ? 1000L : 1500L) : 3000L;
        thermalPollArmed = true;
        hbmBgHandler.postDelayed(thermalRunnable, delayMs);
    }

    private void requestHbmTileRefresh() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, HbmTileService.class));
        } catch (Exception ignored) {}
    }

    private int computeThermalEffectiveCap(int userCapNits, int batt10, int ap10) {
        if (!prefs.getBoolean("hbm_thermal_enabled", true)) {
            lastThermalStage = 0;
            lastThermalAclForceOn = false;
            return userCapNits;
        }

        int eff10;
        if (debugSimTemp10 >= 0) {
            eff10 = debugSimTemp10;
        } else {
            int b = batt10;
            if (b < 0) {
                try {
                    Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    b = (bat != null) ? bat.getIntExtra("temperature", 250) : 250;
                } catch (Exception e) {
                    b = 250;
                }
            }
            eff10 = (ap10 > 0) ? Math.max(b, ap10 - 40) : b;
        }

        int limit10 = Math.max(35, Math.min(48, prefs.getInt("hbm_thermal_limit_c", 42))) * 10;
        int prevStage = lastThermalStage;
        int stage;
        if (eff10 >= limit10 + 30 || (prevStage == 3 && eff10 > limit10 + 10)) {
            stage = 3;
        } else if (eff10 >= limit10 || (prevStage >= 2 && eff10 > limit10 - 20)) {
            stage = 2;
        } else if (eff10 >= limit10 - 30 || (prevStage >= 1 && eff10 > limit10 - 40)) {
            stage = 1;
        } else {
            stage = 0;
        }

        int effCap;
        if (stage == 3) {
            effCap = Math.min(userCapNits, 300);
        } else if (stage == 2) {
            effCap = Math.min(userCapNits, 400);
        } else if (stage == 1) {
            float frac = Math.max(0.0f, Math.min(1.0f, (limit10 - eff10) / 30.0f));
            effCap = 400 + Math.round(frac * Math.max(0, userCapNits - 400));
        } else {
            effCap = userCapNits;
        }

        lastThermalStage = stage;
        lastThermalAclForceOn = (stage >= 2) || (stage == 1 && eff10 >= limit10 - 20);
        return effCap;
    }

    private int computeWattEffectiveCap(int userCapNits, int actualNits, int panelMw, boolean updateGovernor) {
        if (!prefs.getBoolean("hbm_watt_enabled", true)) {
            resetWattGovernorState(userCapNits);
            return userCapNits;
        }

        wattCapNits = Math.max(220, Math.min(wattCapNits, userCapNits));
        int limitMw = Math.max(1000, Math.min(4500, prefs.getInt("hbm_watt_limit_mw", 2500)));
        int effMw = (debugSimWattMw >= 0) ? debugSimWattMw : panelMw;

        if (updateGovernor && effMw > 0 && (debugSimWattMw >= 0 || (SystemClock.uptimeMillis() - lastHwWriteUptime >= 850L))) {
            int curNits = (actualNits > 80) ? Math.min(720, actualNits) : Math.min(userCapNits, wattCapNits);
            if (effMw > limitMw) {
                lastWattAclForceOn = true;
                float ratio = (limitMw * 0.94f) / (float) effMw;
                int target = Math.round(curNits * (float) Math.pow(ratio, 0.82));
                if (target >= wattCapNits) {
                    target = wattCapNits - 15;
                }
                wattCapNits = Math.max(220, Math.min(userCapNits, target));
                lastWattThrottling = (wattCapNits < userCapNits);
            } else if (effMw >= Math.round(limitMw * 0.78f)) {
                if (effMw >= Math.round(limitMw * 0.85f)) {
                    lastWattAclForceOn = true;
                }
                lastWattThrottling = (wattCapNits < userCapNits);
            } else {
                if (wattCapNits < userCapNits) {
                    float headroom = (limitMw * 0.88f) / Math.max(250.0f, (float) effMw);
                    int safeTarget = Math.round(curNits * (float) Math.pow(headroom, 0.70));
                    int nextCap = Math.min(userCapNits, Math.min(safeTarget, wattCapNits + 90));
                    if (nextCap <= wattCapNits) {
                        nextCap = Math.min(userCapNits, wattCapNits + 15);
                    }
                    if (nextCap >= userCapNits - 15) {
                        nextCap = userCapNits;
                    }
                    wattCapNits = Math.max(220, nextCap);
                }
                lastWattThrottling = (wattCapNits < userCapNits);
                if (!lastWattThrottling && effMw <= Math.round(limitMw * 0.72f)) {
                    lastWattAclForceOn = false;
                }
            }
        }

        lastWattCapNits = wattCapNits;
        return wattCapNits;
    }

    void onSystemBrightnessChanged() {
        applyCurrentHbmState(true, true, false);
    }

    void onThermalPollTick() {
        thermalPollArmed = false;
        if (lastDisplayState != Display.STATE_ON) return;
        boolean thermOn = prefs.getBoolean("hbm_thermal_enabled", true);
        boolean wattOn = prefs.getBoolean("hbm_watt_enabled", true);
        if (!thermOn && !wattOn) return;
        boolean forceMax = prefs.getBoolean("hbm_force_max", false);
        if (!forceMax && !hbmActiveNow && lastThermalStage == 0 && !lastWattThrottling) return;

        int prevStage = lastThermalStage;
        int prevCap = lastEffectiveCapNits;
        boolean prevWatt = lastWattThrottling;
        applyCurrentHbmState(false, false, true);
        if (lastThermalStage != prevStage || lastEffectiveCapNits != prevCap || lastWattThrottling != prevWatt) {
            sendBroadcast(new Intent(ACTION_STATE_UPDATED));
            requestHbmTileRefresh();
        }
        scheduleThermalPollIfNeeded(true, false);
    }

    void onHbmReassertTick(boolean watchdog) {
        if (lastDisplayState != Display.STATE_ON) return;
        boolean forceMax = prefs.getBoolean("hbm_force_max", false);
        if (watchdog && forceMax) {
            int targetNits = Math.max(200, Math.min(720, lastEffectiveCapNits));
            int actual = RootShell.readPanelActualNits();
            if (actual >= 0) lastActualNits = actual;
            if (actual >= 0 && actual < targetNits - 25) {
                applyCurrentHbmState(false, true, false);
            } else {
                sendBroadcast(new Intent(ACTION_STATE_UPDATED));
            }
            if (hbmBgHandler != null) {
                hbmBgHandler.removeCallbacks(hbmWatchdogRunnable);
                hbmBgHandler.postDelayed(hbmWatchdogRunnable, 1500L);
            }
            return;
        }
        applyCurrentHbmState(false, watchdog, false);
    }

    void applyCurrentHbmState(boolean scheduleSettle, boolean broadcast) {
        applyCurrentHbmState(scheduleSettle, broadcast, false);
    }

    void applyCurrentHbmState(boolean scheduleSettle, boolean broadcast, boolean allowWattStep) {
        if (lastDisplayState != Display.STATE_ON) return;

        int prevStage = lastThermalStage;
        boolean prevWatt = lastWattThrottling;
        int[] tele = RootShell.readPanelAndThermal();
        if (tele[0] >= 0) lastActualNits = tele[0];
        if (tele[1] >= 0) lastApTemp10 = tele[1];
        if (tele[2] >= 0) lastBattTemp10 = tele[2];
        if (tele[3] >= 0) lastPanelMw = tele[3];

        boolean forceMax = prefs.getBoolean("hbm_force_max", false);
        boolean sliderUnlock = prefs.getBoolean("hbm_slider_unlock", false);
        boolean disableAcl = prefs.getBoolean("hbm_disable_acl", false);
        int userCapNits = Math.max(400, Math.min(720, prefs.getInt("hbm_cap_nits", 720)));

        int thermalCapNits = computeThermalEffectiveCap(userCapNits, lastBattTemp10, lastApTemp10);
        int wattCap = computeWattEffectiveCap(userCapNits, lastActualNits, lastPanelMw, allowWattStep);
        int effCapNits = Math.min(thermalCapNits, wattCap);
        lastEffectiveCapNits = effCapNits;
        int capHw = nitsToHw(effCapNits);
        boolean allowAclOff = disableAcl && !lastThermalAclForceOn && !lastWattAclForceOn;

        if (forceMax) {
            boolean wantAclOff = (capHw > RootShell.HW_NORMAL_MAX) && allowAclOff;
            if (capHw != lastAppliedHwBright || wantAclOff != lastDisableAclApplied) {
                lastHwWriteUptime = SystemClock.uptimeMillis();
            }
            RootShell.setPanelHbmState(capHw, wantAclOff);
            hbmActiveNow = capHw > RootShell.HW_NORMAL_MAX;
            lastAppliedHwBright = capHw;
            lastDisableAclApplied = wantAclOff;
            if (scheduleSettle) {
                scheduleHbmSettles(true);
            }
        } else if (sliderUnlock) {
            int mode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            int b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
            if (mode == 0 && b > 180 && (capHw > RootShell.HW_NORMAL_MAX || hbmActiveNow || lastThermalStage >= 2 || lastWattThrottling || prevWatt || lastDisableAclApplied)) {
                int uncappedHw = 18000 + Math.round(((b - 180) / 75.0f) * (nitsToHw(userCapNits) - 18000));
                int hw = (lastThermalStage == 3 || lastWattThrottling)
                        ? Math.min(uncappedHw, capHw)
                        : (18000 + Math.round(((b - 180) / 75.0f) * (capHw - 18000)));
                boolean inHbm = hw > RootShell.HW_NORMAL_MAX;
                boolean wantAclOff = inHbm && allowAclOff;
                if (hw != lastAppliedHwBright || wantAclOff != lastDisableAclApplied) {
                    lastHwWriteUptime = SystemClock.uptimeMillis();
                }
                RootShell.setPanelHbmState(hw, wantAclOff);
                hbmActiveNow = inHbm;
                lastAppliedHwBright = hw;
                lastDisableAclApplied = wantAclOff;
                if (scheduleSettle) {
                    scheduleHbmSettles(false);
                }
            } else {
                if (lastDisableAclApplied) {
                    RootShell.setPanelAcl(false);
                    lastDisableAclApplied = false;
                }
                if (hbmActiveNow && mode == 0 && b <= 180) {
                    cancelHbmRunnables();
                }
                resetWattGovernorState(userCapNits);
                hbmActiveNow = false;
                lastAppliedHwBright = -1;
            }
        } else {
            cancelHbmRunnables();
            if (hbmActiveNow || lastDisableAclApplied || lastWattThrottling) {
                int mode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                int b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
                if (mode == 0) {
                    RootShell.setPanelHbmState(b * 100, false);
                } else {
                    RootShell.setPanelAcl(false);
                }
                lastDisableAclApplied = false;
            }
            resetWattGovernorState(userCapNits);
            hbmActiveNow = false;
            lastAppliedHwBright = -1;
        }

        if (lastThermalStage != prevStage || lastWattThrottling != prevWatt) {
            requestHbmTileRefresh();
        }
        scheduleThermalPollIfNeeded(scheduleSettle, scheduleSettle);

        if (broadcast) {
            sendBroadcast(new Intent(ACTION_STATE_UPDATED));
        }
    }

    private void setForceHbmEnabled(boolean enable) {
        boolean currentlyForced = prefs.getBoolean("hbm_force_max", false);
        int userCapNits = Math.max(400, Math.min(720, prefs.getInt("hbm_cap_nits", 720)));
        if (enable && !currentlyForced) {
            int mode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            int b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 150);
            prefs.edit()
                    .putInt("hbm_prev_mode", mode)
                    .putInt("hbm_prev_bright", b)
                    .putBoolean("hbm_force_max", true)
                    .apply();
            setSystemIntFast(Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            setSystemIntFast(Settings.System.SCREEN_BRIGHTNESS, 255);
            applyCurrentHbmState(true, true, false);
            requestHbmTileRefresh();
        } else if (!enable && currentlyForced) {
            int prevMode = prefs.getInt("hbm_prev_mode", 0);
            int prevBright = Math.max(10, Math.min(255, prefs.getInt("hbm_prev_bright", 150)));
            prefs.edit().putBoolean("hbm_force_max", false).apply();
            cancelHbmRunnables();
            resetWattGovernorState(userCapNits);
            RootShell.setPanelAcl(false);
            lastDisableAclApplied = false;
            setSystemIntFast(Settings.System.SCREEN_BRIGHTNESS, prevBright);
            setSystemIntFast(Settings.System.SCREEN_BRIGHTNESS_MODE, prevMode);
            if (prevMode == 0) {
                boolean sliderUnlock = prefs.getBoolean("hbm_slider_unlock", false);
                if (!sliderUnlock || prevBright <= 180) {
                    RootShell.setPanelHbmState(prevBright * 100, false);
                    hbmActiveNow = false;
                    lastAppliedHwBright = -1;
                } else {
                    applyCurrentHbmState(true, false, false);
                }
            } else {
                hbmActiveNow = false;
                lastAppliedHwBright = -1;
            }
            sendBroadcast(new Intent(ACTION_STATE_UPDATED));
            requestHbmTileRefresh();
        } else {
            applyCurrentHbmState(true, true, false);
            requestHbmTileRefresh();
        }
    }

    void handleHbmBgCommand(int action, boolean flag, int value) {
        if (action == HbmCommandRunnable.ACT_INIT_ROOT) {
            RootShell.ensureRoot();
            // Whitelist from Doze and grant WRITE_SETTINGS for sub-millisecond AOD & brightness updates
            RootShell.execFast("dumpsys deviceidle whitelist +com.s9plus.helper 2>/dev/null");
            RootShell.execFast("cmd appops set com.s9plus.helper WRITE_SETTINGS allow 2>/dev/null");
            if (prefs.getBoolean("no_screen_timeout", false)) {
                applyScreenTimeoutPref();
            }
            if (lastDisplayState == Display.STATE_ON) {
                applyCurrentHbmState(true, false);
            }
        } else if (action == HbmCommandRunnable.ACT_REFRESH_HBM) {
            if (hbmBgHandler != null) {
                if (hbmSettleRunnable != null) hbmBgHandler.removeCallbacks(hbmSettleRunnable);
                if (hbmWatchdogRunnable != null) hbmBgHandler.removeCallbacks(hbmWatchdogRunnable);
            }
            if (!prefs.getBoolean("hbm_watt_enabled", true)) {
                resetWattGovernorState(Math.max(400, Math.min(720, prefs.getInt("hbm_cap_nits", 720))));
            }
            applyCurrentHbmState(true, true, true);
        } else if (action == HbmCommandRunnable.ACT_SET_FORCE_HBM) {
            setForceHbmEnabled(flag);
        } else if (action == HbmCommandRunnable.ACT_TOGGLE_FORCE_HBM) {
            boolean next = !prefs.getBoolean("hbm_force_max", false);
            setForceHbmEnabled(next);
        } else if (action == HbmCommandRunnable.ACT_ENABLE_SLIDER_UNLOCK) {
            int mode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            if (mode == 1) {
                setSystemIntFast(Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            }
            applyCurrentHbmState(true, true, false);
        } else if (action == HbmCommandRunnable.ACT_SIM_THERMAL) {
            debugSimTemp10 = value;
            applyCurrentHbmState(false, true, false);
            requestHbmTileRefresh();
        } else if (action == HbmCommandRunnable.ACT_SIM_WATT) {
            debugSimWattMw = value;
            applyCurrentHbmState(false, true, true);
            requestHbmTileRefresh();
        } else if (action == HbmCommandRunnable.ACT_APPLY_SCREEN_TIMEOUT) {
            applyScreenTimeoutPref();
        } else if (action == HbmCommandRunnable.ACT_DISPLAY_OFF_RESET) {
            int uCap = (prefs != null) ? Math.max(400, Math.min(720, prefs.getInt("hbm_cap_nits", 720))) : 720;
            resetWattGovernorState(uCap);
        } else if (action == HbmCommandRunnable.ACT_REFRESH_PREFS_LED) {
            if (isPlaying && prefs.getBoolean("led_enabled", false)) {
                RootShell.clearLedPattern();
                applyLedColor(currentColor, 1.0f);
            } else {
                RootShell.turnOffLed();
            }
        } else if (action == HbmCommandRunnable.ACT_SCREEN_OFF_LED) {
            if (isPlaying && prefs.getBoolean("led_enabled", false)) {
                RootShell.clearLedPattern();
                applyLedColor(currentColor, 1.0f);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String act = intent.getAction();
            if ("REFRESH_PREFS".equals(act)) {
                updateVisualizerState();
                if (hbmBgHandler != null) {
                    hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_REFRESH_PREFS_LED, false, 0));
                }
            } else if ("TEST_AOD_CYCLE".equals(act)) {
                triggerAutomaticAodCycle();
            } else if ("REFRESH_HBM".equals(act)) {
                if (hbmBgHandler != null) {
                    hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_REFRESH_HBM, false, 0));
                }
            } else if ("SET_FORCE_HBM".equals(act)) {
                boolean en = intent.getBooleanExtra("enabled", false);
                if (hbmBgHandler != null) {
                    hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_SET_FORCE_HBM, en, 0));
                }
            } else if ("TOGGLE_FORCE_HBM".equals(act)) {
                if (hbmBgHandler != null) {
                    hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_TOGGLE_FORCE_HBM, false, 0));
                }
            } else if ("ENABLE_SLIDER_UNLOCK".equals(act)) {
                if (hbmBgHandler != null) {
                    hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_ENABLE_SLIDER_UNLOCK, false, 0));
                }
            } else if ("SIM_THERMAL".equals(act)) {
                int temp10 = intent.getIntExtra("temp10", -1);
                if (hbmBgHandler != null) {
                    hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_SIM_THERMAL, false, temp10));
                }
            } else if ("SIM_WATT".equals(act)) {
                int mw = intent.getIntExtra("mw", -1);
                if (hbmBgHandler != null) {
                    hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_SIM_WATT, false, mw));
                }
            } else if ("APPLY_SCREEN_TIMEOUT".equals(act)) {
                if (hbmBgHandler != null) {
                    hbmBgHandler.post(new HbmCommandRunnable(this, HbmCommandRunnable.ACT_APPLY_SCREEN_TIMEOUT, false, 0));
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAudioVisualizer();
        cancelHbmRunnables();
        if (brightnessObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(brightnessObserver);
            } catch (Exception ignored) {}
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (hbmBgHandler != null) {
            hbmBgHandler.removeCallbacksAndMessages(null);
        }
        if (hbmThread != null) {
            hbmThread.quitSafely();
            hbmThread = null;
        }
        RootShell.stopDozeDaemon();
        RootShell.stopQueryShell();
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(this);
            } catch (Exception ignored) {}
        }
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
