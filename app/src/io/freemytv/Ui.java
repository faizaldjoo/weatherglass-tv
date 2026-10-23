package io.freemytv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Fonts and text for the home screen, the screensaver and the settings. Font: Inter (variable, assets/fonts, SIL OFL),
 * with the "display" optical size for large text and the "text" size for small text; without it, the system font at a
 * similar weight.
 */
final class Ui {
    private Ui() {}

    /** Android 9 to 11: older TVs and boxes, whose GPUs need the cheaper drawing paths (Shadows, rounded images). */
    static final boolean LEGACY = Build.VERSION.SDK_INT < 31;
    static final Locale RU = new Locale("ru", "RU");
    static final Locale PT = new Locale("pt", "BR");
    static final Locale ID = new Locale("in", "ID");
    /**
     * English or Bahasa Indonesia, from the settings or, with "auto", the TV's own language. Indonesian also sets `en`
     * (numbers, dates and anything not translated follow the English), with `indo` choosing the Indonesian text.
     */
    static volatile String language = language();
    static volatile boolean indo = "id".equals(language), en = "en".equals(language) || indo, es = "es".equals(language), de = "de".equals(language),
            ru = "ru".equals(language);
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = font(a, 32, 200);
        light = font(a, 32, 300);
        displayMedium = font(a, 32, 500);
        displaySemibold = font(a, 32, 600);
        regular = font(a, 14, 400);
        medium = font(a, 14, 500);
        semibold = font(a, 14, 600);
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight; the system font if the file is missing. */
    private static Typeface font(AssetManager a, int opticalSize, int weight) {
        if (has(a, FONT)) {
            try {
                Typeface t = new Typeface.Builder(a, FONT)
                        .setFontVariationSettings("'opsz' " + opticalSize + ", 'wght' " + weight).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
            }
        }
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    private static final String FONT = "fonts/InterVariable.ttf";

    /** "pt", "es", "de", "ru" or "en": the one picked (TV settings or config.properties), or with "auto" the TV's own, else English. */
    private static String language() {
        String lang = Prefs.language();
        if (lang.equals("en") || lang.equals("id")) return lang;
        String tv = Locale.getDefault().getLanguage();   // Android still says "in" for Indonesian
        return "in".equals(tv) || "id".equals(tv) ? "id" : "en";
    }

    /** After the language changes in the settings (and once Prefs can read them). */
    static void applyLanguage() {
        language = language();
        indo = "id".equals(language);
        en = "en".equals(language) || indo;
        es = "es".equals(language);
        de = "de".equals(language);
        ru = "ru".equals(language);
    }

    private static boolean has(AssetManager a, String path) {
        try (InputStream in = a.open(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the prices don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static String tr(String pt, String english) {
        return indo ? Indonesian.text(english) : de ? German.text(english) : ru ? Russian.text(english) : es ? Spanish.text(english) : en ? english : pt;
    }

    /** German comes from German.java here too (keyed by the English, with patterns for text built at runtime). */
    static String tr(String pt, String english, String spanish) {
        return indo ? Indonesian.text(english) : de ? German.text(english) : ru ? Russian.text(english) : es ? spanish : en ? english : pt;
    }

    static Locale locale() {
        return indo ? ID : ru ? RU : es ? spanish() : de ? german() : en ? Locale.US : PT;
    }

    /** The TV's own Spanish (es-MX, es-AR…) when it is in Spanish, Spain's otherwise. */
    private static Locale spanish() {
        Locale tv = Locale.getDefault();
        return "es".equals(tv.getLanguage()) ? tv : new Locale("es", "ES");
    }

    /** The TV's own German (de-AT, de-CH…) when it is in German, Germany's otherwise. */
    private static Locale german() {
        Locale tv = Locale.getDefault();
        return "de".equals(tv.getLanguage()) ? tv : Locale.GERMANY;
    }

    /** "21:47" (in English, "9:47", like the iPhone in the US). */
    static String clock(long now) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now));   // 24-hour, like Indonesia's clocks
    }

    /** "5:21" (sunrise and sunset; in English, "5:21 AM"). */
    static String time(long at) {
        return once("time", at, () -> new SimpleDateFormat("HH:mm", Locale.US).format(new Date(at)));
    }

    private static final HashMap<String, String> FORMATTED = new HashMap<>();

    /**
     * A formatted value, made once per language: the bottom drawer is redrawn on every frame of its focus animation,
     * and SimpleDateFormat and String.format there cost ~10 ms a frame on a Mi Box 3.
     */
    static String once(String kind, double value, Supplier<String> format) {
        String key = kind + '|' + language + '|' + value;
        synchronized (FORMATTED) {
            String s = FORMATTED.get(key);
            if (s == null) {
                if (FORMATTED.size() > 200) FORMATTED.clear();
                s = format.get();
                FORMATTED.put(key, s);
            }
            return s;
        }
    }

    /** "domingo, 18 de outubro" (lowercase, like the iPhone in Portuguese); "Sunday, October 18" in English; "Sonntag, 18. Oktober". */
    /** "Good Evening, Haikal" (the name from Settings; without one, just the greeting), by the TV's clock. */
    static String greeting(long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        String hello = indo ? (h >= 4 && h < 11 ? "Selamat Pagi" : h < 15 && h >= 11 ? "Selamat Siang" : h >= 15 && h < 18 ? "Selamat Sore" : "Selamat Malam")
                : h >= 5 && h < 12 ? "Good Morning" : h >= 12 && h < 18 ? "Good Afternoon" : "Good Evening";
        String name = Prefs.greetingName();
        return name.isEmpty() ? hello : hello + ", " + name;
    }

    /** The waving hand after the greeting. */
    static final String WAVE = "\uD83D\uDC4B";
    static final long WAVE_MS = 1600;

    /** The hand's tilt in degrees, `ms` into a wave (0 outside it): a few quick waves that settle. */
    static float waveAngle(long ms) {
        if (ms <= 0 || ms >= WAVE_MS) return 0;
        float t = ms / (float) WAVE_MS;
        return (float) (16 * Math.sin(t * 2 * Math.PI * 2.5) * (1 - t) + 4 * Math.sin(t * Math.PI));
    }

    static String date(long now) {
        return indo ? new SimpleDateFormat("EEEE, d MMMM", ID).format(new Date(now))
                : ru ? new SimpleDateFormat("EEEE, d MMMM", locale()).format(new Date(now))
                : en ? new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date(now))
                : new SimpleDateFormat(de ? "EEEE, d. MMMM" : "EEEE, d 'de' MMMM", locale()).format(new Date(now));
    }

    /** Spinning arc (work in progress). */
    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
