package io.freemytv;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * TalkBack for the screens drawn by hand on one Canvas: every item the remote can reach becomes a virtual node with
 * its place on screen. On a TV, TalkBack moves among virtual nodes itself with the arrows; the view follows (Source.focus),
 * and when the view moves its own focus, TalkBack is told so it reads the new item.
 */
final class Spoken extends AccessibilityNodeProvider {
    interface Source {
        /** Ids (positive) of the items the remote can reach now, in reading order. */
        void items(List<Integer> out);
        String text(int id);
        /** The item's bounds in the view's coordinates; false if there's nothing there now. */
        boolean bounds(int id, Rect out);
        /** The item with the remote's focus, or -1. */
        int focused();
        /** TalkBack moved to this item: move the remote's focus there too. */
        void focus(int id);
        /** OK on this item (TalkBack's click). */
        void click(int id);
        /** False for what is only there to be read (a widget): it takes the focus, so TalkBack gets to it, but OK does nothing. */
        default boolean clickable(int id) { return true; }
    }

    /** TalkBack's click and the remote's OK can both arrive for one press: only the first counts. */
    private static final long SAME_PRESS_MS = 600;

    private final View host;
    private final Source source;
    private final AccessibilityManager manager;
    private final List<Integer> ids = new ArrayList<>();
    private final Rect bounds = new Rect();
    private final int[] onScreen = new int[2];
    private final Runnable settled = () -> send(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    private int lastFocused = -1, accessibilityFocused = -1;
    private String lastText = "";
    private boolean following;
    private long keyAt, talkBackAt;

    Spoken(View host, Source source) {
        this.host = host;
        this.source = source;
        manager = (AccessibilityManager) host.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    /** After every focus move (or change on screen): TalkBack reads the focused item if it changed. */
    void focusMoved() {
        if (!manager.isEnabled()) return;
        send(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        // Again once the slide is over: the items have moved to where they rest.
        host.removeCallbacks(settled);
        host.postDelayed(settled, 600);
        int f = source.focused();
        String t = f < 0 ? "" : source.text(f);
        if (f == lastFocused && t.equals(lastText)) return;
        lastFocused = f;
        lastText = t;
        if (!following && f >= 0) send(f, AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    /** The screen came back: read the focused item again once the window has focus. */
    void readAgain() {
        lastFocused = -1;
        host.postDelayed(this::focusMoved, 400);
    }

    /** The remote's OK: false if TalkBack already clicked for this press. */
    boolean keyClick() {
        keyAt = SystemClock.uptimeMillis();
        return keyAt - talkBackAt > SAME_PRESS_MS;
    }

    @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
        if (id == HOST_VIEW_ID) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host);
            host.onInitializeAccessibilityNodeInfo(info);
            ids.clear();
            source.items(ids);
            for (int item : ids) if (source.bounds(item, bounds)) info.addChild(host, item);
            return info;
        }
        String text = source.text(id);
        if (text.isEmpty() || !source.bounds(id, bounds)) return null;
        boolean clickable = source.clickable(id), focused = id == source.focused();
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host, id);
        info.setPackageName(host.getContext().getPackageName());
        info.setClassName(clickable ? Button.class.getName() : TextView.class.getName());
        info.setParent(host);
        info.setText(text);
        info.setBoundsInParent(bounds);
        host.getLocationOnScreen(onScreen);
        bounds.offset(onScreen[0], onScreen[1]);
        info.setBoundsInScreen(bounds);
        info.setEnabled(true);
        info.setVisibleToUser(true);
        info.setImportantForAccessibility(true);
        info.setFocusable(true);
        info.setFocused(focused);
        info.setClickable(clickable);
        if (clickable) info.addAction(AccessibilityAction.ACTION_CLICK);
        if (!focused) info.addAction(AccessibilityAction.ACTION_FOCUS);
        info.setAccessibilityFocused(id == accessibilityFocused);
        info.addAction(id == accessibilityFocused ? AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                : AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        return info;
    }

    @Override public boolean performAction(int id, int action, Bundle arguments) {
        if (id == HOST_VIEW_ID) return host.performAccessibilityAction(action, arguments);
        if (action == AccessibilityNodeInfo.ACTION_CLICK && !source.clickable(id)) return false;
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                if (accessibilityFocused == id) return false;
                accessibilityFocused = id;
                follow(id);
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                if (accessibilityFocused != id) return false;
                accessibilityFocused = -1;
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                return true;
            case AccessibilityNodeInfo.ACTION_FOCUS:
                follow(id);
                return true;
            case AccessibilityNodeInfo.ACTION_CLICK:
                talkBackAt = SystemClock.uptimeMillis();
                if (talkBackAt - keyAt > SAME_PRESS_MS) {
                    follow(id);
                    source.click(id);
                }
                return true;
            default:
                return false;
        }
    }

    /** Moves the view's focus to where TalkBack is, without telling TalkBack back (it's already there). */
    private void follow(int id) {
        following = true;
        try {
            if (source.focused() != id) source.focus(id);
        } finally {
            following = false;
        }
        lastFocused = source.focused();
        lastText = lastFocused < 0 ? "" : source.text(lastFocused);
    }

    private void send(int id, int type) {
        ViewParent parent = host.getParent();
        if (parent == null || !manager.isEnabled()) return;
        AccessibilityEvent e = AccessibilityEvent.obtain(type);
        if (id == HOST_VIEW_ID) {
            host.onInitializeAccessibilityEvent(e);
            e.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        } else {
            e.setSource(host, id);
            e.setPackageName(host.getContext().getPackageName());
            e.setClassName(Button.class.getName());
            e.setEnabled(true);
            e.getText().add(source.text(id));
        }
        parent.requestSendAccessibilityEvent(host, e);
    }
}
