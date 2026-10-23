package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507;
    static final int[] KEYS = RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt", "en", "es", "de" or "ru". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("ru") ? "ru" : v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("de") ? "de"
                : v.startsWith("en") ? "en" : v.startsWith("id") || v.startsWith("in") ? "id" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    /** The size of the clock, date, weather and prices on the clock layout: "small", "medium" or "large". */
    static String textSize() {
        String v = sp().getString("text_size", "medium");
        return v.equals("small") || v.equals("large") ? v : "medium";
    }

    static void setTextSize(String v) { put("text_size", v); }

    /** The clock widget's glass: "clear" (the shelf's), "frosted" (the sky blurred through it) or "strong" (more blur, milkier). */
    static String glass() {
        String v = sp().getString("glass", "frosted");
        return v.equals("clear") || v.equals("strong") ? v : "frosted";
    }

    static void setGlass(String v) { put("glass", v); }

    /** textSize() as a scale of the original (large) layout. */
    static float textScale() {
        String v = textSize();
        return v.equals("small") ? 0.7f : v.equals("large") ? 1f : 0.85f;
    }

    // MARK: - Greeting and camera

    /** The name in the greeting ("Good Evening, Haikal"), typed on the TV; config greeting_name until then. */
    static String greetingName() { return (app == null ? Config.GREETING_NAME : sp().getString("greeting_name", Config.GREETING_NAME)).trim(); }
    static void setGreetingName(String v) { put("greeting_name", v.trim()); }

    /** The CCTV camera's RTSP address, typed on the TV; config cctv_url until then. */
    static String cameraUrl() { return sp().getString("camera_url", Config.CCTV_URL).trim(); }
    static void setCameraUrl(String v) { put("camera_url", v.trim()); }

    /** Whether the camera tile shows (on by default once there is an address). */
    static boolean camera() { return sp().getBoolean("camera", true) && !cameraUrl().isEmpty(); }
    static void setCamera(boolean on) { put("camera", on); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    /**
     * The home screen's background: "ink" (ink mountains, following the time of day and the weather) or "photos" (the
     * picks' photos, as before). The default comes from config.properties (weather_wallpaper).
     */
    static final String[] WALLPAPERS = {"ink", "photos"};

    static String wallpaper() {
        String v = sp().getString("wallpaper", Config.WEATHER_SKY ? "ink" : "photos");
        return v.equals("photos") ? "photos" : "ink";   // the weather sky (and the earlier rain-on-glass) became the ink mountains
    }
    static void setWallpaper(String v) { put("wallpaper", v); }

    /** The weather sky moves (clouds, rain, stars); off keeps it still for slower TVs. */
    static boolean wallpaperMotion() { return sp().getBoolean("wallpaperMotion", Config.WEATHER_MOTION); }
    static void setWallpaperMotion(boolean on) { put("wallpaperMotion", on); }

    /**
     * Sky preview: which weather the sky shows ("live" = the real weather) and whether by day or night ("live" = the
     * real time). Only the sky changes; the weather widget keeps the real forecast.
     */
    static final String[] SKY_WEATHERS = {"live", "clear", "partly", "cloudy", "drizzle", "rain", "storm", "haze"};
    private static final int[] SKY_CODES = {-1, 0, 2, 3, 53, 61, 95, 45};
    static final String[] SKY_TIMES = {"live", "sunrise", "morning", "noon", "afternoon", "golden", "sunset", "dusk", "night"};
    /** The hour (on the ink wallpaper's fixed day) for each Sky preview time; -1 for the real time. */
    private static final float[] SKY_TIME_HOURS = {-1, 5.75f, 7.5f, 12, 15.3f, 16.9f, 17.7f, 18.4f, 21.5f};

    static String skyWeather() {
        String v = sp().getString("skyWeather", "live");
        return java.util.Arrays.asList(SKY_WEATHERS).contains(v) ? v : "live";
    }
    static void setSkyWeather(String v) { put("skyWeather", v); }

    /** The weather code the sky should show instead of the real one, or Integer.MIN_VALUE for the real one. */
    static int skyWeatherCode() {
        int at = java.util.Arrays.asList(SKY_WEATHERS).indexOf(skyWeather());
        return at <= 0 ? Integer.MIN_VALUE : SKY_CODES[at];
    }

    static String skyTime() {
        String v = sp().getString("skyTime", "live");
        if (v.equals("day")) return "afternoon";   // the first version had only Day and Night
        return java.util.Arrays.asList(SKY_TIMES).contains(v) ? v : "live";
    }

    /** The hour picked in Sky preview: time, or -1 for the real time. */
    static float skyTimeHour() {
        return SKY_TIME_HOURS[Math.max(0, java.util.Arrays.asList(SKY_TIMES).indexOf(skyTime()))];
    }
    static void setSkyTime(String v) { put("skyTime", v); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            if (stamp != keysStamp) {
                keysStamp = stamp;
                java.util.Arrays.fill(keyApps, null);
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
