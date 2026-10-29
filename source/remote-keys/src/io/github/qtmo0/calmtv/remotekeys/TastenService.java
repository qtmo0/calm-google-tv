package io.github.qtmo0.calmtv.remotekeys;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Remaps the Google TV Streamer remote's app buttons.
 *
 * The remote sends BUTTON_3 for YouTube, BUTTON_4 for Netflix and MACRO_1 for the star (see
 * /system/usr/keylayout/Vendor_18d1_Product_9451.kl). The system hands those to
 * Google TV's home screen and the Chromecast service as global keys; an
 * accessibility key filter sees them first, so consuming them here wins.
 */
public class TastenService extends AccessibilityService {

    private static final String TAG = "TvTasten";
    // Zattoo's own app links. Opened as Zattoo's first page, the guide shows wherever it was left (hours back);
    // reached from another page it starts at "now". So the star key goes to live TV first, waits until the
    // live view is really on screen, then opens the guide. Counting window events wasn't enough: after a night
    // in standby Zattoo first redraws its old page, and a guide link that meets an open guide does nothing.
    private static final String ZATTOO_GUIDE = "https://zattoo.com/guide";
    private static final String ZATTOO_DETOUR = "https://zattoo.com/live";
    // The live view has ~22 elements (~50 while the channel info shows), the guide ~220. Anything in between
    // counts as "not the guide". Only the count is read, no texts.
    private static final int OTHER_MIN = 12, OTHER_MAX = 120;
    private static final long POLL_MS = 150;
    private static final long SETTLE_MS = 150;    // seen twice in a row: a moment more, then the guide
    private static final long GIVE_UP_MS = 8000;  // live never recognised: open the guide anyway

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String guideFor;  // package waiting for its guide, or null
    private int liveSeen;
    private long pressedAt;
    private final Runnable openGuide = () -> {
        String pkg = guideFor;
        guideFor = null;
        if (pkg == null) return;
        Log.i(TAG, "star: guide after " + (SystemClock.uptimeMillis() - pressedAt) + " ms");
        openLink(pkg, ZATTOO_GUIDE);
    };
    private final Runnable pollLive = new Runnable() {
        @Override public void run() {
            if (guideFor == null) return;
            long waited = SystemClock.uptimeMillis() - pressedAt;
            if (waited >= GIVE_UP_MS) {
                Log.i(TAG, "star: live not recognised, opening guide anyway");
                handler.post(openGuide);
                return;
            }
            int n = elementsOf(guideFor);
            liveSeen = isOtherPage(n) ? liveSeen + 1 : 0;
            Log.i(TAG, "star: " + n + " elements at " + waited + " ms");
            if (liveSeen >= 2) handler.postDelayed(openGuide, SETTLE_MS);
            else handler.postDelayed(this, POLL_MS);
        }
    };

    private static boolean isOtherPage(int elements) {
        return elements >= OTHER_MIN && elements <= OTHER_MAX;
    }

    /** Number of elements in the app's window if it is the active one, else -1. */
    private int elementsOf(String pkg) {
        // Only ask the app's window when it is in front: a video player can take ~5 s to answer.
        if (!pkg.equals(front)) return -1;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !pkg.contentEquals(String.valueOf(root.getPackageName()))) return -1;
        return count(root, 300);
    }

    private static int count(AccessibilityNodeInfo node, int cap) {
        int n = 1;
        for (int i = 0; i < node.getChildCount() && n < cap; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) n += count(child, cap - n);
        }
        return n;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String pkg = targetFor(event.getKeyCode());
        if (pkg == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            handler.removeCallbacks(openGuide);
            handler.removeCallbacks(pollLive);
            guideFor = null;
            if (event.getKeyCode() == KeyEvent.KEYCODE_MACRO_1) {
                guideFor = pkg;
                liveSeen = 0;
                pressedAt = SystemClock.uptimeMillis();
                int n = elementsOf(pkg);
                if (isOtherPage(n)) {
                    // Zattoo is in front on another page: the guide comes up at "now" without a detour.
                    Log.i(TAG, "star: Zattoo shows another page (" + n + " elements), guide directly");
                    openGuide.run();
                } else {
                    Log.i(TAG, "star: live first (" + n + " elements)");
                    openLink(pkg, ZATTOO_DETOUR);
                    handler.postDelayed(pollLive, POLL_MS);
                }
            } else {
                open(pkg);
            }
        }
        return true; // swallow both down and up so the old handler never sees them
    }

    private static String targetFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_3: return "com.google.android.youtube.tv";
            case KeyEvent.KEYCODE_BUTTON_4: return "com.github.damontecres.wholphin";
            case KeyEvent.KEYCODE_MACRO_1: return "com.zattoo.player";
            default: return null;
        }
    }

    private void open(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) { Log.w(TAG, "No launch intent for " + pkg); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open " + pkg, e);
        }
    }

    /** Opens a page inside the app; falls back to its start screen if the link isn't handled. */
    private void openLink(String pkg, String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg);
        // Hand the link to the running window (a second window makes Zattoo crash when it closes); without
        // CLEAR_TOP/SINGLE_TOP Android would only bring the old task to the front and drop the link.
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open " + url + ", starting " + pkg, e);
            open(pkg);
        }
    }

    private String front;  // package of the app in front, from window changes (no content)

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        // System overlays (volume, notifications) and the keyboard don't replace the app underneath.
        if (p.equals("com.android.systemui") || p.equals("android") || p.contains("inputmethod")) return;
        front = p;
    }
    @Override public void onInterrupt() { }
}
