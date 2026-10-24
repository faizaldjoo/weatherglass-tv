package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String poster;          // the title's poster (TMDB path), for the two tall cards
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        boolean detailed;
        // The Movie wallpaper's lists (moviesDetails): the story, the year (or the release day), the age rating, the
        // genre · runtime line, the YouTube trailer's key and the theatrical release date
        String story = "", year = "", rating = "", info = "", trailer;
        long release;
    }

    /** The billboard's artwork: big (on screen, GPU), the focused card's landscape (GPU) and a small copy for the frost. */
    static final class Art {
        final Bitmap big, land, small;
        Art(Bitmap big, Bitmap land, Bitmap small) { this.big = big; this.land = land; this.small = small; }
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 4;   // 4: picks carry their poster
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_RU = {"ЭТИМ УТРОМ", "СЕГОДНЯ ДНЁМ", "СЕГОДНЯ ВЕЧЕРОМ"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ID = {"PAGI INI", "SORE INI", "MALAM INI"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    static final String[] PERIOD_LABEL_DE = {"HEUTE MORGEN", "HEUTE NACHMITTAG", "HEUTE ABEND"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"okko", "okko", "ru.more.play"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("oneplay", "Oneplay");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private final Map<String, Bitmap> posters = new LinkedHashMap<>();   // tall-card posters, ready to draw
    private final java.util.Set<String> posterPending = new java.util.HashSet<>();
    private static final String POSTERS = "https://image.tmdb.org/t/p/w780";
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.indo ? PERIOD_LABEL_ID : Ui.ru ? PERIOD_LABEL_RU : Ui.es ? PERIOD_LABEL_ES : Ui.de ? PERIOD_LABEL_DE : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)
                [period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    /** The pick's poster as a tall card (or null: loads it in the background and notifies when it's ready). */
    Bitmap poster(Pick p) {
        if (p == null || p.poster == null) return null;
        final String key = p.poster;
        synchronized (posters) {
            Bitmap b = posters.get(key);
            if (b != null) return b;
            if (!posterPending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadPoster(key);
            } finally {
                synchronized (posters) { posterPending.remove(key); }
            }
        });
        return null;
    }

    /** Downloads the poster once (kept on disk, the newest 30) and crops it to the tall card. */
    private void loadPoster(String path) {
        File dir = new File(context.getCacheDir(), "posters");
        File f = new File(dir, path.replace("/", ""));
        try {
            if (!f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File part = new File(f.getPath() + ".part");
                HttpURLConnection c = (HttpURLConnection) new URL(POSTERS + path).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                } finally {
                    c.disconnect();
                }
                if (!part.renameTo(f)) throw new IOException("couldn't save the poster");
                File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
                if (files != null && files.length > 30) {
                    Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                    //noinspection ResultOfMethodCallIgnored
                    for (int i = 0; i < files.length - 30; i++) files[i].delete();
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.TALL_W && bounds.outHeight / (opts.inSampleSize * 2) >= Backdrop.TALL_H) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable poster");
            Bitmap card = Backdrop.card(b, Backdrop.TALL_W, Backdrop.TALL_H);
            if (card != b) b.recycle();
            synchronized (posters) {
                posters.put(path, card);
                while (posters.size() > 12) posters.remove(posters.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("poster: " + e.getMessage());
        }
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        if (Ui.ru) return "ru-RU";
        if (Ui.indo) return "id-ID";   // TMDB has Indonesian titles and stories for most films
        if (Ui.es) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (Ui.de) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        return Ui.en ? "en-US" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language + ",en");
        p.title = title(p, o);
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        String poster = o.optString("poster_path", "");   // in the TV's language when TMDB has one
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && Ui.ru) parts.add(minutes >= 60 ? (minutes / 60) + " ч " + (minutes % 60) + " мин" : minutes + " мин");
            else if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.ru) parts.add(Russian.count(seasons, "сезон", "сезона", "сезонов"));
            else if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0 && Ui.de) parts.add(seasons == 1 ? "1 Staffel" : seasons + " Staffeln");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        if (Ui.en || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || Ui.language.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_ES = {
        {"Action & Adventure", "Acción y Aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"},
        {"War & Politics", "Guerra y Política"}, {"Kids", "Infantil"}, {"News", "Noticias"},
        {"Soap", "Telenovela"}, {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_DE = {
        {"Action & Adventure", "Action & Abenteuer"}, {"War & Politics", "Krieg & Politik"}, {"Kids", "Kinder"},
        {"News", "Nachrichten"}, {"Soap", "Seifenoper"}, {"Talk", "Talkshow"},
    };

    private static String genre(String name) {
        if (Ui.ru) return Russian.text(name);
        for (String[] g : Ui.es ? GENRES_ES : Ui.de ? GENRES_DE : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        for (String[] s : SERVICES) {
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm:" + p.kind + p.id + p.app;
    }

    private boolean needsLink(Pick p) {
        return linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    boolean same = want.equals("max") ? name.equals("max") : name.contains(want);
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 10 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= 24) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 24; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - Movie lists (the Movie wallpaper: For You's four TMDB movie lists, movies only)

    /** TMDB's lists, in the order of the categories: Popular, Now Playing, Upcoming, Top Rated. */
    static final String[] LISTS = {"popular", "now_playing", "upcoming", "top_rated"};
    private static final int LIST_MAX = 15;
    /** The billboard's size (16:9, covers the 1380 × 860 area on the right) and the focused card's (406 × 228). */
    static final int ART_W = Math.round(Backdrop.W * 0.8f), ART_H = Math.round(Backdrop.H * 0.8f);
    static final int LAND_W = Math.round(Backdrop.W * 0.22f), LAND_H = Math.round(Backdrop.H * 0.22f);
    static final int POSTER_W = Math.round(Backdrop.W * 0.084f), POSTER_H = Math.round(Backdrop.W * 0.126f);
    private final ExecutorService movieIo = Executors.newSingleThreadExecutor();
    private final ExecutorService artIo = Executors.newSingleThreadExecutor();
    private final ExecutorService posterIo = Executors.newSingleThreadExecutor();
    @SuppressWarnings("unchecked")
    private volatile List<Pick>[] movieLists = new List[] {new ArrayList<Pick>(), new ArrayList<Pick>(), new ArrayList<Pick>(), new ArrayList<Pick>()};
    private volatile boolean moviesFetching, moviesLoaded;
    private volatile long moviesAt, moviesFailedAt;
    private final Map<String, Art> arts = new LinkedHashMap<>();
    private final Set<String> artPending = new HashSet<>();
    private final Map<String, Bitmap> smallPosters = new LinkedHashMap<>();
    private final Set<String> smallPending = new HashSet<>();

    /** The list for a category (0 to 3); fetched again when it's six hours old, or the language or the services changed. */
    List<Pick> movies(int list) {
        if (!hasKey(context)) return new ArrayList<>();
        loadMovies();
        refreshMovies(list);
        List<Pick> l = movieLists[Math.max(0, Math.min(3, list))];
        return new ArrayList<>(l);
    }

    private long stampValue, stampAt;

    /** sourceStamp() asks the package manager about every service: at most once a minute (this runs on every notice). */
    private long stamp() {
        long now = System.currentTimeMillis();
        if (stampAt == 0 || now - stampAt > 60_000 || now < stampAt) {
            stampValue = sourceStamp();
            stampAt = now;
        }
        return stampValue;
    }

    private void loadMovies() {
        if (moviesLoaded) return;
        moviesLoaded = true;
        try {
            if (cache.getLong("mStamp", 0) != stamp() || !Ui.language.equals(cache.getString("mLanguage", ""))) return;
            moviesAt = cache.getLong("mAt", 0);
            @SuppressWarnings("unchecked") List<Pick>[] l = new List[4];
            for (int i = 0; i < 4; i++) l[i] = parseMovies(new JSONArray(cache.getString("m" + i, "[]")));
            movieLists = l;
        } catch (Exception ignored) {
        }
    }

    private void refreshMovies(int first) {
        if (moviesFetching) return;
        long now = System.currentTimeMillis();
        long sinceFail = now - moviesFailedAt;
        if (moviesFailedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        boolean stale = moviesAt == 0 || now - moviesAt > LIST_EVERY || now < moviesAt
                || cache.getLong("mStamp", 0) != stamp() || !Ui.language.equals(cache.getString("mLanguage", ""));
        boolean missing = false;
        for (List<Pick> l : movieLists) for (Pick p : l) missing |= !p.detailed;
        if (!stale && !missing) return;
        moviesFetching = true;
        final long stamp = stamp();
        final String language = Ui.language;
        movieIo.execute(() -> {
            try {
                if (stale) {
                    @SuppressWarnings("unchecked") List<Pick>[] l = new List[4];
                    for (int i = 0; i < 4; i++) l[i] = fetchMovieList(LISTS[i]);
                    if (l[0].isEmpty() && l[1].isEmpty()) throw new IOException("TMDB returned no movies");
                    movieLists = l;
                    moviesAt = System.currentTimeMillis();
                    saveMovies(stamp, language);
                    notifyListeners();
                }
                // The details (genre, runtime, rating, services, trailer): the list on screen first, then the others
                for (int n = 0; n < 4; n++) {
                    List<Pick> l = movieLists[(first + n) % 4];
                    boolean changed = false;
                    for (Pick p : l) {
                        if (p.detailed) continue;
                        try {
                            movieDetails(p);
                            changed = true;
                        } catch (Exception e) {
                            p.detailed = true;   // not again until the list is fetched again
                            changed = true;
                            AppLog.w("movie " + p.id + ": " + e.getMessage());
                        }
                    }
                    if (changed) {
                        saveMovies(stamp, language);
                        notifyListeners();
                    }
                }
                moviesFailedAt = 0;
            } catch (Exception e) {
                moviesFailedAt = System.currentTimeMillis();
                AppLog.w("movies: " + e.getMessage());
            } finally {
                moviesFetching = false;
            }
        });
    }

    private List<Pick> fetchMovieList(String list) throws Exception {
        JSONArray results = get("/movie/" + list + "?language=" + lang() + "&region=" + REGION + "&page=1").getJSONArray("results");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < results.length() && out.size() < LIST_MAX; i++) {
            JSONObject o = results.getJSONObject(i);
            if (o.optBoolean("adult")) continue;
            int before = out.size();
            add(out, o, "movie");
            if (out.size() == before) continue;
            Pick p = out.get(out.size() - 1);
            p.story = o.optString("overview", "");
            String date = o.optString("release_date", "");
            if (date.length() >= 4) p.year = date.substring(0, 4);
            p.release = parseDay(date);
        }
        return out;
    }

    /** "2026-11-12" → that day at noon, local time (0 when unknown). */
    private static long parseDay(String date) {
        try {
            if (date == null || date.length() < 10) return 0;
            Calendar cal = Calendar.getInstance();
            cal.clear();
            cal.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1, Integer.parseInt(date.substring(8, 10)), 12, 0);
            return cal.getTimeInMillis();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Genre · runtime, the age rating, the service it's on (among the TV's apps) and its YouTube trailer. */
    private void movieDetails(Pick p) throws Exception {
        String video = Ui.language.equals("en") ? "en" : Ui.language + ",en";
        JSONObject o = get("/movie/" + p.id + "?language=" + lang() + "&append_to_response=watch/providers,videos,release_dates"
                + "&include_video_language=" + video);
        String title = o.optString("title", "");
        if (!title.isEmpty()) p.title = title;
        if (p.story.isEmpty()) p.story = o.optString("overview", "");
        if (p.story.isEmpty() && !Ui.en) {
            try {
                p.story = get("/movie/" + p.id + "?language=en-US").optString("overview", "");
            } catch (Exception ignored) {
            }
        }
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        int minutes = o.optInt("runtime");
        if (minutes > 0) parts.add(runtime(minutes));
        p.info = String.join(" · ", parts);
        // The age rating where you live (else the US one); for Upcoming, the release day there
        JSONArray countries = o.optJSONObject("release_dates") == null ? null : o.optJSONObject("release_dates").optJSONArray("results");
        String us = "";
        for (int i = 0; countries != null && i < countries.length(); i++) {
            JSONObject country = countries.getJSONObject(i);
            JSONArray dates = country.optJSONArray("release_dates");
            String cert = "";
            long theatrical = 0;
            for (int j = 0; dates != null && j < dates.length(); j++) {
                JSONObject d = dates.getJSONObject(j);
                if (cert.isEmpty()) cert = d.optString("certification", "").trim();
                int type = d.optInt("type");
                if ((type == 2 || type == 3) && theatrical == 0) theatrical = parseDay(d.optString("release_date", ""));
            }
            String code = country.optString("iso_3166_1");
            if (code.equals(REGION)) {
                p.rating = cert;
                if (theatrical != 0) p.release = theatrical;
            }
            if (code.equals("US")) us = cert;
        }
        if (p.rating.isEmpty()) p.rating = us;
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject here = wp == null || wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = here == null ? null : here.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        // The trailer: an official YouTube trailer in the TV's language, else in English, else a teaser
        JSONArray videos = o.optJSONObject("videos") == null ? null : o.optJSONObject("videos").optJSONArray("results");
        int best = -1;
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            if (!"YouTube".equals(v.optString("site"))) continue;
            String type = v.optString("type");
            int score = (type.equals("Trailer") ? 8 : type.equals("Teaser") ? 4 : 0) + (v.optBoolean("official") ? 2 : 0)
                    + (Ui.language.equals(v.optString("iso_639_1")) ? 1 : 0);
            if (score < 4) continue;
            if (score > best) {
                best = score;
                p.trailer = v.optString("key");
            }
        }
        p.detailed = true;
    }

    private static String runtime(int minutes) {
        if (Ui.ru) return minutes >= 60 ? (minutes / 60) + " ч " + (minutes % 60) + " мин" : minutes + " мин";
        if (Ui.indo) return minutes >= 60 ? (minutes / 60) + " j " + (minutes % 60) + " mnt" : minutes + " mnt";
        if (Ui.en) return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    private void saveMovies(long stamp, String language) {
        try {
            SharedPreferences.Editor e = cache.edit().putLong("mAt", moviesAt).putLong("mStamp", stamp).putString("mLanguage", language);
            for (int i = 0; i < 4; i++) {
                JSONArray a = new JSONArray();
                for (Pick p : movieLists[i]) {
                    a.put(new JSONObject().put("id", p.id).put("title", p.title).put("backdrop", p.backdrop)
                            .put("poster", p.poster == null ? "" : p.poster).put("story", p.story).put("year", p.year)
                            .put("rating", p.rating).put("info", p.info).put("trailer", p.trailer == null ? "" : p.trailer)
                            .put("release", p.release).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                            .put("detailed", p.detailed));
                }
                e.putString("m" + i, a.toString());
            }
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parseMovies(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = "movie";
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.story = o.optString("story");
            p.year = o.optString("year");
            p.rating = o.optString("rating");
            p.info = o.optString("info");
            p.trailer = o.optString("trailer").isEmpty() ? null : o.optString("trailer");
            p.release = o.optLong("release");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }

    /** The billboard's artwork for the pick (or null: loads it in the background and notifies when it's ready). */
    Art art(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;
        synchronized (arts) {
            Art a = arts.get(key);
            if (a != null) {
                arts.remove(key);   // most recently used last
                arts.put(key, a);
                return a;
            }
            if (!artPending.add(key)) return null;
        }
        artIo.execute(() -> {
            try {
                loadArt(key);
            } finally {
                synchronized (arts) { artPending.remove(key); }
            }
        });
        return null;
    }

    /** Whether the art is ready (without loading it). */
    boolean hasArt(Pick p) {
        if (p == null || p.backdrop == null) return false;
        synchronized (arts) { return arts.containsKey(p.backdrop); }
    }

    private void loadArt(String path) {
        File f = new File(new File(context.getCacheDir(), "movie-art"), path.replace("/", ""));
        try {
            fetchFile("https://image.tmdb.org/t/p/w1280" + path, f, 40);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= ART_W) opts.inSampleSize *= 2;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
            if (src == null) throw new IOException("unreadable image");
            Bitmap full = Bitmap.createBitmap(ART_W, ART_H, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(full);
            float scale = Math.max((float) ART_W / src.getWidth(), (float) ART_H / src.getHeight());
            float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
            c.drawBitmap(src, null, new android.graphics.RectF((ART_W - sw) / 2, (ART_H - sh) / 2, (ART_W + sw) / 2, (ART_H + sh) / 2),
                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.DITHER_FLAG));
            src.recycle();
            Bitmap land = Backdrop.card(full, LAND_W, LAND_H);
            Bitmap small = Bitmap.createScaledBitmap(full, Math.max(1, ART_W / 4), Math.max(1, ART_H / 4), true);
            Art art = new Art(Backdrop.gpu(full), land, small);
            synchronized (arts) {
                arts.put(path, art);
                while (arts.size() > 4) arts.remove(arts.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("movie art: " + e.getMessage());
        }
    }

    /** The pick's poster at the card's size (or null: loads it in the background and notifies when it's ready). */
    Bitmap smallPoster(Pick p) {
        if (p == null || p.poster == null) return null;
        final String key = p.poster;
        synchronized (smallPosters) {
            Bitmap b = smallPosters.get(key);
            if (b != null) return b;
            if (!smallPending.add(key)) return null;
        }
        posterIo.execute(() -> {
            File f = new File(new File(context.getCacheDir(), "movie-posters"), key.replace("/", ""));
            try {
                fetchFile("https://image.tmdb.org/t/p/w342" + key, f, 80);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                while (bounds.outWidth / (opts.inSampleSize * 2) >= POSTER_W && bounds.outHeight / (opts.inSampleSize * 2) >= POSTER_H) opts.inSampleSize *= 2;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
                if (b == null) throw new IOException("unreadable poster");
                Bitmap card = Backdrop.card(b, POSTER_W, POSTER_H);
                if (card != b) b.recycle();
                synchronized (smallPosters) {
                    smallPosters.put(key, card);
                    while (smallPosters.size() > 64) smallPosters.remove(smallPosters.keySet().iterator().next());
                }
                notifyListeners();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                AppLog.w("movie poster: " + e.getMessage());
            } finally {
                synchronized (smallPosters) { smallPending.remove(key); }
            }
        });
        return null;
    }

    /** Downloads `url` to `f` once (a folder keeps its newest `keep` files). */
    private static void fetchFile(String url, File f, int keep) throws IOException {
        if (f.exists()) return;
        File dir = f.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save " + f.getName());
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files != null && files.length > keep) {
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            //noinspection ResultOfMethodCallIgnored
            for (int i = 0; i < files.length - keep; i++) files[i].delete();
        }
    }

    /** Opens the film's YouTube trailer (the TV's YouTube app when it has one). */
    static boolean openTrailer(android.app.Activity a, Pick p) {
        if (p == null || p.trailer == null) return false;
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.youtube.com/watch?v=" + p.trailer));
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        for (String pkg : new String[] {"com.google.android.youtube.tv", "com.google.android.youtube.tvkids", "com.google.android.youtube"}) {
            if (HomeApps.launchIntent(a.getPackageManager(), pkg) == null) continue;
            i.setPackage(pkg);
            try {
                a.startActivity(i);
                return true;
            } catch (RuntimeException ignored) {
                i.setPackage(null);
            }
        }
        try {
            a.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("poster", p.poster == null ? "" : p.poster).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if (k.startsWith("wm:") && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
