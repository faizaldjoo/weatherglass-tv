package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, in the same style as the home screen:
 *  - the recommendations' photos in full screen, one every 30 s, with a slow zoom and pan (the "Ken Burns" effect of
 *    the Apple TV photos) and a crossfade between them; each photo is loaded 10% larger than the screen, so the zoom
 *    never upscales it;
 *  - top right, like the iPhone lock screen: the date with the weather, the big Liquid Glass clock (the photo shows
 *    through the digits, bent at the edges; Shaders.GLASS_TEXT) and the compact prices;
 *  - bottom left, the photo's caption: the time-of-day label ("TONIGHT"), the title, and genre · runtime · service.
 * Dark scrims only where there is text, stronger where the photo is bright. Any key exits (as on Apple TV).
 * Without photos (no TMDB key, or before the first fetch): the big clock over the night aurora.
 *
 * With the Ink mountains wallpaper: the same mountains, gliding on their own (the depths sway, a slow zoom), with the
 * clock top left, today's prayer times bottom left (the next one highlighted) and the time of day, city and weather
 * bottom right; the text drifts a few pixels over 10 minutes so nothing burns in.
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    private static final long SHOW_MS = 30_000, FADE_MS = 3_000, FIRST_MS = 1_600;
    private static final float ZOOM = 0.1f;
    /** Photos 10% larger than the screen (the zoom goes up to 1.1 without upscaling). */
    private static final int PW = 2112, PH = 1188;
    private static final float MARGIN = 80, RIGHT = 1840, TITLE_W = 900, META_BASE = 1008;
    private static final float DATE_BASE = 92, CLOCK_TOP = 120, CLOCK_SIZE = 200, QUOTES_GAP = 52;
    private static final double DRIFT_PERIOD_S = 600;

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner) and its caption. */
    private static final class Slide {
        final Recs.Pick pick;
        final Bitmap image, frost;
        final BitmapShader shader;    // the frosted version, for the clock's glass
        final float lightCaption, lightClock;
        final boolean zoomIn;
        final float panX, panY;
        final Recording caption = Recording.create("caption");
        long startedAt;

        Slide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock, int n) {
            this.pick = pick;
            this.image = image;
            this.frost = frost;
            shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Frost.filter(shader, BitmapShader.FILTER_MODE_LINEAR);
            this.lightCaption = lightCaption;
            this.lightClock = lightClock;
            zoomIn = n % 2 == 0;
            // Toward one of the corners, alternating, without repeating the previous one.
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    /** The glass clock's digits, rendered once a minute in the background. */
    private static final class Glass {
        final String text;
        final Bitmap slope, cover, shadow;
        final int x, y;
        final float inkTop, inkHeight;

        Glass(String text, Bitmap slope, Bitmap cover, Bitmap shadow, int x, int y, float inkTop, float inkHeight) {
            this.text = text;
            this.slope = slope;
            this.cover = cover;
            this.shadow = shadow;
            this.x = x;
            this.y = y;
            this.inkTop = inkTop;
            this.inkHeight = inkHeight;
        }
    }

    private final Scene scene = new Scene(false);
    private final HomeData data;
    // The Ink mountains screensaver
    private final WeatherSky inkHelper = new WeatherSky();
    private final InkSky ink = new InkSky(inkHelper);
    private Paint iWave, iDate, iClock, iLine, iSmall, iLabel, iName, iPName, iPNameOn, iPTime, iPTimeOn, iScrim, iBox;
    private RadialGradient[] scrimDark, scrimLight;
    private final long[] prayerAt = new long[5];
    private final String[] prayerText = new String[5];
    private int prayerNext = -1;
    private String prayerLine = "";
    private final RectF box = new RectF();
    private final Recs recs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recording content = Recording.create("clock");     // without photos: the clock over the aurora
    private final Recording chrome = Recording.create("corner");     // date, weather, prices and the clock's shadow
    private final float timeOffset = (float) (Math.random() * 300);
    private final Matrix matrix = new Matrix();
    private final float[] placeA = new float[3], placeB = new float[3];
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint black = new Paint();
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glassPaint = new Paint();
    private final Set<String> failed = new HashSet<>();     // Recs.key: a movie and a series can have the same ID
    private RuntimeShader glassShader;
    private boolean running, dirty = true, chromeDirty = true;
    private boolean fallback;       // the aurora with the clock (no photos)
    private boolean decoding;
    private int generation, slides;
    private Slide current, incoming, prepared;
    private Glass glass;
    private String glassFor = "";
    private long startedAt, firstAt, lastFrame, lastSky;
    private int w, h;
    private float k = 1;
    private long minute = -1;
    private String clock = "", date = "";
    private String greet = "", dateRest = "";   // the ink layout's greeting line, split around the waving hand
    private long waveAt = -1;
    private Paint datePaint, clockPaint, weatherPaint, weatherDim, quotePaint, stalePaint;
    private Paint cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, metaPaint;
    private TextPaint titlePaint;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            recs.prefetchAll();
            dirty = true;
            chromeDirty = true;
            prepareNext();
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        HomeData.loadRates();   // on Android 11 the screensaver's process missed any change made in the settings
        data = HomeData.get(c);
        recs = Recs.get(c);
        Shaders.dream(scene.palette);
        scene.setMotion(0.4f, 0.32f);
        scene.applyPalette();
        content.setUseCompositingLayer(true, null);
        try {
            glassShader = new RuntimeShader(Shaders.GLASS_TEXT);
            glassPaint.setShader(glassShader);
        } catch (RuntimeException | LinkageError e) {   // Android 9 to 12: no AGSL
            AppLog.w("glass clock unavailable: " + e.getMessage());
            glassShader = null;
        }
        black.setColor(0xFF000000);
        shadowPaint.setColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        startedAt = clock();
        lastFrame = 0;
        failed.clear();
        fallback = recs.showcase().isEmpty();
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (!inkMode() && !liteMode()) {
            recs.prefetchAll();
            prepareNext();
        }
        handler.postDelayed(tick, 60_000);
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        // The photos stay in GPU memory until the garbage collector runs: release them now (after the last frame).
        final java.util.Set<Bitmap> gone = new java.util.HashSet<>();
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s == null) continue;
            gone.add(s.image);
            gone.add(s.frost);
        }
        if (!gone.isEmpty()) handler.postDelayed(() -> { for (Bitmap b : gone) b.recycle(); }, 1000);
        current = incoming = prepared = null;
        ink.release();
        glass = null;
        slides = 0;
        glassFor = "";
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
    }

    @Override public void onData() {
        dirty = true;
        chromeDirty = true;
        invalidate();
    }

    @Override public void onRecs() {
        if (running) prepareNext();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        inkHelper.setSize(w, h);
        ink.setSize(w, h);
        iDate = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);   // the size of the rates
        iWave = Ui.text(Ui.medium, 28 * k, 0xFFFFFFFF);
        iClock = Ui.numbers(Ui.light, 230 * k, 0xFFFFFFFF);
        iLine = Ui.numbers(Ui.medium, 32 * k, 0xFFFFFFFF);
        iSmall = Ui.numbers(Ui.medium, 26 * k, 0xFFFFFFFF);
        iLabel = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        iLabel.setLetterSpacing(0.12f);
        iName = Ui.text(Ui.displaySemibold, 56 * k, 0xFFFFFFFF);
        iPName = Ui.text(Ui.medium, 26 * k, 0xFFFFFFFF);
        iPNameOn = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        iPTime = Ui.numbers(Ui.regular, 40 * k, 0xFFFFFFFF);
        iPTimeOn = Ui.numbers(Ui.semibold, 40 * k, 0xFFFFFFFF);
        iScrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        iBox = new Paint(Paint.ANTI_ALIAS_FLAG);
        float[][] at = {{330, 250, 640}, {1620, 980, 640}, {400, 990, 680}};
        int[] strength = {0x47, 0x6B, 0x5C};
        scrimDark = new RadialGradient[3];
        scrimLight = new RadialGradient[3];
        for (int i = 0; i < 3; i++) {
            scrimDark[i] = new RadialGradient(at[i][0] * k, at[i][1] * k, at[i][2] * k, strength[i] << 24, 0, Shader.TileMode.CLAMP);
            scrimLight[i] = new RadialGradient(at[i][0] * k, at[i][1] * k, at[i][2] * k, strength[i] << 24 | 0xFFFAF0, 0x00FFFAF0, Shader.TileMode.CLAMP);
        }
        scene.setSize(w, h);
        content.setPosition(0, 0, w, h);
        chrome.setPosition(0, 0, w, h);
        // Without photos (the clock over the aurora).
        datePaint = Ui.text(Ui.semibold, 36 * k, 0xD9FFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        clockPaint = Ui.numbers(Ui.thin, 280 * k, 0xF2FFFFFF);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setLetterSpacing(-0.01f);
        weatherPaint = Ui.numbers(Ui.medium, 30 * k, 0xC7FFFFFF);
        weatherDim = Ui.numbers(Ui.regular, 28 * k, 0x80FFFFFF);
        quotePaint = Ui.numbers(Ui.medium, 24 * k, 0x99FFFFFF);
        stalePaint = Ui.text(Ui.regular, 20 * k, 0x59FFFFFF);
        stalePaint.setTextAlign(Paint.Align.CENTER);
        // With photos: the corner (date, clock, prices) and the caption.
        cornerDate = Ui.numbers(Ui.semibold, 30 * k, 0xEBFFFFFF);
        cornerQuote = Ui.numbers(Ui.regular, 23 * k, 0xC7FFFFFF);
        cornerStale = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cornerClock = Ui.numbers(Ui.displaySemibold, CLOCK_SIZE * k, 0xF2FFFFFF);
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xB8FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xC7FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        for (Paint p : new Paint[] {cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, titlePaint, metaPaint}) {
            p.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        }
        glassFor = "";
        dirty = true;
        chromeDirty = true;
        for (Slide s : new Slide[] {current, incoming, prepared}) if (s != null) recordCaption(s);
    }

    // MARK: - Photos

    /** Loads the next photo in the background (one at a time); once ready, it comes in at the right moment. */
    private void prepareNext() {
        if (!running || decoding || prepared != null) return;
        List<Recs.Pick> list = recs.showcase();
        if (list.isEmpty()) return;
        Slide last = incoming != null ? incoming : current;
        int start = 0;
        if (last != null) {
            for (int i = 0; i < list.size(); i++) {
                if (Recs.key(list.get(i)).equals(Recs.key(last.pick))) {
                    start = i + 1;
                    break;
                }
            }
        }
        Recs.Pick pick = null;
        for (int i = 0; i < list.size() && pick == null; i++) {
            Recs.Pick p = list.get((start + i) % list.size());
            if (!failed.contains(Recs.key(p))) pick = p;
        }
        if (pick == null) {
            if (current == null) fallback = true;
            return;
        }
        if (last != null && Recs.key(pick).equals(Recs.key(last.pick))) {
            // Only one photo: the same one again, with a different motion.
            prepared = newSlide(pick, last.image, last.frost, last.lightCaption, last.lightClock);
            return;
        }
        File file = recs.readyImage(pick);
        if (file == null) {
            failed.add(Recs.key(pick));   // the image is gone from disk: try the next one
            prepareNext();
            return;
        }
        decoding = true;
        final int gen = generation;
        final Recs.Pick chosen = pick;
        worker.execute(() -> {
            Bitmap[] images = null;
            float[] light = new float[2];
            try {
                images = Backdrop.photo(file, PW, PH, light);
                images[0] = Backdrop.gpu(withScrims(images[0], light[0], light[1]));
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver: photo for “" + chosen.title + "”: " + e.getMessage());
            }
            final Bitmap[] ready = images;
            handler.post(() -> {
                if (gen != generation) {
                    // The screensaver closed while the photo was loading: free the memory right away.
                    if (ready != null) for (Bitmap b : ready) b.recycle();
                    return;
                }
                decoding = false;
                if (ready == null) {
                    failed.add(Recs.key(chosen));
                    prepareNext();
                    return;
                }
                prepared = newSlide(chosen, ready[0], ready[1], light[0], light[1]);
                invalidate();
            });
        });
    }

    private Slide newSlide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock) {
        Slide s = new Slide(pick, image, frost, lightCaption, lightClock, slides++);
        if (w > 0) recordCaption(s);
        return s;
    }

    /** The prepared photo goes on screen when its turn comes (the first one right away; the others every 30 s). */
    private void advance(long up) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.startedAt = up;
            firstAt = up;
            bindPhotos();
            prepareNext();
            return;
        }
        if (incoming == null && prepared != null && up >= current.startedAt + SHOW_MS) {
            incoming = prepared;
            prepared = null;
            incoming.startedAt = up;
            bindPhotos();
            return;
        }
        if (incoming != null && up >= incoming.startedAt + FADE_MS) {
            Slide old = current;
            current = incoming;
            incoming = null;
            bindPhotos();
            if (old.image != current.image) {
                // Once the last frame that uses them has been drawn.
                Bitmap gone = old.image, goneFrost = old.frost;
                handler.postDelayed(() -> {
                    gone.recycle();
                    goneFrost.recycle();
                }, 1000);
            }
            prepareNext();
        }
    }

    /** The photos the clock's glass shows (the current one and, during a crossfade, the incoming one). */
    private void bindPhotos() {
        if (glassShader == null || current == null) return;
        glassShader.setInputShader("imageA", current.shader);
        glassShader.setInputShader("imageB", incoming != null ? incoming.shader : current.shader);
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private void place(Slide s, long up, float[] out) {
        float u = Ui.clamp((up - s.startedAt) / (float) (SHOW_MS + FADE_MS));
        float f = s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        out[0] = rx * (s.panX * f - 1);
        out[1] = ry * (s.panY * f - 1);
        out[2] = (float) w / s.image.getWidth() * z;
    }

    private void drawPhoto(Canvas c, Slide s, float[] place, float alpha) {
        if (alpha <= 0.004f) return;
        matrix.setScale(place[2], place[2]);
        matrix.postTranslate(place[0], place[1]);
        photoPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    // MARK: - Frames: 20 per second, locked to every 3rd display frame (60 Hz)
    // Motion is slow (under half a pixel per frame). Measured on the TV: ~4 ms of GPU per frame of ours, but drawing
    // every frame, it only reaches ~50 fps (the system also composites the UI on the GPU); at 20 there's room to spare.

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        // The ink mountains drift slowly: 12 frames a second (rain falls: 20); the photos keep their 22. Lite draws
        // once a minute (onDraw redraws on the minute), and 30 frames a second only while the hand waves.
        long every = 45_000_000L;
        if (liteMode()) every = 60_000_000_000L;
        if (inkMode()) {
            int kind = WeatherSky.kind(inkCode());
            every = kind == WeatherSky.RAIN || kind == WeatherSky.STORM ? 50_000_000L : 83_000_000L;
        }
        // While the hand waves (ink layout), 30 frames a second
        if ((inkMode() || liteMode()) && waveAt >= 0) {
            long since = SystemClock.uptimeMillis() - waveAt;
            if (since >= 0 && since < Ui.WAVE_MS + 100) every = 33_000_000L;
        }
        if (frameTimeNanos - lastFrame >= every) {
            lastFrame = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** The clock that drives the animations. */
    private long clock() {
        long up = SystemClock.uptimeMillis();
        return up;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long up = clock();
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Ui.clock(now);
            date = Ui.greeting(now) + "  ·  " + Ui.date(now);   // "Good Evening, Haikal · Friday, 23 October"
            greet = Ui.greeting(now);
            dateRest = "  ·  " + Ui.date(now);
            if (waveAt < 0) waveAt = up + 1500;   // one wave, once the screensaver has faded in
            dirty = true;
            chromeDirty = true;
            if (inkMode() || liteMode()) refreshPrayers(now);
            liteStep++;
            if (liteMode()) invalidate();   // the new minute, drawn once
        }
        if (liteMode()) {
            drawLite(canvas, up);
            return;
        }
        if (inkMode()) {
            drawInk(canvas, now, up);
            return;
        }
        if (running) advance(up);
        if (!glassFor.equals(clock)) buildGlass(clock);

        float first = current == null ? 0 : fadeIn(up - firstAt, FIRST_MS);
        if (current == null || first < 1) {
            if (fallback) drawFallback(canvas, up, current == null ? 1 : 1 - first);
            else canvas.drawRect(0, 0, w, h, black);
        }
        if (current == null) return;
        if (fallback && first >= 1) fallback = false;

        place(current, up, placeA);
        float e = incoming == null ? 0 : fadeIn(up - incoming.startedAt, FADE_MS);
        drawPhoto(canvas, current, placeA, first);
        if (incoming != null) {
            place(incoming, up, placeB);
            drawPhoto(canvas, incoming, placeB, e);
        }

        if (chromeDirty) recordChrome();
        chrome.setAlpha(first);
        chrome.draw(canvas);
        drawGlass(canvas, first, e);
        drawCaptions(canvas, up);
    }

    private static float fadeIn(long elapsed, long duration) {
        float t = Ui.clamp(elapsed / (float) duration);
        return t * t * (3 - 2 * t);
    }

    /**
     * Black scrims where there is text, stronger where the photo is bright, painted once onto the photo itself (in the
     * background): each frame draws only the photo (the TV's GPU feels every full-screen layer). With the slow zoom,
     * they move a little along with the photo, which makes no difference to the text.
     */
    private static Bitmap withScrims(Bitmap photo, float lightCaption, float lightClock) {
        int w = photo.getWidth(), h = photo.getHeight();
        Canvas c = new Canvas(photo);
        Paint p = new Paint(Paint.DITHER_FLAG);
        float caption = scrimStrength(lightCaption, 0.5f), corner = scrimStrength(lightClock, 0.42f);
        p.setShader(new LinearGradient(0, h, 0, h * 0.42f,
                new int[] {black(0.55f * caption), black(0.33f * caption), black(0.11f * caption), 0},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.42f, w, h, p);
        RadialGradient around = new RadialGradient(0, h, w * 0.7f,
                new int[] {black(caption), black(0.55f * caption), black(0.15f * caption), 0},
                new float[] {0, 0.4f, 0.72f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, 0.62f, 0, h);
        around.setLocalMatrix(m);
        p.setShader(around);
        c.drawRect(0, h - w * 0.7f * 0.62f, w * 0.7f, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.3f, new int[] {black(0.3f), black(0.075f), 0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.3f, p);
        float cx = w * 0.86f, cy = h * 0.15f;
        RadialGradient clockSide = new RadialGradient(cx, cy, w * 0.36f,
                new int[] {black(corner), black(0.6f * corner), black(0.15f * corner), 0},
                new float[] {0, 0.42f, 0.75f, 1}, Shader.TileMode.CLAMP);
        Matrix mc = new Matrix();
        mc.setScale(1, 0.72f, cx, cy);
        clockSide.setLocalMatrix(mc);
        p.setShader(clockSide);
        c.drawRect(w * 0.5f, 0, w, cy + w * 0.36f * 0.72f, p);
        return photo;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    private static float scrimStrength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    // MARK: - Corner: date and weather, glass clock, prices

    private void recordChrome() {
        Canvas c = chrome.beginRecording(w, h);
        try {
            float right = RIGHT * k;
            float x = right;
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                String temp = HomeData.degrees(wx.temp);
                float tw = cornerDate.measureText(temp);
                c.drawText(temp, x - tw, DATE_BASE * k, cornerDate);
                x -= tw + 10 * k;
                String symbol = Symbols.weather(wx.code, wx.isDay);
                float size = 30 * k, sw = Symbols.width(getContext(), symbol, size);
                Symbols.draw(getContext(), c, symbol, x - sw / 2, DATE_BASE * k - Ui.halfCap(cornerDate), size, 0xEBFFFFFF);
                x -= sw + 26 * k;
            }
            c.drawText(date, x - cornerDate.measureText(date), DATE_BASE * k, cornerDate);

            Glass g = glass;
            if (g != null && glassShader != null) {
                // The shadow under the glass: sets the digits apart from a bright photo.
                shadowPaint.setAlpha(140);
                c.drawBitmap(g.shadow, g.x, g.y + 6 * k, shadowPaint);
            } else {
                Rect ink = new Rect();
                cornerClock.getTextBounds(clock, 0, clock.length(), ink);
                c.drawText(clock, right - ink.right, CLOCK_TOP * k - ink.top, cornerClock);
            }

            // Compact prices under the clock, right-aligned.
            float base = (CLOCK_TOP + Ui.cap(cornerClock) / k + QUOTES_GAP) * k;
            float cap = Ui.cap(cornerQuote), gap = 7 * k, spacing = 26 * k;
            int[] coins = HomeData.active();
            int n = HomeData.coinCount();
            String[] values = new String[n];
            float[] glyphs = new float[n];
            float total = spacing * (n - 1);
            for (int i = 0; i < n; i++) {
                values[i] = HomeData.compact(data.prices[coins[i]]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                total += glyphs[i] + gap + cornerQuote.measureText(values[i]);
            }
            float qx = right - total;
            for (int i = 0; i < n; i++) {
                Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], qx + glyphs[i] / 2, base - cap / 2, cap, 0xA6FFFFFF);
                qx += glyphs[i] + gap;
                c.drawText(values[i], qx, base, cornerQuote);
                qx += cornerQuote.measureText(values[i]) + spacing;
            }
            String stale = HomeData.staleness(data.pricesAt, HomeData.pricesEvery());
            if (!stale.isEmpty() && n > 0) c.drawText(stale, right - total - 24 * k - cornerStale.measureText(stale), base, cornerStale);
        } finally {
            chrome.endRecording();
        }
        chromeDirty = false;
    }

    private void drawGlass(Canvas c, float alpha, float e) {
        Glass g = glass;
        if (g == null || glassShader == null || alpha <= 0.004f) return;
        // The glass samples the frosted version (1/6 of the photo): same position, with the scale 6 times larger.
        float f = Backdrop.PHOTO_FROST;
        glassShader.setFloatUniform("placeA", placeA[0], placeA[1], placeA[2] * f);
        if (incoming != null) glassShader.setFloatUniform("placeB", placeB[0], placeB[1], placeB[2] * f);
        else glassShader.setFloatUniform("placeB", placeA[0], placeA[1], placeA[2] * f);
        glassShader.setFloatUniform("mixB", incoming == null ? 0 : e);
        glassShader.setFloatUniform("origin", g.x, g.y);
        glassShader.setFloatUniform("ink", g.inkTop, g.inkHeight);
        glassShader.setFloatUniform("refraction", 14 * k);
        glassShader.setFloatUniform("specular", 1);
        glassShader.setFloatUniform("alpha", alpha);
        c.drawRect(g.x, g.y, g.x + g.cover.getWidth(), g.y + g.cover.getHeight(), glassPaint);
    }

    /** Builds the glass clock's digits in the background (once a minute) and swaps them in when they're ready. */
    private void buildGlass(String text) {
        glassFor = text;
        if (glassShader == null || text.isEmpty()) {
            chromeDirty = true;
            return;
        }
        final Paint paint = new Paint(cornerClock);
        paint.clearShadowLayer();
        paint.setColor(0xFFFFFFFF);
        final float right = RIGHT * k, top = CLOCK_TOP * k, scale = k;
        final int gen = generation;
        worker.execute(() -> {
            Glass g = makeGlass(text, paint, right, top, scale);
            handler.post(() -> {
                if (gen != generation || !text.equals(glassFor)) return;
                glass = g;
                glassShader.setInputShader("slope", nearest(g.slope));
                glassShader.setInputShader("cover", nearest(g.cover));
                chromeDirty = true;
                invalidate();
            });
        });
    }

    private static BitmapShader nearest(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Frost.filter(s, BitmapShader.FILTER_MODE_NEAREST);
        return s;
    }

    /**
     * The sharp rendering of the digits, the surface height (the blurred rendering: 0.5 at the edge, 1 in the middle of
     * the strokes), its slope (for the lens and the highlight) and the shadow.
     */
    private static Glass makeGlass(String text, Paint paint, float right, float top, float k) {
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        int pad = Math.round(40 * k);
        int w = ink.width() + 2 * pad, h = ink.height() + 2 * pad;
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, paint);
        int[] px = new int[w * h];
        cover.getPixels(px, 0, w, 0, 0, w, h);
        int[] alpha = new int[px.length];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) * 16;
        int[] height = blur(alpha, w, h, Math.max(2, Math.round(6 * k)), 3);
        int[] soft = blur(alpha, w, h, Math.max(3, Math.round(12 * k)), 3);
        float max = 1;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
                max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
            }
        }
        int[] slope = new int[px.length], shadow = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
                float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
                int r = 128 + Math.round(127 * gx / max), g = 128 + Math.round(127 * gy / max);
                int b = Math.min(255, height[i] / 16);
                slope[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                shadow[i] = Math.min(255, soft[i] / 16) << 24;
            }
        }
        int x = Math.round(right - ink.width()) - pad, y = Math.round(top) - pad;
        return new Glass(text, Bitmap.createBitmap(slope, w, h, Bitmap.Config.ARGB_8888), cover,
                Bitmap.createBitmap(shadow, w, h, Bitmap.Config.ARGB_8888), x, y, pad, Math.max(1, ink.height()));
    }

    /** Box blur (passes of radius r in both directions); anything outside the image counts as empty. */
    private static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }

    // MARK: - Photo caption (bottom left)

    private void recordCaption(Slide s) {
        Canvas c = s.caption.beginRecording(w, h);
        try {
            float x = MARGIN * k, metaBase = META_BASE * k, titleBase = metaBase - 58 * k;
            String title = s.pick.title == null ? "" : s.pick.title;
            StaticLayout t = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.round(TITLE_W * k))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false).build();
            float originY = titleBase - t.getLineBaseline(t.getLineCount() - 1);
            float capTop = originY + t.getLineBaseline(0) - Ui.cap(titlePaint);
            String label = s.pick.list >= 0 ? Recs.listNames()[s.pick.list].toUpperCase(Ui.locale()) : Recs.periodLabel();
            c.drawText(label, x, capTop - 30 * k, labelPaint);
            c.save();
            c.translate(x - Ui.leftBearing(titlePaint, title), originY);
            t.draw(c);
            c.restore();
            String meta = Recs.captionMeta(s.pick);
            if (!meta.isEmpty()) c.drawText(meta, x, metaBase, metaPaint);
        } finally {
            s.caption.endRecording();
        }
        s.caption.setPosition(0, 0, w, h);
    }

    /** The caption fades out fast; the new one rises in once the photo has partly changed (like the home screen). */
    private void drawCaptions(Canvas c, long up) {
        if (incoming == null) {
            float a = current.startedAt == firstAt ? fadeIn(up - firstAt - 700, 900) : 1;
            current.caption.setAlpha(a);
            current.caption.setTranslationY(14 * k * (1 - a));
            current.caption.draw(c);
            return;
        }
        long t = up - incoming.startedAt;
        float out = 1 - Ui.clamp(t / 500f);
        if (out > 0.004f) {
            current.caption.setAlpha(out);
            current.caption.setTranslationY(0);
            current.caption.draw(c);
        }
        float in = fadeIn(t - 1300, 900);
        if (in > 0.004f) {
            incoming.caption.setAlpha(in);
            incoming.caption.setTranslationY(14 * k * (1 - in));
            incoming.caption.draw(c);
        }
    }

    // MARK: - Without photos: the big clock over the aurora

    private void drawFallback(Canvas canvas, long up, float alpha) {
        float t = (up - startedAt) / 1000f;
        if (up - lastSky >= 100) {
            lastSky = up;
            scene.advance(timeOffset + t);
        }
        scene.drawBackground(canvas);
        if (dirty) {
            Canvas rc = content.beginRecording(w, h);
            try {
                drawContent(rc);
            } finally {
                content.endRecording();
            }
            dirty = false;
        }
        double phase = 2 * Math.PI * t / DRIFT_PERIOD_S;
        content.setTranslationX((float) (40 * k * Math.sin(phase)));
        content.setTranslationY((float) (26 * k * Math.sin(2 * phase + 0.7)));
        content.setAlpha(alpha);
        content.draw(canvas);
    }

    private void drawContent(Canvas c) {
        float cx = w / 2f;
        float clockCap = Ui.cap(clockPaint);
        float clockBase = h * 0.5f + clockCap / 2 - 10 * k;
        c.drawText(date, cx, clockBase - clockCap - 34 * k, datePaint);
        c.drawText(clock, cx, clockBase, clockPaint);

        // Weather: symbol, temperature, high and low, centered under the clock.
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp) && Prefs.weather()) {
            float base = clockBase + 76 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            String now = HomeData.degrees(wx.temp);
            String range = Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "  Мин. " + HomeData.degrees(wx.min)
                    : Ui.indo ? "T:" + HomeData.degrees(wx.max) + "  R:" + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "  L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "  T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "  Mín. " + HomeData.degrees(wx.min);
            float sw = Symbols.width(getContext(), symbol, 32 * k);
            float width = sw + 12 * k + weatherPaint.measureText(now) + 18 * k + weatherDim.measureText(range);
            float x = cx - width / 2;
            Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(weatherPaint), 32 * k, 0xC7FFFFFF);
            x += sw + 12 * k;
            c.drawText(now, x, base, weatherPaint);
            x += weatherPaint.measureText(now) + 18 * k;
            c.drawText(range, x, base, weatherDim);
        }

        // Prices near the bottom edge: small white logo + value, all four on one line.
        float cap = Ui.cap(quotePaint), spacing = 56 * k, gap = 10 * k;
        int[] coins = HomeData.active();
        String[] values = new String[HomeData.coinCount()];
        float total = spacing * (values.length - 1);
        for (int i = 0; i < values.length; i++) {
            values[i] = HomeData.price(data.prices[coins[i]]);
            total += Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap) + gap + quotePaint.measureText(values[i]);
        }
        float x = cx - total / 2, base = h - 92 * k;
        for (int i = 0; i < values.length; i++) {
            float gw = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + gw / 2, base - cap / 2, cap, 0x80FFFFFF);
            x += gw + gap;
            c.drawText(values[i], x, base, quotePaint);
            x += quotePaint.measureText(values[i]) + spacing;
        }
        String stale = HomeData.staleness(data.pricesAt, HomeData.pricesEvery());
        if (!stale.isEmpty() && values.length > 0) c.drawText(Ui.tr("cotações de ", "prices from ") + stale, cx, base + 38 * k, stalePaint);
    }

    // MARK: - Ink mountains

    private static boolean inkMode() {
        return Prefs.wallpaper().equals("ink");
    }

    // MARK: - Lite: the home screen's aurora dimmed, the clock, the date, the weather and the next prayer, once a minute

    private static boolean liteMode() {
        return Prefs.lite();
    }

    private int liteStep;
    private Shader liteBase, liteTeal, liteViolet;
    private float liteK = -1;

    private void drawLite(Canvas c, long up) {
        if (liteK != k) {
            liteK = k;
            liteBase = new LinearGradient(0, 0, w * 0.6f, h, new int[] {0xFF04060C, 0xFF06091A, 0xFF070A1C}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
            liteTeal = new RadialGradient(0.08f * w, 1.08f * h, 0.47f * w, new int[] {0x4D1A7884, 0x001A7884}, new float[] {0, 0.7f}, Shader.TileMode.CLAMP);
            liteViolet = new RadialGradient(0.96f * w, 0.6f * h, 0.57f * w, new int[] {0x476040AA, 0x006040AA}, new float[] {0, 0.7f}, Shader.TileMode.CLAMP);
        }
        iBox.setColor(0xFFFFFFFF);   // opaque: the paint's alpha applies to a shader too
        for (Shader s : new Shader[] {liteBase, liteTeal, liteViolet}) {
            iBox.setShader(s);
            c.drawRect(0, 0, w, h, iBox);
        }
        iBox.setShader(null);
        // The block drifts inside a 120 px box, a step each minute, so the clock never sits on the same pixels for long
        float dx = (liteStep * 37 % 120) * k, dy = (liteStep * 53 % 120) * k;
        c.save();
        c.translate(160 * k + dx, 300 * k + dy);
        int fg = 0xFFFFFFFF, dim = 0x8CFFFFFF, soft = 0xCCFFFFFF;
        iDate.setColor(dim);
        iDate.setTextSize(30 * k);
        c.drawText(greet, 0, 0, iDate);
        float hs = 28 * k, hx = iDate.measureText(greet) + 12 * k, hcy = -Ui.halfCap(iDate);
        c.save();
        c.rotate(Ui.waveAngle(up - waveAt), hx + hs * 0.35f, hcy + hs * 0.35f);
        WeatherIcons.wave(getContext(), c, hx + hs / 2, hcy, hs, dim);
        c.restore();
        iDate.setTextSize(26 * k);
        iClock.setColor(fg);
        iClock.setTextSize(300 * k);
        c.drawText(clock, -Ui.leftBearing(iClock, clock), 300 * k, iClock);
        iClock.setTextSize(230 * k);
        iLine.setColor(soft);
        iLine.setTextSize(36 * k);
        c.drawText(Ui.date(System.currentTimeMillis()), 0, 365 * k, iLine);
        iLine.setTextSize(32 * k);
        HomeData.Weather wx = data.weather;
        boolean weather = Prefs.weather() && !Double.isNaN(wx.temp);
        StringBuilder line = new StringBuilder();
        if (weather) line.append(HomeData.degrees(wx.temp)).append(' ').append(HomeData.condition(wx.code, wx.isDay));
        if (!prayerLine.isEmpty()) line.append(line.length() > 0 ? "    ·    " : "").append(prayerLine);
        iSmall.setColor(dim);
        iSmall.setTextSize(28 * k);
        if (line.length() > 0) c.drawText(line.toString(), 0, 418 * k, iSmall);
        iSmall.setTextSize(26 * k);
        c.restore();
    }

    /** The weather the sky shows: the real one, or the one picked in Home Settings (Sky preview). */
    private int inkCode() {
        int code = Prefs.skyWeatherCode();
        return code == Integer.MIN_VALUE ? data.weather.code : code;
    }

    /** The hour on the ink wallpaper's day: the one picked in Sky preview, or now with today's real sunrise and sunset. */
    private float inkHour(long now) {
        float picked = Prefs.skyTimeHour();
        if (picked >= 0) return picked;
        float hour = Shaders.timeOfDay(now, 0) / 3_600_000f;
        float rise = Shaders.timeOfDay(data.weather.sunrise, (long) (InkSky.SUNRISE * 3_600_000L)) / 3_600_000f;
        float set = Shaders.timeOfDay(data.weather.sunset, (long) (InkSky.SUNSET * 3_600_000L)) / 3_600_000f;
        return InkSky.canonical(hour, rise, set);
    }

    private void refreshPrayers(long now) {
        prayerNext = -1;
        prayerLine = "";
        double lat, lon;
        try {
            lat = Double.parseDouble(Prefs.latitude());
            lon = Double.parseDouble(Prefs.longitude());
        } catch (Exception e) {
            return;
        }
        long[] today = PrayerTimes.times(lat, lon, now, 0), next = PrayerTimes.next(lat, lon, now);
        for (int i = 0; i < 5; i++) {
            prayerAt[i] = today[i];
            prayerText[i] = Ui.clock(today[i]);
        }
        prayerNext = next[1] == today[(int) next[0]] ? (int) next[0] : 0;   // after Isha: tomorrow's Fajr
        long left = Math.max(0, (next[1] - now + 59_999) / 60_000);
        prayerLine = PrayerTimes.name((int) next[0]) + " " + Ui.clock(next[1]) + "  ·  " + Ui.tr("em ", "in ")
                + (left >= 60 ? (left / 60) + (Ui.indo ? " jam " : " h ") + (left % 60) + (Ui.indo ? " menit" : " min")
                : left + (Ui.indo ? " menit" : " min"));
    }

    /** The caption for the time of day, on the wallpaper's day. */
    private static String phase(float hour) {
        if (hour < 4.2f || hour >= 19.2f) return Ui.tr("ESTA NOITE", "TONIGHT");
        if (hour < 5.1f) return Ui.tr("ANTES DO AMANHECER", "BEFORE DAWN");
        if (hour < 5.75f) return Ui.tr("AMANHECER", "DAWN");
        if (hour < 7.5f) return Ui.tr("NASCER DO SOL", "SUNRISE");
        if (hour < 11) return Ui.tr("ESTA MANHÃ", "THIS MORNING");
        if (hour < 14.5f) return Ui.tr("MEIO-DIA", "MIDDAY");
        if (hour < 16.6f) return Ui.tr("ESTA TARDE", "THIS AFTERNOON");
        if (hour < 17.5f) return Ui.tr("HORA DOURADA", "GOLDEN HOUR");
        if (hour < 18.2f) return Ui.tr("PÔR DO SOL · MAGHRIB", "SUNSET · MAGHRIB");
        return Ui.tr("CREPÚSCULO", "DUSK");
    }

    private void drawInk(Canvas c, long now, long up) {
        float hour = inkHour(now), sec = (up - startedAt) / 1000f;
        int code = inkCode(), kind = WeatherSky.kind(code);
        boolean motion = Prefs.wallpaperMotion();
        // The mountains glide on their own: the depths sway over ~90 s, under a slow zoom in and out
        float par = motion ? (float) Math.sin(sec * 2 * Math.PI / 90) * 0.9f : 0;
        float z = 1.04f + (motion ? 0.03f * (float) Math.sin(sec * 2 * Math.PI / 240) : 0);
        c.save();
        c.scale(z, z, w / 2f, h / 2f);
        ink.draw(c, code, hour, up, par, motion);
        c.restore();

        boolean dark = InkSky.night(hour) >= 0.5f || kind == WeatherSky.STORM;
        int fg = dark ? 0xFFFFFFFF : 0xFF0B1626, dim = dark ? 0xE0FFFFFF : 0xE615263C;
        RadialGradient[] scrims = dark ? scrimDark : scrimLight;
        float[][] at = {{330, 250, 640}, {1620, 980, 640}, {400, 990, 680}};
        for (int i = 0; i < 3; i++) {
            iScrim.setShader(scrims[i]);
            c.drawCircle(at[i][0] * k, at[i][1] * k, at[i][2] * k, iScrim);
        }

        // The text drifts a few pixels over ten minutes, so nothing stays burned in one place
        float dx = (float) Math.sin(sec * 2 * Math.PI / 600) * 18 * k, dy = (float) Math.cos(sec * 2 * Math.PI / 600) * 12 * k;
        c.save();
        c.translate(dx, dy);
        HomeData.Weather wx = data.weather;
        boolean weather = Prefs.weather() && !Double.isNaN(wx.temp);
        String condition = weather ? HomeData.condition(wx.code, wx.isDay) : "";
        // Top left: greeting and date, the clock, the next prayer, the rates (the weather is at the bottom right)
        iDate.setColor(dim);
        c.drawText(greet, 80 * k, 110 * k, iDate);
        float hs = 30 * k, hx = 80 * k + iDate.measureText(greet) + 10 * k, ww = hs, hcy = 110 * k - Ui.halfCap(iDate);
        c.save();
        c.rotate(Ui.waveAngle(up - waveAt), hx + hs * 0.35f, hcy + hs * 0.35f);
        WeatherIcons.wave(getContext(), c, hx + hs / 2, hcy, hs, dim);
        c.restore();
        c.drawText(dateRest, hx + ww, 110 * k, iDate);
        iClock.setColor(fg);
        c.drawText(clock, 80 * k - Ui.leftBearing(iClock, clock), 330 * k, iClock);
        iLine.setColor(dim);
        if (!prayerLine.isEmpty()) c.drawText(prayerLine, 80 * k, 396 * k, iLine);
        if (Prefs.quotes() && HomeData.active().length > 0) {
            StringBuilder rates = new StringBuilder();
            for (int coin : HomeData.active()) {
                if (rates.length() > 0) rates.append("   ");
                rates.append(HomeData.RATES ? HomeData.COINS[coin] : HomeData.coinName(coin)).append(' ').append(HomeData.compact(data.prices[coin]));
            }
            iSmall.setColor(dim);
            c.drawText(rates.toString(), 80 * k, 446 * k, iSmall);
        }
        // Bottom right: the time of day, the city, the weather
        String label = phase(hour), city = Prefs.city().isEmpty() ? Config.CITY : Prefs.city();
        iLabel.setColor(dim);
        c.drawText(label, 1840 * k - iLabel.measureText(label), 920 * k, iLabel);
        iName.setColor(fg);
        if (!city.isEmpty()) c.drawText(city, 1840 * k - iName.measureText(city), 984 * k, iName);
        if (weather) {
            String line = condition + "  ·  " + HomeData.degrees(wx.temp) + "  ·  H " + HomeData.degrees(wx.max) + "  L " + HomeData.degrees(wx.min);
            iSmall.setColor(dim);
            c.drawText(line, 1840 * k - iSmall.measureText(line), 1030 * k, iSmall);
        }
        // Bottom left: today's five prayer times, the next one highlighted
        if (prayerNext >= 0) {
            iLabel.setColor(dim);
            c.drawText(Ui.tr("HORÁRIOS DE ORAÇÃO", "PRAYER TIMES"), 80 * k, 920 * k, iLabel);
            for (int i = 0; i < 5; i++) {
                float x = (80 + i * 172) * k;
                boolean on = i == prayerNext;
                if (on) {
                    iBox.setColor(dark ? 0x29FFFFFF : 0x1715263C);
                    box.set(x - 14 * k, 940 * k, x + 144 * k, 1044 * k);
                    c.drawRoundRect(box, 18 * k, 18 * k, iBox);
                }
                Paint name = on ? iPNameOn : iPName, time = on ? iPTimeOn : iPTime;
                name.setColor(on ? fg : dim);
                time.setColor(fg);
                c.drawText(PrayerTimes.name(i), x, 980 * k, name);
                c.drawText(prayerText[i], x, 1028 * k, time);
            }
        }
        c.restore();
    }
}
