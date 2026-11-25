package home.phonelock;

import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Bluetooth for the child's Wi-Fi &amp; Bluetooth screen: switch it on or off, list paired devices, find nearby
 * ones and pair with one (headphones, speakers). Switching Bluetooth on and off is refused to ordinary apps
 * since Android 13 but allowed for the device owner.
 *
 * <p>Android asks the child to confirm every pairing, headphones included, in a dialog that belongs to the
 * Settings app, and only system apps may answer that dialog themselves. The lock normally keeps Settings out, so
 * while a pairing this screen started is in progress Settings may run inside the lock (see {@link #pairing}).
 * That window closes as soon as the pairing succeeds or fails, or after {@link #PAIRING_WINDOW_MS}, whichever
 * comes first, and it is never saved: a restart closes it too. Forgetting a device isn't available to apps; a
 * parent does that in Settings (parent mode). New pairings are reported to the parents.
 */
@SuppressWarnings("MissingPermission") // granted by the device owner (Policy.OWN_PERMISSIONS)
final class BluetoothControl {
    private BluetoothControl() {}

    /** Settings' package: its pairing dialog is the one Android shows for every pairing. */
    static final String SETTINGS = "com.android.settings";

    /** The longest Settings may stay inside the lock for one pairing, if Android never reports the outcome. */
    static final long PAIRING_WINDOW_MS = 2 * 60_000L;

    /** Until when Settings may run inside the lock for a pairing; 0 when no pairing is in progress. */
    private static volatile long pairingUntil;

    /** Listens for the outcome of the pairing in progress; null when there is none. */
    private static BroadcastReceiver outcome;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static BluetoothAdapter adapter(Context c) {
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }

    static boolean available(Context c) {
        return adapter(c) != null;
    }

    static boolean isOn(Context c) {
        BluetoothAdapter a = adapter(c);
        return a != null && a.isEnabled();
    }

    /** Switches Bluetooth on or off; false if Android refused. */
    @SuppressWarnings("deprecation")
    static boolean setOn(Context c, boolean on) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return false;
        try {
            return on ? a.enable() : a.disable();
        } catch (Exception e) {
            Log.w(Policy.TAG, "bluetooth on/off: " + e);
            return false;
        }
    }

    /** Paired devices, by name. */
    static List<BluetoothDevice> paired(Context c) {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothAdapter a = adapter(c);
        if (a == null) return out;
        try {
            out.addAll(a.getBondedDevices());
        } catch (SecurityException e) {
            Log.w(Policy.TAG, "bluetooth paired: " + e);
        }
        out.sort((x, y) -> name(x).compareToIgnoreCase(name(y)));
        return out;
    }

    /** Starts looking for nearby devices; each arrives as BluetoothDevice.ACTION_FOUND. */
    static void discover(Context c) {
        BluetoothAdapter a = adapter(c);
        if (a == null) return;
        try {
            if (a.isDiscovering()) a.cancelDiscovery();
            a.startDiscovery();
        } catch (SecurityException e) {
            Log.w(Policy.TAG, "bluetooth discover: " + e);
        }
    }

    static void stopDiscovery(Context c) {
        BluetoothAdapter a = adapter(c);
        try {
            if (a != null && a.isDiscovering()) a.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
    }

    /** Whether this phone is paired with {@code d}. */
    static boolean isPaired(BluetoothDevice d) {
        try {
            return d.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Whether a pairing started here is still in progress, so Settings' pairing dialog may run inside the lock. */
    static boolean pairing() {
        return System.currentTimeMillis() < pairingUntil;
    }

    /**
     * Starts pairing with {@code d}: lets Android's pairing dialog run inside the lock, then asks Android to pair.
     * The outcome arrives as BluetoothDevice.ACTION_BOND_STATE_CHANGED; a success is reported to the parents here,
     * whether or not this screen is still in front. Returns false for a phone or computer (see {@link #accessory})
     * and if Android refused to start.
     */
    static boolean pair(Context c, BluetoothDevice d) {
        stopDiscovery(c);
        if (!accessory(d)) return false;
        Context app = c.getApplicationContext();
        openPairingWindow(app, d);
        try {
            if (d.createBond()) return true;
        } catch (SecurityException e) {
            Log.w(Policy.TAG, "bluetooth pair: " + e);
        }
        closePairingWindow(app);
        return false;
    }

    /**
     * Whether {@code d} may be paired by the child: accessories (headphones, speakers, watches, keyboards), not
     * phones or computers, which a parent pairs in parent mode. A device that doesn't say what it is counts as an
     * accessory, since many cheap headsets don't.
     */
    static boolean accessory(BluetoothDevice d) {
        try {
            android.bluetooth.BluetoothClass k = d.getBluetoothClass();
            if (k == null) return true;
            int major = k.getMajorDeviceClass();
            return major != android.bluetooth.BluetoothClass.Device.Major.PHONE
                    && major != android.bluetooth.BluetoothClass.Device.Major.COMPUTER;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * Lets Settings run inside the lock until the pairing with {@code d} settles or {@link #PAIRING_WINDOW_MS}
     * passes. Settings is added to the lock's packages directly, since a full {@link Policy#apply} runs in the
     * background and could come too late for the dialog; any apply meanwhile keeps it (see {@link #pairing}).
     */
    private static synchronized void openPairingWindow(Context app, BluetoothDevice d) {
        closeReceiver(app);
        long until = System.currentTimeMillis() + PAIRING_WINDOW_MS;
        pairingUntil = until;

        // Follow the outcome from the app, not the screen: the dialog pauses the screen, which then stops listening.
        outcome = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                BluetoothDevice who = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (who == null || !who.getAddress().equals(d.getAddress())) return;
                int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (state == BluetoothDevice.BOND_BONDING) return;
                if (state == BluetoothDevice.BOND_BONDED) {
                    Outbox.alert(ctx, ctx.getString(R.string.tools_bluetooth_paired_event, name(who)));
                }
                closePairingWindow(app);
            }
        };
        app.registerReceiver(
                outcome, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED);

        // If Android never reports an outcome, close anyway (unless a newer pairing has started since).
        MAIN.postDelayed(
                () -> {
                    if (pairingUntil == until) closePairingWindow(app);
                },
                PAIRING_WINDOW_MS);

        try {
            DevicePolicyManager dpm = Policy.dpm(app);
            java.util.Set<String> pkgs =
                    new java.util.LinkedHashSet<>(java.util.Arrays.asList(dpm.getLockTaskPackages(Policy.admin(app))));
            pkgs.add(SETTINGS);
            dpm.setLockTaskPackages(Policy.admin(app), pkgs.toArray(new String[0]));
        } catch (Exception e) {
            Log.w(Policy.TAG, "bluetooth pairing window: " + e);
        }
    }

    /** Ends the pairing window: Settings is out of the lock again once the policy is re-applied. */
    private static synchronized void closePairingWindow(Context app) {
        pairingUntil = 0;
        closeReceiver(app);
        Policy.applySoon(app);
    }

    private static void closeReceiver(Context app) {
        if (outcome == null) return;
        try {
            app.unregisterReceiver(outcome);
        } catch (IllegalArgumentException ignored) {
        }
        outcome = null;
    }

    /** A device's name, or its address when it has none. */
    static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }
}
