package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.RenderNode;
import android.os.Build;

/**
 * A recorded drawing that can be replayed with an alpha and a translation: a RenderNode on Android 10+, a Picture on
 * Android 9 (no RenderNode). Only Node touches RenderNode, so the classes that use Recording still verify on Android 9.
 */
abstract class Recording {
    static Recording create(String name) {
        return Build.VERSION.SDK_INT >= 29 ? new Node(name) : new Pic();
    }

    abstract Canvas beginRecording(int width, int height);
    abstract void endRecording();
    abstract void setPosition(int left, int top, int right, int bottom);
    /** On Android 9 the layer is skipped: the alpha is applied with saveLayerAlpha only when it's below 1. */
    abstract void setUseCompositingLayer(boolean use, Paint paint);
    abstract void setAlpha(float alpha);
    abstract void setTranslationX(float x);
    abstract void setTranslationY(float y);
    abstract void draw(Canvas c);

    private static final class Node extends Recording {
        private final RenderNode node;

        Node(String name) {
            node = new RenderNode(name);
        }

        @Override Canvas beginRecording(int width, int height) { return node.beginRecording(width, height); }
        @Override void endRecording() { node.endRecording(); }
        @Override void setPosition(int l, int t, int r, int b) { node.setPosition(l, t, r, b); }
        @Override void setUseCompositingLayer(boolean use, Paint paint) { node.setUseCompositingLayer(use, paint); }
        @Override void setAlpha(float alpha) { node.setAlpha(alpha); }
        @Override void setTranslationX(float x) { node.setTranslationX(x); }
        @Override void setTranslationY(float y) { node.setTranslationY(y); }
        @Override void draw(Canvas c) { c.drawRenderNode(node); }
    }

    private static final class Pic extends Recording {
        private final Picture picture = new Picture();
        private float alpha = 1, tx, ty;
        private int left, top, right, bottom;

        @Override Canvas beginRecording(int width, int height) {
            return picture.beginRecording(width, height);
        }

        @Override void endRecording() { picture.endRecording(); }

        @Override void setPosition(int l, int t, int r, int b) {
            left = l;
            top = t;
            right = r;
            bottom = b;
        }

        @Override void setUseCompositingLayer(boolean use, Paint paint) {}
        @Override void setAlpha(float a) { alpha = Math.max(0, Math.min(1, a)); }
        @Override void setTranslationX(float x) { tx = x; }
        @Override void setTranslationY(float y) { ty = y; }

        @Override void draw(Canvas c) {
            if (alpha <= 0.004f) return;
            int save = alpha < 1
                    ? c.saveLayerAlpha(left + tx, top + ty, right + tx, bottom + ty, Math.round(alpha * 255))
                    : c.save();
            c.translate(left + tx, top + ty);
            c.drawPicture(picture);
            c.restoreToCount(save);
        }
    }
}
