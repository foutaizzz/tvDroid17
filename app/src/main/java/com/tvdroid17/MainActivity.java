package com.tvdroid17;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * tvOS style home with a pinned dock, app grid and local tile focus scaling.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {
    static final String EXTRA_OPEN_INPUTS = "com.tvdroid17.OPEN_INPUTS";
    private static java.lang.ref.WeakReference<MainActivity> foreground = new java.lang.ref.WeakReference<>(null);
    static boolean toggleForegroundControls() {
        MainActivity home = foreground.get();
        if (home == null || !home.homeResumed) return false;
        home.runOnUiThread(home::toggleControlPanel);
        return true;
    }
    static boolean toggleForegroundInputs() {
        MainActivity home = foreground.get();
        if (home == null || !home.homeResumed) return false;
        home.runOnUiThread(home::toggleInputs);
        return true;
    }

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private static final long STARTUP_ARTWORK_GRACE_MS = 10_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private DockDecoration dockDecoration;
    private ArtworkSlideshow artworkSlideshow;
    private ArtworkBackdropView artworkView;
    private View artworkTarget, artworkHint;
    private View artworkDetails;
    private View startupCover;
    private TrailerClient trailerClient;
    private final ExecutorService trailerWorker = Executors.newSingleThreadExecutor();
    private Future<?> trailerLookup;
    private ArtworkPolicy.Item trailerLookupItem;
    private int trailerLookupGeneration;
    private final Map<String, String> trailerKeyCache = new HashMap<>();
    private final Set<String> completedTrailerLookups = new HashSet<>();
    private int trailerGeneration;
    private boolean trailerDelayElapsed, trailerLookupDone, trailerSurfaceActive;
    private boolean trailerPlaying, trailerUiHidden, trailerAttempted, trailerPlayerReady, trailerPlayRequested;
    private String pendingTrailerKey;
    private final Runnable trailerDelay = () -> {
        trailerDelayElapsed = true;
        maybeStartTrailer(trailerGeneration);
    };
    private final Runnable trailerHideInfo = () -> hideTrailerDetails(trailerGeneration);
    private final Runnable trailerStartTimeout = () -> finishTrailer(trailerGeneration);
    private long detailsFrameRevision = -1;
    private boolean browsingApps;
    private boolean gridScrolledIntoApps;
    private int pendingArtworkFocus = RecyclerView.NO_POSITION;
    private int pendingVerticalTarget = RecyclerView.NO_POSITION;
    private int pendingVerticalDirection;
    private int drawerScrollGeneration;
    private boolean homeResumed;
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            adapter.invalidatePackage(intent.getData() == null ? null : intent.getData().getSchemeSpecificPart());
            if (!homeResumed) return;
            View focused = getCurrentFocus();
            boolean onTile = focused != null && focused.getId() == R.id.card;
            String key = lastFocusedKey;
            int slot = lastFocused;
            if (adapter.reload() && onTile) grid.post(() -> {
                int position = adapter.positionOf(key);
                focusTile(position >= 0 ? position : slot);
            });
        }
    };
    private ControlPanel controlPanel;
    private InputsPanel inputsPanel;
    private Drawable controlButtonMaterial;
    private boolean controlPanelOpen;
    private boolean fixedArtworkTest;
    private SettingsMenu settingsMenu;
    private TextView btnWifi;
    private boolean wifiPermissionToastShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Move mode (long press on a tile): position of the tile being moved, or NO_POSITION. */
    private int editingPosition = RecyclerView.NO_POSITION;
    /** Order snapshot taken when move mode starts, restored on cancel (Back). */
    private java.util.List<String> editingBackup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable startupCoverTimeout = this::revealStartupContent;
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
            handler.post(MainActivity.this::refreshArtworkConnectivity);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
            handler.post(MainActivity.this::refreshArtworkConnectivity);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            handler.post(MainActivity.this::refreshArtworkConnectivity);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        disableSoundEffects(root);
        artworkView = findViewById(R.id.artwork);
        startupCover = findViewById(R.id.startupCover);
        File testFiles = getExternalFilesDir(null);
        if (BuildConfig.DEBUG && testFiles != null) {
            File reference = new File(testFiles, "control-reference.jpg");
            if (reference.isFile()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap fixture = BitmapFactory.decodeFile(reference.getAbsolutePath(), options);
                if (fixture != null) {
                    fixedArtworkTest = true;
                    artworkView.setTestBackground(fixture);
                }
            }
        }
        artworkSlideshow = new ArtworkSlideshow(artworkView);
        trailerClient = new TrailerClient(this);
        artworkTarget = findViewById(R.id.artworkTarget);
        artworkHint = findViewById(R.id.artworkHint);
        artworkDetails = findViewById(R.id.artworkDetails);
        artworkView.setArtworkChangedListener(() -> {
            updateArtworkTarget();
            // ArtworkSlideshow only commits a frame after its background and logo (or text
            // fallback) are ready. Reveal the artwork and launcher chrome in the same draw.
            if (artworkView.currentItem() != null) revealStartupContent();
        });
        artworkTarget.setOnFocusChangeListener((v, focused) -> {
            if (focused) findViewById(R.id.summaryScroll).scrollTo(0, 0);
            if (focused) artworkView.setBackdropBlurred(false);
            if (focused) beginTrailerFocus();
            else clearTrailerFocus();
            artworkSlideshow.setHeld(focused);
            artworkView.setArtworkSelected(focused);
            artworkHint.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            artworkDetails.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            if (focused) artworkTarget.setAlpha(1f);
            else {
                artworkTarget.setAlpha(1f);
                artworkDetails.setAlpha(1f);
            }
            grid.animate().cancel();
            grid.setAlpha(1f);
        });
        artworkTarget.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0 && PlayTorrioDetails.isInstalled(this)) {
                    Intent detail = PlayTorrioDetails.intent(artworkView.currentItem());
                    if (detail != null) open(detail);
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (((SummaryScrollView) findViewById(R.id.summaryScroll)).moveReadingPosition(-1)) return true;
                if (event.getRepeatCount() > 0) return true;
                findViewById(R.id.btnSettings).requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (((SummaryScrollView) findViewById(R.id.summaryScroll)).moveReadingPosition(1)) return true;
                focusTile(Math.min(lastFocused, COLUMNS - 1));
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getRepeatCount() == 0) {
                    clearTrailerFocus();
                    artworkSlideshow.move(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                }
                return true;
            }
            return false;
        });
        artworkTarget.setOnClickListener(v -> {
            if (!PlayTorrioDetails.isInstalled(this)) return;
            Intent detail = PlayTorrioDetails.intent(artworkView.currentItem());
            if (detail != null) open(detail);
        });
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new TvGridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(COLUMNS, density, artworkView);
        inputsPanel = new InputsPanel(this, findViewById(R.id.btnSettings), dockDecoration.sharedGlass());
        if (artworkHint != null) artworkHint.setBackground(dockDecoration.materialFor(artworkHint, 17f));
        View statusCapsule = findViewById(R.id.statusCapsule);
        if (statusCapsule != null) statusCapsule.setBackground(dockDecoration.materialFor(statusCapsule, 16f));
        View topButtons = findViewById(R.id.topButtons);
        if (topButtons != null) topButtons.setTranslationY(-2f * density);
        View clock = findViewById(R.id.clock);
        if (clock != null) clock.setTranslationX(4f * density);
        View controlButton = findViewById(R.id.btnSettings);
        if (controlButton != null) {
            // Preserve the existing right-side icon position; inset only its circular backdrop.
            controlButton.setTranslationX(4f * density);
            controlButtonMaterial = new InsetDrawable(
                    dockDecoration.controlButtonMaterial(controlButton, 14f), Math.round(0f * density));
            controlButton.setBackground(null);
            controlButton.setOnFocusChangeListener((v, focused) -> updateControlButtonAppearance());
        }
        grid.addItemDecoration(dockDecoration);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.025f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = getResources().getDimensionPixelSize(R.dimen.tile_row_height)
                    + (int) (18 * density);
            int want = Math.max(0, h - dockRow
                    - getResources().getDimensionPixelSize(R.dimen.dock_bottom_clearance));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            float cell = (grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight()) / (float) COLUMNS;
            float tile = getResources().getDimension(R.dimen.tile_width);
            findViewById(R.id.topButtons).setTranslationX(
                    getResources().getDimension(R.dimen.dock_inset) - (cell - tile) / 2f);
        });


        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> toggleInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> toggleControlPanel());

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (v.getId() == R.id.btnSettings) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!focusArtwork()) focusTile(Math.min(lastFocused, COLUMNS - 1));
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);


        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        // Required when targeting Android 14: our own broadcast, so it must not be exported.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packagesChanged, packages, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packagesChanged, packages);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The firmware can drop the accessibility service on reinstall/force-stop/reboot:
        // re-assert it (and verify via AccessibilityManager) every time Home comes back.
        A11yRebinder.ensureBound(this);
        ScreensaverBacklight.restore(this);
        updateArtworkTarget();
        artworkView.setBackdropBlurred(false);
        homeResumed = true;
        startupCover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(startupCoverTimeout);
        handler.postDelayed(startupCoverTimeout, STARTUP_ARTWORK_GRACE_MS);
        foreground = new java.lang.ref.WeakReference<>(this);
        drawerScrollGeneration++;
        browsingApps = false;
        // Start networking while package illustrations are being loaded, without delaying first draw.
        artworkSlideshow.resume(hasInternet());
        // The system may have trimmed the dock blur while Home was hidden; recapture once visible.
        handler.postDelayed(() -> { if (dockDecoration != null) dockDecoration.refreshIfNeeded(); }, 500);
        handler.postDelayed(() -> { if (dockDecoration != null) dockDecoration.refreshIfNeeded(); }, 2000);
        // Read these before the reload applies: rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // The package query runs off the UI thread; refocus once the list is applied. Always
        // refocus: after the reload the view that holds focus may no longer show the app it had.
        adapter.reloadAsync(changed -> {
            if (!homeResumed) return;
            if (changed) grid.scheduleLayoutAnimation();
            grid.post(() -> {
                int pos = adapter.positionOf(returnKey);
                focusTile(pos >= 0 ? pos : returnSlot);
                if (getIntent().getBooleanExtra(EXTRA_OPEN_INPUTS, false)) {
                    getIntent().removeExtra(EXTRA_OPEN_INPUTS);
                    showInputs();
                }
            });
        });
    }

    private void revealStartupContent() {
        if (!homeResumed || startupCover == null) return;
        handler.removeCallbacks(startupCoverTimeout);
        startupCover.setVisibility(View.GONE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_OPEN_INPUTS, false)) {
            if (homeResumed) {
                intent.removeExtra(EXTRA_OPEN_INPUTS);
                runOnUiThread(this::toggleInputs);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        artworkView.setBackdropBlurred(false);
        homeResumed = false;
        if (foreground.get() == this) foreground.clear();
        dismissInputs();
        drawerScrollGeneration++;
        if (controlPanel != null) controlPanel.dismiss();
        if (settingsMenu != null) settingsMenu.dismiss();
        handler.removeCallbacks(startupCoverTimeout);
        artworkSlideshow.pause();
        adapter.cancelPendingReload();
        clearTrailerFocus();
        cancelTrailerLookup();
        handler.removeCallbacks(minuteTick);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Free the shared blurred bitmaps while another app is in front; the next artwork
        // frame recaptures them, so nothing changes visually on return.
        if (level >= TRIM_MEMORY_UI_HIDDEN && dockDecoration != null) dockDecoration.releaseBackdrop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        adapter.shutdown();
        clearTrailerFocus();
        cancelTrailerLookup();
        trailerWorker.shutdownNow();
        artworkSlideshow.close();
        artworkView.close();
        unregisterReceiver(packagesChanged);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onBackPressed() {
        if (editingPosition != RecyclerView.NO_POSITION) {
            exitEditing(true);   // Back cancels the move and restores the previous order
            return;
        }
        if (inputsPanel != null && inputsPanel.isShowing()) {
            dismissInputs();
            return;
        }
        if (artworkTarget.hasFocus() && (trailerSurfaceActive || trailerPlaying || trailerUiHidden)) {
            finishTrailer(trailerGeneration);
            return;
        }
        if (artworkTarget.hasFocus()) focusTile(Math.min(lastFocused, COLUMNS - 1));
    }

    private void beginTrailerFocus() {
        clearTrailerFocus();
        // The focus may have been retained across an activity return, so do not rely only on the
        // focus-change listener to freeze the slideshow timer.
        artworkSlideshow.setHeld(true);
        int generation = trailerGeneration;
        ArtworkPolicy.Item selected = artworkView.currentItem();
        trailerAttempted = false;
        trailerDelayElapsed = false;
        trailerPlayerReady = false;
        trailerPlayRequested = false;
        if (selected == null || trailerClient == null || !trailerClient.isConfigured()
                || !PlayTorrioDetails.supports(selected)) return;
        String itemKey = trailerItemKey(selected);
        trailerLookupDone = completedTrailerLookups.contains(itemKey);
        pendingTrailerKey = trailerKeyCache.get(itemKey);
        handler.postDelayed(trailerDelay, 5_000L);
        prepareTrailerLookup(selected);
        maybeStartTrailer(generation);
    }

    private void maybeStartTrailer(int generation) {
        if (generation != trailerGeneration || !artworkTarget.hasFocus() || !trailerLookupDone) return;
        if (!trailerSurfaceActive) {
            if (trailerAttempted || pendingTrailerKey == null || pendingTrailerKey.isEmpty()) return;
            trailerAttempted = true;
            // The WebView and YouTube player warm up behind the still image; playback remains
            // paused until the five-second dwell has elapsed.
            artworkSlideshow.setHeld(true);
            trailerSurfaceActive = true;
            trailerPlaying = false;
            artworkView.prepareTrailer(pendingTrailerKey, new TrailerWebView.Listener() {
                @Override public void onReady() {
                    if (generation != trailerGeneration || !artworkTarget.hasFocus()) return;
                    trailerPlayerReady = true;
                    maybeStartTrailer(generation);
                }

            @Override public void onPlaying() {
                if (generation != trailerGeneration || !artworkTarget.hasFocus()) return;
                artworkSlideshow.setHeld(true);
                trailerPlaying = true;
                handler.removeCallbacks(trailerStartTimeout);
                // Let YouTube's transient play overlay disappear while the video surface is
                // still hidden. The player keeps running, so this does not change the 5s start.
                handler.postDelayed(() -> {
                    if (generation == trailerGeneration && trailerPlaying && artworkTarget.hasFocus()) {
                        artworkView.revealTrailer();
                    }
                }, 900L);
                handler.postDelayed(trailerHideInfo, 4_000L);
            }

            @Override public void onEnded() {
                if (generation == trailerGeneration) finishTrailer(generation);
            }

            @Override public void onUnavailable(String reason) {
                if (generation == trailerGeneration) finishTrailer(generation);
            }
            });
        }
        if (trailerDelayElapsed && trailerPlayerReady && !trailerPlayRequested) {
            trailerPlayRequested = true;
            artworkView.startTrailer();
            handler.postDelayed(trailerStartTimeout, 15_000L);
        }
    }

    private String trailerItemKey(ArtworkPolicy.Item item) {
        return item.type + ":" + item.id;
    }

    private void prepareTrailerLookup(ArtworkPolicy.Item item) {
        if (item == null || trailerClient == null || !trailerClient.isConfigured()
                || !PlayTorrioDetails.supports(item)) return;
        String key = trailerItemKey(item);
        if (completedTrailerLookups.contains(key)) {
            if (artworkView.currentItem() == item) {
                trailerLookupDone = true;
                pendingTrailerKey = trailerKeyCache.get(key);
                if (artworkTarget.hasFocus()) maybeStartTrailer(trailerGeneration);
            }
            return;
        }
        if (item == trailerLookupItem && trailerLookup != null) return;

        if (trailerLookup != null) trailerLookup.cancel(true);
        trailerClient.cancel();
        trailerLookupItem = item;
        trailerLookupDone = false;
        pendingTrailerKey = null;
        int lookupGeneration = ++trailerLookupGeneration;
        trailerLookup = trailerWorker.submit(() -> {
            String result = null;
            try { result = trailerClient.findTrailerKey(item); }
            catch (Exception error) { android.util.Log.w("Trailer", "Trailer lookup failed", error); }
            final String trailerKey = result;
            handler.post(() -> {
                if (lookupGeneration != trailerLookupGeneration) return;
                trailerLookup = null;
                trailerLookupItem = null;
                trailerKeyCache.put(key, trailerKey);
                completedTrailerLookups.add(key);
                if (artworkView.currentItem() != item) return;
                trailerLookupDone = true;
                pendingTrailerKey = trailerKey;
                if (artworkTarget.hasFocus()) maybeStartTrailer(trailerGeneration);
            });
        });
    }

    private void hideTrailerDetails(int generation) {
        if (generation != trailerGeneration || !trailerPlaying || !artworkTarget.hasFocus()) return;
        trailerUiHidden = true;
        artworkTarget.animate().cancel();
        artworkTarget.animate().alpha(0f).setDuration(400L).start();
        artworkView.setTopTitleVisible(false, 400L);
        animateHomeChrome(false, 400L);
    }

    private void finishTrailer(int generation) {
        if (generation != trailerGeneration || !trailerSurfaceActive) return;
        handler.removeCallbacks(trailerStartTimeout);
        handler.removeCallbacks(trailerHideInfo);
        trailerSurfaceActive = false;
        trailerPlaying = false;
        trailerUiHidden = false;
        artworkTarget.animate().cancel();
        artworkTarget.setAlpha(0f);
        artworkTarget.setVisibility(View.VISIBLE);
        artworkView.setTopTitleVisible(true, 350L);
        artworkTarget.animate().alpha(1f).setDuration(350L).start();
        animateHomeChrome(true, 350L);
        artworkView.fadeOutTrailer(null);
    }

    private void animateHomeChrome(boolean visible, long duration) {
        View topButtons = findViewById(R.id.topButtons);
        animateAlpha(topButtons, visible, duration);
        animateAlpha(grid, visible, duration);
    }

    private void animateAlpha(View view, boolean visible, long duration) {
        if (view == null) return;
        view.animate().cancel();
        if (visible) view.setAlpha(0f);
        view.animate().alpha(visible ? 1f : 0f).setDuration(duration).start();
    }

    private void clearTrailerFocus() {
        boolean restoreChrome = trailerUiHidden;
        trailerGeneration++;
        handler.removeCallbacks(trailerDelay);
        handler.removeCallbacks(trailerHideInfo);
        handler.removeCallbacks(trailerStartTimeout);
        trailerSurfaceActive = false;
        trailerPlaying = false;
        trailerUiHidden = false;
        trailerAttempted = false;
        trailerDelayElapsed = false;
        trailerPlayerReady = false;
        trailerPlayRequested = false;
        if (artworkTarget != null) {
            artworkTarget.animate().cancel();
            artworkTarget.setAlpha(1f);
        }
        if (artworkView != null) {
            artworkView.stopTrailer();
            artworkView.setTopTitleVisible(true, 0L);
        }
        if (restoreChrome) animateHomeChrome(true, 350L);
    }

    private void cancelTrailerLookup() {
        trailerLookupGeneration++;
        if (trailerLookup != null) {
            trailerLookup.cancel(true);
            trailerLookup = null;
        }
        trailerLookupItem = null;
        if (trailerClient != null) trailerClient.cancel();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS || event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) toggleControlPanel();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && inputsPanel != null && inputsPanel.isShowing()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) dismissInputs();
            return true;
        }
        // Other firmware versions deliver Source directly to the foreground activity.
        if (event.getKeyCode() == KeyEvent.KEYCODE_TV_INPUT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                toggleInputs();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- tile focus -----------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        artworkView.setBackdropBlurred(position >= COLUMNS);
        if (pendingArtworkFocus != RecyclerView.NO_POSITION && pendingArtworkFocus != position)
            pendingArtworkFocus = RecyclerView.NO_POSITION;
        int verticalDirection = position == pendingVerticalTarget ? pendingVerticalDirection : 0;
        if (position == pendingVerticalTarget) {
            pendingVerticalTarget = RecyclerView.NO_POSITION;
            pendingVerticalDirection = 0;
        }
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            gridScrolledIntoApps = false;
            setBrowsingApps(false);
            // Dock: park it at the bottom of the screen.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            scrollDock(false);
        } else {
            setBrowsingApps(true);
            scrollTileIntoView(card, verticalDirection);
        }
    }

    // ---- navigation ------------------------------------------------------------------------------

    private void setBrowsingApps(boolean browsing) {
        browsingApps = browsing;
    }

    private void scrollDock(boolean expanded) {
        int version = ++drawerScrollGeneration;
        // RecyclerView makes its own focus rectangle request after the focus callback.
        // Apply our destination afterward so that request cannot cancel the drawer transition.
        grid.post(() -> {
            if (version != drawerScrollGeneration || browsingApps != expanded || !homeResumed) return;
            View first = grid.getLayoutManager().findViewByPosition(0);
            if (first == null) return;
            grid.stopScroll();
            int desired = Math.round(6 * density) + (expanded ? 0 : grid.getPaddingTop());
            int dy = first.getTop() - desired;
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy,
                    new PathInterpolator(.22f, 1f, .36f, 1f), 320);
        });
    }

    private void scrollTileIntoView(View card, int verticalDirection) {
        int version=++drawerScrollGeneration;
        grid.post(()->{
            if(version!=drawerScrollGeneration || !browsingApps || !homeResumed || !card.hasFocus())return;
            grid.stopScroll();
            int dy;
            if (verticalDirection < 0 && gridScrolledIntoApps && grid.canScrollVertically(-1)) {
                // Reverse the downward grid movement one full row at a time as focus moves up.
                dy = -getResources().getDimensionPixelSize(R.dimen.tile_row_height);
            } else {
                View item=(View)card.getParent();
                float pad=card.getHeight()*(AppAdapter.FOCUS_SCALE-1f)/2f;
                float top=item.getTop()+card.getTop()-pad;
                float bottom=item.getTop()+card.getTop()+card.getHeight()+pad;
                int viewportTop=Math.round(6*density);
                int viewportBottom=grid.getHeight()-grid.getPaddingBottom();
                dy=bottom>viewportBottom?Math.round(bottom-viewportBottom)
                        :top<viewportTop?Math.round(top-viewportTop):0;
            }
            if (dy > 1) gridScrolledIntoApps = true;
            if(Math.abs(dy)>1)grid.smoothScrollBy(0,dy,new PathInterpolator(.22f,1f,.36f,1f),260);
        });
    }

    private boolean focusArtwork() {
        return PlayTorrioDetails.isInstalled(this)
                && PlayTorrioDetails.supports(artworkView.currentItem()) && artworkTarget.requestFocus();
    }

    private void updateArtworkTarget() {
        boolean hadFocus = artworkTarget.hasFocus();
        ArtworkPolicy.Item item = artworkView.currentItem();
        boolean available = PlayTorrioDetails.isInstalled(this) && PlayTorrioDetails.supports(item);
        boolean controlsVisible = artworkView.controlsVisible();
        View details = findViewById(R.id.artworkDetails);
        long frameRevision = artworkView.frameRevision();
        boolean newFrame = frameRevision != detailsFrameRevision;
        if (newFrame) {
            detailsFrameRevision = frameRevision;
            details.animate().cancel();
            details.setAlpha(0f);
            if (!controlsVisible) details.animate().alpha(1f).setDuration(600).start();
            // Resolve trailer metadata as soon as this film or series is displayed. If selected,
            // the player can then warm up during the five-second dwell instead of starting cold.
            if (available) prepareTrailerLookup(item);
            if (hadFocus) beginTrailerFocus();
        }
        artworkTarget.setVisibility(available ? (controlsVisible ? View.INVISIBLE : View.VISIBLE) : View.GONE);
        artworkTarget.setFocusable(available && !controlsVisible);
        if (controlsVisible) details.setVisibility(View.INVISIBLE);
        if (available) artworkTarget.setContentDescription("Ouvrir " + item.title + " dans PlayTorrio");
        else if (hadFocus && homeResumed && !controlsVisible) focusTile(Math.min(lastFocused, COLUMNS - 1));
        if (available) {
            TextView genres = findViewById(R.id.artworkGenres);
            genres.setText(("series".equals(item.type) ? "Série" : "Film")
                    + (item.genres.isEmpty() ? "" : " · " + item.genres));
            TextView description = findViewById(R.id.artworkDescription);
            boolean newDescription = !description.getText().toString().equals(item.description);
            description.setText(item.description);
            boolean hideUntilLocalized = artworkView.isDescriptionPending(item);
            description.setVisibility(item.description.isEmpty() || hideUntilLocalized ? View.GONE : View.VISIBLE);
            View summary = findViewById(R.id.summaryScroll);
            summary.setVisibility(item.description.isEmpty() || hideUntilLocalized ? View.GONE : View.VISIBLE);
            if (newDescription) summary.scrollTo(0, 0);
            artworkView.post(() -> {
                if (artworkView.currentItem() != item) return;
                FrameLayout.LayoutParams box = (FrameLayout.LayoutParams) artworkTarget.getLayoutParams();
                box.topMargin = artworkView.titleBottom() + Math.round(14 * density);
                artworkTarget.setLayoutParams(box);
                ((SummaryScrollView) summary).setMaximumHeight((int) (root.getHeight() * .70f)
                        - box.topMargin - genres.getMeasuredHeight() - Math.round(70 * density));
            });
            if (pendingArtworkFocus != RecyclerView.NO_POSITION) {
                int requestedFrom = pendingArtworkFocus;
                artworkTarget.post(() -> {
                    if (pendingArtworkFocus != requestedFrom) return;
                    View focused = getCurrentFocus();
                    if (focused == null || focused.getId() != R.id.card || lastFocused != requestedFrom) {
                        pendingArtworkFocus = RecyclerView.NO_POSITION;
                        return;
                    }
                    pendingArtworkFocus = RecyclerView.NO_POSITION;
                    focusArtwork();
                });
            }
        }
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        if (editingPosition != RecyclerView.NO_POSITION) {
            moveEditingVertical(position, keyCode);
            return true;
        }
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                if (focusArtwork()) {
                    pendingArtworkFocus = RecyclerView.NO_POSITION;
                    return true;
                }
                // The first catalogue frame may still be loading just after Home resumes.
                // Hold this request here so Android cannot jump to the clock/settings capsule.
                pendingArtworkFocus = position;
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        pendingVerticalTarget = target;
        pendingVerticalDirection = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
        focusTile(target);
        return true;
    }

    @Override
    public boolean onTileHorizontalKey(int position, int keyCode) {
        if (editingPosition != RecyclerView.NO_POSITION) {
            moveEditingHorizontal(position, keyCode);
            return true;
        }
        int count = adapter.getItemCount();
        int rowStart = (position / COLUMNS) * COLUMNS;
        int rowEnd = Math.min(rowStart + COLUMNS, count) - 1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (position > rowStart) {
                focusTile(position - 1);
                return true;
            }
            // Preserve the dock's existing left-edge stop; later rows wrap to their previous row.
            if (rowStart >= COLUMNS) {
                int target = rowStart - 1;
                // This wrap also moves focus up one row, so reverse the grid by one row too.
                pendingVerticalTarget = target;
                pendingVerticalDirection = -1;
                focusTile(target);
            }
            return true;
        }
        if (position < rowEnd) {
            focusTile(position + 1);
            return true;
        }
        // Right at a row edge continues at the beginning of the next row instead of escaping
        // upward to the settings capsule or into another focusable view.
        if (rowEnd + 1 < count) focusTile(rowEnd + 1);
        return true;
    }

    // ---- move mode (long press on OK) -------------------------------------------

    @Override
    public boolean onTileLongClick(int position) {
        if (editingPosition != RecyclerView.NO_POSITION) return true; // already moving
        editingPosition = position;
        editingBackup = adapter.snapshotOrder();
        adapter.setEditingPosition(position);
        Toast.makeText(this, "Déplacer : flèches pour déplacer, OK pour valider, Retour pour annuler",
                Toast.LENGTH_LONG).show();
        return true;
    }

    @Override
    public boolean isEditing() {
        return editingPosition != RecyclerView.NO_POSITION;
    }

    @Override
    public void onTileValidate() {
        exitEditing(false);
    }

    /** Swap the moved tile with the neighbour in the given direction, like normal navigation. */
    private void moveEditingVertical(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target = -1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return;            // stay in the dock, never jump to the artwork
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return;      // already on the last row
            target = Math.min(position + COLUMNS, count - 1);
        }
        moveEditingTo(target);
    }

    private void moveEditingHorizontal(int position, int keyCode) {
        int count = adapter.getItemCount();
        int rowStart = (position / COLUMNS) * COLUMNS;
        int rowEnd = Math.min(rowStart + COLUMNS, count) - 1;
        int target = -1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (position > rowStart) target = position - 1;
            else if (rowStart >= COLUMNS) target = rowStart - 1;   // wrap to the previous row's end
        } else {
            if (position < rowEnd) target = position + 1;
            else if (rowEnd + 1 < count) target = rowEnd + 1;       // wrap to the next row's start
        }
        moveEditingTo(target);
    }

    private void moveEditingTo(int target) {
        if (target < 0 || target == editingPosition) return;
        adapter.moveItem(editingPosition, target);
        editingPosition = target;
        adapter.setEditingPosition(target);
        focusTile(target);
    }

    private void exitEditing(boolean cancel) {
        int pos = editingPosition;
        editingPosition = RecyclerView.NO_POSITION;
        adapter.setEditingPosition(RecyclerView.NO_POSITION);
        if (cancel && editingBackup != null) adapter.restoreOrder(editingBackup);
        editingBackup = null;
        if (pos != RecyclerView.NO_POSITION && adapter.getItemCount() > 0)
            focusTile(Math.min(pos, adapter.getItemCount() - 1));
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Restore the shelf even when the same card kept focus while another app was open.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private boolean hasInternet() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void refreshArtworkConnectivity() {
        if (artworkSlideshow != null) artworkSlideshow.setOnline(hasInternet());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean canReadSsid = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = canReadSsid && wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                } else if (!canReadSsid && !wifiPermissionToastShown) {
                    wifiPermissionToastShown = true;
                    Toast.makeText(this, R.string.wifi_permission_needed, Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception ignored) {
        }
        btnWifi.setContentDescription(getString(R.string.wifi) + ": " + text);
        btnWifi.setTooltipText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** TV remotes should move focus silently; sound effects stay enabled on other form factors. */
    private static void disableSoundEffects(View view) {
        view.setSoundEffectsEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) disableSoundEffects(group.getChildAt(i));
        }
    }

    private void toggleControlPanel() {
        if (controlPanel != null && controlPanel.isShowing()) {
            controlPanel.dismiss();
            return;
        }
        if (controlPanel != null) controlPanel.dismiss();
        {
            Runnable[] actions = new Runnable[12];
            actions[ControlPanel.POWER] = this::showPowerOptions;
            actions[ControlPanel.SETTINGS] = () -> open(DeviceSettingsActivity.intent(this, "device"));
            actions[ControlPanel.WIFI] = () -> open(DeviceSettingsActivity.intent(this, "wifi"));
            actions[ControlPanel.AUDIO] = () -> open(new Intent(Settings.ACTION_SOUND_SETTINGS));
            actions[ControlPanel.TIMER] = () -> openTvSettings(".partnercustomizer.timer.TimerActivity");
            actions[ControlPanel.DEVICES] = () -> openTvSettings(".accessories.AddAccessoryActivity");
            actions[ControlPanel.CAPTIONS] = () -> open(DeviceSettingsActivity.intent(this, "captions"));
            actions[ControlPanel.SOURCES] = this::showInputs;
            actions[ControlPanel.APPS] = () -> focusTile(Math.min(COLUMNS, adapter.getItemCount() - 1));
            // Long-press on the Applications tile: system details of the currently focused app.
            actions[ControlPanel.APP_INFO] = () -> {
                String pkg = adapter.pkgAt(lastFocused);
                if (pkg != null) AppAdapter.openDetails(this, pkg);
            };
            actions[ControlPanel.HOME] = () -> focusTile(Math.min(lastFocused, COLUMNS - 1));
            String network = btnWifi.getTooltipText() == null ? "" : btnWifi.getTooltipText().toString();
            if (network.equals(getString(R.string.wifi))) network = hasInternet() ? "Connecté" : "";
            controlPanel = new ControlPanel(this, findViewById(R.id.btnSettings), actions, network,
                    dockDecoration.sharedGlass());
            controlPanel.setOnClosed(() -> {
                controlPanelOpen = false;
                updateControlButtonAppearance();
            });
            controlPanel.setOnFocusChanged(this::updateControlButtonAppearance);
        }
        controlPanelOpen = true;
        updateControlButtonAppearance();
        controlPanel.show(root);
    }

    private void updateControlButtonAppearance() {
        View button = findViewById(R.id.btnSettings);
        ImageView icon = findViewById(R.id.btnSettingsIcon);
        if (button == null) return;
        boolean focused = button.hasFocus();
        // While the panel is open, the focus sits on the invisible proxy parked over this button
        // right after opening: treat that as "focused on the button" so both entry paths (SET key
        // and clicking the button) look identical.
        boolean anchorFocused = focused
                || (controlPanel != null && controlPanel.isShowing() && controlPanel.isAnchorFocused());
        if (controlPanelOpen && controlPanel != null && controlPanel.isShowing()) {
            // Panel open: circle always visible. White + dark icon when the focus is on the
            // button (or its anchor proxy), black + white icon when navigating inside the panel.
            if (anchorFocused) {
                GradientDrawable white = new GradientDrawable();
                white.setShape(GradientDrawable.OVAL);
                white.setColor(Color.WHITE);
                button.setBackground(white);
                if (icon != null) icon.setColorFilter(0xFF0B0C08);
            } else {
                button.setBackground(controlButtonMaterial);
                if (icon != null) icon.setColorFilter(Color.WHITE);
            }
        } else {
            button.setBackground(focused ? controlButtonMaterial : null);
            if (icon != null) icon.setColorFilter(Color.WHITE);
        }
    }

    private void showSettingsMenu() {
        if (settingsMenu != null) settingsMenu.dismiss();
        Runnable[] actions = {
                () -> openTvSettings(".partnercustomizer.picture.PictureActivity"),
                () -> open(new Intent(Settings.ACTION_SOUND_SETTINGS)),
                () -> openTvSettings(".device.display.daydream.DaydreamActivity"),
                () -> open(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)),
                () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)),
                () -> openTvSettings(".accessories.AddAccessoryActivity"),
                () -> openTvSettings(".system.CaptionSetupActivity"),
                () -> open(new Intent(Settings.ACTION_SETTINGS))
        };
        settingsMenu = new SettingsMenu(this, findViewById(R.id.btnSettings), actions);
        settingsMenu.show(root);
    }

    private void openTvSettings(String activity) {
        open(new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings" + activity));
    }

    private void showPowerOptions() {
        if (!SourceKeyService.showPowerOptions()) {
            new android.app.AlertDialog.Builder(this).setTitle("Alimentation")
                    .setMessage("L'arrêt complet est protégé par Android. Le menu d'alimentation système n'est pas disponible actuellement. Utilisez le bouton d'alimentation de la télécommande.")
                    .setPositiveButton("Fermer", null).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void toggleInputs() {
        if (inputsPanel == null) return;
        if (controlPanel != null) controlPanel.dismiss();
        inputsPanel.toggle();
    }

    private void dismissInputs() {
        if (inputsPanel != null) inputsPanel.dismiss();
    }

    private void showInputs() {
        if (inputsPanel == null) return;
        if (controlPanel != null) controlPanel.dismiss();
        inputsPanel.show();
    }
}
