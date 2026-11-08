package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Future;

/** Owns transient artwork requests; lifecycle/network changes invalidate every old callback. */
final class ArtworkSlideshow {
    private static final long INTERVAL_MS = 10_000L;
    private static final long CATALOGUE_AGE_MS = 86_400_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService descriptionWorker = Executors.newSingleThreadExecutor();
    private final CinemetaClient client = new CinemetaClient();
    private final FrenchDescriptionClient frenchDescriptions;
    private final ArtworkBackdropView view;
    private final CatalogueStore store;
    private List<ArtworkPolicy.Item> catalogue = new ArrayList<>();
    private long catalogueTime;
    private int position, generation, displayedPosition = -1, requestedPosition = -1;
    private int descriptionGeneration;
    private int failedPosition = -1;
    private int consecutiveFailures;
    private boolean resumed, online, held, manualNavigation;
    private Future<?> pending;
    private Future<?> pendingDescription;
    private final Set<String> descriptionRequests = Collections.synchronizedSet(new HashSet<>());
    private final Runnable next = this::loadNext;

    ArtworkSlideshow(ArtworkBackdropView view) {
        this.view = view;
        store = new CatalogueStore(view.getContext());
        frenchDescriptions = new FrenchDescriptionClient(view.getContext());
        catalogue = store.read();
        if (!catalogue.isEmpty()) catalogueTime = SystemClock.elapsedRealtime() - store.age();
    }

    void resume(boolean connected) {
        held = false;
        manualNavigation = false;
        resumed = true;
        online = connected;
        consecutiveFailures = 0;
        cancelAndClear();
        if (online) loadNext();
    }

    void setOnline(boolean connected) {
        if (online == connected) return;
        online = connected;
        consecutiveFailures = 0;
        cancelAndClear();
        cancelDescriptionLoads();
        if (resumed && online) loadNext();
    }

    void pause() {
        resumed = false;
        held = false;
        manualNavigation = false;
        cancelAndClear();
        cancelDescriptionLoads();
    }

    void close() {
        pause();
        worker.shutdownNow();
        descriptionWorker.shutdownNow();
    }

    /** Keep the visible title stable while the user selects it, without retaining an offline image. */
    void setHeld(boolean selected) {
        if (held == selected) {
            if (selected) {
                // Always clear a queued advance on selection. A repeated focus notification can
                // arrive after a slide callback has already queued the next item.
                main.removeCallbacks(next);
                cancelPendingForSelection();
            }
            return;
        }
        main.removeCallbacks(next);
        held = selected;
        if (selected) manualNavigation = false;
        if (selected) cancelPendingForSelection();
        else if (resumed && online) main.postDelayed(next, INTERVAL_MS);
    }

    private void cancelPendingForSelection() {
        // Freeze an upcoming slide, but let the visible title's optional logo finish loading.
        if (requestedPosition >= 0) {
            generation++;
            if (pending != null) pending.cancel(true);
            client.cancel();
            requestedPosition = -1;
            view.resolvePendingTitle();
        }
    }

    /** Manual navigation stays available while the automatic carousel is held on selection. */
    void move(int direction) {
        if (!resumed || !online || catalogue.isEmpty() || direction == 0) return;
        manualNavigation = true;
        int from = requestedPosition >= 0 ? requestedPosition
                : failedPosition >= 0 ? failedPosition : displayedPosition;
        int target = Math.floorMod(Math.max(0, from) + (direction < 0 ? -1 : 1), catalogue.size());
        generation++;
        main.removeCallbacks(next);
        if (pending != null) pending.cancel(true);
        client.cancel();
        failedPosition = -1;
        loadSlot(target, true);
    }

    private void cancelAndClear() {
        generation++;
        main.removeCallbacks(next);
        if (pending != null) pending.cancel(true);
        client.cancel();
        requestedPosition = -1;
        failedPosition = -1;
        displayedPosition = -1;
        view.clear();
    }

    private void loadNext() {
        if (!resumed || !online || !ArtworkPolicy.canAutoAdvance(held, manualNavigation)) return;
        loadSlot(position, false);
    }

    private void loadSlot(int target, boolean manual) {
        final int version = ++generation;
        final List<ArtworkPolicy.Item> known = catalogue;
        final boolean refresh = known.isEmpty() || SystemClock.elapsedRealtime() - catalogueTime >= CATALOGUE_AGE_MS;
        final int slot = target;
        final boolean firstBackdrop = displayedPosition < 0;
        requestedPosition = slot;
        pending = worker.submit(() -> {
            try {
                List<ArtworkPolicy.Item> items = refresh ? client.catalogue() : known;
                if (Thread.currentThread().isInterrupted()) return;
                if (items.isEmpty()) throw new IOException("Empty artwork catalogue");
                if (refresh) main.post(() -> {
                    if (version != generation || !resumed || !online) return;
                    // A bad first image must not make every retry fetch the same catalogue again.
                    catalogue = items;
                    catalogueTime = SystemClock.elapsedRealtime();
                    store.save(items);
                });
                ArtworkPolicy.Item item = items.get(slot % items.size());
                // A subsequent slide is committed once, with its logo or definitive text fallback.
                // Only the first background may appear early, with its pending title hidden.
                CinemetaClient.Frame frame;
                if (firstBackdrop) {
                    CinemetaClient.Frame background = client.loadBackground(item);
                    frame = new CinemetaClient.Frame(item, background.background, null, true);
                } else frame = client.load(item);
                main.post(() -> {
                    if (version != generation || !resumed || !online) return;
                    catalogue = items;
                    consecutiveFailures = 0;
                    position = (slot + 1) % items.size();
                    displayedPosition = slot % items.size();
                    failedPosition = -1;
                    requestedPosition = -1;
                    boolean translate = frenchDescriptions.isConfigured();
                    // Manual navigation displays the loaded title directly; only the automatic
                    // carousel crossfades.
                    view.show(frame, translate, manual);
                    loadFrenchDescription(item, frame.item.description);
                    scheduleNext(INTERVAL_MS);
                });
                if (firstBackdrop) {
                    android.graphics.Bitmap logo = client.loadLogo(item);
                    main.post(() -> {
                        if (version == generation && resumed && online) view.updateLogo(item, logo);
                    });
                }
            } catch (IOException | RuntimeException unavailable) {
                android.util.Log.w("TvHomeArtwork", "Cinemeta artwork unavailable", unavailable);
                main.post(() -> {
                    if (version != generation || !resumed || !online) return;
                    requestedPosition = -1;
                    failedPosition = slot;
                    position = Math.floorMod(slot + 1, Math.max(1, catalogue.size()));
                    // A failed replacement is not a disconnection. Keep the last good frame
                    // until another one is ready; pause/offline still release all artwork.
                    view.resolvePendingTitle();
                    consecutiveFailures++;
                    long retry = Math.min(INTERVAL_MS, 1_000L << Math.min(4, consecutiveFailures - 1));
                    scheduleNext(retry);
                });
            }
        });
    }

    private void scheduleNext(long delay) {
        main.removeCallbacks(next);
        if (ArtworkPolicy.canAutoAdvance(held, manualNavigation) && resumed && online)
            main.postDelayed(next, delay);
    }

    private void loadFrenchDescription(ArtworkPolicy.Item item, String fallback) {
        if (!frenchDescriptions.isConfigured()) return;
        String key = item.type + ":" + item.id;
        if (!descriptionRequests.add(key)) return;
        int descriptionVersion = descriptionGeneration;
        pendingDescription = descriptionWorker.submit(() -> {
            try {
                String description = frenchDescriptions.load(item);
                main.post(() -> {
                    ArtworkPolicy.Item current = view.currentItem();
                    if (descriptionVersion == descriptionGeneration && resumed && online && current == item)
                        view.updateDescription(item, description.isEmpty() ? fallback : description);
                });
            } catch (IOException | RuntimeException unavailable) {
                android.util.Log.d("TvHomeArtwork", "French description unavailable", unavailable);
                main.post(() -> {
                    if (descriptionVersion == descriptionGeneration && resumed && online && view.currentItem() == item)
                        view.updateDescription(item, fallback);
                });
            } finally {
                descriptionRequests.remove(key);
            }
        });
    }

    private void cancelDescriptionLoads() {
        descriptionGeneration++;
        if (pendingDescription != null) {
            pendingDescription.cancel(true);
            pendingDescription = null;
        }
        frenchDescriptions.cancel();
        descriptionRequests.clear();
    }
}
