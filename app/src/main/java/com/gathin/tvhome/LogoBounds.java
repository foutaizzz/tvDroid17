package com.gathin.tvhome;

/** Bounds of visible pixels, excluding only the fully transparent margin. */
final class LogoBounds {
    static int[] find(int[] pixels, int width, int height) {
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] >>> 24) == 0) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        return right < left ? null : new int[]{left, top, right - left + 1, bottom - top + 1};
    }
}
