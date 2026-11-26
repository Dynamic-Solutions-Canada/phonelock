package home.phonelock;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The child's Wi-Fi or Bluetooth screen, opened from its icon on the home screen (they're scheduled and
 * allowed like apps, see {@link BuiltInApps}): an on/off switch and a short list. Wi-Fi: tap a network to join (password the first time), hold a saved one to forget it.
 * Bluetooth: paired devices, and "Pair a new device" for headphones and speakers. Settings stays locked;
 * parents are told about new networks and devices ({@link WifiControl}, {@link BluetoothControl}).
 *
 * <p>Start it with {@link #EXTRA_TAB} = {@link #TAB_BLUETOOTH} for Bluetooth, else it shows Wi-Fi. It
 * closes if that one isn't allowed right now.
 */
public class ConnectionsActivity extends Activity {
    static final String EXTRA_TAB = "tab";
    static final int TAB_WIFI = 0;
    static final int TAB_BLUETOOTH = 1;

    private static final int TEXT = ScreenStyle.TEXT;
    private static final int MUTED = ScreenStyle.MUTED;
    private static final int CARD = ScreenStyle.CARD;
    private static final int ACCENT = ScreenStyle.ACCENT;

    private int tab;
    private LinearLayout content;
    /**
     * Devices found by the current Bluetooth search, by address, including ones not shown (see
     * {@link BluetoothControl#accessory}); null while not searching.
     */
    private Map<String, BluetoothDevice> found;
    /** Whether the Bluetooth search is still running; Android ends it by itself after about 12 seconds. */
    private boolean searching;

    private String btNote;

    /** The device a pairing was started with from here, until its outcome has been shown; null otherwise. */
    private BluetoothDevice pairingWith;
    /** Re-checks the schedule while open, so a grant running out closes the screen. */
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            if (allowedNow()) handler.postDelayed(this, RECHECK_MS);
        }
    };
    private static final long RECHECK_MS = 30_000;

    private final BroadcastReceiver radios = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Policy.ACTION_CHANGED.equals(a)) {
                if (allowedNow()) show();
                return;
            }
            if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)) WifiControl.onConnected(c);
            if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(a)
                    && i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING) {
                String ssid = WifiControl.onAuthFailed(c);
                if (ssid != null) toast(c.getString(R.string.tools_wifi_wrong_password, ssid));
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(a)) searching = true;
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) searching = false;
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (BluetoothDevice.ACTION_FOUND.equals(a)
                    && d != null
                    && found != null
                    && d.getBondState() != BluetoothDevice.BOND_BONDED) {
                found.put(d.getAddress(), d);
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a) && d != null) {
                int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                int prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (state == BluetoothDevice.BOND_BONDED) {
                    // BluetoothControl reports the new device to the parents.
                    if (found != null) found.remove(d.getAddress());
                    btNote = c.getString(R.string.tools_bluetooth_paired, BluetoothControl.name(d));
                    pairingWith = null;
                } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                    btNote = c.getString(R.string.tools_bluetooth_pair_failed, BluetoothControl.name(d));
                    pairingWith = null;
                }
            }
            show();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tab = getIntent().getIntExtra(EXTRA_TAB, TAB_WIFI);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        // fitsSystemWindows replaces a view's own padding with the system bars' insets, so the frame keeps
        // clear of the bars and the layout inside it carries the margins.
        LinearLayout frame = new LinearLayout(this);
        frame.setFitsSystemWindows(true);
        frame.setBackground(ScreenStyle.background());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(16));
        frame.addView(root, new LinearLayout.LayoutParams(-1, -1));

        root.addView(text(
                BuiltInApps.label(this, tab == TAB_BLUETOOTH ? BuiltInApps.BLUETOOTH : BuiltInApps.WIFI), 28, TEXT));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1);
        sl.topMargin = dp(20);
        root.addView(scroll, sl);

        TextView done = pill(getString(R.string.done), ScreenStyle.BUTTON);
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(52));
        dl.topMargin = dp(12);
        root.addView(done, dl);
        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!allowedNow()) return;
        handler.postDelayed(recheck, RECHECK_MS);
        // Policy changes come from this app only; the radios' broadcasts come from the system.
        registerReceiver(radios, new IntentFilter(Policy.ACTION_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        // A found device's name and class can arrive later; the list is filtered on both.
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction(BluetoothDevice.ACTION_CLASS_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(systemRadios, f, Context.RECEIVER_EXPORTED);
        WifiControl.scan(this);
        notePairingOutcome();
        show();
    }

    /**
     * Back from Android's pairing dialog: this screen stopped listening while the dialog covered it, so the
     * outcome may have been missed. Once the pairing has settled, say how it went.
     */
    private void notePairingOutcome() {
        if (pairingWith == null || BluetoothControl.pairing()) return;
        boolean paired = BluetoothControl.isPaired(pairingWith);
        btNote = getString(
                paired ? R.string.tools_bluetooth_paired : R.string.tools_bluetooth_pair_failed,
                BluetoothControl.name(pairingWith));
        pairingWith = null;
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(recheck);
        try {
            unregisterReceiver(radios);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(systemRadios);
        } catch (IllegalArgumentException ignored) {
        }
        BluetoothControl.stopDiscovery(this);
        found = null;
        searching = false;
        super.onPause();
    }

    /** The system's radio broadcasts, handled like ours (a receiver can be registered only once). */
    private final BroadcastReceiver systemRadios = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Policy.ACTION_CHANGED.equals(i.getAction())) return; // only this app may send that
            radios.onReceive(c, i);
        }
    };

    /**
     * Like any app, this screen is open only while it's allowed (schedule, a grant, or parent mode). Closes it
     * and returns false otherwise; checked on opening, on policy changes, periodically, and before each action.
     */
    private boolean allowedNow() {
        String id = tab == TAB_BLUETOOTH ? BuiltInApps.BLUETOOTH : BuiltInApps.WIFI;
        if (BuiltInApps.allowedNow(this, id)) return true;
        if (!isFinishing()) {
            toast(getString(R.string.tools_not_allowed_now, BuiltInApps.label(this, id)));
            finish();
        }
        return false;
    }

    /** Redraws the selected tab from the radios' current state. */
    private void show() {
        content.removeAllViews();
        if (tab == TAB_WIFI) showWifi();
        else showBluetooth();
    }

    // ---------- Wi-Fi ----------

    private void showWifi() {
        boolean on = WifiControl.isOn(this);
        toggleCard(getString(R.string.tools_wifi), on, value -> {
            if (!WifiControl.setOn(this, value)) {
                toast(getString(value ? R.string.tools_wifi_switch_on_failed : R.string.tools_wifi_switch_off_failed));
            }
            show();
        });
        if (!on) return;

        List<WifiControl.Network> nets = WifiControl.nearby(this);
        heading(getString(R.string.tools_wifi_networks));
        if (nets.isEmpty()) {
            note(getString(R.string.tools_wifi_looking));
            return;
        }
        LinearLayout list = card();
        for (WifiControl.Network n : nets) {
            String detail = getString(
                    n.connected
                            ? R.string.tools_wifi_connected
                            : n.saved
                                    ? R.string.tools_wifi_saved
                                    : n.secured ? R.string.tools_wifi_secured : R.string.tools_wifi_open);
            View row = listRow(n.ssid, detail, n.connected, signalBars(n.bars));
            row.setOnClickListener(v -> onNetwork(n));
            if (n.saved)
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.tools_wifi_forget_title, n.ssid))
                            .setPositiveButton(R.string.tools_wifi_forget, (d, w) -> {
                                if (!allowedNow()) return;
                                WifiControl.forget(this, n.ssid);
                                show();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    return true;
                });
            list.addView(row);
        }
        content.addView(list);
        if (nets.stream().anyMatch(n -> n.saved)) note(getString(R.string.tools_wifi_forget_hint));
    }

    /** Tapping a network: join it, asking for its password the first time. */
    private void onNetwork(WifiControl.Network n) {
        if (n.connected || !allowedNow()) return;
        if (n.saved || !n.secured) {
            if (!WifiControl.join(this, n.ssid, null)) toast(getString(R.string.tools_wifi_join_failed, n.ssid));
            return;
        }
        EditText pw = new EditText(this);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setHint(R.string.tools_wifi_password_hint);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(pw, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(n.ssid)
                .setView(box)
                .setPositiveButton(R.string.tools_wifi_join, (d, w) -> {
                    if (!allowedNow()) return;
                    if (!WifiControl.join(this, n.ssid, pw.getText().toString())) {
                        toast(getString(R.string.tools_wifi_join_failed, n.ssid));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- Bluetooth ----------

    private void showBluetooth() {
        boolean on = BluetoothControl.isOn(this);
        toggleCard(getString(R.string.tools_bluetooth), on, value -> {
            if (!BluetoothControl.setOn(this, value)) {
                toast(getString(
                        value
                                ? R.string.tools_bluetooth_switch_on_failed
                                : R.string.tools_bluetooth_switch_off_failed));
            }
            show();
        });
        if (!on) return;

        heading(getString(R.string.tools_bluetooth_your_devices));
        List<BluetoothDevice> paired = BluetoothControl.paired(this);
        if (paired.isEmpty()) {
            note(getString(R.string.tools_bluetooth_none_yet));
        } else {
            LinearLayout list = card();
            for (BluetoothDevice d : paired) list.addView(listRow(BluetoothControl.name(d), null, false, null));
            content.addView(list);
        }

        if (btNote != null) note(btNote);
        if (found == null) {
            searchButton(getString(R.string.tools_bluetooth_pair_new));
            return;
        }

        heading(getString(R.string.tools_bluetooth_nearby));
        note(getString(R.string.tools_bluetooth_pairing_mode));
        List<BluetoothDevice> shown = new ArrayList<>();
        for (BluetoothDevice d : found.values()) if (BluetoothControl.accessory(d)) shown.add(d);

        // The search's state goes above the list, so it's in view however many devices are found.
        if (searching) {
            searchingRow();
        } else {
            // The search has ended (by itself, or because a pairing started): offer another.
            if (shown.isEmpty()) note(getString(R.string.tools_bluetooth_none_found));
            searchButton(getString(R.string.tools_bluetooth_search_again));
        }
        if (!shown.isEmpty()) {
            LinearLayout list = card();
            for (BluetoothDevice d : shown) {
                View row =
                        listRow(BluetoothControl.name(d), getString(R.string.tools_bluetooth_tap_to_pair), false, null);
                row.setOnClickListener(v -> onFound(d));
                list.addView(row);
            }
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
            ll.topMargin = dp(12);
            content.addView(list, ll);
        }
    }

    /**
     * Starts a fresh search for nearby devices, forgetting the last one's results (a device that went out of
     * range would otherwise stay listed).
     */
    private void startSearch() {
        if (!allowedNow()) return;
        found = new LinkedHashMap<>();
        btNote = null;
        searching = BluetoothControl.discover(this);
        show();
    }

    /** Tapping a found device: pair with it, which shows Android's own confirmation. */
    private void onFound(BluetoothDevice d) {
        if (!allowedNow()) return;
        btNote = getString(R.string.tools_bluetooth_pairing, BluetoothControl.name(d));
        pairingWith = d;
        if (!BluetoothControl.accessory(d)) {
            pairingWith = null;
            btNote = getString(R.string.tools_bluetooth_pair_failed, BluetoothControl.name(d));
        } else if (!BluetoothControl.pair(this, d)) {
            pairingWith = null;
            btNote = getString(R.string.tools_bluetooth_pairing_failed_start);
        }
        show();
    }

    /** The full-width button that starts a Bluetooth search. */
    private void searchButton(String label) {
        TextView pair = pill(label, ACCENT);
        pair.setTextColor(ScreenStyle.ON_ACCENT);
        pair.setOnClickListener(v -> startSearch());
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(52));
        pl.topMargin = dp(20);
        content.addView(pair, pl);
    }

    /** A small spinner and "Searching…", shown while the Bluetooth search runs. */
    private void searchingRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(12), dp(8), dp(4));
        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        r.addView(spinner, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = text(getString(R.string.tools_bluetooth_searching), 14, MUTED);
        t.setPadding(dp(10), 0, 0, 0);
        r.addView(t);
        content.addView(r);
    }

    // ---------- pieces ----------

    interface OnToggle {
        void toggle(boolean on);
    }

    /** The big card at the top of a tab: its name, On/Off, and a switch. */
    private void toggleCard(String title, boolean on, OnToggle onToggle) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(rounded(CARD, 20));
        c.setPadding(dp(20), dp(16), dp(16), dp(16));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 20, TEXT));
        words.addView(text(getString(on ? R.string.tools_on : R.string.tools_off), 14, MUTED));
        c.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Switch s = new Switch(this);
        s.setChecked(on);
        s.setOnClickListener(v -> {
            if (allowedNow()) onToggle.toggle(s.isChecked());
        });
        c.addView(s);
        content.addView(c);
    }

    /** A rounded group that list rows go in. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 20));
        c.setPadding(0, dp(4), 0, dp(4));
        return c;
    }

    /** One row: a name, an optional detail line, and an optional view on the right (signal bars). */
    private View listRow(String title, String detail, boolean highlight, View end) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(14), dp(20), dp(14));
        r.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
                null,
                new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 17, highlight ? ACCENT : TEXT));
        if (detail != null) words.addView(text(detail, 13, MUTED));
        r.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (end != null) r.addView(end);
        r.setClickable(true);
        return r;
    }

    /** Four bars of rising height, the first {@code level} lit (0 to 4). */
    private View signalBars(int level) {
        LinearLayout bars = new LinearLayout(this);
        bars.setGravity(Gravity.BOTTOM);
        for (int i = 0; i < 4; i++) {
            View bar = new View(this);
            bar.setBackground(rounded(i < Math.max(1, level) ? TEXT : Color.argb(60, 255, 255, 255), 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(4), dp(6 + i * 4));
            lp.leftMargin = dp(2);
            bars.addView(bar, lp);
        }
        return bars;
    }

    private void heading(String s) {
        TextView t = text(s, 14, MUTED);
        t.setPadding(dp(8), dp(24), 0, dp(8));
        content.addView(t);
    }

    private void note(String s) {
        TextView t = text(s, 14, MUTED);
        t.setPadding(dp(8), dp(10), dp(8), 0);
        content.addView(t);
    }

    private TextView pill(String label, int color) {
        return ScreenStyle.pill(this, label, color);
    }

    private TextView text(String s, int sp, int color) {
        return ScreenStyle.text(this, s, sp, color);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        return ScreenStyle.rounded(this, color, radiusDp);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return ScreenStyle.dp(this, v);
    }
}
