package com.tvdroid17;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;
import androidx.recyclerview.widget.RecyclerView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Warm, repeatable 1080p fades measured by Android's actual window frame metrics. */
public class FadePerformanceInstrumentation extends Instrumentation {
    private static final class FrameSample {
        final long total, actualVsync, intendedVsync, draw, animation, sync, commands, swap;
        FrameSample(FrameMetrics metrics) {
            total = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            actualVsync = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP);
            intendedVsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
            draw = metrics.getMetric(FrameMetrics.DRAW_DURATION);
            animation = metrics.getMetric(FrameMetrics.ANIMATION_DURATION);
            sync = metrics.getMetric(FrameMetrics.SYNC_DURATION);
            commands = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION);
            swap = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION);
        }
    }
    private final List<FrameSample> frames = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean measuring;
    private volatile int droppedReports;
    private double lastPhaseTotalP90;
    private double lastPhaseVsyncP90;
    private Activity activity;
    private ArtworkBackdropView artwork;
    private CinemetaClient.Frame[] pictures;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        HandlerThread metricsThread = new HandlerThread("FadeMetrics"); metricsThread.start();
        Window.OnFrameMetricsAvailableListener listener = (window, metrics, dropped) -> {
            if (measuring) {
                frames.add(new FrameSample(metrics));
                droppedReports += dropped;
            }
        };
        ArtworkSlideshow slideshow = null;
        Runnable original = null;
        try {
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.tvdroid17.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            artwork = activity.findViewById(R.id.artwork);
            slideshow = (ArtworkSlideshow) field(activity, "artworkSlideshow");
            ArtworkSlideshow controller = slideshow;
            check(controller::pause);
            ArtworkPolicy.Item item = new ArtworkPolicy.Item("movie", "tt123", "Mesure du fondu", "", "");
            pictures = new CinemetaClient.Frame[]{new CinemetaClient.Frame(item, picture(0xFF22385E, 0xFFE1A353), null),
                    new CinemetaClient.Frame(item, picture(0xFFB96B63, 0xFF236E88), null)};
            for (int n = 0; n < 2; n++) { final int index = n; check(() -> artwork.show(pictures[index])); SystemClock.sleep(1000); }
            activity.getWindow().addOnFrameMetricsAvailableListener(listener, new Handler(metricsThread.getLooper()));
            String live = phase("live-dock");
            double liveVsyncP90 = lastPhaseVsyncP90;
            long frameBudget = Math.round(1_000_000_000d
                    / activity.getWindowManager().getDefaultDisplay().getRefreshRate());
            original = (Runnable) field(artwork, "dockVisualChanged");
            check(() -> artwork.setDockVisualChangedListener(null));
            String without = phase("without-capture");
            Runnable restore = original; check(() -> artwork.setDockVisualChangedListener(restore));
            RecyclerView grid = activity.findViewById(R.id.grid);
            FrostedControls glass = (FrostedControls) field(grid.getItemDecorationAt(0), "glass");
            long[] times = new long[5];
            for (int n = 0; n < times.length; n++) {
                final int index = n;
                check(() -> { long start = SystemClock.elapsedRealtimeNanos(); glass.capture(artwork); times[index] = SystemClock.elapsedRealtimeNanos() - start; });
            }
            List<Long> captures = new ArrayList<>(); for (long time : times) captures.add(time);
            String report = live + "; " + without + "; frame budget=" + frameBudget / 1_000_000d
                    + "ms; capture median=" + percentile(captures, .5) + "ms";
            result.putString("metrics", report);
            if (liveVsyncP90 > frameBudget * 1.5 / 1_000_000d)
                throw new AssertionError("90th percentile presented-frame spacing exceeds 1.5 refresh periods: " + report);
            check(() -> artwork.show(pictures[0])); SystemClock.sleep(850);
            check(() -> artwork.show(pictures[1])); SystemClock.sleep(100);
            FrostedControls fresh = new FrostedControls();
            check(() -> fresh.captureArtwork(artwork));
            if (field(fresh, "previous") == null) throw new AssertionError("Opening controls during fade lost outgoing image");
            Bitmap cached = (Bitmap) field(fresh, "backdrop");
            int cachedGeneration = cached.getGenerationId();
            for (int sample = 0; sample < 4; sample++) {
                SystemClock.sleep(60);
                check(() -> fresh.captureArtwork(artwork));
                if (cached.getGenerationId() != cachedGeneration) throw new AssertionError("Blur is recalculated during opacity-only animation");
            }
            check(() -> { artwork.clear(); fresh.captureArtwork(artwork); });
            if (field(fresh, "previous") != null || field(fresh, "backdrop") != null)
                throw new AssertionError("Offline glass retained endpoint images");
            result.putString("verification", "PASS: presented-frame spacing stays within 1.5 display periods, blur cache stable during fade, mid-fade controls retain both endpoints, offline textures released; " + report);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("verification", "FAIL: " + failure); finish(Activity.RESULT_CANCELED, result);
        } finally {
            measuring = false;
            if (activity != null) activity.getWindow().removeOnFrameMetricsAvailableListener(listener);
            if (artwork != null && original != null) { Runnable restore = original; check(() -> artwork.setDockVisualChangedListener(restore)); }
            if (slideshow != null) { ArtworkSlideshow controller = slideshow; check(() -> controller.resume(true)); }
            metricsThread.quitSafely();
        }
    }
    private String phase(String name) {
        synchronized (frames) { frames.clear(); }
        droppedReports = 0;
        for (int n = 0; n < 6; n++) {
            final int index = n % 2;
            measuring = true;
            check(() -> artwork.show(pictures[index]));
            SystemClock.sleep(800);
            measuring = false;
            SystemClock.sleep(80);
        }
        List<FrameSample> samples;
        synchronized (frames) { samples = new ArrayList<>(frames); }
        List<Long> total = values(samples, 0), actualVsync = values(samples, 1), intendedVsync = values(samples, 2);
        List<Long> intervals = new ArrayList<>(), lateness = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            FrameSample sample = samples.get(i);
            if (sample.actualVsync >= sample.intendedVsync) lateness.add(sample.actualVsync - sample.intendedVsync);
            if (i > 0) {
                long gap = actualVsync.get(i) - actualVsync.get(i - 1);
                if (gap > 0 && gap < 200_000_000L) intervals.add(gap);
            }
        }
        lastPhaseTotalP90 = percentile(total, .90);
        lastPhaseVsyncP90 = percentile(intervals, .90);
        int slow = 0; for (long frame : total) if (frame > 33_400_000L) slow++;
        return name + " frames=" + samples.size() + " metrics-dropped=" + droppedReports + " total-p50=" + percentile(total, .5)
                + "ms total-p90=" + lastPhaseTotalP90 + "ms over33ms=" + slow + " actual-vsync-p90=" + percentile(intervals, .9)
                + "ms lateness-p90=" + percentile(lateness, .9) + "ms draw-p90=" + percentile(values(samples, 3), .9)
                + "ms animation-p90=" + percentile(values(samples, 4), .9) + "ms sync-p90=" + percentile(values(samples, 5), .9)
                + "ms commands-p90=" + percentile(values(samples, 6), .9) + "ms swap-p90=" + percentile(values(samples, 7), .9) + "ms";
    }
    private List<Long> values(List<FrameSample> samples, int metric) {
        List<Long> values = new ArrayList<>(samples.size());
        for (FrameSample sample : samples) switch (metric) {
            case 0: values.add(sample.total); break;
            case 1: values.add(sample.actualVsync); break;
            case 2: values.add(sample.intendedVsync); break;
            case 3: values.add(sample.draw); break;
            case 4: values.add(sample.animation); break;
            case 5: values.add(sample.sync); break;
            case 6: values.add(sample.commands); break;
            case 7: values.add(sample.swap); break;
            default: throw new IllegalArgumentException("Unknown frame metric " + metric);
        }
        return values;
    }
    private double percentile(List<Long> values, double amount) {
        if (values.isEmpty()) throw new AssertionError("No Android frame metrics");
        List<Long> sorted = new ArrayList<>(values); Collections.sort(sorted);
        return Math.round(sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * amount))) / 100_000d) / 10d;
    }
    private Bitmap picture(int first, int last) {
        Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap); Paint paint = new Paint();
        paint.setShader(new LinearGradient(0, 0, 1920, 1080, first, last, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 1920, 1080, paint); return bitmap;
    }
    private Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private void check(Runnable action) {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
