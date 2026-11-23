package com.tvdroid17;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dropdown menu of TV inputs (HDMI ports with connection state, AV, Live TV).
 * Standalone so it can be shown both from the home screen and from the translucent
 * overlay (RemoteControlsActivity) on top of another app.
 */
final class InputsPanel {
    private final Activity activity;
    private final View anchor;
    private final FrostedControls glass;
    private final float density;
    private PopupWindow popup;

    InputsPanel(Activity activity, View anchor, FrostedControls glass) {
        this.activity = activity;
        this.anchor = anchor;
        this.glass = glass;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    /** Reverse of the show animation: fade/shrink up, then actually dismiss the popup. */
    void dismiss() {
        final PopupWindow current = popup;
        popup = null;
        if (current == null || !current.isShowing()) return;
        View panel = current.getContentView();
        panel.animate().cancel();
        panel.animate().alpha(0f).scaleY(.92f).translationY(-8 * density).setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .withEndAction(current::dismiss)
                .start();
    }

    void toggle() {
        if (isShowing()) dismiss();
        else show();
    }

    void show() {
        if (isShowing()) return;
        View panel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, null, false);
        panel.setBackground(glass.material(panel, 20f, false));
        LinearLayout rows = panel.findViewById(R.id.rows);

        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow p = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup = p;
        p.setOnDismissListener(() -> {
            if (popup == p) popup = null;
        });
        p.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        p.setOutsideTouchable(true);
        p.setAnimationStyle(0);
        p.setElevation(0f);

        TvInputManager tim = (TvInputManager) activity.getSystemService(Activity.TV_INPUT_SERVICE);
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(activity)).compareToIgnoreCase(String.valueOf(b.loadLabel(activity)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(activity);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                addRow(rows, p, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        String liveTvPkg = activity.getString(R.string.live_tv_package).trim();
        Intent liveTv = liveTvPkg.isEmpty() ? null
                : activity.getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            final Intent liveTvIntent = liveTv;
            addRow(rows, p, R.drawable.ic_livetv, activity.getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            popup = null;
            Toast.makeText(activity, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row so port names are never squeezed out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        p.setWidth(Math.max(width, panel.getMeasuredWidth()));

        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        p.showAsDropDown(anchor, Math.round(4 * density), (int) (10 * density), Gravity.END);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addRow(LinearLayout rows, PopupWindow p, int iconRes, String title, int state, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        Drawable idleBackground = glass.material(row, 13f, FrostedControls.CONTROL_BUTTON_TINT);
        GradientDrawable focusedBackground = new GradientDrawable();
        focusedBackground.setColor(Color.WHITE);
        focusedBackground.setCornerRadius(13f * density);
        row.setBackground(idleBackground);
        row.setOnFocusChangeListener((v, focused) ->
                v.setBackground(focused ? focusedBackground : idleBackground));
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            p.dismiss();
            action.run();
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                p.dismiss();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams rowParams = (LinearLayout.LayoutParams) row.getLayoutParams();
        rowParams.setMargins(0, Math.round(3 * density), 0, Math.round(3 * density));
        rows.addView(row, rowParams);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
