package com.s9plus.helper;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

public class RootShell {
    public static final int HW_NORMAL_MAX = 25500;
    public static final int HW_HBM_MAX = 44800;
    private static final Object EXEC_LOCK = new Object();
    private static final Object DOZE_LOCK = new Object();
    private static final Object QUERY_LOCK = new Object();

    private static Process suProcess;
    private static DataOutputStream suIn;
    private static boolean hasRoot = false;
    private static int lastInR = -1, lastInG = -1, lastInB = -1;

    private static Process dozeProc;
    private static DataOutputStream dozeIn;
    private static BufferedReader dozeOut;

    private static Process queryProc;
    private static DataOutputStream queryIn;
    private static BufferedReader queryOut;

    public static boolean ensureRoot() {
        synchronized (EXEC_LOCK) {
            try {
                if (suProcess != null) {
                    try {
                        suProcess.exitValue();
                        suProcess = null;
                    } catch (IllegalThreadStateException running) {
                        return hasRoot;
                    }
                }
                suProcess = Runtime.getRuntime().exec("su");
                suIn = new DataOutputStream(suProcess.getOutputStream());
                suIn.writeBytes("id\n");
                suIn.flush();
                BufferedReader br = new BufferedReader(new InputStreamReader(suProcess.getInputStream()));
                String line = br.readLine();
                hasRoot = (line != null && line.contains("uid=0"));
                return hasRoot;
            } catch (Exception e) {
                hasRoot = false;
                return false;
            }
        }
    }

    public static void execFast(String cmd) {
        synchronized (EXEC_LOCK) {
            try {
                if (suProcess == null || suIn == null) {
                    if (!ensureRoot()) return;
                }
                suIn.writeBytes(cmd + "\n");
                suIn.flush();
            } catch (Exception e) {
                suProcess = null;
                suIn = null;
            }
        }
    }

    public static void powerSleep(long uptimeMs) {
        execFast("input keyevent 223 >/dev/null 2>&1");
    }

    public static void powerWake(long uptimeMs) {
        execFast("input keyevent 224 >/dev/null 2>&1");
    }

    public static boolean ensureDozeDaemon(String apkPath) {
        synchronized (DOZE_LOCK) {
            try {
                if (dozeProc != null) {
                    try {
                        dozeProc.exitValue();
                        dozeProc = null;
                        dozeIn = null;
                        dozeOut = null;
                    } catch (IllegalThreadStateException running) {
                        return dozeIn != null && dozeOut != null;
                    }
                }
                String cmd = "CLASSPATH='" + apkPath + "' exec app_process /system/bin com.s9plus.helper.DisplayDozeDaemon";
                dozeProc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                dozeIn = new DataOutputStream(dozeProc.getOutputStream());
                dozeOut = new BufferedReader(new InputStreamReader(dozeProc.getInputStream()));
                String ready = dozeOut.readLine();
                if (ready != null && ready.trim().equals("READY")) {
                    return true;
                }
                stopDozeDaemon();
                return false;
            } catch (Exception e) {
                stopDozeDaemon();
                return false;
            }
        }
    }

    public static long pulseHardwareDoze(String apkPath, String modeCmd, int holdMs) {
        synchronized (DOZE_LOCK) {
            try {
                if (!ensureDozeDaemon(apkPath)) {
                    return -1L;
                }
                dozeIn.writeBytes(modeCmd + " " + Math.max(0, holdMs) + "\n");
                dozeIn.flush();
                String resp = dozeOut.readLine();
                if (resp != null && resp.startsWith("OK ")) {
                    return Long.parseLong(resp.substring(3).trim());
                }
                stopDozeDaemon();
                return -1L;
            } catch (Exception e) {
                stopDozeDaemon();
                return -1L;
            }
        }
    }

    public static void stopDozeDaemon() {
        synchronized (DOZE_LOCK) {
            try {
                if (dozeIn != null) {
                    dozeIn.writeBytes("QUIT\n");
                    dozeIn.flush();
                    dozeIn.close();
                }
            } catch (Exception ignored) {}
            try {
                if (dozeOut != null) dozeOut.close();
            } catch (Exception ignored) {}
            try {
                if (dozeProc != null) dozeProc.destroy();
            } catch (Exception ignored) {}
            dozeProc = null;
            dozeIn = null;
            dozeOut = null;
        }
    }

    public static void setS9RgbLed(int r255, int g255, int b255) {
        synchronized (EXEC_LOCK) {
            r255 = Math.max(0, Math.min(255, r255));
            g255 = Math.max(0, Math.min(255, g255));
            b255 = Math.max(0, Math.min(255, b255));

            // On S9+ (star2lte), normal mode multiplies R by 700% (7x -> u8 overflow at 37!),
            // G by 60% (0.6x), B by 120% (1.2x), while lowpower mode uses the same 0x0a current
            // with R ratio = 100% (1.0x). Using lowpower=1 for R (0..255 -> 0..255) and lowpower=0
            // for G (0..37 -> 0..22) and B (0..37 -> 0..44) gives exact 700:60:120 Samsung white
            // balance with 256 smooth red steps and zero u8 overflow at any brightness (0..100%).
            int inR = r255;
            int targetHwG = Math.round((g255 * 22.0f) / 255.0f);
            int inG = (targetHwG <= 0) ? 0 : Math.min(37, (targetHwG * 100 + 59) / 60);
            int inB = Math.min(37, Math.round((b255 * 37.0f) / 255.0f));

            if (inR == lastInR && inG == lastInG && inB == lastInB) {
                return;
            }
            lastInR = inR;
            lastInG = inG;
            lastInB = inB;

            String cmd = String.format(
                "echo 1 > /sys/class/sec/led/led_lowpower 2>/dev/null; " +
                "echo %d > /sys/class/sec/led/led_r 2>/dev/null; " +
                "echo 0 > /sys/class/sec/led/led_lowpower 2>/dev/null; " +
                "echo %d > /sys/class/sec/led/led_g 2>/dev/null; " +
                "echo %d > /sys/class/sec/led/led_b 2>/dev/null",
                inR, inG, inB
            );
            execFast(cmd);
        }
    }

    public static void clearLedPattern() {
        synchronized (EXEC_LOCK) {
            lastInR = -1;
            lastInG = -1;
            lastInB = -1;
            execFast("echo 0 > /sys/class/sec/led/led_pattern 2>/dev/null; " +
                     "echo 0x000000 0 0 > /sys/class/sec/led/led_blink 2>/dev/null");
        }
    }

    public static void turnOffLed() {
        synchronized (EXEC_LOCK) {
            lastInR = 0;
            lastInG = 0;
            lastInB = 0;
            execFast("echo 0 > /sys/class/sec/led/led_r 2>/dev/null; " +
                     "echo 0 > /sys/class/sec/led/led_g 2>/dev/null; " +
                     "echo 0 > /sys/class/sec/led/led_b 2>/dev/null; " +
                     "echo 0 > /sys/class/sec/led/led_pattern 2>/dev/null; " +
                     "echo 0x000000 0 0 > /sys/class/sec/led/led_blink 2>/dev/null");
        }
    }

    public static void setPanelBrightness(int hwVal) {
        synchronized (EXEC_LOCK) {
            int clamped = Math.max(0, Math.min(HW_HBM_MAX, hwVal));
            execFast("echo " + clamped + " > /sys/class/backlight/panel/brightness 2>/dev/null");
        }
    }

    public static void setPanelAcl(boolean disableAcl) {
        synchronized (EXEC_LOCK) {
            execFast("echo " + (disableAcl ? "0" : "5") + " > /sys/class/lcd/panel/adaptive_control 2>/dev/null");
        }
    }

    public static void setPanelHbmState(int hwVal, boolean disableAcl) {
        synchronized (EXEC_LOCK) {
            int clamped = Math.max(0, Math.min(HW_HBM_MAX, hwVal));
            execFast(
                "echo " + (disableAcl ? "0" : "5") + " > /sys/class/lcd/panel/adaptive_control 2>/dev/null; " +
                "echo " + clamped + " > /sys/class/backlight/panel/brightness 2>/dev/null"
            );
        }
    }

    private static boolean ensureQueryShell() {
        synchronized (QUERY_LOCK) {
            try {
                if (queryProc != null) {
                    try {
                        queryProc.exitValue();
                        queryProc = null;
                    } catch (IllegalThreadStateException running) {
                        return queryIn != null && queryOut != null;
                    }
                }
                queryProc = Runtime.getRuntime().exec("su");
                queryIn = new DataOutputStream(queryProc.getOutputStream());
                queryOut = new BufferedReader(new InputStreamReader(queryProc.getInputStream()));
                queryIn.writeBytes("echo READY\n");
                queryIn.flush();
                String line = queryOut.readLine();
                if (line != null && line.trim().equals("READY")) {
                    return true;
                }
                stopQueryShell();
                return false;
            } catch (Exception e) {
                stopQueryShell();
                return false;
            }
        }
    }

    public static void stopQueryShell() {
        synchronized (QUERY_LOCK) {
            try {
                if (queryIn != null) {
                    queryIn.writeBytes("exit\n");
                    queryIn.flush();
                    queryIn.close();
                }
            } catch (Exception ignored) {}
            try {
                if (queryOut != null) queryOut.close();
            } catch (Exception ignored) {}
            try {
                if (queryProc != null) queryProc.destroy();
            } catch (Exception ignored) {}
            queryProc = null;
            queryIn = null;
            queryOut = null;
        }
    }

    public static int[] readPanelAndThermal() {
        int[] res = new int[]{-1, -1, -1, -1};
        synchronized (QUERY_LOCK) {
            try {
                if (!ensureQueryShell()) return res;
                queryIn.writeBytes(
                    "A=-1; T=-1; B=-1; P=-1; " +
                    "[ -r /sys/class/backlight/panel/actual_brightness ] && read -r A < /sys/class/backlight/panel/actual_brightness; " +
                    "[ -r /sys/class/sec/sec-ap-thermistor/temperature ] && read -r T < /sys/class/sec/sec-ap-thermistor/temperature; " +
                    "[ -r /sys/class/power_supply/battery/temp ] && read -r B < /sys/class/power_supply/battery/temp; " +
                    "if [ -r /sys/class/sec/disp_pmic/power_meter/adc_val_all ]; then P=0; " +
                    "while read -r L || [ -n \"$L\" ]; do for w in $L; do " +
                    "case \"$w\" in CH[0-7]:*) v=${w#CH?:}; case \"$v\" in ''|*[!0-9]*) ;; *) P=$((P + v));; esac;; esac; " +
                    "done; done < /sys/class/sec/disp_pmic/power_meter/adc_val_all; P=$(((P + 500) / 1000)); fi; " +
                    "echo \"$A $T $B $P\"\n"
                );
                queryIn.flush();
                String line = queryOut.readLine();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    for (int i = 0; i < 4 && i < parts.length; i++) {
                        try {
                            res[i] = Integer.parseInt(parts[i]);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                stopQueryShell();
            }
        }
        return res;
    }

    public static int readPanelActualNits() {
        synchronized (QUERY_LOCK) {
            try {
                if (!ensureQueryShell()) return -1;
                queryIn.writeBytes(
                    "A=-1; [ -r /sys/class/backlight/panel/actual_brightness ] && read -r A < /sys/class/backlight/panel/actual_brightness; echo \"$A\"\n"
                );
                queryIn.flush();
                String line = queryOut.readLine();
                if (line != null) {
                    return Integer.parseInt(line.trim());
                }
            } catch (Exception e) {
                stopQueryShell();
            }
            return -1;
        }
    }

    public static boolean resetBatteryCycles() {
        if (!ensureRoot()) return false;
        execFast(
            "cp /efs/FactoryApp/batt_discharge_level /efs/FactoryApp/batt_discharge_level.bak 2>/dev/null; " +
            "cp /efs/FactoryApp/asoc /efs/FactoryApp/asoc.bak 2>/dev/null; " +
            "echo -n 0 > /efs/FactoryApp/batt_discharge_level 2>/dev/null; " +
            "echo -n 100 > /efs/FactoryApp/asoc 2>/dev/null; " +
            "chmod 660 /efs/FactoryApp/batt_discharge_level /efs/FactoryApp/asoc 2>/dev/null; " +
            "echo 1 > /sys/class/power_supply/battery/fg_reset_soc 2>/dev/null; " +
            "echo 1 > /sys/class/power_supply/battery/batt_reset_soc 2>/dev/null"
        );
        return true;
    }
}
