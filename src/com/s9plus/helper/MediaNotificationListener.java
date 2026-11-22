package com.s9plus.helper;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

class MediaSourceRefreshReceiver extends BroadcastReceiver {
    private final MediaNotificationListener listener;

    MediaSourceRefreshReceiver(MediaNotificationListener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        listener.refreshMediaSessions();
    }
}

class ActiveSessionsObserver implements MediaSessionManager.OnActiveSessionsChangedListener {
    private final MediaNotificationListener listener;

    ActiveSessionsObserver(MediaNotificationListener listener) {
        this.listener = listener;
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        listener.onActiveSessionsUpdated(controllers);
    }
}

class MediaCtrlCallback extends MediaController.Callback {
    private final MediaNotificationListener listener;
    private final MediaController controller;

    MediaCtrlCallback(MediaNotificationListener listener, MediaController controller) {
        this.listener = listener;
        this.controller = controller;
    }

    @Override
    public void onPlaybackStateChanged(PlaybackState state) {
        listener.onControllerUpdated(controller);
    }

    @Override
    public void onMetadataChanged(MediaMetadata metadata) {
        listener.onControllerUpdated(controller);
    }

    @Override
    public void onSessionDestroyed() {
        listener.refreshMediaSessions();
    }
}

public class MediaNotificationListener extends NotificationListenerService {
    private Handler mainHandler;
    private MediaSessionManager sessionManager;
    private ComponentName listenerComponent;
    private ActiveSessionsObserver sessionsObserver;
    private MediaSourceRefreshReceiver sourceReceiver;
    private boolean sessionsRegistered = false;
    private boolean receiverRegistered = false;
    private final ArrayList<MediaController> trackedControllers = new ArrayList<MediaController>();
    private final ArrayList<MediaCtrlCallback> trackedCallbacks = new ArrayList<MediaCtrlCallback>();

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        if (listenerComponent == null) {
            listenerComponent = new ComponentName(this, MediaNotificationListener.class);
        }
        if (sourceReceiver == null) {
            sourceReceiver = new MediaSourceRefreshReceiver(this);
        }
        if (!receiverRegistered) {
            try {
                registerReceiver(sourceReceiver, new IntentFilter(S9HelperService.ACTION_REFRESH_MEDIA_SOURCE));
                receiverRegistered = true;
            } catch (Exception e) {
                Log.w("S9Helper", "Media source receiver register failed", e);
            }
        }
        if (sessionManager == null) {
            sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        }
        if (sessionsObserver == null) {
            sessionsObserver = new ActiveSessionsObserver(this);
        }
        if (sessionManager != null && !sessionsRegistered) {
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsObserver, listenerComponent, mainHandler);
                sessionsRegistered = true;
            } catch (Exception e) {
                Log.w("S9Helper", "addOnActiveSessionsChangedListener failed", e);
            }
        }
        try {
            startService(new Intent(this, S9HelperService.class));
        } catch (Exception ignored) {}
        refreshMediaSessions();
    }

    @Override
    public void onListenerDisconnected() {
        cleanupListeners();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        cleanupListeners();
        super.onDestroy();
    }

    private void cleanupListeners() {
        clearControllerCallbacks();
        if (sessionManager != null && sessionsObserver != null && sessionsRegistered) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsObserver);
            } catch (Exception ignored) {}
            sessionsRegistered = false;
        }
        if (sourceReceiver != null && receiverRegistered) {
            try {
                unregisterReceiver(sourceReceiver);
            } catch (Exception ignored) {}
            receiverRegistered = false;
        }
    }

    private void clearControllerCallbacks() {
        int count = Math.min(trackedControllers.size(), trackedCallbacks.size());
        for (int i = 0; i < count; i++) {
            try {
                trackedControllers.get(i).unregisterCallback(trackedCallbacks.get(i));
            } catch (Exception ignored) {}
        }
        trackedControllers.clear();
        trackedCallbacks.clear();
    }

    private boolean isPackageAllowed(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        boolean powerampOnly = getSharedPreferences("s9_prefs", MODE_PRIVATE)
                .getBoolean("led_poweramp_only", false);
        if (powerampOnly) {
            return "com.maxmpz.audioplayer".equals(pkg);
        }
        return true;
    }

    private static boolean isControllerActiveState(PlaybackState state) {
        if (state == null) return false;
        int st = state.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
    }

    void refreshMediaSessions() {
        try {
            if (sessionManager == null) {
                sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            }
            if (listenerComponent == null) {
                listenerComponent = new ComponentName(this, MediaNotificationListener.class);
            }
            if (sessionManager != null) {
                List<MediaController> controllers = sessionManager.getActiveSessions(listenerComponent);
                onActiveSessionsUpdated(controllers);
            }
        } catch (Exception e) {
            Log.w("S9Helper", "refreshMediaSessions failed", e);
        }
    }

    void onActiveSessionsUpdated(List<MediaController> controllers) {
        clearControllerCallbacks();
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        if (controllers != null) {
            for (MediaController mc : controllers) {
                if (mc == null) continue;
                MediaCtrlCallback cb = new MediaCtrlCallback(this, mc);
                try {
                    mc.registerCallback(cb, mainHandler);
                    trackedControllers.add(mc);
                    trackedCallbacks.add(cb);
                } catch (Exception ignored) {}
            }
        }
        selectAndApplyBestController(controllers, null, null, null);
    }

    void onControllerUpdated(MediaController changedMc) {
        List<MediaController> current = null;
        try {
            if (sessionManager != null && listenerComponent != null) {
                current = sessionManager.getActiveSessions(listenerComponent);
            }
        } catch (Exception ignored) {}
        if (current == null || current.isEmpty()) {
            current = trackedControllers;
        }
        selectAndApplyBestController(current, null, null, changedMc);
    }

    private void selectAndApplyBestController(
            List<MediaController> controllers,
            Bitmap fallbackArt,
            String fallbackPkg,
            MediaController preferredMc) {
        MediaController bestPlaying = null;
        MediaController firstAllowed = null;

        if (controllers != null) {
            for (MediaController mc : controllers) {
                if (mc == null) continue;
                String pkg = mc.getPackageName();
                if (!isPackageAllowed(pkg)) continue;
                if (firstAllowed == null || sameController(mc, preferredMc)) {
                    firstAllowed = mc;
                }
                if (isControllerActiveState(mc.getPlaybackState())) {
                    if (bestPlaying == null || sameController(mc, preferredMc)) {
                        bestPlaying = mc;
                        if (sameController(mc, preferredMc)) {
                            break;
                        }
                    }
                }
            }
        }

        MediaController chosen = (bestPlaying != null) ? bestPlaying : firstAllowed;
        if (chosen != null) {
            Bitmap matchingFallback = (fallbackPkg != null && fallbackPkg.equals(chosen.getPackageName()))
                    ? fallbackArt : null;
            extractFromController(chosen, matchingFallback);
            return;
        }

        if (S9HelperService.isPlaying || !S9HelperService.activeMediaPackage.isEmpty()) {
            S9HelperService.isPlaying = false;
            S9HelperService.activeMediaPackage = "";
            notifyServiceMediaUpdate();
        }
    }

    private static boolean sameController(MediaController a, MediaController b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        try {
            MediaSession.Token ta = a.getSessionToken();
            MediaSession.Token tb = b.getSessionToken();
            if (ta != null && ta.equals(tb)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        String pkg = sbn.getPackageName();
        if (!isPackageAllowed(pkg)) return;

        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        if (extras == null) return;

        boolean hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
        if (!hasMediaSession && !"com.maxmpz.audioplayer".equals(pkg)) {
            return;
        }

        Bitmap largeBmp = loadIconBitmap(n.getLargeIcon());
        MediaSession.Token token = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        if (token != null) {
            try {
                MediaController mc = new MediaController(this, token);
                boolean thisPlaying = isControllerActiveState(mc.getPlaybackState());
                if (thisPlaying) {
                    extractFromController(mc, largeBmp);
                    return;
                }
                List<MediaController> active = null;
                try {
                    if (sessionManager != null && listenerComponent != null) {
                        active = sessionManager.getActiveSessions(listenerComponent);
                    }
                } catch (Exception ignored) {}
                if (active != null && !active.isEmpty()) {
                    selectAndApplyBestController(active, largeBmp, pkg, mc);
                } else {
                    extractFromController(mc, largeBmp);
                }
                return;
            } catch (Exception ignored) {}
        }

        if (largeBmp != null) {
            CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
            String title = (titleCs != null ? titleCs.toString() : "") +
                           (textCs != null ? " - " + textCs.toString() : "");
            if (title.trim().isEmpty()) {
                title = resolveAppLabel(pkg);
            }
            S9HelperService.activeMediaPackage = pkg;
            S9HelperService.currentTrackTitle = title;
            S9HelperService.isPlaying = true;
            S9HelperService.currentColor = AlbumColorExtractor.extractPowerampBlurColor(largeBmp);
            notifyServiceMediaUpdate();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && isPackageAllowed(sbn.getPackageName())) {
            refreshMediaSessions();
        }
    }

    private void extractFromController(MediaController mc, Bitmap notifFallback) {
        try {
            String pkg = mc.getPackageName();
            MediaMetadata meta = mc.getMetadata();
            PlaybackState state = mc.getPlaybackState();
            boolean playing = isControllerActiveState(state);

            String title = resolveAppLabel(pkg);
            if (meta != null) {
                String t = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
                String a = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (t != null && !t.isEmpty()) {
                    title = (a != null && !a.isEmpty()) ? (a + " - " + t) : t;
                }
            }

            Bitmap art = extractArtBitmap(pkg, meta, notifFallback);
            S9HelperService.activeMediaPackage = (pkg != null) ? pkg : "";
            S9HelperService.currentTrackTitle = title;
            S9HelperService.isPlaying = playing;
            if (art != null) {
                S9HelperService.currentColor = AlbumColorExtractor.extractPowerampBlurColor(art);
            }

            notifyServiceMediaUpdate();
        } catch (Exception e) {
            Log.w("S9Helper", "extractFromController failed", e);
        }
    }

    private Bitmap extractArtBitmap(String pkg, MediaMetadata meta, Bitmap notifFallback) {
        if (meta != null) {
            Bitmap bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bmp != null) return bmp;
            bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bmp != null) return bmp;
            bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (bmp != null) return bmp;

            String uriStr = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uriStr == null || uriStr.isEmpty()) {
                uriStr = meta.getString(MediaMetadata.METADATA_KEY_ART_URI);
            }
            bmp = decodeLocalUriBitmap(uriStr);
            if (bmp != null) return bmp;
        }
        if (notifFallback != null) {
            return notifFallback;
        }
        if (pkg != null) {
            try {
                StatusBarNotification[] sbns = getActiveNotifications();
                if (sbns != null) {
                    for (StatusBarNotification sbn : sbns) {
                        if (sbn != null && pkg.equals(sbn.getPackageName()) && sbn.getNotification() != null) {
                            Bitmap bmp = loadIconBitmap(sbn.getNotification().getLargeIcon());
                            if (bmp != null) return bmp;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Bitmap decodeLocalUriBitmap(String uriStr) {
        if (uriStr == null) return null;
        if (!uriStr.startsWith("content://") && !uriStr.startsWith("file://")) {
            return null;
        }
        InputStream in = null;
        try {
            Uri uri = Uri.parse(uriStr);
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 4;
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    private Bitmap loadIconBitmap(Icon icon) {
        if (icon == null) return null;
        try {
            Drawable d = icon.loadDrawable(this);
            if (d instanceof BitmapDrawable) {
                return ((BitmapDrawable) d).getBitmap();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String resolveAppLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "Music";
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception ignored) {}
        return "Music";
    }

    private void notifyServiceMediaUpdate() {
        try {
            Intent refresh = new Intent(this, S9HelperService.class);
            refresh.setAction("REFRESH_PREFS");
            startService(refresh);
        } catch (Exception ignored) {}
        sendBroadcast(new Intent(S9HelperService.ACTION_STATE_UPDATED));
    }
}
