package home.phonelock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import org.json.JSONObject;

/**
 * Ends PhoneLock's control of this phone, for a parent's {@code release} message or the adb {@code release}
 * command. Afterwards the phone is an ordinary phone with the lock app still installed but inert, and a
 * notification asks to uninstall it (an app that isn't a device owner can't remove itself silently).
 */
final class Release {
    private Release() {}

    /** A release message is only acted on this soon after it was sent, so a delayed one can't free a phone hours later. */
    static final long MAX_LATE_MS = 5 * 60_000L;

    /** How long to wait for the hub to accept the "released" event before going ahead anyway. */
    private static final long CONFIRM_TIMEOUT_MS = 20_000L;

    private static final String DONE = "released";
    private static final int NOTICE_ID = 0x5e1ea5e;

    /** Every part of the app that would keep controlling or reporting on the phone; disabled once released. */
    private static final Class<?>[] PARTS = {
        HomeActivity.class,
        RemoteService.class,
        ScreenService.class,
        OutgoingCallGuard.class,
        IncomingCallGuard.class,
        BootReceiver.class,
        LocationLog.Tick.class,
    };

    /**
     * Whether a release sent at {@code sentMs} may still be acted on at {@code nowMs}: no more than
     * {@link #MAX_LATE_MS} late. A sender clock slightly ahead is accepted; the freshness check before
     * dispatch already drops anything too far in the future.
     */
    static boolean onTime(long sentMs, long nowMs) {
        return sentMs > 0 && nowMs - sentMs <= MAX_LATE_MS;
    }

    /** Whether this phone has been released (the lock app then stays out of the way until it is uninstalled). */
    static boolean done(Context c) {
        return Policy.prefs(c).getBoolean(DONE, false);
    }

    /**
     * Releases the phone for parent {@code who}. Runs off the main thread (it waits for the hub). In order:
     * <ol>
     *   <li>tell every parent, while the pairing still works, so their apps can forget the phone;
     *   <li>lift every restriction and stop being device owner;
     *   <li>forget the pairing, stop the service and disable every part that controls or reports;
     *   <li>ask the child's phone to uninstall the lock app.
     * </ol>
     * Returns a short result for adb. If Android refuses to drop device ownership, nothing after it runs and the
     * parents are told it failed.
     */
    static synchronized String run(Context c, String who) {
        if (done(c)) return "already released";

        // 1. While still paired: the parents' apps forget the phone when this arrives.
        try {
            JSONObject o = Remote.msg("event");
            o.put("text", c.getString(R.string.events_released, who));
            o.put("by", who);
            o.put("released", true);
            boolean sent = Remote.publishNow(c, o, CONFIRM_TIMEOUT_MS);
            if (!sent) Log.w(Policy.TAG, "release: parents not told (hub unreachable); releasing anyway");
        } catch (Exception e) {
            Log.w(Policy.TAG, "release event: " + e);
        }

        // 2. Lift every restriction while this app is still owner, then give up ownership.
        try {
            Policy.releaseAll(c);
            Policy.dpm(c).clearDeviceOwnerApp(c.getPackageName());
        } catch (Exception e) {
            Log.w(Policy.TAG, "release failed: " + e);
            Remote.event(c, c.getString(R.string.events_release_failed), who, false);
            Policy.apply(c);
            return "failed: " + e;
        }
        Policy.prefs(c).edit().putBoolean(DONE, true).commit();

        // 3. Nothing reports any more: no pairing, no service, and no part left that could start one.
        Pairing.forget(c);
        c.stopService(new Intent(c, RemoteService.class));
        disableParts(c);

        // 4. The child's phone offers to uninstall the lock app.
        askToUninstall(c);
        Log.i(Policy.TAG, "released by " + who);
        return "released";
    }

    /**
     * Brings back a released lock app that was made device owner again (set up again without being
     * uninstalled), so the new setup works. Does nothing on a phone that was never released.
     */
    static void undo(Context c) {
        if (!done(c)) return;
        setParts(c, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        Policy.prefs(c).edit().remove(DONE).commit();
        c.getSystemService(NotificationManager.class).cancel(NOTICE_ID);
        Log.i(Policy.TAG, "set up again after a release");
    }

    private static void disableParts(Context c) {
        setParts(c, PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
    }

    private static void setParts(Context c, int state) {
        PackageManager pm = c.getPackageManager();
        for (Class<?> part : PARTS) {
            try {
                pm.setComponentEnabledSetting(new ComponentName(c, part), state, PackageManager.DONT_KILL_APP);
            } catch (Exception e) {
                Log.w(Policy.TAG, "release: set " + part.getSimpleName() + " to " + state + ": " + e);
            }
        }
    }

    /** A notification that opens Android's own "Uninstall PhoneLock?" prompt. */
    private static void askToUninstall(Context c) {
        Intent delete = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 0, delete, PendingIntent.FLAG_IMMUTABLE);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.cancel(1); // the "parent controls are active" notice
        nm.createNotificationChannel(new NotificationChannel(
                "parent", c.getString(R.string.events_channel_from_parents), NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(c, "parent")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(c.getString(R.string.events_released_child))
                .setContentText(c.getString(R.string.events_released_child_uninstall))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTICE_ID, n);
    }
}
