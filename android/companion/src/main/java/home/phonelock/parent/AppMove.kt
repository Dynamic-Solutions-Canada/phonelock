package home.phonelock.parent

import android.content.Context
import android.content.Intent

/**
 * Moving this app's devices to PhoneLock's parent app under its new id (D-011). The old app can't update into
 * the new one, and its pairing keys are sealed by its own keystore entry, so the devices are handed over
 * instead: their pairing links, sent straight to the new app's import screen by explicit component.
 *
 * Contract version 1. Installed old apps keep these names for as long as they exist, so never change them;
 * a different payload is a new [VERSION] that the receiver must still accept alongside this one. Both sides
 * check the other's signing certificate, so pairing keys only ever go to, and come from, PhoneLock itself.
 */
internal object AppMove {
    /** The parent app's new application id. */
    const val NEW_PACKAGE = "ca.dynamicsolutions.phonelock.parent"

    /** The old application id, the only one that sends. */
    const val OLD_PACKAGE = "home.phonelock.parent"

    /** The receiving activity in the new app; its class keeps the code's own package, which doesn't move. */
    const val IMPORT_ACTIVITY = "home.phonelock.parent.ImportDevicesActivity"

    const val ACTION = "home.phonelock.parent.action.IMPORT_DEVICES"
    const val EXTRA_VERSION = "version"
    const val VERSION = 1

    /** One phonelock://pair link per device (Setup.pairingLink). */
    const val EXTRA_LINKS = "links"

    /** This parent's name, which the links don't carry. */
    const val EXTRA_PARENT_NAME = "parentName"

    /** In the result: how many devices the new app added. */
    const val EXTRA_ADDED = "added"

    /** Where the new app is downloaded from. */
    const val RELEASES_PAGE = "https://github.com/Dynamic-Solutions-Canada/phonelock/releases/latest"

    /** Whether this is the old app, which moves its devices; the app under the new id never sends. */
    fun isOldApp(c: Context) = c.packageName == OLD_PACKAGE

    /**
     * Whether the new app is installed and signed with this app's own key. Nothing is sent otherwise, so another
     * app given the same package name can never receive the pairing keys.
     */
    fun newAppReady(c: Context): Boolean {
        val pm = c.packageManager
        val theirs = Releases.installedSigner(pm, NEW_PACKAGE) ?: return false
        return theirs == Releases.installedSigner(pm, c.packageName)
    }

    /** The request that hands every device to the new app; start it for a result, after [newAppReady]. */
    fun moveIntent(): Intent = Intent(ACTION)
        .setClassName(NEW_PACKAGE, IMPORT_ACTIVITY)
        .putExtra(EXTRA_VERSION, VERSION)
        .putStringArrayListExtra(EXTRA_LINKS, ArrayList(Hub.devices.value.map(Setup::pairingLink)))
        .putExtra(EXTRA_PARENT_NAME, Hub.parentName.value)
}
