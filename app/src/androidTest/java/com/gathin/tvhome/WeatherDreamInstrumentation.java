package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

public class WeatherDreamInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Class<?> model=Class.forName("com.gathin.tvhome.WeatherData");
            java.lang.reflect.Method parse=model.getDeclaredMethod("parse",String.class,long.class);parse.setAccessible(true);
            String json="{\"current\":{\"temperature_2m\":18.4,\"weather_code\":3,\"is_day\":1},\"daily\":{\"time\":[\"2026-10-19\"],\"temperature_2m_max\":[20.2],\"temperature_2m_min\":[15.1]}}";
            Object data=parse.invoke(null,json,System.currentTimeMillis());
            java.lang.reflect.Method condition=model.getDeclaredMethod("condition");condition.setAccessible(true);
            if(!condition.invoke(data).equals("Nuageux"))throw new AssertionError("Weather not localized");
            String today=java.time.LocalDate.now(java.time.ZoneId.of("Europe/Paris")).toString();
            json=json.replace("2026-10-19",today);
            WeatherData current=WeatherData.parse(json,System.currentTimeMillis());
            if(!current.fresh(System.currentTimeMillis()))throw new AssertionError("Fresh weather rejected");
            WeatherData stale=WeatherData.parse(json,System.currentTimeMillis()-WeatherData.MAX_AGE_MS-1);
            if(stale.usable(System.currentTimeMillis()))throw new AssertionError("Expired weather still usable");
            try{WeatherData.parse(json.replace("18.4","null"),System.currentTimeMillis());throw new AssertionError("Missing temperature accepted");}catch(org.json.JSONException expected){}
            Bitmap bitmap=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);
            runOnMainSync(()->{
                WeatherClockView view=new WeatherClockView(getTargetContext());view.layout(0,0,1920,1080);
                view.setWeather(current,false);view.draw(new Canvas(bitmap));
            });
            if(bitmap.getPixel(0,0)!=Color.BLACK || bitmap.getPixel(1919,1079)!=Color.BLACK)throw new AssertionError("Not pure black");
            int red=0;for(int y=0;y<1080;y+=4)for(int x=0;x<1920;x+=4){int pixel=bitmap.getPixel(x,y);if(Color.red(pixel)>60&&Color.red(pixel)>Color.green(pixel)*2)red++;}
            if(red<2000)throw new AssertionError("Clock/weather composition missing");bitmap.recycle();
            WeatherClient client=new WeatherClient(getTargetContext());WeatherData actual;
            try{actual=client.fetch();}finally{client.close();}
            if(actual==null||!actual.usable(System.currentTimeMillis()))throw new AssertionError("No live weather from TV");
            WeatherClient cached=new WeatherClient(getTargetContext());
            if(cached.cached()==null)throw new AssertionError("Offline snapshot unavailable");cached.close();
            try{cached.fetch();throw new AssertionError("Closed client still fetches");}catch(java.io.IOException expected){}
            result.putString("verification","PASS: French conditions, complete temperatures, invalid/expired payload rejection, black/red 1080p rendering, live HTTPS weather from TV, offline snapshot and closed client");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
    }
}
