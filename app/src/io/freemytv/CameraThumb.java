package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.function.Consumer;

/**
 * A still picture from the CCTV camera for its shelf tile. The camera's own snapshot doesn't work (HTTP 500) and
 * Android's player can't log in to it, so this reads one key frame from its small video stream itself (RtspKeyFrame;
 * cctv_url with /stream0 → /stream1, 640×360), has the TV's decoder draw it onto an off-screen GL surface, copies the
 * pixels and hangs up. A few seconds of network at most every 2 minutes while
 * the home screen is up; the last picture is kept on disk so it shows right away after a restart. If the stream can't be
 * read, the tile keeps its drawn camera.
 */
final class CameraThumb {
    static final int W = 480, H = 270;
    private static final long EVERY_MS = 120_000, GIVE_UP_MS = 25_000;
    private static final int FW = 640, FH = 360;   // the frame copied (the tile is smaller)

    private final Context context;
    private final String url;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastAt;
    private boolean busy;

    CameraThumb(Context c, String rtspUrl) {
        context = c.getApplicationContext();
        url = rtspUrl.contains("/stream0") ? rtspUrl.replace("/stream0", "/stream1") : rtspUrl;
    }

    /** The last picture on disk, already made into a tile, or null. */
    Bitmap cached() {
        File f = file();
        if (!f.exists()) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        return b == null ? null : tile(b);
    }

    /** Grabs a new picture if the last try is over 2 minutes old; `done` gets the tile on the main thread. */
    void refresh(Consumer<Bitmap> done) {
        long now = System.currentTimeMillis();
        if (busy || url.isEmpty() || now - lastAt < EVERY_MS) return;
        busy = true;
        lastAt = now;
        new Grab(frame -> {
            Bitmap tile = null;
            if (frame != null) {
                try (OutputStream os = new FileOutputStream(file())) {
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, os);
                } catch (Exception e) {
                    AppLog.w("camera still: " + e);
                }
                tile = tile(frame);
                frame.recycle();
            }
            Bitmap result = tile;
            main.post(() -> {
                busy = false;
                if (result != null) done.accept(result);
            });
        }).start();
    }

    /** A picture from elsewhere (the live popup's last frame) becomes the still: saved, and made into the tile. */
    Bitmap keep(Bitmap frame) {
        lastAt = System.currentTimeMillis();
        try (OutputStream os = new FileOutputStream(file())) {
            frame.compress(Bitmap.CompressFormat.JPEG, 85, os);
        } catch (Exception e) {
            AppLog.w("camera still: " + e);
        }
        return tile(frame);
    }

    private File file() {
        return new File(context.getCacheDir(), "camera-still.jpg");
    }

    // MARK: - The tile: the picture filling 16:9, a soft shade at the top and the red LIVE mark

    private static Bitmap tile(Bitmap src) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float s = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        float w = src.getWidth() * s, h = src.getHeight() * s;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, null, new RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2), p);
        p.setShader(new LinearGradient(0, 0, 0, H * 0.45f, 0x59000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H * 0.45f, p);
        p.setShader(null);
        p.setColor(0xFFC5221F);
        c.drawRoundRect(new RectF(22, 20, 132, 62), 10, 10, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(42, 41, 7, p);
        Paint t = Ui.text(Ui.displaySemibold, 26, 0xFFFFFFFF);
        c.drawText("LIVE", 56, 41 + Ui.halfCap(t), t);
        return b;
    }

    // MARK: - One grab: RTSP key frame → MediaCodec → SurfaceTexture → off-screen GL → pixels

    private final class Grab implements SurfaceTexture.OnFrameAvailableListener {
        private final Consumer<Bitmap> done;
        private HandlerThread thread;
        private Handler handler;   // the GL thread
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext egl = EGL14.EGL_NO_CONTEXT;
        private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
        private int texture, program;
        private SurfaceTexture surfaceTexture;
        private Surface surface;
        private RtspKeyFrame rtsp;
        private volatile boolean finished;
        private Bitmap result;
        private final float[] matrix = new float[16];

        Grab(Consumer<Bitmap> done) {
            this.done = done;
        }

        void start() {
            thread = new HandlerThread("camera-still");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(() -> {
                try {
                    setUpGl();
                    surfaceTexture = new SurfaceTexture(texture);
                    surfaceTexture.setDefaultBufferSize(FW, FH);
                    surfaceTexture.setOnFrameAvailableListener(this, handler);
                    surface = new Surface(surfaceTexture);
                } catch (Exception e) {
                    finish(null, e.toString());
                    cleanUp();
                    return;
                }
                handler.postDelayed(() -> finish(null, "timed out"), GIVE_UP_MS);
                new Thread(this::stream, "camera-rtsp").start();
            });
        }

        /** On its own thread: the key frame from the camera, then the decoder draws it onto our surface. */
        private void stream() {
            MediaCodec codec = null;
            try {
                rtsp = new RtspKeyFrame(url);
                RtspKeyFrame.Frame f = rtsp.read(15_000);
                if (finished) return;
                codec = MediaCodec.createDecoderByType(f.mime);
                MediaFormat format = MediaFormat.createVideoFormat(f.mime, FW, FH);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(f.data.length + 1024, 512 * 1024));
                codec.configure(format, surface, null, 0);
                codec.start();
                int in = codec.dequeueInputBuffer(2_000_000);
                if (in < 0) throw new IllegalStateException("decoder busy");
                ByteBuffer buf = codec.getInputBuffer(in);
                buf.clear();
                buf.put(f.data);
                codec.queueInputBuffer(in, 0, f.data.length, 0, 0);
                int eos = codec.dequeueInputBuffer(2_000_000);   // end of stream: the decoder hands out what it has
                if (eos >= 0) codec.queueInputBuffer(eos, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long until = SystemClock.uptimeMillis() + 4000;
                boolean shown = false;
                while (!shown && !finished && SystemClock.uptimeMillis() < until) {
                    int outIndex = codec.dequeueOutputBuffer(info, 100_000);
                    if (outIndex >= 0) {
                        boolean frame = info.size > 0;
                        codec.releaseOutputBuffer(outIndex, frame);   // drawn onto the SurfaceTexture → onFrameAvailable
                        shown = frame;
                        if (!frame && (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    }
                }
                if (!shown) throw new IllegalStateException("the decoder gave no picture");
                for (int i = 0; i < 40 && !finished; i++) Thread.sleep(50);   // until the GL thread has copied it
            } catch (Exception e) {
                handler.post(() -> finish(null, e.toString()));
            } finally {
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (Exception ignored) {
                    }
                    codec.release();
                }
                handler.post(() -> {
                    finish(null, "no picture");
                    cleanUp();
                });
            }
        }

        @Override public void onFrameAvailable(SurfaceTexture st) {
            if (finished) return;
            try {
                st.updateTexImage();
                st.getTransformMatrix(matrix);
                finish(read(), null);
            } catch (Exception e) {
                finish(null, e.toString());
            }
        }

        /** The result (the first one counts); the stream stops. Clean-up follows once the RTSP thread is done. */
        private void finish(Bitmap frame, String why) {
            if (finished) return;
            finished = true;
            result = frame;
            if (why != null) AppLog.w("camera still: " + why);
            if (rtsp != null) rtsp.cancel();
        }

        private void cleanUp() {
            if (surface != null) surface.release();
            if (surfaceTexture != null) surfaceTexture.release();
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
                if (egl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, egl);
                EGL14.eglTerminate(display);
            }
            handler.removeCallbacksAndMessages(null);
            thread.quitSafely();
            done.accept(result);
        }

        private void setUpGl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("no EGL");
            int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) throw new IllegalStateException("no EGL config");
            egl = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0], new int[] {EGL14.EGL_WIDTH, FW, EGL14.EGL_HEIGHT, FH, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, egl)) throw new IllegalStateException("EGL make current failed");
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            texture = tex[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 p; uniform mat4 m; varying vec2 uv;"
                    + "void main(){ uv = (m * vec4(p * 0.5 + 0.5, 0.0, 1.0)).xy; gl_Position = vec4(p.x, -p.y, 0.0, 1.0); }"));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER,
                    "#extension GL_OES_EGL_image_external : require\n precision mediump float; varying vec2 uv;"
                    + " uniform samplerExternalOES t; void main(){ gl_FragColor = texture2D(t, uv); }"));
            GLES20.glLinkProgram(program);
        }

        private int shader(int type, String code) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, code);
            GLES20.glCompileShader(s);
            return s;
        }

        /** Draws the frame into the pbuffer (upside down, so glReadPixels' bottom-up rows come out the right way). */
        private Bitmap read() {
            GLES20.glViewport(0, 0, FW, FH);
            GLES20.glUseProgram(program);
            FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
            int p = GLES20.glGetAttribLocation(program, "p");
            GLES20.glEnableVertexAttribArray(p);
            GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "m"), 1, false, matrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "t"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            ByteBuffer pixels = ByteBuffer.allocateDirect(FW * FH * 4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, FW, FH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            pixels.rewind();
            Bitmap b = Bitmap.createBitmap(FW, FH, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(pixels);
            return b;
        }
    }
}
