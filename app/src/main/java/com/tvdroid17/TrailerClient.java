package com.tvdroid17;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Resolves a playable YouTube trailer from TMDB metadata using the catalogue IMDb id. */
final class TrailerClient {
    private static final String BASE = "https://api.themoviedb.org/3";
    private static final int MAX_RESPONSE = 512 * 1024;
    private final TokenStore tokens;
    private volatile HttpURLConnection activeConnection;

    TrailerClient(Context context) {
        tokens = new TokenStore(context);
    }

    boolean isConfigured() { return tokens.isConfigured(); }

    String findTrailerKey(ArtworkPolicy.Item item) throws IOException {
        if (!isConfigured() || !PlayTorrioDetails.supports(item)) return null;
        JSONObject found = request(BASE + "/find/" + item.id + "?external_source=imdb_id&language=fr-FR");
        JSONArray matches = found.optJSONArray("movie".equals(item.type) ? "movie_results" : "tv_results");
        if (matches == null || matches.length() == 0) return null;
        int tmdbId = matches.optJSONObject(0) == null ? -1 : matches.optJSONObject(0).optInt("id", -1);
        if (tmdbId <= 0) return null;

        String kind = "movie".equals(item.type) ? "movie" : "tv";
        JSONObject details = request(BASE + "/" + kind + "/" + tmdbId
                + "?append_to_response=videos&language=fr-FR");
        JSONObject videoBlock = details.optJSONObject("videos");
        JSONArray videos = videoBlock == null ? null : videoBlock.optJSONArray("results");
        if (videos == null) return null;
        String originalLanguage = details.optString("original_language", "");
        String selectedKey = null;
        int selectedScore = Integer.MIN_VALUE;
        for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video == null || !"YouTube".equalsIgnoreCase(video.optString("site"))
                    || !"Trailer".equalsIgnoreCase(video.optString("type"))) continue;
            String key = video.optString("key", "");
            if (!key.matches("[A-Za-z0-9_-]{6,20}")) continue;
            String language = video.optString("iso_639_1", "");
            int languageScore = "fr".equalsIgnoreCase(language) ? 400
                    : !originalLanguage.isEmpty() && originalLanguage.equalsIgnoreCase(language) ? 300
                    : "en".equalsIgnoreCase(language) ? 200
                    : language.isEmpty() || "xx".equalsIgnoreCase(language) ? 100 : 0;
            int score = languageScore * 10 + (video.optBoolean("official") ? 1 : 0);
            if (score > selectedScore) {
                selectedScore = score;
                selectedKey = key;
            }
        }
        return selectedKey;
    }

    void cancel() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    private JSONObject request(String address) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
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
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
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
}
