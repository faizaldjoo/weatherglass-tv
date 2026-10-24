package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Weatherglass TV's settings (the app's icon, "All Apps", or the gear on the home screen): a drawer that slides in
 * from the right over the home screen, which stays where it is behind it. The drawer has two levels: the sections
 * (Home Screen, Screensaver, Appearance, Wallpaper, Widgets, Shelf, Remote Buttons, About, TV Settings), and the
 * section itself; Back goes up a level, and closes the drawer from the top. The focused row is white (the Apple TV
 * focus), as on the home screen. "Home Screen" holds the Weatherglass TV / Original choice; the switch runs in the
 * main process (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    // The frosted glass: the home screen as it was when the gear was pressed, blurred (same process: HomeView sets it),
    // and whether it was night there (dark glass) or day (grey glass).
    static final int FROST_W = 240, FROST_H = 135;
    private static volatile Bitmap frost;
    private static volatile Boolean homeNight;

    /** From the home screen, before it opens the settings: its picture (any size), blurred here, and its look. */
    static void setBackdrop(Bitmap screen, boolean night) {
        int[] px = Backdrop.reduce(screen, FROST_W, FROST_H, 3, 3);
        saturate(px);
        Bitmap b = Bitmap.createBitmap(FROST_W, FROST_H, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, FROST_W, 0, 0, FROST_W, FROST_H);
        frost = b;
        homeNight = night;
    }

    /** A touch more color, as frosted glass has (the blur alone greys it). The home screen's frosted glass uses it too. */
    static void saturate(int[] px) {
        for (int i = 0; i < px.length; i++) {
            int r = (px[i] >> 16) & 255, g = (px[i] >> 8) & 255, b = px[i] & 255;
            float y = 0.299f * r + 0.587f * g + 0.114f * b;
            r = Math.max(0, Math.min(255, Math.round(y + (r - y) * 1.35f)));
            g = Math.max(0, Math.min(255, Math.round(y + (g - y) * 1.35f)));
            b = Math.max(0, Math.min(255, Math.round(y + (b - y) * 1.35f)));
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        view.reopen();
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        view.release();
        frost = null;      // the next opening brings its own (or none: plain glass)
        homeNight = null;
        super.onDestroy();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);   // the drawer already slid out
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, ABOUT = 6, CARE = 7;
        // The sections, in the drawer's order (MAIN's rows).
        private static final int S_HOME = 0, S_SCREENSAVER = 1, S_APPEARANCE = 2, S_WALLPAPER = 3, S_WIDGETS = 4,
                S_SHELF = 5, S_REMOTE = 6, S_CARE = 7, S_ABOUT = 8, S_TV = 9, SECTIONS = 10;
        // TV Status: the care rows (the dashboard's), and where they start, under the status block
        private static final String[] CARE_ACTIONS = {"free", "cache", "home", "reboot"};
        private static final float CARE_TOP = 164 + 300;   // TOP + the status block
        private final String[] careText = new String[CARE_ACTIONS.length];   // each row's last result
        private boolean careBusy, rebootArmed;
        private Dashboard.Stats stats;
        private long statsAt;
        private static final int PICK_VISIBLE = 9;
        // The drawer, on the 1920 × 1080 grid: its glass, and the content inside it.
        private static final float DX = 1184, DR = 1884, DT = 36, DB = 1044, L = DX + 26, R = DR - 26, TOP = 164;
        private static final float MAIN_H = 70, LIST_H = 72, CHOICE_H = 100, GAP = 2, DIVIDER = 18, HEADER = 44, INSET = 24;
        private static final int AMBER = 0xFFFFC266, GREEN = 0xFF30B85A, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[24];   // one per row
        private final Spring open = new Spring(0.3f, 1f, 0);   // the drawer: 0 off screen, 1 in place
        private boolean closing;
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button, or a city): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private String switchHint = "";   // what to do before switching (debugging off), shown under the sections
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF(), panel = new RectF();
        private final Path panelPath = new Path();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, section = S_APPEARANCE, focus;
        private boolean running, okDown, backDown, wasBusy;
        private final Spoken spoken;
        private final Click click;   // the home screen's soft clicks (off with "Navigation sounds")
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1, slide;
        private Paint heading, small, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow,
                fill, stroke, scrim, border, sheen, frostPaint;
        private final Shadows shadows = new Shadows();
        private BitmapShader frostShader;
        private Bitmap frostShaderOf;
        private Shader sheenDay, sheenNight;
        private final java.util.HashMap<String, StaticLayout> layouts = new java.util.HashMap<>();
        private TextPaint notice, hint;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            click = new Click(a);
            setFocusable(true);
            setContentDescription("Weatherglass TV");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        // MARK: - The sections

        private String sectionName(int s) {
            switch (s) {
                case S_HOME: return Ui.tr("Início", "Home Screen");
                case S_SCREENSAVER: return Ui.tr("Tela de Descanso", "Screensaver");
                case S_APPEARANCE: return Ui.tr("Aparência", "Appearance");
                case S_WALLPAPER: return Ui.tr("Tema", "Theme");
                case S_WIDGETS: return Ui.tr("Widgets", "Widgets");
                case S_SHELF: return Ui.tr("Prateleira", "Shelf");
                case S_REMOTE: return Ui.tr("Botões do Controle", "Remote Buttons");
                case S_CARE: return Ui.tr("Estado da TV", "TV Status");
                case S_ABOUT: return Ui.tr("Sobre", "About");
                default: return Ui.tr("Ajustes da TV", "TV Settings");
            }
        }

        private static final String[] ICONS = {"home", "bedtime", "sparkles", "partly_cloudy_day", "apps", "live_tv", "gamepad", "tv", null, "tv_settings"};

        /** A section's value on the first level (what it is set to now), or "". */
        private String sectionValue(int s) {
            if (s == S_CARE) {
                Dashboard.Stats st = stats();
                return Math.round(st.memUsed * 100f / Math.max(1, st.memTotal)) + "% " + Ui.tr("de memória", "memory");
            }
            String[] v = settingValues();
            switch (s) {
                case S_HOME: return freeActive() ? "Weatherglass TV" : Ui.tr("Original", "Original");
                case S_SCREENSAVER: return Ui.tr("Ver agora", "Preview");
                case S_APPEARANCE: return v[2];
                case S_WALLPAPER: return v[WALLPAPER_ROW];
                default: return "";
            }
        }

        /** Appearance, Wallpaper and Widgets: which Home Settings rows each one shows, in its order. */
        private int[] sectionRows() {
            // The Movie wallpaper's glass is always transparent (frosting its artwork is too much for a small TV)
            if (section == S_APPEARANCE) return Prefs.wallpaper().equals("photos") ? new int[] {NAME_ROW, 0, 2, TEXT_ROW, 1, 3}
                    : new int[] {NAME_ROW, 0, 2, TEXT_ROW, GLASS_ROW, 1, 3};
            // Movie (the For You billboard) has no sky to preview: only its motion (the artwork's slow breathing)
            if (section == S_WALLPAPER) return Prefs.wallpaper().equals("photos") ? new int[] {WALLPAPER_ROW, MOTION_ROW}
                    : new int[] {WALLPAPER_ROW, MOTION_ROW, SKY_WEATHER_ROW, SKY_TIME_ROW};
            int coins = settingLabels().length - COIN_ROWS;
            int[] r = new int[5 + coins];
            r[0] = 4;
            r[1] = CITY_ROW;
            r[2] = QUOTES_ROW;
            for (int i = 0; i < coins; i++) r[3 + i] = COIN_ROWS + i;
            r[3 + coins] = CAMERA_ROW;
            r[4 + coins] = CAMERA_URL_ROW;
            return r;
        }

        /** A small title above a section's row (null: none). */
        private String groupTitle(int local) {
            if (section == S_WALLPAPER && local == 2) return Ui.tr("PRÉVIA DO CÉU", "SKY PREVIEW");
            if (section == S_WIDGETS && local == 0) return Ui.tr("CLIMA", "WEATHER");
            if (section == S_WIDGETS && local == 2) return HomeData.RATES ? Ui.tr("CÂMBIO", "EXCHANGE RATES") : Ui.tr("COTAÇÕES", "QUOTES");
            if (section == S_WIDGETS && local == 3 + settingLabels().length - COIN_ROWS) return Ui.tr("CÂMERA", "CCTV");
            return null;
        }

        private static boolean isSwitch(int row) {
            return row == 3 || row == 4 || row == MOTION_ROW || row == QUOTES_ROW || row == CAMERA_ROW || row >= COIN_ROWS;
        }

        private static boolean isOn(int row) {
            switch (row) {
                case 3: return Prefs.sounds();
                case 4: return Prefs.weather();
                case MOTION_ROW: return Prefs.wallpaperMotion();
                case QUOTES_ROW: return Prefs.quotes();
                case CAMERA_ROW: return Prefs.camera();
                default: return Prefs.quotes() && Prefs.coin(row - COIN_ROWS);
            }
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == S_HOME && busy()) {
                        String busyText = busyText();
                        return sectionName(S_HOME) + ", " + (busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText);
                    }
                    String value = sectionValue(row);
                    return sectionName(row) + (value.isEmpty() ? "" : ", " + value);
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Weatherglass TV, " + Ui.tr("sem anúncios, com a tela de descanso nova", "no ads, with the new screensaver")
                            : "Original, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". Em uso", ". In use") : "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2");
                }
                case HOME_SETTINGS: {
                    int[] rows = sectionRows();
                    if (row >= rows.length) return "";
                    return settingLabels()[rows[row]] + ", " + settingValues()[rows[row]];
                }
                case REMOTE_KEYS: {
                    String[] labels = keyLabels(), values = keyValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    return app[1] + ", " + (place >= 0 ? Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                            : Ui.tr("fora da prateleira", "not on the shelf"));
                }
                case CARE: {
                    String[] labels = careLabels();
                    return row < labels.length ? labels[row] + (careText[row] == null ? "" : ", " + careText[row]) : "";
                }
                default:
                    return "";
            }
        }

        // MARK: - Layout (1920 × 1080 units)

        private float rowTop(int i) {
            switch (screen) {
                case MAIN: return TOP + i * (MAIN_H + GAP) + (i > S_SCREENSAVER ? DIVIDER : 0) + (i > S_REMOTE ? DIVIDER : 0);
                case HOME_CHOICE: return TOP + i * (CHOICE_H + 8);
                case CARE: return CARE_TOP + i * (LIST_H + GAP);
                case HOME_SETTINGS: {
                    float y = TOP + i * (LIST_H + GAP);
                    for (int j = 0; j <= i; j++) if (groupTitle(j) != null) y += HEADER;
                    return y;
                }
                default: return TOP + i * (LIST_H + GAP);
            }
        }

        private float rowHeight() {
            return screen == MAIN ? MAIN_H : screen == HOME_CHOICE ? CHOICE_H : LIST_H;
        }

        private RectF rowBox(int row) {
            float top = rowTop(row);
            focusBox.set(L * k + slide, top * k, R * k + slide, (top + rowHeight()) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            if (!closing && open.value < 0.01f) open.target = 1;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        /** Opened again while it is up (the gear once more): back to the first level, in place. */
        void reopen() {
            closing = false;
            open.target = 1;
            screen = MAIN;
            focus = 0;
            invalidate();
        }

        void release() {
            click.release();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        /** Back from the first level: the drawer slides out, then the screen closes. */
        private void close() {
            closing = true;
            open.target = 0;
            invalidate();
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy() || !open.settled();
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();   // otherwise still: the poll redraws every 2 s
            if (closing && open.value < 0.02f) {
                activity.finish();
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            heading = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
            small = Ui.text(Ui.regular, 20 * k, 0x8CFFFFFF);
            rowLabel = Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 21 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 18 * k, 0x94FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 22 * k, 0xCCFFFFFF);
            cellValue = Ui.numbers(Ui.medium, 22 * k, 0xF2FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 21 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 21 * k, 0xE0FFFFFF));
            hint = new TextPaint(Ui.text(Ui.regular, 20 * k, 0x94FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1, 1.5f * k));
            border.setColor(0x29FFFFFF);
            scrim = new Paint();
            scrim.setShader(new LinearGradient(0, 0, DX * k, 0, 0x1A000000, 0x66000000, Shader.TileMode.CLAMP));
            sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            sheenDay = new LinearGradient(0, DT * k, 0, (DT + 220) * k, 0x29FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            sheenNight = new LinearGradient(0, DT * k, 0, (DT + 220) * k, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            border.setShader(new LinearGradient(0, DT * k, 0, DB * k, 0x52FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
            frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            frostShader = null;
            layouts.clear();
        }

        /** Night (black glass) or day (grey glass): as the home screen was, else the theme, else the hour. */
        private boolean night() {
            Boolean home = homeNight;
            if (home != null) return home;
            String theme = Prefs.theme();
            if (theme.equals("dark")) return true;
            if (theme.equals("light")) return false;
            java.util.Calendar now = java.util.Calendar.getInstance();
            int hour = now.get(java.util.Calendar.HOUR_OF_DAY);
            return hour < 6 || hour >= 18;
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return SECTIONS;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return sectionRows().length;
                case ABOUT: return 0;
                case CARE: return CARE_ACTIONS.length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Prefs.KEYS.length;
            }
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            open.step(dt);
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) lastSky = up;
            float o = Ui.clamp(open.value);
            slide = (1 - o) * (w - DX * k + 40 * k);

            // The home screen stays behind, dimmed toward the drawer.
            scrim.setAlpha(Math.round(255 * o));
            c.drawRect(0, 0, w, h, scrim);

            // The drawer: the sky (as on the home screen), darkened, in rounded glass with a soft shadow.
            panel.set(DX * k + slide, DT * k, DR * k + slide, DB * k);
            float radius = 44 * k;
            tmp.set(panel);
            tmp.offset(-14 * k, 10 * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * o));
            shadows.draw(c, tmp, radius, 22 * k, shadow);   // cached: a blur per frame is slow on Android 11
            boolean dark = night();
            Bitmap glass = frost;
            if (glass != null) {
                // Frosted glass: the home screen behind, blurred, where the drawer is (it stays put as the drawer slides).
                // A bitmap shader in a rounded rect: no clip path, one pass on the GPU.
                if (frostShader == null || frostShaderOf != glass) {
                    frostShaderOf = glass;
                    frostShader = Frost.filter(new BitmapShader(glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), BitmapShader.FILTER_MODE_LINEAR);
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.setScale((float) w / glass.getWidth(), (float) h / glass.getHeight());
                    frostShader.setLocalMatrix(m);
                    frostPaint.setShader(frostShader);
                }
                c.drawRoundRect(panel, radius, radius, frostPaint);
                fill.setColor(dark ? 0x9E0B0B0D : 0x8A48484E);
            } else {
                fill.setColor(dark ? 0xF21C1C1E : 0xF25A5A62);
            }
            c.drawRoundRect(panel, radius, radius, fill);
            // The light on its top edge, as on glass (the gradients are vertical: made once, for any slide)
            sheen.setShader(dark ? sheenNight : sheenDay);
            c.drawRoundRect(panel, radius, radius, sheen);
            c.drawRoundRect(panel, radius, radius, border);

            c.save();
            c.translate(slide, 0);
            drawHeader(c);
            switch (screen) {
                case MAIN: drawMain(c, seconds); break;
                case HOME_CHOICE: drawChoice(c); break;
                case HOME_SETTINGS: drawSection(c); break;
                case REMOTE_KEYS: drawKeys(c); break;
                case PICKER: drawPicker(c); break;
                case SHELF_EDIT: drawShelfEditor(c); break;
                case CARE: drawCare(c); break;
                default: drawAbout(c); break;
            }
            drawFooter(c);
            c.restore();
        }

        /** The title: "Settings" on the first level; inside a section, its name after a round back arrow. */
        private void drawHeader(Canvas c) {
            float cy = 106 * k;
            if (screen == MAIN) {
                c.drawText(Ui.tr("Ajustes", "Settings"), (L + 14) * k, cy + Ui.halfCap(heading), heading);
                small.setTextAlign(Paint.Align.RIGHT);
                c.drawText(versionName(getContext()), (R - 14) * k, cy + Ui.halfCap(small), small);
                small.setTextAlign(Paint.Align.LEFT);
                return;
            }
            float bx = (L + 30) * k;
            fill.setColor(0x1FFFFFFF);
            c.drawCircle(bx, cy, 26 * k, fill);
            c.save();
            c.scale(-1, 1, bx, cy);
            Symbols.draw(getContext(), c, "chevron_right", bx, cy, 22 * k, 0xFFFFFFFF);
            c.restore();
            String title;
            if (screen == PICKER) title = pickFor == PICK_CITY ? Ui.tr("Cidade", "City") : keyLabels()[pickFor - 1];
            else title = sectionName(screen == HOME_CHOICE ? S_HOME : screen == HOME_SETTINGS ? section
                    : screen == REMOTE_KEYS ? S_REMOTE : screen == SHELF_EDIT ? S_SHELF : screen == CARE ? S_CARE : S_ABOUT);
            c.drawText(TextUtils.ellipsize(title, new TextPaint(heading), (R - L - 100) * k, TextUtils.TruncateAt.END).toString(),
                    (L + 76) * k, cy + Ui.halfCap(heading), heading);
        }

        /** What the remote does here, at the bottom of the drawer. */
        private void drawFooter(Canvas c) {
            String text = screen == MAIN ? Ui.tr("OK ou →  abre      Voltar  fecha", "OK or →  open      Back  close")
                    : Ui.tr("Voltar  Ajustes", "Back  Settings");
            small.setTextAlign(Paint.Align.LEFT);
            c.drawText(text, (L + INSET) * k, (DB - 34) * k, small);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            for (int i = 0; i < SECTIONS; i++) {
                float y = rowTop(i) * k;
                if (i == S_SCREENSAVER + 1 || i == S_REMOTE + 1) {
                    fill.setColor(0x1FFFFFFF);
                    c.drawRect((L + INSET) * k, y - (DIVIDER / 2 + 1) * k, (R - INSET) * k, y - (DIVIDER / 2) * k, fill);
                }
                rowRect.set(L * k, y, R * k, y + MAIN_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xF2FFFFFF, INK, f);
                float cy = rowRect.centerY();
                float ix = rowRect.left + (INSET + 15) * k;
                if (ICONS[i] != null) Symbols.draw(getContext(), c, ICONS[i], ix, cy, 30 * k, ink);
                else drawInfo(c, ix, cy, ink);
                rowLabel.setColor(ink);
                float base = cy + Ui.halfCap(rowLabel);
                c.drawText(sectionName(i), rowRect.left + (INSET + 52) * k, base, rowLabel);
                float right = rowRect.right - INSET * k;
                boolean drills = i != S_SCREENSAVER;
                if (drills) {
                    float cw = Symbols.width(getContext(), "chevron_right", 20 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, cy, 20 * k, blend(0x66FFFFFF, 0x801C1C1E, f));
                    right -= cw + 16 * k;
                }
                if (i == S_HOME && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 300 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 24 * k;
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, sx, cy, 11 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0x9EFFFFFF, 0x991C1C1E, f));
                    String value = TextUtils.ellipsize(sectionValue(i), new TextPaint(rowValue), 260 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(value, right, base, rowValue);
                }
            }

            // Under the sections: what the TV is asking for while it switches, or what went wrong.
            float y = (rowTop(SECTIONS - 1) + MAIN_H + 46) * k;
            String error = !switchHint.isEmpty() ? switchHint : state.optString("error");
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow") || busyText.contains("разрешите"))) {
                wrap(c, Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        (L + INSET) * k, y, new TextPaint(warn), 3);
            } else if (!working && !error.isEmpty()) {
                wrap(c, error, (L + INSET) * k, y, new TextPaint(warn), 3);
            }
        }

        /** The About symbol (an "i" in a circle), drawn: the symbol set has none. */
        private void drawInfo(Canvas c, float cx, float cy, int color) {
            stroke.setColor(color);
            stroke.setStrokeWidth(2.4f * k);
            c.drawCircle(cx, cy, 12 * k, stroke);
            fill.setColor(color);
            c.drawCircle(cx, cy - 5.5f * k, 1.8f * k, fill);
            c.drawLine(cx, cy - 1 * k, cx, cy + 6 * k, stroke);
        }

        /** Home Screen: Weatherglass TV or the original (a radio on the one in use), then how they compare. */
        private void drawChoice(Canvas c) {
            String[][] options = {{"Weatherglass TV", Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver")},
                    {Ui.tr("Original", "Original"), Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = rowTop(i) * k;
                rowRect.set(L * k, y, R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xFFFFFFFF, INK, f);
                float rx = rowRect.left + (INSET + 15) * k, cy = rowRect.centerY();
                stroke.setStrokeWidth(2.6f * k);
                stroke.setColor(i == current ? ink : blend(0x73FFFFFF, 0x661C1C1E, f));
                c.drawCircle(rx, cy, 14 * k, stroke);
                if (i == current) {
                    fill.setColor(ink);
                    c.drawCircle(rx, cy, 7 * k, fill);
                }
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0xA6FFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + (INSET + 52) * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + (INSET + 52) * k, rowRect.top + 78 * k, rowSub);
            }
            float y = (rowTop(1) + CHOICE_H + 42) * k;
            y = wrap(c, Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                    "Switching takes a few seconds; the screen blinks while it measures."), (L + INSET) * k, y, hint, 2);
            drawComparison(c, y + 40 * k);
        }

        private void drawComparison(Canvas c, float top) {
            float x = (L + INSET) * k, colB = (R - INSET) * k, colA = colB - 170 * k;
            tmp.set(L * k + 10 * k, top, R * k - 10 * k, top + 196 * k);
            fill.setColor(0x14FFFFFF);
            c.drawRoundRect(tmp, 26 * k, 26 * k, fill);
            float y = top + 42 * k;
            c.drawText(Ui.tr("COMPARAÇÃO", "COMPARISON"), x + 10 * k, y, header);
            header.setTextAlign(Paint.Align.RIGHT);
            c.drawText("WEATHERGLASS", colA - 10 * k, y, header);
            c.drawText("ORIGINAL", colB - 10 * k, y, header);
            header.setTextAlign(Paint.Align.LEFT);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            String[][] rows = {{Ui.tr("Botão Início", "Home button"), "homeMs"}, {Ui.tr("Memória livre", "Free memory"), "memAvailableMB"},
                    {Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = top + (86 + 38 * i) * k;
                c.drawText(rows[i][0], x + 10 * k, base, cellLabel);
                cellValue.setColor(0xF2FFFFFF);
                c.drawText(metric(a, rows[i][1]), colA - 10 * k, base, cellValue);
                cellValue.setColor(0x99FFFFFF);
                c.drawText(metric(b, rows[i][1]), colB - 10 * k, base, cellValue);
            }
        }

        /** Appearance, Wallpaper or Widgets: their rows, a switch for on/off, ‹ value › for choices. */
        private void drawSection(Canvas c) {
            int[] rows = sectionRows();
            String[] labels = settingLabels(), values = settingValues();
            for (int i = 0; i < rows.length; i++) {
                int row = rows[i];
                float top = rowTop(i);
                String group = groupTitle(i);
                if (group != null) c.drawText(group, (L + INSET) * k, (top - 14) * k, header);
                rowRect.set(L * k, top * k, R * k, (top + LIST_H) * k);
                float f = drawRowBackground(c, i);
                boolean locked = row >= COIN_ROWS && !Prefs.quotes();
                int ink = blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(locked ? Ui.alpha(ink, 0.45f) : ink);
                float cy = rowRect.centerY(), base = cy + Ui.halfCap(rowLabel), right = rowRect.right - INSET * k;
                c.drawText(labels[row], rowRect.left + INSET * k, base, rowLabel);
                if (isSwitch(row)) {
                    drawSwitch(c, right, cy, isOn(row), f, locked);
                } else if (isTyped(row)) {
                    float cw = Symbols.width(getContext(), "chevron_right", 20 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, cy, 20 * k, blend(0x66FFFFFF, 0x801C1C1E, f));
                    rowValue.setColor(blend(0x9EFFFFFF, 0x991C1C1E, f));
                    c.drawText(TextUtils.ellipsize(values[row], new TextPaint(rowValue), 300 * k, TextUtils.TruncateAt.END).toString(),
                            right - cw - 16 * k, base, rowValue);
                } else {
                    drawChoiceValue(c, values[row], right, cy, base, f);
                }
            }
            float y = (rowTop(rows.length - 1) + LIST_H + 40) * k;
            if (y > (DB - 30) * k) return;   // no room for the hint under a long list
            String text = section == S_WIDGETS ? Ui.tr("Com o widget de cotações desligado, as moedas ficam desligadas.",
                    "With the quotes widget off, the coins stay off too.")
                    : section == S_APPEARANCE && Prefs.wallpaper().equals("photos")
                    ? Ui.tr("No fundo Filme o vidro é sempre transparente: é mais leve para a TV.", "On the Movie wallpaper the glass is always transparent: lighter for the TV.")
                    : section == S_WALLPAPER && Prefs.wallpaper().equals("photos")
                    ? Ui.tr("Com o movimento ligado, a imagem do filme se move devagar.", "With Motion on, the film's artwork drifts slowly.")
                    : section == S_WALLPAPER ? Ui.tr("“Ao vivo” segue o céu de verdade.", "Live follows the real sky.")
                    : Ui.tr("OK ou ← → mudam o valor.", "OK or ← → change the value.");
            wrap(c, text, (L + INSET) * k, y, hint, 2);
        }

        /** A choice: its value, with ‹ › around it while focused (← → step through). */
        private void drawChoiceValue(Canvas c, String value, float right, float cy, float base, float f) {
            float cw = Symbols.width(getContext(), "chevron_right", 18 * k);
            int arrows = Ui.alpha(INK, 0.6f * f);
            Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, cy, 18 * k, arrows);
            rowValue.setColor(blend(0xB3FFFFFF, 0xCC1C1C1E, f));
            rowValue.setTextAlign(Paint.Align.CENTER);
            String shown = TextUtils.ellipsize(value, new TextPaint(rowValue), 240 * k, TextUtils.TruncateAt.END).toString();
            float vw = Math.max(150 * k, rowValue.measureText(shown));
            float vc = right - cw - 14 * k - vw / 2;
            c.drawText(shown, vc, base, rowValue);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            float lx = vc - vw / 2 - 14 * k - cw / 2;
            c.save();
            c.scale(-1, 1, lx, cy);
            Symbols.draw(getContext(), c, "chevron_right", lx, cy, 18 * k, arrows);
            c.restore();
        }

        private void drawSwitch(Canvas c, float right, float cy, boolean on, float f, boolean locked) {
            float tw = 64 * k, th = 36 * k;
            tmp.set(right - tw, cy - th / 2, right, cy + th / 2);
            int off = blend(0x38FFFFFF, 0x2E1C1C1E, f);
            fill.setColor(on ? GREEN : off);
            if (locked) fill.setColor(Ui.alpha(fill.getColor(), 0.4f));
            c.drawRoundRect(tmp, th / 2, th / 2, fill);
            float kx = on ? tmp.right - th / 2 : tmp.left + th / 2;
            fill.setColor(locked ? 0x99FFFFFF : 0xFFFFFFFF);
            c.drawCircle(kx, cy, th / 2 - 3 * k, fill);
        }

        private void drawKeys(Canvas c) {
            String[] labels = keyLabels(), values = keyValues();
            for (int i = 0; i < labels.length; i++) {
                float top = rowTop(i);
                rowRect.set(L * k, top * k, R * k, (top + LIST_H) * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                float cy = rowRect.centerY(), base = cy + Ui.halfCap(rowLabel), right = rowRect.right - INSET * k;
                c.drawText(labels[i], rowRect.left + INSET * k, base, rowLabel);
                float cw = Symbols.width(getContext(), "chevron_right", 20 * k);
                Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, cy, 20 * k, blend(0x66FFFFFF, 0x801C1C1E, f));
                rowValue.setColor(blend(0x9EFFFFFF, 0x991C1C1E, f));
                c.drawText(TextUtils.ellipsize(values[i], new TextPaint(rowValue), 260 * k, TextUtils.TruncateAt.END).toString(),
                        right - cw - 16 * k, base, rowValue);
            }
            float y = (rowTop(labels.length - 1) + LIST_H + 40) * k;
            y = wrap(c, Ui.tr("OK escolhe o app; ← → trocam direto.", "OK picks the app; ← → switch it right here."), (L + INSET) * k, y, hint, 2);
            wrap(c, RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video e Google Play: a própria TV controla esses botões.",
                    "YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                    : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube e Prime Video: a própria TV controla esses botões.",
                    "Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                    : RemoteKeys.SONY ? Ui.tr("Padrão da Sony: o botão abre o mesmo app de sempre.",
                    "Sony default: the button opens what it always did.")
                    : RemoteKeys.FORMOVIE ? Ui.tr("Os outros botões: a própria TV os controla.", "The other buttons: the TV itself controls them.")
                    : RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                    "Disney and Globoplay: the TV itself controls those buttons.")
                    : Ui.tr("Botões do controle da TCL: nesta TV funcionam como sempre.",
                    "TCL remote buttons: on this TV they work as always."), (L + INSET) * k, y + 12 * k, hint, 3);
        }

        // MARK: - TV Status: what the TV is doing, and the care actions (the dashboard's, from the drawer)

        private Dashboard.Stats stats() {
            long now = SystemClock.uptimeMillis();
            if (stats == null || now - statsAt > 2000) {
                stats = Dashboard.stats(getContext());
                statsAt = now;
            }
            return stats;
        }

        private String[] careLabels() {
            return new String[] {Ui.tr("Liberar memória", "Free memory"), Ui.tr("Limpar caches", "Clear caches"),
                Ui.tr("Reiniciar o Início", "Restart home screen"), Ui.tr("Reiniciar a TV", "Reboot TV")};
        }

        private void drawCare(Canvas c) {
            Dashboard.Stats st = stats();
            float x = (L + INSET) * k, y = (TOP + 26) * k, width = (R - L - 2 * INSET) * k;
            // Memory: in use of the total, with Weatherglass's own share, and a bar (amber from 85%)
            int pct = Math.round(st.memUsed * 100f / Math.max(1, st.memTotal));
            c.drawText(Ui.tr("MEMÓRIA", "MEMORY"), x, y, header);
            y += 44 * k;
            rowLabel.setColor(0xF2FFFFFF);
            c.drawText(pct + "%", x, y, rowLabel);
            c.drawText(Dashboard.gb(st.memUsed) + " " + Ui.tr("de", "of") + " " + Dashboard.gb(st.memTotal) + "  ·  Weatherglass "
                    + Math.round(st.memOurs / 1048576f) + " MB", x + 90 * k, y, notice);
            y += 18 * k;
            fill.setColor(0x24FFFFFF);
            tmp.set(x, y, x + width, y + 6 * k);
            c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            fill.setColor(pct >= 85 ? AMBER : 0xFF5AA9FF);
            tmp.set(x, y, x + width * Math.min(1, pct / 100f), y + 6 * k);
            c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            // Storage and uptime, side by side
            y += 52 * k;
            int dpct = Math.round((st.diskTotal - st.diskFree) * 100f / Math.max(1, st.diskTotal));
            c.drawText(Ui.tr("ARMAZENAMENTO", "STORAGE"), x, y, header);
            c.drawText(Ui.tr("LIGADA HÁ", "UP FOR"), x + width / 2, y, header);
            y += 44 * k;
            c.drawText(dpct + "%", x, y, rowLabel);
            c.drawText(Dashboard.gb(st.diskFree) + " " + Ui.tr("livres", "free"), x + 90 * k, y, notice);
            c.drawText(Dashboard.upFor(st.uptime), x + width / 2, y, rowLabel);
            y += 44 * k;
            c.drawText(Ui.tr("CUIDADOS", "CARE"), x, y, header);
            // The care rows
            String[] labels = careLabels();
            for (int i = 0; i < labels.length; i++) {
                float top = rowTop(i);
                rowRect.set(L * k, top * k, R * k, (top + LIST_H) * k);
                float f = drawRowBackground(c, i);
                boolean danger = i == 3;
                rowLabel.setColor(blend(danger ? 0xFFFF6B64 : 0xF2FFFFFF, danger ? 0xFFD70015 : INK, f));
                float cy = rowRect.centerY(), base = cy + Ui.halfCap(rowLabel), right = rowRect.right - INSET * k;
                c.drawText(labels[i], rowRect.left + INSET * k, base, rowLabel);
                String value = careText[i] == null ? (i == 0 ? Ui.tr("fecha os apps em segundo plano", "stops background apps")
                        : i == 3 ? Ui.tr("uns 40 s", "about 40 s") : "") : careText[i];
                rowValue.setColor(blend(0x9EFFFFFF, 0x991C1C1E, f));
                c.drawText(TextUtils.ellipsize(value, new TextPaint(rowValue), 330 * k, TextUtils.TruncateAt.END).toString(),
                        right - rowValue.measureText(TextUtils.ellipsize(value, new TextPaint(rowValue), 330 * k, TextUtils.TruncateAt.END).toString()), base, rowValue);
            }
            float hy = (rowTop(labels.length - 1) + LIST_H + 40) * k;
            wrap(c, Ui.tr("O mesmo painel abre no celular: o endereço e o código estão em Sobre.",
                    "The same panel opens on a phone: the address and the code are under About."), x, hy, hint, 2);
        }

        /** OK on a care row: runs it (Reboot takes a second OK), and shows how it went in the row. */
        private void runCare(int row) {
            if (careBusy || row < 0 || row >= CARE_ACTIONS.length) return;
            String action = CARE_ACTIONS[row];
            if (action.equals("reboot") && !rebootArmed) {
                rebootArmed = true;
                careText[row] = Ui.tr("OK de novo para reiniciar", "OK again to reboot");
                handler.postDelayed(() -> { if (rebootArmed) { rebootArmed = false; careText[3] = null; invalidate(); } }, 5000);
                return;
            }
            rebootArmed = false;
            careBusy = true;
            careText[row] = Ui.tr("Um momento…", "One moment…");
            Dashboard.careFrom(getContext(), action, result -> {
                careBusy = false;
                if (result.startsWith("!")) careText[row] = Ui.tr("Não deu: ", "Couldn't: ") + result.substring(1);
                else if (action.equals("free")) careText[row] = result.isEmpty() ? Ui.tr("Feito", "Done") : result + " " + Ui.tr("apps fechados", "apps stopped");
                else if (action.equals("reboot")) careText[row] = Ui.tr("Reiniciando…", "Rebooting…");
                else careText[row] = Ui.tr("Feito", "Done");
                statsAt = 0;
                invalidate();
                announceForAccessibility(careText[row]);
            });
        }

        /** About: the picks, the latest notices, and where this comes from. */
        private void drawAbout(Canvas c) {
            float x = (L + INSET) * k, y = (TOP + 26) * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            String error = recs.optString("error");
            int count = recs.optInt("count");
            String status, detail;
            int dot;
            if (!hasKey) {
                dot = AMBER;
                status = Ui.tr("Desligadas: sem chave do TMDB", "Off: no TMDB key");
                detail = Ui.tr("Ponha tmdb_key no config.properties e gere o app de novo (veja o README).",
                        "Add tmdb_key to config.properties and build again (see the README).");
            } else if (!error.isEmpty()) {
                dot = AMBER;
                status = Ui.tr("Erro", "Error");
                detail = error + ".";
            } else if (count > 0) {
                dot = GREEN;
                status = Ui.tr("Ligadas", "On");
                detail = Ui.tr(count + " títulos nos apps da TV, 3 novos a cada hora.", count + " titles from the TV's apps, 3 new ones every hour.");
            } else {
                dot = 0xFFFFFFFF;
                status = Ui.tr("Buscando os títulos…", "Fetching titles…");
                detail = "";
            }
            y += 44 * k;
            fill.setColor(dot);
            c.drawCircle(x + 7 * k, y - 8 * k, 7 * k, fill);
            rowLabel.setColor(0xF2FFFFFF);
            c.drawText(status, x + 26 * k, y, rowLabel);
            if (!detail.isEmpty()) y = wrap(c, detail, x, y + 38 * k, notice, 3);
            y = wrap(c, Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 34 * k, hint, 2);
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) {
                y = wrap(c, Ui.tr("Links dos títulos por Watchmode.", "Links to the titles by Watchmode."), x, y + 30 * k, hint, 1);
            }

            // The control page on the home network: where to open it, and the code that pairs a phone once
            y += 64 * k;
            c.drawText(Ui.tr("PAINEL", "DASHBOARD"), x, y, header);
            String address = Dashboard.address();
            y += 44 * k;
            rowLabel.setColor(0xF2FFFFFF);
            c.drawText(address.isEmpty() ? Ui.tr("Sem rede Wi‑Fi", "No Wi‑Fi network") : address, x, y, rowLabel);
            y = wrap(c, Ui.tr("Abra no celular ou no computador, na mesma rede Wi‑Fi. Código para parear: ", "Open it on a phone or a computer on the same Wi‑Fi. Code to pair: ")
                    + Dashboard.code(getContext()) + ".", x, y + 38 * k, notice, 2);

            y += 64 * k;
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 3; i++) {
                JSONObject n = notices.optJSONObject(i);
                if (n == null) continue;
                y += 38 * k;
                c.drawText(ago(n.optLong("at")), x, y, hint);
                y = wrap(c, Ui.ru ? Russian.text(n.optString("text")) : n.optString("text"), x, y + 32 * k, notice, 2);
                shown++;
            }
            if (shown == 0) {
                y += 38 * k;
                c.drawText(Ui.tr("Nenhum ainda.", "None yet."), x, y, notice);
            }

            y += 64 * k;
            c.drawText(Ui.tr("SOBRE", "ABOUT"), x, y, header);
            c.drawText(versionName(getContext()), x, y + 40 * k, notice);
            c.drawText(Ui.tr("Modificado por xACTDx", "Modified by xACTDx"), x, y + 74 * k, notice);
            c.drawText(Ui.tr("Baseado no Free My TV, de simbasjungles · Licença MIT",
                    "Based on Free My TV by simbasjungles · MIT License"), x, y + 108 * k, hint);
        }

        /** Text wrapped to the drawer's width (up to maxLines); returns the last line's baseline. */
        private float wrap(Canvas c, String text, float x, float baseline, TextPaint paint, int maxLines) {
            int width = Math.round((R - L - 2 * INSET) * k);
            String key = text + "\u0000" + paint.getTextSize() + "\u0000" + paint.getColor() + "\u0000" + maxLines;
            StaticLayout layout = layouts.get(key);
            if (layout == null) {
                if (layouts.size() > 40) layouts.clear();
                layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                        .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
                layouts.put(key, layout);
            }
            c.save();
            c.translate(x, baseline - layout.getLineBaseline(0));
            layout.draw(c);
            c.restore();
            return baseline + layout.getLineBaseline(layout.getLineCount() - 1) - layout.getLineBaseline(0);
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 22 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                shadows.draw(c, tmp, r, 22 * k, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return v + (Ui.ru ? " мс" : " ms");
            if (key.equals("memAvailableMB") && v >= 1024) return String.format(Ui.locale(), Ui.ru ? "%.2f ГБ" : "%.2f GB", v / 1024.0);
            return v + (Ui.ru ? " МБ" : " MB");
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("agora", "now");
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
        }

        private static String versionName(Context c) {
            try {
                return "Weatherglass TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Weatherglass TV";
            }
        }

        // MARK: - Keys

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (closing) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) { focus--; click.move(); }
                    else if (down && scrolling() && pickScroll > 0) { pickScroll--; click.move(); }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) { focus++; click.move(); }
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) { pickScroll++; click.move(); }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    if (!down) return true;
                    boolean right = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT;
                    if (arrowChanges()) {
                        // A value under the focus: ← → change it (or move the shelf app)
                        click.move();
                        if (screen == SHELF_EDIT) moveShelfApp(right ? 1 : -1);
                        else adjust(right ? 1 : -1);
                    } else if (right && screen == MAIN && focus != S_SCREENSAVER && focus != S_TV && e.getRepeatCount() == 0) {
                        // → goes straight into a section (Screensaver and TV Settings leave the drawer: those take OK)
                        click.open();
                        activate();
                    } else if (right && screen == HOME_SETTINGS && e.getRepeatCount() == 0) {
                        click.open();
                        activate();   // the city: its search
                    } else if (!right && screen != MAIN && e.getRepeatCount() == 0) {
                        // ← goes back up where it has nothing to change
                        click.move();
                        back();
                        spoken.focusMoved();
                    }
                    invalidate();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) {
                            click.open();
                            activate();
                        }
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            click.move();
                            back();
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        /** Whether ← → change something on the focused row (a value, a remote button's app, a shelf app's place). */
        private boolean arrowChanges() {
            if (screen == REMOTE_KEYS) return true;
            if (screen == HOME_SETTINGS) {
                int[] rows = sectionRows();
                return focus < rows.length && !isTyped(rows[focus]);
            }
            if (screen == SHELF_EDIT) {
                int at = pickScroll + focus;
                return at < shelfRows.size() && shelfOrder.contains(shelfRows.get(at)[0]);
            }
            return false;
        }

        /** Up one level; from the first level, the drawer closes. */
        private void back() {
            if (screen == MAIN) {
                close();
                return;
            }
            if (screen == PICKER && pickFor == PICK_CITY) {
                screen = HOME_SETTINGS;
                section = S_WIDGETS;
                focus = 1;
            } else if (screen == PICKER) {
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else {
                focus = screen == HOME_SETTINGS ? section : screen == SHELF_EDIT ? S_SHELF : screen == REMOTE_KEYS ? S_REMOTE
                        : screen == ABOUT ? S_ABOUT : screen == CARE ? S_CARE : S_HOME;
                screen = MAIN;
                rebootArmed = false;
            }
            for (Spring s : pop) s.snap(0);
        }

        // MARK: - Home Settings rows and Remote Buttons

        private static final String[] LANGUAGES = {"auto", "en", "id"}, UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /**
         * The Home Settings rows (shown in Appearance, Wallpaper and Widgets): language, temperature, theme, sounds,
         * weather, wallpaper, its motion, the sky preview (weather, time of day), city, quotes, then one row per coin.
         */
        private static final int WALLPAPER_ROW = 5, MOTION_ROW = 6, SKY_WEATHER_ROW = 7, SKY_TIME_ROW = 8,
                CITY_ROW = 9, QUOTES_ROW = 10, TEXT_ROW = 11, NAME_ROW = 12, CAMERA_ROW = 13, CAMERA_URL_ROW = 14, GLASS_ROW = 15, COIN_ROWS = 16;

        /** Rows whose value is typed (OK opens the keyboard): the city, the name, the camera's address. */
        private static boolean isTyped(int row) {
            return row == CITY_ROW || row == NAME_ROW || row == CAMERA_URL_ROW;
        }
        private static final String[] TEXT_SIZES = {"small", "medium", "large"}, GLASSES = {"frosted", "clear"};

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Idioma", "Language"));
            l.add(Ui.tr("Temperatura", "Temperature"));
            l.add(Ui.tr("Claro ou escuro", "Light or dark"));
            l.add(Ui.tr("Sons de navegação", "Navigation sounds"));
            l.add(Ui.tr("Widget do clima", "Weather widget"));
            l.add(Ui.tr("Tema", "Theme"));
            l.add(Ui.tr("Movimento", "Motion"));
            l.add(Ui.tr("Clima", "Weather"));
            l.add(Ui.tr("Hora do dia", "Time of day"));
            l.add(Ui.tr("Cidade", "City"));
            l.add(HomeData.RATES ? Ui.tr("Widget de câmbio", "Exchange rates widget") : Ui.tr("Widget de cotações", "Quotes widget"));
            l.add(Ui.tr("Widget do relógio", "Clock widget"));
            l.add(Ui.tr("Seu nome", "Your name"));
            l.add(Ui.tr("Câmera de segurança", "CCTV camera"));
            l.add(Ui.tr("Endereço da câmera", "Camera address"));
            l.add(Ui.tr("Vidro", "Glass"));
            if (HomeData.RATES) {
                for (int i = 0; i < HomeData.RATE_CODES.length; i++) l.add(HomeData.coinName(i));
            } else {
                l.add("Bitcoin");
                l.add("Ethereum");
                l.add("Solana");
                if (dollar()) l.add(Ui.tr("Dólar", "Dollar"));
            }
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("en") ? "English" : lang.equals("id") ? "Bahasa Indonesia" : Ui.tr("Automático", "Automatic");
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark") : Ui.tr("Automático", "Automatic");
            v[3] = onOff(Prefs.sounds());
            v[4] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? Ui.tr("Escolher", "Choose") : Prefs.city();
            String wp = Prefs.wallpaper();
            v[WALLPAPER_ROW] = wp.equals("photos") ? Ui.tr("Filme", "Movie")
                    : wp.equals("ink") ? Ui.tr("Montanhas em tinta", "Ink mountains") : Ui.tr("Céu do clima", "Weather sky");
            v[MOTION_ROW] = onOff(Prefs.wallpaperMotion());
            String[] weathers = {Ui.tr("Ao vivo", "Live"), Ui.tr("Céu limpo", "Clear"), Ui.tr("Parcialmente nublado", "Partly cloudy"),
                Ui.tr("Nublado", "Cloudy"), Ui.tr("Garoa", "Drizzle"), Ui.tr("Chuva", "Rain"), Ui.tr("Tempestade", "Thunderstorm"), Ui.tr("Névoa", "Haze")};
            v[SKY_WEATHER_ROW] = weathers[Math.max(0, java.util.Arrays.asList(Prefs.SKY_WEATHERS).indexOf(Prefs.skyWeather()))];
            String[] times = {Ui.tr("Ao vivo", "Live"), Ui.tr("Nascer do sol", "Sunrise"), Ui.tr("Manhã", "Morning"), Ui.tr("Meio-dia", "Noon"),
                Ui.tr("Tarde", "Afternoon"), Ui.tr("Hora dourada", "Golden hour"), Ui.tr("Pôr do sol", "Sunset"), Ui.tr("Crepúsculo", "Dusk"), Ui.tr("Noite", "Night")};
            v[SKY_TIME_ROW] = times[Math.max(0, java.util.Arrays.asList(Prefs.SKY_TIMES).indexOf(Prefs.skyTime()))];
            v[QUOTES_ROW] = onOff(Prefs.quotes());
            String size = Prefs.textSize();
            String glass = Prefs.glass();
            v[GLASS_ROW] = glass.equals("clear") ? Ui.tr("Transparente", "Transparent") : Ui.tr("Fosco", "Frosted");
            v[TEXT_ROW] = size.equals("small") ? Ui.tr("Pequeno", "Small") : size.equals("large") ? Ui.tr("Grande", "Large") : Ui.tr("Médio", "Medium");
            v[NAME_ROW] = Prefs.greetingName().isEmpty() ? Ui.tr("Nenhum", "None") : Prefs.greetingName();
            v[CAMERA_ROW] = onOff(Prefs.camera());
            v[CAMERA_URL_ROW] = cameraHost(Prefs.cameraUrl());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Botão Netflix", "Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Botão Tabii", "Tabii button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("Botão BRAVIA CORE", "BRAVIA CORE button"),
                    Ui.tr("Botão Netflix", "Netflix button"), Ui.tr("Botão Disney+", "Disney+ button"),
                    Ui.tr("Botão Prime Video", "Prime Video button"), Ui.tr("Botão YouTube", "YouTube button"),
                    Ui.tr("Botão Crunchyroll", "Crunchyroll button")};
            return new String[] {Ui.tr("Botão Netflix", "Netflix button"), Ui.tr("Botão YouTube", "YouTube button"),
                    Ui.tr("Botão Prime Video", "Prime Video button"), Ui.tr("Botão TCL Channel", "TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Padrão da Sony", "Sony default") : Ui.tr("Nada", "Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            int[] rows = sectionRows();
            if (focus >= rows.length) return;
            int row = rows[focus];
            switch (row) {
                case 0: {
                    int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]);
                    break;
                }
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: Prefs.setSounds(!Prefs.sounds()); break;
                case 4: Prefs.setWeather(!Prefs.weather()); break;
                case WALLPAPER_ROW: {
                    int at = java.util.Arrays.asList(Prefs.WALLPAPERS).indexOf(Prefs.wallpaper());
                    int n = Prefs.WALLPAPERS.length;
                    Prefs.setWallpaper(Prefs.WALLPAPERS[(Math.max(0, at) + dir + n) % n]);
                    break;
                }
                case MOTION_ROW: Prefs.setWallpaperMotion(!Prefs.wallpaperMotion()); break;
                case SKY_WEATHER_ROW: {
                    int at = java.util.Arrays.asList(Prefs.SKY_WEATHERS).indexOf(Prefs.skyWeather());
                    int n = Prefs.SKY_WEATHERS.length;
                    Prefs.setSkyWeather(Prefs.SKY_WEATHERS[(Math.max(0, at) + dir + n) % n]);
                    break;
                }
                case SKY_TIME_ROW: {
                    int at = java.util.Arrays.asList(Prefs.SKY_TIMES).indexOf(Prefs.skyTime());
                    int n = Prefs.SKY_TIMES.length;
                    Prefs.setSkyTime(Prefs.SKY_TIMES[(Math.max(0, at) + dir + n) % n]);
                    break;
                }
                case CITY_ROW: case NAME_ROW: case CAMERA_URL_ROW: return;
                case CAMERA_ROW:
                    if (Prefs.cameraUrl().isEmpty()) askCameraUrl();   // nothing to show yet: ask for the address first
                    else Prefs.setCamera(!Prefs.camera());
                    break;
                case QUOTES_ROW: Prefs.setQuotes(!Prefs.quotes()); break;
                case GLASS_ROW: {
                    int at = java.util.Arrays.asList(GLASSES).indexOf(Prefs.glass());
                    Prefs.setGlass(GLASSES[(at + dir + GLASSES.length) % GLASSES.length]);
                    break;
                }
                case TEXT_ROW: {
                    int at = java.util.Arrays.asList(TEXT_SIZES).indexOf(Prefs.textSize());
                    Prefs.setTextSize(TEXT_SIZES[(at + dir + TEXT_SIZES.length) % TEXT_SIZES.length]);
                    break;
                }
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(row - COIN_ROWS, !Prefs.coin(row - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        /** The camera's address as the row shows it: just its host (never the password), or "Set up". */
        private static String cameraHost(String url) {
            if (url.isEmpty()) return Ui.tr("Configurar", "Set up");
            try {
                String host = android.net.Uri.parse(url).getHost();
                if (host != null && !host.isEmpty()) return host;
            } catch (Exception ignored) {
            }
            return Ui.tr("Definido", "Set");
        }

        /** A one-line text dialog with the TV's keyboard; `done` gets what was typed. */
        private void askText(String title, String hint, String value, int input, java.util.function.Consumer<String> done) {
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setInputType(input);
            field.setHint(hint);
            field.setText(value);
            field.setSelection(field.getText().length());
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setView(field)
                    .setPositiveButton(Ui.tr("Salvar", "Save"), (d, which) -> done.accept(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                done.accept(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void askName() {
            askText(Ui.tr("Seu nome", "Your name"), Ui.tr("Vazio: só a saudação", "Empty: just the greeting"), Prefs.greetingName(),
                    android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS, name -> {
                        Prefs.setGreetingName(name);
                        invalidate();
                    });
        }

        private void askCameraUrl() {
            askText(Ui.tr("Endereço da câmera", "Camera address"), "rtsp://user:password@192.168.1.20:554/stream0", Prefs.cameraUrl(),
                    android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI, url -> {
                        url = url.trim();
                        if (!url.isEmpty() && !url.contains("://")) url = "rtsp://" + url;
                        Prefs.setCameraUrl(url);
                        Prefs.setCamera(!url.isEmpty());
                        invalidate();
                    });
        }

        private void askCity() {
            if (Ui.tr("Procurando…", "Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Digite o nome da cidade", "Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Cidade", "City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Buscar", "Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Procurando…", "Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("Nenhuma cidade com esse nome.", "No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.tr("Não deu para buscar agora: ", "Couldn't search now: ") + e.getMessage();
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            shelfOrder.clear();
            // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the limit)
            // without a row to take it off.
            PackageManager pm = getContext().getPackageManager();
            for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float top = rowTop(i);
                rowRect.set(L * k, top * k, R * k, (top + LIST_H) * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                float cy = rowRect.centerY(), base = cy + Ui.halfCap(rowLabel), right = rowRect.right - INSET * k;
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 420 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + INSET * k, base, rowLabel);
                if (place >= 0) {
                    // Its place on the shelf, in a small round badge
                    String n = String.valueOf(place + 1);
                    float bw = Math.max(40 * k, rowValue.measureText(n) + 22 * k);
                    tmp.set(right - bw, cy - 18 * k, right, cy + 18 * k);
                    fill.setColor(blend(0x29FFFFFF, 0x1F1C1C1E, f));
                    c.drawRoundRect(tmp, 18 * k, 18 * k, fill);
                    rowValue.setColor(blend(0xF2FFFFFF, INK, f));
                    rowValue.setTextAlign(Paint.Align.CENTER);
                    c.drawText(n, tmp.centerX(), base, rowValue);
                    rowValue.setTextAlign(Paint.Align.RIGHT);
                } else {
                    rowValue.setColor(blend(0x73FFFFFF, 0x801C1C1E, f));
                    c.drawText(Ui.tr("Fora", "Off"), right, base, rowValue);
                }
            }
            float y = (rowTop(Math.max(0, rows - 1)) + LIST_H + 40) * k;
            wrap(c, Ui.tr("OK põe ou tira da prateleira (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem.",
                    "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                    "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden."), (L + INSET) * k, y, hint, 2);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps, the cities): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float top = rowTop(i);
                rowRect.set(L * k, top * k, R * k, (top + LIST_H) * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(TextUtils.ellipsize(pickLabels.get(at), new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(),
                        rowRect.left + INSET * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 24 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - INSET * k - cw / 2, rowRect.centerY(), 24 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            float y = (rowTop(Math.max(0, rows - 1)) + LIST_H + 40) * k;
            wrap(c, (pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."),
                    (L + INSET) * k, y, hint, 1);
        }

        /** The switch needs the TV's own debugging: if it's off, say how and open Developer options (or About). */
        private void switchWhenDebugging(Context c) {
            pendingAt = SystemClock.uptimeMillis();
            new Thread(() -> {
                boolean open;
                try (java.net.Socket s = new java.net.Socket()) {
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", 5555), 1500);
                    open = true;
                } catch (java.io.IOException e) {
                    open = false;
                }
                boolean on = open;
                handler.post(() -> {
                    if (on) {
                        switchHint = "";
                        HomeMode.request(c, HomeMode.FREE);
                        return;
                    }
                    pendingAt = 0;
                    switchHint = Ui.tr("Ligue a depuração USB nas Opções do desenvolvedor e tente de novo.",
                            "Turn on USB debugging in Developer options, then try again.");
                    invalidate();
                    Intent dev = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        activity.startActivity(dev);
                    } catch (RuntimeException e) {
                        // No Developer options yet: About, where pressing the build number 7 times turns them on
                        switchHint = Ui.tr("Em Sobre, aperte o número da versão 7 vezes; depois ligue a depuração USB.",
                                "In About, press the build number 7 times; then turn on USB debugging in Developer options.");
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (RuntimeException ignored) {
                        }
                    }
                });
            }, "debug-check").start();
        }

        private void activate() {
            Context c = getContext();
            if (screen == ABOUT) return;
            if (screen == CARE) {
                runCare(focus);
                invalidate();
                return;
            }
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    if (wantFree) {
                        switchWhenDebugging(c);
                    } else {
                        switchHint = "";
                        HomeMode.request(c, HomeMode.ORIGINAL);
                        pendingAt = SystemClock.uptimeMillis();
                    }
                }
                screen = MAIN;
                focus = S_HOME;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                screen = HOME_SETTINGS;
                section = S_WIDGETS;
                focus = 1;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS) {
                int[] rows = sectionRows();
                int row = focus < rows.length ? rows[focus] : -1;
                if (row == CITY_ROW) askCity();
                else if (row == NAME_ROW) askName();
                else if (row == CAMERA_URL_ROW) askCameraUrl();
                else adjust(1);
                invalidate();
                return;
            } else if (focus == S_HOME) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == S_SCREENSAVER) {
                pause();
                activity.showPreview();
            } else if (focus == S_APPEARANCE || focus == S_WALLPAPER || focus == S_WIDGETS) {
                section = focus;
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (focus == S_SHELF) {
                openShelfEditor();
            } else if (focus == S_REMOTE) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else if (focus == S_CARE) {
                screen = CARE;
                focus = 0;
                statsAt = 0;
            } else if (focus == S_ABOUT) {
                screen = ABOUT;
                focus = 0;
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
