package com.gathin.tvhome;

import org.json.JSONObject;
import org.json.JSONException;
import java.time.LocalDate;
import java.time.ZoneId;

/** Small validated weather snapshot; no location discovery, accounts or API key. */
final class WeatherData {
    static final long REFRESH_MS=20*60_000L, MAX_AGE_MS=2*60*60_000L;
    final double temperature, high, low;
    final int code;
    final boolean day;
    final String date;
    final long fetchedAt;
    WeatherData(double temperature,double high,double low,int code,boolean day,String date,long fetchedAt){
        this.temperature=temperature;this.high=high;this.low=low;this.code=code;this.day=day;this.date=date;this.fetchedAt=fetchedAt;
    }
    static WeatherData parse(String json,long fetchedAt)throws JSONException{
        JSONObject root=new JSONObject(json),current=root.getJSONObject("current"),daily=root.getJSONObject("daily");
        double temperature=current.getDouble("temperature_2m"),high=daily.getJSONArray("temperature_2m_max").getDouble(0),low=daily.getJSONArray("temperature_2m_min").getDouble(0);
        int code=current.getInt("weather_code");String date=daily.getJSONArray("time").getString(0);
        if(!valid(temperature)||!valid(high)||!valid(low)||low>high||code<0||code>99)throw new JSONException("Invalid weather values");
        try{LocalDate.parse(date);}catch(Exception invalid){throw new JSONException("Invalid forecast date");}
        return new WeatherData(temperature,high,low,code,current.optInt("is_day",1)==1,date,fetchedAt);
    }
    private static boolean valid(double value){return Double.isFinite(value)&&value>=-100&&value<=70;}
    boolean usable(long now){return now>=fetchedAt&&now-fetchedAt<=MAX_AGE_MS&&date.equals(LocalDate.now(ZoneId.of("Europe/Paris")).toString());}
    boolean fresh(long now){return usable(now)&&now-fetchedAt<REFRESH_MS;}
    String condition(){
        switch(code){
            case 0:return day?"Ensoleillé":"Ciel dégagé";
            case 1:return "Peu nuageux";
            case 2:return "Éclaircies";
            case 3:return "Nuageux";
            case 45:case 48:return "Brouillard";
            case 51:case 53:case 55:return "Bruine";
            case 56:case 57:case 66:case 67:return "Pluie verglaçante";
            case 61:case 63:case 65:return "Pluvieux";
            case 71:case 73:case 75:case 77:return "Neige";
            case 80:case 81:case 82:return "Averses";
            case 85:case 86:return "Averses de neige";
            case 95:case 96:case 99:return "Orageux";
            default:return "Temps variable";
        }
    }
}
