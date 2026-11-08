package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Small, non-focusable YouTube IFrame surface used only while a detail card is selected. */
final class TrailerWebView extends WebView {
    interface Listener {
        void onReady();
        void onPlaying();
        void onEnded();
        void onUnavailable(String reason);
    }

    private static final String TAG = "TrailerWebView";
    // Keep the embedding page distinct from YouTube itself; YouTube rejects a youtube.com page
    // embedding another youtube.com player (player error 152).
    private static final String APP_ORIGIN = "https://com.gathin.tvhome";
    private Listener listener;
    private boolean terminal;

    TrailerWebView(Context context, String videoId, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(Color.BLACK);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        // The surface only ever loads a fixed YouTube player: no files, no other origins, no cache.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // No YouTube account cookie or tracker is kept on the television.
        android.webkit.CookieManager.getInstance().setAcceptCookie(false);
        android.webkit.CookieManager.getInstance().removeAllCookies(null);
        setWebChromeClient(new WebChromeClient());
        setWebViewClient(new WebViewClient());
        addJavascriptInterface(new Bridge(), "TrailerBridge");
        loadDataWithBaseURL(APP_ORIGIN + "/", html(videoId), "text/html", "UTF-8", null);
    }

    private static String html(String videoId) {
        // IDs have already been validated by TrailerClient; keep the HTML value narrowly constrained.
        String id = videoId.replaceAll("[^A-Za-z0-9_-]", "");
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,height=device-height,initial-scale=1,maximum-scale=1,user-scalable=no\">"
                + "<style>*{box-sizing:border-box}html,body,#player{position:fixed;inset:0;margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;border:0}"
                + "iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head><body>"
                + "<div id=\"player\"></div><script>var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';"
                + "document.head.appendChild(tag);var player;function onYouTubeIframeAPIReady(){player=new YT.Player('player',{width:'100%',height:'100%',"
                + "videoId:'" + id + "',playerVars:{autoplay:0,controls:0,disablekb:1,fs:0,playsinline:1,rel:0,cc_load_policy:0,iv_load_policy:3,autohide:1,origin:'"
                + APP_ORIGIN + "'},"
                + "events:{onReady:function(e){e.target.cueVideoById('" + id + "');TrailerBridge.ready();},onStateChange:function(e){"
                + "if(e.data===YT.PlayerState.PLAYING)TrailerBridge.state(1);else if(e.data===YT.PlayerState.ENDED)TrailerBridge.state(0);},"
                + "onError:function(e){TrailerBridge.error('youtube_'+e.data);},onAutoplayBlocked:function(){TrailerBridge.error('autoplay_blocked');}}});}</script></body></html>";
    }

    void release() {
        terminal = true;
        listener = null;
        stopLoading();
        loadUrl("about:blank");
        removeJavascriptInterface("TrailerBridge");
        setWebChromeClient(null);
        setWebViewClient(null);
        clearCache(true);
        android.webkit.CookieManager.getInstance().removeAllCookies(null);
        android.webkit.CookieManager.getInstance().flush();
        destroy();
    }

    void startPlayback() {
        if (terminal) return;
        evaluateJavascript("if(player){player.setVolume(100);player.playVideo();}", null);
    }

    private final class Bridge {
        @JavascriptInterface public void ready() {
            post(() -> {
                if (terminal || listener == null) return;
                listener.onReady();
            });
        }

        @JavascriptInterface public void state(int state) {
            post(() -> {
                if (terminal || listener == null) return;
                if (state == 1) listener.onPlaying();
                else listener.onEnded();
            });
        }

        @JavascriptInterface public void error(String reason) {
            Log.w(TAG, "Trailer unavailable: " + reason);
            post(() -> {
                if (terminal || listener == null) return;
                terminal = true;
                listener.onUnavailable(reason);
            });
        }
    }
}
