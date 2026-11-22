package com.s9plus.helper;

import android.graphics.Bitmap;
import android.graphics.Color;

public class AlbumColorExtractor {

    public static int extractPowerampBlurColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return Color.rgb(70, 95, 235); // Default Fear Inoculum blue/purple
        }

        Bitmap small = Bitmap.createScaledBitmap(bitmap, 24, 24, true);
        long sumR = 0, sumG = 0, sumB = 0;
        long totalWeight = 0;
        int nonBlackCount = 0;
        int coloredCount = 0;

        // Sample primarily the lower 65% where Poweramp's player controls blur sits
        for (int y = 6; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int pixel = small.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                float[] hsv = new float[3];
                Color.RGBToHSV(r, g, b, hsv);

                // Ignore very dark pixels
                if (hsv[2] < 0.15f) {
                    continue;
                }
                nonBlackCount++;

                // Count genuinely colorful pixels separately so monochrome/white covers don't get tinted red
                if (hsv[1] >= 0.18f) {
                    coloredCount++;
                    int weight = (int) (10 + (hsv[1] * 100) + (hsv[2] * 40));
                    sumR += (long) r * weight;
                    sumG += (long) g * weight;
                    sumB += (long) b * weight;
                    totalWeight += weight;
                }
            }
        }

        // Predominantly black, white, grey, or monochrome cover -> glow clean White
        if (nonBlackCount == 0 || coloredCount < nonBlackCount * 0.12f || totalWeight == 0) {
            return Color.rgb(255, 255, 255);
        }

        int avgR = (int) (sumR / totalWeight);
        int avgG = (int) (sumG / totalWeight);
        int avgB = (int) (sumB / totalWeight);

        float[] hsv = new float[3];
        Color.RGBToHSV(avgR, avgG, avgB, hsv);
        if (hsv[1] < 0.16f) {
            return Color.rgb(255, 255, 255);
        }

        // Boost saturation & value for vibrant colored covers
        hsv[1] = Math.min(1.0f, Math.max(0.65f, hsv[1] * 1.45f));
        hsv[2] = 1.0f;
        return Color.HSVToColor(hsv);
    }

    public static Bitmap createBlurredBackground(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        // Fast multi-pass downscale + upscale box blur
        Bitmap tiny = Bitmap.createScaledBitmap(src, 16, 16, true);
        return Bitmap.createScaledBitmap(tiny, 128, 128, true);
    }
}
