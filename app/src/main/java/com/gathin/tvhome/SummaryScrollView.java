package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** Keeps every word in the layout while bounding the reading area above the shelf. */
public final class SummaryScrollView extends ScrollView {
    private int maximumHeight;

    public SummaryScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        maximumHeight = Math.round(130 * getResources().getDisplayMetrics().density);
        setFocusable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    void setMaximumHeight(int height) {
        height = Math.max(Math.round(30 * getResources().getDisplayMetrics().density), height);
        if (maximumHeight != height) { maximumHeight = height; requestLayout(); }
    }

    boolean moveReadingPosition(int direction) {
        if (!canScrollVertically(direction)) return false;
        int limit = Math.max(0, getChildAt(0).getHeight() - getHeight());
        // Immediate small steps make repeat keys predictable and avoid competing smooth scrolls.
        scrollTo(0, Math.max(0, Math.min(limit, getScrollY()
                + direction * Math.max(1, Math.round(getHeight() * .65f)))));
        awakenScrollBars();
        return true;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maximumHeight, MeasureSpec.AT_MOST));
    }
}
