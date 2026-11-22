package com.s9plus.helper;

import android.os.IBinder;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class DisplayDozeDaemon {
    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_DOZE = 1;
    private static final int POWER_MODE_NORMAL = 2;

    private static final String PATH_BRT = "/sys/class/backlight/panel/brightness";
    private static final String PATH_ACTUAL_BRT = "/sys/class/backlight/panel/actual_brightness";
    private static final String PATH_ALPM = "/sys/class/lcd/panel/alpm";

    private static IBinder displayToken;
    private static Method setDisplayPowerModeMethod;

    private static boolean initSurfaceControl() throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        setDisplayPowerModeMethod = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
        displayToken = null;

        // 1. Android 10-13 (stock S9/S9+/Note 9 & Android 10-13 custom ROMs)
        try {
            Method getToken = sc.getMethod("getInternalDisplayToken");
            displayToken = (IBinder) getToken.invoke(null);
        } catch (Throwable ignored) {}

        // 2. Android 8-9 Pie (stock S8/S8+/Note 8)
        if (displayToken == null) {
            try {
                Method getBuiltIn = sc.getMethod("getBuiltInDisplay", int.class);
                displayToken = (IBinder) getBuiltIn.invoke(null, 0);
            } catch (Throwable ignored) {}
        }

        // 3. Android 14+ custom ROMs via com.android.server.display.DisplayControl
        if (displayToken == null) {
            try {
                Class<?> dc = Class.forName("com.android.server.display.DisplayControl");
                Method getIds = dc.getMethod("getPhysicalDisplayIds");
                long[] ids = (long[]) getIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    Method getTokenById = dc.getMethod("getPhysicalDisplayToken", long.class);
                    displayToken = (IBinder) getTokenById.invoke(null, ids[0]);
                }
            } catch (Throwable ignored) {}
        }

        // 4. Fallback via SurfaceControl physical display IDs
        if (displayToken == null) {
            try {
                Method getIds = sc.getMethod("getPhysicalDisplayIds");
                long[] ids = (long[]) getIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    Method getTokenById = sc.getMethod("getPhysicalDisplayToken", long.class);
                    displayToken = (IBinder) getTokenById.invoke(null, ids[0]);
                }
            } catch (Throwable ignored) {}
        }

        return displayToken != null && setDisplayPowerModeMethod != null;
    }

    private static String readSysfs(String path) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(path);
            byte[] buf = new byte[128];
            int n = fis.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n).trim();
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
        }
    }

    private static void writeSysfs(String path, String value) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(path);
            fos.write((value + "\n").getBytes());
            fos.flush();
        } catch (Exception ignored) {
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private static void setMode(int mode) throws Exception {
        setDisplayPowerModeMethod.invoke(null, displayToken, mode);
    }

    private static long runPulse(boolean viaOff, int holdMs, boolean forceAlpm) throws Exception {
        long t0 = System.nanoTime();
        boolean hasBrt = new File(PATH_BRT).exists();
        boolean hasAlpm = forceAlpm && new File(PATH_ALPM).exists();
        String origBrt = hasBrt ? readSysfs(PATH_BRT) : "";
        if (hasAlpm) {
            writeSysfs(PATH_ALPM, "2");
        }
        if (viaOff) {
            setMode(POWER_MODE_OFF);
        }
        setMode(POWER_MODE_DOZE);
        if (holdMs > 0) {
            Thread.sleep(holdMs);
        }
        setMode(POWER_MODE_NORMAL);
        if (hasAlpm) {
            writeSysfs(PATH_ALPM, "0");
        }

        if (hasBrt) {
            int origVal = -1;
            try {
                origVal = Integer.parseInt(origBrt);
            } catch (NumberFormatException ignored) {}
            if (origVal > 0) {
                String nowBrt = readSysfs(PATH_BRT);
                if (!origBrt.equals(nowBrt)) {
                    writeSysfs(PATH_BRT, origBrt);
                }
            }
        }
        return (System.nanoTime() - t0) / 1000000L;
    }

    private static void runSelfTest() {
        try {
            if (!initSurfaceControl()) {
                System.out.println("TEST_FAIL: null displayToken or method");
                return;
            }
            String origBrt = readSysfs(PATH_BRT);
            String origAct = readSysfs(PATH_ACTUAL_BRT);
            String origAlpm = readSysfs(PATH_ALPM);
            System.out.println("BEFORE: brt=" + origBrt + " actual=" + origAct + " alpm=" + origAlpm);

            // Mode A: NORMAL -> DOZE -> NORMAL
            long tA0 = System.nanoTime();
            setMode(POWER_MODE_DOZE);
            String alpmA = readSysfs(PATH_ALPM);
            String brtA = readSysfs(PATH_BRT);
            Thread.sleep(50);
            setMode(POWER_MODE_NORMAL);
            String afterBrtA = readSysfs(PATH_BRT);
            if (!origBrt.isEmpty() && !origBrt.startsWith("ERR:") && !"0".equals(origBrt) && !origBrt.equals(afterBrtA)) {
                writeSysfs(PATH_BRT, origBrt);
            }
            long msA = (System.nanoTime() - tA0) / 1000000L;
            System.out.println("MODE_A (NORMAL->DOZE->NORMAL): elapsedMs=" + msA
                + " alpmDuringDoze=" + alpmA + " brtDuringDoze=" + brtA
                + " brtAfter=" + afterBrtA + " brtRestored=" + readSysfs(PATH_BRT));

            Thread.sleep(500);

            // Mode B: NORMAL -> OFF -> DOZE -> NORMAL
            long tB0 = System.nanoTime();
            setMode(POWER_MODE_OFF);
            setMode(POWER_MODE_DOZE);
            String alpmB = readSysfs(PATH_ALPM);
            String brtB = readSysfs(PATH_BRT);
            Thread.sleep(50);
            setMode(POWER_MODE_NORMAL);
            String afterBrtB = readSysfs(PATH_BRT);
            if (!origBrt.isEmpty() && !origBrt.startsWith("ERR:") && !"0".equals(origBrt) && !origBrt.equals(afterBrtB)) {
                writeSysfs(PATH_BRT, origBrt);
            }
            long msB = (System.nanoTime() - tB0) / 1000000L;
            System.out.println("MODE_B (OFF->DOZE->NORMAL): elapsedMs=" + msB
                + " alpmDuringDoze=" + alpmB + " brtDuringDoze=" + brtB
                + " brtAfter=" + afterBrtB + " brtRestored=" + readSysfs(PATH_BRT));
        } catch (Throwable t) {
            t.printStackTrace(System.out);
        }
        System.out.flush();
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0 && "--test".equals(args[0])) {
            runSelfTest();
            return;
        }

        try {
            if (!initSurfaceControl()) {
                System.out.println("ERR init_failed");
                System.out.flush();
                return;
            }
            System.out.println("READY");
            System.out.flush();

            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if ("QUIT".equals(line)) {
                    System.out.println("BYE");
                    System.out.flush();
                    break;
                }
                String[] parts = line.split("\\s+");
                String cmd = parts[0];
                int holdMs = 50;
                if (parts.length > 1) {
                    try {
                        holdMs = Math.max(0, Math.min(2000, Integer.parseInt(parts[1])));
                    } catch (NumberFormatException ignored) {}
                }
                try {
                    if ("DOZE".equals(cmd)) {
                        long ms = runPulse(false, holdMs, false);
                        System.out.println("OK " + ms);
                    } else if ("OFFDOZE".equals(cmd)) {
                        long ms = runPulse(true, holdMs, false);
                        System.out.println("OK " + ms);
                    } else if ("DOZE_ALPM".equals(cmd)) {
                        long ms = runPulse(false, holdMs, true);
                        System.out.println("OK " + ms);
                    } else {
                        System.out.println("ERR unknown_cmd");
                    }
                } catch (Throwable t) {
                    System.out.println("ERR " + t.getClass().getSimpleName() + ":" + t.getMessage());
                }
                System.out.flush();
            }
        } catch (Throwable t) {
            System.out.println("ERR " + t.getClass().getSimpleName() + ":" + t.getMessage());
            System.out.flush();
        }
    }
}
