package home.phonelock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.security.GeneralSecurityException;
import javax.crypto.AEADBadTagException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;

/**
 * The sealed format, with a software key standing in for the keystore's: a secret comes back as it went in, each
 * seal looks different, and a value sealed with another key or changed in storage never opens. The keystore part
 * is checked on a device.
 */
public class SecretBoxTest {
    private static final String SECRET = "sndwiKxES8Ik8bBOiB9nlstt7reuBg2EURYMu7faX0I";

    private static SecretKey newKey() throws GeneralSecurityException {
        KeyGenerator g = KeyGenerator.getInstance("AES");
        g.init(256);
        return g.generateKey();
    }

    @Test
    public void opensWhatItSealed() throws Exception {
        SecretKey key = newKey();
        assertEquals(SECRET, SecretBox.open(key, SecretBox.seal(key, SECRET)));
    }

    @Test
    public void eachSealUsesAFreshIv() throws Exception {
        SecretKey key = newKey();
        assertNotEquals(SecretBox.seal(key, SECRET), SecretBox.seal(key, SECRET));
    }

    @Test
    public void anotherKeyCantOpenIt() throws Exception {
        String sealed = SecretBox.seal(newKey(), SECRET);
        assertThrows(AEADBadTagException.class, () -> SecretBox.open(newKey(), sealed));
    }

    @Test
    public void aChangedValueDoesntOpen() throws Exception {
        SecretKey key = newKey();
        String sealed = SecretBox.seal(key, SECRET);
        char last = sealed.charAt(sealed.length() - 1);
        String changed = sealed.substring(0, sealed.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThrows(AEADBadTagException.class, () -> SecretBox.open(key, changed));
    }

    @Test
    public void somethingThatIsntSealedIsRejected() throws Exception {
        SecretKey key = newKey();
        assertThrows(IllegalArgumentException.class, () -> SecretBox.open(key, "not sealed!"));
        assertThrows(IllegalArgumentException.class, () -> SecretBox.open(key, "c2hvcnQ"));
    }
}
