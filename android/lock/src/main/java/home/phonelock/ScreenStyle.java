package home.phonelock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

/**
 * The look of the child's built-in screens (Wi-Fi, Bluetooth, Chat; see {@link BuiltInApps}): the home screen's
 * dark background, rounded cards and pill buttons, and the colours they use, so the screens match each other.
 */
final class ScreenStyle {
    static final int TEXT = Color.WHITE;
    static final int MUTED = Color.rgb(160, 168, 186);
    /** A card on the dark background. */
    static final int CARD = Color.argb(26, 255, 255, 255);

    static final int ACCENT = Color.rgb(120, 170, 255);
    /** An ordinary button, such as Done. */
    static final int BUTTON = Color.rgb(52, 60, 84);
    /** Text on an {@link #ACCENT} button. */
    static final int ON_ACCENT = Color.rgb(12, 13, 18);

    private ScreenStyle() {}

    /** The home screen's background, so a built-in screen feels like part of it. */
    static GradientDrawable background() {
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[] {Color.rgb(28, 34, 52), Color.rgb(12, 13, 18)});
    }

    static TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** A centred label on a rounded button shape; the caller sets its size and click handler. */
    static TextView pill(Context c, String label, int color) {
        TextView t = text(c, label, 16, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(c, color, 26));
        return t;
    }

    static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
