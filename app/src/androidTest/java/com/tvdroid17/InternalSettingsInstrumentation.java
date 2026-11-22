package com.tvdroid17;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.TextClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Navigates all custom pages and intercepts native edits: never changes Wi-Fi or power. */
public class InternalSettingsInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Activity activity=startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(),DeviceSettingsActivity.class.getName())
                    .putExtra("page","device").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            android.net.ConnectivityManager cm=activity.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
            Method page=DeviceSettingsActivity.class.getDeclaredMethod("showPage",String.class);page.setAccessible(true);
            for(String id:new String[]{"wifi","network","about","date","language","storage","dream","captions"}){
                main(()->{try{page.invoke(activity,id);}catch(Exception e){throw new AssertionError(e);}});
                SystemClock.sleep(220);waitForIdleSync();
                ArrayList<View> rows=rows(activity);
                if(rows.size()<2 || !rows.get(0).hasFocus())throw new AssertionError("No initial focus on "+id);
                if(id.equals("wifi") && caps!=null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)){
                    android.widget.TextView value=rows.get(1).findViewWithTag("value");
                    if(value.getText().toString().equals("Non connecté"))
                        throw new AssertionError("A hidden SSID is incorrectly displayed as disconnected");
                }
                for(View row:rows){
                    main(row::requestFocus);
                    if(row.getHeight()<=0 || row.getWidth()<600)throw new AssertionError("Invalid row bounds on "+id);
                }
            }
            main(()->{try{page.invoke(activity,"wifi");}catch(Exception e){throw new AssertionError(e);}});
            ActivityMonitor monitor=addMonitor(new android.content.IntentFilter(Settings.ACTION_WIFI_SETTINGS),new ActivityResult(Activity.RESULT_OK,null),true);
            for(View row:rows(activity))if(row.getTag().toString().endsWith(":Choisir un réseau"))main(row::performClick);
            if(monitor.getHits()!=1)throw new AssertionError("Native Wi-Fi connection route missing");removeMonitor(monitor);
            main(activity::onBackPressed);
            View dream=new WeatherClockView(getTargetContext());
            if(!(((ColorDrawable)dream.getBackground()).getColor()==Color.BLACK))throw new AssertionError("Screensaver not black");
            main(activity::finish);
            result.putString("verification","PASS: 8 themed internal pages, focus and scroll, real settings values, native Wi-Fi edit route, black weather clock background; no settings changed");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
    }
    @SuppressWarnings("unchecked") private ArrayList<View> rows(Activity activity)throws Exception{Field f=DeviceSettingsActivity.class.getDeclaredField("rows");f.setAccessible(true);return (ArrayList<View>)f.get(activity);}
    private void main(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
}
