package com.tvdroid17;

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
    private long remainingAutoAdvanceMs = INTERVAL_MS;
    private long nextDeadline;
    private boolean resumed, online, held;
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
        if (selected && nextDeadline > 0L)
            remainingAutoAdvanceMs = Math.max(0L, nextDeadline - SystemClock.elapsedRealtime());
        main.removeCallbacks(next);
        nextDeadline = 0L;
        held = selected;
        if (selected) cancelPendingForSelection();
        else if (resumed && online) postNext(remainingAutoAdvanceMs);
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
        remainingAutoAdvanceMs = INTERVAL_MS;
        nextDeadline = 0L;
        view.clear();
    }

    private void loadNext() {
        nextDeadline = 0L;
        remainingAutoAdvanceMs = INTERVAL_MS;
        if (!resumed || !online || !ArtworkPolicy.canAutoAdvance(held)) return;
        loadSlot(position, false);
    }

    private void loadSlot(int target, boolean manual) {
        final int version = ++generation;
        final List<ArtworkPolicy.Item> known = catalogue;
        final boolean refresh = known.isEmpty() || SystemClock.elapsedRealtime() - catalogueTime >= CATALOGUE_AGE_MS;
        final int slot = target;
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
                // Publish a slide only after its background and optional logo have both resolved.
                // loadLogo returns null when no usable logo exists, so the title is then shown as
                // the definitive fallback instead of appearing before a logo that is still loading.
                CinemetaClient.Frame frame = client.load(item);
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
                    // A missing poster must not hold the screen: try the next one every second.
                    long retry = Math.min(1_000L, 1_000L << Math.min(4, consecutiveFailures - 1));
                    scheduleNext(retry);
                });
            }
        });
    }

    private void scheduleNext(long delay) {
        main.removeCallbacks(next);
        nextDeadline = 0L;
        remainingAutoAdvanceMs = delay;
        if (ArtworkPolicy.canAutoAdvance(held) && resumed && online) postNext(delay);
    }

    private void postNext(long delay) {
        remainingAutoAdvanceMs = delay;
        nextDeadline = SystemClock.elapsedRealtime() + delay;
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
