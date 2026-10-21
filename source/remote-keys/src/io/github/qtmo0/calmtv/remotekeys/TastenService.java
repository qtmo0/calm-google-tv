package io.github.qtmo0.calmtv.remotekeys;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remaps the Google TV Streamer remote's app buttons.
 *
 * The remote sends BUTTON_3 for YouTube and BUTTON_4 for Netflix (see
 * /system/usr/keylayout/Vendor_18d1_Product_9451.kl). The system hands those to
 * Google TV's home screen and the Chromecast service as global keys; an
 * accessibility key filter sees them first, so consuming them here wins.
 */
public class TastenService extends AccessibilityService {

    private static final String TAG = "TvTasten";

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String pkg = targetFor(event.getKeyCode());
        if (pkg == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            open(pkg);
        }
        return true; // swallow both down and up so the old handler never sees them
    }

    private static String targetFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_3: return "com.google.android.youtube.tv";
            case KeyEvent.KEYCODE_BUTTON_4: return "com.github.damontecres.wholphin";
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

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
