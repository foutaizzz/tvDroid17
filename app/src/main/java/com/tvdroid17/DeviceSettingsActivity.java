package com.tvdroid17;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.view.accessibility.CaptioningManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Themed pages backed by live Android values. Privileged edits use the original system panels. */
public class DeviceSettingsActivity extends Activity {
    static final String PAGE = "page";
    private final ArrayList<String> history = new ArrayList<>();
    private String page;
    private float density;
    private FrameLayout design;
    private LinearLayout list;
    private ImageView illustration;
    private TextView description;
    private final ArrayList<View> rows = new ArrayList<>();

    static Intent intent(Activity activity, String page) {
        return new Intent(activity, DeviceSettingsActivity.class).putExtra(PAGE, page);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        page = state == null ? getIntent().getStringExtra(PAGE) : state.getString(PAGE);
        if (page == null) page = "device";
        if (state != null && state.getStringArrayList("history") != null)
            history.addAll(state.getStringArrayList("history"));
    }

    @Override protected void onResume() { super.onResume(); render(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString(PAGE, page); state.putStringArrayList("history", history);
    }
    @Override public void onBackPressed() {
        if (history.isEmpty()) finish();
        else { page = history.remove(history.size() - 1); render(); }
    }
    private void showPage(String next) { history.add(page); page = next; render(); }

    private void render() {
        Object previous = getCurrentFocus() == null ? null : getCurrentFocus().getTag();
        FrameLayout root = new FrameLayout(this);
        root.addView(new SettingsBackdropView(this), new FrameLayout.LayoutParams(-1, -1));
        design = new FrameLayout(this); design.setClipChildren(false);
        root.addView(design, new FrameLayout.LayoutParams(dp(960), dp(540), Gravity.CENTER));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            float scale = Math.min((r-l)/(960*density), (b-t)/(540*density));
            design.setScaleX(scale); design.setScaleY(scale);
        });
        setContentView(root);
        TextView heading = text(title(), 28, 0xFF66666A);
        heading.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); heading.setGravity(Gravity.CENTER);
        place(heading, 80, 26, 800, 40);
        FrameLayout card = new FrameLayout(this);
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF535353, 0xFF535353}); surface.setCornerRadius(dp(62)); card.setBackground(surface);
        place(card, 128, 118, 270, 270);
        illustration = new ImageView(this); illustration.setColorFilter(0xFF1C1C1E);
        card.addView(illustration, new FrameLayout.LayoutParams(dp(82),dp(82),Gravity.CENTER));
        description = text("", 15, 0xFF8E8E93); description.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        description.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        description.setLineSpacing(dp(3),1); place(description,92,420,342,90);
        FrameLayout listViewport = new FrameLayout(this);
        listViewport.setClipChildren(true); listViewport.setClipToPadding(true);
        place(listViewport,522,86,406,420);
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(true); scroll.setClipChildren(true); scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16),dp(8),dp(16),0); list.setClipToPadding(true); list.setClipChildren(false);
        scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        listViewport.addView(scroll,new FrameLayout.LayoutParams(-1,-1)); rows.clear();
        populate();
        // Keep scrolling rows below the fixed page title.
        heading.bringToFront();
        View selected = rows.isEmpty() ? null : rows.get(0);
        for (View row : rows) if (row.getTag().equals(previous)) selected = row;
        if (selected != null) { selected.requestFocus(); final View target = selected; target.post(target::requestFocus); }
        design.setAlpha(0); design.setTranslationX(dp(10));
        design.animate().alpha(1).translationX(0).setDuration(180)
                .setInterpolator(new PathInterpolator(.22f,1,.36f,1)).start();
    }

    private String title() {
        switch(page) {
            case "wifi": return "Wi-Fi";
            case "network": return "Connexion réseau";
            case "about": return "À propos";
            case "date": return "Date et heure";
            case "language": return "Langue";
            case "storage": return "Stockage";
            case "dream": return "Économiseur d’écran";
            case "captions": return "Sous-titres";
            default: return "Préférences relatives à l’appareil";
        }
    }
    private void populate() {
        switch(page) {
            case "wifi": wifi(); return;
            case "network": network(); return;
            case "about":
                row("Modèle",Build.MODEL,"Informations du téléviseur.",R.drawable.ic_controls,null);
                row("Version Android",Build.VERSION.RELEASE,"Version du système installé.",R.drawable.ic_controls,null);
                row("Version du logiciel",Build.DISPLAY,"Version publiée par le fabricant.",R.drawable.ic_controls,null);
                row("Descriptions françaises","TMDB","Les résumés français proviennent de TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",R.drawable.ic_controls,null);
                row("Bandes-annonces et résumés français", new TokenStore(this).isConfigured() ? "Clé TMDB présente sur la TV" : "Clé absente : désactivés",
                        "La clé TMDB n’est pas dans l’APK : elle est lue depuis un fichier posé sur la TV à l’installation.",R.drawable.ic_controls,null);
                row("Service Source (accessibilité)", accessibilityServiceOn() ? "Activé" : "Désactivé",
                        "Filtre uniquement les touches Source et SET pour ouvrir les entrées et le centre de contrôle. Ne lit pas l’écran, ne conserve rien, n’envoie rien sur le réseau. Ouvrez les réglages d’accessibilité pour le désactiver.",
                        R.drawable.ic_accessibility, () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                row("Informations système",null,"Retrouvez les informations détaillées et les mises à jour du téléviseur.",R.drawable.ic_controls,()->nativePage(".about.AboutActivity")); return;
            case "date":
                row("Date et heure",DateFormat.getDateTimeInstance(DateFormat.LONG,DateFormat.SHORT,Locale.FRANCE).format(new Date()),"La date et l’heure actuelles du téléviseur.",R.drawable.ic_timer,null);
                row("Réglage automatique",Settings.Global.getInt(getContentResolver(),Settings.Global.AUTO_TIME,0)==1?"Activé":"Désactivé","Synchronisation de l’heure du système.",R.drawable.ic_timer,null);
                row("Fuseau horaire",TimeZone.getDefault().getID(),"Fuseau horaire utilisé par le téléviseur.",R.drawable.ic_timer,null);
                row("Modifier la date et l’heure",null,"Ajustez l’heure et sa synchronisation.",R.drawable.ic_timer,()->nativePage(".system.DateTimeActivity")); return;
            case "language":
                row("Langue du téléviseur",getResources().getConfiguration().getLocales().get(0).getDisplayName(Locale.FRANCE),"Langue utilisée dans les menus Android.",R.drawable.ic_controls,null);
                row("Changer de langue",null,"Choisissez une autre langue pour le téléviseur.",R.drawable.ic_controls,()->nativePage(".system.LanguageActivity")); return;
            case "storage":
                File disk=Environment.getDataDirectory();
                row("Espace disponible",android.text.format.Formatter.formatFileSize(this,disk.getUsableSpace()),"Espace encore disponible sur le stockage interne.",R.drawable.ic_apps,null);
                row("Capacité totale",android.text.format.Formatter.formatFileSize(this,disk.getTotalSpace()),"Capacité de la partition de données Android.",R.drawable.ic_apps,null);
                row("Gérer le stockage",null,"Consultez les applications et les supports de stockage.",R.drawable.ic_apps,()->nativePage(".device.StorageResetActivity")); return;
            case "dream":
                String city=getString(R.string.weather_city);
                row("Apparence","Cadran rouge sur fond noir","Une horloge à aiguilles et la météo de "+city+" : température, conditions et minimum/maximum du jour.",R.drawable.ic_timer,null);
                row("Météo",city,"Données : Open-Meteo (https://open-meteo.com), licence CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Conditions traduites en français. Actualisation toutes les vingt minutes. La ville est configurée localement, sans géolocalisation par IP.",R.drawable.ic_wifi,null);
                row("Délai et activation",null,"Réglez le démarrage de l’économiseur d’écran.",R.drawable.ic_timer,()->nativePage(".device.display.daydream.DaydreamActivity")); return;
            case "captions":
                CaptioningManager manager=getSystemService(CaptioningManager.class);
                row("Sous-titres",manager!=null && manager.isEnabled()?"Activés":"Désactivés","Préférence de sous-titrage Android. Les applications peuvent avoir leurs propres réglages.",R.drawable.ic_accessibility,null);
                row("Taille du texte",manager==null?"Par défaut":Math.round(manager.getFontScale()*100)+" %","Taille de texte choisie dans les préférences Android.",R.drawable.ic_accessibility,null);
                row("Modifier les sous-titres",null,"Choisissez la langue, la taille et le style des sous-titres.",R.drawable.ic_accessibility,()->nativePage(".system.CaptionSetupActivity")); return;
            default:
                link("À propos","Informations et version du téléviseur.",R.drawable.ic_controls,"about");
                link("Date et heure","Heure, synchronisation et fuseau horaire.",R.drawable.ic_timer,"date");
                link("Langue","Langue des menus du téléviseur.",R.drawable.ic_controls,"language");
                row("Clavier",null,"Configuration du clavier et de la saisie.",R.drawable.ic_controls,()->nativePage(".inputmethod.KeyboardActivity"));
                row("Entrées",null,"Personnalisez les entrées du téléviseur.",R.drawable.ic_input,()->nativePage(".system.InputsActivity"));
                row("Image",null,"Réglez l’image avec les commandes du fabricant.",R.drawable.ic_settings,()->nativePage(".partnercustomizer.picture.PictureActivity"));
                row("Son",null,"Réglez les sorties audio et le son du téléviseur.",R.drawable.ic_sound,()->open(new Intent(Settings.ACTION_SOUND_SETTINGS)));
                link("Stockage","Espace disponible et gestion du stockage.",R.drawable.ic_apps,"storage");
                link("Économiseur d’écran","Horloge et déclenchement de l’économiseur.",R.drawable.ic_timer,"dream");
                link("Sous-titres","Langue, taille et présentation des sous-titres.",R.drawable.ic_accessibility,"captions");
                link("Wi-Fi","Connexion et informations réseau.",R.drawable.ic_wifi,"wifi");
                row("Autres préférences Android",null,"Retrouvez toutes les options proposées par Xiaomi et Android.",R.drawable.ic_controls,()->nativePage(".MainSettings"));
        }
    }
    private void wifi() {
        WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info=wifi==null?null:wifi.getConnectionInfo();
        String name=info==null?"":info.getSSID();
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        android.net.Network active=cm==null?null:cm.getActiveNetwork();
        NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
        boolean connected=(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                || (info!=null && info.getNetworkId()!=-1);
        if(name==null || name.isEmpty() || name.equals("<unknown ssid>")) name=connected?"Connecté":"Non connecté";
        else name=name.replace("\"","");
        row("Wi-Fi",wifi!=null && wifi.isWifiEnabled()?"Activé":"Désactivé","État du Wi-Fi du téléviseur.",R.drawable.ic_wifi,null);
        row("Réseau actuel",name,"Consultez les informations de la connexion actuelle.",R.drawable.ic_wifi,()->showPage("network"));
        row("Choisir un réseau",null,"Connectez le téléviseur à un réseau Wi-Fi ou modifiez la connexion.",R.drawable.ic_wifi,()->open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        row("Réglages réseau avancés",null,"Accédez aux paramètres IP, DNS et aux réseaux enregistrés.",R.drawable.ic_wifi,()->nativePage(".connectivity.NetworkActivity"));
    }
    private void network() {
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        android.net.Network active=cm==null?null:cm.getActiveNetwork();
        NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
        row("Accès Internet",caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?"Connecté":"Non disponible","État de la connexion Internet vérifié par Android.",R.drawable.ic_wifi,null);
        WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo info=wifi==null?null:wifi.getConnectionInfo();
        if(info!=null && info.getNetworkId()!=-1) {
            row("Signal Wi-Fi",(WifiManager.calculateSignalLevel(info.getRssi(),5)+1)+" / 5","Puissance du signal du réseau actuel.",R.drawable.ic_wifi,null);
            row("Vitesse de liaison",info.getLinkSpeed()>0?info.getLinkSpeed()+" Mbit/s":"Indisponible","Vitesse de la liaison Wi-Fi ; ce n’est pas une mesure du débit Internet.",R.drawable.ic_wifi,null);
        }
        android.net.LinkProperties links=active==null?null:cm.getLinkProperties(active);
        if(links!=null) {
            String addresses=android.text.TextUtils.join("\n",links.getLinkAddresses());
            row("Adresses réseau",addresses.isEmpty()?"Indisponible":addresses,"Adresses attribuées au téléviseur sur le réseau local.",R.drawable.ic_wifi,null);
        }
        row("Configurer la connexion",null,"Modifiez les paramètres du réseau.",R.drawable.ic_wifi,()->open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
    }
    private void link(String title,String detail,int cardIcon,String destination) {
        row(title,null,detail,cardIcon,()->showPage(destination));
    }
    /**
     * A row of the list. It shows no icon of its own: {@code cardIcon} is the one drawn in the
     * card at the top of the page, and it changes when the row takes the focus.
     */
    private void row(String label,String value,String help,int cardIcon,Runnable action) {
        android.text.TextPaint measure=new android.text.TextPaint();
        measure.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        measure.setTextSize(19*getResources().getDisplayMetrics().scaledDensity);
        boolean twoLines=value!=null && (measure.measureText(value)>dp(168) || value.contains("\n"));
        SettingsRow row=new SettingsRow(this,twoLines); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16),0,dp(14),0);
        row.setFocusable(true); row.setFocusableInTouchMode(true); row.setDefaultFocusHighlightEnabled(false);
        row.setStateListAnimator(null); row.setTag(page+":"+label);
        row.setClickable(action!=null); row.setClipChildren(false);
        LinearLayout texts=new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        TextView title=text(label,19,0xFFF5F5F7);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);texts.addView(title);
        TextView detail=null;
        if(value!=null){
            detail=text(value.replace('\n',' '),twoLines?16:19,0xFF8E8E93);detail.setTag("value");
            detail.setSingleLine(true);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if(twoLines){detail.setPadding(0,dp(4),0,0);texts.addView(detail);}
        }
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        if(detail!=null && !twoLines){
            detail.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);detail.setMaxWidth(dp(168));
            LinearLayout.LayoutParams valueBox=new LinearLayout.LayoutParams(-2,-2);valueBox.leftMargin=dp(12);row.addView(detail,valueBox);
        }
        ImageView arrow=null;
        if(action!=null) {arrow=new ImageView(this);arrow.setImageResource(R.drawable.ic_chevron_right);arrow.setColorFilter(0xFF8E8E93);
            LinearLayout.LayoutParams arrowBox=new LinearLayout.LayoutParams(dp(14),dp(20));arrowBox.leftMargin=dp(12);row.addView(arrow,arrowBox);}
        LinearLayout.LayoutParams box=new LinearLayout.LayoutParams(-1,dp(twoLines?64:33));box.bottomMargin=dp(twoLines?10:6);list.addView(row,box);rows.add(row);
        TextView focusDetail=detail;
        ImageView focusArrow=arrow;
        row.setOnFocusChangeListener((v,focused)->{
            row.highlight(focused);
            title.setTextColor(focused?0xFF111113:0xFFF5F5F7);
            if(focusDetail!=null) focusDetail.setTextColor(0xFF8E8E93);
            if(focusArrow!=null) focusArrow.setColorFilter(focused?0xFF6E6E73:0xFF8E8E93);
            if(focused){illustration.setImageResource(cardIcon);description.setText(value==null?help:value+"\n"+help);}
        });
        if(action!=null)row.setOnClickListener(v->action.run());
    }
    /** Whether our key-filtering accessibility service is currently enabled on the television. */
    private boolean accessibilityServiceOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName());
    }
    private void nativePage(String suffix) {open(new Intent().setClassName("com.android.tv.settings","com.android.tv.settings"+suffix));}
    private void open(Intent intent) {
        try {startActivity(intent);} catch(android.content.ActivityNotFoundException|SecurityException unavailable) {
            Toast.makeText(this,R.string.not_available,Toast.LENGTH_SHORT).show();
        }
    }
    private GradientDrawable pill(int color){GradientDrawable value=new GradientDrawable();value.setColor(color);value.setCornerRadius(dp(17));return value;}
    private TextView text(String value,float size,int color){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setIncludeFontPadding(false);text.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));return text;}
    private void place(View view,int x,int y,int w,int h){FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(dp(w),dp(h));box.leftMargin=dp(x);box.topMargin=dp(y);design.addView(view,box);}
    private int dp(float value){return Math.round(value*density);}
}
