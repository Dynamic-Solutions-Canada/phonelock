package home.phonelock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction());
        // Before anything sends text: a phone paired by the English-only lock app stays English. Best effort,
        // as nothing here may stop the lock coming back after an update.
        if (updated) {
            try {
                Language.keepEnglishAfterUpdate(c);
            } catch (RuntimeException e) {
                android.util.Log.w(Policy.TAG, "language after update: " + e);
            }
        }
        // A reboot always ends parent mode.
        if (!updated) {
            Policy.storeParentUntil(c, 0);
        }
        // Pairs with "Turning off": parents see when it came back, even if it starts without signal.
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            int level = Battery.level(c);
            Outbox.note(c, level >= 0
                    ? c.getString(R.string.home_event_turned_on_battery, level)
                    : c.getString(R.string.home_event_turned_on));
        }
        Policy.apply(c);
        RemoteService.start(c);
        // Bring our home screen (and the kiosk) straight back: while this app was being updated, Android
        // fell back to the system launcher, which would otherwise stay in front until Home is pressed.
        if (updated) {
            // In the app's language, which Android may not have applied to c yet if it was only just set.
            Updater.reportIfUpdated(Language.localized(c));
            try {
                c.startActivity(new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                android.util.Log.w(Policy.TAG, "home after update: " + e);
            }
        }
    }
}
