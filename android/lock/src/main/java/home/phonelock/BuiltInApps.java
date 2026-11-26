package home.phonelock;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import java.util.Arrays;
import java.util.List;

/**
 * Wi-Fi, Bluetooth and the family chat, offered on the child's home screen as if they were apps: they have a
 * name and an icon, and parents schedule, hide, allow and pause them like any other app. They aren't installed
 * packages but screens of this app ({@link ConnectionsActivity}, {@link ChatActivity}), so the places that deal
 * with real packages (hiding, suspending, the lock's allowed list, uninstalling) skip them; everything that
 * works on app names (schedules, grants, blocks, the Ask list) treats them like apps.
 *
 * <p>Their ids look like package names so they fit the same lists; no real package uses them.
 */
final class BuiltInApps {
    static final String WIFI = "home.phonelock.wifi";
    static final String BLUETOOTH = "home.phonelock.bluetooth";
    static final String CHAT = "home.phonelock.chat";
    static final List<String> ALL = Arrays.asList(WIFI, BLUETOOTH, CHAT);

    /** The built-in apps this phone offers: {@link #ALL} without Chat where chat isn't available ({@link ChatLog#available}). */
    static List<String> offered(Context c) {
        List<String> out = new java.util.ArrayList<>();
        for (String id : ALL) if (Policy.isPresent(c, id)) out.add(id);
        return out;
    }
    /**
     * The two that change the phone's connections: on the home screen they sit behind the ⋮ button and in the
     * Ask picker they are pills, while Chat is an icon in the grid like an app.
     */
    static final List<String> CONTROLS = Arrays.asList(WIFI, BLUETOOTH);

    private BuiltInApps() {}

    static boolean is(String id) {
        return ALL.contains(id);
    }

    /**
     * "Wi-Fi settings" / "Bluetooth settings": what she can change there, not the radio itself. "Chat" for the
     * family chat.
     */
    static String label(Context c, String id) {
        if (CHAT.equals(id)) return c.getString(R.string.chat_app_name);
        return c.getString(BLUETOOTH.equals(id) ? R.string.events_bluetooth_settings : R.string.events_wifi_settings);
    }

    /** Opens its screen. */
    static Intent launch(Context c, String id) {
        if (CHAT.equals(id)) return new Intent(c, ChatActivity.class);
        return new Intent(c, ConnectionsActivity.class)
                .putExtra(
                        ConnectionsActivity.EXTRA_TAB,
                        BLUETOOTH.equals(id) ? ConnectionsActivity.TAB_BLUETOOTH : ConnectionsActivity.TAB_WIFI);
    }

    /**
     * Whether its screen may be open now, like any app: allowed in the current situation, granted for a while,
     * or parent mode.
     */
    static boolean allowedNow(Context c, String id) {
        if (CHAT.equals(id) && !ChatLog.available(c)) return false;
        return Policy.inParentMode(c) || Policy.homeApps(c).contains(id);
    }

    /** A round launcher-style icon: a coloured circle with a white symbol. */
    static Drawable icon(Context c, String id) {
        int size = Math.round(96 * c.getResources().getDisplayMetrics().density);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = CHAT.equals(id)
                ? Color.rgb(255, 143, 0)
                : BLUETOOTH.equals(id) ? Color.rgb(41, 98, 255) : Color.rgb(0, 150, 136);
        p.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        if (CHAT.equals(id)) {
            // A speech bubble: a rounded box with a tail at its lower left.
            RectF box = new RectF(size * 0.24f, size * 0.28f, size * 0.76f, size * 0.64f);
            canvas.drawRoundRect(box, size * 0.1f, size * 0.1f, p);
            Path tail = new Path();
            tail.moveTo(size * 0.34f, size * 0.6f);
            tail.lineTo(size * 0.3f, size * 0.76f);
            tail.lineTo(size * 0.48f, size * 0.62f);
            tail.close();
            canvas.drawPath(tail, p);
        } else if (BLUETOOTH.equals(id)) {
            // The Bluetooth rune.
            p.setTextSize(size * 0.55f);
            p.setFakeBoldText(true);
            canvas.drawText("ᛒ", size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p);
        } else {
            // Three Wi-Fi arcs and a dot.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(size * 0.07f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float cx = size / 2f;
            float cy = size * 0.68f;
            for (int i = 1; i <= 3; i++) {
                float r = size * 0.12f * i;
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 225, 90, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, size * 0.05f, p);
        }
        return new BitmapDrawable(c.getResources(), b);
    }

    /**
     * Once per device: puts the family chat on the home screen in every situation, bedtime included, so parents
     * can reach her at any time until they decide otherwise. A parent who takes it off later isn't overruled.
     */
    static void addChatOnce(Context c) {
        android.content.SharedPreferences p = Policy.prefs(c);
        if (p.getBoolean("chat_app_added", false)) return;
        Schedule.setMask(c, CHAT, Schedule.ALL);
        p.edit().putBoolean("chat_app_added", true).apply();
    }

    /**
     * Once per device: puts Wi-Fi and Bluetooth on the home screen (normal and limited hours, like a newly
     * added app), since they used to be always available there.
     */
    static void addOnce(Context c) {
        android.content.SharedPreferences p = Policy.prefs(c);
        if (p.getBoolean("built_in_apps_added", false)) return;
        java.util.List<String> apps = new java.util.ArrayList<>(Policy.visibleApps(c));
        for (String id : CONTROLS) if (!apps.contains(id)) apps.add(id);
        p.edit()
                .putString("apps", String.join(",", apps))
                .putBoolean("built_in_apps_added", true)
                .apply();
    }
}
