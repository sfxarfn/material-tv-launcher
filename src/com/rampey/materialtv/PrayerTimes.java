package com.rampey.materialtv;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Prayer times for Kota Batu, computed on the TV (no network): Fajr 20 degrees, Isha 18 degrees, Asr with the
 * Shafi'i shadow, as the Kemenag RI (Bimas Islam) table does. The astronomical times are shifted by a fixed number of
 * minutes per prayer so they match that table for Batu; fitted on 2026-10 and 2026-06, within about a minute of it.
 */
final class PrayerTimes {
    // The place the times are for: Kota Batu until the companion app reports another (see configure).
    private static final double BATU_LAT = -7.8672, BATU_LON = 112.5239;
    private static final double[] BATU_OFFSET = {2.56, 3.42, 2.47, 7.3, 2.43};   // fitted to the Kemenag table for Batu
    private static final double[] DEFAULT_OFFSET = {2, 2, 2, 2, 2};            // Kemenag's usual 2 minutes of caution
    private static final String[] NAMES = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final double RAD = Math.PI / 180;

    private static volatile double LAT = BATU_LAT, LON = BATU_LON;
    private static volatile TimeZone ZONE = TimeZone.getTimeZone("Asia/Jakarta");
    private static volatile double[] OFFSET = BATU_OFFSET;
    private static volatile String place = "Kota Batu";

    private PrayerTimes() {
    }

    /** The place the times are for. Near Kota Batu the fitted offsets apply; anywhere else the standard 2 minutes. */
    static void configure(String name, double lat, double lon, String zone) {
        place = name;
        LAT = lat;
        LON = lon;
        ZONE = TimeZone.getTimeZone(zone);
        double dLat = (lat - BATU_LAT) * 111, dLon = (lon - BATU_LON) * 111 * Math.cos(BATU_LAT * RAD);
        OFFSET = Math.sqrt(dLat * dLat + dLon * dLon) < 30 ? BATU_OFFSET : DEFAULT_OFFSET;
    }

    static String placeName() {
        return place;
    }

    /** "Next Salah — Ashar in 43 mins" */
    static String nextLabel(long nowMs) {
        Calendar c = Calendar.getInstance(ZONE);
        c.setTimeInMillis(nowMs);
        double nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0;
        double[] today = times(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        boolean friday = c.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
        for (int i = 0; i < NAMES.length; i++) {
            // On Friday the midday prayer is Jumuah.
            if (today[i] > nowMin) return label(i == 1 && friday ? "Jumuah" : NAMES[i], today[i] - nowMin);
        }
        c.add(Calendar.DAY_OF_MONTH, 1);
        double[] tomorrow = times(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        return label(NAMES[0], 24 * 60 - nowMin + tomorrow[0]);
    }

    /** The next sun event as {"rise" or "set", "17:24"}; once today's sunset has passed, tomorrow's sunrise. Sea-level horizon, no offset. */
    static String[] nextSun(long nowMs) {
        Calendar c = Calendar.getInstance(ZONE);
        c.setTimeInMillis(nowMs);
        double nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        double[] today = sunTimes(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        if (nowMin < today[0]) return new String[]{"rise", clock(today[0])};
        if (nowMin < today[1]) return new String[]{"set", clock(today[1])};
        c.add(Calendar.DAY_OF_MONTH, 1);
        return new String[]{"rise", clock(sunTimes(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))[0])};
    }

    /**
     * How night it is, 0 (day) to 1 (full night), following the sun: it eases in from 15 minutes before sunset to
     * an hour after it, holds through the night, and eases out from an hour before sunrise to 15 minutes after it.
     */
    static float nightLevel(long nowMs) {
        Calendar c = Calendar.getInstance(ZONE);
        c.setTimeInMillis(nowMs);
        double now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0;
        double[] t = sunTimes(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        double rise = t[0], set = t[1];
        double x;
        if (now >= rise + 15 && now <= set - 15) return 0f;
        if (now < rise + 15) x = 1 - (now - (rise - 60)) / 75;   // dawn: night until an hour before sunrise
        else x = (now - (set - 15)) / 75;                        // dusk
        x = Math.max(0, Math.min(1, x));
        return (float) (x * x * (3 - 2 * x));
    }

    private static final String[] HIJRI_MONTHS = {"Muh", "Saf", "Rab I", "Rab II", "Jum I", "Jum II", "Raj", "Sha", "Ram", "Syaw", "Dzul Q", "Dzul H"};

    /**
     * The Hijri date, short: "12 Rab II 1448". The Islamic day starts at sunset, so from Maghrib on it shows the next
     * day's date. Umm al-Qura calendar (Android's own); Indonesia's Kemenag calendar can differ from it by a day.
     */
    static String hijri(long nowMs) {
        Calendar c = Calendar.getInstance(ZONE);
        c.setTimeInMillis(nowMs);
        double nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        double[] t = sunTimes(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        if (nowMin >= t[1]) c.add(Calendar.DAY_OF_MONTH, 1);
        java.time.chrono.HijrahDate h = java.time.chrono.HijrahDate.from(
                java.time.LocalDate.of(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)));
        return h.get(java.time.temporal.ChronoField.DAY_OF_MONTH) + " "
                + HIJRI_MONTHS[h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR) - 1] + " "
                + h.get(java.time.temporal.ChronoField.YEAR_OF_ERA);
    }

    /** Minutes after local midnight for sunrise and sunset. */
    private static double[] sunTimes(int y, int m, int d) {
        double[] sun = sun(jd(y, m, d) + 0.5);
        double zone = ZONE.getOffset(new java.util.GregorianCalendar(y, m - 1, d, 12, 0).getTimeInMillis()) / 3_600_000.0;
        double dhuhr = 12 + zone - LON / 15 - sun[1];
        double half = hourAngle(0.833, sun[0]);
        return new double[]{Math.round((dhuhr - half) * 60), Math.round((dhuhr + half) * 60)};
    }

    private static String clock(double minutes) {
        int min = (int) minutes;
        return String.format(java.util.Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    private static String label(String name, double minutes) {
        int m = (int) Math.ceil(minutes);
        String in;
        if (m < 60) in = m + (m == 1 ? " min" : " mins");
        else in = (m / 60) + "h" + (m % 60 == 0 ? "" : " " + (m % 60) + "m");
        return "Next Salah — " + name + " in " + in;
    }

    /** Minutes after local midnight for Subuh, Dzuhur, Ashar, Maghrib, Isya, rounded to the minute. */
    static double[] times(int y, int m, int d) {
        double[] sun = sun(jd(y, m, d) + 0.5);
        double dec = sun[0], eqt = sun[1];
        double zone = ZONE.getOffset(new java.util.GregorianCalendar(y, m - 1, d, 12, 0).getTimeInMillis()) / 3_600_000.0;
        double dhuhr = 12 + zone - LON / 15 - eqt;
        double asrAngle = Math.atan(1 / (1 + Math.tan(Math.abs(LAT - dec) * RAD))) / RAD;
        double[] raw = {
                dhuhr - hourAngle(20, dec),
                dhuhr,
                dhuhr + hourAngle(-asrAngle, dec),
                dhuhr + hourAngle(0.833, dec),
                dhuhr + hourAngle(18, dec)};
        double[] out = new double[5];
        for (int i = 0; i < 5; i++) out[i] = Math.round(raw[i] * 60 + OFFSET[i]);
        return out;
    }

    private static double hourAngle(double angle, double dec) {
        double x = (-Math.sin(angle * RAD) - Math.sin(LAT * RAD) * Math.sin(dec * RAD)) / (Math.cos(LAT * RAD) * Math.cos(dec * RAD));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, x)))) / 15;
    }

    private static double jd(int y, int m, int d) {
        if (m <= 2) {
            y--;
            m += 12;
        }
        int a = y / 100;
        int b = 2 - a + a / 4;
        return (int) (365.25 * (y + 4716)) + (int) (30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** Declination (degrees) and equation of time (hours) at a Julian day. */
    private static double[] sun(double jd) {
        double dd = jd - 2451545.0;
        double g = mod(357.529 + 0.98560028 * dd, 360);
        double q = mod(280.459 + 0.98564736 * dd, 360);
        double l = mod(q + 1.915 * Math.sin(g * RAD) + 0.020 * Math.sin(2 * g * RAD), 360);
        double e = 23.439 - 0.00000036 * dd;
        double ra = mod(Math.atan2(Math.cos(e * RAD) * Math.sin(l * RAD), Math.cos(l * RAD)) / RAD / 15, 24);
        double eqt = q / 15 - ra;
        if (eqt > 12) eqt -= 24;
        if (eqt < -12) eqt += 24;
        double dec = Math.asin(Math.sin(e * RAD) * Math.sin(l * RAD)) / RAD;
        return new double[]{dec, eqt};
    }

    private static double mod(double a, double b) {
        double r = a % b;
        return r < 0 ? r + b : r;
    }
}
