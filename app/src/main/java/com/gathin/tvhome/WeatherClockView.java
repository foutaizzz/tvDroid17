package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;
import java.util.Calendar;

/** Vector clock and weather composition for the screensaver. */
final class WeatherClockView extends View {
    // Bright LED red for the main clock and weather, with dimmer red for secondary marks.
    static final int RED=0xFFFF0000;
    private static final int DIM_RED=0xFF5C0000;   // rouge sombre des graduations minutes (mesuré: 92,0,0)
    // Geometry tuned for a 16:9 television canvas at 960 x 540 logical pixels.
    private static final float CX=275f, CY=269f, DIAL_R=163f;
    private static final int SECOND_HAND_RED=0xFFB20004;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface medium,bold;
    private WeatherData weather;
    private boolean loading=true;
    private Bitmap dialCache;
    private Canvas dialCanvas;
    private String cachedWeatherKey="";
    private final String city;
    WeatherClockView(Context context){super(context);medium=context.getResources().getFont(R.font.clock_500);bold=Typeface.create(medium,Typeface.BOLD);city=context.getString(R.string.weather_city);setBackgroundColor(Color.BLACK);}
    void setWeather(WeatherData value,boolean loading){weather=value;this.loading=loading;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float scale=Math.min(getWidth()/960f,getHeight()/540f);
        if(scale<=0)return;
        canvas.save();canvas.translate((getWidth()-960*scale)/2,(getHeight()-540*scale)/2);canvas.scale(scale,scale);
        // Keep the composition fixed inside the canvas, scaling it uniformly to the screen.
        renderDial();
        canvas.drawBitmap(dialCache,0,0,null);
        // Only the hands move every frame; the dial behind them is a cached bitmap.
        Calendar now=Calendar.getInstance();
        float seconds=now.get(Calendar.SECOND)+now.get(Calendar.MILLISECOND)/1000f;
        float minutes=now.get(Calendar.MINUTE)+seconds/60f;
        float hours=now.get(Calendar.HOUR)+minutes/60f;
        // The shared center is painted first; the red hands then sit on top of the black dial.
        paint.setStyle(Paint.Style.FILL);paint.setColor(RED);canvas.drawCircle(CX,CY,10f,paint);
        mainHand(canvas,CX,CY,hours*30,100.5f,21f,6f,6.5f,12.5f);
        mainHand(canvas,CX,CY,minutes*6,169f,21f,6f,6.5f,12.5f);
        hand(canvas,CX,CY,seconds*6,163.5f,2.5f,28f,SECOND_HAND_RED);
        paint.setColor(SECOND_HAND_RED);canvas.drawCircle(CX,CY,6.5f,paint);
        paint.setColor(Color.BLACK);canvas.drawCircle(CX,CY,3.25f,paint);
        canvas.restore();
    }
    /** The dial and weather labels are cached and redrawn only when weather changes. */
    private void renderDial(){
        String weatherKey=(weather==null?"-":weather.code+"|"+weather.temperature+"|"+weather.condition()+"|"+weather.high+"|"+weather.low+"|"+weather.day)+"|"+loading;
        if(dialCache==null){dialCache=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);dialCanvas=new Canvas(dialCache);}
        if(weatherKey.equals(cachedWeatherKey))return;
        cachedWeatherKey=weatherKey;
        dialCanvas.drawColor(Color.BLACK);
        dial(CX,CY,DIAL_R);
        // Same fixed layout, with the city supplied by this install's configuration.
        text(dialCanvas,city,520.5f,132,40,bold,RED);
        text(dialCanvas,weather==null?"—°":Math.round(weather.temperature)+"°",521.5f,272,174,medium,RED);
        if(weather!=null){
            icon(dialCanvas,526.5f,319,weather);
            text(dialCanvas,weather.condition(),521.5f,391,32,medium,RED);
            text(dialCanvas,"↑ "+Math.round(weather.high)+"°  ↓ "+Math.round(weather.low)+"°",522.5f,434,33,bold,RED);
        }else text(dialCanvas,loading?"Actualisation…":"Météo indisponible",521.5f,391,32,medium,RED);
    }
    private void dial(float x,float y,float radius){
        // The superellipse border keeps the hour and minute marks inside the clock face.
        // Hour marks (every 30 degrees) run from radius 129 to the edge, in bright red.
        // Graduations de minutes (toutes les 6°) : 18 unités avant le bord, épaisseur 3, rouge sombre.
        for(int i=0;i<60;i++){
            boolean hour=i%5==0;
            double angle=Math.toRadians(i*6-90);
            float outer=roundedSquareRadius(radius,angle);
            float inner=hour?129f:outer-18f;
            line(dialCanvas,x+(float)Math.cos(angle)*inner,y+(float)Math.sin(angle)*inner,x+(float)Math.cos(angle)*outer,y+(float)Math.sin(angle)*outer,hour?3.5f:3f, hour?RED:DIM_RED);
        }
        // The 12, 3, 6, and 9 stay balanced within the clock face.
        centerText(dialCanvas,"12",x,y-radius*.596f,51);
        centerText(dialCanvas,"3",x+radius*.626f,y,51);
        centerText(dialCanvas,"6",x,y+radius*.596f,51);
        centerText(dialCanvas,"9",x-radius*.622f,y,51);
    }
    private static float roundedSquareRadius(float halfSide,double angle){
        // Superellipse n=10 : demi-côté 163 unités, rayon 326 px aux
        // axes, 402 px à 36°). n=10 donne des bords nettement moins arrondis qu'un n=6.
        double dx=Math.abs(Math.cos(angle)),dy=Math.abs(Math.sin(angle));
        return (float)(halfSide/Math.pow(Math.pow(dx,10)+Math.pow(dy,10),1d/10));
    }
    private void hand(Canvas c,float x,float y,float degrees,float length,float width,float tail,int color){
        double angle=Math.toRadians(degrees-90);float dx=(float)Math.cos(angle),dy=(float)Math.sin(angle);
        line(c,x-dx*tail,y-dy*tail,x+dx*length,y+dy*length,width,color);
    }
    /** Aiguille principale : tige fine depuis le moyeu, evasement conique, corps plein, bout arrondi.
     *  tip = longueur totale jusqu'a la pointe (bout arrondi compris), shoulder = fin de la tige,
     *  taper = longueur de l'evasement, shaftW = largeur de la tige, bodyW = largeur du corps. */
    private void mainHand(Canvas c,float x,float y,float degrees,float tip,float shoulder,float taper,float shaftW,float bodyW){
        double angle=Math.toRadians(degrees-90);
        float dx=(float)Math.cos(angle),dy=(float)Math.sin(angle);
        float px=-dy,py=dx;                       // perpendiculaire a l'axe
        float sh=shaftW/2f,bh=bodyW/2f;
        float base=tip-bh;                        // le bout arrondi depasse de bh
        Path p=new Path();
        p.moveTo(x+px*sh,y+py*sh);                                     // depart, cote +perp
        p.lineTo(x+dx*shoulder+px*sh,y+dy*shoulder+py*sh);             // fin de la tige
        p.lineTo(x+dx*(shoulder+taper)+px*bh,y+dy*(shoulder+taper)+py*bh); // fin de l'evasement
        p.lineTo(x+dx*base+px*bh,y+dy*base+py*bh);                     // corps, cote +perp
        p.lineTo(x+dx*base-px*bh,y+dy*base-py*bh);                     // corps, cote -perp
        p.lineTo(x+dx*(shoulder+taper)-px*bh,y+dy*(shoulder+taper)-py*bh);
        p.lineTo(x+dx*shoulder-px*sh,y+dy*shoulder-py*sh);
        p.lineTo(x-px*sh,y-py*sh);
        p.close();
        paint.setStyle(Paint.Style.FILL);paint.setColor(RED);
        c.drawPath(p,paint);
        c.drawCircle(x+dx*base,y+dy*base,bh,paint);                    // bout arrondi
    }
    private void centerText(Canvas c,String value,float x,float y,float size){
        paint.setTextSize(size);paint.setTypeface(medium);float baseline=y-(paint.ascent()+paint.descent())/2;
        text(c,value,x-paint.measureText(value)/2,baseline,size,medium,RED);
    }
    private void icon(Canvas c,float x,float y,WeatherData data){
        c.save();c.translate(x,y);int code=data.code;
        if(code==0||code==1||code==2){
            if(data.day){
                paint.setColor(RED);paint.setStyle(Paint.Style.FILL);c.drawCircle(18,19,11,paint);
                for(int i=0;i<8;i++){double a=i*Math.PI/4;line(c,18+(float)Math.cos(a)*16,19+(float)Math.sin(a)*16,18+(float)Math.cos(a)*21,19+(float)Math.sin(a)*21,2.5f,RED);}
            }else{
                paint.setColor(RED);paint.setStyle(Paint.Style.FILL);c.drawCircle(18,19,17.5f,paint);
                paint.setColor(Color.BLACK);c.drawCircle(26,11,16,paint);
            }
            if(code==0||code==1){c.restore();return;}
            c.translate(8,8);
        }
        paint.setStyle(Paint.Style.FILL);paint.setColor(RED);
        c.drawCircle(13,24,10,paint);c.drawCircle(24,17,14,paint);c.drawCircle(36,25,10,paint);c.drawRoundRect(8,24,40,34,5,5,paint);
        if((code>=51&&code<=67)||(code>=80&&code<=82))for(int i=0;i<3;i++)line(c,13+i*11,40,10+i*11,47,2.5f,RED);
        if((code>=71&&code<=77)||code==85||code==86)for(int i=0;i<3;i++){line(c,12+i*12,41,12+i*12,48,1.6f,RED);line(c,9+i*12,44.5f,15+i*12,44.5f,1.6f,RED);}
        if(code==45||code==48){line(c,5,40,43,40,2.5f,RED);line(c,9,47,40,47,2.5f,RED);}
        if(code>=95){Path bolt=new Path();bolt.moveTo(24,34);bolt.lineTo(15,46);bolt.lineTo(24,44);bolt.lineTo(20,56);bolt.lineTo(34,39);bolt.lineTo(25,41);bolt.close();paint.setStyle(Paint.Style.FILL);paint.setColor(RED);c.drawPath(bolt,paint);}
        c.restore();
    }
    private void text(Canvas c,String value,float x,float baseline,float size,Typeface font,int color){paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTypeface(font);paint.setTextSize(size);c.drawText(value,x,baseline,paint);}
    private void line(Canvas c,float x1,float y1,float x2,float y2,float width,int color){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(color);c.drawLine(x1,y1,x2,y2,paint);paint.setStyle(Paint.Style.FILL);}
}
