package home.phonelock.parent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs after a reboot or an app update. Starting the app process re-arms QuietWatch's alarm (App.onCreate) in
 * both builds; the GitHub build also restarts its background connection.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = BackgroundConnection.start(c)
}

/** Notification buttons: answering a request, or deciding on a screen alert, without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // A screen alert's "Mark OK" or "Block" carries the alert id instead of a request.
        if (i.getStringExtra("kind") == "allowscreen") {
            Hub.setScreenRule(i.getStringExtra("device") ?: return, i.getStringExtra("screen") ?: return, "ok", minutes = 30)
            c.getSystemService(android.app.NotificationManager::class.java).cancel(i.getIntExtra("nid", 0))
            return
        }
        val alert = i.getStringExtra("alert")
        if (alert != null) {
            ScreenAlerts.decide(alert, i.getStringExtra("kind") ?: return)
            return
        }
        Hub.answer(
            i.getStringExtra("req") ?: return,
            i.getStringExtra("kind") ?: return,
            i.getLongExtra("minutes", 60),
        )
    }
}
