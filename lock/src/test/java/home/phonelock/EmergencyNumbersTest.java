package home.phonelock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Cases taken from Android's emergency number database (packages/services/Telephony ecc/input/eccdata.txt). */
public class EmergencyNumbersTest {
    private static final int POLICE = 1, AMBULANCE = 2, FIRE = 4, MARINE = 8, ALL = POLICE | AMBULANCE | FIRE;

    /** A number from the database for {@code country}. */
    private static EmergencyNumbers.Candidate db(String country, String number, int categories) {
        return new EmergencyNumbers.Candidate(number, country, categories, true);
    }

    /** A number the SIM or network sent. */
    private static EmergencyNumbers.Candidate carrier(String country, String number, int categories) {
        return new EmergencyNumbers.Candidate(number, country, categories, false);
    }

    /** One of the generic numbers every phone lists, with no country. */
    private static EmergencyNumbers.Candidate generic(String number) {
        return new EmergencyNumbers.Candidate(number, "", 0, true);
    }

    @Test
    public void countrysMainNumber() {
        assertEquals("911", EmergencyNumbers.pick(Arrays.asList(db("us", "911", ALL), generic("112"), generic("911"))));
        assertEquals("999", EmergencyNumbers.pick(Arrays.asList(db("gb", "999", ALL), generic("112"))));
        assertEquals("000", EmergencyNumbers.pick(Collections.singletonList(db("au", "000", ALL))));
        // France: 112 for everything; 15, 17 and 18 are single services; 115 and 116000 are helplines.
        assertEquals("112", EmergencyNumbers.pick(Arrays.asList(
                db("fr", "112", ALL), db("fr", "115", 0), db("fr", "116000", 0), db("fr", "15", AMBULANCE),
                db("fr", "17", POLICE))));
    }

    @Test
    public void whereTwoAreListedTheTaughtOne() {
        // Android sorts by number, so 112 comes first.
        assertEquals("911", EmergencyNumbers.pick(Arrays.asList(db("ca", "112", ALL), db("ca", "911", ALL))));
        assertEquals("111", EmergencyNumbers.pick(Arrays.asList(
                db("nz", "111", ALL), db("nz", "112", ALL), db("nz", "911", ALL))));
        assertEquals("112", EmergencyNumbers.pick(Arrays.asList(db("pt", "112", ALL), db("pt", "911", ALL))));
        assertEquals("112", EmergencyNumbers.pick(Arrays.asList(db("ch", "112", ALL), db("ch", "911", ALL))));
    }

    @Test
    public void carrierGeneralNumberWhenNoMainOne() {
        assertEquals("112", EmergencyNumbers.pick(Arrays.asList(carrier("xx", "999", 0), carrier("xx", "112", 0))));
        // A built-in unspecified number is a helpline, not a general one.
        assertEquals("911", EmergencyNumbers.pick(Collections.singletonList(db("kr", "1366", 0))));
    }

    @Test
    public void serviceOnlyCountriesGetTheAmbulanceNumber() {
        assertEquals("119", EmergencyNumbers.pick(Arrays.asList(
                db("jp", "110", POLICE), db("jp", "118", MARINE), db("jp", "119", AMBULANCE | FIRE),
                generic("112"), generic("911"))));
        assertEquals("120", EmergencyNumbers.pick(Arrays.asList(
                db("cn", "110", POLICE), db("cn", "119", FIRE), db("cn", "120", AMBULANCE))));
        assertEquals("192", EmergencyNumbers.pick(Arrays.asList(
                db("br", "185", MARINE), db("br", "190", POLICE), db("br", "192", AMBULANCE), db("br", "193", FIRE))));
        assertEquals("110", EmergencyNumbers.pick(Arrays.asList(db("xx", "110", POLICE), db("xx", "118", MARINE))));
    }

    @Test
    public void genericListAloneFallsBackTo911() {
        // No SIM or no number database (as on the emulator): the same list everywhere.
        assertEquals("911", EmergencyNumbers.pick(Arrays.asList(
                generic("000"), generic("08"), generic("110"), generic("112"), generic("999"))));
        assertEquals("911", EmergencyNumbers.pick(Collections.emptyList()));
        assertEquals("911", EmergencyNumbers.pick(Collections.singletonList(carrier("us", "*112#", 0))));
    }
}
