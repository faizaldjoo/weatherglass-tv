package io.freemytv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV's control page on the home network: http://<the TV's address>:8765, opened on a phone or a laptop on the
 * same Wi-Fi. It shows what the TV is doing (memory, storage, uptime, the app in front) and works the TV: the keys of
 * the remote, text, opening apps, freeing memory, clearing caches, a sleep timer, a reboot.
 *
 * It runs while the home screen's process lives (Android keeps the launcher around). Keys and the care actions go
 * through the TV's own ADB, like the mode switch; only the fixed commands below are ever sent, never text from the
 * network as it came (keys are picked from a list, typed text is escaped to a plain character set).
 *
 * A phone pairs once with the four-digit code shown in Settings → About; it then holds a token. The page itself is
 * served to anyone on the network (it shows nothing until paired).
 */
final class Dashboard {
    static final int PORT = 8765;
    private static final String[] KEYS = {"HOME", "BACK", "MENU", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
        "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE", "MEDIA_PLAY_PAUSE", "MEDIA_REWIND", "MEDIA_FAST_FORWARD", "POWER",
        "CHANNEL_UP", "CHANNEL_DOWN", "SLEEP"};
    /** What the page can open by name, besides the shelf's apps. */
    private static final String[][] APPS = {
        {"netflix", "Netflix", "com.netflix.ninja"}, {"youtube", "YouTube", "com.google.android.youtube.tv"},
        {"prime", "Prime Video", "com.amazon.amazonvideo.livingroom"}, {"disney", "Disney+", "com.disney.disneyplus"},
        {"appletv", "Apple TV", "com.apple.atve.androidtv.appletv"}, {"max", "Max", "com.wbd.stream"},
        {"stremio", "Stremio", "com.stremio.one"}, {"spotify", "Spotify", "com.spotify.tv.android"},
    };

    /** What the home screen does for the page (on its own thread): the things that need it in front. */
    interface Host {
        void openInputs();
        void openCamera();
        boolean hasCamera();
    }

    private static Dashboard instance;

    static synchronized void start(Activity a, Host host) {
        if (instance == null) instance = new Dashboard(a.getApplicationContext());
        instance.activity = a;
        instance.host = host;
    }

    static synchronized void stop(Activity a) {
        if (instance != null && instance.activity == a) {
            instance.activity = null;
            instance.host = null;
        }
    }

    /** The address to open, for the About screen ("http://192.168.1.105:8765"), or empty without Wi-Fi. */
    static String address() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface n = all.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                Enumeration<InetAddress> addresses = n.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement();
                    if (a.isSiteLocalAddress() && a.getAddress().length == 4) return "http://" + a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return "";
    }

    /** The pairing code (four digits, made once). */
    static String code(Context c) {
        SharedPreferences sp = c.getSharedPreferences("dashboard", Context.MODE_PRIVATE);
        String code = sp.getString("code", "");
        if (code.length() != 4) {
            code = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
            sp.edit().putString("code", code).apply();
        }
        return code;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ExecutorService adbIo = Executors.newSingleThreadExecutor();   // one ADB conversation at a time
    private volatile Activity activity;
    private volatile Host host;
    private Adb adb;
    private String front = "", frontLabel = "";
    private long frontAt;
    private final Runnable sleep = () -> adbRun("input keyevent KEYCODE_SLEEP");
    private long sleepAt;

    private Dashboard(Context c) {
        context = c;
        prefs = c.getSharedPreferences("dashboard", Context.MODE_PRIVATE);
        Thread t = new Thread(this::serve, "dashboard");
        t.setDaemon(true);
        t.start();
    }

    private void serve() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            server.setReuseAddress(true);
            AppLog.w("dashboard: " + address());
            while (true) {
                Socket s = server.accept();
                io.execute(() -> handle(s));
            }
        } catch (IOException e) {
            AppLog.w("dashboard: " + e.getMessage());
        }
    }

    // MARK: - HTTP (just enough: one request per connection, small bodies)

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String request = r.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], path = parts[1], query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            int length = 0;
            String token = "";
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                if (lower.startsWith("x-token:")) token = line.substring(8).trim();
            }
            char[] body = new char[Math.min(length, 4096)];
            int read = 0;
            while (read < body.length) {
                int n = r.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            String content = new String(body, 0, read);
            OutputStream out = socket.getOutputStream();
            if (path.equals("/") || path.equals("/index.html")) {
                send(out, 200, "text/html; charset=utf-8", page());
            } else if (path.startsWith("/api/")) {
                api(out, method, path.substring(5), query, content, token);
            } else {
                send(out, 404, "text/plain", "not found");
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("dashboard: " + e.getMessage());
        }
    }

    private static void send(OutputStream out, int status, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 404 ? "Not Found" : "Error")
                + "\r\nContent-Type: " + type + "\r\nContent-Length: " + data.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (!k.equals(name)) continue;
            try {
                return URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), "UTF-8");
            } catch (IOException e) {
                return "";
            }
        }
        return "";
    }

    // MARK: - The API

    private void api(OutputStream out, String method, String what, String query, String body, String token) throws IOException {
        JSONObject reply = new JSONObject();
        try {
            if (what.equals("pair")) {
                // The four-digit code from Settings → About; a wrong code waits a second (no quick guessing)
                if (!param(query, "code").equals(code(context))) {
                    SystemClock.sleep(1000);
                    send(out, 401, "application/json", reply.put("error", "wrong code").toString());
                    return;
                }
                byte[] bytes = new byte[18];
                new SecureRandom().nextBytes(bytes);
                String t = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
                Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", Collections.<String>emptySet()));
                tokens.add(t);
                prefs.edit().putStringSet("tokens", tokens).apply();
                send(out, 200, "application/json", reply.put("token", t).toString());
                return;
            }
            if (token.isEmpty() || !prefs.getStringSet("tokens", Collections.<String>emptySet()).contains(token)) {
                send(out, 401, "application/json", reply.put("error", "pair first").toString());
                return;
            }
            switch (what) {
                case "status": send(out, 200, "application/json", status().toString()); return;
                case "key": {
                    String key = param(query, "code");
                    if (!Arrays.asList(KEYS).contains(key)) throw new IOException("unknown key");
                    adbRun("input keyevent KEYCODE_" + key);
                    break;
                }
                case "text": {
                    String text = body.trim();
                    if (text.isEmpty() || text.length() > 200) throw new IOException("no text");
                    adbRun("input text " + shellText(text));
                    break;
                }
                case "open": {
                    String id = param(query, "id");
                    if (!open(id)) throw new IOException("can't open " + id);
                    reply.put("opened", id);
                    break;
                }
                case "care": {
                    String action = param(query, "what");
                    care(action, reply);
                    break;
                }
                default:
                    send(out, 404, "application/json", reply.put("error", "unknown").toString());
                    return;
            }
            send(out, 200, "application/json", reply.put("ok", true).toString());
        } catch (Exception e) {
            try {
                send(out, 500, "application/json", new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()).toString());
            } catch (Exception ignored) {
            }
        }
    }

    /** Typed text for `input text`: letters, digits and plain punctuation only; a space becomes %s as adb wants it. */
    private static String shellText(String text) {
        StringBuilder s = new StringBuilder("'");
        for (char ch : text.toCharArray()) {
            if (ch == ' ') s.append("%s");
            else if (Character.isLetterOrDigit(ch) && ch < 128) s.append(ch);
            else if (".,-_@!?:#+=".indexOf(ch) >= 0) s.append(ch);
            // anything else is dropped: quotes, slashes and the like never reach the shell
        }
        return s.append("'").toString();
    }

    private JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        o.put("memTotal", mi.totalMem).put("memUsed", mi.totalMem - mi.availMem);
        // Our own processes (the home screen, the main one, the key reader), by their real share (PSS)
        long ours = 0;
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs != null) {
            int[] pids = new int[procs.size()];
            for (int i = 0; i < pids.length; i++) pids[i] = procs.get(i).pid;
            Debug.MemoryInfo[] infos = am.getProcessMemoryInfo(pids);
            for (Debug.MemoryInfo info : infos) ours += info.getTotalPss() * 1024L;
        }
        o.put("memOurs", ours);
        StatFs fs = new StatFs(android.os.Environment.getDataDirectory().getPath());
        o.put("diskTotal", fs.getTotalBytes()).put("diskFree", fs.getAvailableBytes());
        o.put("uptime", SystemClock.elapsedRealtime());
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        o.put("on", pm == null || pm.isInteractive());
        o.put("model", (Build.BRAND.substring(0, 1).toUpperCase(Locale.ROOT) + Build.BRAND.substring(1)) + " " + Build.MODEL);
        o.put("android", Build.VERSION.RELEASE);
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                o.put("rssi", wifi.getConnectionInfo().getRssi());
                o.put("ghz", wifi.getConnectionInfo().getFrequency() >= 5000 ? 5 : 2.4);
            }
        } catch (RuntimeException ignored) {
        }
        o.put("front", frontApp());
        o.put("sleepAt", sleepAt);
        // What the page can open: the shelf's apps and the known ones that are installed, Live TV, inputs, camera
        JSONArray apps = new JSONArray();
        PackageManager pm2 = context.getPackageManager();
        Set<String> seen = new HashSet<>();
        for (String[] a : APPS) {
            if (HomeApps.launchIntent(pm2, a[2]) != null && seen.add(a[2])) apps.put(new JSONObject().put("id", a[0]).put("label", a[1]));
        }
        for (HomeApps.Item it : HomeApps.shelf(context, false)) {
            if (it.kind == HomeApps.APP && it.pkg != null && seen.add(it.pkg)) apps.put(new JSONObject().put("id", "pkg:" + it.pkg).put("label", it.label));
        }
        if (HomeApps.liveTv(context) != null) apps.put(new JSONObject().put("id", "live").put("label", Ui.tr("TV aberta", "Live TV")));
        apps.put(new JSONObject().put("id", "inputs").put("label", Ui.tr("Entradas HDMI", "HDMI inputs")));
        Host h = host;
        if (h != null && h.hasCamera()) apps.put(new JSONObject().put("id", "camera").put("label", Ui.tr("Câmera", "CCTV camera")));
        apps.put(new JSONObject().put("id", "settings").put("label", Ui.tr("Configurações da TV", "TV Settings")));
        o.put("apps", apps);
        return o;
    }

    /** The app in front, from the TV's shell (cached 5 s): "Netflix", or "" when it can't tell. */
    private String frontApp() {
        long now = SystemClock.uptimeMillis();
        if (now - frontAt < 5000) return frontLabel;
        frontAt = now;
        try {
            String dump = adbRun("dumpsys activity activities | grep -m1 -E 'ResumedActivity|topResumedActivity'");
            int slash = dump.indexOf('/');
            if (slash > 0) {
                int start = dump.lastIndexOf(' ', slash) + 1;
                String pkg = dump.substring(start, slash);
                if (!pkg.equals(front)) {
                    front = pkg;
                    frontLabel = pkg.equals(context.getPackageName()) ? "Weatherglass TV"
                            : context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg, 0)).toString();
                }
            }
        } catch (Exception e) {
            frontLabel = "";
        }
        return frontLabel;
    }

    private boolean open(String id) throws Exception {
        final Activity a = activity;
        final Host h = host;
        Intent intent = null;
        String pkg = null;
        for (String[] app : APPS) if (app[0].equals(id)) pkg = app[2];
        if (id.startsWith("pkg:")) pkg = id.substring(4);
        if (pkg != null) {
            intent = HomeApps.launchIntent(context.getPackageManager(), pkg);
            if (intent == null) return false;
        } else if (id.equals("live")) {
            intent = HomeApps.liveTv(context);
            if (intent == null) return false;
        } else if (id.equals("settings")) {
            intent = new Intent(Settings.ACTION_SETTINGS);
        } else if (id.equals("inputs") || id.equals("camera")) {
            if (a == null || h == null) return false;
            // The home screen does these itself (its drawer, its camera popup): bring it to the front first
            main.post(() -> {
                Intent home = new Intent(a, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                a.startActivity(home);
                main.postDelayed(() -> { if (id.equals("inputs")) h.openInputs(); else h.openCamera(); }, 600);
            });
            return true;
        } else {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        final Intent go = intent;
        main.post(() -> {
            try {
                context.startActivity(go);
            } catch (RuntimeException e) {
                AppLog.w("dashboard: open " + id + ": " + e.getMessage());
            }
        });
        return true;
    }

    private void care(String action, JSONObject reply) throws Exception {
        switch (action) {
            case "free": {
                // Stops every app in the background (the ones in front and the system's stay)
                int before = runningApps();
                adbRun("am kill-all");
                SystemClock.sleep(500);
                reply.put("stopped", Math.max(0, before - runningApps()));
                break;
            }
            case "cache":
                adbRun("pm trim-caches 100G");   // every app's cache, down to nothing
                break;
            case "home": {
                // The home screen starts again (Android brings the launcher back by itself)
                main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
                break;
            }
            case "sleep30":
            case "sleep60": {
                long minutes = action.equals("sleep30") ? 30 : 60;
                main.removeCallbacks(sleep);
                main.postDelayed(sleep, minutes * 60_000L);
                sleepAt = System.currentTimeMillis() + minutes * 60_000L;
                reply.put("sleepAt", sleepAt);
                break;
            }
            case "sleepOff":
                main.removeCallbacks(sleep);
                sleepAt = 0;
                break;
            case "reboot":
                adbRun("reboot");
                break;
            default:
                throw new IOException("unknown action");
        }
    }

    private int runningApps() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        return procs == null ? 0 : procs.size();
    }

    // MARK: - The TV's shell, one fixed command at a time, over one connection kept open

    private String adbRun(String command) {
        try {
            return adbIo.submit(() -> {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        if (adb == null) adb = Adb.connect(context, 0, null);
                        return adb.shell(command);
                    } catch (IOException e) {
                        if (adb != null) adb.close();
                        adb = null;
                        if (attempt == 1) throw e;
                    }
                }
                return "";
            }).get();
        } catch (Exception e) {
            AppLog.w("dashboard: " + command.split(" ")[0] + ": " + e.getMessage());
            return "";
        }
    }

    // MARK: - The page (assets/dashboard/index.html, its texts in the TV's language)

    private String page() throws IOException {
        StringBuilder s = new StringBuilder();
        try (InputStream in = context.getAssets().open("dashboard/index.html")) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) s.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        }
        String html = s.toString();
        int at = 0;
        StringBuilder out = new StringBuilder();
        while (true) {
            int open = html.indexOf("{{", at);
            if (open < 0) {
                out.append(html, at, html.length());
                break;
            }
            int close = html.indexOf("}}", open);
            out.append(html, at, open);
            String key = html.substring(open + 2, close);
            out.append(text(key));
            at = close + 2;
        }
        return out.toString();
    }

    /** The page's texts, by their English (Indonesian, Spanish, German and Russian come from the language files). */
    private static String text(String english) {
        switch (english) {
            case "lang": return Ui.language;
            case "Remote": return Ui.tr("Controle", "Remote");
            case "Open": return Ui.tr("Abrir", "Open");
            case "Care": return Ui.tr("Cuidados", "Care");
            case "Log": return Ui.tr("Registro", "Log");
            case "Memory": return Ui.tr("Memória", "Memory");
            case "Storage": return Ui.tr("Armazenamento", "Storage");
            case "Up since": return Ui.tr("Ligada há", "Up since");
            case "In front": return Ui.tr("Na frente", "In front");
            case "Type to the TV": return Ui.tr("Digitar na TV", "Type to the TV");
            case "Send": return Ui.tr("Enviar", "Send");
            case "a search, a password": return Ui.tr("uma busca, uma senha", "a search, a password");
            case "when the TV feels slow": return Ui.tr("quando a TV parece lenta", "when the TV feels slow");
            case "Free memory": return Ui.tr("Liberar memória", "Free memory");
            case "stops apps in the background": return Ui.tr("fecha os apps em segundo plano", "stops apps in the background");
            case "Clear caches": return Ui.tr("Limpar caches", "Clear caches");
            case "Restart home screen": return Ui.tr("Reiniciar o Início", "Restart home screen");
            case "Sleep in 30 min": return Ui.tr("Dormir em 30 min", "Sleep in 30 min");
            case "Sleep in 60 min": return Ui.tr("Dormir em 60 min", "Sleep in 60 min");
            case "Reboot TV": return Ui.tr("Reiniciar a TV", "Reboot TV");
            case "about 40 s": return Ui.tr("uns 40 s", "about 40 s");
            case "Reboot the TV?": return Ui.tr("Reiniciar a TV?", "Reboot the TV?");
            case "It turns off and comes back in about 40 seconds, into the home screen. Whatever is playing stops.":
                return Ui.tr("Ela desliga e volta em uns 40 segundos, no Início. O que estiver tocando para.",
                        "It turns off and comes back in about 40 seconds, into the home screen. Whatever is playing stops.");
            case "Cancel": return Ui.tr("Cancelar", "Cancel");
            case "Reboot": return Ui.tr("Reiniciar", "Reboot");
            case "Haptic feedback": return Ui.tr("Vibração ao tocar", "Haptic feedback");
            case "not on this device": return Ui.tr("não neste aparelho", "not on this device");
            case "Pair this phone": return Ui.tr("Parear este aparelho", "Pair this phone");
            case "Enter the four-digit code from the TV's Settings → About.": return Ui.tr("Digite o código de quatro dígitos de Configurações → Sobre na TV.", "Enter the four-digit code from the TV's Settings → About.");
            case "Pair": return Ui.tr("Parear", "Pair");
            case "Wrong code": return Ui.tr("Código errado", "Wrong code");
            case "No connection to the TV": return Ui.tr("Sem conexão com a TV", "No connection to the TV");
            case "Inputs": return Ui.tr("Entradas", "Inputs");
            case "used": return Ui.tr("usados", "used");
            case "free": return Ui.tr("livres", "free");
            case "of": return Ui.tr("de", "of");
            case "Weatherglass": return "Weatherglass";
            default: return Ui.tr(english, english);
        }
    }
}
