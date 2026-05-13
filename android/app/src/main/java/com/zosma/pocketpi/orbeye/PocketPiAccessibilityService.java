/*
 * PocketPiAccessibilityService — vendored from KarryViber/orb-eye (MIT license).
 *
 * Original work: Copyright (c) 2026 Orb / @karry_viber
 * https://github.com/KarryViber/orb-eye
 *
 * Modifications for Pocket Pi:
 *   - Stripped the embedded ServerSocket + HTTP routing (lines ~137-295 of
 *     upstream). Pocket Pi's existing PocketPiApiServer (127.0.0.1:9998,
 *     bearer-token gated) is the single auth surface for the agent. Handler
 *     methods exposed as public so PocketPiApiServer can dispatch to them.
 *   - Added a singleton accessor (instance / getInstance) so a bound service
 *     can be reached from the API server's request handlers.
 *   - Added an event ring buffer + sequence cursor + blocking poll, so the
 *     agent can long-poll /ui/events/poll for proactive responses
 *     (notification arrived, window changed, etc.) without a callback channel.
 *
 * Original orb-eye HTTP routes carried over (just routed through the unified
 * 9998 server rather than orb-eye's standalone 7333 server).
 */
package com.zosma.pocketpi.orbeye;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class PocketPiAccessibilityService extends AccessibilityService {
    private static final String TAG = "PocketPiAS";
    private static final int MAX_NOTIFICATIONS = 50;
    private static final int MAX_EVENTS = 200;

    /**
     * Singleton accessor. Null when the user hasn't toggled the service on in
     * Settings -> Accessibility -> Pocket Pi. PocketPiApiServer's UI handlers
     * MUST null-check this and return 403 when absent.
     */
    private static volatile PocketPiAccessibilityService INSTANCE = null;
    public static PocketPiAccessibilityService getInstance() { return INSTANCE; }

    // ===== Notification buffer =====
    private final CopyOnWriteArrayList<JSONObject> notificationBuffer = new CopyOnWriteArrayList<>();

    // ===== Event ring (notifications + window changes), with monotonic seq =====
    // Each event: { seq, timestamp, type: "notification"|"window_changed", ...payload }
    // /ui/events/poll blocks up to timeoutMs and returns events with seq > since.
    private final CopyOnWriteArrayList<JSONObject> eventRing = new CopyOnWriteArrayList<>();
    private final AtomicLong eventSeq = new AtomicLong(0);
    private final Object eventLock = new Object();

    // ===== Wait for UI change =====
    private volatile CountDownLatch uiChangeLatch = new CountDownLatch(1);
    private volatile String lastWindowPackage = "";
    private volatile String lastWindowClass = "";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Log.i(TAG, "PocketPi accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            captureNotification(event);
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (event.getPackageName() != null) {
                    lastWindowPackage = event.getPackageName().toString();
                }
                if (event.getClassName() != null) {
                    lastWindowClass = event.getClassName().toString();
                }
                emitWindowChangedEvent();
            }
            // unblock any /ui/wait callers
            uiChangeLatch.countDown();
        }
    }

    private void captureNotification(AccessibilityEvent event) {
        try {
            JSONObject notif = new JSONObject();
            notif.put("timestamp", System.currentTimeMillis());
            notif.put("package", event.getPackageName() != null ? event.getPackageName().toString() : "");

            JSONArray textArr = new JSONArray();
            if (event.getText() != null) {
                for (CharSequence cs : event.getText()) {
                    if (cs != null) textArr.put(cs.toString());
                }
            }
            notif.put("text", textArr);

            Parcelable parcel = event.getParcelableData();
            if (parcel instanceof Notification) {
                Notification n = (Notification) parcel;
                if (n.extras != null) {
                    String title = n.extras.getString(Notification.EXTRA_TITLE, "");
                    CharSequence body = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                    CharSequence bigText = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                    notif.put("title", title);
                    notif.put("body", body != null ? body.toString() : "");
                    if (bigText != null) notif.put("bigText", bigText.toString());
                }
            }

            notificationBuffer.add(notif);
            while (notificationBuffer.size() > MAX_NOTIFICATIONS) {
                notificationBuffer.remove(0);
            }

            // Also emit as a poll-able event so the agent can react in real time.
            try {
                JSONObject ev = new JSONObject();
                ev.put("type", "notification");
                ev.put("notification", notif);
                emitEvent(ev);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "Notification capture error: " + e.getMessage());
        }
    }

    private void emitWindowChangedEvent() {
        try {
            JSONObject ev = new JSONObject();
            ev.put("type", "window_changed");
            ev.put("package", lastWindowPackage);
            ev.put("activity", lastWindowClass);
            emitEvent(ev);
        } catch (Exception ignored) {}
    }

    private void emitEvent(JSONObject ev) throws Exception {
        long seq = eventSeq.incrementAndGet();
        ev.put("seq", seq);
        ev.put("timestamp", System.currentTimeMillis());
        eventRing.add(ev);
        while (eventRing.size() > MAX_EVENTS) {
            eventRing.remove(0);
        }
        synchronized (eventLock) {
            eventLock.notifyAll();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "PocketPi accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (INSTANCE == this) INSTANCE = null;
    }

    // ============================================================================
    // PUBLIC API — called by PocketPiApiServer's UI handlers.
    // ============================================================================

    /** GET /ui/events/poll?since=<seq>&timeoutMs=<ms>. Returns events with seq > since. */
    public String pollEvents(long since, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            List<JSONObject> matching = new ArrayList<>();
            for (JSONObject ev : eventRing) {
                long s = ev.optLong("seq", 0);
                if (s > since) matching.add(ev);
            }
            if (!matching.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (JSONObject m : matching) arr.put(m);
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("events", arr);
                result.put("cursor", eventSeq.get());
                return result.toString();
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("events", new JSONArray());
                result.put("cursor", eventSeq.get());
                result.put("timeout", true);
                return result.toString();
            }
            synchronized (eventLock) {
                eventLock.wait(remaining);
            }
        }
    }

    /** GET /ui/notifications?clear=true&exclude=pkg1,pkg2&package=pkg */
    public String getNotifications(String filterPkg, List<String> excludePkgs, boolean clear) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject n : notificationBuffer) {
            String pkg = n.optString("package");
            if (filterPkg != null && !pkg.equals(filterPkg)) continue;
            if (excludePkgs != null && excludePkgs.contains(pkg)) continue;
            arr.put(n);
        }
        if (clear) notificationBuffer.clear();

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("notifications", arr);
        result.put("count", arr.length());
        return result.toString();
    }

    /** POST /ui/wait — block until window change or timeout. */
    public String handleWait(long timeoutMs) throws Exception {
        uiChangeLatch = new CountDownLatch(1);
        boolean changed = uiChangeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("changed", changed);
        result.put("timeoutMs", timeoutMs);
        result.put("package", lastWindowPackage);
        result.put("activity", lastWindowClass);
        return result.toString();
    }

    /** GET /ui/info — current app + activity. */
    public String getAppInfo() throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("package", lastWindowPackage);
        result.put("activity", lastWindowClass);
        if (root != null) {
            result.put("windowPackage", root.getPackageName() != null ? root.getPackageName().toString() : "");
            result.put("windowChildCount", root.getChildCount());
            root.recycle();
        }
        return result.toString();
    }

    /** POST /ui/swipe {x1,y1,x2,y2,duration?} */
    public String handleSwipe(JSONObject body) throws Exception {
        int x1 = body.getInt("x1");
        int y1 = body.getInt("y1");
        int x2 = body.getInt("x2");
        int y2 = body.getInt("y2");
        long duration = body.optLong("duration", 300);

        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        boolean dispatched = dispatchGesture(builder.build(), null, null);

        JSONObject result = new JSONObject();
        result.put("ok", dispatched);
        return result.toString();
    }

    /** POST /ui/longpress {x,y,duration?} */
    public String handleLongPress(JSONObject body) throws Exception {
        int x = body.getInt("x");
        int y = body.getInt("y");
        long duration = body.optLong("duration", 1000);

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        boolean dispatched = dispatchGesture(builder.build(), null, null);

        JSONObject result = new JSONObject();
        result.put("ok", dispatched);
        result.put("x", x);
        result.put("y", y);
        return result.toString();
    }

    /** GET /ui/tree — full accessibility node tree, optionally filtered by package. */
    public String getUiTree(String filterPkg) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        JSONObject tree = nodeToJson(root, 0, 15, filterPkg);
        root.recycle();
        return tree != null ? tree.toString() : errorJson("No matching content", "NOT_FOUND");
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo node, int depth, int maxDepth, String filterPkg) throws Exception {
        if (filterPkg != null && node.getPackageName() != null
                && !node.getPackageName().toString().equals(filterPkg)) {
            return null;
        }

        JSONObject obj = new JSONObject();
        obj.put("class", node.getClassName() != null ? node.getClassName().toString() : "");
        obj.put("text", node.getText() != null ? node.getText().toString() : "");
        obj.put("desc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
        obj.put("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
        obj.put("pkg", node.getPackageName() != null ? node.getPackageName().toString() : "");
        obj.put("clickable", node.isClickable());
        obj.put("editable", node.isEditable());
        obj.put("focused", node.isFocused());
        obj.put("selected", node.isSelected());
        obj.put("enabled", node.isEnabled());
        obj.put("scrollable", node.isScrollable());
        obj.put("checked", node.isChecked());

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        obj.put("bounds", bounds.flattenToString());
        obj.put("centerX", bounds.centerX());
        obj.put("centerY", bounds.centerY());
        obj.put("hash", System.identityHashCode(node));

        if (depth < maxDepth) {
            JSONArray children = new JSONArray();
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    JSONObject childJson = nodeToJson(child, depth + 1, maxDepth, filterPkg);
                    if (childJson != null) children.put(childJson);
                    child.recycle();
                }
            }
            if (children.length() > 0) obj.put("children", children);
        }
        return obj;
    }

    /** GET /ui/screen — flat list with optional filters (scrollable, editable, package). */
    public String getScreenElements(boolean onlyScrollable, boolean onlyEditable, String filterPkg) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        JSONArray elements = new JSONArray();
        collectScreenElements(root, elements, onlyScrollable, onlyEditable, filterPkg, false);
        root.recycle();

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("elements", elements);
        return result.toString();
    }

    private void collectScreenElements(AccessibilityNodeInfo node, JSONArray out,
            boolean onlyScrollable, boolean onlyEditable, String filterPkg,
            boolean insideScrollable) throws Exception {
        if (node == null) return;
        String pkg = node.getPackageName() != null ? node.getPackageName().toString() : "";
        if (filterPkg != null && !pkg.equals(filterPkg)) {
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    collectScreenElements(child, out, onlyScrollable, onlyEditable, filterPkg, insideScrollable);
                    child.recycle();
                }
            }
            return;
        }
        boolean nowInsideScrollable = insideScrollable || node.isScrollable();
        String text = node.getText() != null ? node.getText().toString() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        boolean isEditable = node.isEditable();

        boolean passScrollable = !onlyScrollable || nowInsideScrollable;
        boolean passEditable = !onlyEditable || isEditable;

        if (passScrollable && passEditable && (!text.isEmpty() || !desc.isEmpty())) {
            JSONObject item = new JSONObject();
            if (!text.isEmpty()) item.put("text", text);
            if (!desc.isEmpty()) item.put("desc", desc);
            item.put("id", node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "");
            item.put("clickable", node.isClickable());
            item.put("editable", isEditable);
            item.put("scrollable", node.isScrollable());

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            item.put("bounds", bounds.flattenToString());
            item.put("centerX", bounds.centerX());
            item.put("centerY", bounds.centerY());

            out.put(item);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectScreenElements(child, out, onlyScrollable, onlyEditable, filterPkg, nowInsideScrollable);
                child.recycle();
            }
        }
    }

    /** GET /ui/focused — current focused element (input or accessibility). */
    public String getFocusedElement() throws Exception {
        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) focused = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focused == null) return errorJson("No focused element", "NOT_FOUND");

        JSONObject obj = nodeToJson(focused, 0, 0, null);
        focused.recycle();
        return obj != null ? obj.toString() : errorJson("Node unavailable", "NOT_FOUND");
    }

    /** POST /ui/tap {x,y,duration?} */
    public String handleTap(JSONObject body) throws Exception {
        int x = body.getInt("x");
        int y = body.getInt("y");
        long duration = body.optLong("duration", 100);

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        boolean dispatched = dispatchGesture(builder.build(), null, null);

        JSONObject result = new JSONObject();
        result.put("ok", dispatched);
        result.put("x", x);
        result.put("y", y);
        return result.toString();
    }

    /** POST /ui/click {text|desc|id|bounds} — click by element rather than coords. */
    public String handleClick(JSONObject body) throws Exception {
        String targetText = body.optString("text", "");
        String targetDesc = body.optString("desc", "");
        String targetId = body.optString("id", "");
        String targetBounds = body.optString("bounds", "");

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        AccessibilityNodeInfo target = null;

        if (!targetText.isEmpty()) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(targetText);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.isClickable()) { target = n; break; }
                }
                if (target == null) target = findClickableParent(nodes.get(0));
                if (target == null) target = nodes.get(0);
            }
        }
        if (target == null && !targetId.isEmpty()) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(targetId);
            if (nodes != null && !nodes.isEmpty()) target = nodes.get(0);
        }
        if (target == null && !targetDesc.isEmpty()) {
            target = findNodeByDesc(root, targetDesc);
        }
        if (target == null && !targetBounds.isEmpty()) {
            target = findNodeByBounds(root, targetBounds);
        }
        if (target == null) { root.recycle(); return errorJson("Element not found", "NOT_FOUND"); }

        boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            AccessibilityNodeInfo parent = findClickableParent(target);
            if (parent != null) clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        JSONObject result = new JSONObject();
        result.put("ok", clicked);
        result.put("text", target.getText() != null ? target.getText().toString() : "");
        root.recycle();
        return result.toString();
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node.getParent();
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.isClickable()) return current;
            current = current.getParent();
            depth++;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByDesc(AccessibilityNodeInfo root, String desc) {
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.getContentDescription() != null
                    && node.getContentDescription().toString().contains(desc)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByBounds(AccessibilityNodeInfo root, String boundsStr) {
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.flattenToString().equals(boundsStr)) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    /** POST /ui/type {text, append?, clear?} — text injection into focused field. */
    public String handleInput(JSONObject body) throws Exception {
        boolean clearOnly = body.optBoolean("clear", false);
        String text = body.optString("text", "");
        boolean append = body.optBoolean("append", false);

        if (!clearOnly && text.isEmpty()) return errorJson("Provide 'text' or 'clear':true", "INVALID_ARGS");

        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) focused = findFirstEditable(root);
        }
        if (focused == null) return errorJson("No editable field found", "NO_EDITABLE");

        String previousText = focused.getText() != null ? focused.getText().toString() : "";

        if (clearOnly) {
            Bundle clearArgs = new Bundle();
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            boolean cleared = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
            JSONObject result = new JSONObject();
            result.put("ok", cleared);
            result.put("previousText", previousText);
            result.put("action", "clear");
            return result.toString();
        }

        String newText;
        if (append) {
            newText = previousText + text;
        } else {
            Bundle clearArgs = new Bundle();
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
            newText = text;
        }

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
        boolean set = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

        JSONObject result = new JSONObject();
        result.put("ok", set);
        result.put("text", newText);
        result.put("previousText", previousText);
        result.put("action", append ? "append" : "set");
        return result.toString();
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo root) {
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.isEditable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    /** POST /ui/scroll {direction, target?, count?} */
    public String handleScroll(JSONObject body) throws Exception {
        String direction = body.optString("direction", "down");
        String targetText = body.optString("target", "");
        int count = Math.max(1, body.optInt("count", 1));

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        AccessibilityNodeInfo scrollable = !targetText.isEmpty()
                ? findScrollableContaining(root, targetText)
                : findFirstScrollable(root);

        if (scrollable == null) { root.recycle(); return errorJson("No scrollable element found", "NOT_FOUND"); }

        int action = (direction.equals("up") || direction.equals("left"))
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;

        boolean lastResult = false;
        for (int i = 0; i < count; i++) {
            lastResult = scrollable.performAction(action);
            if (i < count - 1) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
        }
        root.recycle();

        JSONObject result = new JSONObject();
        result.put("ok", lastResult);
        result.put("direction", direction);
        result.put("count", count);
        return result.toString();
    }

    private AccessibilityNodeInfo findFirstScrollable(AccessibilityNodeInfo root) {
        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.isScrollable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollableContaining(AccessibilityNodeInfo root, String targetText) {
        AccessibilityNodeInfo textNode = null;
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(targetText);
        if (matches != null && !matches.isEmpty()) textNode = matches.get(0);
        if (textNode == null) return null;

        AccessibilityNodeInfo current = textNode;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current.isScrollable()) return current;
            current = current.getParent();
            depth++;
        }
        return findFirstScrollable(root);
    }

    /** POST /ui/find {text|desc|id, clickable?, index?} */
    public String handleFind(JSONObject body) throws Exception {
        String text = body.optString("text", "");
        String desc = body.optString("desc", "");
        String id = body.optString("id", "");
        boolean onlyClickable = body.optBoolean("clickable", false);
        int index = body.optInt("index", 0);

        if (text.isEmpty() && desc.isEmpty() && id.isEmpty()) {
            return errorJson("Provide 'text', 'desc', or 'id'", "INVALID_ARGS");
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return errorJson("No active window", "NOT_FOUND");

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();

        if (!text.isEmpty()) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
            if (found != null) candidates.addAll(found);
        } else if (!id.isEmpty()) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
            if (found != null) candidates.addAll(found);
        } else {
            collectByDesc(root, desc, candidates);
        }

        if (onlyClickable) {
            List<AccessibilityNodeInfo> filtered = new ArrayList<>();
            for (AccessibilityNodeInfo n : candidates) {
                if (n.isClickable()) filtered.add(n);
                else {
                    AccessibilityNodeInfo parent = findClickableParent(n);
                    if (parent != null) filtered.add(parent);
                }
            }
            candidates = filtered;
        }

        if (candidates.isEmpty()) { root.recycle(); return errorJson("No matching element found", "NOT_FOUND"); }
        if (index >= candidates.size()) {
            root.recycle();
            return errorJson("Index " + index + " out of range (found " + candidates.size() + ")", "NOT_FOUND");
        }

        AccessibilityNodeInfo target = candidates.get(index);
        Rect bounds = new Rect();
        target.getBoundsInScreen(bounds);

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("text", target.getText() != null ? target.getText().toString() : "");
        result.put("desc", target.getContentDescription() != null ? target.getContentDescription().toString() : "");
        result.put("id", target.getViewIdResourceName() != null ? target.getViewIdResourceName() : "");
        result.put("bounds", bounds.flattenToString());
        result.put("centerX", bounds.centerX());
        result.put("centerY", bounds.centerY());
        result.put("clickable", target.isClickable());
        result.put("editable", target.isEditable());
        result.put("scrollable", target.isScrollable());
        result.put("matchCount", candidates.size());
        result.put("index", index);

        root.recycle();
        return result.toString();
    }

    private void collectByDesc(AccessibilityNodeInfo node, String descQuery, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().contains(descQuery)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectByDesc(child, descQuery, out);
        }
    }

    /** GET /ui/screenshot — base64 PNG (API 30+). */
    public String handleScreenshot() throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return errorJson("takeScreenshot requires API 30+, device is API " + Build.VERSION.SDK_INT, "NOT_SUPPORTED");
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> imageRef = new AtomicReference<>(null);
        AtomicReference<String> errorRef = new AtomicReference<>(null);
        AtomicReference<Integer> widthRef = new AtomicReference<>(0);
        AtomicReference<Integer> heightRef = new AtomicReference<>(0);

        takeScreenshot(Display.DEFAULT_DISPLAY,
                getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        try {
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(
                                    screenshot.getHardwareBuffer(),
                                    screenshot.getColorSpace()
                            ).copy(Bitmap.Config.ARGB_8888, false);

                            widthRef.set(bmp.getWidth());
                            heightRef.set(bmp.getHeight());

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
                            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                            imageRef.set("data:image/png;base64," + b64);
                            bmp.recycle();
                        } catch (Exception e) {
                            errorRef.set("Bitmap encode failed: " + e.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        errorRef.set("takeScreenshot failed with code " + errorCode);
                        latch.countDown();
                    }
                });

        boolean done = latch.await(10, TimeUnit.SECONDS);
        if (!done) return errorJson("Screenshot timed out", "TIMEOUT");
        if (errorRef.get() != null) return errorJson(errorRef.get(), "SCREENSHOT_FAILED");

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("image", imageRef.get());
        result.put("width", widthRef.get());
        result.put("height", heightRef.get());
        return result.toString();
    }

    /** GET /ui/clipboard — read system clipboard. */
    public String handleClipboardGet() throws Exception {
        final String[] textHolder = {null};
        final CountDownLatch latch = new CountDownLatch(1);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    CharSequence text = item.getText();
                    textHolder[0] = text != null ? text.toString() : "";
                } else {
                    textHolder[0] = "";
                }
            } catch (Exception e) {
                textHolder[0] = "";
            } finally {
                latch.countDown();
            }
        });

        boolean done = latch.await(3, TimeUnit.SECONDS);
        if (!done) return errorJson("Clipboard read timed out", "TIMEOUT");

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("text", textHolder[0]);
        return result.toString();
    }

    /** POST /ui/clipboard {text} — write system clipboard. */
    public String handleClipboardSet(JSONObject body) throws Exception {
        String text = body.optString("text", "");
        if (text.isEmpty() && !body.has("text")) return errorJson("Provide 'text' field", "INVALID_ARGS");

        final boolean[] successHolder = {false};
        final CountDownLatch latch = new CountDownLatch(1);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    ClipData clip = ClipData.newPlainText("pocket-pi", text);
                    cm.setPrimaryClip(clip);
                    successHolder[0] = true;
                }
            } catch (Exception e) {
                successHolder[0] = false;
            } finally {
                latch.countDown();
            }
        });

        boolean done = latch.await(3, TimeUnit.SECONDS);
        if (!done) return errorJson("Clipboard write timed out", "TIMEOUT");

        JSONObject result = new JSONObject();
        result.put("ok", successHolder[0]);
        result.put("text", text);
        return result.toString();
    }

    /** POST /ui/gesture {type, ...} — pinch_in/out or multi-stroke. */
    public String handleGesture(JSONObject body) throws Exception {
        String type = body.optString("type", "");
        switch (type) {
            case "pinch_in":
            case "pinch_out":
                return handlePinch(body, type.equals("pinch_in"));
            case "multi":
                return handleMultiStroke(body);
            default:
                return errorJson("Unknown gesture type: " + type + ". Use pinch_in, pinch_out, or multi", "INVALID_ARGS");
        }
    }

    private String handlePinch(JSONObject body, boolean pinchIn) throws Exception {
        int cx = body.optInt("x", 540);
        int cy = body.optInt("y", 1200);
        int distance = body.optInt("distance", 200);
        long durationMs = body.optLong("durationMs", 300);
        int half = distance / 2;

        int startX1, startX2, endX1, endX2;
        if (pinchIn) {
            startX1 = cx - half; startX2 = cx + half;
            endX1 = cx; endX2 = cx;
        } else {
            startX1 = cx; startX2 = cx;
            endX1 = cx - half; endX2 = cx + half;
        }

        Path path1 = new Path();
        path1.moveTo(startX1, cy);
        path1.lineTo(endX1, cy);
        Path path2 = new Path();
        path2.moveTo(startX2, cy);
        path2.lineTo(endX2, cy);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path1, 0, durationMs));
        builder.addStroke(new GestureDescription.StrokeDescription(path2, 0, durationMs));

        boolean dispatched = dispatchGesture(builder.build(), null, null);

        JSONObject result = new JSONObject();
        result.put("ok", dispatched);
        result.put("type", pinchIn ? "pinch_in" : "pinch_out");
        result.put("x", cx);
        result.put("y", cy);
        result.put("distance", distance);
        return result.toString();
    }

    private String handleMultiStroke(JSONObject body) throws Exception {
        JSONArray strokes = body.optJSONArray("strokes");
        if (strokes == null || strokes.length() == 0) {
            return errorJson("'strokes' array required for type=multi", "INVALID_ARGS");
        }
        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (int i = 0; i < strokes.length(); i++) {
            JSONObject stroke = strokes.getJSONObject(i);
            JSONArray pathArr = stroke.getJSONArray("path");
            long startMs = stroke.optLong("startMs", 0);
            long durationMs = stroke.optLong("durationMs", 300);
            if (pathArr.length() < 2) return errorJson("Each stroke path needs at least 2 points", "INVALID_ARGS");
            Path p = new Path();
            JSONArray firstPt = pathArr.getJSONArray(0);
            p.moveTo((float) firstPt.getDouble(0), (float) firstPt.getDouble(1));
            for (int j = 1; j < pathArr.length(); j++) {
                JSONArray pt = pathArr.getJSONArray(j);
                p.lineTo((float) pt.getDouble(0), (float) pt.getDouble(1));
            }
            builder.addStroke(new GestureDescription.StrokeDescription(p, startMs, durationMs));
        }
        boolean dispatched = dispatchGesture(builder.build(), null, null);

        JSONObject result = new JSONObject();
        result.put("ok", dispatched);
        result.put("type", "multi");
        result.put("strokeCount", strokes.length());
        return result.toString();
    }

    /** POST /ui/global {action} — back/home/recents/notifications/quick_settings. */
    public String handleGlobalAction(String action) throws Exception {
        int code;
        switch (action) {
            case "back": code = GLOBAL_ACTION_BACK; break;
            case "home": code = GLOBAL_ACTION_HOME; break;
            case "recents": code = GLOBAL_ACTION_RECENTS; break;
            case "notifications": code = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": code = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power_dialog": code = GLOBAL_ACTION_POWER_DIALOG; break;
            default: return errorJson("Unknown global action: " + action, "INVALID_ARGS");
        }
        boolean ok = performGlobalAction(code);
        JSONObject result = new JSONObject();
        result.put("ok", ok);
        result.put("action", action);
        return result.toString();
    }

    // ===== Error helper =====

    private String errorJson(String msg, String code) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ok", false);
            obj.put("error", msg);
            obj.put("code", code);
            return obj.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + msg.replace("\"", "'") + "\",\"code\":\"" + code + "\"}";
        }
    }
}
