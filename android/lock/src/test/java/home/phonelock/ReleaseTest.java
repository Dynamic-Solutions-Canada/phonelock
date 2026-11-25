package home.phonelock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A parent's release is irreversible, so the phone acts on it only if it arrives within five minutes. */
public class ReleaseTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void aPromptReleaseIsActedOn() {
        assertTrue(Release.onTime(NOW, NOW));
        assertTrue(Release.onTime(NOW - Release.MAX_LATE_MS, NOW));
    }

    @Test
    public void aLateReleaseIsDropped() {
        assertFalse(Release.onTime(NOW - Release.MAX_LATE_MS - 1, NOW));
        assertFalse(Release.onTime(NOW - 6 * 3600_000L, NOW));
    }

    @Test
    public void aReleaseWithoutATimeIsDropped() {
        assertFalse(Release.onTime(0, NOW));
        assertFalse(Release.onTime(-1, NOW));
    }

    @Test
    public void aSenderClockSlightlyAheadIsAccepted() {
        // The freshness check before dispatch limits how far ahead a sender's clock may be.
        assertTrue(Release.onTime(NOW + 60_000L, NOW));
    }
}
