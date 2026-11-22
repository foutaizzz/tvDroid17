package com.tvdroid17;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The TMDB read token lives on the television, never inside the APK.
 *
 * The installer writes it into the app's own files, so an APK that travels carries no account
 * key: anybody can copy it around, and it still works without the key being shipped. When the
 * file is missing the TMDB features (trailers, French summaries) switch themselves off instead
 * of failing.
 */
final class TokenStore {
    private static final String FILE_NAME = "tmdb-token.txt";
    /** A token is a short ASCII string; anything larger is not one. */
    private static final int MAX_BYTES = 1024;

    private final File[] candidates;
    private String cached;

    TokenStore(Context context) {
        Context app = context.getApplicationContext();
        File external = app.getExternalFilesDir(null);
        candidates = new File[]{
                external != null ? new File(external, FILE_NAME) : null,
                new File(app.getFilesDir(), FILE_NAME)
        };
    }

    /** The token, or an empty string when the television has none. */
    String token() {
        if (cached != null) return cached;
        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) continue;
            try {
                if (candidate.length() <= 0 || candidate.length() > MAX_BYTES) continue;
                String value = new String(Files.readAllBytes(candidate.toPath()),
                        StandardCharsets.UTF_8).trim();
                if (value.matches("[A-Za-z0-9._-]{20,400}")) {
                    cached = value;
                    return cached;
                }
            } catch (IOException | RuntimeException unavailable) {
                // An unreadable file simply means the TMDB features stay off.
            }
        }
        cached = "";
        return cached;
    }

    boolean isConfigured() {
        return !token().isEmpty();
    }
}
