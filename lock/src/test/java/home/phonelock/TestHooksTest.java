package home.phonelock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import org.junit.Test;

/** Test-only adb commands (test-forget-key) run only in a debuggable build, never in a release one. */
public class TestHooksTest {
    @Test
    public void aReleaseBuildRefusesTestHooks() {
        assertFalse(ConfigReceiver.testHooksAllowed(0));
        assertFalse(ConfigReceiver.testHooksAllowed(ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_HAS_CODE));
    }

    @Test
    public void aDebugBuildAllowsThem() {
        assertTrue(ConfigReceiver.testHooksAllowed(ApplicationInfo.FLAG_DEBUGGABLE | ApplicationInfo.FLAG_HAS_CODE));
    }
}
