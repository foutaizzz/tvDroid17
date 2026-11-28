package com.tvdroid17;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.time.Year;
import java.util.List;

/** Public metadata and in-memory artwork only. No credentials, files or HTTP disk cache. */
final class CinemetaClient {
    private static final String BASE = "https://v3-cinemeta.strem.io";
    /** The panel is 1920x1080; backdrops are scaled to exactly that size. */
    private static final int SCREEN_WIDTH = 1920, SCREEN_HEIGHT = 1080;
    private volatile HttpURLConnection activeConnection;

    static final class Frame {
        final String title;
        final Bitmap background, logo;
        final ArtworkPolicy.Item item;
        final boolean logoPending;

        Frame(ArtworkPolicy.Item item, Bitmap background, Bitmap logo) {
            this(item, background, logo, false);
        }

        Frame(ArtworkPolicy.Item item, Bitmap background, Bitmap logo, boolean logoPending) {
            this.item = item;
            this.title = item.title;
            this.background = background;
            this.logo = logo;
            this.logoPending = logoPending;
        }
    }

    List<ArtworkPolicy.Item> catalogue() throws IOException {
        List<ArtworkPolicy.Item> movies = readCatalogue("movie");
        List<ArtworkPolicy.Item> series = readCatalogue("series");
        List<ArtworkPolicy.Item> mixed = ArtworkPolicy.mix(movies, series);
        if (mixed.isEmpty()) throw new IOException("No artwork available");
        return mixed;
    }

    private List<ArtworkPolicy.Item> readCatalogue(String type) throws IOException {
        List<ArtworkPolicy.Item> items = new ArrayList<>();
        try {
            // PlayTorrio's New rows use the current-year catalogues. last-videos instead
            // ranks episodes from long-running shows and does not match New - Série.
            byte[] body = request(BASE + "/catalog/" + type + "/year.json?genre=" + Year.now().getValue(), false, 2_000_000);
            JSONArray metas = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("metas");
            if (metas == null) return items;
            for (int i = 0; i < metas.length(); i++) {
                JSONObject meta = metas.optJSONObject(i);
                if (meta == null) continue;
                items.add(parseItem(type, meta));
            }
        } catch (JSONException invalid) {
            throw new IOException("Invalid catalogue", invalid);
        }
        return items;
    }

    Frame load(ArtworkPolicy.Item item) throws IOException {
        Frame first = loadBackground(item);
        return new Frame(item, first.background, loadLogo(item));
    }

    Frame loadBackground(ArtworkPolicy.Item item) throws IOException {
        IOException unavailableLarge = null;
        String large = ArtworkPolicy.largeBackground(item);
        if (!large.isEmpty() && !large.equals(item.background)) {
            try {
                // The catalogue points at the 1280 px version, stretched to the panel. The large
                // one is 3840 px: halved by the decoder, it lands on 1920 px exactly.
                Bitmap backdrop = decode(request(large, true, 8_000_000), 1920, 1080, false);
                return new Frame(item, fitToScreen(backdrop), null);
            } catch (IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
                unavailableLarge = unavailable;
            }
        }
        try {
            Bitmap backdrop = decode(request(item.background, true, 8_000_000), 1920, 1080, false);
            return new Frame(item, fitToScreen(backdrop), null);
        } catch (IOException unavailableBackground) {
            if (Thread.currentThread().isInterrupted()) throw unavailableBackground;
            if (unavailableLarge != null) unavailableBackground.addSuppressed(unavailableLarge);
            String poster = ArtworkPolicy.posterUrl(item);
            if (poster.isEmpty() || poster.equals(item.background)) throw unavailableBackground;
            try {
                Bitmap fallback = decode(request(poster, true, 4_000_000), 800, 1200, false);
                return new Frame(item, fitToScreen(fallback), null);
            } catch (IOException unavailablePoster) {
                unavailablePoster.addSuppressed(unavailableBackground);
                throw unavailablePoster;
            }
        }
    }

    /**
     * Scales a backdrop to exactly the screen size, so the artwork covers the panel without the
     * view having to crop a smaller source, and a bigger one is never stretched.
     */
    private static Bitmap fitToScreen(Bitmap source) {
        if (source == null) return null;
        if (source.getWidth() == SCREEN_WIDTH && source.getHeight() == SCREEN_HEIGHT) return source;
        Bitmap scaled = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.RGB_565);
        android.graphics.Canvas canvas = new android.graphics.Canvas(scaled);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        // Center crop: fill the screen and cut the overflow, like the view does.
        float scale = Math.max(SCREEN_WIDTH / (float) source.getWidth(),
                SCREEN_HEIGHT / (float) source.getHeight());
        float width = source.getWidth() * scale, height = source.getHeight() * scale;
        android.graphics.RectF target = new android.graphics.RectF(
                (SCREEN_WIDTH - width) / 2f, (SCREEN_HEIGHT - height) / 2f,
                (SCREEN_WIDTH + width) / 2f, (SCREEN_HEIGHT + height) / 2f);
        canvas.drawBitmap(source, null, target, paint);
        if (source != scaled) source.recycle();
        return scaled;
    }

    Bitmap loadLogo(ArtworkPolicy.Item item) throws IOException {
        Bitmap logo = null;
        if (ArtworkPolicy.isImageUrl(item.logo)) {
            try {
                logo = trimLogo(decode(request(item.logo, true, 4_000_000), 800, 400, true));
            } catch (IOException unavailableLogo) {
                // A missing logo must not discard a perfectly usable backdrop and text title.
                if (Thread.currentThread().isInterrupted()) throw unavailableLogo;
            }
        }
        return logo;
    }

    static ArtworkPolicy.Item parseItem(String type, JSONObject meta) {
        JSONArray values = meta.optJSONArray("genres");
        List<String> genres = new ArrayList<>();
        if (values != null) for (int n = 0; n < Math.min(4, values.length()); n++) {
            String genre = frenchGenre(values.optString(n));
            if (!genre.isEmpty() && !genres.contains(genre)) genres.add(genre);
        }
        return new ArtworkPolicy.Item(type, meta.optString("id"), meta.optString("name"),
                meta.optString("background"), meta.optString("logo"),
                String.join(" · ", genres), meta.optString("description"));
    }

    private static String frenchGenre(String genre) {
        switch (genre) {
            case "Adventure": return "Aventure";
            case "Animation": return "Animation";
            case "Biography": return "Biographie";
            case "Comedy": return "Comédie";
            case "Crime": return "Policier";
            case "Documentary": return "Documentaire";
            case "Drama": return "Drame";
            case "Family": return "Famille";
            case "Fantasy": return "Fantastique";
            case "History": return "Historique";
            case "Horror": return "Horreur";
            case "Music": return "Musique";
            case "Musical": return "Comédie musicale";
            case "Mystery": return "Mystère";
            case "Romance": return "Romance";
            case "Sci-Fi": case "Science Fiction": return "Science-fiction";
            case "War": return "Guerre";
            default: return genre;
        }
    }

    void cancel() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    static Bitmap trimLogo(Bitmap logo) {
        int width = logo.getWidth(), height = logo.getHeight();
        int[] pixels = new int[width * height];
        logo.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] bounds = LogoBounds.find(pixels, width, height);
        if (bounds == null) return null;
        return Bitmap.createBitmap(logo, bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    private byte[] request(String address, boolean image, int limit) throws IOException {
        for (int redirect = 0; redirect < 4; redirect++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            URL url = new URL(address);
            boolean permitted = image ? ArtworkPolicy.isImageUrl(address) : ArtworkPolicy.isCatalogueUrl(address);
            if (!permitted) throw new IOException("Unsupported artwork source");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            activeConnection = connection;
            try {
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(4_000);
                connection.setReadTimeout(6_000);
                connection.setRequestProperty("Accept", image ? "image/*" : "application/json");
                connection.setRequestProperty("Cache-Control", "no-store");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Invalid redirect");
                    address = new URL(url, location).toString();
                    continue;
                }
                if (status != 200) throw new IOException("Artwork HTTP " + status);
                String mime = connection.getContentType();
                if (image && (mime == null || !mime.startsWith("image/"))) throw new IOException("Not an image");
                if (connection.getContentLengthLong() > limit) throw new IOException("Response too large");
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16_384];
                    int count;
                    long deadline = System.nanoTime() + 12_000_000_000L;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                        if (bytes.size() + count > limit || System.nanoTime() > deadline)
                            throw new IOException("Response limit exceeded");
                        bytes.write(buffer, 0, count);
                    }
                    return bytes.toByteArray();
                }
            } finally {
                connection.disconnect();
                if (activeConnection == connection) activeConnection = null;
            }
        }
        throw new IOException("Too many redirects");
    }

    private static Bitmap decode(byte[] bytes, int width, int height, boolean alpha) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
        // Decode a little above the panel size, then scale down. A full-resolution 4K source would
        // cost 16 MB on a television that has barely 120 MB free; a 3840 px source still lands
        // exactly on 1920 px after one halving, so it stays sharp.
        int maxWidth = width + width / 3;
        int maxHeight = height + height / 3;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > maxWidth
                || options.outHeight / options.inSampleSize > maxHeight) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("Cannot decode image");
        return bitmap;
    }
}
