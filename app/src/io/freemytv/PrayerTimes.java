package io.freemytv;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The five daily prayer times for a place, calculated on the TV (no internet), with the parameters Indonesia's
 * Ministry of Religious Affairs (Kemenag) uses: Fajr at 20° below the horizon, Isha at 18°, Asr when a shadow equals
 * its object plus its noon shadow (Shafi'i), Maghrib at sunset, and a few minutes of ihtiyat (safety margin): 2 on Subuh, Ashar and Isya, 3 on Dzuhur and
 * Maghrib, which matches Bimas Islam's published schedule for Sumedang to the minute.
 * The sun's position follows the usual low-precision formulas (accurate to well under a minute for these times).
 */
final class PrayerTimes {
    private PrayerTimes() {}

    static final String[] NAMES = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    /** The names as said in Indonesia (Kemenag / Bimas Islam): Subuh, Dzuhur, Ashar, Maghrib, Isya. */
    static final String[] NAMES_ID = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};

    /** A prayer's name in the UI's language. */
    static String name(int i) {
        return Ui.indo ? NAMES_ID[i] : NAMES[i];
    }
    private static final double FAJR = 20, ISHA = 18, SUNSET = 0.833;
    private static final double[] IHTIYAT_MIN = {2, 3, 2, 3, 2};

    /** Today's five times (epoch ms) at the place, in the TV's time zone; `dayOffset` 1 for tomorrow. */
    static long[] times(double lat, double lon, long now, int dayOffset) {
        TimeZone zone = TimeZone.getDefault();
        Calendar day = Calendar.getInstance(zone);
        day.setTimeInMillis(now);
        day.add(Calendar.DAY_OF_MONTH, dayOffset);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long midnight = day.getTimeInMillis();
        double tz = zone.getOffset(midnight + 12 * 3_600_000L) / 3_600_000.0;
        double jd = julian(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH)) - lon / 360;

        // First guesses (hours), then one pass with the sun's position at each time.
        double[] t = {5, 12, 13, 18, 18};
        for (int pass = 0; pass < 2; pass++) {
            double[] next = new double[5];
            next[0] = noon(jd, t[0], tz, lon) - arc(jd, t[0], lat, FAJR);
            next[1] = noon(jd, t[1], tz, lon);
            next[2] = noon(jd, t[2], tz, lon) + asrArc(jd, t[2], lat);
            next[3] = noon(jd, t[3], tz, lon) + arc(jd, t[3], lat, SUNSET);
            next[4] = noon(jd, t[4], tz, lon) + arc(jd, t[4], lat, ISHA);
            t = next;
        }
        long[] out = new long[5];
        for (int i = 0; i < 5; i++) {
            double minutes = Math.ceil(t[i] * 60 + IHTIYAT_MIN[i]);   // rounded up to the minute, like printed schedules
            out[i] = midnight + Math.round(minutes) * 60_000L;
        }
        return out;
    }

    /** The next prayer from now: {index into NAMES, time}; after Isya, tomorrow's Subuh. */
    static long[] next(double lat, double lon, long now) {
        long[] today = times(lat, lon, now, 0);
        for (int i = 0; i < 5; i++) if (today[i] > now) return new long[] {i, today[i]};
        return new long[] {0, times(lat, lon, now, 1)[0]};
    }

    // MARK: - The sun

    private static double julian(int year, int month, int day) {
        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        double a = Math.floor(year / 100.0), b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    /** {declination (degrees), equation of time (hours)} at Julian day `jd`. */
    private static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * d, 360), q = fix(280.459 + 0.98564736 * d, 360);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360);
        double e = 23.439 - 0.00000036 * d;
        double ra = fix(atan2(cos(e) * sin(l), cos(l)) / 15, 24);
        return new double[] {asin(sin(e) * sin(l)), q / 15 - ra};
    }

    private static double noon(double jd, double hour, double tz, double lon) {
        double eqt = sun(jd + hour / 24)[1];
        return fix(12 - eqt, 24) + tz - lon / 15;
    }

    /** Hours between noon and the sun at `angle` degrees below the horizon. */
    private static double arc(double jd, double hour, double lat, double angle) {
        double decl = sun(jd + hour / 24)[0];
        double v = (-sin(angle) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat));
        return acos(Math.max(-1, Math.min(1, v))) / 15;
    }

    /** Hours between noon and Asr (a shadow as long as its object, plus the noon shadow). */
    private static double asrArc(double jd, double hour, double lat) {
        double decl = sun(jd + hour / 24)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        return arc(jd, hour, lat, angle);
    }

    private static double fix(double v, double range) {
        v = v - range * Math.floor(v / range);
        return v < 0 ? v + range : v;
    }

    private static double sin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double cos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double asin(double v) { return Math.toDegrees(Math.asin(v)); }
    private static double acos(double v) { return Math.toDegrees(Math.acos(v)); }
    private static double atan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
}
