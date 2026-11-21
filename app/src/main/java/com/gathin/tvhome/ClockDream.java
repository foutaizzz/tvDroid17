package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Red analog clock on black, with public weather for the configured city. */
public class ClockDream extends DreamService {
    // The dial behind the hands is a cached bitmap, so only the hands are redrawn; 25 fps gives
    // a 0.24° step for the second hand — smooth to the eye — while keeping the CPU load low.
    private static final long CLOCK_FRAME_MS=40;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WeatherClockView content;
    private WeatherData weather;
    private WeatherClient client;
    private ExecutorService worker;
    private boolean dreaming;
    private int generation;
    private final Runnable clockTick=new Runnable(){
        @Override public void run(){
            if(!dreaming)return;
            if(weather!=null&&!weather.usable(System.currentTimeMillis())){weather=null;content.setWeather(null,false);}
            content.invalidate();
            handler.postDelayed(this,CLOCK_FRAME_MS);
        }
    };
    private final Runnable refresh=this::refreshWeather;
    @Override public void onAttachedToWindow(){
        super.onAttachedToWindow();setInteractive(false);setFullscreen(true);setScreenBright(true);
        android.widget.FrameLayout black=new android.widget.FrameLayout(this);
        black.setBackgroundColor(android.graphics.Color.BLACK);
        content=new WeatherClockView(this);
        black.addView(content,new android.widget.FrameLayout.LayoutParams(-1,-1));
        setContentView(black);
    }
    @Override public void onDreamingStarted(){
        super.onDreamingStarted();dreaming=true;generation++;
        ScreensaverBacklight.dim(this);
        content.setAlpha(0);content.animate().alpha(1).setDuration(900).start();
        client=new WeatherClient(this);worker=Executors.newSingleThreadExecutor();
        clockTick.run();refreshWeather();
    }
    private void refreshWeather(){
        if(!dreaming||worker==null)return;
        final int version=generation;final WeatherClient request=client;
        worker.submit(()->{
            WeatherData cached=request.cached();
            handler.post(()->{
                if(!dreaming||generation!=version)return;
                if(cached!=null){weather=cached;content.setWeather(weather,false);}
            });
            WeatherData loaded=cached;boolean success=cached!=null&&cached.fresh(System.currentTimeMillis());
            if(!success){try{loaded=request.fetch();success=true;}catch(Exception unavailable){/* Clock stays usable offline. */}}
            final WeatherData result=loaded;final boolean available=success;
            handler.post(()->{
                if(!dreaming||generation!=version)return;
                weather=result!=null&&result.usable(System.currentTimeMillis())?result:null;
                content.setWeather(weather,false);
                long delay=available&&weather!=null?Math.max(60_000,WeatherData.REFRESH_MS-(System.currentTimeMillis()-weather.fetchedAt)):5*60_000;
                handler.postDelayed(refresh,delay);
            });
        });
    }
    @Override public void onDreamingStopped(){stop();super.onDreamingStopped();}
    @Override public void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
    private void stop(){
        dreaming=false;generation++;handler.removeCallbacksAndMessages(null);
        ScreensaverBacklight.restore(this);
        if(content!=null)content.animate().cancel();
        if(client!=null){client.close();client=null;}
        if(worker!=null){worker.shutdownNow();worker=null;}
        weather=null;
    }
}
