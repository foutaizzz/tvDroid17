package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Fixed city, HTTPS and bounded responses. Only the small last successful JSON is cached. */
final class WeatherClient implements AutoCloseable {
    static final String URL_AIX="https://api.open-meteo.com/v1/forecast?latitude=48.8566&longitude=2.3522&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=Europe%2FParis&forecast_days=1";
    private final SharedPreferences cache;
    private volatile boolean closed;
    private volatile HttpURLConnection active;
    WeatherClient(Context context){cache=context.getSharedPreferences("weather_aix",Context.MODE_PRIVATE);}
    WeatherData cached(){
        try{WeatherData data=WeatherData.parse(cache.getString("json",""),cache.getLong("at",0));return data.usable(System.currentTimeMillis())?data:null;}
        catch(Exception invalid){return null;}
    }
    WeatherData fetch()throws Exception{
        if(closed)throw new java.io.IOException("Closed");
        HttpURLConnection connection=(HttpURLConnection)new URL(URL_AIX).openConnection();active=connection;
        try{
            if(closed)throw new java.io.IOException("Closed");
            connection.setConnectTimeout(8000);connection.setReadTimeout(8000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","application/json");
            if(connection.getResponseCode()!=200)throw new java.io.IOException("Weather unavailable");
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()){
                byte[] buffer=new byte[2048];int count;
                while((count=input.read(buffer))!=-1){
                    if(closed||Thread.currentThread().isInterrupted())throw new java.io.IOException("Cancelled");
                    if(output.size()+count>32768)throw new java.io.IOException("Oversized weather response");
                    output.write(buffer,0,count);
                }
            }
            String json=new String(output.toByteArray(),StandardCharsets.UTF_8);
            long now=System.currentTimeMillis();WeatherData result=WeatherData.parse(json,now);
            if(!result.usable(now))throw new java.io.IOException("Outdated forecast");
            if(closed)throw new java.io.IOException("Cancelled");
            cache.edit().putString("json",json).putLong("at",now).apply();return result;
        }finally{connection.disconnect();active=null;}
    }
    @Override public void close(){closed=true;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
}
