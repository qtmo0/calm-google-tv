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
    // reached from another page it starts at "now". So the star key goes to live TV first, waits until
    // Zattoo has drawn something, then opens the guide.
    private static final String ZATTOO_GUIDE = "https://zattoo.com/guide";
    private static final String ZATTOO_DETOUR = "https://zattoo.com/live";
    private static final int DRAWN_EVENTS = 5;   // window changes from Zattoo before its page counts as drawn
    private static final long SETTLE_MS = 600;    // then a little more time before switching page
    private static final long GIVE_UP_MS = 6000;  // no changes at all: open the guide anyway

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String guideFor;  // package waiting for its guide, or null
    private int changes;
    private long pressedAt;
    private final Runnable openGuide = () -> {
        String pkg = guideFor;
        guideFor = null;
        if (pkg == null) return;
        Log.i(TAG, "star: guide after " + (SystemClock.uptimeMillis() - pressedAt) + " ms, " + changes + " changes");
        openLink(pkg, ZATTOO_GUIDE);
    };

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String pkg = targetFor(event.getKeyCode());
        if (pkg == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            handler.removeCallbacks(openGuide);
            guideFor = null;
            if (event.getKeyCode() == KeyEvent.KEYCODE_MACRO_1) {
                guideFor = pkg;
                changes = 0;
                pressedAt = SystemClock.uptimeMillis();
                Log.i(TAG, "star: live first");
                openLink(pkg, ZATTOO_DETOUR);
                handler.postDelayed(openGuide, GIVE_UP_MS);
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

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (guideFor == null || !guideFor.contentEquals(String.valueOf(event.getPackageName()))) return;
        if (++changes <= DRAWN_EVENTS) Log.i(TAG, "star: change " + changes + " "
                + AccessibilityEvent.eventTypeToString(event.getEventType()) + " at " + (SystemClock.uptimeMillis() - pressedAt) + " ms");
        if (changes == DRAWN_EVENTS) {
            handler.removeCallbacks(openGuide);
            handler.postDelayed(openGuide, SETTLE_MS);
        }
    }
    @Override public void onInterrupt() { }
}
