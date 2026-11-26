package home.phonelock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Stores the parent pairing: the ntfy server and topic, the shared AES secret and this phone's display
 * name. Set over adb by {@link ConfigReceiver}, during setup from the parent app.
 */
final class Pairing {
    private static final String TAG = "PhoneLock";
    /** The sealed secret (SecretBox); the plain "secret" key is only read to migrate older installs. */
    private static final String SEALED = "secret_enc";

    private static final String PLAIN = "secret";

    /** The secret as last read or set in this process, so messages don't each cost a keystore call. */
    private static String cached;

    /** True when the stored secret can never be opened on this phone; worked out on each read, never stored. */
    private static boolean lost;

    private Pairing() {}

    /**
     * The 32-byte base64url pairing secret, or "" when there is none or it can't be read now. Never throws and
     * never writes, except to move an older install's plain secret into sealed storage: this runs on boot, before
     * the phone is unlocked, and a lock app that fails here can't be reached to be fixed.
     */
    static synchronized String secret(Context c) {
        if (cached != null) return cached;
        SharedPreferences p = Policy.prefs(c);
        String sealed = p.getString(SEALED, null);
        if (sealed != null) {
            try {
                cached = SecretBox.open(sealed);
                lost = false;
                return cached;
            } catch (SecretBox.LostKeyException e) {
                // Permanent: shown on the home screen and in diagnostics until a parent pairs this phone again.
                Log.w(TAG, "pairing secret can't be read on this phone", e);
                lost = true;
                return "";
            } catch (Exception e) {
                // Possibly passing (keystore busy): not cached, so the next read tries again.
                Log.w(TAG, "pairing secret not readable yet", e);
                return "";
            }
        }
        String plain = p.getString(PLAIN, "");
        if (!plain.isEmpty()) migrate(p, plain);
        cached = plain;
        lost = false;
        return plain;
    }

    /**
     * Whether this phone holds a pairing secret it can't read any more, so no parent can reach it until it is
     * paired again (Fix setup over Wi-Fi from the parent app re-sends it).
     */
    static synchronized boolean keyLost(Context c) {
        secret(c);
        return lost;
    }

    /** Whether the secret is stored sealed and no plain copy is left; for adb status, never the secret itself. */
    static synchronized boolean storedSealed(Context c) {
        SharedPreferences p = Policy.prefs(c);
        return p.contains(SEALED) && !p.contains(PLAIN);
    }

    /**
     * Debug builds only: deletes the keystore's wrapping key, as a keystore reset would, so the lost-key path can
     * be tested on a phone. Release builds refuse it (see ConfigReceiver).
     */
    static synchronized void forgetKeyForTest() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        ks.deleteEntry(SecretBox.ALIAS);
        cached = null;
    }

    /**
     * Moves an older install's plain secret into sealed storage. The sealed copy is written and checked before
     * the plain one is removed, so a crash or a keystore failure always leaves one readable copy.
     */
    private static void migrate(SharedPreferences p, String plain) {
        try {
            String sealed = SecretBox.seal(plain);
            if (!plain.equals(SecretBox.open(sealed))) return;
            if (!p.edit().putString(SEALED, sealed).commit()) return;
            p.edit().remove(PLAIN).commit();
        } catch (Exception e) {
            // Keeps working from the plain copy, and tries again on the next start.
            Log.w(TAG, "pairing secret not moved to sealed storage yet", e);
        }
    }

    /** Sets the ntfy server and topic, forgetting the last-seen message, and reconnects. */
    static void setRemote(Context c, String server, String topic) {
        storeRemote(c, server, topic);
        RemoteService.restart(c);
    }

    /** Sets the 32-byte base64url secret (empty clears it) and reconnects. Throws if it isn't a valid key. */
    static void setSecret(Context c, String secret) {
        storeSecret(c, secret);
        // Paired by a lock app that has languages: later updates must not pin it to English.
        Language.markSetUp(c);
        RemoteService.restart(c);
    }

    private static void storeRemote(Context c, String server, String topic) {
        Policy.prefs(c)
                .edit()
                .putString("ntfy_server", server)
                .putString("ntfy_topic", topic)
                .remove("ntfy_last_id")
                .apply();
    }

    /**
     * Stores [secret] sealed, replacing any earlier one (empty clears it). If the keystore can't seal it, the
     * secret is kept in plain form so the phone stays reachable, and moved on a later start.
     */
    private static synchronized void storeSecret(Context c, String secret) {
        SharedPreferences p = Policy.prefs(c);
        // A new pairing (or none, after a release) starts a new family chat: the old one belongs to the old
        // parents, so it is deleted rather than offered to whoever pairs next.
        String old = secret(c);
        if (!(old == null ? "" : old).equals(secret == null ? "" : secret)) ChatLog.clear(c);
        if (secret == null || secret.isEmpty()) {
            p.edit().remove(SEALED).remove(PLAIN).commit();
            cached = "";
            lost = false;
            return;
        }
        Crypto.key(secret); // validates: throws if it isn't a 32-byte key
        String sealed = null;
        try {
            String s = SecretBox.seal(secret);
            if (secret.equals(SecretBox.open(s))) sealed = s;
        } catch (Exception e) {
            Log.w(TAG, "pairing secret stored unsealed for now", e);
        }
        if (sealed != null) {
            p.edit().putString(SEALED, sealed).remove(PLAIN).commit();
        } else {
            p.edit().putString(PLAIN, secret).remove(SEALED).commit();
        }
        cached = secret;
        lost = false;
    }

    /** Forgets the pairing (server, topic and secret) without reconnecting: the phone was released. */
    static void forget(Context c) {
        storeRemote(c, "", "");
        storeSecret(c, "");
    }

    /** Sets the name the parent apps show for this phone, and tells them. */
    static void setName(Context c, String name) {
        Policy.prefs(c)
                .edit()
                .putString("device_name", name == null || name.isEmpty() ? "Device" : name)
                .apply();
        Remote.sendState(c, false);
    }
}
