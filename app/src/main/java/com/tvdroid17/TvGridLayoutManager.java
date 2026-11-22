package com.tvdroid17;

import android.content.Context;
import android.view.View;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** The home owns focus scrolling; its collapsed top inset is not a focus-safe viewport. */
final class TvGridLayoutManager extends GridLayoutManager {
    private final int nextRow;
    TvGridLayoutManager(Context context,int columns){super(context,columns);nextRow=context.getResources().getDimensionPixelSize(R.dimen.tile_row_height);}

    @Override public boolean onRequestChildFocus(RecyclerView parent,RecyclerView.State state,View child,View focused){
        // MainActivity positions the focused card once, including its final focus scale.
        return true;
    }
    @Override protected void calculateExtraLayoutSpace(RecyclerView.State state,int[] extra){
        super.calculateExtraLayoutSpace(state,extra);
        // Keep the next remote row available even while the collapsed top inset is large.
        extra[1]=Math.max(extra[1],nextRow);
    }
}
