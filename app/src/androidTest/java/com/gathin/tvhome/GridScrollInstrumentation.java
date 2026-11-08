package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;

/** Follows the real D-pad path and records every scroll delta, including animation reversals. */
public class GridScrollInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Activity activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            RecyclerView grid=activity.findViewById(R.id.grid);SystemClock.sleep(500);waitForIdleSync();
            if(grid.getAdapter().getItemCount()<=12)throw new AssertionError("Need three installed app rows");
            focus(activity, grid, 5);
            key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
            if (position(activity, grid) != 6) throw new AssertionError("Right from favorite 6 must wrap to app row 2");
            key(activity, KeyEvent.KEYCODE_DPAD_LEFT);
            if (position(activity, grid) != 5) throw new AssertionError("Left from app row 2 must return to favorite 6");
            focus(activity, grid, 11);
            key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
            if (position(activity, grid) != 12) throw new AssertionError("Right from app row 2 end must wrap to app row 3");
            key(activity, KeyEvent.KEYCODE_DPAD_LEFT);
            if (position(activity, grid) != 11) throw new AssertionError("Left from app row 3 must return to app row 2 end");
            ArrayList<Integer> deltas=new ArrayList<>();
            RecyclerView.OnScrollListener recorder=new RecyclerView.OnScrollListener(){@Override public void onScrolled(RecyclerView rv,int dx,int dy){deltas.add(dy);}};
            main(()->{grid.addOnScrollListener(recorder);grid.findViewHolderForAdapterPosition(0).itemView.findViewById(R.id.card).requestFocus();});
            SystemClock.sleep(450);deltas.clear();
            int firstTop=firstTop(grid);
            key(activity,KeyEvent.KEYCODE_DPAD_DOWN);SystemClock.sleep(650);waitForIdleSync();
            int secondTop=firstTop(grid),firstMove=sum(deltas);deltas.clear();
            int secondPosition=position(activity,grid);
            key(activity,KeyEvent.KEYCODE_DPAD_DOWN);SystemClock.sleep(650);waitForIdleSync();
            int thirdTop=firstTop(grid),secondMove=sum(deltas),reverse=0;
            for(int dy:deltas)if(dy<0)reverse-=dy;
            int thirdPosition=position(activity,grid);
            result.putString("metrics","dockTop="+firstTop+" row2DockTop="+secondTop+" row3DockTop="+thirdTop+" moves="+firstMove+","+secondMove+" reverse="+reverse+" focused="+secondPosition+","+thirdPosition);
            if(secondPosition!=6||thirdPosition!=12)throw new AssertionError("D-pad selected wrong rows");
            if(secondMove<0||reverse>2)throw new AssertionError("Scrolling Down to row 3 reverses by "+reverse+"px");
            if(firstMove>2*activity.getResources().getDimensionPixelSize(R.dimen.tile_row_height))
                throw new AssertionError("Entering row 2 shifts more than two row heights: "+firstMove);
            checkVisible(activity,grid);
            key(activity,KeyEvent.KEYCODE_DPAD_UP);SystemClock.sleep(450);
            key(activity,KeyEvent.KEYCODE_DPAD_UP);SystemClock.sleep(500);waitForIdleSync();
            if(position(activity,grid)!=0||Math.abs(firstTop(grid)-firstTop)>2)throw new AssertionError("Favorites position not restored");
            deltas.clear();
            key(activity,KeyEvent.KEYCODE_DPAD_DOWN);SystemClock.sleep(60);
            key(activity,KeyEvent.KEYCODE_DPAD_DOWN);SystemClock.sleep(650);waitForIdleSync();
            int fastReverse=0;for(int dy:deltas)if(dy<0)fastReverse-=dy;
            if(position(activity,grid)!=12||fastReverse>2)throw new AssertionError("Rapid Down keys reverse the animation: "+fastReverse);
            checkVisible(activity,grid);
            key(activity,KeyEvent.KEYCODE_DPAD_UP);SystemClock.sleep(60);key(activity,KeyEvent.KEYCODE_DPAD_UP);SystemClock.sleep(650);
            if(position(activity,grid)!=0||Math.abs(firstTop(grid)-firstTop)>2)throw new AssertionError("Rapid return did not restore favorites");
            main(()->grid.removeOnScrollListener(recorder));
            result.putString("verification","PASS: row 2/3 navigation stays monotonic, bounded first lift, scaled cards visible, slow/rapid return restores dock");finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("failure",android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}
    }
    private int sum(ArrayList<Integer> values){int total=0;for(int value:values)total+=value;return total;}
    private int firstTop(RecyclerView grid){View view=grid.getLayoutManager().findViewByPosition(0);return view==null?Integer.MIN_VALUE:view.getTop();}
    private int position(Activity activity,RecyclerView grid){RecyclerView.ViewHolder holder=grid.findContainingViewHolder(activity.getCurrentFocus());return holder==null?-1:holder.getBindingAdapterPosition();}
    private void checkVisible(Activity activity,RecyclerView grid){
        main(()->{View card=activity.getCurrentFocus();int[] xy=new int[2],origin=new int[2];card.getLocationOnScreen(xy);grid.getLocationOnScreen(origin);
            float pad=card.getHeight()*.11f;
            if(xy[1]-origin[1]-pad<0||xy[1]-origin[1]+card.getHeight()+pad>grid.getHeight())throw new AssertionError("Scaled focused card is clipped");});
    }
    private void key(Activity activity,int code){main(()->{activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));});}
    private void focus(Activity activity, RecyclerView grid, int target) {
        main(() -> {
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(target);
            if (holder == null) throw new AssertionError("No laid out tile at " + target);
            holder.itemView.findViewById(R.id.card).requestFocus();
        });
        SystemClock.sleep(250);
        waitForIdleSync();
    }
    private void main(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
}
