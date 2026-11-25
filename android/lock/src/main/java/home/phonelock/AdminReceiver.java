package home.phonelock;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context c, Intent i) {
        // Set up again after a release without being uninstalled: bring every part back first.
        Release.undo(c);
        Policy.apply(c);
    }
}
