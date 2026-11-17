package com.jarvis.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Locale;

/** Executes only explicit, local commands issued by the user from JARVIS. */
public class JarvisAccessibilityService extends AccessibilityService {
    private static volatile JarvisAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isConnected() { return instance != null; }

    @Override public void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No screen data is stored or transmitted. */ }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    public static String runCommand(String action, String value) {
        JarvisAccessibilityService s = instance;
        if (s == null) return "ACCESSIBILITY_OFF";
        return s.execute(action == null ? "" : action, value == null ? "" : value);
    }

    private String execute(String action, String value) {
        String a = action.toLowerCase(Locale.ROOT).trim();
        if (a.equals("back")) return performGlobalAction(GLOBAL_ACTION_BACK) ? "OK" : "FAILED";
        if (a.equals("home")) return performGlobalAction(GLOBAL_ACTION_HOME) ? "OK" : "FAILED";
        if (a.equals("scroll_down") || a.equals("scroll_up")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "NO_ACTIVE_WINDOW";
            AccessibilityNodeInfo target = findScrollable(root);
            boolean ok = target != null && target.performAction(a.equals("scroll_down") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            root.recycle();
            return ok ? "OK" : "NO_SCROLL_TARGET";
        }
        if (a.equals("click_text")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "NO_ACTIVE_WINDOW";
            AccessibilityNodeInfo target = findText(root, value);
            boolean ok = false;
            if (target != null) {
                AccessibilityNodeInfo clickable = target;
                while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
                ok = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            root.recycle();
            return ok ? "OK" : "TEXT_NOT_FOUND_OR_NOT_CLICKABLE";
        }
        if (a.equals("type_text")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "NO_ACTIVE_WINDOW";
            AccessibilityNodeInfo field = findFocusedEditable(root);
            boolean ok = false;
            if (field != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
            root.recycle();
            return ok ? "OK" : "NO_FOCUSED_TEXT_FIELD";
        }
        if (a.equals("tap")) return "USE_CLICK_TEXT_OR_SCREEN_GESTURE";
        return "UNKNOWN_ACTION";
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>(); q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n.isScrollable()) return n;
            for (int i=0;i<n.getChildCount();i++) { AccessibilityNodeInfo c=n.getChild(i); if(c!=null) q.add(c); }
        }
        return null;
    }
    private AccessibilityNodeInfo findText(AccessibilityNodeInfo root, String wanted) {
        String w = wanted.toLowerCase(Locale.ROOT).trim();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>(); q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            CharSequence t=n.getText(), d=n.getContentDescription();
            if ((!w.isEmpty() && t!=null && t.toString().toLowerCase(Locale.ROOT).contains(w)) ||
                (!w.isEmpty() && d!=null && d.toString().toLowerCase(Locale.ROOT).contains(w))) return n;
            for (int i=0;i<n.getChildCount();i++) { AccessibilityNodeInfo c=n.getChild(i); if(c!=null) q.add(c); }
        }
        return null;
    }
    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>(); q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n=q.removeFirst();
            if (n.isEditable() && n.isFocused()) return n;
            for(int i=0;i<n.getChildCount();i++){ AccessibilityNodeInfo c=n.getChild(i); if(c!=null) q.add(c); }
        }
        return null;
    }
}
