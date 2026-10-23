package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * A live sky for the current weather behind the home screen (config.properties weather_wallpaper=true): clear, partly
 * cloudy, cloudy, rain, thunderstorm or fog, by day or night (blended during the transition), with drifting clouds,
 * falling rain, lightning and twinkling stars. Three depths move at different speeds when the focus moves along the
 * shelf (parallax). Drawn with plain Canvas calls (no shaders), so it runs on Android 9 to 11 too: the clouds are
 * illustrated once into bitmaps (a shaded back row of puffs, a lit front row, a flat bottom) for the current kind of
 * sky, the haze is three ridges of hills with mist between them, the rain is one drawLines call, and nothing is
 * allocated while drawing.
 */
final class WeatherSky {
    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, RAIN = 3, STORM = 4, FOG = 5;
    private static final float FAR = 28, MID = 80, NEAR = 140;   // parallax travel (px at 1920) of each depth

    // Top, middle and bottom of the sky for each kind, by day and by night.
    private static final int[][] DAY = {
        {0xFF2F7FD4, 0xFF6AAEE8, 0xFFE6F0FA}, {0xFF4F91D2, 0xFF7FB2E4, 0xFFE4EDF7}, {0xFF7D8A9A, 0xFFA3AEBB, 0xFFE4E7EB},
        {0xFF46546A, 0xFF5F6E84, 0xFFAAB4C1}, {0xFF161C27, 0xFF232B39, 0xFF444D5D}, {0xFFB8C0C9, 0xFFCDD3D9, 0xFFECEEF0}};
    private static final int[][] NIGHT = {
        {0xFF040A18, 0xFF0B1730, 0xFF16274A}, {0xFF060D1D, 0xFF0E1A33, 0xFF18284A}, {0xFF0F1622, 0xFF18212F, 0xFF232D3D},
        {0xFF0A1222, 0xFF111C31, 0xFF0A0F1B}, {0xFF06090F, 0xFF0D121B, 0xFF141A25}, {0xFF161D29, 0xFF222A37, 0xFF2C3442}};
    private static final int[] CLOUD_DAY = {0xF2FFFFFF, 0xF2FFFFFF, 0xEBECF0F5, 0xEBAAB4C2, 0xF2404858, 0xCCF0F2F5};
    private static final int[] CLOUD_NIGHT = {0x80506080, 0x9E546484, 0xD93A4458, 0xD9343E52, 0xEB1E2430, 0x99465060};

    /** One cloud: height (px at 1920), size, seconds to cross the screen, starting point (0..1), shape. */
    private static final float[][] SOME = {{50, 1.0f, 140, 0.00f, 0}, {260, 0.7f, 200, 0.30f, 1}, {430, 0.85f, 170, 0.62f, 0}, {620, 0.6f, 230, 0.85f, 1}};
    private static final float[][] MANY = {{-40, 1.5f, 120, 0.00f, 0}, {120, 1.25f, 160, 0.22f, 1}, {300, 1.4f, 140, 0.48f, 0},
        {470, 1.1f, 190, 0.70f, 1}, {620, 1.3f, 170, 0.36f, 0}, {760, 1.2f, 210, 0.88f, 1}};

    // Cloud shapes in a 640x240 box: the back and front rows of puffs {x, y, r}, then the flat base line, its left and right.
    private static final float[][][] BACK = {
        {{250, 120, 78}, {370, 100, 92}, {470, 135, 62}},
        {{210, 125, 66}, {320, 95, 88}, {430, 118, 72}},
        {{300, 110, 74}, {410, 125, 60}}};
    private static final float[][][] FRONT = {
        {{150, 160, 52}, {245, 135, 70}, {350, 128, 80}, {455, 155, 58}, {530, 172, 36}},
        {{130, 168, 42}, {215, 145, 62}, {310, 130, 74}, {410, 150, 62}, {500, 170, 40}},
        {{190, 160, 50}, {280, 140, 64}, {380, 150, 58}, {460, 172, 38}}};
    private static final float[][] BASE = {{205, 110, 560}, {205, 95, 535}, {205, 150, 495}};
    // Haze: three ridges of hills, far to near: base line, height, wave, phase, how much they stand out of the haze.
    private static final float[][] RIDGES = {{600, 70, 0.0030f, 1.3f, 0.34f}, {690, 58, 0.0046f, 4.1f, 0.56f}, {790, 46, 0.0062f, 2.2f, 0.8f}};
    private static final float[] RIDGE_DEPTH = {FAR, MID, NEAR};

    private int w, h;
    private float k = 1;
    private int shaderKind = -1;
    private LinearGradient dayGradient, nightGradient, washDay, washDark;
    private RadialGradient sunGlow, moonGlow;
    private final Bitmap[] cloudDay = new Bitmap[3], cloudNight = new Bitmap[3];
    private int cloudKind = -1;
    private Bitmap mist;
    private final Path[] ridges = new Path[3];
    private LinearGradient[] ridgeHazeDay = new LinearGradient[3], ridgeHazeNight = new LinearGradient[3];
    private RadialGradient hazeGlowDay, hazeGlowNight;
    private final Paint fill = new Paint(), bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG), rainNear = new Paint(Paint.ANTI_ALIAS_FLAG), rainFar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG), boltGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Path moon = new Path(), bolt1 = new Path(), bolt2 = new Path();
    private float[] stars = new float[0], twinkle = new float[0];
    // Rain: per drop its x, starting point, length, speed (px/s) and whether it's near (longer, brighter, faster).
    private static final int DROPS = 230;
    private final float[] dropX = new float[DROPS], dropPhase = new float[DROPS], dropLen = new float[DROPS], dropSpeed = new float[DROPS];
    private final boolean[] dropNear = new boolean[DROPS];
    private final float[] nearLines = new float[DROPS * 4], farLines = new float[DROPS * 4];

    WeatherSky() {
        fill.setStyle(Paint.Style.FILL);
        dots.setStrokeCap(Paint.Cap.ROUND);
        rainNear.setStrokeCap(Paint.Cap.ROUND);
        rainFar.setStrokeCap(Paint.Cap.ROUND);
        bolt.setStyle(Paint.Style.STROKE);
        bolt.setStrokeJoin(Paint.Join.ROUND);
        bolt.setStrokeCap(Paint.Cap.ROUND);
        boltGlow.setStyle(Paint.Style.STROKE);
        boltGlow.setStrokeJoin(Paint.Join.ROUND);
    }

    /** The kind of sky for an Open-Meteo (WMO) weather code; unknown (no data yet) reads as partly cloudy. */
    static int kind(int code) {
        if (code < 0) return PARTLY;
        if (code <= 1) return CLEAR;
        if (code == 2) return PARTLY;
        if (code == 45 || code == 48) return FOG;
        if (code >= 95) return STORM;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return RAIN;
        return CLOUDY;   // overcast, and snow (drawn as a heavy grey sky)
    }

    /** Rain and storms darken the sky enough that the home screen switches to its night colors (white text). */
    static boolean dark(int code) {
        int kind = kind(code);
        return kind == RAIN || kind == STORM;
    }

    void setSize(int width, int height) {
        if (width == w && height == h) return;
        w = width;
        h = height;
        k = w / 1920f;
        shaderKind = -1;
        cloudKind = -1;
        makeHaze();
        makeStars();
        makeRain();
        makeBolts();
        float r = 64 * k;
        moon.reset();
        moon.addCircle(1150 * k, 150 * k, r, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(1150 * k + r * 0.45f, 150 * k - r * 0.25f, r * 0.9f, Path.Direction.CW);
        moon.op(bite, Path.Op.DIFFERENCE);
        sunGlow = new RadialGradient(1150 * k, 150 * k, 520 * k, new int[] {0xF0FFF4D6, 0x66FFE4B0, 0x00FFE4B0}, new float[] {0, 0.35f, 1}, Shader.TileMode.CLAMP);
        moonGlow = new RadialGradient(1150 * k, 150 * k, 340 * k, new int[] {0x55BED2FF, 0x00BED2FF}, null, Shader.TileMode.CLAMP);
        washDay = new LinearGradient(0, h * 0.55f, 0, h, 0x00F0F4FA, 0x8CF0F4FA, Shader.TileMode.CLAMP);
        washDark = new LinearGradient(0, 0, 0, h, new int[] {0x40040810, 0x00040810, 0x00040810, 0x73040810}, new float[] {0, 0.3f, 0.6f, 1}, Shader.TileMode.CLAMP);
        rainNear.setStrokeWidth(Math.max(1, 3 * k));
        rainFar.setStrokeWidth(Math.max(1, 2 * k));
        dots.setStrokeWidth(Math.max(1, 2.5f * k));
        bolt.setStrokeWidth(6 * k);
        boltGlow.setStrokeWidth(18 * k);
        boltGlow.setMaskFilter(new BlurMaskFilter(16 * k, BlurMaskFilter.Blur.NORMAL));
    }

    /** The clouds for this kind of sky, once by day and once by night (the two fade into each other at dusk). */
    private void makeClouds(int kind) {
        if (kind == cloudKind) return;
        cloudKind = kind;
        for (int i = 0; i < 3; i++) {
            if (cloudDay[i] != null) cloudDay[i].recycle();
            if (cloudNight[i] != null) cloudNight[i].recycle();
            cloudDay[i] = cloudArt(i, CLOUD_DAY[kind], DAY[kind][0]);
            cloudNight[i] = cloudArt(i, CLOUD_NIGHT[kind], NIGHT[kind][0]);
        }
    }

    /**
     * One illustrated cloud: a back row of puffs in the shade color, the front row lit from the top (a highlight on
     * each puff, shading toward its flat bottom), with a soft shadow between the two rows. Opaque; drawn with the
     * cloud color's alpha.
     */
    private Bitmap cloudArt(int shape, int color, int skyTop) {
        int bw = Math.max(64, Math.round(640 * k)), bh = Math.max(24, Math.round(240 * k));
        float s = bw / 640f, base = BASE[shape][0], left = BASE[shape][1], right = BASE[shape][2];
        int light = color | 0xFF000000, shade = blend(light, skyTop | 0xFF000000, 0.42f);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.clipRect(0, 0, bw, base * s);
        Path back = puffs(BACK[shape], base - 8, left + 40, right - 40, s, 0);
        Path front = puffs(FRONT[shape], base, left, right, s, 0);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 30 * s, 0, base * s, blend(light, shade, 0.3f), shade, Shader.TileMode.CLAMP));
        c.drawPath(back, p);
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Ui.alpha(shade, 0.6f));
        shadow.setMaskFilter(new BlurMaskFilter(Math.max(1, 14 * s), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(puffs(FRONT[shape], base, left, right, s, -2), shadow);
        // The front row in its own layer, so the light and shade land only on it.
        c.saveLayer(0, 0, bw, bh, null);
        p.setShader(new LinearGradient(0, 70 * s, 0, base * s, new int[] {light, light, blend(light, shade, 0.55f)},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawPath(front, p);
        Paint atop = new Paint(Paint.ANTI_ALIAS_FLAG);
        atop.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        int hi = blend(light, 0xFFFFFFFF, 0.75f);
        float lum = (((light >> 16) & 255) + ((light >> 8) & 255) + (light & 255)) / 765f, hiA = 0.2f + 0.4f * lum;
        for (float[] puff : FRONT[shape]) {
            float x = (puff[0] - puff[2] * 0.25f) * s, y = (puff[1] - puff[2] * 0.4f) * s;
            atop.setShader(new RadialGradient(x, y, puff[2] * 0.9f * s, Ui.alpha(hi, hiA), hi & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, atop);
        }
        atop.setShader(new LinearGradient(0, (base - 60) * s, 0, base * s, shade & 0x00FFFFFF, Ui.alpha(shade, 0.5f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, atop);
        c.restore();
        return b;
    }

    /** A row of puffs on a rounded flat base (one filled shape). */
    private static Path puffs(float[][] circles, float base, float left, float right, float s, float dy) {
        Path p = new Path();
        for (float[] circle : circles) p.addCircle(circle[0] * s, (circle[1] + dy) * s, circle[2] * s, Path.Direction.CW);
        p.addRoundRect(new RectF(left * s, (base - 46 + dy) * s, right * s, (base + dy) * s), 23 * s, 23 * s, Path.Direction.CW);
        return p;
    }

    /** Haze: the ridges of hills, the haze settling in front of each, the mist that drifts between them, the sun's glow. */
    private void makeHaze() {
        for (int i = 0; i < 3; i++) {
            float[] r = RIDGES[i];
            Path p = new Path();
            p.moveTo(-260 * k, h);
            for (int x = -260; x <= 2180; x += 16) {
                double y = r[0] + r[1] * (0.55 * Math.sin(x * r[2] + r[3]) + 0.3 * Math.sin(x * r[2] * 2.3 + r[3] * 1.7)
                        + 0.15 * Math.sin(x * r[2] * 5.1 + r[3] * 0.6));
                p.lineTo(x * k, (float) y * k);
            }
            p.lineTo(2180 * k, h);
            p.close();
            ridges[i] = p;
            ridgeHazeDay[i] = new LinearGradient(0, (r[0] - r[1]) * k, 0, (r[0] + 120) * k, 0x00ECEEF0, 0x66ECEEF0, Shader.TileMode.CLAMP);
            ridgeHazeNight[i] = new LinearGradient(0, (r[0] - r[1]) * k, 0, (r[0] + 120) * k, 0x002C3442, 0x662C3442, Shader.TileMode.CLAMP);
        }
        hazeGlowDay = new RadialGradient(1150 * k, 210 * k, 380 * k, 0x99FFF6E6, 0x00FFF6E6, Shader.TileMode.CLAMP);
        hazeGlowNight = new RadialGradient(1150 * k, 210 * k, 380 * k, 0x40C8D2E6, 0x00C8D2E6, Shader.TileMode.CLAMP);
        // The mist: long soft streaks, drawn once as an alpha mask and tinted when drawn.
        int mw = Math.max(64, Math.round(2600 * k)), mh = Math.max(8, Math.round(260 * k));
        Bitmap b = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, 40 * k), BlurMaskFilter.Blur.NORMAL));
        Random r = new Random(7);
        for (int i = 0; i < 18; i++) {
            p.setColor(Ui.alpha(0xFFFFFFFF, 0.45f + r.nextFloat() * 0.4f));
            float cx = r.nextFloat() * mw, cy = (110 + r.nextFloat() * 50) * k;
            float rx = (180 + r.nextFloat() * 260) * k, ry = (34 + r.nextFloat() * 36) * k;
            c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p);
        }
        if (mist != null) mist.recycle();
        mist = b.extractAlpha();
        b.recycle();
    }

    private void makeStars() {
        Random r = new Random(11);
        stars = new float[140 * 2];
        for (int i = 0; i < stars.length; i += 2) {
            stars[i] = r.nextFloat() * w;
            stars[i + 1] = r.nextFloat() * h * 0.6f;
        }
        twinkle = new float[40 * 2];
        for (int i = 0; i < twinkle.length; i += 2) {
            twinkle[i] = r.nextFloat() * w;
            twinkle[i + 1] = r.nextFloat() * h * 0.6f;
        }
    }

    private void makeRain() {
        Random r = new Random(29);
        for (int i = 0; i < DROPS; i++) {
            dropNear[i] = r.nextFloat() < 0.3f;
            dropX[i] = r.nextFloat() * (w + 300 * k);
            dropPhase[i] = r.nextFloat();
            dropLen[i] = ((dropNear[i] ? 90 : 50) + r.nextFloat() * (dropNear[i] ? 60 : 40)) * k;
            dropSpeed[i] = (dropNear[i] ? 2600 : 1600) * (0.85f + r.nextFloat() * 0.3f) * k;
        }
    }

    private void makeBolts() {
        bolt1.reset();
        float[] a = {150, 0, 118, 150, 162, 160, 96, 330, 140, 338, 60, 560};
        bolt1.moveTo((1180 + a[0]) * k, (a[1] - 20) * k);
        for (int i = 2; i < a.length; i += 2) bolt1.lineTo((1180 + a[i]) * k, (a[i + 1] - 20) * k);
        bolt1.moveTo((1180 + 118) * k, (150 - 20) * k);
        bolt1.lineTo((1180 + 70) * k, (230 - 20) * k);
        bolt2.reset();
        float[] b = {110, 0, 84, 120, 122, 128, 70, 270, 104, 276, 40, 440};
        bolt2.moveTo((330 + b[0]) * k, (b[1] - 10) * k);
        for (int i = 2; i < b.length; i += 2) bolt2.lineTo((330 + b[i]) * k, (b[i + 1] - 10) * k);
    }

    private void makeGradients(int kind) {
        if (kind == shaderKind) return;
        shaderKind = kind;
        dayGradient = new LinearGradient(0, 0, 0, h, DAY[kind], new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP);
        nightGradient = new LinearGradient(0, 0, 0, h, NIGHT[kind], new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
    }

    /** Whether this sky has anything that moves (everything but a still, cloudless day). */
    static boolean animated(int code) {
        return true;
    }

    /**
     * Draws the sky. `night` 0 = day, 1 = night; `ms` the animation clock; `parallax` -1 (first app on the shelf) to 1
     * (last one); without `motion`, a still frame.
     */
    void draw(Canvas c, int code, float night, long ms, float parallax, boolean motion) {
        if (w == 0) return;
        int kind = kind(code);
        makeGradients(kind);
        float t = motion ? (ms % 3_600_000L) / 1000f : 0;
        float day = 1 - night;

        // Sky
        fill.setAlpha(255);
        if (day > 0.001f) {
            fill.setShader(dayGradient);
            c.drawRect(0, 0, w, h, fill);
        }
        if (night > 0.001f) {
            fill.setShader(nightGradient);
            fill.setAlpha(Math.round(255 * night));
            c.drawRect(0, 0, w, h, fill);
            fill.setAlpha(255);
        }
        fill.setShader(null);

        // Far: sun, moon and stars
        float far = -parallax * FAR * k;
        c.save();
        c.translate(far, -Math.abs(parallax) * 4 * k);
        if (kind == CLEAR || kind == PARTLY) {
            if (day > 0.001f) {
                float breathe = 0.92f + 0.08f * (float) Math.sin(t * 2 * Math.PI / 9);
                fill.setShader(sunGlow);
                fill.setAlpha(Math.round(255 * day * breathe * (kind == CLEAR ? 1 : 0.85f)));
                c.drawCircle(1150 * k, 150 * k, 520 * k, fill);
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFF3D6, day * (kind == CLEAR ? 1 : 0.85f)));
                c.drawCircle(1150 * k, 150 * k, 62 * k, fill);
            }
            if (night > 0.001f) {
                dots.setColor(Ui.alpha(0xFFEBF2FF, night * 0.75f));
                c.drawPoints(stars, dots);
                float tw = 0.25f + 0.75f * (0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 4.5));
                dots.setColor(Ui.alpha(0xFFEBF2FF, night * tw));
                c.drawPoints(twinkle, dots);
                fill.setShader(moonGlow);
                fill.setAlpha(Math.round(255 * night));
                c.drawCircle(1150 * k, 150 * k, 340 * k, fill);
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFE8EEFB, night * (kind == CLEAR ? 1 : 0.8f)));
                c.drawPath(moon, fill);
            }
        }
        c.restore();

        // Middle: clouds, fog and lightning
        float mid = -parallax * MID * k;
        float[][] layout = kind == CLEAR || kind == FOG ? null : kind == PARTLY ? SOME : MANY;
        if (layout != null) {
            makeClouds(kind);
            float dayAlpha = (CLOUD_DAY[kind] >>> 24) / 255f * day, nightAlpha = (CLOUD_NIGHT[kind] >>> 24) / 255f * night;
            float cw = 640 * k, ch = 240 * k;
            for (int i = 0; i < layout.length; i++) {
                float[] cloud = layout[i];
                float scale = cloud[1], width = cw * scale, travel = w + width;
                float x = (float) (((t / cloud[2] + cloud[3]) % 1.0) * travel) - width + mid;
                float y = cloud[0] * k;
                dst.set(x, y, x + width, y + ch * scale);
                if (dayAlpha > 0.004f) {
                    bitmapPaint.setColor(Ui.alpha(0xFF000000, dayAlpha));
                    c.drawBitmap(cloudDay[i % 3], null, dst, bitmapPaint);
                }
                if (nightAlpha > 0.004f) {
                    bitmapPaint.setColor(Ui.alpha(0xFF000000, nightAlpha));
                    c.drawBitmap(cloudNight[i % 3], null, dst, bitmapPaint);
                }
            }
        }
        if (kind == FOG) drawHaze(c, t, night, parallax);
        if (kind == STORM) drawLightning(c, t, mid);

        // Near: rain
        if (kind == RAIN || kind == STORM) drawRain(c, t, -parallax * NEAR * k, kind == STORM, night);

        // Wash: lighter at the bottom by day (the shelf reads on it), darker top and bottom at night and in storms.
        boolean dim = night > 0.5f || kind == STORM || kind == RAIN;
        fill.setShader(dim ? washDark : washDay);
        fill.setAlpha(255);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
    }

    /** The mist (an alpha mask, tinted by the paint's color), for the ink wallpaper too. */
    Bitmap mist() { return mist; }

    private void drawHaze(Canvas c, float t, float night, float parallax) {
        float day = 1 - night;
        // The sun (or the moon) as a soft flat disc behind the haze
        c.save();
        c.translate(-parallax * FAR * k, 0);
        fill.setShader(hazeGlowDay);
        fill.setAlpha(Math.round(255 * day));
        if (day > 0.004f) c.drawCircle(1150 * k, 210 * k, 380 * k, fill);
        fill.setShader(hazeGlowNight);
        fill.setAlpha(Math.round(255 * night));
        if (night > 0.004f) c.drawCircle(1150 * k, 210 * k, 380 * k, fill);
        fill.setShader(null);
        fill.setColor(blend(0xB3FFFAF0, 0x59D8E0EE, night));
        c.drawCircle(1150 * k, 210 * k, 70 * k, fill);
        c.restore();
        // Hills, far to near, each with haze settling in front of it and mist drifting over it
        int haze = blend(0xFFECEEF0, 0xFF2C3442, night), hill = blend(0xFF7E8C9A, 0xFF0B1018, night);
        for (int i = 0; i < 3; i++) {
            float[] r = RIDGES[i];
            float shift = -parallax * RIDGE_DEPTH[i] * k;
            c.save();
            c.translate(shift, 0);
            fill.setColor(blend(haze, hill, r[4]));
            c.drawPath(ridges[i], fill);
            c.restore();
            fill.setShader(night < 0.5f ? ridgeHazeDay[i] : ridgeHazeNight[i]);
            fill.setAlpha(255);
            c.drawRect(0, (r[0] - r[1]) * k, w, h, fill);
            fill.setShader(null);
            bitmapPaint.setColor(Ui.alpha(blend(0xFFFFFFFF, 0xFF5A6578, night), 0.55f - i * 0.1f));
            float drift = (float) Math.sin(t * 2 * Math.PI / (46 + i * 17) + i * 2) * 220 * k - 340 * k + shift;
            c.drawBitmap(mist, drift, (r[0] - 150 + i * 10) * k, bitmapPaint);
        }
        // A veil over everything
        fill.setColor(Ui.alpha(haze, day > 0.5f ? 0.18f : 0.12f));
        c.drawRect(0, 0, w, h, fill);
    }

    private void drawRain(Canvas c, float t, float shift, boolean heavy, float night) {
        int count = heavy ? DROPS : 150, nn = 0, nf = 0;
        float span = h + 300 * k, slant = 0.16f, wrap = w + 300 * k;
        for (int i = 0; i < count; i++) {
            float y = (float) (((dropPhase[i] + t * dropSpeed[i] * (heavy ? 1.25f : 1) / span) % 1.0) * span) - 200 * k;
            float x = dropX[i] - y * slant + shift;
            x = ((x % wrap) + wrap) % wrap - 150 * k;
            float len = dropLen[i];
            float[] lines = dropNear[i] ? nearLines : farLines;
            int at = dropNear[i] ? nn : nf;
            lines[at] = x;
            lines[at + 1] = y;
            lines[at + 2] = x - len * slant;
            lines[at + 3] = y + len;
            if (dropNear[i]) nn += 4;
            else nf += 4;
        }
        rainFar.setColor(blend(0x73EBF2FF, 0x4DCDDCFA, night));
        rainNear.setColor(blend(0xB3EBF2FF, 0x8CCDDCFA, night));
        c.drawLines(farLines, 0, nf, rainFar);
        c.drawLines(nearLines, 0, nn, rainNear);
        // Mist where the rain lands
        fill.setShader(null);
        fill.setColor(blend(0x26DCE4EE, 0x1E788CAF, night));
        c.drawRect(0, h * 0.7f, w, h, fill);
    }

    /** Two strikes in turn: the sky lights up from where each one lands, with its bolt. */
    void drawLightning(Canvas c, float t, float shift) {
        for (int s = 0; s < 2; s++) {
            float phase = (float) (((t + (s == 0 ? 0 : 3.9)) % 7.5) / 7.5);
            float flash = flash(phase);
            if (flash <= 0.01f) continue;
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFE8EEFF, flash * 0.5f));
            c.drawRect(0, 0, w, h, fill);
            c.save();
            c.translate(shift, 0);
            boltGlow.setColor(Ui.alpha(0xFFA0B9FF, flash));
            bolt.setColor(Ui.alpha(0xFFF4F7FF, flash));
            Path p = s == 0 ? bolt1 : bolt2;
            c.drawPath(p, boltGlow);
            c.drawPath(p, bolt);
            c.restore();
        }
    }

    /** The flash over one cycle: dark, a strike, a flicker, a second strike, dark again. */
    private static float flash(float p) {
        if (p < 0.86f || p > 0.92f) return 0;
        if (p < 0.87f) return (p - 0.86f) / 0.01f;
        if (p < 0.88f) return 1 - 0.9f * (p - 0.87f) / 0.01f;
        if (p < 0.895f) return 0.1f + 0.6f * (p - 0.88f) / 0.015f;
        return 0.7f * (1 - (p - 0.895f) / 0.025f);
    }

    /** The color the glass (shelf, drawer) should take: the sky behind it, near the bottom of the screen. */
    int glassColor(int code, float night) {
        int kind = kind(code);
        int dayColor = blend(DAY[kind][1], DAY[kind][2], 0.7f), nightColor = blend(NIGHT[kind][1], NIGHT[kind][2], 0.7f);
        return blend(dayColor, nightColor, night);
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
