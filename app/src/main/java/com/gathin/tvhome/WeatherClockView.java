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

/** Vector clock/weather composition inspired by the supplied red StandBy reference. */
final class WeatherClockView extends View {
    // Bright LED red for the main clock and weather, with dimmer red for secondary marks.
    static final int RED=0xFFFF0000;
    private static final int DIM_RED=0xFF5C0000;   // rouge sombre des graduations minutes (mesuré: 92,0,0)
    // Mode test désactivé : données réelles (météo réseau Paris, heure système).
    static final boolean TEST_MODE=false;
    // Géométrie mesurée sur ref-economiseur-écran-*.png (1920x1080, ÷2 = espace 960x540) :
    // cadran centré en (550,539)px, rayon 325px, 12 graduations au bord, bloc météo à droite.
    private static final float CX=275f, CY=269f, DIAL_R=163f;
    private static final int SECOND_HAND_RED=0xFFB20004;
    private static final String[] WEEKDAYS={"","DIM","LUN","MAR","MER","JEU","VEN","SAM"};
    private static final String[] MONTHS={"JAN","FÉV","MAR","AVR","MAI","JUIN","JUIL","AOÛ","SEP","OCT","NOV","DÉC"};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular,medium;
    private WeatherData weather;
    private boolean loading=true;
    private int dialDay=-1,dialYear=-1;
    private String dialDate="",dialYearText="";
    private Bitmap dialCache;
    private Canvas dialCanvas;
    private String cachedWeatherKey="";
    private final long began=android.os.SystemClock.elapsedRealtime();
    WeatherClockView(Context context){super(context);regular=context.getResources().getFont(R.font.clock_400);medium=context.getResources().getFont(R.font.clock_500);setBackgroundColor(Color.BLACK);}
    void setWeather(WeatherData value,boolean loading){weather=value;this.loading=loading;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float scale=Math.min(getWidth()/960f,getHeight()/540f);
        if(scale<=0)return;
        canvas.save();canvas.translate((getWidth()-960*scale)/2,(getHeight()-540*scale)/2);canvas.scale(scale,scale);
        // The composition is fixed: the reference is a still image, and a moving layout would
        // never line up with it.
        renderDial();
        canvas.drawBitmap(dialCache,0,0,null);
        // Only the hands move every frame; the dial behind them is a cached bitmap.
        float seconds, minutes, hours;
        if (TEST_MODE) {
            // Heure factice 04:05:41 pour la comparaison avec la référence.
            seconds=41f; minutes=5f+41f/60f; hours=4f+minutes/60f;
        } else {
            Calendar now=Calendar.getInstance();
            seconds=now.get(Calendar.SECOND)+now.get(Calendar.MILLISECOND)/1000f;
            minutes=now.get(Calendar.MINUTE)+seconds/60f; hours=now.get(Calendar.HOUR)+minutes/60f;
        }
        // Aiguilles mesurées sur ref-economiseur-écran-aiguilles-seules.png (meme instant 04:05:41,
        // centre 550,539 px, 2 px par unite) :
        //  - pointe des heures   201 px (100,5 u), des minutes 338 px (169 u), des secondes 327 px (163,5 u)
        //  - tige fine de 13 px (6,5 u) jusqu'a 42 px (21 u), puis evasement conique de 16 px (8 u)
        //    vers le corps plein de 25 px (12,5 u), termine par un bout arrondi.
        //  - pointe de l'aiguille des secondes 327 px, avec une queue de 56 px (28 u) derriere le moyeu.
        // Moyeu, trace en premier pour rester derriere les aiguilles : cercle rouge vif de 10 u,
        // puis (apres les aiguilles) le disque rouge sombre de 6,5 u auquel l'aiguille des secondes
        // se raccorde, perce d'un trou noir de 3,25 u.
        paint.setStyle(Paint.Style.FILL);paint.setColor(RED);canvas.drawCircle(CX,CY,10f,paint);
        mainHand(canvas,CX,CY,hours*30,100.5f,21f,6f,6.5f,12.5f);
        mainHand(canvas,CX,CY,minutes*6,169f,21f,6f,6.5f,12.5f);
        hand(canvas,CX,CY,seconds*6,163.5f,2.5f,28f,SECOND_HAND_RED);
        paint.setColor(SECOND_HAND_RED);canvas.drawCircle(CX,CY,6.5f,paint);
        paint.setColor(Color.BLACK);canvas.drawCircle(CX,CY,3.25f,paint);
        canvas.restore();
    }
    /** Dial, city and weather change only with the date or the weather: render them once. */
    private void renderDial(){
        Calendar now=Calendar.getInstance();
        int dayOfYear=now.get(Calendar.DAY_OF_YEAR),year=now.get(Calendar.YEAR);
        String weatherKey=(weather==null?"-":weather.code+"|"+weather.temperature+"|"+weather.condition()+"|"+weather.high+"|"+weather.low+"|"+weather.day)+"|"+loading;
        if(dialCache==null){dialCache=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);dialCanvas=new Canvas(dialCache);}
        if(dayOfYear==dialDay&&year==dialYear&&weatherKey.equals(cachedWeatherKey))return;
        dialDay=dayOfYear;dialYear=year;cachedWeatherKey=weatherKey;
        dialDate=WEEKDAYS[now.get(Calendar.DAY_OF_WEEK)]+" "+now.get(Calendar.DAY_OF_MONTH);
        dialYearText=MONTHS[now.get(Calendar.MONTH)];
        dialCanvas.drawColor(Color.BLACK);
        dial(CX,CY,DIAL_R);
        if (TEST_MODE) {
            // Données factices (référence) : Paris, 7°, "Ciel plutôt dégagé", 14°/6°, simple croissant de lune.
            WeatherData fake=new WeatherData(7,14,6,0,false,"2026-11-05",System.currentTimeMillis());
            text(dialCanvas,"Paris",520.5f,132,40,medium,RED);
            text(dialCanvas,"7°",521.5f,272,174,medium,RED);
            icon(dialCanvas,526.5f,319,fake);
            text(dialCanvas,"Ciel plutôt dégagé",521.5f,391,32,medium,RED);
            text(dialCanvas,"↑ 14°  ↓ 6°",522.5f,434,33,regular,RED);
        } else {
            // Données réelles : météo réseau Paris — mêmes constantes visuelles que la
            // branche test (mesurées sur la référence Apple).
            text(dialCanvas,"Paris",520.5f,132,40,medium,RED);
            text(dialCanvas,weather==null?"—°":Math.round(weather.temperature)+"°",521.5f,272,174,medium,RED);
            if(weather!=null){
                icon(dialCanvas,526.5f,319,weather);
                text(dialCanvas,weather.condition(),521.5f,391,32,medium,RED);
                text(dialCanvas,"↑ "+Math.round(weather.high)+"°  ↓ "+Math.round(weather.low)+"°",522.5f,434,33,regular,RED);
            }else text(dialCanvas,loading?"Actualisation…":"Météo indisponible",521.5f,391,32,medium,RED);
        }
    }
    private void dial(float x,float y,float radius){
        // Mesuré sur la référence : bord en superellipse (n=6). Graduations d'heures (toutes
        // les 30°) : du rayon fixe intérieur 129 jusqu'au bord, épaisseur 3,5, rouge vif.
        // Graduations de minutes (toutes les 6°) : 18 unités avant le bord, épaisseur 3, rouge sombre.
        for(int i=0;i<60;i++){
            boolean hour=i%5==0;
            double angle=Math.toRadians(i*6-90);
            float outer=roundedSquareRadius(radius,angle);
            float inner=hour?129f:outer-18f;
            line(dialCanvas,x+(float)Math.cos(angle)*inner,y+(float)Math.sin(angle)*inner,x+(float)Math.cos(angle)*outer,y+(float)Math.sin(angle)*outer,hour?3.5f:3f, hour?RED:DIM_RED);
        }
        // Chiffres 12/3/6/9 : mesurés dans la référence (rouge vif, hauteur 72 px, centres à
        // 102 u pour 3, 97 u pour 6, 101 u pour 9 et 97 u pour 12).
        centerText(dialCanvas,"12",x,y-radius*.596f,51);
        centerText(dialCanvas,"3",x+radius*.626f,y,51);
        centerText(dialCanvas,"6",x,y+radius*.596f,51);
        centerText(dialCanvas,"9",x-radius*.622f,y,51);
        /* Date et mois retirés du cadran à la demande — code conservé en commentaire :
        centerText(dialCanvas,dialDate,x,y-radius*.33f,20);
        centerText(dialCanvas,dialYearText,x,y+radius*.33f,20);
        */
    }
    private static float roundedSquareRadius(float halfSide,double angle){
        // Superellipse n=10 : ajusté sur la référence (demi-côté 163 unités, rayon 326 px aux
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
