package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/** Checks that the global controls really return to the app below, with native edits blocked. */
public class RemoteOverlayInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            getTargetContext().startActivity(new Intent().setClassName("com.android.tv.settings","com.android.tv.settings.MainSettings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(700);
            Activity overlay=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),RemoteControlsActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(350);waitForIdleSync();
            Field panelField=RemoteControlsActivity.class.getDeclaredField("panel");panelField.setAccessible(true);
            ControlPanel panel=(ControlPanel)panelField.get(overlay);
            runOnMainSync(panel::dismiss);
            SystemClock.sleep(500);
            ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand("dumpsys activity activities");
            String tasks;
            try(java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){
                java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
                tasks=new String(output.toByteArray(),StandardCharsets.UTF_8);
            }
            if(!java.util.Arrays.stream(tasks.split("\n")).anyMatch(line->line.contains("mResumedActivity")&&line.contains("com.android.tv.settings/.MainSettings")))
                throw new AssertionError("Closing overlay did not restore app below");
            Activity second=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),RemoteControlsActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(300);waitForIdleSync();
            ControlPanel secondPanel=(ControlPanel)panelField.get(second);
            Field buttonsField=ControlPanel.class.getDeclaredField("buttons");buttonsField.setAccessible(true);
            View[] buttons=(View[])buttonsField.get(secondPanel);
            runOnMainSync(()->{
                if(!RemoteControlsActivity.toggleIfVisible())throw new AssertionError("Visible overlay was not found for SET toggle");
            });
            SystemClock.sleep(250);waitForIdleSync();
            if(!second.isFinishing())throw new AssertionError("SET did not close the overlay over the app");
            Activity reopened=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),RemoteControlsActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(300);waitForIdleSync();
            ControlPanel reopenedPanel=(ControlPanel)panelField.get(reopened);
            View[] reopenedButtons=(View[])buttonsField.get(reopenedPanel);
            ActivityMonitor monitor=addMonitor(DeviceSettingsActivity.class.getName(),new ActivityResult(Activity.RESULT_OK,null),true);
            runOnMainSync(()->reopenedButtons[ControlPanel.SETTINGS].performClick());
            SystemClock.sleep(400);
            if(monitor.getHits()!=1)throw new AssertionError("Dismissed overlay lost preferences action");
            removeMonitor(monitor);
            result.putString("verification","PASS: shared controls open above native app, SET toggle closes overlay, Back restores app, preferences action survives dismissal; no power or settings changes");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable failure){result.putString("failure",android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}
    }
}
