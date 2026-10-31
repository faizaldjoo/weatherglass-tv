package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional crypto prices (Binance, every 5 min) and weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Neither source needs a key. Location and currency come from config.properties.
 */
final class HomeData {
    /**
     * Exchange rates instead of crypto (config.properties "rates", e.g. USD,AUD): up to four currencies priced in
     * "currency" (e.g. IDR), daily reference rates from frankfurter.dev with the change from the day before. They take
     * the coins' places everywhere (the row on the home screen, the drawer card, the screensaver, the settings).
     * Picked on the TV too (Prefs.rates), so they can change while the app runs: loadRates() reads them again.
     */
    private static final String[] CRYPTO = {"BTC", "ETH", "SOL", "USDT"};
    static volatile String[] RATE_CODES = {};
    static volatile boolean RATES;
    static volatile String[] COINS = CRYPTO;
    static final String[] COIN_NAMES = {"Bitcoin", "Ethereum", "Solana", "Dollar"};
    private static final String CURRENCY = Config.CURRENCY.trim().toUpperCase(Locale.ROOT);
    private static String ratesLoaded;

    static { loadRates(); }

    /**
     * Reads the rates picked on the TV again: the settings call it when they change, the screensaver when it starts
     * (on Android 11 it runs in another process). A change drops the old prices and the old currency labels.
     */
    static synchronized void loadRates() {
        String v = Prefs.rates();
        if (v.equals(ratesLoaded)) return;
        ratesLoaded = v;
        String[] codes = rateCodes(v);
        RATE_CODES = codes;
        RATES = codes.length > 0;
        COINS = RATES ? padded(codes) : CRYPTO;
        activeCoins = null;
        Glyphs.forgetLabels();
        HomeData data = instance;
        if (data != null) data.pricesChanged();
    }

    private static String[] rateCodes(String rates) {
        List<String> out = new ArrayList<>();
        for (String code : rates.split(",")) {
            code = code.trim().toUpperCase(Locale.ROOT);
            if (code.matches("[A-Z]{3}") && out.size() < 4 && !out.contains(code)) out.add(code);
        }
        return out.toArray(new String[0]);
    }

    private static String[] padded(String[] codes) {
        String[] out = {"", "", "", ""};
        System.arraycopy(codes, 0, out, 0, codes.length);
        return out;
    }

    /** How often the prices are fetched: every 5 min (crypto) or every hour (reference rates change once a day). */
    static long pricesEvery() {
        return RATES ? 60 * 60_000L : PRICES_EVERY;
    }

    private static String rateName(String code) {
        switch (code) {
            case "USD": return "US Dollar";
            case "AUD": return "Australian Dollar";
            case "EUR": return "Euro";
            case "GBP": return "British Pound";
            case "SGD": return "Singapore Dollar";
            case "MYR": return "Ringgit";
            case "JPY": return "Japanese Yen";
            case "CNY": return "Yuan";
            case "SAR": return "Saudi Riyal";
            default: return code;
        }
    }
    /** Binance has no pairs in some currencies (INR...) and then refuses the whole request: prices go in dollars instead. */
    private static volatile boolean inDollars;

    /** Binance quotes dollars as USDT. */
    private static String quote() {
        return CURRENCY.equals("USD") || inDollars ? "USDT" : CURRENCY;
    }
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day,precipitation&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day,precipitation&timezone=auto&forecast_days=2";

    /**
     * The model's "drizzle" with (almost) nothing falling reads as what it looks like outside: cloudy. Open-Meteo often
     * says drizzle under a grey tropical sky that other weather apps (and the window) call cloudy.
     */
    private static int seen(int code, double precipitationMm) {
        return code >= 51 && code <= 57 && precipitationMm < 0.1 ? 3 : code;
    }

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + WEATHER_QUERY;
    }
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 30 * 60_000L;
    interface Listener { void onData(); }

    /** BTC, ETH and SOL; in Brazilian reais also the dollar (USDT/BRL), which Binance doesn't list for most currencies. */
    static int coinCount() {
        return Prefs.quotes() ? active().length : 0;
    }

    private static volatile int[] activeCoins;
    private static volatile int activeVersion = -1;

    /** Indexes in COINS of the coins the user has on (and that Binance lists in this currency). */
    static int[] active() {
        int[] a = activeCoins;
        if (a != null && activeVersion == Prefs.version) return a;
        int n = 0;
        int[] tmp = new int[COINS.length];
        for (int i = 0; i < COINS.length; i++) {
            boolean listed = RATES ? i < RATE_CODES.length : i < 3 || CURRENCY.equals("BRL");
            if (Prefs.coin(i) && listed) tmp[n++] = i;
        }
        a = java.util.Arrays.copyOf(tmp, n);
        activeCoins = a;
        activeVersion = Prefs.version;
        return a;
    }

    /** Settings changed (city, coins): fetch again now. */
    void settingsChanged() {
        pricesAt = 0;
        weatherAt = 0;
        pricesFailedAt = 0;
        weatherFailedAt = 0;
    }

    /** Other currencies: the old numbers mean nothing now, fetch the new ones. */
    private void pricesChanged() {
        java.util.Arrays.fill(prices, Double.NaN);
        java.util.Arrays.fill(changes, Double.NaN);
        pricesAt = 0;
        pricesFailedAt = 0;
    }

    static String coinName(int i) {
        if (RATES) return rateName(COINS[i]);
        return i == 3 ? Ui.tr("Dólar", "Dollar") : COIN_NAMES[i];
    }

    /** Binance.com refuses the US (HTTP 451); Binance.US answers the same call in dollars. */
    private static final String[] PRICE_HOSTS = {"https://api.binance.com", "https://api.binance.us"};

    private static String pricesPath() {
        StringBuilder symbols = new StringBuilder();
        int[] coins = active();
        for (int i = 0; i < coins.length; i++) symbols.append(i == 0 ? "" : ",").append("%22").append(COINS[coins[i]]).append(quote()).append("%22");
        return "/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D";
    }

    private static String getPrices() throws IOException {
        IOException failed = null;
        for (String host : PRICE_HOSTS) {
            try {
                return get(host + pricesPath());
            } catch (IOException e) {
                failed = e;
                // A pair Binance doesn't list (HTTP 400) isn't a refusal: the caller falls back to dollars instead.
                if ("HTTP 400".equals(e.getMessage())) break;
            }
        }
        throw failed;
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    /** 24 h change (%), in the same order. */
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;
    private volatile long pricesFailedAt, weatherFailedAt;

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        inDollars = !RATES && CURRENCY.equals(cache.getString("dollarsFor", ""));
        try {
            // The cached prices are for the coins or rates they were fetched for; anything else means fetch again.
            if (Prefs.quotes() && pricesKey().equals(cache.getString("pricesKey", ""))) {
                String p = cache.getString("prices", null);
                if (p != null) {
                    if (RATES) parseRates(p);
                    else parsePrices(p);
                }
                pricesAt = cache.getLong("pricesAt", 0);
            }
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                weatherAt = cache.getLong("weatherAt", 0);
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > pricesEvery() || now < pricesAt) && !failedRecently(pricesFailedAt, now)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body;
                    if (RATES) {
                        body = getRates();
                        parseRates(body);
                    } else try {
                        body = getPrices();
                    } catch (IOException e) {
                        if (inDollars || CURRENCY.equals("USD") || !"HTTP 400".equals(e.getMessage())) throw e;
                        inDollars = true;
                        cache.edit().putString("dollarsFor", CURRENCY).apply();
                        AppLog.w("prices: Binance has no " + CURRENCY + " pairs, using dollars");
                        body = getPrices();
                    }
                    if (!RATES) parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).putString("pricesKey", pricesKey()).apply();
                    notifyListeners();
                } catch (Exception e) {
                    pricesFailedAt = System.currentTimeMillis();
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String body = get(weatherUrl());
                    weather = parseWeather(body);
                    weatherAt = System.currentTimeMillis();
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", weatherPlace()).apply();
                    notifyListeners();
                } catch (Exception e) {
                    weatherFailedAt = System.currentTimeMillis();
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 2 * 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private void parsePrices(String body) throws Exception {
        JSONArray list = new JSONArray(body);
        String quote = quote();
        int found = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            String symbol = o.getString("symbol");
            for (int k = 0; k < COINS.length; k++) {
                if (!symbol.equals(COINS[k] + quote)) continue;
                found++;
                double last = Double.parseDouble(o.optString("lastPrice", o.optString("price", "NaN")));
                double open = Double.parseDouble(o.optString("openPrice", "NaN"));
                prices[k] = last;
                changes[k] = open > 0 ? (last - open) / open * 100 : Double.NaN;
            }
        }
        if (found == 0) pricesAt = 0;   // cached in another currency (config changed): fetch again
    }

    private static String pricesKey() {
        return RATES ? "rates:" + String.join(",", RATE_CODES) + ">" + CURRENCY : "coins>" + CURRENCY;
    }

    /**
     * The last ten days of each rate (frankfurter.dev, ECB reference rates; no key), as one JSON object by currency.
     * Ten days so weekends and holidays (no new rate) still leave two days to compare.
     */
    private static String getRates() throws Exception {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String from = day.format(new java.util.Date(System.currentTimeMillis() - 10 * 24 * 60 * 60_000L));
        JSONObject all = new JSONObject();
        for (String code : RATE_CODES) {
            if (code.equals(CURRENCY)) continue;
            all.put(code, new JSONObject(get("https://api.frankfurter.dev/v1/" + from + "..?base=" + code + "&symbols=" + CURRENCY)));
        }
        return all.toString();
    }

    /** Latest rate and the change from the previous day with a rate. */
    private void parseRates(String body) throws Exception {
        JSONObject all = new JSONObject(body);
        for (int k = 0; k < RATE_CODES.length; k++) {
            JSONObject o = all.optJSONObject(RATE_CODES[k]);
            JSONObject rates = o == null ? null : o.optJSONObject("rates");
            if (rates == null) continue;
            List<String> days = new ArrayList<>();
            java.util.Iterator<String> keys = rates.keys();
            while (keys.hasNext()) days.add(keys.next());
            java.util.Collections.sort(days);
            if (days.isEmpty()) continue;
            double last = rates.getJSONObject(days.get(days.size() - 1)).optDouble(CURRENCY, Double.NaN);
            double before = days.size() < 2 ? Double.NaN : rates.getJSONObject(days.get(days.size() - 2)).optDouble(CURRENCY, Double.NaN);
            prices[k] = last;
            changes[k] = before > 0 ? (last - before) / before * 100 : Double.NaN;
        }
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = seen(current.getInt("weather_code"), current.optDouble("precipitation", 0));
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            JSONArray rain = hourly.optJSONArray("precipitation");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(seen(codes.getInt(i), rain == null ? 1 : rain.optDouble(i, 1)));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static List<String[]> findCity(String name) throws Exception {
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + Ui.language
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        if (RATES) return grouped(v);
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        return String.format(locale(), "%.2f", v);
    }

    /** 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (RATES) return grouped(v);
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        if (v >= 100) return String.format(locale(), "%.0f", v);
        return String.format(locale(), "%.2f", v);
    }

    /**
     * $108,234 · $185.20 · R$ 581.234 · 74 264 € (cents only below a thousand), in the configured currency, with the
     * symbol and spaces where the language puts them.
     */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        if (RATES) return symbol + grouped(v);
        return Ui.once("brl", v, () -> {
            DecimalFormat f = (DecimalFormat) NumberFormat.getCurrencyInstance(locale());
            DecimalFormatSymbols s = f.getDecimalFormatSymbols();
            s.setCurrencySymbol(symbol.trim());   // our symbols: ICU would write "US$" outside the US
            f.setDecimalFormatSymbols(s);
            f.setMinimumFractionDigits(v >= 1000 ? 0 : 2);
            f.setMaximumFractionDigits(v >= 1000 ? 0 : 2);
            return f.format(v);
        });
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (Ui.indo) {
            if (code == 0) return "Cerah";
            if (code == 1) return "Cerah berawan";
            if (code == 2) return "Berawan sebagian";
            if (code == 3) return "Berawan";
            if (code == 45 || code == 48) return "Kabut";
            if (code >= 51 && code <= 57) return "Gerimis";
            if (code == 65 || code == 67 || code == 82) return "Hujan lebat";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Salju";
            if (code >= 95) return "Badai petir";
            return "Hujan";
        }
        if (Ui.en) {
            if (code == 0) return "Clear";
            if (code == 1) return day ? "Mostly Sunny" : "Mostly Clear";
            if (code == 2) return "Partly Cloudy";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code >= 51 && code <= 57) return "Drizzle";
            if (code == 65 || code == 67 || code == 82) return "Heavy Rain";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
            if (code >= 95) return "Thunderstorms";
            return "Rain";
        }
        if (Ui.ru) {
            if (code == 0) return "Ясно";
            if (code == 1) return "Малооблачно";
            if (code == 2) return "Переменная облачность";
            if (code == 3) return "Облачно";
            if (code == 45 || code == 48) return "Туман";
            if (code >= 51 && code <= 57) return "Морось";
            if (code == 65 || code == 67 || code == 82) return "Сильный дождь";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Снег";
            if (code >= 95) return "Гроза";
            return "Дождь";
        }
        if (Ui.es) {
            if (code == 0) return day ? "Despejado" : "Noche despejada";
            if (code == 1) return "Mayormente despejado";
            if (code == 2) return "Parcialmente nublado";
            if (code == 3) return "Nublado";
            if (code == 45 || code == 48) return "Niebla";
            if (code >= 51 && code <= 57) return "Llovizna";
            if (code == 65 || code == 67 || code == 82) return "Lluvia fuerte";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Nieve";
            if (code >= 95) return "Tormenta";
            return "Lluvia";
        }
        if (Ui.de) {
            if (code == 0) return day ? "Sonnig" : "Klar";
            if (code == 1) return day ? "Überwiegend sonnig" : "Überwiegend klar";
            if (code == 2) return "Teilweise bewölkt";
            if (code == 3) return "Bewölkt";
            if (code == 45 || code == 48) return "Nebel";
            if (code >= 51 && code <= 57) return "Nieselregen";
            if (code == 65 || code == 67 || code == 82) return "Starker Regen";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Schnee";
            if (code >= 95) return "Gewitter";
            return "Regen";
        }
        if (code == 0) return day ? "Céu limpo" : "Noite limpa";
        if (code == 1) return "Poucas nuvens";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code == 65 || code == 67 || code == 82) return "Chuva forte";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neve";
        if (code >= 95) return "Tempestade";
        return "Chuva";
    }

    /** A rate the way it's written in its currency: 18.043 in rupiah (dots), 1.42 below a hundred. */
    private static String grouped(double v) {
        return Ui.once("grouped", v, () -> {
            Locale where = CURRENCY.equals("IDR") ? new Locale("in", "ID") : locale();
            return String.format(where, v >= 100 ? "%,.0f" : "%,.2f", v);
        });
    }

    private static String currencySymbol() {
        switch (inDollars ? "USD" : CURRENCY) {
            case "IDR": return "Rp ";
            case "USD": return "$";
            case "BRL": return "R$ ";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            default: return CURRENCY + " ";
        }
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign); "+1,2 %" in the languages that space the percent sign. */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return Ui.once("change", v, () -> {
            NumberFormat f = NumberFormat.getPercentInstance(locale());
            f.setMinimumFractionDigits(1);
            f.setMaximumFractionDigits(1);
            return (v >= 0 ? "+" : "−") + f.format(Math.abs(v) / 100);
        });
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> Ui.en ? new SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(at))
                : new SimpleDateFormat(Ui.es || Ui.ru ? "HH:mm" : Ui.de ? "HH 'Uhr'" : "HH'h'", Ui.locale()).format(new java.util.Date(at)));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        if (Ui.indo) return minutes < 60 ? minutes + " menit lalu" : (minutes / 60) + " jam lalu";
        if (Ui.en) return minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
        if (Ui.ru) return minutes < 60 ? minutes + " мин назад" : (minutes / 60) + " ч назад";
        if (Ui.es) return minutes < 60 ? "hace " + minutes + " min" : "hace " + (minutes / 60) + " h";
        if (Ui.de) return minutes < 60 ? "vor " + minutes + " Min." : "vor " + (minutes / 60) + " Std.";
        return minutes < 60 ? "há " + minutes + " min" : "há " + (minutes / 60) + " h";
    }
}
