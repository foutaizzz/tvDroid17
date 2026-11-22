package com.tvdroid17;

import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;

/** Native detail entry verified against installed PlayTorrio 1.1.2. Never requests playback. */
final class PlayTorrioDetails {
    private static final String PACKAGE = "com.playtorrio.tv";

    static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    static boolean supports(ArtworkPolicy.Item item) {
        return item != null && ("movie".equals(item.type) || "series".equals(item.type))
                && item.id != null && item.id.matches("tt[0-9]+");
    }

    static Intent intent(ArtworkPolicy.Item item) {
        if (!supports(item)) return null;
        // This version reads detail extras in onCreate; onNewIntent ignores detail launches.
        // Reset only its navigation task, never its saved profiles/preferences/data.
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setClassName("com.playtorrio.tv", "com.playtorrio.tv.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("contentId", item.id)
                .putExtra("contentType", item.type)
                .putExtra("launchMode", "detail")
                .putExtra("name", item.title);
        if (ArtworkPolicy.isImageUrl(item.background)) intent.putExtra("backdrop", item.background);
        if (ArtworkPolicy.isImageUrl(item.logo)) intent.putExtra("logo", item.logo);
        return intent;
    }
}
