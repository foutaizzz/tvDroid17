package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Optional French plot summaries from TMDB; Cinemeta remains the catalog and fallback source. */
final class FrenchDescriptionClient {
    private static final String BASE = "https://api.themoviedb.org/3";
    private static final long FOUND_TTL = 30L * 24 * 60 * 60 * 1000;
    private static final long MISSING_TTL = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_RESPONSE = 512 * 1024;

    private final TokenStore tokens;
    private final SharedPreferences cache;
    private volatile HttpURLConnection activeConnection;

    FrenchDescriptionClient(Context context) {
        tokens = new TokenStore(context);
        cache = context.getApplicationContext().getSharedPreferences("french_descriptions", Context.MODE_PRIVATE);
    }

    boolean isConfigured() { return tokens.isConfigured(); }

    void cancel() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    String load(ArtworkPolicy.Item item) throws IOException {
        if (!isConfigured() || item == null || !"movie".equals(item.type) && !"series".equals(item.type)
                || item.id == null || !item.id.matches("tt[0-9]+")) return "";
        // Version the cache so earlier empty IMDb-only lookups are retried.
        String key = "v2_" + item.type + "_" + item.id;
        String cached = readCache(key);
        if (cached != null) return cached;

        String kind = "movie".equals(item.type) ? "movie" : "tv";
        JSONObject matches = request(BASE + "/find/" + item.id + ".json?external_source=imdb_id&language=fr-FR");
        JSONArray results = matches.optJSONArray("movie".equals(item.type) ? "movie_results" : "tv_results");
        if (results == null || results.length() == 0) {
            String query = URLEncoder.encode(item.title, "UTF-8");
            matches = request(BASE + "/search/" + kind + "?query=" + query + "&language=fr-FR");
            results = matches.optJSONArray("results");
        }
        if (results == null) return saveCache(key, "");
        for (int n = 0; n < results.length(); n++) {
            JSONObject match = results.optJSONObject(n);
            if (match == null || match.optInt("id", -1) <= 0) continue;
            String overview = match.optString("overview", "").trim();
            if (!overview.isEmpty()) return saveCache(key, overview);
            JSONObject translations = request(BASE + "/" + kind + "/" + match.optInt("id") + "/translations");
            overview = parseFrenchOverview(translations.toString(), "");
            if (!overview.isEmpty()) return saveCache(key, overview);
        }
        return saveCache(key, "");
    }

    private String readCache(String key) {
        String saved = cache.getString(key, null);
        if (saved == null) return null;
        try {
            JSONObject entry = new JSONObject(saved);
            String description = entry.optString("description");
            long age = System.currentTimeMillis() - entry.optLong("savedAt", 0);
            long ttl = description.isEmpty() ? MISSING_TTL : FOUND_TTL;
            return age >= 0 && age < ttl ? description : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    private String saveCache(String key, String description) {
        try {
            cache.edit().putString(key, new JSONObject().put("description", description)
                    .put("savedAt", System.currentTimeMillis()).toString()).apply();
        } catch (Exception ignored) { /* A cache failure must not hide a valid description. */ }
        return description;
    }

    private JSONObject request(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        activeConnection = connection;
        try {
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            connection.setConnectTimeout(2_500);
            connection.setReadTimeout(3_500);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + tokens.token());
            connection.setRequestProperty("Cache-Control", "no-store");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("TMDB HTTP " + status);
            if (connection.getContentLengthLong() > MAX_RESPONSE) throw new IOException("TMDB response too large");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8_192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_RESPONSE) throw new IOException("TMDB response too large");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception invalid) {
                if (invalid instanceof IOException) throw (IOException) invalid;
                throw new IOException("Invalid TMDB response", invalid);
            }
        } finally {
            connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
        }
    }

    static int parseTmdbId(String body, String type) {
        try {
            JSONObject response = new JSONObject(body);
            JSONArray matches = response.optJSONArray("movie".equals(type) ? "movie_results" : "tv_results");
            if (matches == null) return -1;
            for (int n = 0; n < matches.length(); n++) {
                int id = matches.optJSONObject(n) == null ? -1 : matches.optJSONObject(n).optInt("id", -1);
                if (id > 0) return id;
            }
        } catch (Exception ignored) { }
        return -1;
    }

    static String parseFrenchOverview(String body, String fallback) {
        try {
            JSONArray translations = new JSONObject(body).optJSONArray("translations");
            if (translations == null) return fallback;
            String anyFrench = "";
            for (int n = 0; n < translations.length(); n++) {
                JSONObject translation = translations.optJSONObject(n);
                if (translation == null || !"fr".equalsIgnoreCase(translation.optString("iso_639_1"))) continue;
                String overview = translation.optJSONObject("data") == null ? ""
                        : translation.optJSONObject("data").optString("overview", "").trim();
                if (overview.isEmpty()) continue;
                if ("FR".equalsIgnoreCase(translation.optString("iso_3166_1"))) return overview;
                if (anyFrench.isEmpty()) anyFrench = overview;
            }
            return anyFrench.isEmpty() ? fallback : anyFrench;
        } catch (Exception invalid) {
            return fallback;
        }
    }
}
