package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import java.util.Random;

/**
 * The "Ink mountains" wallpaper: a cream sky with a red sun over layered ridges of mountains with fine contour lines,
 * fading into mist. Its colors follow the time of day (dawn, sunrise, a misty morning, a bright noon, the afternoon,
 * golden hour, the sun setting behind the peaks at Maghrib, dusk, and the moon at night) and are tinted by the weather
 * (soft cloud washes, raindrops on the glass, lightning, haze).
 *
 * The mountains and clouds are drawn into bitmaps on a background thread, again every 6 minutes of the day (their
 * colors change slowly), and the new ones fade in over 2 s; each frame only moves bitmaps, so it costs about what the
 * weather sky does.
 *
 * Hours here are on a fixed day with sunrise at 5:45 and sunset at 17:42; the home screen maps the real time onto it
 * with the day's real sunrise and sunset (canonical()).
 */
final class InkSky {
    private static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, RAIN = 3, STORM = 4, FOG = 5;
    private static final float FAR = 28, MID = 80, NEAR = 140;   // parallax travel (px at 1920) of each depth
    static final float SUNRISE = 5.75f, SUNSET = 17.7f;

    // Each weather by day and by night: sky top, sky bottom, far ridges, near ridges.
    private static final int[][] DAY = {
        {0xFFF7EFDC, 0xFFF1E5CB, 0xFFC9D5D9, 0xFF1E3443}, {0xFFF4ECDC, 0xFFEFE5D0, 0xFFC6D2D7, 0xFF22384A},
        {0xFFE4E2DC, 0xFFDAD8D1, 0xFFBCC6CA, 0xFF2A3A46}, {0xFFC9CFD2, 0xFFDADDDC, 0xFFB0BBC1, 0xFF22323E},
        {0xFF46515C, 0xFF6A747B, 0xFF5C6973, 0xFF0F1922}, {0xFFEFECE5, 0xFFF3F1EB, 0xFFDDE2E4, 0xFF62717C}};
    private static final int[][] NIGHT = {
        {0xFF0E1828, 0xFF213048, 0xFF34465C, 0xFF060C14}, {0xFF0F192A, 0xFF22314A, 0xFF33455B, 0xFF070D16},
        {0xFF1A2230, 0xFF283141, 0xFF354153, 0xFF0A1018}, {0xFF151D29, 0xFF232C3A, 0xFF2F3B4B, 0xFF070C12},
        {0xFF0B1018, 0xFF171E29, 0xFF242E3B, 0xFF04070B}, {0xFF262E3B, 0xFF343D4B, 0xFF404B5C, 0xFF151C26}};
    private static final float[] DAY_SUN = {1, 0.95f, 0.3f, 0, 0, 0.45f}, NIGHT_SUN = {1, 0.9f, 0.25f, 0, 0, 0.35f};
    private static final int[] CLOUDS = {0, 4, 7, 6, 7, 0};
    private static final float[] TINT = {0, 0.15f, 0.6f, 0.75f, 0.85f, 0.7f};   // how much the weather overrides the time's colors

    // The clear sky through the day. Colors: sky top, sky bottom, far, near, sun top, sun bottom (0: no sun or moon).
    // Values: moon (1) or sun (0), its strength, x, y, radius (px at 1920), night (0..1), morning mist (1 = usual).
    private static final float[] HOURS = {0, 4.2f, 5.1f, 5.75f, 7.5f, 12, 15.3f, 16.9f, 17.7f, 18.4f, 19.3f, 24};
    private static final int[][] COLORS = {
        {0xFF0E1828, 0xFF213048, 0xFF34465C, 0xFF060C14, 0xFFF6ECD4, 0xFFC8BB9B},
        {0xFF111B2F, 0xFF29324E, 0xFF39455E, 0xFF09101A, 0xFFF6ECD4, 0xFFC8BB9B},
        {0xFF2C3557, 0xFF8A7389, 0xFF55566E, 0xFF141826, 0, 0},
        {0xFFE5B6A3, 0xFFF6DBBF, 0xFFA9AEBF, 0xFF2B3447, 0xFFF4683E, 0xFFF8C9A0},
        {0xFFC9DCE6, 0xFFF1EEE4, 0xFFB3C8D6, 0xFF264059, 0xFFF57048, 0xFFFBD9B4},
        {0xFFFCF4D9, 0xFFFAF1DC, 0xFFC3D2D6, 0xFF17293A, 0xFFE5303C, 0xFFF08A6E},
        {0xFFF7EFDC, 0xFFF1E5CB, 0xFFC9D5D9, 0xFF1E3443, 0xFFF0444A, 0xFFF6CDB8},
        {0xFFF2D6A4, 0xFFF8E5BE, 0xFFCDB89F, 0xFF3A3038, 0xFFF2803A, 0xFFF9D49A},
        {0xFFDC8A6E, 0xFFF5BE8B, 0xFFB38B87, 0xFF2B2033, 0xFFE2412E, 0xFFF29A6A},
        {0xFF29314F, 0xFF86697A, 0xFF4C4C66, 0xFF111522, 0, 0},
        {0xFF0E1828, 0xFF213048, 0xFF34465C, 0xFF060C14, 0xFFF6ECD4, 0xFFC8BB9B},
        {0xFF0E1828, 0xFF213048, 0xFF34465C, 0xFF060C14, 0xFFF6ECD4, 0xFFC8BB9B}};
    private static final float[][] VALUES = {
        {1, 1.0f, 1250, 210, 80, 1, 1}, {1, 0.7f, 1300, 300, 80, 1, 1}, {0, 0, 1480, 600, 110, 0.8f, 1},
        {0, 1, 1480, 560, 112, 0.15f, 1}, {0, 1, 1420, 390, 100, 0, 1.5f}, {0, 1, 1200, 130, 72, 0, 0.35f},
        {0, 1, 1260, 270, 94, 0, 1}, {0, 1, 1380, 440, 104, 0, 1}, {0, 1, 1460, 585, 114, 0.25f, 1},
        {0, 0, 1460, 640, 114, 0.8f, 1}, {1, 1, 1250, 230, 80, 1, 1}, {1, 1, 1250, 210, 80, 1, 1}};

    // Ridges far to near: base line, height, wave, phase (at 1920x1080); everything above TOP is sky.
    private static final float[][] RIDGES = {{600, 95, 0.0024f, 0.4f}, {660, 115, 0.0030f, 2.1f}, {730, 135, 0.0036f, 4.0f},
        {810, 150, 0.0042f, 1.2f}, {890, 160, 0.0048f, 3.3f}, {970, 165, 0.0054f, 5.1f}};
    private static final float TOP = 400, MARGIN = 160;
    // The far and middle ridges sit in mist, so they're kept at lower resolution (and scaled up when drawn): less memory.
    private static final float[] RES = {0.5f, 0.75f, 1};
    // Soft cloud washes: overlapping ellipses {x, y, rx, ry, opacity} in an 1100x300 box, blurred together once.
    private static final float[][][] WASHES = {
        {{560, 150, 420, 70, .55f}, {520, 140, 300, 52, .95f}, {700, 160, 240, 44, .85f}, {330, 168, 200, 34, .7f}},
        {{540, 150, 400, 64, .55f}, {460, 150, 280, 48, .9f}, {680, 140, 260, 56, .95f}},
        {{560, 155, 440, 72, .5f}, {600, 145, 320, 54, .95f}, {380, 165, 180, 34, .75f}}};
    private static final float CLOUD_W = 1100, CLOUD_H = 300, CLOUD_RES = 0.5f;   // blurred anyway: half resolution
    // Partly cloudy keeps one composition (upper left, right of the sun, two faint ones): x, y, size, opacity.
    private static final float[][] ANCHORS = {{-260, 10, 1.15f, 1}, {930, 190, 1.25f, 1}, {1250, -60, .8f, .7f}, {380, 330, .9f, .55f}};
    private static final float[] CLOUD_Y = {20, 230, 120, 420, 20, 300, 160, 380};
    // Raindrops on the glass: 440 drops and 9 running ones, all stamped from one small drop drawn once (day and night)
    private static final int GLASS_DROPS = 440, RUNNERS = 9;
    private final float[] gx = new float[GLASS_DROPS], gy = new float[GLASS_DROPS], gr = new float[GLASS_DROPS], gsq = new float[GLASS_DROPS];
    private final float[] rx = new float[RUNNERS], rr = new float[RUNNERS], rspeed = new float[RUNNERS], rph = new float[RUNNERS], rwob = new float[RUNNERS];
    private Bitmap dropDay, dropNight;
    private final Path trail = new Path();
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The look at one moment. */
    static final class Look {
        int sky0, sky1, far, near, sun0, sun1;
        boolean moon, fog;
        float sunA, sx, sy, sr, night, stars, mist;
        int clouds;
    }

    private final WeatherSky helper;   // its mist and lightning
    private int w, h;
    private float k = 1;
    private final Look look = new Look();
    private static final Look scratch = new Look();   // night(), on the main thread
    private static final float[] DEPTH = {FAR, MID, NEAR};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint rainNear = new Paint(Paint.ANTI_ALIAS_FLAG), rainFar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Path disc = new Path(), band = new Path();
    private float[] stars = new float[0];
    private final float[] cloudY = new float[8], cloudLen = new float[8], cloudTh = new float[8], cloudDur = new float[8], cloudPh = new float[8];
    private static final int DROPS = 230;
    private final float[] dropX = new float[DROPS], dropPhase = new float[DROPS], dropLen = new float[DROPS], dropSpeed = new float[DROPS];
    private final boolean[] dropNear = new boolean[DROPS];
    private final float[] nearLines = new float[DROPS * 4], farLines = new float[DROPS * 4];

    // The sky and sun shaders, rebuilt each minute of the day.
    private long shaderKey = -1;
    private LinearGradient skyGradient, sunGradient;
    private RadialGradient sunGlow;

    // The mountains (3 depths) and clouds (3 shapes) for a weather and time; the next set is drawn on a worker thread.
    private static final class Art {
        long key;
        float width, height;   // drawn size of the ridge bitmaps
        Bitmap[] ridges = new Bitmap[3], clouds = new Bitmap[3];
        void recycle() {
            for (Bitmap b : ridges) if (b != null) b.recycle();
            for (Bitmap b : clouds) if (b != null) b.recycle();
        }
    }
    private Art art, fading;
    private long fadeAt;
    private final Object lock = new Object();
    private Art ready;                              // guarded by lock
    private volatile long buildingKey = -1;

    InkSky(WeatherSky helper) {
        this.helper = helper;
        fill.setStyle(Paint.Style.FILL);
        rainNear.setStrokeCap(Paint.Cap.ROUND);
        rainFar.setStrokeCap(Paint.Cap.ROUND);
        Random r = new Random(5);
        for (int i = 0; i < 8; i++) {
            cloudY[i] = CLOUD_Y[i] + r.nextFloat() * 30;
            cloudLen[i] = 0.85f + r.nextFloat() * 0.7f;
            cloudTh[i] = 0.6f + r.nextFloat() * 0.7f;
            cloudDur[i] = 160 + r.nextFloat() * 120;
            cloudPh[i] = r.nextFloat();
        }
        Random g = new Random(77);
        for (int i = 0; i < GLASS_DROPS; i++) {
            float u = g.nextFloat();
            gr[i] = u < 0.5f ? 3 + g.nextFloat() * 3.5f : u < 0.88f ? 6.5f + g.nextFloat() * 6 : 12.5f + g.nextFloat() * 10;
            gx[i] = g.nextFloat() * 1920;
            gy[i] = g.nextFloat() * 1080;
            gsq[i] = 0.92f + g.nextFloat() * 0.22f;
        }
        for (int i = 0; i < RUNNERS; i++) {
            rx[i] = 60 + g.nextFloat() * 1800;
            rr[i] = 8 + g.nextFloat() * 6;
            rspeed[i] = 35 + g.nextFloat() * 80;
            rph[i] = g.nextFloat();
            rwob[i] = g.nextFloat() * 6;
        }
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setSize(int width, int height) {
        if (width == w && height == h) return;
        w = width;
        h = height;
        k = w / 1920f;
        shaderKey = -1;
        if (art != null) art.recycle();
        if (fading != null) fading.recycle();
        art = fading = null;
        Random r = new Random(5);
        stars = new float[70 * 4];
        for (int i = 0; i < stars.length; i += 4) {
            stars[i] = r.nextFloat() * w;
            stars[i + 1] = r.nextFloat() * h * 0.42f;
            stars[i + 2] = 0.6f + r.nextFloat() * 0.9f;
            stars[i + 3] = r.nextFloat() * 6;
        }
        Random d = new Random(29);
        for (int i = 0; i < DROPS; i++) {
            dropNear[i] = d.nextFloat() < 0.3f;
            dropX[i] = d.nextFloat() * (w + 300 * k);
            dropPhase[i] = d.nextFloat();
            dropLen[i] = ((dropNear[i] ? 90 : 50) + d.nextFloat() * (dropNear[i] ? 60 : 40)) * k;
            dropSpeed[i] = (dropNear[i] ? 2600 : 1600) * (0.85f + d.nextFloat() * 0.3f) * k;
        }
        rainNear.setStrokeWidth(Math.max(0.8f, 1.8f * k));
        rainFar.setStrokeWidth(Math.max(0.8f, 1.2f * k));
    }

    /** Frees the mountains and clouds (the home screen going away, e.g. for the screensaver); drawn again when needed. */
    void release() {
        if (art != null) art.recycle();
        if (fading != null) fading.recycle();
        art = fading = null;
        synchronized (lock) {
            if (ready != null) ready.recycle();
            ready = null;
        }
    }

    // MARK: - Time of day

    /** The hour on this wallpaper's fixed day for a real hour, given today's real sunrise and sunset (hours). */
    static float canonical(float hour, float rise, float set) {
        if (!(set > rise + 6)) return hour;
        float c;
        if (hour >= rise && hour <= set) c = SUNRISE + (hour - rise) / (set - rise) * (SUNSET - SUNRISE);
        else if (hour > set) c = SUNSET + (hour - set);
        else c = SUNRISE - (rise - hour);
        return ((c % 24) + 24) % 24;
    }

    /** How much night (0 by day, 1 at night) at this hour of the fixed day. */
    static float night(float hour) {
        time(hour, scratch);
        return scratch.night;
    }

    /** The clear sky at an hour, eased between the keyframes. */
    private static void time(float hour, Look out) {
        hour = ((hour % 24) + 24) % 24;
        int i = 0;
        while (HOURS[i + 1] <= hour) i++;
        float t = (hour - HOURS[i]) / (HOURS[i + 1] - HOURS[i]), e = t * t * (3 - 2 * t);
        int[] a = COLORS[i], b = COLORS[i + 1];
        float[] va = VALUES[i], vb = VALUES[i + 1];
        out.sky0 = blend(a[0], b[0], e);
        out.sky1 = blend(a[1], b[1], e);
        out.far = blend(a[2], b[2], e);
        out.near = blend(a[3], b[3], e);
        boolean sunA = a[4] != 0, sunB = b[4] != 0, sameKind = va[0] == vb[0];
        boolean useA = sunA && sunB ? e < 0.5f : sunA;
        if (sunA && sunB && sameKind) {
            out.sun0 = blend(a[4], b[4], e);
            out.sun1 = blend(a[5], b[5], e);
        } else {
            out.sun0 = useA ? a[4] : b[4];
            out.sun1 = useA ? a[5] : b[5];
        }
        out.moon = (useA ? va[0] : vb[0]) > 0.5f;
        out.sunA = sunA && sunB && !sameKind ? Math.abs(1 - 2 * e) * (e < 0.5f ? va[1] : vb[1]) : va[1] + (vb[1] - va[1]) * e;
        out.sx = va[2] + (vb[2] - va[2]) * e;
        out.sy = va[3] + (vb[3] - va[3]) * e;
        out.sr = va[4] + (vb[4] - va[4]) * e;
        out.night = va[5] + (vb[5] - va[5]) * e;
        out.mist = va[6] + (vb[6] - va[6]) * e;
    }

    /** The look for a weather (WeatherSky kind) at an hour: the time's sky, tinted toward that weather. */
    private static void look(int kind, float hour, Look out) {
        time(hour, out);
        float n = out.night, s = TINT[kind];
        int[] d = DAY[kind], q = NIGHT[kind];
        out.sky0 = blend(out.sky0, blend(d[0], q[0], n), s);
        out.sky1 = blend(out.sky1, blend(d[1], q[1], n), s);
        out.far = blend(out.far, blend(d[2], q[2], n), s);
        out.near = blend(out.near, blend(d[3], q[3], n), s);
        if (out.sun0 != 0 && !out.moon && (kind == CLOUDY || kind == FOG)) {
            out.sun0 = blend(out.sun0, 0xFFF3E8D0, 0.75f);
            out.sun1 = blend(out.sun1, 0xFFEDE4D2, 0.6f);
        }
        out.sunA *= n >= 0.5f ? NIGHT_SUN[kind] : DAY_SUN[kind];
        out.stars = n > 0.6f && kind <= PARTLY ? (n - 0.6f) / 0.4f : 0;
        out.clouds = CLOUDS[kind];
        out.fog = kind == FOG;
        if (kind > PARTLY) out.mist = 1;
    }

    /** The color the glass (shelf, drawer) should take: the misty mountains behind it. */
    int glassColor(int code, float hour) {
        Look l = new Look();
        look(WeatherSky.kind(code), hour, l);
        return blend(blend(l.far, l.near, 0.45f), l.sky1, 0.35f);
    }

    // MARK: - Drawing

    /** Draws the wallpaper for a weather code at an hour of the fixed day; `parallax` -1..1; without `motion`, still. */
    /**
     * Where the sun or the moon is in the last drawn sky (px), for the glass's light; false without one (cloudy days
     * keep a faint sun, which still counts).
     */
    boolean light(float[] out) {
        if (look.sun0 == 0 || look.sunA <= 0.02f) return false;
        out[0] = look.sx * k;
        out[1] = look.sy * k;
        return true;
    }

    void draw(Canvas c, int code, float hour, long ms, float parallax, boolean motion) {
        if (w == 0) return;
        // Drizzle: the cloudy sky, with just a sprinkle of small drops on the glass (no rain falling outside)
        boolean drizzle = code >= 51 && code <= 57;
        int kind = drizzle ? CLOUDY : WeatherSky.kind(code);
        float t = motion ? (ms % 3_600_000L) / 1000f : 0;
        look(kind, hour, look);
        boolean night = look.night >= 0.5f;

        // The mountains and clouds: the set for this weather and 6-minute step, drawn on a worker thread.
        long key = w * 1_000_000L + kind * 1000L + Math.round(hour * 10);
        takeReady();
        if (art == null) {
            art = build(key, kind, hour);   // the first time, straight away
        } else if (art.key != key && buildingKey != key) {
            buildingKey = key;
            final int bk = kind;
            final float bh = hour;
            new Thread(() -> {
                Art a = build(key, bk, bh), old;
                synchronized (lock) {
                    old = ready;   // never taken by the main thread (it takes and clears under the lock)
                    ready = a;
                }
                if (old != null) old.recycle();
                buildingKey = -1;
            }, "InkSky").start();
        }

        // Sky
        long sk = kind * 10000L + Math.round(hour * 60);
        if (sk != shaderKey) {
            shaderKey = sk;
            skyGradient = new LinearGradient(0, 0, 0, h * 0.62f, look.sky0, look.sky1, Shader.TileMode.CLAMP);
            if (look.sun0 != 0) {
                float x = look.sx * k, y = look.sy * k, r = look.sr * k;
                sunGlow = new RadialGradient(x, y, r * 3, new int[] {Ui.alpha(look.sun0, look.moon ? 0.2f : 0.16f), Ui.alpha(look.sun0, look.moon ? 0.2f : 0.16f), look.sun0 & 0x00FFFFFF},
                        new float[] {0, 0.27f, 1}, Shader.TileMode.CLAMP);
                sunGradient = new LinearGradient(0, y - r, 0, y + r, new int[] {look.sun0, blend(look.sun0, look.sun1, 0.3f),
                        blend(look.sun1, look.sky1, look.moon ? 0 : 0.25f)}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
            }
        }
        fill.setShader(skyGradient);
        fill.setAlpha(255);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        float far = -parallax * FAR * k;
        if (look.stars > 0.01f) {
            for (int i = 0; i < stars.length; i += 4) {
                float a = look.stars * (0.35f + 0.45f * (0.5f + 0.5f * (float) Math.sin(t * 1.3f + stars[i + 3])));
                fill.setColor(Ui.alpha(0xFFF0E8D2, a));
                c.drawCircle(stars[i] + far, stars[i + 1], stars[i + 2] * 1.4f * k, fill);
            }
        }
        if (look.sun0 != 0 && look.sunA > 0.01f) drawDisc(c, far, t);

        // Soft cloud washes: partly cloudy keeps a fixed composition swaying in place; cloudier skies drift across
        if (look.clouds > 0 && art.clouds[0] != null) {
            for (int i = 0; i < look.clouds; i++) {
                float x, y, width, a = 1;
                if (kind == PARTLY && i < ANCHORS.length) {
                    float[] an = ANCHORS[i];
                    width = CLOUD_W * k * an[2];
                    a = an[3];
                    x = (an[0] + (float) Math.sin(t * 2 * Math.PI / (140 + i * 35) + i * 1.7) * 90) * k - parallax * MID * k * 0.6f;
                    y = an[1] * k;
                } else {
                    width = CLOUD_W * k * cloudLen[i] * (kind >= CLOUDY ? 1.3f : 1);
                    x = (float) (((t / cloudDur[i] + cloudPh[i]) % 1.0) * (w + width)) - width - parallax * MID * k * 0.6f;
                    y = (cloudY[i] - 40) * k;
                }
                bitmapPaint.setColor(Ui.alpha(0xFF000000, a));
                dst.set(x, y, x + width, y + CLOUD_H * k * (width / (CLOUD_W * k)));
                c.drawBitmap(art.clouds[i % 3], null, dst, bitmapPaint);
            }
        }
        if (kind == STORM) helper.drawLightning(c, t, -parallax * MID * k);

        // Mountains (the previous set underneath while the new one fades in), with mist drifting between the depths
        float fade = 1;
        if (fading != null) {
            fade = Math.min(1, (SystemClock.uptimeMillis() - fadeAt) / 2000f);
            if (fade >= 1) {
                fading.recycle();
                fading = null;
            }
        }
        float[] depth = DEPTH;
        Bitmap mist = helper.mist();
        for (int i = 0; i < 3; i++) {
            float x = -MARGIN * k - parallax * depth[i] * k, y = TOP * k;
            if (fading != null) {
                bitmapPaint.setColor(0xFF000000);
                dst.set(x, y, x + fading.width, y + fading.height);
                c.drawBitmap(fading.ridges[i], null, dst, bitmapPaint);
            }
            bitmapPaint.setColor(Ui.alpha(0xFF000000, fade));
            dst.set(x, y, x + art.width, y + art.height);
            c.drawBitmap(art.ridges[i], null, dst, bitmapPaint);
            if (i == 2 && !look.fog && look.mist < 1.2f || mist == null) continue;
            float a = look.fog ? 0.9f : Math.min(0.95f, (night ? 0.35f : 0.5f) * look.mist);
            bitmapPaint.setColor(Ui.alpha(look.sky1, a));
            float drift = (float) Math.sin(t * 2 * Math.PI / (50 + i * 15) + i * 2) * 200 * k - 340 * k - parallax * depth[i] * k;
            c.drawBitmap(mist, drift, (440 + i * 110) * k, bitmapPaint);
        }

        if (drizzle) drawGlass(c, t, 0, night, -parallax * NEAR * k);
        if (kind == RAIN || kind == STORM) {
            drawRain(c, t, -parallax * NEAR * k, kind == STORM, night);   // light rain outside, faint
            drawGlass(c, t, kind == STORM ? 2 : 1, night, -parallax * NEAR * k);   // and drops on the glass in front
        }
    }

    /**
     * Raindrops on the glass: a faint veil, drops of all sizes, and big ones running down with a wet trail. `level`: 0
     * drizzle (a sprinkle of small drops, two running), 1 rain, 2 storm.
     */
    private void drawGlass(Canvas c, float t, int level, boolean night, float shift) {
        if (dropDay == null) {
            dropDay = drop(false);
            dropNight = drop(true);
        }
        Bitmap sprite = night ? dropNight : dropDay;
        fill.setShader(null);
        fill.setColor(night ? 0x2E141E30 : 0x29E1E8EE);
        c.drawRect(0, 0, w, h, fill);
        bitmapPaint.setColor(0xFF000000);
        float sx = shift * 0.15f;
        int count = level == 0 ? 130 : level == 1 ? 200 : 280;
        float small = level == 0 ? 0.7f : 1;
        fill.setColor(night ? 0x59D2DCF0 : 0x99FFFFFF);
        for (int i = 0; i < count; i++) {
            float r = gr[i] * k * small, x = gx[i] * k + sx, y = gy[i] * k;
            if (r < 1.4f) {
                c.drawRect(x, y, x + 1.5f, y + 1.5f, fill);
                continue;
            }
            dst.set(x - r * 1.19f, y - r * 1.19f * gsq[i], x + r * 1.19f, y + r * 1.19f * gsq[i]);
            c.drawBitmap(sprite, null, dst, bitmapPaint);
        }
        trailPaint.setColor(night ? 0x24C8D7F0 : 0x47FFFFFF);
        int runners = level == 0 ? 2 : level == 1 ? 4 : 6;
        for (int i = 0; i < runners; i++) {
            float span = 1080 + 400;
            float y = (float) (((t * rspeed[i] / span) + rph[i]) % 1.0) * span - 200, top = Math.max(-200, y - 520);
            trailPaint.setStrokeWidth(Math.max(1, rr[i] * 0.45f * k));
            trail.reset();
            for (float yy = top; yy <= y; yy += 12) {
                float px = (rx[i] + wobble(yy, rwob[i])) * k + sx, py = yy * k;
                if (yy == top) trail.moveTo(px, py);
                else trail.lineTo(px, py);
            }
            c.drawPath(trail, trailPaint);
            for (float yy = y - 40; yy > top; yy -= 46) {
                float br = (2 + Math.abs((float) Math.sin(yy * 1.7f + rwob[i])) * 2.5f) * k, px = (rx[i] + wobble(yy, rwob[i])) * k + sx;
                dst.set(px - br, yy * k - br, px + br, yy * k + br);
                c.drawBitmap(sprite, null, dst, bitmapPaint);
            }
            float r = rr[i] * k, px = (rx[i] + wobble(y, rwob[i])) * k + sx;
            dst.set(px - r * 1.15f, y * k - r * 1.35f, px + r * 1.15f, y * k + r * 1.35f);
            c.drawBitmap(sprite, null, dst, bitmapPaint);
        }
    }

    private static float wobble(float y, float w) {
        return (float) (Math.sin(y * 0.018 + w) * 5 + Math.sin(y * 0.051 + w * 2) * 2);
    }

    /** One drop, drawn once: a clear body darker at the top and lighter at the bottom, a shadow edge, a highlight. */
    private static Bitmap drop(boolean night) {
        int s = 128;
        float x = s / 2f, y = s / 2f, r = s * 0.42f;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, y - r, 0, y + r, new int[] {night ? 0x6B040812 : 0x421C2A3A,
                night ? 0x2E3C4B64 : 0x2EB4C8D7, night ? 0x80CDDCF5 : 0x99FFFFFF}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * 0.045f);
        p.setColor(night ? 0x73000000 : 0x4714202E);
        RectF o = new RectF(x - r + s * 0.02f, y - r + s * 0.02f, x + r - s * 0.02f, y + r - s * 0.02f);
        c.drawArc(o, 22, 140, false, p);
        p.setStrokeWidth(s * 0.025f);
        p.setColor(night ? 0x40DCE6FA : 0x8CFFFFFF);
        c.drawArc(o, 198, 90, false, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(night ? 0xE6F4F7FF : 0xF2FFFFFF);
        c.save();
        c.rotate(-34, x - r * 0.36f, y - r * 0.38f);
        c.drawOval(new RectF(x - r * 0.62f, y - r * 0.54f, x - r * 0.1f, y - r * 0.22f), p);
        c.restore();
        c.drawCircle(x + r * 0.3f, y + r * 0.45f, r * 0.07f, p);
        return b;
    }

    private void takeReady() {
        Art r;
        synchronized (lock) {
            r = ready;
            ready = null;
        }
        if (r == null) return;
        if (art != null && art.key == r.key) {
            r.recycle();
            return;
        }
        if (fading != null) fading.recycle();
        fading = art;
        art = r;
        fadeAt = SystemClock.uptimeMillis();
    }

    /** The sun or moon: a soft glow, the disc lit from the top, and a few wavy brush bands across its lower half. */
    private void drawDisc(Canvas c, float far, float t) {
        float x = look.sx * k + far, y = look.sy * k, r = look.sr * k, a = Math.min(1, look.sunA);
        c.save();
        c.translate(far, 0);
        fill.setShader(sunGlow);
        fill.setAlpha(Math.round(255 * a));
        c.drawCircle(look.sx * k, y, r * 3, fill);
        fill.setShader(sunGradient);
        c.drawCircle(look.sx * k, y, r, fill);
        fill.setShader(null);
        c.restore();
        disc.reset();
        disc.addCircle(x, y, r, Path.Direction.CW);
        c.save();
        c.clipPath(disc);
        fill.setColor(Ui.alpha(look.moon ? 0x14FFFAEB : 0x33FFF2E4, a));
        for (int i = 0; i < 4; i++) {
            float by = y + r * (0.02f + i * 0.22f);
            band.reset();
            band.moveTo(x - r, y + r * 1.2f);
            for (float xx = -r; xx <= r + 0.01f; xx += r / 12) band.lineTo(x + xx, by + (float) Math.sin(xx / r * 3 + i * 1.3f + t * 0.15f) * r * 0.06f);
            band.lineTo(x + r, y + r * 1.2f);
            band.close();
            c.drawPath(band, fill);
        }
        c.restore();
    }

    private void drawRain(Canvas c, float t, float shift, boolean heavy, boolean night) {
        int count = heavy ? DROPS : 150, nn = 0, nf = 0;
        float span = h + 300 * k, slant = 0.12f, wrap = w + 300 * k;
        for (int i = 0; i < count; i++) {
            float y = (float) (((dropPhase[i] + t * dropSpeed[i] * (heavy ? 1.25f : 1) / span) % 1.0) * span) - 200 * k;
            float x = dropX[i] - y * slant + shift;
            x = ((x % wrap) + wrap) % wrap - 150 * k;
            float[] lines = dropNear[i] ? nearLines : farLines;
            int at = dropNear[i] ? nn : nf;
            lines[at] = x;
            lines[at + 1] = y;
            lines[at + 2] = x - dropLen[i] * slant;
            lines[at + 3] = y + dropLen[i];
            if (dropNear[i]) nn += 4;
            else nf += 4;
        }
        rainFar.setColor(night ? 0x0FD2DCEB : 0x0E323E4A);   // faint: it's outside, behind the drops on the glass
        rainNear.setColor(night ? 0x1BD2DCEB : 0x18323E4A);
        c.drawLines(farLines, 0, nf, rainFar);
        c.drawLines(nearLines, 0, nn, rainNear);
    }

    // MARK: - Art (drawn off the main thread)

    private Art build(long key, int kind, float hour) {
        Look l = new Look();
        look(kind, Math.round(hour * 10) / 10f, l);
        Art a = new Art();
        a.key = key;
        boolean night = l.night >= 0.5f;
        float m = MARGIN * k;
        int bw = Math.round(w + 2 * m), bh = Math.max(1, Math.round(h - TOP * k));
        a.width = bw;
        a.height = bh;
        int mistColor = l.sky1;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(0.6f, 1.3f * k));
        for (int g = 0; g < 3; g++) {
            Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(bw * RES[g])), Math.max(1, Math.round(bh * RES[g])), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(RES[g], RES[g]);
            c.translate(0, -TOP * k);
            for (int i = g * 2; i < g * 2 + 2; i++) {
                float[] r = RIDGES[i];
                int crest = blend(l.far, l.near, (float) Math.pow(i / 5f, 1.15));
                if (l.fog) crest = blend(crest, mistColor, i < 4 ? 0.55f - i * 0.08f : 0.25f);
                Path ridge = new Path();
                ridge.moveTo(0, h);
                for (int x = -160; x <= 2080; x += 8) ridge.lineTo(x * k + m, ridgeY(x, r) * k);
                ridge.lineTo(bw, h);
                ridge.close();
                p.setShader(new LinearGradient(0, (r[0] - r[1] * 1.4f) * k, 0, (r[0] + 150) * k,
                        new int[] {crest, blend(crest, mistColor, 0.3f), blend(crest, mistColor, 0.6f)}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
                c.drawPath(ridge, p);
                // Contour lines flowing down from the crest
                c.save();
                c.clipPath(ridge);
                line.setColor(Ui.alpha(blend(crest, night ? 0xFF8899AA : 0xFF000000, night ? 0.25f : 0.3f), 0.4f));
                for (int j = 1; j <= 4; j++) {
                    float off = j * j * 4 + 6;
                    Path contour = new Path();
                    for (int x = -160; x <= 2080; x += 8) {
                        float y = (ridgeY(x + j * 15, r) + off + 7 * (float) Math.sin(x * 0.011 + j * 1.7 + i)) * k;
                        if (x == -160) contour.moveTo(x * k + m, y);
                        else contour.lineTo(x * k + m, y);
                    }
                    c.drawPath(contour, line);
                }
                c.restore();
            }
            a.ridges[g] = b;
        }
        if (l.clouds > 0) {
            int tone = night ? blend(l.sky1, l.far, 0.5f) : kind == STORM ? blend(l.sky0, l.near, 0.3f)
                    : kind >= CLOUDY ? blend(blend(l.sky1, 0xFFFFFFFF, 0.55f), l.far, 0.12f) : blend(l.sky1, 0xFFFFFFFF, 0.92f);
            // Low sun (morning, golden hour, sunset): the clouds melt into the sky's own warm colour, a touch lighter and
            // more see-through, instead of standing out white (or as a separate peach)
            float fade = 1;
            if (!night && kind != STORM && l.sunA > 0) {
                float low = Math.max(0, Math.min(1, (l.sy - 250) / 220f));
                if (low > 0) {
                    int skyLit = blend(blend(l.sky0, l.sky1, 0.45f), 0xFFFFFFFF, 0.14f);
                    tone = blend(tone, skyLit, (kind >= CLOUDY ? 0.5f : 0.9f) * low);
                    fade = 1 - (kind >= CLOUDY ? 0.15f : 0.35f) * low;
                }
            }
            for (int i = 0; i < 3; i++) a.clouds[i] = cloud(i, tone, fade);
        }
        return a;
    }

    private static float ridgeY(float x, float[] r) {
        float f = r[2], ph = r[3];
        double v = 0.5 * Math.sin(x * f + ph) + 0.25 * Math.sin(x * f * 2.1 + ph * 1.3) + 0.12 * Math.sin(x * f * 4.3 + ph * 0.7)
                + 0.06 * Math.sin(x * f * 8.7 + ph * 2.1);
        v += 0.45 * Math.pow(Math.max(0, Math.sin(x * f * 0.6 + ph * 0.4)), 3);
        // low on the left (under the clock), tall on the right
        return (float) (r[0] - r[1] * v * (0.45 + 0.85 * Math.min(1, Math.max(0, x / 1920))));
    }

    /** A soft cloud wash: a few overlapping ellipses blurred together once, like a watercolor wash (no outline). */
    private Bitmap cloud(int shape, int tone, float fade) {
        float sc = k * CLOUD_RES;
        int bw = Math.max(32, Math.round(CLOUD_W * sc)), bh = Math.max(8, Math.round(CLOUD_H * sc));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(sc, sc);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(60, BlurMaskFilter.Blur.NORMAL));   // in the 1100x300 box's units
        for (float[] e : WASHES[shape]) {
            p.setColor(Ui.alpha(tone | 0xFF000000, e[4] * fade));
            c.drawOval(new RectF(e[0] - e[2], e[1] - e[3], e[0] + e[2], e[1] + e[3]), p);
        }
        return b;
    }

    private static int blend(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }
}
