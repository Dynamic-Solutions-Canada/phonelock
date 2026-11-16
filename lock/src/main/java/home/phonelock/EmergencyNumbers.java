package home.phonelock;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The emergency number the SOS screen shows and dials: the local one the phone's network, SIM or Android's
 * own database reports (911, 112, 999, ...), or 911 when the phone can't tell.
 */
final class EmergencyNumbers {
    private EmergencyNumbers() {}

    /** Used when the phone reports nothing better. Every phone treats it as an emergency number. */
    static final String FALLBACK = "911";

    /** The GSM number every phone accepts, and the main number across Europe. */
    private static final String GSM = "112";

    /** Police, ambulance and fire together: how Android's number database marks a country's main number. */
    private static final int ALL_SERVICES = EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE
            | EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE
            | EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE;

    /**
     * Countries whose database lists another main number beside 112, where children are taught that other one.
     * The rest with two (Belgium, Switzerland, Portugal) teach 112. Android sorts the list by number, not by the
     * database's order, so this can't be inferred from the list.
     */
    private static final Map<String, String> TAUGHT = Map.of("ca", "911", "nz", "111");

    /** One reported number, reduced to what {@link #pick} needs. */
    static final class Candidate {
        final String number;
        /** Lower-case ISO country the number belongs to; "" for the generic list every phone has. */
        final String country;
        /** EmergencyNumber service category bits; 0 means unspecified. */
        final int categories;
        /**
         * True when it came only from Android's built-in sources (the number database or the defaults), not
         * from the SIM or the network. The database marks helplines (France's 115, Korea's and Russia's
         * service lines) as unspecified, so an unspecified number only means "every service" when a carrier
         * sent it.
         */
        final boolean builtIn;

        Candidate(String number, String country, int categories, boolean builtIn) {
            this.number = number;
            this.country = country;
            this.categories = categories;
            this.builtIn = builtIn;
        }
    }

    /** The number for this phone right now. Never throws; falls back to {@link #FALLBACK}. */
    static String local(Context c) {
        try {
            return pick(candidates(c));
        } catch (RuntimeException e) {
            // No telephony, no permission, or the phone service isn't up yet.
            Log.w(Policy.TAG, "emergency numbers: " + e);
            return FALLBACK;
        }
    }

    /**
     * Chooses from {@code list}:
     * <ol>
     * <li>Numbers with no country are ignored: phones without a SIM or a number database report the same
     * generic list everywhere (000, 08, 110, 112, 118, 119, 911, 999).</li>
     * <li>The country's main number, listed for police, ambulance and fire (911, 999, 000, 112). Where there
     * are several: the one children are taught ({@link #TAUGHT}), else 112.</li>
     * <li>A general number from the SIM or network, preferring 112.</li>
     * <li>Where there are only separate service numbers (Japan, China, Brazil): ambulance, then police.</li>
     * <li>Otherwise {@link #FALLBACK}.</li>
     * </ol>
     */
    static String pick(List<Candidate> list) {
        List<Candidate> local = new ArrayList<>();
        for (Candidate n : list) {
            if (!n.country.isEmpty() && n.number.matches("[0-9]+")) local.add(n);
        }

        // The country's main number.
        List<Candidate> main = new ArrayList<>();
        for (Candidate n : local) {
            if ((n.categories & ALL_SERVICES) == ALL_SERVICES) main.add(n);
        }
        if (!main.isEmpty()) {
            String taught = TAUGHT.get(main.get(0).country);
            for (Candidate n : main) {
                if (n.number.equals(taught)) return n.number;
            }
            return preferGsm(main);
        }

        // A general number a carrier sent.
        List<Candidate> general = new ArrayList<>();
        for (Candidate n : local) {
            if (n.categories == 0 && !n.builtIn) general.add(n);
        }
        if (!general.isEmpty()) return preferGsm(general);

        // Separate service numbers only.
        for (int wanted : new int[] {
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE,
                EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE}) {
            for (Candidate n : local) {
                if ((n.categories & wanted) != 0) return n.number;
            }
        }
        return FALLBACK;
    }

    /** 112 if it is among {@code numbers}, else the first. {@code numbers} must not be empty. */
    private static String preferGsm(List<Candidate> numbers) {
        for (Candidate n : numbers) {
            if (GSM.equals(n.number)) return n.number;
        }
        return numbers.get(0).number;
    }

    /** Every number the phone reports, over all active SIMs, as {@link Candidate}s. */
    private static List<Candidate> candidates(Context c) {
        List<Candidate> out = new ArrayList<>();
        TelephonyManager tm = c.getSystemService(TelephonyManager.class);
        if (tm == null) return out;
        Map<Integer, List<EmergencyNumber>> bySim = tm.getEmergencyNumberList();
        for (List<EmergencyNumber> numbers : bySim.values()) {
            for (EmergencyNumber n : numbers) {
                int categories = 0;
                for (int cat : n.getEmergencyServiceCategories()) categories |= cat;
                // isFromSources needs every given bit, so check each carrier source on its own.
                boolean builtIn = true;
                for (int source : n.getEmergencyNumberSources()) {
                    if (source != EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE
                            && source != EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DEFAULT) {
                        builtIn = false;
                    }
                }
                out.add(new Candidate(n.getNumber(), n.getCountryIso().toLowerCase(java.util.Locale.ROOT),
                        categories, builtIn));
            }
        }
        return out;
    }
}
