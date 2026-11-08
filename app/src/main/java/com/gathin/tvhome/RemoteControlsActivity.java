package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.lang.ref.WeakReference;

/** Translucent host for the same controls over another app; Back returns to that app. */
public class RemoteControlsActivity extends Activity {
    private static WeakReference<RemoteControlsActivity> foreground = new WeakReference<>(null);
    private ControlPanel panel;
    static boolean toggleIfVisible() {
        RemoteControlsActivity activity = foreground.get();
        if (activity == null || activity.isFinishing()) return false;
        activity.runOnUiThread(activity::finish);
        return true;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        foreground = new WeakReference<>(this);
        float density=getResources().getDisplayMetrics().density;
        FrameLayout root=new FrameLayout(this);
        FrameLayout capsule=new FrameLayout(this);
        FrameLayout.LayoutParams box=new FrameLayout.LayoutParams((int)(82*density),(int)(32*density),Gravity.TOP|Gravity.END);
        box.topMargin=(int)(26*density);box.rightMargin=(int)(18*density);root.addView(capsule,box);
        View anchor=new View(this);capsule.addView(anchor,new FrameLayout.LayoutParams(1,1));
        setContentView(root);
        Runnable[] actions=new Runnable[11];
        actions[ControlPanel.POWER]=()->{
            if(!SourceKeyService.showPowerOptions())Toast.makeText(this,R.string.not_available,Toast.LENGTH_SHORT).show();
        };
        actions[ControlPanel.SETTINGS]=()->open(DeviceSettingsActivity.intent(this,"device"));
        actions[ControlPanel.WIFI]=()->open(DeviceSettingsActivity.intent(this,"wifi"));
        actions[ControlPanel.AUDIO]=()->open(new Intent(Settings.ACTION_SOUND_SETTINGS));
        actions[ControlPanel.TIMER]=()->nativePage(".partnercustomizer.timer.TimerActivity");
        actions[ControlPanel.DEVICES]=()->nativePage(".accessories.AddAccessoryActivity");
        actions[ControlPanel.CAPTIONS]=()->open(DeviceSettingsActivity.intent(this,"captions"));
        actions[ControlPanel.SOURCES]=()->open(new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_INPUTS,true));
        actions[ControlPanel.APPS]=()->open(new Intent(this,MainActivity.class));
        actions[ControlPanel.HOME]=()->open(new Intent(this,MainActivity.class));
        panel=new ControlPanel(this,anchor,actions,"");
        panel.setOnClosed(this::finish);
        root.post(()->{if(!isFinishing())panel.show(root);});
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_SETTINGS){
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onDestroy(){
        if(foreground.get()==this)foreground.clear();
        super.onDestroy();
    }
    @Override public void onBackPressed(){finish();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);finish();}
    @Override protected void onPause(){super.onPause();if(panel!=null)panel.dismiss();finish();}
    private void nativePage(String suffix){open(new Intent().setClassName("com.android.tv.settings","com.android.tv.settings"+suffix));}
    private void open(Intent intent){
        try{startActivity(intent);}catch(android.content.ActivityNotFoundException|SecurityException unavailable){Toast.makeText(this,R.string.not_available,Toast.LENGTH_SHORT).show();}
    }
}
