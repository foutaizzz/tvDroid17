package com.gathin.tvhome;

/** Explicit remote paths through the asymmetric Apple-style control centre. */
final class ControlNavigation {
    static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;
    // Index 9 (HOME) was removed from the panel: no link may point at it, or requestFocus()
    // would crash on a null button. Layout: POWER(0) tall left, WIFI(2)/SETTINGS(1) right,
    // AUDIO(3)/TIMER(4) lower row, circles DEVICES(5) CAPTIONS(6) SOURCES(7) APPS(8),
    // CONTROL(10) proxy sits on the capsule above the panel.
    private static final int[][] LINKS = {
            {0, 0, 0, 3, 3, 5, 5, 6, 7, 8, 2},
            {2, 1, 10, 4, 4, 6, 7, 8, 8, 8, 10},
            {10, 2, 10, 0, 1, 3, 3, 4, 4, 8, 10},
            {3, 4, 1, 5, 7, 5, 6, 7, 8, 8, 2}
    };

    static int next(int index, int direction) { return LINKS[direction][index]; }
}
