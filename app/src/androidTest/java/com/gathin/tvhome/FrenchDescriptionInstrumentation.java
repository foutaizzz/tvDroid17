package com.gathin.tvhome;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Deterministic TMDB response parsing; no API key or network required. */
public final class FrenchDescriptionInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Class<?> client = Class.forName("com.gathin.tvhome.FrenchDescriptionClient");
            Method overview = client.getDeclaredMethod("parseFrenchOverview", String.class, String.class);
            overview.setAccessible(true);
            Method tmdbId = client.getDeclaredMethod("parseTmdbId", String.class, String.class);
            tmdbId.setAccessible(true);
            Constructor<?> constructor = client.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            Object instance = constructor.newInstance(getTargetContext());
            Method configured = client.getDeclaredMethod("isConfigured");
            configured.setAccessible(true);
            // The token lives on the television, not in the APK: without it the client must
            // report itself as unconfigured and return nothing rather than fail.
            if (!Boolean.TRUE.equals(configured.invoke(instance))) {
                Method load = client.getDeclaredMethod("load", ArtworkPolicy.Item.class);
                load.setAccessible(true);
                equal("", load.invoke(instance, new ArtworkPolicy.Item("movie", "tt12345", "Film", "", "")));
            }

            Activity activity = startActivitySync(new Intent(Intent.ACTION_MAIN)
                    .setClassName(getTargetContext(), "com.gathin.tvhome.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ArtworkBackdropView artwork = activity.findViewById(R.id.artwork);
            java.lang.reflect.Field slideshowField = MainActivity.class.getDeclaredField("artworkSlideshow");
            slideshowField.setAccessible(true);
            ArtworkSlideshow slideshow = (ArtworkSlideshow) slideshowField.get(activity);
            ArtworkPolicy.Item item = new ArtworkPolicy.Item("movie", "tt1234567", "Film test", "", "", "", "Résumé Cinemeta");
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFF334455);
            String french = "Une famille découvre un secret au cœur d’une aventure inattendue.";
            runOnMainSync(() -> {
                slideshow.pause();
                artwork.show(new CinemetaClient.Frame(item, bitmap, null));
                artwork.updateDescription(item, french);
            });
            waitForIdleSync();
            equal(french, item.description);
            equal(french, ((TextView) activity.findViewById(R.id.artworkDescription)).getText().toString());

            String translations = "{\"translations\":["
                    + "{\"iso_639_1\":\"en\",\"iso_3166_1\":\"US\",\"data\":{\"overview\":\"English text\"}},"
                    + "{\"iso_639_1\":\"fr\",\"iso_3166_1\":\"CA\",\"data\":{\"overview\":\"Résumé canadien\"}},"
                    + "{\"iso_639_1\":\"fr\",\"iso_3166_1\":\"FR\",\"data\":{\"overview\":\"Résumé français\"}}]}";
            equal("Résumé français", overview.invoke(null, translations, "English Cinemeta fallback"));
            equal("English Cinemeta fallback", overview.invoke(null,
                    "{\"translations\":[{\"iso_639_1\":\"fr\",\"data\":{\"overview\":\" \"}}]}",
                    "English Cinemeta fallback"));
            equal("English Cinemeta fallback", overview.invoke(null, "not json", "English Cinemeta fallback"));
            equal(12345, tmdbId.invoke(null,
                    "{\"movie_results\":[{\"id\":12345}],\"tv_results\":[{\"id\":67890}]}", "movie"));
            equal(67890, tmdbId.invoke(null,
                    "{\"movie_results\":[{\"id\":12345}],\"tv_results\":[{\"id\":67890}]}", "series"));
            equal(-1, tmdbId.invoke(null, "{\"movie_results\":[]}", "movie"));
            result.putString("verification", "PASS: French-first translation, English fallback, invalid response fallback, movie/series IMDb lookup mapping, no-token fallback");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("verification", "FAIL: " + failure);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void equal(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Expected " + expected + " but got " + actual);
    }
}
