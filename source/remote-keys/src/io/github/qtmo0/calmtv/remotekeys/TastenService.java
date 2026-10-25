package io.github.qtmo0.calmtv.remotekeys;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
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
    // Zattoo's own app link for its "Programm" tab.
    private static final String ZATTOO_GUIDE = "https://zattoo.com/guide";

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String pkg = targetFor(event.getKeyCode());
        if (pkg == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MACRO_1) openLink(pkg, ZATTOO_GUIDE);
            else open(pkg);
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
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open " + url + ", starting " + pkg, e);
            open(pkg);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
