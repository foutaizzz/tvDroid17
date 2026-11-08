package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Only twenty public metadata records, never image bytes. Avoids two requests on a fresh process. */
final class CatalogueStore {
    private static final long AGE = 86_400_000L;
    private final SharedPreferences preferences;
    CatalogueStore(Context context) {
        // v3 invalidates the earlier fourteen-item cache.
        preferences = context.getSharedPreferences("cinemeta_catalogue_v3", Context.MODE_PRIVATE);
    }
    long age() { return Math.max(0, System.currentTimeMillis() - preferences.getLong("time", 0)); }
    List<ArtworkPolicy.Item> read() {
        List<ArtworkPolicy.Item> items = new ArrayList<>();
        long elapsed = System.currentTimeMillis() - preferences.getLong("time", 0);
        if (elapsed < 0 || elapsed >= AGE) return items;
        try {
            JSONArray saved = new JSONArray(preferences.getString("items", "[]"));
            for (int n = 0; n < Math.min(saved.length(), 20); n++) {
                JSONObject item = saved.getJSONObject(n);
                ArtworkPolicy.Item value = new ArtworkPolicy.Item(item.getString("type"), item.getString("id"),
                        item.getString("name"), item.getString("background"), item.optString("logo"),
                        item.optString("genres"), item.optString("description"));
                if (PlayTorrioDetails.supports(value) && ArtworkPolicy.isImageUrl(value.background)) items.add(value);
            }
        } catch (Exception invalid) { items.clear(); }
        return items;
    }
    void save(List<ArtworkPolicy.Item> items) {
        try {
            JSONArray saved = new JSONArray();
            for (ArtworkPolicy.Item item : items) saved.put(new JSONObject()
                    .put("type", item.type).put("id", item.id).put("name", item.title)
                    .put("background", item.background).put("logo", item.logo)
                    .put("genres", item.genres).put("description", item.description));
            preferences.edit().putString("items", saved.toString()).putLong("time", System.currentTimeMillis()).apply();
        } catch (Exception invalid) { /* Metadata caching never blocks the live artwork. */ }
    }
}
