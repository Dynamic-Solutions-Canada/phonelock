package home.phonelock;

import android.net.Uri;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;

/**
 * Sees every outgoing call before it is placed (Android's call redirection role, granted by
 * tools/configure-lock.sh) and cancels calls to numbers off the safe list when parents limit outgoing
 * calls. Android never sends emergency numbers here, so 911 and the like always connect.
 */
public class OutgoingCallGuard extends CallRedirectionService {
    @Override
    public void onPlaceCall(Uri handle, PhoneAccountHandle account, boolean allowInteractiveResponse) {
        String number = handle == null ? null : handle.getSchemeSpecificPart();
        if (!CallGuard.limitOutgoing(this) || CallGuard.match(this, number) != null) {
            placeCallUnmodified();
            return;
        }
        cancelCall();
        String who = CallGuard.describe(this, number);
        Remote.notifyKid(this, getString(R.string.events_call_not_allowed_child, who));
        Remote.event(this, getString(R.string.events_call_attempt, who));
        CallLogFeed.sendAttempt(this, number, who);
    }
}
