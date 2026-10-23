package io.freemytv;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * The CCTV camera live, in a popup over the home screen: a rounded 16:9 window in the middle, the rest dimmed. Our own
 * RTSP client (RtspKeyFrame.stream) feeds the TV's decoder, which draws straight onto the window. OK opens the camera
 * full screen in VLC, Back closes the popup. On closing, the last frame becomes the tile's still. It closes by itself
 * after 10 minutes so a forgotten popup doesn't keep the camera (and the screen) on.
 */
final class CameraPopup implements TextureView.SurfaceTextureListener {
    private static final long AUTO_CLOSE_MS = 10 * 60_000;

    private final Activity activity;
    private final String url;
    private final Runnable fullScreen;
    private final Consumer<Bitmap> lastFrame;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final TextureView video;
    private final TextView status;
    private Surface surface;
    private Thread thread;
    private volatile RtspKeyFrame rtsp;
    private volatile boolean stopped, shown;

    /**
     * @param fullScreen what OK does (VLC full screen)
     * @param lastFrame  gets the last picture when the popup closes (only if one was shown)
     */
    CameraPopup(Activity a, String rtspUrl, Runnable fullScreen, Consumer<Bitmap> lastFrame) {
        activity = a;
        url = rtspUrl;
        this.fullScreen = fullScreen;
        this.lastFrame = lastFrame;
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        float k = sw / 1920f;
        int w = Math.round(1180 * k), h = w * 9 / 16, radius = Math.round(30 * k);

        // The video, clipped to rounded corners, on black until the first frame
        FrameLayout frame = new FrameLayout(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B0E13);
        bg.setCornerRadius(radius);
        bg.setStroke(Math.max(1, Math.round(2 * k)), 0x33FFFFFF);
        frame.setBackground(bg);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        frame.setClipToOutline(true);
        frame.setElevation(24 * k);
        video = new TextureView(a);
        video.setSurfaceTextureListener(this);
        video.setAlpha(0f);
        frame.addView(video, new FrameLayout.LayoutParams(w, h));
        status = text(a, Ui.tr("Conectando à câmera…", "Connecting to the camera…"), 30 * k, 0xB3FFFFFF);
        frame.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        View live = new LiveBadge(a, k);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(124 * k), Math.round(48 * k), Gravity.TOP | Gravity.START);
        lp.setMargins(Math.round(28 * k), Math.round(26 * k), 0, 0);
        frame.addView(live, lp);

        // Under it: what the remote does
        TextView hint = text(a, Ui.tr("OK  tela cheia      Voltar  fechar", "OK  full screen      Back  close"), 26 * k, 0x99FFFFFF);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(frame, new LinearLayout.LayoutParams(w, h));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = Math.round(26 * k);
        column.addView(hint, hp);
        FrameLayout root = new FrameLayout(a);
        root.addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        dialog = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            win.setDimAmount(0.7f);
            win.setWindowAnimations(android.R.style.Animation_Dialog);
        }
        dialog.setOnKeyListener((d, code, e) -> {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            }
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
            if (!ok) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) {
                dismiss();
                this.fullScreen.run();
            }
            return true;
        });
        dialog.setOnDismissListener(d -> stop());
    }

    void show() {
        dialog.show();
        main.postDelayed(this::dismiss, AUTO_CLOSE_MS);
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    void dismiss() {
        keepLastFrame();
        if (dialog.isShowing()) dialog.dismiss();
        else stop();
    }

    /** The picture on screen now becomes the tile's still (while the window still has it). */
    private void keepLastFrame() {
        if (!shown || stopped || lastFrame == null) return;
        try {
            Bitmap b = video.getBitmap(640, 360);
            if (b != null) lastFrame.accept(b);
        } catch (Exception e) {
            AppLog.w("camera popup: " + e);
        }
    }

    /** Stops the stream. Safe to call twice. */
    private void stop() {
        if (stopped) return;
        stopped = true;
        main.removeCallbacksAndMessages(null);
        RtspKeyFrame r = rtsp;
        if (r != null) r.cancel();
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1500);   // the decoder lets go of the surface before it goes away
            } catch (InterruptedException ignored) {
            }
        }
    }

    // MARK: - The surface → the stream

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (stopped) return;
        surface = new Surface(st);
        thread = new Thread(this::play, "camera-live");
        thread.start();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stop();
        if (surface != null) surface.release();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
        if (shown) return;
        shown = true;
        video.animate().alpha(1f).setDuration(250).start();
        status.setVisibility(View.GONE);
    }

    /** On its own thread: RTSP → decoder → the window, until stopped. */
    private void play() {
        MediaCodec[] codec = {null};
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long[] first = {-1};
        try {
            rtsp = new RtspKeyFrame(url);
            if (stopped) return;
            rtsp.stream((data, ts, key) -> {
                if (stopped) return false;
                if (codec[0] == null) {
                    MediaFormat f = MediaFormat.createVideoFormat(rtsp.mime(), 1920, 1080);
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
                    f.setInteger(MediaFormat.KEY_MAX_WIDTH, 3840);
                    f.setInteger(MediaFormat.KEY_MAX_HEIGHT, 2160);
                    codec[0] = MediaCodec.createDecoderByType(rtsp.mime());
                    codec[0].configure(f, surface, null, 0);
                    codec[0].start();
                    first[0] = ts;
                }
                MediaCodec c = codec[0];
                long pts = ((ts - first[0]) & 0xFFFFFFFFL) * 1_000_000L / 90_000L;
                while (!stopped) {
                    drain(c, info);
                    int i = c.dequeueInputBuffer(20_000);
                    if (i < 0) continue;
                    ByteBuffer buf = c.getInputBuffer(i);
                    buf.clear();
                    if (data.length > buf.capacity()) {   // too big for the decoder: skip it
                        c.queueInputBuffer(i, 0, 0, pts, 0);
                        break;
                    }
                    buf.put(data);
                    c.queueInputBuffer(i, 0, data.length, pts, 0);
                    break;
                }
                drain(c, info);
                return !stopped;
            });
        } catch (Exception e) {
            if (!stopped) {
                AppLog.w("camera popup: " + e);
                main.post(() -> {
                    if (stopped) return;
                    status.setText(Ui.tr("A câmera não respondeu.", "The camera didn't answer."));
                    status.setVisibility(View.VISIBLE);
                    video.setAlpha(0f);
                });
            }
        } finally {
            if (codec[0] != null) {
                try {
                    codec[0].stop();
                } catch (Exception ignored) {
                }
                codec[0].release();
            }
        }
    }

    /** Shows every decoded frame right away (live: no waiting for timestamps). */
    private static void drain(MediaCodec c, MediaCodec.BufferInfo info) {
        while (true) {
            int o = c.dequeueOutputBuffer(info, 0);
            if (o >= 0) c.releaseOutputBuffer(o, info.size > 0);
            else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && o != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return;
        }
    }

    // MARK: - Small parts

    private static TextView text(Activity a, String s, float px, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        if (Ui.medium != null) t.setTypeface(Ui.medium);
        return t;
    }

    /** The red LIVE mark, like on the tile. */
    private static final class LiveBadge extends View {
        private final float k;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint t;

        LiveBadge(Activity a, float k) {
            super(a);
            this.k = k;
            t = Ui.text(Ui.displaySemibold, 28 * k, 0xFFFFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(0xFFC5221F);
            c.drawRoundRect(new RectF(0, 0, w, h), 11 * k, 11 * k, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(22 * k, h / 2, 7.5f * k, p);
            c.drawText("LIVE", 38 * k, h / 2 + Ui.halfCap(t), t);
        }
    }
}
