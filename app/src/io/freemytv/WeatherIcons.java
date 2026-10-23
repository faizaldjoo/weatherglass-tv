package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The home screen's glass weather icons (assets/weather, 256 × 192, in color: a glass cloud in front of the sun or the
 * moon), the location pin and the waving hand (white, tinted when drawn). Named like Symbols.weather: sunny, partly_cloudy_day...
 */
final class WeatherIcons {
    private WeatherIcons() {}

    static final float ASPECT = 256 / 192f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    private static Bitmap get(Context c, String name, boolean alpha) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("weather/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null && alpha) b = b.extractAlpha();
                if (b != null) b.setHasMipMap(true);
            } catch (IOException ignored) {
            }
            CACHE.put(name, b);
            return b;
        }
    }

    /** The weather icon `name`, `height` px tall, centered at (cx, cy); false if there is none. */
    static boolean draw(Context c, Canvas canvas, String name, float cx, float cy, float height) {
        Bitmap b = get(c, name, false);
        if (b == null) return false;
        float w = height * ASPECT;
        synchronized (PAINT) {
            PAINT.setColor(0xFFFFFFFF);
            RECT.set(cx - w / 2, cy - height / 2, cx + w / 2, cy + height / 2);
            canvas.drawBitmap(b, null, RECT, PAINT);
        }
        return true;
    }

    /** The location pin, `size` px square, centered at (cx, cy), in `color`. */
    static void pin(Context c, Canvas canvas, float cx, float cy, float size, int color) {
        tinted(c, canvas, "location", cx, cy, size, color);
    }

    /** The waving hand after the greeting, one color like the text (a phosphor icon, not the emoji). */
    static void wave(Context c, Canvas canvas, float cx, float cy, float size, int color) {
        tinted(c, canvas, "wave", cx, cy, size, color);
    }

    private static void tinted(Context c, Canvas canvas, String name, float cx, float cy, float size, int color) {
        Bitmap b = get(c, name, true);
        if (b == null) return;
        synchronized (PAINT) {
            PAINT.setColor(color);
            RECT.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            canvas.drawBitmap(b, null, RECT, PAINT);
        }
    }
}
