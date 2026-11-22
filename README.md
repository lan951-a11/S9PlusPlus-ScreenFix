# S9++ (ScreenFix)

An open-source, unencrypted Android root utility. **Important:** the screen fix only works on Galaxy **9 series** phones (**S9 / S9+ / Note 9**, plus experimental S8 support) whose **specific mode of failure is needing Always On Display (AOD) first before turning on in order to display the full color gamut** (without green tint or washed-out colors) — along with a whole lot of other display and comfort features including **HBM 720-nit brightness unlock** with thermal and display-PMIC wattage safety guards, a **Music-synced RGB Notification LED**, a no-timeout override, and **replacement battery calibration**.

> [!NOTE]
> **100% AI-Coded — Use at Your Own Risk:** This project was **100% AI-coded**. What works on my device might not work on yours—different panels, firmware versions, kernels, and hardware degradation states can behave differently. Always back up important data before making root or `/efs` changes.

> [!CAUTION]
> **ROOTED & EXYNOS MODELS ONLY**
> - This app **requires root access** (Magisk or KernelSU) and interacts directly with Samsung **Exynos** kernel sysfs nodes and Android's internal `SurfaceControl` display power modes.
> - **Qualcomm / Snapdragon variants and unrooted phones are NOT supported** and will not work.

> [!WARNING]
> **Galaxy S8 / S8+ / Note 8 Support Is Experimental (NOT Guaranteed)**
> - Built and verified primarily for **Exynos Galaxy S9 / S9+ / Note 9** (`Exynos 9810`).
> - Cross-Android-version fallbacks (Android 9 Pie `SurfaceControl.getBuiltInDisplay(0)` and Android 14+ `DisplayControl`) are included so you can try the AOD screen fix on **Exynos Galaxy S8 / S8+ / Note 8** (`Exynos 8895`), **but working behavior is NOT guaranteed on S8 panels**.
> - **HBM 720-nit unlock & Display PMIC wattage limiting are calibrated specifically for S9 / S9+ / Note 9 panels (`44800` LUT / `s2dos05`). Do NOT enable HBM unlock on S8-series phones.**
> - Software AOD pre-charge pulses only work on OLED panels whose green tint recovers when woken through Always On Display. Severely degraded flex cables or heat-damaged panels (e.g., failing when warm) still require physical display repair.

---

## Features (In UI Order)

### 1. Automatic AOD Screen Color Fix
- **Auto-Cycle Real AOD on Wake (`OFF -> ON`):** Briefly pulses the OLED panel through hardware Always On Display (`DOZE`) pre-charge whenever waking from screen-off so subpixels initialize cleanly without green tint or crushed/washed colors.
- **Direct Hardware DOZE Mode (`~0.1–0.3s`):** Uses a persistent root `app_process` helper (`DisplayDozeDaemon`) calling `SurfaceControl.setDisplayPowerMode` + `/sys/class/lcd/panel/alpm` directly to skip Samsung's slower lockscreen AOD UI cycle, with automatic fallback if unavailable.
- **AOD Extra Hold Slider (`0–1000 ms`):** Adjusts dwell time in hardware DOZE before waking to full ON (default `50 ms`) for aging panels that need more stabilization time.

### 2. Display Max Brightness Unlock (HBM 720 nits — S9 / Note 9 Exynos Only)
*(Off by default on fresh install until enabled by user.)*
- **Unlocked System Slider:** Extends manual brightness up to **720 nits** in the top 30% of the Android slider (instead of Samsung's stock 400-nit manual ceiling).
- **Force MAX HBM & Quick Settings Tile (`Max HBM`):** One-tap outdoor max-brightness boost with previous brightness restoration.
- **ACL Control:** Optional toggle to disable OLED Automatic Current Limiter during HBM (`adaptive_control`).
- **Thermal Safety Guard:** Monitors battery & board thermistors behind the panel with automatic step-down and hysteresis.
- **OLED Wattage Safety Guard:** Reads live display PMIC power (`/sys/class/sec/disp_pmic/power_meter/adc_val_all`) and smoothly scales HBM brightness to stay under your chosen Watt cap while leaving dark/black UIs unrestricted.
- **Never Auto-Turn Off Screen:** Optional kiosk/navigation toggle to disable the 10-minute screen timeout (off by default).

### 3. Music RGB Notification LED
*(Off by default on fresh install until enabled by user.)*
- Extracts the blurred dominant accent color from currently playing album art across **all music apps** (`MediaNotificationListener`) or **Poweramp only** (`Poweramp Only` toggle).
- Drives the front RGB notification LED with proper Exynos channel scaling: Solid mode, Relative Dynamic VU mode, or **Absolute Loudness** mode with manual Min (`0–95%`) and Max (`5–100%`) brightness bounds, adjustable swing (`50–1000%`), and polling rate (`2–60 Hz`).

### 4. Replacement Battery Calibration (Root)
- Backs up and resets `/efs/FactoryApp/batt_discharge_level` (`0`) and `asoc` (`100`) and triggers fuel-gauge SoC reset after installing a brand-new replacement battery. *(Do not run on an old worn battery.)*

---

## Supported Devices & Android Versions

| Device Family | SoC | Stock OS | Custom ROMs | Support Status |
| :--- | :--- | :--- | :--- | :--- |
| **Galaxy S9** (`SM-G960F/FD/N`, `starlte`) | Exynos 9810 | Android 10 (One UI 2.5) | Android 11–14+ | **Supported** (AOD Fix, HBM, RGB LED, Battery) |
| **Galaxy S9+** (`SM-G965F/FD/N`, `star2lte`) | Exynos 9810 | Android 10 (One UI 2.5) | Android 11–14+ | **Primary / Verified** (All features) |
| **Galaxy Note 9** (`SM-N960F/FD/N`, `crownlte`) | Exynos 9810 | Android 10 (One UI 2.5) | Android 11–14+ | **Supported** (AOD Fix, HBM, RGB LED, Battery) |
| **Galaxy S8 / S8+ / Note 8** (`SM-G950F`, `SM-G955F`, `SM-N950F`) | Exynos 8895 | Android 9 Pie | Android 10–14+ | **Experimental / NOT Guaranteed** (Try AOD Fix only; do not enable S9 HBM unlock) |
| Snapdragon / US / Unrooted variants (`*U`, `*U1`, `*W`, `*0`) | Qualcomm | Any | Any | **Not Supported** |

---

## Installation & Setup

1. Ensure your Exynos Samsung phone is **rooted with Magisk or KernelSU**.
2. Install `S9PlusPlus-v1.0.0.apk` from GitHub Releases.
3. Open **S9++** and grant **Superuser (Root)** permission when prompted.
4. Test the screen color fix using **"Test Automatic AOD Wake Cycle Now"**, then toggle **"Auto-Cycle Real AOD on Power Button Wake"** ON/OFF as needed.
5. *(Optional)* For **Music RGB Notification LED**, grant Audio (`RECORD_AUDIO`) permission and tap **"Grant Notification Access"**.
6. *(Optional)* Add the **`Max HBM`** Quick Settings tile on S9 / Note 9 Exynos devices.

---

## Building from Source (100% Open, Unencrypted & Unobfuscated)

Nothing in this repository is encrypted, packed, or obfuscated. All Java classes compile directly with debug symbols (`javac -g -source 8 -target 8`) and standard `d8` dexing (`--min-api 26`, zero ProGuard/R8 shrinking).

### Requirements
- JDK 8–21 (`javac`, `jar`, `keytool`, `java`)
- Android SDK `build-tools` (tested with Android 14 build-tools: `aapt2`, `aapt`, `zipalign`, `d8.jar`, `apksigner.jar`)
- Android SDK `platforms\android-29` (or `android-29`+ `android.jar`)

### Build on Windows (PowerShell)

```powershell
# Using ANDROID_HOME and JAVA_HOME environment variables:
powershell -ExecutionPolicy Bypass -File .\build.ps1

# Or specify tool paths explicitly:
powershell -ExecutionPolicy Bypass -File .\build.ps1 `
  -SdkBuildTools "C:\Android\Sdk\build-tools\34.0.0" `
  -AndroidJar "C:\Android\Sdk\platforms\android-29\android.jar" `
  -JdkBin "C:\Program Files\Java\jdk-21\bin"
```

The signed APK is output to `releases\S9PlusPlus-v1.0.0.apk` (a local signing keystore is automatically generated under `signing\release.keystore` on first run and excluded via `.gitignore`).

---

## Open Source, Modifications & License (GNU GPLv3)

This project is released under the **GNU General Public License v3.0 (GPL-3.0)** — see [LICENSE](LICENSE).

**Everyone is explicitly welcome to:**
- Read, inspect, modify, and improve any part of the source code (nothing is encrypted or obfuscated).
- Fork the project or release modified versions **under your own name**.

**Under the GPL-3.0 terms, you MUST:**
- Keep all distributed source code and modified derivatives **100% free** (free software, libre source access, no proprietary lockdown).
- Keep the complete corresponding source code open, unencrypted, and licensed under **GPL-3.0** whenever you distribute builds or modified APKs.
