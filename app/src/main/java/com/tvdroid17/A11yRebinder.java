package com.tvdroid17;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

/**
 * Keeps SourceKeyService bound. Some firmwares (Xiaomi) drop third-party accessibility
 * services on package reinstall, force-stop or reboot, which kills the SET/INPUT keys.
 * This receiver re-asserts the binding on boot and after our own package is replaced,
 * and MainActivity re-checks on every resume. Verification uses AccessibilityManager,
 * not just the settings string.
 */
public class A11yRebinder extends BroadcastReceiver {
    private static final int MAX_TRIES = 6;
    private static final long RETRY_DELAY_MS = 1500L;

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ensureBound(context.getApplicationContext(), 0);
        }
    }

    /** Re-assert the service if AccessibilityManager does not list it; retries a few times. */
    static void ensureBound(Context context) {
        ensureBound(context, 0);
    }

    private static void ensureBound(Context context, int attempt) {
        if (isBound(context)) return;
        String id = new ComponentName(context, SourceKeyService.class).flattenToString();
        ContentResolver cr = context.getContentResolver();
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, id);
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        if (attempt + 1 < MAX_TRIES) {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> ensureBound(context, attempt + 1), RETRY_DELAY_MS);
        }
    }

    private static boolean isBound(Context context) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        String id = new ComponentName(context, SourceKeyService.class).flattenToString();
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (id.equals(info.getId())) return true;
        }
        return false;
    }
}
