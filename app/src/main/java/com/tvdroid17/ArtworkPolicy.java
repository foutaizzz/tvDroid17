package com.tvdroid17;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selection rules shared by the network adapter and standalone JVM tests. */
final class ArtworkPolicy {
    static final class Item {
        final String type, id, title, background, logo, genres;
        String description;

        Item(String type, String id, String title, String background, String logo) {
            this(type, id, title, background, logo, "", "");
        }

        Item(String type, String id, String title, String background, String logo, String genres, String description) {
            this.type = type;
            this.id = id;
            this.title = title;
            this.background = background;
            this.logo = logo;
            this.genres = genres == null ? "" : genres;
            this.description = description == null ? "" : description;
        }
    }

    static boolean isImageUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("images.metahub.space".equals(host) || "live.metahub.space".equals(host)
                    || "image.tmdb.org".equals(host)
                    || "assets.fanart.tv".equals(host) || "m.media-amazon.com".equals(host));
        } catch (Exception invalid) {
            return false;
        }
    }

    static String posterUrl(Item item) {
        if (item == null || item.id == null || !item.id.matches("tt[0-9]+")) return "";
        return "https://images.metahub.space/poster/medium/" + item.id + "/img";
    }

    /**
     * The largest background the artwork server offers. The catalogue points at the 1280 px
     * version, which has to be stretched to the 1920 px panel; the large one is 3840 px and
     * lands on exactly 1920 px once the decoder halves it. An empty string means the
     * catalogue URL is not a known artwork server, and must be used as it is.
     */
    static String largeBackground(Item item) {
        if (item == null || item.background == null) return "";
        String background = item.background;
        int marker = background.indexOf("/background/medium/");
        if (marker < 0) return "";
        return background.substring(0, marker) + "/background/large/"
                + background.substring(marker + "/background/medium/".length());
    }

    static boolean canAutoAdvance(boolean held) {
        return !held;
    }

    static List<Item> mix(List<Item> movies, List<Item> series) {
        List<Item> films = takeTen(movies), shows = takeTen(series), mixed = new ArrayList<>();
        for (int i = 0; i < Math.max(films.size(), shows.size()); i++) {
            if (i < films.size()) mixed.add(films.get(i));
            if (i < shows.size()) mixed.add(shows.get(i));
        }
        return mixed;
    }

    static boolean isCatalogueUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("v3-cinemeta.strem.io".equals(uri.getHost())
                    || "cinemeta-catalogs.strem.io".equals(uri.getHost()));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static List<Item> takeTen(List<Item> source) {
        List<Item> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item item : source) {
            if (item.id == null || item.id.isEmpty() || item.title == null || item.title.isEmpty()
                    || !isImageUrl(item.background) || !seen.add(item.type + ":" + item.id)) continue;
            selected.add(item);
            if (selected.size() == 10) break;
        }
        return selected;
    }
}
