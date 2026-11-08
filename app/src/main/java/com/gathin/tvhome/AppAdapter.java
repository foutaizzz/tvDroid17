package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad direction on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);
        boolean onTileHorizontalKey(int position, int keyCode);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.22f;

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private final Set<String> changedPackages = new HashSet<>();
    private volatile boolean loaded, dirty = true;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile int reloadGeneration;

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void invalidatePackage(String pkg) {
        dirty = true;
        if (pkg != null) changedPackages.add(pkg);
        else for (Entry entry : items) changedPackages.add(entry.pkg);
    }

    boolean reload() {
        if (loaded && !dirty) return false;
        return applyEntries(queryEntries());
    }

    /**
     * Runs the package query off the UI thread (queryIntentActivities + banner/icon loads can
     * jank a low-end TV), then applies the result on the main thread. {@code onDone} receives
     * whether the list changed; a newer reload supersedes a pending one.
     */
    void reloadAsync(Consumer<Boolean> onDone) {
        final int gen = ++reloadGeneration;
        dirty = true;
        io.execute(() -> {
            List<Entry> fresh = queryEntries();
            main.post(() -> {
                if (gen != reloadGeneration) return;
                boolean changed = applyEntries(fresh);
                if (onDone != null) onDone.accept(changed);
            });
        });
    }

    void cancelPendingReload() {
        reloadGeneration++;
    }

    void shutdown() {
        cancelPendingReload();
        io.shutdownNow();
    }

    /** Package queries and drawable loads; safe to run off the main thread. */
    private List<Entry> queryEntries() {
        PackageManager pm = ctx.getPackageManager();
        // TV apps declare LEANBACK_LAUNCHER; apps that only declare LAUNCHER are still usable
        // with a D-pad as long as they do not require a touchscreen (same rule as Play on TV).
        Map<ComponentName, ResolveInfo> found = new LinkedHashMap<>();
        Set<ComponentName> leanbackOnly = new HashSet<>();
        Intent leanbackProbe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(leanbackProbe, 0)) {
            if (ri.activityInfo == null) continue;
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            leanbackOnly.add(cn);
            found.put(cn, ri);
        }
        Intent launcherProbe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcherProbe, 0)) {
            if (ri.activityInfo == null) continue;
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            if (leanbackOnly.contains(cn) || found.containsKey(cn)) continue;
            if (requiresTouchscreen(pm, ri.activityInfo.packageName)) continue;
            found.put(cn, ri);
        }
        List<Entry> fresh = new ArrayList<>();
        Map<ComponentName, Entry> previous = new HashMap<>();
        synchronized (items) {
            for (Entry entry : items) previous.put(entry.component, entry);
        }
        Set<String> changed;
        synchronized (changedPackages) {
            changed = new HashSet<>(changedPackages);
        }
        for (ResolveInfo ri : found.values()) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Entry cached = previous.get(component);
            if (cached != null && !changed.contains(pkg)) {
                fresh.add(cached);
                continue;
            }
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, component, banner, icon));
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        return fresh;
    }

    /** Must run on the main thread: mutates the adapter list and notifies. */
    private boolean applyEntries(List<Entry> fresh) {
        loaded = true;
        dirty = false;
        synchronized (changedPackages) {
            changedPackages.clear();
        }
        synchronized (items) {
            if (fresh.equals(items)) return false;
            items.clear();
            items.addAll(fresh);
        }
        notifyDataSetChanged();
        return true;
    }

    /** True when the package declares android.hardware.touchscreen as required. */
    private static boolean requiresTouchscreen(PackageManager pm, String pkg) {
        try {
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            if (info.reqFeatures != null) {
                for (FeatureInfo f : info.reqFeatures) {
                    if (PackageManager.FEATURE_TOUCHSCREEN.equals(f.name)
                            && (f.flags & FeatureInfo.FLAG_REQUIRED) != 0) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.card.setContentDescription(e.label);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.GONE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            card.setSoundEffectsEnabled(false);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = v.getResources().getDimension(R.dimen.tile_radius);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int pos = getBindingAdapterPosition();
                    return listener != null && pos != RecyclerView.NO_POSITION
                            && listener.onTileHorizontalKey(pos, keyCode);
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                view.setElevation(focused ? density * 3f : 0f);
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f))
                        .start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}