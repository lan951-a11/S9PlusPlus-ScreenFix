package com.s9plus.helper;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

class UiEventReceiver extends BroadcastReceiver {
    private final MainActivity act;
    UiEventReceiver(MainActivity act) {
        this.act = act;
    }
    @Override
    public void onReceive(Context context, Intent intent) {
        act.updateUiFromService();
    }
}

class UiRefreshRunnable implements Runnable {
    private final MainActivity act;
    UiRefreshRunnable(MainActivity act) {
        this.act = act;
    }
    @Override
    public void run() {
        act.onLiveUiRefreshTick();
    }
}

class UiTeleFetchRunnable implements Runnable {
    private final MainActivity act;
    UiTeleFetchRunnable(MainActivity act) {
        this.act = act;
    }
    @Override
    public void run() {
        act.runTelemetryFetchOnBg();
    }
}

class UiTeleApplyRunnable implements Runnable {
    private final MainActivity act;
    UiTeleApplyRunnable(MainActivity act) {
        this.act = act;
    }
    @Override
    public void run() {
        act.onTelemetryFetchedOnUi();
    }
}

class DialogDismissClick implements View.OnClickListener {
    private final Dialog dialog;
    DialogDismissClick(Dialog dialog) {
        this.dialog = dialog;
    }
    @Override
    public void onClick(View v) {
        dialog.dismiss();
    }
}

public class MainActivity extends Activity implements View.OnClickListener, CompoundButton.OnCheckedChangeListener, SeekBar.OnSeekBarChangeListener {
    private static final int ID_SWITCH_LED = 1001;
    private static final int ID_SWITCH_PULSE = 1002;
    private static final int ID_SWITCH_AOD = 1003;
    private static final int ID_SWITCH_DIRECT_DOZE = 1004;
    private static final int ID_SWITCH_HBM_SLIDER = 1005;
    private static final int ID_SWITCH_HBM_FORCE = 1006;
    private static final int ID_SWITCH_HBM_ACL = 1007;
    private static final int ID_SWITCH_HBM_THERMAL = 1008;
    private static final int ID_SWITCH_HBM_WATT = 1009;
    private static final int ID_SWITCH_NO_TIMEOUT = 1010;
    private static final int ID_SWITCH_POWERAMP_ONLY = 1011;
    private static final int ID_SWITCH_ABS_LOUDNESS = 1012;
    private static final int ID_BTN_TEST_LED = 2001;
    private static final int ID_BTN_NOTIF_ACCESS = 2002;
    private static final int ID_BTN_TEST_AOD = 2003;
    private static final int ID_BTN_RESET_BATT = 2004;
    private static final int ID_SEEK_LED_BRIGHT = 3001;
    private static final int ID_SEEK_AOD_MS = 3002;
    private static final int ID_SEEK_LED_REACT = 3003;
    private static final int ID_SEEK_LED_HZ = 3004;
    private static final int ID_SEEK_HBM_CAP = 3005;
    private static final int ID_SEEK_HBM_THERMAL = 3006;
    private static final int ID_SEEK_HBM_WATT = 3007;
    private static final int ID_SEEK_ABS_MIN = 3008;
    private static final int ID_SEEK_ABS_MAX = 3009;
    private static final int ID_INFO_HBM_SLIDER = 4001;
    private static final int ID_INFO_HBM_FORCE = 4002;
    private static final int ID_INFO_HBM_ACL = 4003;
    private static final int ID_INFO_HBM_CAP = 4004;
    private static final int ID_INFO_HBM_THERMAL = 4005;
    private static final int ID_INFO_HBM_WATT = 4006;
    private static final int ID_INFO_NO_TIMEOUT = 4007;
    private static final int ID_INFO_AOD_FIX = 4008;
    private static final int ID_INFO_DIRECT_DOZE = 4009;
    private static final int ID_INFO_AOD_HOLD = 4010;
    private static final int ID_INFO_RESET_BATT = 4011;

    private SharedPreferences prefs;
    private LinearLayout rootLayout;
    private TextView trackView;
    private TextView colorHexView;
    private View colorPreviewBox;
    private TextView ledBrightLabel;
    private SeekBar brightSeek;
    private TextView absMinLabel;
    private SeekBar absMinSeek;
    private TextView absMaxLabel;
    private SeekBar absMaxSeek;
    private TextView ledReactivityLabel;
    private TextView ledHzLabel;
    private TextView msLabel;
    private TextView hbmStatusView;
    private TextView hbmCapLabel;
    private TextView hbmThermalLabel;
    private TextView hbmWattLabel;
    private Switch hbmSliderSwitch;
    private Switch hbmForceSwitch;
    private Switch hbmAclSwitch;
    private Switch hbmThermalSwitch;
    private Switch hbmWattSwitch;
    private Switch noTimeoutSwitch;
    private Switch powerampOnlySwitch;
    private Switch absLoudnessSwitch;
    private boolean updatingHbmUi = false;
    private UiEventReceiver uiReceiver;
    private Handler uiHandler;
    private UiRefreshRunnable uiRefreshRunnable;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private UiTeleFetchRunnable uiTeleFetchRunnable;
    private UiTeleApplyRunnable uiTeleApplyRunnable;
    private volatile boolean uiVisible = false;
    private String lastRenderedTrack = null;
    private String lastRenderedHex = null;
    private int lastRenderedSwatchColor = 0;
    private boolean hasRenderedSwatch = false;
    private GradientDrawable colorSwatchDrawable;
    private String lastHbmStatusText = null;
    private int lastHbmStatusColor = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uiReceiver = new UiEventReceiver(this);
        uiHandler = new Handler(Looper.getMainLooper());
        uiRefreshRunnable = new UiRefreshRunnable(this);
        bgThread = new HandlerThread("S9UiTele");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        uiTeleFetchRunnable = new UiTeleFetchRunnable(this);
        uiTeleApplyRunnable = new UiTeleApplyRunnable(this);
        prefs = getSharedPreferences("s9_prefs", MODE_PRIVATE);

        Intent svc = new Intent(this, S9HelperService.class);
        startService(svc);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100);
        }

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        buildUi();
        updateUiFromService();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(false);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Color.BLACK);

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setFitsSystemWindows(false);
        rootLayout.setPadding(56, 80, 56, 140);
        rootLayout.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("S9++ (ScreenFix)");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        rootLayout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Only fixes 9-series OLED screens (and experimental S8) whose specific failure mode needs Always On Display (AOD) first before turning on to show full color gamut \u2014 along with a whole lot of other display and comfort features.");
        subtitle.setTextSize(13);
        subtitle.setLineSpacing(3f, 1.15f);
        subtitle.setTextColor(Color.parseColor("#B8A8CC"));
        subtitle.setPadding(0, 8, 0, 22);
        rootLayout.addView(subtitle);

        // Compatibility & Root Warning Banner
        LinearLayout warnCard = new LinearLayout(this);
        warnCard.setOrientation(LinearLayout.VERTICAL);
        warnCard.setPadding(40, 32, 40, 32);
        GradientDrawable warnBg = new GradientDrawable();
        warnBg.setColor(Color.BLACK);
        warnBg.setCornerRadius(getScreenCornerRadiusPx());
        warnBg.setStroke(4, Color.parseColor("#FFB74D"));
        warnCard.setBackground(warnBg);
        LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        warnLp.bottomMargin = 36;
        warnCard.setLayoutParams(warnLp);

        TextView warnTitle = new TextView(this);
        warnTitle.setText("REQUIREMENTS & COMPATIBILITY WARNING");
        warnTitle.setTextSize(14);
        warnTitle.setTypeface(Typeface.DEFAULT_BOLD);
        warnTitle.setTextColor(Color.parseColor("#FFB74D"));
        warnTitle.setPadding(0, 0, 0, 10);
        warnCard.addView(warnTitle);

        TextView warnBody = new TextView(this);
        warnBody.setText(
                "\u2022 ROOTED & EXYNOS MODELS ONLY: Requires Magisk / KernelSU root on Samsung Exynos hardware. Snapdragon models and unrooted phones are NOT supported.\n"
                + "\u2022 Supported: Galaxy S9 / S9+ / Note 9 series (Exynos 9810).\n"
                + "\u2022 Experimental (NOT guaranteed): Galaxy S8 / S8+ / Note 8 series (Exynos 8895) \u2014 AOD fix includes Android 9 fallback, but is NOT guaranteed to work on S8 panels. HBM 720-nit unlock is calibrated for S9 / Note 9 only."
        );
        warnBody.setTextSize(13);
        warnBody.setLineSpacing(4f, 1.15f);
        warnBody.setTextColor(Color.parseColor("#FFE6C2"));
        warnCard.addView(warnBody);

        String hwName = (Build.HARDWARE != null) ? Build.HARDWARE : "";
        boolean isQcom = hwName.toLowerCase(java.util.Locale.US).contains("qcom");
        String detectedText = "Detected: " + Build.MODEL + " (" + Build.DEVICE + " / " + hwName
                + ", Android " + Build.VERSION.RELEASE + ")";
        if (isQcom) {
            detectedText += " \u2014 WARNING: Qualcomm/Snapdragon hardware detected; Exynos paths will not work!";
        }
        TextView detectedView = new TextView(this);
        detectedView.setText(detectedText);
        detectedView.setTextSize(12);
        detectedView.setTextColor(Color.parseColor(isQcom ? "#FF6E6E" : "#FFCC80"));
        detectedView.setPadding(0, 14, 0, 0);
        warnCard.addView(detectedView);

        rootLayout.addView(warnCard);

        // Card 1: Automatic Real AOD Screen Color Fix
        LinearLayout aodCard = createCard();

        TextView aodTitle = new TextView(this);
        aodTitle.setText("Automatic AOD Screen Color Fix");
        aodTitle.setTextSize(18);
        aodTitle.setTypeface(Typeface.DEFAULT_BOLD);
        aodTitle.setTextColor(Color.WHITE);
        aodCard.addView(aodTitle);

        Switch aodSwitch = new Switch(this);
        aodSwitch.setId(ID_SWITCH_AOD);
        aodSwitch.setChecked(prefs.getBoolean("aod_fix_enabled", true));
        aodSwitch.setOnCheckedChangeListener(this);
        aodCard.addView(createSwitchInfoRow(
                "Auto-Cycle Real AOD on Power Button Wake",
                aodSwitch, ID_INFO_AOD_FIX, 16, 10));

        Switch directDozeSwitch = new Switch(this);
        directDozeSwitch.setId(ID_SWITCH_DIRECT_DOZE);
        directDozeSwitch.setChecked(prefs.getBoolean("aod_direct_doze", true));
        directDozeSwitch.setOnCheckedChangeListener(this);
        aodCard.addView(createSwitchInfoRow(
                "Ultra-Fast Direct Hardware DOZE (~0.1s, skips AOD UI)",
                directDozeSwitch, ID_INFO_DIRECT_DOZE, 6, 12));

        int currentMs = prefs.getInt("aod_precharge_ms", 50);
        msLabel = new TextView(this);
        msLabel.setText("AOD Extra Hold After DOZE: " + currentMs + " ms (" + String.format("%.2f", currentMs / 1000.0f) + "s)");
        msLabel.setTextColor(Color.parseColor("#CCDDFF"));
        aodCard.addView(createLabelInfoRow(msLabel, ID_INFO_AOD_HOLD, "AOD Extra Hold After DOZE", 10, 8));

        SeekBar msSeek = new SeekBar(this);
        msSeek.setId(ID_SEEK_AOD_MS);
        msSeek.setMax(1000); // 0ms to 1000ms
        msSeek.setProgress(Math.max(0, Math.min(1000, currentMs)));
        msSeek.setOnSeekBarChangeListener(this);
        aodCard.addView(msSeek);

        Button testAodBtn = createButton("Test Automatic AOD Wake Cycle Now", ID_BTN_TEST_AOD);
        aodCard.addView(testAodBtn);

        rootLayout.addView(aodCard);

        // Card 2: Display Max Brightness Unlock (720 nits HBM)
        LinearLayout hbmCard = createCard();

        TextView hbmTitle = new TextView(this);
        hbmTitle.setText("Display Max Brightness Unlock (HBM 720 nits)");
        hbmTitle.setTextSize(18);
        hbmTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hbmTitle.setTextColor(Color.WHITE);
        hbmCard.addView(hbmTitle);

        hbmStatusView = new TextView(this);
        hbmStatusView.setTextSize(14);
        hbmStatusView.setMinLines(3);
        hbmStatusView.setTextColor(Color.parseColor("#88FFAA"));
        hbmStatusView.setPadding(0, 12, 0, 16);
        hbmCard.addView(hbmStatusView);

        hbmSliderSwitch = new Switch(this);
        hbmSliderSwitch.setId(ID_SWITCH_HBM_SLIDER);
        hbmSliderSwitch.setChecked(prefs.getBoolean("hbm_slider_unlock", false));
        hbmSliderSwitch.setOnCheckedChangeListener(this);
        hbmCard.addView(createSwitchInfoRow(
                "Unlock Full 720-nit Range on System Slider (top 30% -> 720 nits)",
                hbmSliderSwitch, ID_INFO_HBM_SLIDER, 4, 8));

        hbmForceSwitch = new Switch(this);
        hbmForceSwitch.setId(ID_SWITCH_HBM_FORCE);
        hbmForceSwitch.setChecked(prefs.getBoolean("hbm_force_max", false));
        hbmForceSwitch.setOnCheckedChangeListener(this);
        hbmCard.addView(createSwitchInfoRow(
                "Force MAX HBM Brightness Now",
                hbmForceSwitch, ID_INFO_HBM_FORCE, 12, 12));

        hbmAclSwitch = new Switch(this);
        hbmAclSwitch.setId(ID_SWITCH_HBM_ACL);
        hbmAclSwitch.setChecked(prefs.getBoolean("hbm_disable_acl", false));
        hbmAclSwitch.setOnCheckedChangeListener(this);
        hbmCard.addView(createSwitchInfoRow(
                "Disable OLED ACL White Dimming in HBM",
                hbmAclSwitch, ID_INFO_HBM_ACL, 8, 8));

        int capNits = Math.max(400, Math.min(720, prefs.getInt("hbm_cap_nits", 720)));
        hbmCapLabel = new TextView(this);
        hbmCapLabel.setText("Unlocked Brightness Ceiling: " + capNits + " nits");
        hbmCapLabel.setTextColor(Color.parseColor("#CCDDFF"));
        hbmCard.addView(createLabelInfoRow(hbmCapLabel, ID_INFO_HBM_CAP, "Unlocked Brightness Ceiling", 14, 8));

        SeekBar hbmCapSeek = new SeekBar(this);
        hbmCapSeek.setId(ID_SEEK_HBM_CAP);
        hbmCapSeek.setMax(320); // 400..720 nits
        hbmCapSeek.setProgress(capNits - 400);
        hbmCapSeek.setOnSeekBarChangeListener(this);
        hbmCard.addView(hbmCapSeek);

        hbmThermalSwitch = new Switch(this);
        hbmThermalSwitch.setId(ID_SWITCH_HBM_THERMAL);
        hbmThermalSwitch.setChecked(prefs.getBoolean("hbm_thermal_enabled", true));
        hbmThermalSwitch.setOnCheckedChangeListener(this);
        hbmCard.addView(createSwitchInfoRow(
                "Thermal Safety Guard (Auto Step-Down on Heat)",
                hbmThermalSwitch, ID_INFO_HBM_THERMAL, 14, 6));

        int limitC = Math.max(35, Math.min(48, prefs.getInt("hbm_thermal_limit_c", 42)));
        hbmThermalLabel = new TextView(this);
        hbmThermalLabel.setText("Thermal Limit: " + limitC + "\u00B0C Batt / " + (limitC + 4) + "\u00B0C Board");
        hbmThermalLabel.setTextColor(Color.parseColor("#CCDDFF"));
        hbmThermalLabel.setPadding(0, 8, 0, 8);
        hbmCard.addView(hbmThermalLabel);

        SeekBar hbmThermalSeek = new SeekBar(this);
        hbmThermalSeek.setId(ID_SEEK_HBM_THERMAL);
        hbmThermalSeek.setMax(13); // 35..48 C
        hbmThermalSeek.setProgress(limitC - 35);
        hbmThermalSeek.setOnSeekBarChangeListener(this);
        hbmCard.addView(hbmThermalSeek);

        hbmWattSwitch = new Switch(this);
        hbmWattSwitch.setId(ID_SWITCH_HBM_WATT);
        hbmWattSwitch.setChecked(prefs.getBoolean("hbm_watt_enabled", true));
        hbmWattSwitch.setOnCheckedChangeListener(this);
        hbmCard.addView(createSwitchInfoRow(
                "OLED Wattage Safety Guard (Live Panel Power Cap)",
                hbmWattSwitch, ID_INFO_HBM_WATT, 14, 6));

        int wattLimitMw = Math.max(1000, Math.min(4500, prefs.getInt("hbm_watt_limit_mw", 2500)));
        hbmWattLabel = new TextView(this);
        hbmWattLabel.setText(String.format("OLED Power Cap: %.1f W (Display PMIC)", wattLimitMw / 1000.0f));
        hbmWattLabel.setTextColor(Color.parseColor("#CCDDFF"));
        hbmWattLabel.setPadding(0, 8, 0, 8);
        hbmCard.addView(hbmWattLabel);

        SeekBar hbmWattSeek = new SeekBar(this);
        hbmWattSeek.setId(ID_SEEK_HBM_WATT);
        hbmWattSeek.setMax(35); // 1.0 W .. 4.5 W (0.1 W steps)
        hbmWattSeek.setProgress(Math.max(0, Math.min(35, (wattLimitMw - 1000) / 100)));
        hbmWattSeek.setOnSeekBarChangeListener(this);
        hbmCard.addView(hbmWattSeek);

        noTimeoutSwitch = new Switch(this);
        noTimeoutSwitch.setId(ID_SWITCH_NO_TIMEOUT);
        noTimeoutSwitch.setChecked(prefs.getBoolean("no_screen_timeout", false));
        noTimeoutSwitch.setOnCheckedChangeListener(this);
        hbmCard.addView(createSwitchInfoRow(
                "Never Auto-Turn Off Screen (Disable 10m Timeout)",
                noTimeoutSwitch, ID_INFO_NO_TIMEOUT, 14, 6));

        rootLayout.addView(hbmCard);

        // Card 3: Music RGB Notification LED
        LinearLayout ledCard = createCard();

        TextView ledTitle = new TextView(this);
        ledTitle.setText("Music RGB Notification LED");
        ledTitle.setTextSize(18);
        ledTitle.setTypeface(Typeface.DEFAULT_BOLD);
        ledTitle.setTextColor(Color.WHITE);
        ledCard.addView(ledTitle);

        trackView = new TextView(this);
        trackView.setText(S9HelperService.currentTrackTitle);
        trackView.setTextSize(14);
        trackView.setTextColor(Color.parseColor("#CCDDFF"));
        trackView.setPadding(0, 10, 0, 10);
        ledCard.addView(trackView);

        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.setPadding(0, 12, 0, 24);

        colorPreviewBox = new View(this);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(96, 96);
        boxLp.rightMargin = 32;
        colorPreviewBox.setLayoutParams(boxLp);
        previewRow.addView(colorPreviewBox);

        colorHexView = new TextView(this);
        colorHexView.setTextSize(15);
        colorHexView.setTextColor(Color.WHITE);
        previewRow.addView(colorHexView);
        ledCard.addView(previewRow);

        Switch ledSwitch = new Switch(this);
        ledSwitch.setId(ID_SWITCH_LED);
        ledSwitch.setText("Sync Top-Left RGB LED with Song Blur Color");
        ledSwitch.setTextColor(Color.WHITE);
        ledSwitch.setChecked(prefs.getBoolean("led_enabled", false));
        ledSwitch.setOnCheckedChangeListener(this);
        ledCard.addView(ledSwitch);

        powerampOnlySwitch = new Switch(this);
        powerampOnlySwitch.setId(ID_SWITCH_POWERAMP_ONLY);
        powerampOnlySwitch.setText("Poweramp Only (Off = All Music Apps)");
        powerampOnlySwitch.setTextColor(Color.WHITE);
        powerampOnlySwitch.setPadding(0, 20, 0, 0);
        powerampOnlySwitch.setChecked(prefs.getBoolean("led_poweramp_only", false));
        powerampOnlySwitch.setOnCheckedChangeListener(this);
        ledCard.addView(powerampOnlySwitch);

        Switch pulseSwitch = new Switch(this);
        pulseSwitch.setId(ID_SWITCH_PULSE);
        pulseSwitch.setText("VU Meter Mode \u2014 Brightness Follows Volume (Uncheck for Solid)");
        pulseSwitch.setTextColor(Color.WHITE);
        pulseSwitch.setPadding(0, 20, 0, 12);
        pulseSwitch.setChecked(prefs.getBoolean("volume_pulse_enabled", true));
        pulseSwitch.setOnCheckedChangeListener(this);
        ledCard.addView(pulseSwitch);

        absLoudnessSwitch = new Switch(this);
        absLoudnessSwitch.setId(ID_SWITCH_ABS_LOUDNESS);
        absLoudnessSwitch.setText("Absolute Loudness Mode (Quiet = Dim, Loud = Bright)");
        absLoudnessSwitch.setTextColor(Color.WHITE);
        absLoudnessSwitch.setPadding(0, 4, 0, 16);
        absLoudnessSwitch.setChecked(prefs.getBoolean("led_abs_loudness", false));
        absLoudnessSwitch.setOnCheckedChangeListener(this);
        ledCard.addView(absLoudnessSwitch);

        int currentBrightPct = prefs.getInt("led_brightness_pct", 100);
        ledBrightLabel = new TextView(this);
        ledBrightLabel.setText("LED Max Brightness: " + currentBrightPct + "%");
        ledBrightLabel.setTextColor(Color.parseColor("#CCDDFF"));
        ledBrightLabel.setPadding(0, 12, 0, 8);
        ledCard.addView(ledBrightLabel);

        brightSeek = new SeekBar(this);
        brightSeek.setId(ID_SEEK_LED_BRIGHT);
        brightSeek.setMax(95); // 5% to 100%
        brightSeek.setProgress(Math.max(0, currentBrightPct - 5));
        brightSeek.setOnSeekBarChangeListener(this);
        ledCard.addView(brightSeek);

        int absMinPct = Math.max(0, Math.min(95, prefs.getInt("led_abs_min_pct", 0)));
        absMinLabel = new TextView(this);
        absMinLabel.setText("Absolute Min Brightness (Quiet): " + absMinPct + "%");
        absMinLabel.setTextColor(Color.parseColor("#CCDDFF"));
        absMinLabel.setPadding(0, 14, 0, 8);
        ledCard.addView(absMinLabel);

        absMinSeek = new SeekBar(this);
        absMinSeek.setId(ID_SEEK_ABS_MIN);
        absMinSeek.setMax(95); // 0% to 95%
        absMinSeek.setProgress(absMinPct);
        absMinSeek.setOnSeekBarChangeListener(this);
        ledCard.addView(absMinSeek);

        int absMaxPct = Math.max(5, Math.min(100, prefs.getInt("led_abs_max_pct", 100)));
        absMaxLabel = new TextView(this);
        absMaxLabel.setText("Absolute Max Brightness (Loud): " + absMaxPct + "%");
        absMaxLabel.setTextColor(Color.parseColor("#CCDDFF"));
        absMaxLabel.setPadding(0, 14, 0, 8);
        ledCard.addView(absMaxLabel);

        absMaxSeek = new SeekBar(this);
        absMaxSeek.setId(ID_SEEK_ABS_MAX);
        absMaxSeek.setMax(95); // 5% to 100%
        absMaxSeek.setProgress(Math.max(0, absMaxPct - 5));
        absMaxSeek.setOnSeekBarChangeListener(this);
        ledCard.addView(absMaxSeek);

        updateLedSliderEnabledStates();

        int currentReactPct = prefs.getInt("led_reactivity_pct", 250);
        ledReactivityLabel = new TextView(this);
        ledReactivityLabel.setText("VU Meter Swing / Dynamics: " + currentReactPct + "%");
        ledReactivityLabel.setTextColor(Color.parseColor("#CCDDFF"));
        ledReactivityLabel.setPadding(0, 16, 0, 8);
        ledCard.addView(ledReactivityLabel);

        SeekBar reactSeek = new SeekBar(this);
        reactSeek.setId(ID_SEEK_LED_REACT);
        reactSeek.setMax(950); // 50% to 1000%
        reactSeek.setProgress(Math.max(0, Math.min(950, currentReactPct - 50)));
        reactSeek.setOnSeekBarChangeListener(this);
        ledCard.addView(reactSeek);

        int currentHz = prefs.getInt("led_vu_hz", 20);
        ledHzLabel = new TextView(this);
        ledHzLabel.setText("VU Meter Update Frequency: " + currentHz + " Hz");
        ledHzLabel.setTextColor(Color.parseColor("#CCDDFF"));
        ledHzLabel.setPadding(0, 16, 0, 8);
        ledCard.addView(ledHzLabel);

        SeekBar hzSeek = new SeekBar(this);
        hzSeek.setId(ID_SEEK_LED_HZ);
        hzSeek.setMax(58); // 2 Hz to 60 Hz
        hzSeek.setProgress(Math.max(0, Math.min(58, currentHz - 2)));
        hzSeek.setOnSeekBarChangeListener(this);
        ledCard.addView(hzSeek);

        Button testLedBtn = createButton("Test Full-Bright Blue/Purple RGB LED", ID_BTN_TEST_LED);
        ledCard.addView(testLedBtn);

        Button notifAccessBtn = createButton("Grant Notification Access (if not granted)", ID_BTN_NOTIF_ACCESS);
        ledCard.addView(notifAccessBtn);

        rootLayout.addView(ledCard);

        // Card 4: Battery Cycle Counter Reset
        LinearLayout battCard = createCard();

        TextView battTitle = new TextView(this);
        battTitle.setText("Replacement Battery Calibration (Root)");
        battTitle.setTextSize(18);
        battTitle.setTypeface(Typeface.DEFAULT_BOLD);
        battTitle.setTextColor(Color.WHITE);
        battCard.addView(createLabelInfoRow(battTitle, ID_INFO_RESET_BATT, "Replacement Battery Calibration", 0, 8));

        Button resetBattBtn = createButton("Reset Battery Cycle Count to 0 & Health to 100%", ID_BTN_RESET_BATT);
        battCard.addView(resetBattBtn);

        rootLayout.addView(battCard);

        scroll.addView(rootLayout);
        setContentView(scroll);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (updatingHbmUi) return;
        int id = buttonView.getId();
        if (id == ID_SWITCH_LED) {
            prefs.edit().putBoolean("led_enabled", isChecked).apply();
            notifyServicePrefsChanged();
        } else if (id == ID_SWITCH_PULSE) {
            prefs.edit().putBoolean("volume_pulse_enabled", isChecked).apply();
            updateLedSliderEnabledStates();
            notifyServicePrefsChanged();
        } else if (id == ID_SWITCH_ABS_LOUDNESS) {
            prefs.edit().putBoolean("led_abs_loudness", isChecked).apply();
            updateLedSliderEnabledStates();
            notifyServicePrefsChanged();
        } else if (id == ID_SWITCH_AOD) {
            prefs.edit().putBoolean("aod_fix_enabled", isChecked).apply();
        } else if (id == ID_SWITCH_DIRECT_DOZE) {
            prefs.edit().putBoolean("aod_direct_doze", isChecked).apply();
        } else if (id == ID_SWITCH_HBM_SLIDER) {
            prefs.edit().putBoolean("hbm_slider_unlock", isChecked).apply();
            Intent i = new Intent(this, S9HelperService.class);
            i.setAction(isChecked ? "ENABLE_SLIDER_UNLOCK" : "REFRESH_HBM");
            startService(i);
        } else if (id == ID_SWITCH_HBM_FORCE) {
            Intent i = new Intent(this, S9HelperService.class);
            i.setAction("SET_FORCE_HBM");
            i.putExtra("enabled", isChecked);
            startService(i);
        } else if (id == ID_SWITCH_HBM_ACL) {
            prefs.edit().putBoolean("hbm_disable_acl", isChecked).apply();
            Intent i = new Intent(this, S9HelperService.class);
            i.setAction("REFRESH_HBM");
            startService(i);
        } else if (id == ID_SWITCH_HBM_THERMAL) {
            prefs.edit().putBoolean("hbm_thermal_enabled", isChecked).apply();
            Intent i = new Intent(this, S9HelperService.class);
            i.setAction("REFRESH_HBM");
            startService(i);
        } else if (id == ID_SWITCH_HBM_WATT) {
            prefs.edit().putBoolean("hbm_watt_enabled", isChecked).apply();
            Intent i = new Intent(this, S9HelperService.class);
            i.setAction("REFRESH_HBM");
            startService(i);
        } else if (id == ID_SWITCH_NO_TIMEOUT) {
            prefs.edit().putBoolean("no_screen_timeout", isChecked).apply();
            Intent i = new Intent(this, S9HelperService.class);
            i.setAction("APPLY_SCREEN_TIMEOUT");
            startService(i);
        } else if (id == ID_SWITCH_POWERAMP_ONLY) {
            prefs.edit().putBoolean("led_poweramp_only", isChecked).apply();
            notifyServicePrefsChanged();
            sendBroadcast(new Intent(S9HelperService.ACTION_REFRESH_MEDIA_SOURCE));
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == ID_BTN_TEST_LED) {
            S9HelperService.currentColor = Color.rgb(70, 95, 235);
            boolean absActive = prefs.getBoolean("volume_pulse_enabled", true)
                    && prefs.getBoolean("led_abs_loudness", false);
            int pct = absActive
                    ? Math.max(5, Math.min(100, prefs.getInt("led_abs_max_pct", 100)))
                    : prefs.getInt("led_brightness_pct", 100);
            RootShell.clearLedPattern();
            previewLedAtPct(pct);
            int r = (70 * pct) / 100;
            int g = (95 * pct) / 100;
            int b = (235 * pct) / 100;
            updateUiFromService();
            Toast.makeText(this, "Full High-Power RGB LED (" + pct + "%): R=" + r + " G=" + g + " B=" + b, Toast.LENGTH_SHORT).show();
        } else if (id == ID_BTN_NOTIF_ACCESS) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } else if (id == ID_BTN_TEST_AOD) {
            Intent i = new Intent(this, S9HelperService.class);
            i.setAction("TEST_AOD_CYCLE");
            startService(i);
        } else if (id == ID_BTN_RESET_BATT) {
            boolean ok = RootShell.resetBatteryCycles();
            if (ok) {
                Toast.makeText(this, "Reset /efs/FactoryApp/batt_discharge_level to 0 and ASOC to 100%!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Root access not active yet — grant Magisk root first!", Toast.LENGTH_LONG).show();
            }
        } else if (id >= ID_INFO_HBM_SLIDER && id <= ID_INFO_RESET_BATT) {
            showInfoDialog(id);
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        int id = seekBar.getId();
        if (id == ID_SEEK_LED_BRIGHT) {
            int pct = 5 + progress;
            ledBrightLabel.setText("LED Max Brightness: " + pct + "%");
            prefs.edit().putInt("led_brightness_pct", pct).apply();
            if (fromUser) {
                previewLedAtPct(pct);
            }
        } else if (id == ID_SEEK_ABS_MIN) {
            int minPct = progress;
            absMinLabel.setText("Absolute Min Brightness (Quiet): " + minPct + "%");
            int curMax = Math.max(5, Math.min(100, prefs.getInt("led_abs_max_pct", 100)));
            if (minPct > curMax) {
                int newMax = Math.max(5, minPct);
                prefs.edit().putInt("led_abs_min_pct", minPct).putInt("led_abs_max_pct", newMax).apply();
                absMaxLabel.setText("Absolute Max Brightness (Loud): " + newMax + "%");
                if (absMaxSeek != null) {
                    absMaxSeek.setProgress(Math.max(0, newMax - 5));
                }
            } else {
                prefs.edit().putInt("led_abs_min_pct", minPct).apply();
            }
            if (fromUser) {
                previewLedAtPct(minPct);
            }
        } else if (id == ID_SEEK_ABS_MAX) {
            int maxPct = 5 + progress;
            absMaxLabel.setText("Absolute Max Brightness (Loud): " + maxPct + "%");
            int curMin = Math.max(0, Math.min(95, prefs.getInt("led_abs_min_pct", 0)));
            if (maxPct < curMin) {
                prefs.edit().putInt("led_abs_max_pct", maxPct).putInt("led_abs_min_pct", maxPct).apply();
                absMinLabel.setText("Absolute Min Brightness (Quiet): " + maxPct + "%");
                if (absMinSeek != null) {
                    absMinSeek.setProgress(maxPct);
                }
            } else {
                prefs.edit().putInt("led_abs_max_pct", maxPct).apply();
            }
            if (fromUser) {
                previewLedAtPct(maxPct);
            }
        } else if (id == ID_SEEK_LED_REACT) {
            int pct = 50 + progress;
            ledReactivityLabel.setText("VU Meter Swing / Dynamics: " + pct + "%");
            prefs.edit().putInt("led_reactivity_pct", pct).apply();
        } else if (id == ID_SEEK_LED_HZ) {
            int hz = 2 + progress;
            ledHzLabel.setText("VU Meter Update Frequency: " + hz + " Hz");
            prefs.edit().putInt("led_vu_hz", hz).apply();
        } else if (id == ID_SEEK_AOD_MS) {
            int ms = progress;
            msLabel.setText("AOD Extra Hold After DOZE: " + ms + " ms (" + String.format("%.2f", ms / 1000.0f) + "s)");
            prefs.edit().putInt("aod_precharge_ms", ms).apply();
        } else if (id == ID_SEEK_HBM_CAP) {
            int nits = 400 + progress;
            hbmCapLabel.setText("Unlocked Brightness Ceiling: " + nits + " nits");
            prefs.edit().putInt("hbm_cap_nits", nits).apply();
            if (fromUser) {
                Intent i = new Intent(this, S9HelperService.class);
                i.setAction("REFRESH_HBM");
                startService(i);
            }
        } else if (id == ID_SEEK_HBM_THERMAL) {
            int limitC = 35 + progress;
            hbmThermalLabel.setText("Thermal Limit: " + limitC + "\u00B0C Batt / " + (limitC + 4) + "\u00B0C Board");
            prefs.edit().putInt("hbm_thermal_limit_c", limitC).apply();
            if (fromUser) {
                Intent i = new Intent(this, S9HelperService.class);
                i.setAction("REFRESH_HBM");
                startService(i);
            }
        } else if (id == ID_SEEK_HBM_WATT) {
            int mw = (10 + progress) * 100;
            hbmWattLabel.setText(String.format("OLED Power Cap: %.1f W (Display PMIC)", mw / 1000.0f));
            prefs.edit().putInt("hbm_watt_limit_mw", mw).apply();
            if (fromUser) {
                Intent i = new Intent(this, S9HelperService.class);
                i.setAction("REFRESH_HBM");
                startService(i);
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        int id = seekBar.getId();
        if (id == ID_SEEK_LED_BRIGHT || id == ID_SEEK_ABS_MIN || id == ID_SEEK_ABS_MAX) {
            RootShell.clearLedPattern();
        }
    }

    private void previewLedAtPct(int pct) {
        int clamped = Math.max(0, Math.min(100, pct));
        int c = S9HelperService.currentColor;
        RootShell.setS9RgbLed((Color.red(c) * clamped) / 100, (Color.green(c) * clamped) / 100, (Color.blue(c) * clamped) / 100);
    }

    private void updateLedSliderEnabledStates() {
        boolean pulseOn = prefs.getBoolean("volume_pulse_enabled", true);
        boolean absOn = pulseOn && prefs.getBoolean("led_abs_loudness", false);
        float switchAlpha = pulseOn ? 1.0f : 0.35f;
        float brightAlpha = absOn ? 0.35f : 1.0f;
        float absAlpha = absOn ? 1.0f : 0.35f;

        if (absLoudnessSwitch != null) {
            absLoudnessSwitch.setEnabled(pulseOn);
            absLoudnessSwitch.setAlpha(switchAlpha);
        }
        if (ledBrightLabel != null) ledBrightLabel.setAlpha(brightAlpha);
        if (brightSeek != null) {
            brightSeek.setEnabled(!absOn);
            brightSeek.setAlpha(brightAlpha);
        }
        if (absMinLabel != null) absMinLabel.setAlpha(absAlpha);
        if (absMinSeek != null) {
            absMinSeek.setEnabled(absOn);
            absMinSeek.setAlpha(absAlpha);
        }
        if (absMaxLabel != null) absMaxLabel.setAlpha(absAlpha);
        if (absMaxSeek != null) {
            absMaxSeek.setEnabled(absOn);
            absMaxSeek.setAlpha(absAlpha);
        }
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        int id = seekBar.getId();
        if (id == ID_SEEK_HBM_CAP || id == ID_SEEK_HBM_THERMAL || id == ID_SEEK_HBM_WATT) {
            Intent i = new Intent(this, S9HelperService.class);
            i.setAction("REFRESH_HBM");
            startService(i);
        }
    }

    private float getScreenCornerRadiusPx() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 5.0f, dm);
    }

    private TextView createCircleBadge(int sizePx, float textSp) {
        TextView tv = new TextView(this);
        tv.setText("i");
        tv.setTypeface(Typeface.SERIF, Typeface.BOLD);
        tv.setTextSize(textSp);
        tv.setTextColor(Color.parseColor("#C978CA"));
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.BLACK);
        circle.setStroke(3, Color.parseColor("#A84CA9"));
        tv.setBackground(circle);
        return tv;
    }

    private TextView createInfoIcon(int id, String contentDesc) {
        TextView iv = createCircleBadge(64, 13f);
        iv.setId(id);
        iv.setContentDescription(contentDesc);
        iv.setClickable(true);
        iv.setFocusable(true);
        iv.setOnClickListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(64, 64);
        lp.leftMargin = 16;
        lp.rightMargin = 20;
        iv.setLayoutParams(lp);
        return iv;
    }

    private LinearLayout createSwitchInfoRow(String labelText, Switch sw, int infoId, int padTop, int padBottom) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, padTop, 0, padBottom);

        TextView tv = new TextView(this);
        tv.setText(labelText);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        row.addView(tv);

        row.addView(createInfoIcon(infoId, "Info: " + labelText));

        sw.setText("");
        sw.setPadding(0, 0, 0, 0);
        sw.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(sw);
        return row;
    }

    private LinearLayout createLabelInfoRow(TextView labelView, int infoId, String desc, int padTop, int padBottom) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, padTop, 0, padBottom);

        labelView.setPadding(0, 0, 0, 0);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        row.addView(labelView);

        TextView infoIcon = createInfoIcon(infoId, "Info: " + desc);
        ((LinearLayout.LayoutParams) infoIcon.getLayoutParams()).rightMargin = 4;
        row.addView(infoIcon);
        return row;
    }

    private void showInfoDialog(int infoId) {
        String title;
        String whatItDoes;
        String recommended;
        if (infoId == ID_INFO_HBM_SLIDER) {
            title = "Unlock Full 720-nit System Slider";
            whatItDoes = "Unlocks hardware High Brightness Mode (up to 720 nits instead of Samsung's 400-nit manual limit) in the top 30% of the system brightness bar when Auto-Brightness is off. Calibrated specifically for Exynos Galaxy S9 / S9+ / Note 9 (Exynos 9810) panels; do not enable on S8 series.";
            recommended = "ON for easy on-demand outdoor brightness on S9 / Note 9 Exynos models; OFF on S8 / S8+ / Note 8.";
        } else if (infoId == ID_INFO_HBM_FORCE) {
            title = "Force MAX HBM Brightness Now";
            whatItDoes = "Immediately locks the display to your max brightness ceiling (still protected by active Safety Guards) and restores your previous brightness when switched off. Also available via the Max HBM Quick Settings tile.";
            recommended = "OFF daily; toggle ON only briefly when you need full brightness in direct sunlight.";
        } else if (infoId == ID_INFO_HBM_ACL) {
            title = "Disable OLED ACL White Dimming in HBM";
            whatItDoes = "Controls Samsung's hardware OLED Automatic Current Limiter during HBM. ON disables ACL (0) for ~15\u201320% brighter full-white areas at higher power and heat; OFF keeps stock ACL (5) active for lower power draw and less OLED wear. Thermal/Wattage Safety Guards still re-enable ACL automatically if limits are approached.";
            recommended = "OFF for battery life and longer OLED screen lifespan; ON only when maximum white legibility outdoors is needed.";
        } else if (infoId == ID_INFO_HBM_CAP) {
            title = "Unlocked Brightness Ceiling (400\u2013720 nits)";
            whatItDoes = "Sets the maximum nit ceiling that the unlocked system slider and Force MAX HBM switch can drive into the OLED panel (Exynos S9 / S9+ / Note 9 lookup scaling).";
            recommended = "600\u2013720 nits with Thermal & Wattage Safety Guards ON, or 550\u2013600 nits for gentler OLED wear.";
        } else if (infoId == ID_INFO_HBM_THERMAL) {
            title = "Thermal Safety Guard";
            whatItDoes = "Monitors both the battery thermistor and sec-ap-thermistor behind the display during HBM. Smoothly steps brightness down and forces ACL ON near your limit, pauses HBM at 400 nits at the limit, and drops to ~300 nits at +3\u00B0C critical heat with 2\u00B0C hysteresis to prevent flicker.";
            recommended = "ON with the default 42\u00B0C Batt / 46\u00B0C Board limit.";
        } else if (infoId == ID_INFO_HBM_WATT) {
            title = "OLED Wattage Safety Guard";
            whatItDoes = "Reads live OLED panel electrical power directly from the Exynos S9 / Note 9 display PMIC (s2dos05 power meter). Automatically enables ACL and smoothly scales brightness down if bright white screens exceed your Watt cap, while allowing dark/black UIs to run at full nits.";
            recommended = "ON with a 2.0\u20132.5 W cap (default 2.5 W).";
        } else if (infoId == ID_INFO_NO_TIMEOUT) {
            title = "Never Auto-Turn Off Screen";
            whatItDoes = "Disables Samsung's 10-minute screen-off timeout (sets 2147483647 ms \u2248 24.8 days + stay awake while charging) and enforces it on boot so the screen only turns off when you press the Power button. Turning OFF restores your prior timeout.";
            recommended = "ON only for dedicated navigation or kiosk use (press Power when idle to prevent static OLED burn-in); OFF for normal pocket use.";
        } else if (infoId == ID_INFO_AOD_FIX) {
            title = "Auto-Cycle Real AOD on Wake";
            whatItDoes = "Fixes the OLED green-tint / washed-out color bug on cold wake on rooted Exynos Galaxy S9 / S9+ / Note 9 (and experimental S8 / S8+ \u2014 not guaranteed) by briefly pulsing the panel through Always-On Display (DOZE) pre-charge whenever waking from OFF -> ON.";
            recommended = "ON if your screen shows green tint or washed colors when waking; OFF if unaffected.";
        } else if (infoId == ID_INFO_DIRECT_DOZE) {
            title = "Ultra-Fast Direct Hardware DOZE";
            whatItDoes = "Sends the hardware DOZE_ALPM pre-charge pulse directly through a persistent root daemon (~0.1\u20130.3s), skipping Samsung's slower full AOD lockscreen UI cycle. Falls back automatically if unavailable.";
            recommended = "ON for the fastest, cleanest wake color fix.";
        } else if (infoId == ID_INFO_AOD_HOLD) {
            title = "AOD Extra Hold After DOZE";
            whatItDoes = "Sets extra dwell time (0\u20131000 ms) inside hardware DOZE before waking to full ON. Longer hold gives aging OLED drivers more stabilization time; shorter wakes slightly faster.";
            recommended = "50 ms (0.05s) for most panels; raise to 100\u2013200 ms only if slight wake tint still appears.";
        } else {
            title = "Replacement Battery Calibration (Root)";
            whatItDoes = "Backs up and resets Samsung's /efs/FactoryApp/batt_discharge_level to 0 cycles and asoc to 100% health, then resets the fuel gauge SoC so the OS stops throttling or under-charging based on an old worn battery's counter.";
            recommended = "Tap once right after installing a brand-new replacement battery, then reboot and charge to 100%. Do NOT use on an old worn cell.";
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        float cardRadius = getScreenCornerRadiusPx();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(56, 52, 56, 48);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.BLACK);
        cardBg.setCornerRadius(cardRadius);
        cardBg.setStroke(4, Color.parseColor("#A84CA9"));
        card.setBackground(cardBg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 24);

        TextView badge = createCircleBadge(56, 13f);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(56, 56);
        badgeLp.rightMargin = 20;
        badge.setLayoutParams(badgeLp);
        header.addView(badge);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(18);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        header.addView(titleTv);
        card.addView(header);

        TextView whatHdr = new TextView(this);
        whatHdr.setText("WHAT IT DOES");
        whatHdr.setTextSize(12);
        whatHdr.setTypeface(Typeface.DEFAULT_BOLD);
        whatHdr.setTextColor(Color.parseColor("#C978CA"));
        whatHdr.setPadding(0, 0, 0, 8);
        card.addView(whatHdr);

        TextView whatTv = new TextView(this);
        whatTv.setText(whatItDoes);
        whatTv.setTextSize(14);
        whatTv.setLineSpacing(4f, 1.15f);
        whatTv.setTextColor(Color.parseColor("#DCE6F7"));
        whatTv.setPadding(0, 0, 0, 24);
        card.addView(whatTv);

        LinearLayout recBox = new LinearLayout(this);
        recBox.setOrientation(LinearLayout.VERTICAL);
        recBox.setPadding(28, 22, 28, 22);
        GradientDrawable recBg = new GradientDrawable();
        recBg.setColor(Color.BLACK);
        recBg.setCornerRadius(Math.max(24f, cardRadius - 48f));
        recBg.setStroke(2, Color.parseColor("#38D399"));
        recBox.setBackground(recBg);

        TextView recHdr = new TextView(this);
        recHdr.setText("RECOMMENDED");
        recHdr.setTextSize(12);
        recHdr.setTypeface(Typeface.DEFAULT_BOLD);
        recHdr.setTextColor(Color.parseColor("#88FFAA"));
        recHdr.setPadding(0, 0, 0, 6);
        recBox.addView(recHdr);

        TextView recTv = new TextView(this);
        recTv.setText(recommended);
        recTv.setTextSize(14);
        recTv.setLineSpacing(4f, 1.15f);
        recTv.setTextColor(Color.WHITE);
        recBox.addView(recTv);
        card.addView(recBox);

        Button okBtn = new Button(this);
        okBtn.setText("Got it");
        okBtn.setTextColor(Color.WHITE);
        okBtn.setAllCaps(false);
        okBtn.setOnClickListener(new DialogDismissClick(dialog));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        okLp.topMargin = 28;
        okBtn.setLayoutParams(okLp);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(Color.argb(180, 58, 82, 210));
        okBg.setCornerRadius(Math.max(24f, cardRadius - 52f));
        okBtn.setBackground(okBg);
        card.addView(okBtn);

        dialog.setContentView(card);
        dialog.show();
        try {
            Window dw = dialog.getWindow();
            if (dw != null) {
                dw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                DisplayMetrics dm = getResources().getDisplayMetrics();
                dw.setLayout(Math.round(dm.widthPixels * 0.90f), WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception ignored) {}
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(52, 46, 52, 46);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = 40;
        card.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(getScreenCornerRadiusPx());
        bg.setStroke(4, Color.parseColor("#A84CA9"));
        card.setBackground(bg);
        return card;
    }

    private Button createButton(String text, int id) {
        Button btn = new Button(this);
        btn.setId(id);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setOnClickListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = 20;
        btn.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(180, 58, 82, 210));
        bg.setCornerRadius(Math.max(24f, getScreenCornerRadiusPx() - 52f));
        btn.setBackground(bg);
        return btn;
    }

    private void notifyServicePrefsChanged() {
        Intent i = new Intent(this, S9HelperService.class);
        i.setAction("REFRESH_PREFS");
        startService(i);
    }

    private void syncSwitchState(Switch sw, boolean desired) {
        if (sw != null && sw.isChecked() != desired) {
            sw.setChecked(desired);
        }
    }

    void updateUiFromService() {
        updatingHbmUi = true;
        syncSwitchState(hbmSliderSwitch, prefs.getBoolean("hbm_slider_unlock", false));
        syncSwitchState(hbmForceSwitch, prefs.getBoolean("hbm_force_max", false));
        syncSwitchState(hbmAclSwitch, prefs.getBoolean("hbm_disable_acl", false));
        syncSwitchState(hbmThermalSwitch, prefs.getBoolean("hbm_thermal_enabled", true));
        syncSwitchState(hbmWattSwitch, prefs.getBoolean("hbm_watt_enabled", true));
        syncSwitchState(noTimeoutSwitch, prefs.getBoolean("no_screen_timeout", false));
        syncSwitchState(powerampOnlySwitch, prefs.getBoolean("led_poweramp_only", false));
        syncSwitchState(absLoudnessSwitch, prefs.getBoolean("led_abs_loudness", false));
        updatingHbmUi = false;

        updateLedSliderEnabledStates();
        renderHbmStatusFromCache();

        String titleStr = (S9HelperService.currentTrackTitle != null) ? S9HelperService.currentTrackTitle : "";
        if (!titleStr.equals(lastRenderedTrack)) {
            lastRenderedTrack = titleStr;
            trackView.setText(titleStr);
        }

        int c = S9HelperService.currentColor;
        if (!hasRenderedSwatch || c != lastRenderedSwatchColor) {
            lastRenderedSwatchColor = c;
            hasRenderedSwatch = true;
            String hex = String.format("#%02X%02X%02X (R:%d G:%d B:%d)",
                    Color.red(c), Color.green(c), Color.blue(c),
                    Color.red(c), Color.green(c), Color.blue(c));
            if (!hex.equals(lastRenderedHex)) {
                lastRenderedHex = hex;
                colorHexView.setText("Active Blur & LED Color:\n" + hex);
            }
            if (colorSwatchDrawable == null) {
                colorSwatchDrawable = new GradientDrawable();
                colorSwatchDrawable.setCornerRadius(24f);
                colorSwatchDrawable.setStroke(3, Color.WHITE);
                colorSwatchDrawable.setColor(c);
                colorPreviewBox.setBackground(colorSwatchDrawable);
            } else {
                colorSwatchDrawable.setColor(c);
                colorPreviewBox.invalidate();
            }
        }
    }

    private void requestAsyncTelemetryRefresh() {
        if (bgHandler != null && uiTeleFetchRunnable != null) {
            bgHandler.removeCallbacks(uiTeleFetchRunnable);
            bgHandler.post(uiTeleFetchRunnable);
        }
    }

    void runTelemetryFetchOnBg() {
        int[] t = RootShell.readPanelAndThermal();
        if (t[0] >= 0) S9HelperService.lastActualNits = t[0];
        if (t[1] >= 0) S9HelperService.lastApTemp10 = t[1];
        if (t[2] >= 0) S9HelperService.lastBattTemp10 = t[2];
        if (t[3] >= 0) S9HelperService.lastPanelMw = t[3];
        if (uiVisible && uiHandler != null && uiTeleApplyRunnable != null) {
            uiHandler.removeCallbacks(uiTeleApplyRunnable);
            uiHandler.post(uiTeleApplyRunnable);
        }
    }

    void onTelemetryFetchedOnUi() {
        if (!uiVisible) return;
        renderHbmStatusFromCache();
    }

    private void renderHbmStatusFromCache() {
        if (hbmStatusView == null) return;
        int nits = S9HelperService.lastActualNits;
        int ap10 = S9HelperService.lastApTemp10;
        int batt10 = S9HelperService.lastBattTemp10;
        int mw = S9HelperService.lastPanelMw;

        String wattStr = (mw >= 0) ? String.format(" | %.2f W OLED", mw / 1000.0f) : "";
        String tempStr = (batt10 >= 0 && ap10 >= 0)
                ? String.format(" | %.1f\u00B0C batt / %.1f\u00B0C board", batt10 / 10.0f, ap10 / 10.0f)
                : "";
        String line1 = "Panel: " + (nits >= 0 ? (nits + " nits") : "--") + " (Stock: 400)" + wattStr + tempStr;

        boolean thermOn = prefs.getBoolean("hbm_thermal_enabled", true);
        int st = S9HelperService.lastThermalStage;
        int eff = S9HelperService.lastEffectiveCapNits;
        String thermPart;
        if (!thermOn) {
            thermPart = "Thermal: OFF";
        } else if (st == 3) {
            thermPart = "Thermal: CRIT (" + eff + "n)";
        } else if (st == 2) {
            thermPart = "Thermal: HOT (400n)";
        } else if (st == 1) {
            thermPart = "Thermal: WARM (" + eff + "n)";
        } else {
            thermPart = "Thermal: SAFE";
        }

        boolean wattOn = prefs.getBoolean("hbm_watt_enabled", true);
        int limitMw = Math.max(1000, Math.min(4500, prefs.getInt("hbm_watt_limit_mw", 2500)));
        String wattPart;
        if (!wattOn) {
            wattPart = "Wattage: OFF";
        } else if (S9HelperService.lastWattThrottling) {
            wattPart = String.format("Wattage: CAP %dn (%.1f W)", S9HelperService.lastWattCapNits, limitMw / 1000.0f);
        } else {
            wattPart = String.format("Wattage: SAFE (%.1f W cap)", limitMw / 1000.0f);
        }

        int statusCol;
        if (thermOn && st == 3) {
            statusCol = Color.parseColor("#FF5252");
        } else if ((thermOn && st == 2) || (wattOn && S9HelperService.lastWattThrottling)) {
            statusCol = Color.parseColor("#FF9E40");
        } else if ((thermOn && st == 1) || nits > 400) {
            statusCol = Color.parseColor("#FFD166");
        } else {
            statusCol = Color.parseColor("#88FFAA");
        }

        String fullText = line1 + "\n" + thermPart + " | " + wattPart;
        if (!fullText.equals(lastHbmStatusText)) {
            lastHbmStatusText = fullText;
            hbmStatusView.setText(fullText);
        }
        if (statusCol != lastHbmStatusColor) {
            lastHbmStatusColor = statusCol;
            hbmStatusView.setTextColor(statusCol);
        }
    }

    void onLiveUiRefreshTick() {
        if (!uiVisible) return;
        requestAsyncTelemetryRefresh();
        uiHandler.postDelayed(uiRefreshRunnable, 1500L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiVisible = true;
        registerReceiver(uiReceiver, new IntentFilter(S9HelperService.ACTION_STATE_UPDATED));
        updateUiFromService();
        requestAsyncTelemetryRefresh();
        uiHandler.removeCallbacks(uiRefreshRunnable);
        uiHandler.postDelayed(uiRefreshRunnable, 1500L);
    }

    @Override
    protected void onPause() {
        uiVisible = false;
        if (uiHandler != null) {
            if (uiRefreshRunnable != null) uiHandler.removeCallbacks(uiRefreshRunnable);
            if (uiTeleApplyRunnable != null) uiHandler.removeCallbacks(uiTeleApplyRunnable);
        }
        if (bgHandler != null && uiTeleFetchRunnable != null) {
            bgHandler.removeCallbacks(uiTeleFetchRunnable);
        }
        try {
            unregisterReceiver(uiReceiver);
        } catch (Exception ignored) {}
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiVisible = false;
        if (uiHandler != null) {
            uiHandler.removeCallbacksAndMessages(null);
        }
        if (bgHandler != null) {
            bgHandler.removeCallbacksAndMessages(null);
        }
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
        }
        super.onDestroy();
    }
}
