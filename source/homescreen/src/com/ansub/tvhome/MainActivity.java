package com.ansub.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeMap;
import java.util.List;

public class MainActivity extends Activity {

    // Top-row apps, in this order of preference:
    //  1. a list saved at install time:  am start -n com.ansub.tvhome/.MainActivity --es apps "pkg1,pkg2"
    //     (--es apps auto  goes back to 2; add --es stamp <unique> so it's applied only once, see saveConfig)
    //  2. apps.txt (built in as Apps.LIST), topped up with other TV apps installed on this TV.
    // Missing apps are skipped. Topping up stops at six apps, but a chosen list can be any length:
    // apps wrap into rows of six, like Apple TV, and the page scrolls down once they don't all fit.
    // On the TV, hold OK on an app (or press Menu) to move or remove it, or add another; that saves list 1.
    // A network tile always sits at the top right, level with the clock. Settings shortcuts can join
    // it there, off until picked the same way:
    //   --es quick "bluetooth,sound,picture"
    // (--es quick none  takes them away again; "wifi" and "ethernet" are the network tile now).
    private static final String PREFS = "config";
    private static final List<String> NOT_APPS = Arrays.asList("com.android.vending", "com.android.tv.settings",
            "com.android.tv", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher");
    private static final String PLAY_STORE = "com.android.vending";
    private static final String SETTINGS = "settings";
    private static final String ADD = "add";
    private static final String[] EDIT_ITEMS = {"Move", "Replace", "Remove from home", "Uninstall"};
    private static final String[] EDIT_ITEMS_SYSTEM = {"Move", "Replace", "Remove from home", "App info"};
    private static final int PER_ROW = 6, MAX_INPUTS = 4;

    // Settings shortcuts: name, label, icon. Tile keys are QUICK + name.
    private static final String QUICK = "quick:";
    private static final String[] QUICK_NAMES = {"bluetooth", "sound", "picture"};
    private static final String[] QUICK_LABELS = {"Bluetooth", "Sound", "Picture"};
    private static final int[][] QUICK_ICONS = {LineIcon.BLUETOOTH, LineIcon.SOUND, LineIcon.PICTURE};
    private static final String NETWORK = "network";
    // A VPN switch, not something to watch: it sits with the icons at the top right, never in the app row.
    private static final String TAILSCALE = "com.tailscale.ipn";
    private static final String LG_INPUT = "lg:"; // bar keys for an LG TV's inputs: "lg:HDMI_1"
    private static final int OFFLINE = 0, ETHERNET = 4; // network states; 1 to 3 are Wi-Fi bars
    // TCL keeps its own Picture and Sound panels in a service; Android's Sound page is missing there.
    private static final String TCL_SHOW_WINDOW = "com.tcl.settings.SHOW_WINDOW";

    // Layout is designed at 1920x1080 and scaled to the TV's real UI size by px().
    private static final int MARGIN = 96, GAP = 36, RADIUS = 22;
    private static final int ROW1_TOP = 604, ROW_STEP = 224;
    private static final int GRID_MIN_TOP = 400; // more rows push the grid up, but not into the clock
    private static final int ICON_TOP = 76, ICON_BUTTON = 72, ICON_SIZE = 40, ICON_GAP = 12; // level with the date
    private float scale = 1f;

    private static Typeface regular, semibold;

    private FrameLayout root; // the page, inside a vertical scroller
    private int tileW, tileH, gridTop; // app grid geometry, set by buildTiles
    private final List<View> tiles = new ArrayList<>();
    private final List<View> iconCaptions = new ArrayList<>(); // bar captions and divider, rebuilt with the tiles
    private final java.util.Map<View, TextView> captionOf = new java.util.HashMap<>();

    // An LG TV this Streamer is plugged into (see LgTv): its inputs that belong in the bar, and what the
    // menu on the settings icon shows before pairing (null: still looking, "": none on the network).
    private LgTv lg;
    private List<LgTv.Input> lgInputs;
    private String lgInputsShown = "";
    private List<LgTv.Found> lgFound; // LG TVs on the network, for the menu (null: still looking)
    private boolean lgSearching;
    private Dialog openMenu;
    private String lgForegroundSeen;
    // Dots at the bar icons: green = VPN connected; orange = an HDMI device reports "on" (the TV can't
    // tell running from rest mode, so orange means "on or resting"). The HDMI dots can be hidden
    // in the TV menu ("Show device status"), remembered across restarts.
    private static final int DOT_GREEN = 0xFF5DCAA5, DOT_ORANGE = 0xFFF29B4B;
    private static final String SHOW_DEVICE_DOTS = "deviceDots";
    private final List<View> hdmiDots = new ArrayList<>();

    private boolean showDeviceDots() { return getSharedPreferences(PREFS, 0).getBoolean(SHOW_DEVICE_DOTS, true); }
    // Right after switching inputs the TV briefly reports them half-filled (a console without its type,
    // or missing). A step "down" (no longer a console, other name, gone) only counts once it has been
    // reported twice in a row; anything else applies at once. In memory only, so it follows the TV.
    private final java.util.Map<String, LgTv.Input> lgLast = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> lgDoubt = new java.util.HashMap<>();
    private boolean dirty = true;
    private java.util.Locale shownLocale; // the TV's language (and region) when the page was built
    private String focusedKey;
    private final List<String> rowApps = new ArrayList<>(); // the top row as shown, in order
    private String movingKey;   // the app being moved with left/right, if any
    private TextView moveHint;  // shown above the apps while moving
    private boolean longPressed; // OK was held: swallow the key-up so the app doesn't open
    private String okDownOn; // the tile OK went down on; a key-up left over from another app opens nothing

    // Apps installed, removed or updated. This often lands once Home is already back in front (an app
    // uninstalled from the menu is gone only after Android's prompt has closed), so catch up then.
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            dirty = true;
            refreshApps();
        }
    };

    // The network tile follows the connection live: Wi-Fi bars, Ethernet or Offline. Only its icon and
    // label change, so nothing is rebuilt.
    private ImageView netIcon;
    private int netState = -1;
    private final ConnectivityManager.NetworkCallback networkChanged = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { runOnUiThread(MainActivity.this::refreshNetwork); }
        @Override public void onLost(Network n) { runOnUiThread(MainActivity.this::refreshNetwork); }
        // Also fires when the Wi-Fi signal gets stronger or weaker.
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            runOnUiThread(MainActivity.this::refreshNetwork);
        }
    };

    // The VPN dot on the Tailscale icon: green while any VPN is up.
    private View vpnDot;
    private final ConnectivityManager.NetworkCallback vpnChanged = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { runOnUiThread(MainActivity.this::refreshVpn); }
        @Override public void onLost(Network n) { runOnUiThread(MainActivity.this::refreshVpn); }
    };

    // Background and greeting follow the time of day in four calm steps. Checked at the top of each
    // hour, also while another app is in front, so the new background is ready before you come back;
    // and again on every return to Home, in case the device slept through the hour.
    private TextView greeting;
    private int shownPhase = -1;
    private boolean resumed;
    private static final int FADE_MS = 2000;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable phaseTick = new Runnable() {
        @Override public void run() {
            applyPhase(resumed); // on screen: fade; behind another app: swap quietly
            scheduleTick();
        }
    };

    private void scheduleTick() {
        handler.removeCallbacks(phaseTick);
        long now = System.currentTimeMillis();
        handler.postDelayed(phaseTick, 3_600_000 - now % 3_600_000 + 1000);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        saveConfig(getIntent());
        loadFonts();
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        scroller.addView(root, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        shownLocale = java.util.Locale.getDefault();
        String day = Strings.datePattern();
        TextClock date = clock(day, day, px(30), 0xA8FFFFFF, regular);
        TextClock time = clock("h:mm", "H:mm", px(164), Color.WHITE, regular);
        time.setLetterSpacing(-0.02f);
        time.setFontFeatureSettings("tnum");
        time.setIncludeFontPadding(false);
        greeting = text(px(34), 0xB8FFFFFF, regular);
        clock.addView(greeting);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
        dp.topMargin = px(4);
        clock.addView(date, dp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = px(-14); // pull the big digits up under the date
        clock.addView(time, tp);
        root.addView(clock, at(px(MARGIN), px(72), -2, -2));
        lg = new LgTv(this, handler, lgListener);
        applyPhase(false);
        scheduleTick();

        moveHint = text(px(24), 0xA8FFFFFF, regular);
        moveHint.setText(Strings.t("Use the arrows to move it, then press OK"));
        moveHint.setVisibility(View.GONE);
        root.addView(moveHint, at(px(MARGIN), px(ROW1_TOP - 64), -2, -2));

        setContentView(scroller);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);
        IntentFilter tf = new IntentFilter(TEST_PHASE);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(testPhase, tf, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED);
        else registerReceiver(testPhase, tf, android.Manifest.permission.DUMP, null);
        IntentFilter pf = new IntentFilter(LG_PAIR);
        if (android.os.Build.VERSION.SDK_INT >= 33)
            registerReceiver(lgPair, pf, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED);
        else registerReceiver(lgPair, pf, android.Manifest.permission.DUMP, null);
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) try { tim.registerCallback(inputsChanged, handler); } catch (Exception ignored) {}

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) try {
            if (android.os.Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkChanged);
            else cm.registerNetworkCallback(new NetworkRequest.Builder().build(), networkChanged);
        } catch (Exception ignored) {}
        if (cm != null) try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), vpnChanged);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        unregisterReceiver(testPhase);
        unregisterReceiver(lgPair);
        lg.stop();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) try { tim.unregisterCallback(inputsChanged); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) try { cm.unregisterNetworkCallback(networkChanged); } catch (Exception ignored) {}
        if (cm != null) try { cm.unregisterNetworkCallback(vpnChanged); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        resumed = false; // the hourly check keeps running behind other apps
        lg.stop();       // the LG connection only lives while Home is in front
        handler.removeCallbacks(lgKeepAlive);
        if (openMenu != null) openMenu.dismiss();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (saveConfig(intent)) buildTiles();
        if (openMenu != null && intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) openMenu.dismiss();
        // Home pressed while already home: jump back to the first app.
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME) && hasWindowFocus()) jumpToFirst();
    }

    // Back to the first app and the top of the page. Not while an app is being moved (Back ends the
    // move in the tile's own key listener before it gets here); open menus are dialogs and take Back first.
    private void jumpToFirst() {
        if (movingKey != null || tiles.isEmpty()) return;
        tiles.get(0).requestFocus();
        if (root.getParent() instanceof ScrollView) ((ScrollView) root.getParent()).smoothScrollTo(0, 0);
    }

    // Saves an app list passed with --es apps "..." and shortcuts passed with --es quick "...".
    // Returns true if it changed.
    // When Android kills the home screen in the background, it can start it again with the same old
    // intent; the stamp install.sh sends stops that from undoing changes made on the TV since.
    private boolean saveConfig(Intent intent) {
        String apps = intent == null ? null : intent.getStringExtra("apps");
        String quick = intent == null ? null : intent.getStringExtra("quick");
        if (apps == null && quick == null) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String stamp = intent.getStringExtra("stamp");
        if (stamp != null && stamp.equals(prefs.getString("stamp", null))) return false;
        SharedPreferences.Editor e = prefs.edit();
        if (stamp != null) e.putString("stamp", stamp);
        if (apps != null) {
            apps = apps.trim();
            if (apps.isEmpty() || apps.equalsIgnoreCase("auto")) e.remove("apps");
            else e.putString("apps", apps);
        }
        if (quick != null) {
            quick = quick.trim();
            if (quick.isEmpty() || quick.equalsIgnoreCase("none")) e.remove("quick");
            else e.putString("quick", quick);
        }
        e.apply();
        dirty = true;
        return true;
    }

    private List<String> appList(PackageManager pm) {
        List<String> out = new ArrayList<>();
        String saved = getSharedPreferences(PREFS, 0).getString("apps", null);
        String[] wanted = saved != null ? saved.split("[,\\s]+") : Apps.LIST;
        for (String p : wanted) if (!p.isEmpty() && !out.contains(p) && launchIntent(pm, p) != null) out.add(p);
        if (saved == null) {
            // Top up with other TV apps people installed (or updated), skipping launchers and system tools.
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (out.size() >= PER_ROW) break;
                String p = ri.activityInfo.packageName;
                int flags = ri.activityInfo.applicationInfo.flags;
                boolean stockSystem = (flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (stockSystem || out.contains(p) || NOT_APPS.contains(p) || p.equals(getPackageName())) continue;
                out.add(p);
            }
        }
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild only when apps were installed, removed or updated.
        if (dirty) {
            dirty = false;
            buildTiles();
        }
        refreshNetwork();
        refreshVpn();
        if (lg.paired()) { lg.start(false); handler.removeCallbacks(lgKeepAlive); handler.postDelayed(lgKeepAlive, 10_000); }
        else lookForLg();
        // Tailscale sets the VPN up or tears it down a moment after you leave its app: look again.
        handler.postDelayed(this::refreshVpn, 1000);
        handler.postDelayed(this::refreshVpn, 3000);
        // Coming back: if the hour was missed (device asleep), show the right colours straight away.
        applyPhase(false);
        resumed = true;
        scheduleTick();
        View f = getCurrentFocus();
        if (f == null && !tiles.isEmpty()) tiles.get(0).requestFocus();
    }

    // Back from a menu or a window on top (like the uninstall prompt): catch up on apps that changed.
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshApps();
    }

    // Rebuilds the tiles if apps changed, but only while Home is in front with no menu open and no
    // app being moved; otherwise the next onResume, focus change or end of the move does it.
    private void refreshApps() {
        if (!dirty || !hasWindowFocus() || movingKey != null) return;
        dirty = false;
        buildTiles();
    }

    // The TV's language changed: start over so the labels and the date follow it.
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (!java.util.Locale.getDefault().equals(shownLocale)) recreate();
    }

    @Override
    public void onBackPressed() { jumpToFirst(); } // home screen: nothing to go back to, so act like Home

    // Morning, day, evening, night: three diagonal stops and a soft glow from the top left.
    private static final int[][] PHASE_COLORS = {
            {0xFF23192E, 0xFF4A2E3C, 0xFF6E4636, 0x38FFBE8C},
            {0xFF0F2433, 0xFF1B4150, 0xFF2A5F5C, 0x2E8CDCD2},
            {0xFF171430, 0xFF32213F, 0xFF52293C, 0x2EE696B4},
            {0xFF070A14, 0xFF111A2E, 0xFF1C2640, 0x247896DC}};
    private static final String[][] GREETINGS = {
            {"Guten Morgen", "Good morning"}, {"Hallo", "Hello"},
            {"Guten Abend", "Good evening"}, {"Gute Nacht", "Good night"}};

    // For testing the phase change without touching the TV's clock. Only adb may send it (the sender
    // needs the DUMP permission, which the adb shell has and apps don't):
    //   adb shell am broadcast -a com.ansub.tvhome.TEST_PHASE --ei phase 2     (0-3; -1 = real time again)
    // It acts like the top of the hour: fades if Home is on screen, otherwise swaps quietly.
    private static final String TEST_PHASE = "com.ansub.tvhome.TEST_PHASE";
    private static int phaseOverride = -1;
    private final BroadcastReceiver testPhase = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            phaseOverride = Math.max(-1, Math.min(3, i.getIntExtra("phase", -1)));
            android.util.Log.i("tvhome", "test phase " + phaseOverride + (resumed ? " (on screen, fade)" : " (behind, quiet)"));
            applyPhase(resumed);
        }
    };

    private static int phaseNow() {
        if (phaseOverride >= 0) return phaseOverride;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 5 && h < 11 ? 0 : h >= 11 && h < 17 ? 1 : h >= 17 && h < 22 ? 2 : 3;
    }

    // Swaps the window background only when the phase changes: drawn once by the window, no extra view.
    // The final picture is a dithered bitmap (dark gradients band visibly on an OLED and the GPU ignores
    // the dither flag), worked out on a low-priority thread in a fraction of a second.
    //  fade = true (Home on screen): the old picture stays until the new one is ready, then they
    //         cross-fade over FADE_MS.
    //  fade = false (behind another app, at start, or on return): the GPU gradient of the new phase is
    //         set at once, so the old time of day never shows; the bitmap replaces it when ready.
    private void applyPhase(final boolean fade) {
        final int p = phaseNow();
        if (p == shownPhase) return;
        shownPhase = p;
        if (!fade) getWindow().setBackgroundDrawable(background(p));
        boolean de = "de".equals(java.util.Locale.getDefault().getLanguage());
        greeting.setText(GREETINGS[p][de ? 0 : 1]);
        final int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        final float glowR = px(1300);
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            final Bitmap b = ditheredBackground(PHASE_COLORS[p], w, h, glowR);
            runOnUiThread(() -> {
                if (p != shownPhase || isDestroyed()) return;
                android.util.Log.i("tvhome", "background ready, phase " + p + (fade && resumed ? " (fading)" : " (swapped quietly)"));
                final BitmapDrawable next = new BitmapDrawable(getResources(), b);
                final Bitmap prev = shownBitmap;
                shownBitmap = b;
                Drawable old = getWindow().getDecorView().getBackground();
                if (fade && resumed && old != null) {
                    final android.graphics.drawable.TransitionDrawable t =
                            new android.graphics.drawable.TransitionDrawable(new Drawable[] {old, next});
                    t.setCrossFadeEnabled(true);
                    getWindow().setBackgroundDrawable(t);
                    t.startTransition(FADE_MS);
                    // Afterwards keep only the new picture, then free the old one.
                    handler.postDelayed(() -> {
                        if (getWindow().getDecorView().getBackground() == t) getWindow().setBackgroundDrawable(next);
                        freeLater(prev);
                    }, FADE_MS + 100);
                } else {
                    getWindow().setBackgroundDrawable(next);
                    freeLater(prev);
                }
            });
        }, "background").start();
    }

    // An 8 MB background bitmap and its GPU copy would otherwise linger until a garbage collection, which
    // the small Java heap rarely triggers. Freed once a few frames have shown the new one.
    private Bitmap shownBitmap;

    private void freeLater(final Bitmap b) {
        if (b == null) return;
        handler.postDelayed(() -> { if (b != shownBitmap && !b.isRecycled()) b.recycle(); }, 500);
    }

    // The same picture as background(): a top-left to bottom-right gradient through three colours, with
    // the glow on top (fading linearly to nothing at glowR around 15% across the top edge). Worked out in
    // floating point, then each channel gets triangular noise of up to +-1 step before rounding, which
    // turns the 8-bit steps into an even, invisible grain.
    private static Bitmap ditheredBackground(int[] c, int w, int h, float glowR) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] row = new int[w];
        float gx = 0.15f * w, gy = 0, ga = ((c[3] >>> 24) & 0xFF) / 255f;
        float gr = (c[3] >> 16) & 0xFF, gg = (c[3] >> 8) & 0xFF, gb = c[3] & 0xFF;
        float diag = (float) w * w + (float) h * h;
        int seed = 0x9E3779B9;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float t = (x * (float) w + y * (float) h) / diag;
                int c0 = t < 0.5f ? c[0] : c[1], c1 = t < 0.5f ? c[1] : c[2];
                float k = t < 0.5f ? t * 2 : (t - 0.5f) * 2;
                float r = lerp((c0 >> 16) & 0xFF, (c1 >> 16) & 0xFF, k);
                float g = lerp((c0 >> 8) & 0xFF, (c1 >> 8) & 0xFF, k);
                float b = lerp(c0 & 0xFF, c1 & 0xFF, k);
                float dx = x - gx, dy = y - gy;
                float a = ga * Math.max(0f, 1f - (float) Math.sqrt(dx * dx + dy * dy) / glowR);
                r += (gr - r) * a;
                g += (gg - g) * a;
                b += (gb - b) * a;
                // Cheap integer hash per pixel and channel: two uniform values make one triangular one.
                int s = seed ^ (x * 0x27D4EB2D) ^ (y * 0x165667B1);
                s ^= s >>> 15; s *= 0x2C1B3C6D; s ^= s >>> 12; s *= 0x297A2D39; s ^= s >>> 15;
                r = r + ((s & 0xFF) + ((s >>> 8) & 0xFF)) / 255f - 1f;
                g = g + (((s >>> 16) & 0xFF) + ((s >>> 24) & 0xFF)) / 255f - 1f;
                s *= 0x2C1B3C6D; s ^= s >>> 13;
                b = b + ((s & 0xFF) + ((s >>> 8) & 0xFF)) / 255f - 1f;
                row[x] = 0xFF000000 | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1);
        }
        return bmp;
    }

    private static float lerp(float a, float b, float k) { return a + (b - a) * k; }

    private static int clamp8(float v) { return Math.max(0, Math.min(255, Math.round(v))); }

    private Drawable background(int phase) {
        int[] c = PHASE_COLORS[phase];
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {c[0], c[1], c[2]});
        GradientDrawable glow = radial(c[3], 0.15f, 0f, px(1300));
        for (GradientDrawable g : new GradientDrawable[] {base, glow}) g.setDither(true);
        return new LayerDrawable(new Drawable[] {base, glow});
    }

    private static GradientDrawable radial(int color, float cx, float cy, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(r);
        return g;
    }

    private void buildTiles() {
        // While tiles are swapped out, removing the focused one would hand focus down the row (and
        // overwrite focusedKey), so hold focus off until the new tiles are in.
        String want = focusedKey;
        int wantAt = rowApps.indexOf(want); // where it was, in case the app is gone now
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (View t : tiles) {
            View wrap = (View) t.getParent();
            ((ViewGroup) wrap.getParent()).removeView(wrap);
        }
        for (View v : iconCaptions) root.removeView(v);
        hdmiDots.clear();
        iconCaptions.clear();
        captionOf.clear();
        tiles.clear();
        rowApps.clear();
        movingKey = null;
        moveHint.setVisibility(View.GONE);

        PackageManager pm = getPackageManager();
        int m = px(MARGIN), g = px(GAP);
        int w = (getResources().getDisplayMetrics().widthPixels - 2 * m - g * (PER_ROW - 1)) / PER_ROW;
        int h = w * 9 / 16;
        tileW = w;
        tileH = h;

        List<String> apps = appList(pm);
        apps.remove(TAILSCALE);
        // Apps fill rows of six. With more rows the grid starts higher; past two rows the page scrolls.
        // Play Store, Settings and Add app sit with the icons at the top right, so there's no bottom row.
        int launchable = 0;
        for (String pkg : apps) if (launchIntent(pm, pkg) != null) launchable++;
        int rows = Math.max(1, (launchable + PER_ROW - 1) / PER_ROW);
        gridTop = Math.max(px(GRID_MIN_TOP), px(ROW1_TOP) - (rows - 1) * px(ROW_STEP));
        ((FrameLayout.LayoutParams) moveHint.getLayoutParams()).topMargin = gridTop - px(64);
        root.setMinimumHeight(gridTop + (rows - 1) * px(ROW_STEP) + h + px(MARGIN) + px(60));

        for (String pkg : apps) {
            Intent li = launchIntent(pm, pkg);
            if (li == null) continue;
            Drawable banner = null;
            try {
                banner = pm.getActivityBanner(li);
                if (banner == null) banner = pm.getApplicationBanner(pkg);
            } catch (Exception ignored) {}
            String label = pkg;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
            // Apps without a TV banner get a plain tile with their normal icon.
            Drawable icon = null;
            if (banner == null) try { icon = pm.getApplicationIcon(pkg); } catch (Exception ignored) {}
            int i = rowApps.size();
            rowApps.add(pkg);
            addTile(root, pkg, label, banner, null, icon, gridX(i), gridY(i), w, h);
        }

        // Plain icons at the top right, level with the greeting, in two groups split by a thin line:
        // status (settings shortcuts, Tailscale, network), then actions (HDMI inputs, Play Store,
        // Settings, Add app). Added last, so the first app keeps first focus; Up from the apps reaches them.
        boolean vpn = launchIntent(pm, TAILSCALE) != null;
        // The bar stays the same whatever is in the app row (also when Play Store is a tile there too).
        boolean store = launchIntent(pm, PLAY_STORE) != null;
        // HDMI inputs: from the paired LG TV (see LgTv) if there is one, else the TV's own inputs (Google TV
        // built into a TV). Each: key, name, game console or not, device on or not.
        List<String[]> inputs = new ArrayList<>();
        if (lg.paired()) {
            if (lgInputs != null) for (LgTv.Input in : lgInputs)
                inputs.add(new String[] {LG_INPUT + in.id, in.label, in.kind, in.on ? "on" : ""});
        } else {
            for (TvInputInfo in : hdmiInputs()) inputs.add(new String[] {in.getId(), inputLabel(in), "", ""});
        }
        List<Integer> quick = quickList();
        int ib = px(ICON_BUTTON), ig = px(ICON_GAP), split = px(36);
        int count = quick.size() + (vpn ? 1 : 0) + 1 + (store ? 1 : 0) + inputs.size() + 2;
        int splits = inputs.isEmpty() ? 1 : 2; // a second line right of the HDMI inputs, only if there are any
        // The last icon's right edge lines up with the apps' (its focus circle sticks out past them).
        int qx = getResources().getDisplayMetrics().widthPixels - m + (ib - px(ICON_SIZE)) / 2
                - count * ib - (count - 1) * ig - splits * split;
        for (int q : quick) {
            addIcon(QUICK + QUICK_NAMES[q], Strings.t(QUICK_LABELS[q]), new LineIcon(QUICK_ICONS[q]), qx);
            qx += ib + ig;
        }
        vpnDot = null;
        if (vpn) {
            ImageView iv = addIcon(TAILSCALE, "Tailscale", new LineIcon(LineIcon.SHIELD), qx);
            vpnDot = addDot(iv, DOT_GREEN);
            qx += ib + ig;
        }
        netIcon = addIcon(NETWORK, Strings.t("Network"), null, qx);
        netState = -1;
        refreshNetwork();
        refreshVpn();
        qx += ib + ig;

        qx = addDivider(qx, ig, ib, split);

        // HDMI inputs first, left of Play Store, then a second line.
        for (String[] in : inputs) {
            int[] icon = "game".equals(in[2]) ? LineIcon.CONTROLLER : "box".equals(in[2]) ? LineIcon.BOX
                    : "disc".equals(in[2]) ? LineIcon.DISC : "pc".equals(in[2]) ? LineIcon.LAPTOP : LineIcon.HDMI;
            ImageView iv = addIcon(in[0], in[1], new LineIcon(icon), qx);
            if ("on".equals(in[3])) { // the device reports "on" (or is resting, see DOT_ORANGE)
                View dot = addDot(iv, DOT_ORANGE);
                dot.setVisibility(showDeviceDots() ? View.VISIBLE : View.INVISIBLE);
                hdmiDots.add(dot);
            }
            qx += ib + ig;
        }
        if (!inputs.isEmpty()) qx = addDivider(qx, ig, ib, split);
        if (store) {
            addIcon(PLAY_STORE, "Play Store", new LineIcon(LineIcon.PLAY), qx);
            qx += ib + ig;
        }
        addIcon(SETTINGS, Strings.t("Settings"), new LineIcon(LineIcon.SETTINGS), qx);
        qx += ib + ig;
        addIcon(ADD, Strings.t("Add app"), new LineIcon(LineIcon.PLUS), qx);

        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // An app that was uninstalled hands focus to the one now in its place, as Remove does.
        if (wantAt >= 0 && !rowApps.contains(want))
            want = rowApps.isEmpty() ? ADD : rowApps.get(Math.min(wantAt, rowApps.size() - 1));
        View restore = null;
        for (View t : tiles) if (t.getTag().equals(want)) restore = t;
        if (restore != null) restore.requestFocus();
    }

    // A thin vertical line between groups in the bar. Returns where the next icon starts.
    private int addDivider(int qx, int ig, int ib, int split) {
        View line = new View(this);
        line.setBackgroundColor(0x33FFFFFF);
        root.addView(line, at(qx - ig + (split + ig) / 2 - 1, px(ICON_TOP) + ib / 2 - px(18), Math.max(1, px(2)), px(36)));
        iconCaptions.add(line);
        return qx + split;
    }

    // The small dot at an icon's lower right (see DOT_GREEN, DOT_ORANGE).
    private View addDot(ImageView iv, int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        View v = new View(this);
        v.setBackground(dot);
        int d = px(14);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(d, d, Gravity.CENTER);
        lp.leftMargin = px(18);
        lp.topMargin = px(18);
        ((FrameLayout) iv.getParent()).addView(v, lp);
        return v;
    }

    private void addTile(ViewGroup parent, final String key, String label, Drawable banner, int[] lineIcon, Drawable appIcon,
                         int x, int y, int w, int h) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        if (banner != null) {
            bg.setColor(0xFF1E1A1C);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(fit(banner, w, h));
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // Quiet "glass" tile: icon above label.
            bg.setColor(0x13FFFFFF);
            bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            int size = lineIcon != null ? px(44) : px(64);
            if (lineIcon != null) iv.setImageDrawable(new LineIcon(lineIcon));
            else if (appIcon != null) iv.setImageDrawable(fit(appIcon, size, size));
            inner.addView(iv, new LinearLayout.LayoutParams(size, size));
            TextView t = text(px(24), 0xE0FFFFFF, regular);
            t.setText(label);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(16), 0, px(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = px(12);
            inner.addView(t, lp);
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setTag(key);
        // A thin white ring while focused, drawn over the banner. Fixed shape, only its color changes.
        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(RADIUS));
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(Math.max(2, px(4)), Color.TRANSPARENT);
        card.setForeground(ring);

        // Banner tiles show their name underneath when focused.
        final TextView caption = banner == null ? null : text(px(24), Color.WHITE, regular);
        if (caption != null) {
            caption.setText(label);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            caption.setVisibility(View.INVISIBLE);
        }

        final boolean glass = banner == null;
        card.setOnFocusChangeListener((v, has) -> {
            if (has) focusedKey = key;
            if (glass) bg.setColor(has ? 0x38FFFFFF : 0x13FFFFFF);
            ring.setStroke(Math.max(2, px(4)), has ? 0xE6FFFFFF : Color.TRANSPARENT);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? px(26) : 0)
                    .setDuration(140).withLayer().start();
            if (caption != null) caption.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        final boolean editable = rowApps.contains(key);
        card.setOnClickListener(v -> open(key));
        if (editable) card.setOnLongClickListener(v -> { showEditMenu(key); return true; });
        card.setOnKeyListener((v, code, ev) -> {
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
            if (key.equals(movingKey)) return moveKey(key, code, ok, down);
            // Left/Right walk the apps in reading order: past the end of a row into the next one, back from
            // the start of a row to the end of the one above, and round from the last app to the first.
            // Not while moving an app; the icons keep Android's own focus. Up/Down are left to Android too.
            if (editable && movingKey == null && rowApps.size() > 1
                    && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (down) {
                    int at = rowApps.indexOf(key), n = rowApps.size();
                    int to = code == KeyEvent.KEYCODE_DPAD_LEFT ? at - 1 : at + 1;
                    boolean wraps = to < 0 || to >= n;
                    // A held-down key steps through the apps but stops at the ends instead of spinning round.
                    if (at >= 0 && !(wraps && ev.getRepeatCount() > 0)) {
                        View t = tile(rowApps.get((to + n) % n));
                        if (t != null) t.requestFocus();
                    }
                }
                return true;
            }
            if (editable && code == KeyEvent.KEYCODE_MENU) {
                if (!down) showEditMenu(key);
                return true;
            }
            if (!ok) return false;
            if (down) {
                if (ev.getRepeatCount() == 0) { longPressed = false; okDownOn = key; }
                else if (editable && !longPressed && (ev.isLongPress()
                        || ev.getEventTime() - ev.getDownTime() >= ViewConfiguration.getLongPressTimeout())) {
                    longPressed = true;
                    showEditMenu(key);
                }
            } else {
                boolean mine = key.equals(okDownOn);
                okDownOn = null;
                if (mine && !longPressed) open(key);
            }
            return true;
        });

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(w, h));
        if (caption != null) {
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(w, -2);
            cp.topMargin = h + px(22);
            wrap.addView(caption, cp);
        }
        parent.addView(wrap, at(x, y, w, -2));
        tiles.add(card);
    }

    // An icon on its own, no tile or label: a glass circle shows behind it only while it's focused.
    private ImageView addIcon(final String key, String label, Drawable icon, int x) {
        int b = px(ICON_BUTTON), size = px(ICON_SIZE);
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        card.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        card.addView(iv, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        card.setFocusable(true);
        card.setTag(key);
        card.setContentDescription(label);
        // Its name shows underneath while focused, like the app tiles. The text is read from the content
        // description at that moment, so live states (Wi-Fi/Ethernet/Offline, VPN on/off) are current.
        // Centred under the icon, but kept on screen at the right edge.
        final TextView caption = text(px(24), Color.WHITE, regular);
        caption.setSingleLine(true);
        caption.setVisibility(View.INVISIBLE);
        root.addView(caption, at(0, px(ICON_TOP) + b + px(10), -2, -2));
        captionOf.put(card, caption);
        card.setOnFocusChangeListener((v, has) -> {
            if (has) focusedKey = key;
            bg.setColor(has ? 0x38FFFFFF : Color.TRANSPARENT);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).withLayer().start();
            if (has) updateCaption(v);
            caption.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        card.setOnClickListener(v -> open(key));
        if (SETTINGS.equals(key)) {
            // Hold OK (or press Menu) on the settings icon: the TV menu. A plain OK still opens Settings.
            card.setOnLongClickListener(v -> { showTvMenu(v); return true; });
            card.setOnKeyListener((v, code, ev) -> {
                if (code != KeyEvent.KEYCODE_MENU) return false;
                if (ev.getAction() == KeyEvent.ACTION_UP) showTvMenu(v);
                return true;
            });
        }
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.addView(card, new FrameLayout.LayoutParams(b, b));
        root.addView(wrap, at(x, px(ICON_TOP), b, b));
        tiles.add(card);
        iconCaptions.add(caption);
        return iv;
    }

    // Puts the icon's current name under it, centred, but kept on screen at the right edge. Called on
    // focus and whenever a live state (network, VPN) changes while the icon is focused.
    private void updateCaption(View card) {
        TextView caption = captionOf.get(card);
        if (caption == null) return;
        CharSequence name = card.getContentDescription();
        caption.setText(name);
        int b = px(ICON_BUTTON), screenW = getResources().getDisplayMetrics().widthPixels;
        int center = ((FrameLayout.LayoutParams) ((View) card.getParent()).getLayoutParams()).leftMargin + b / 2;
        int tw = (int) Math.ceil(caption.getPaint().measureText(name == null ? "" : name.toString()));
        ((FrameLayout.LayoutParams) caption.getLayoutParams()).leftMargin =
                Math.max(px(24), Math.min(center - tw / 2, screenW - px(24) - tw));
        caption.requestLayout();
    }

    private void open(String key) {
        Intent i;
        if (ADD.equals(key)) {
            showAddMenu(-1);
            return;
        } else if (SETTINGS.equals(key)) {
            i = new Intent(Settings.ACTION_SETTINGS);
        } else if (isInput(key)) {
            openInput(key);
            return;
        } else if (NETWORK.equals(key)) {
            openNetworkSettings();
            return;
        } else if (key.startsWith(LG_INPUT)) {
            lg.switchTo(key.substring(LG_INPUT.length()));
            return;
        } else if (key.startsWith(QUICK)) {
            openQuick(key.substring(QUICK.length()));
            return;
        } else {
            i = launchIntent(getPackageManager(), key);
            if (i == null) return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    // Live TV apps an HDMI tile can open, in order. Droidlogic (Amlogic) TVs ship Live TV under their
    // own package, with TvActivity as its entry point.
    private static final String[][] LIVE_TV = {{"com.android.tv", "com.android.tv.MainActivity"},
            {"com.droidlogic.android.tv", "com.android.tv.TvActivity"}};
    private static final String INPUT_PLAYER = "com.google.android.tv.inputplayer";

    // Opens an HDMI input: the stock Live TV app first, then Droidlogic's, then whatever the TV picks.
    // Each one is tried only when the one before it can't start, so a TV where the stock app already
    // worked takes exactly the same path as before. The one exception: when the TV itself sends HDMI to
    // Google TV's input player, that goes first, because some Google TVs ship com.android.tv as a stub
    // that starts fine but only says "Hello World!" (issue #5).
    private void openInput(String id) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        List<Intent> tries = new ArrayList<>();
        ResolveInfo def = getPackageManager().resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && INPUT_PLAYER.equals(def.activityInfo.packageName))
            tries.add(new Intent(view).setClassName(INPUT_PLAYER, def.activityInfo.name));
        for (String[] live : LIVE_TV) {
            Intent t = new Intent(view).setClassName(live[0], live[1]);
            if (t.resolveActivity(getPackageManager()) != null) tries.add(t);
        }
        tries.add(view);
        for (Intent t : tries) {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(t);
                return;
            } catch (Exception ignored) {}
        }
    }

    // The shortcuts picked with --es quick, in that order, as indexes into QUICK_NAMES. A shortcut
    // this TV has no screen for is left out, so it never shows a tile that does nothing.
    private List<Integer> quickList() {
        List<Integer> out = new ArrayList<>();
        String saved = getSharedPreferences(PREFS, 0).getString("quick", null);
        if (saved == null) return out;
        for (String name : saved.split("[,\\s]+")) {
            int q = Arrays.asList(QUICK_NAMES).indexOf(name.toLowerCase(java.util.Locale.ROOT));
            if (q < 0 || out.contains(q) || quickIntents(QUICK_NAMES[q]).isEmpty()) continue;
            out.add(q);
        }
        return out;
    }

    // OFFLINE, 1 to 3 for Wi-Fi with that many bars, or ETHERNET (any other link, which on a TV is a cable).
    private int networkState() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return OFFLINE;
        try {
            int rssi = Integer.MIN_VALUE;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (c == null) return OFFLINE;
                if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return ETHERNET;
                if (android.os.Build.VERSION.SDK_INT >= 29) rssi = c.getSignalStrength();
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni == null || !ni.isConnected()) return OFFLINE;
                if (ni.getType() != ConnectivityManager.TYPE_WIFI) return ETHERNET;
            }
            if (rssi == Integer.MIN_VALUE) { // Android 9 and older: ask the Wi-Fi service
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.getConnectionInfo() != null) rssi = wm.getConnectionInfo().getRssi();
            }
            return 1 + WifiManager.calculateSignalLevel(rssi, 3);
        } catch (Exception e) {
            return OFFLINE;
        }
    }

    private void refreshNetwork() {
        if (netIcon == null) return;
        int state = networkState();
        if (state == netState) return;
        netState = state;
        if (state == ETHERNET) {
            netIcon.setImageDrawable(new LineIcon(LineIcon.ETHERNET));
        } else {
            // The whole Wi-Fi fan dim, with the bars it has bright on top. Offline is a slash instead.
            LineIcon fan = new LineIcon(LineIcon.WIFI);
            fan.setAlpha(0x47);
            int[] top = state == OFFLINE ? LineIcon.SLASH : state == 1 ? LineIcon.WIFI_1
                    : state == 2 ? LineIcon.WIFI_2 : LineIcon.WIFI;
            netIcon.setImageDrawable(new LayerDrawable(new Drawable[] {fan, new LineIcon(top)}));
        }
        ((View) netIcon.getParent()).setContentDescription(
                state == OFFLINE ? Strings.t("Offline") : state == ETHERNET ? "Ethernet"
                        : "de".equals(java.util.Locale.getDefault().getLanguage()) ? "WLAN" : "Wi-Fi");
        View netCard = (View) netIcon.getParent();
        if (netCard.isFocused()) updateCaption(netCard);
    }

    // Shows the green dot on the Tailscale icon while a VPN is connected.
    @SuppressWarnings("deprecation")
    private void refreshVpn() {
        if (vpnDot == null) return;
        boolean up = false;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && android.os.Build.VERSION.SDK_INT >= 23) try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) up = true;
            }
        } catch (Exception ignored) {}
        vpnDot.setVisibility(up ? View.VISIBLE : View.INVISIBLE);
        View card = (View) vpnDot.getParent();
        card.setContentDescription(up ? "Tailscale: VPN an" : "Tailscale: VPN aus");
        if (card.isFocused()) updateCaption(card);
    }

    // Wi-Fi settings (on Android TV that page lists Ethernet too), else the TV's network page, else Settings.
    private void openNetworkSettings() {
        for (String a : new String[] {Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS,
                Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }
    }

    // Screens a shortcut can open, best first, keeping only those this TV has.
    private List<Intent> quickIntents(String name) {
        List<Intent> all = new ArrayList<>();
        String tclType = name.equals("sound") ? "SoundPreset" : name.equals("picture") ? "picture" : null;
        if (tclType != null)
            all.add(new Intent(TCL_SHOW_WINDOW).setClassName("com.tcl.settings", "com.tcl.settings.ShowWindowService")
                    .putExtra("Type", tclType));
        switch (name) {
            case "bluetooth":
                all.add(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                all.add(new Intent("com.google.android.intent.action.CONNECT_INPUT")); // Android TV's pairing screen
                break;
            case "sound": all.add(new Intent(Settings.ACTION_SOUND_SETTINGS)); break;
            case "picture": all.add(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); break;
        }
        PackageManager pm = getPackageManager();
        List<Intent> out = new ArrayList<>();
        for (Intent i : all) {
            boolean service = TCL_SHOW_WINDOW.equals(i.getAction());
            if (service ? pm.resolveService(i, 0) != null : i.resolveActivity(pm) != null) out.add(i);
        }
        return out;
    }

    // Opens a shortcut's screen: the first one that starts wins.
    private void openQuick(String name) {
        for (Intent i : quickIntents(name)) {
            try {
                if (TCL_SHOW_WINDOW.equals(i.getAction())) startService(i);
                else startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }
    }

    interface Pick { void at(int which); }

    // A side panel in the home screen's own look: a title, then one row per choice, with the same
    // glass focus as the tiles. Back closes it. iconPkgs (or null) puts each app's icon by its name.
    private void panel(String title, final List<String> labels, final List<String> iconPkgs, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(48), px(92), px(48), px(48));

        TextView head = text(px(40), Color.WHITE, semibold);
        head.setText(title);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(px(24), 0, px(24), 0);
        box.addView(head);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(RADIUS));
        focus.setColor(0x38FFFFFF);
        list.setSelector(focus);
        // Icons load as rows appear, drawn small to save RAM.
        final PackageManager pm = getPackageManager();
        final int size = px(48);
        final Drawable[] icons = new Drawable[labels.size()];
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.size(); }
            @Override public Object getItem(int pos) { return labels.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = convert != null ? (TextView) convert : text(px(30), 0xE6FFFFFF, regular);
                t.setText(labels.get(pos));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(px(88));
                t.setPadding(px(24), 0, px(24), 0);
                Drawable icon = null;
                if (iconPkgs != null) {
                    if (icons[pos] == null) {
                        try { icons[pos] = fit(pm.getApplicationIcon(iconPkgs.get(pos)), size, size); }
                        catch (Exception e) { icons[pos] = new ColorDrawable(Color.TRANSPARENT); }
                        icons[pos].setBounds(0, 0, size, size);
                    }
                    icon = icons[pos];
                }
                t.setCompoundDrawablesRelative(icon, null, null, null);
                t.setCompoundDrawablePadding(px(24));
                return t;
            }
        });
        list.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            pick.at(pos);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = px(36);
        box.addView(list, lp);

        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0xF7161113));
        w.setLayout(px(640), -1);
        w.setGravity(Gravity.END);
        w.setDimAmount(0.5f);
        d.show();
    }

    // A small menu that drops down under a tile, in the same glass as the tiles. Opens above the
    // tile when there's no room below. Back closes it.
    private void dropdown(View anchor, String[] items, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = px(10), rowH = px(64);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        bg.setColor(0xFF231D1F);
        bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
        box.setBackground(bg);
        float widest = 0; // some languages need more room than the tile is wide
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            final TextView t = text(px(26), 0xD9FFFFFF, regular);
            t.setText(Strings.t(items[i]));
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(22), 0, px(22), 0);
            widest = Math.max(widest, t.getPaint().measureText(t.getText().toString()) + 2 * px(22) + 2 * pad);
            final GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(px(RADIUS - pad));
            focus.setColor(Color.TRANSPARENT);
            t.setBackground(focus);
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                focus.setColor(has ? 0x38FFFFFF : Color.TRANSPARENT);
                t.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
            });
            t.setOnClickListener(v -> {
                d.dismiss();
                pick.at(which);
            });
            box.addView(t, new LinearLayout.LayoutParams(-1, rowH));
        }
        d.setContentView(box);

        // Line it up with the tile as drawn (focused tiles are scaled up), below its name.
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int tileBottom = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY());
        int w = Math.max(Math.max(Math.round(anchor.getWidth() * anchor.getScaleX()), px(320)), (int) Math.ceil(widest));
        int h = items.length * rowH + 2 * pad;
        int y = tileBottom + px(64);
        if (y + h > getResources().getDisplayMetrics().heightPixels - px(24)) y = at[1] - h - px(16);
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setGravity(Gravity.TOP | Gravity.START);
        android.view.WindowManager.LayoutParams lp = win.getAttributes();
        lp.x = at[0];
        lp.y = y;
        lp.width = w;
        lp.height = h;
        win.setAttributes(lp);
        d.show();
    }

    // Hold OK (or Menu) on a top-row app: move it, swap it for another, or take it off the home
    // screen. Removing only takes the tile away: the app stays installed and can be added back.
    // Apps the user installed can also be uninstalled; Android asks to confirm, and the tile goes
    // once the app is gone. Apps that came with the TV can't be, so they get App info instead,
    // where Android offers to turn them off.
    private void showEditMenu(final String key) {
        final int index = rowApps.indexOf(key);
        if (index < 0) return;
        final boolean user = userInstalled(key);
        dropdown(tile(key), user ? EDIT_ITEMS : EDIT_ITEMS_SYSTEM, which -> {
            if (which == 0) startMove(key);
            else if (which == 1) showAddMenu(index);
            else if (which == 3) {
                Uri pkg = Uri.parse("package:" + key);
                try { startActivity(user ? new Intent(Intent.ACTION_DELETE, pkg)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); }
                catch (Exception ignored) {}
            } else {
                rowApps.remove(index);
                saveRow();
                focusedKey = rowApps.isEmpty() ? ADD : rowApps.get(Math.min(index, rowApps.size() - 1));
                buildTiles();
            }
        });
    }

    // Every launchable app that isn't on the top row yet, by name. The pick is added at the end,
    // or takes the place of the app at replaceIndex (-1 to add).
    private void showAddMenu(final int replaceIndex) {
        final PackageManager pm = getPackageManager();
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (rowApps.contains(p) || byLabel.containsValue(p) || p.equals(getPackageName())
                        || (NOT_APPS.contains(p) && !p.equals(PLAY_STORE))) continue;
                String label = labelOf(p);
                if (!byLabel.containsKey(label)) byLabel.put(label, p);
            }
        }
        if (byLabel.isEmpty()) {
            panel(Strings.t("Every app is already here"), Collections.singletonList("OK"), null, which -> {});
            return;
        }
        final List<String> pkgs = new ArrayList<>(byLabel.values());
        String title = replaceIndex < 0 ? Strings.t("Add an app")
                : String.format(Strings.t("Replace %s"), labelOf(rowApps.get(replaceIndex)));
        panel(title, new ArrayList<>(byLabel.keySet()), pkgs, which -> {
            if (replaceIndex < 0) rowApps.add(pkgs.get(which));
            else rowApps.set(replaceIndex, pkgs.get(which));
            saveRow();
            focusedKey = pkgs.get(which);
            buildTiles();
        });
    }

    private String labelOf(String pkg) {
        PackageManager pm = getPackageManager();
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    // Saves the top row as the app list, so it no longer follows apps.txt.
    private void saveRow() {
        getSharedPreferences(PREFS, 0).edit().putString("apps", android.text.TextUtils.join(",", rowApps)).apply();
    }

    // Where app number i sits in the grid.
    private int gridX(int i) {
        return px(MARGIN) + (i % PER_ROW) * (tileW + px(GAP));
    }

    private int gridY(int i) {
        return gridTop + (i / PER_ROW) * px(ROW_STEP);
    }

    private View tile(String key) {
        for (View t : tiles) if (t.getTag().equals(key)) return t;
        return null;
    }

    // Move mode: the tile gets an outline, the arrows move it through the grid, OK or Back finishes.
    private void startMove(String key) {
        View card = tile(key);
        if (card == null) return;
        movingKey = key;
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(px(RADIUS));
        outline.setStroke(px(4), Color.WHITE);
        ((FrameLayout) card).setForeground(outline);
        moveHint.setVisibility(View.VISIBLE);
        card.requestFocus();
    }

    private boolean moveKey(String key, int code, boolean ok, boolean down) {
        int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : code == KeyEvent.KEYCODE_DPAD_UP ? -PER_ROW : code == KeyEvent.KEYCODE_DPAD_DOWN ? PER_ROW : 0;
        if (step != 0) {
            int from = rowApps.indexOf(key), to = from + step;
            if (down && to >= 0 && to < rowApps.size()) {
                // Slide the tiles into their new places: no rebuild, so it stays quick on slow TVs.
                rowApps.add(to, rowApps.remove(from));
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                    View wrap = (View) tile(rowApps.get(i)).getParent();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wrap.getLayoutParams();
                    lp.leftMargin = gridX(i);
                    lp.topMargin = gridY(i);
                    wrap.setLayoutParams(lp);
                }
                // Scroll along with it once it has moved.
                final View card = tile(key);
                card.post(() -> card.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight())));
            }
            return true;
        }
        if (ok || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                movingKey = null;
                longPressed = false;
                ((FrameLayout) tile(key)).setForeground(null);
                moveHint.setVisibility(View.GONE);
                saveRow();
                refreshApps();
            }
            return true;
        }
        return true; // up/down and everything else stay put while moving
    }

    private static boolean isInput(String key) {
        return key.indexOf('/') > 0;
    }

    // The TV's own HDMI inputs (not devices behind them), in order, skipping hidden ones.
    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo in : tim.getTvInputList()) {
            if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null && !in.isHidden(this)
                    && hdmiActive(tim, in)) out.add(in);
        }
        Collections.sort(out, (a, b) -> a.getId().compareTo(b.getId()));
        return out.size() > MAX_INPUTS ? out.subList(0, MAX_INPUTS) : out;
    }

    // Only inputs with a device plugged in: connected, or in standby (a console in rest mode can usually
    // be woken over HDMI-CEC). How well this works depends on the TV maker's input service; one that
    // can't tell reports "connected", so then every input shows, as before. If asking fails, show it.
    private static boolean hdmiActive(TvInputManager tim, TvInputInfo in) {
        try {
            return tim.getInputState(in.getId()) != TvInputManager.INPUT_STATE_DISCONNECTED;
        } catch (Exception e) {
            return true;
        }
    }

    // Plugging a device in or out (or switching it fully off) changes the bar live.
    private final TvInputManager.TvInputCallback inputsChanged = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String id, int state) { inputsChanged(); }
        @Override public void onInputAdded(String id) { inputsChanged(); }
        @Override public void onInputRemoved(String id) { inputsChanged(); }
        @Override public void onInputUpdated(String id) { inputsChanged(); }
    };

    private void inputsChanged() {
        dirty = true;
        refreshApps(); // rebuilds now if Home is in front, else on the way back
    }

    // ---- LG TV: inputs in the bar, the TV menu on the settings icon ------------------------------

    // Pairing from the computer, for setup with Claude Code: tvctl.sh lg-pair. Like the menu's
    // "Connect TV"; only adb can send it (DUMP permission).
    private static final String LG_PAIR = "com.ansub.tvhome.LG_PAIR";
    private final BroadcastReceiver lgPair = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Optional --es host <IP of the TV>, for networks with several LG TVs.
            String host = i.getStringExtra("host");
            List<LgTv.Found> found = lgFound;
            if (host != null) {
                LgTv.Found f = new LgTv.Found();
                f.host = host;
                if (found != null) for (LgTv.Found x : found) if (host.equals(x.host)) f = x;
                connectLg(f);
            } else if (found != null && found.size() > 1) {
                android.util.Log.i("tvhome-lg", "pairing refused: several LG TVs, give one with --es host");
            } else {
                connectLg(found != null && found.size() == 1 ? found.get(0) : null);
            }
        }
    };

    private final LgTv.Listener lgListener = new LgTv.Listener() {
        @Override public void onInputs(List<LgTv.Input> all, String foreground) {
            // Which input is this Streamer? The one whose device calls itself by our device name over
            // HDMI-CEC ("Wohnzimmer"). Found afresh every time, so moving the Streamer to another port
            // just works. Fallback when CEC is off: the input the TV shows while Home is on screen, but
            // only once it has said so twice in a row (right after switching back it still names the
            // input we came from).
            SharedPreferences p = getSharedPreferences(LgTv.PREFS, 0);
            String own = null;
            String me = Settings.Global.getString(getContentResolver(), "device_name");
            if (me != null) for (LgTv.Input in : all) if (me.equals(in.cecName)) own = in.id;
            if (own == null && resumed && foreground != null && foreground.startsWith("com.webos.app.hdmi")) {
                String fg = "HDMI_" + foreground.substring("com.webos.app.hdmi".length());
                if (fg.equals(lgForegroundSeen)) own = fg;
                lgForegroundSeen = fg;
            }
            if (own != null && !own.equals(p.getString("own", null))) p.edit().putString("own", own).apply();
            own = p.getString("own", null);
            List<LgTv.Input> show = new ArrayList<>();
            StringBuilder sig = new StringBuilder();
            java.util.Map<String, LgTv.Input> seen = new java.util.HashMap<>();
            for (LgTv.Input in : all) {
                // Not our own input, not a soundbar, and only where the TV knows a device or one is on.
                // Names and devices come only from the TV: when it forgets one, so does the bar.
                if (in.id.equals(own) || in.audio) continue;
                LgTv.Input before = lgLast.get(in.id);
                boolean wanted = in.known || in.on;
                if (before != null && (!wanted || (!before.kind.isEmpty() && in.kind.isEmpty()) || !before.label.equals(in.label))) {
                    if (doubt(in.id) < 2) { // not yet: keep what was shown, but take the live on/off
                        before.on = in.on;
                        in = before;
                        wanted = true;
                    }
                } else {
                    lgDoubt.remove(in.id);
                }
                if (!wanted) continue;
                seen.put(in.id, in);
                show.add(in);
                sig.append(in.id).append('|').append(in.label).append('|').append(in.on).append('|').append(in.kind).append(';');
            }
            // An input the TV didn't list at all this time: also only gone after the second time.
            java.util.Set<String> listed = new java.util.HashSet<>();
            for (LgTv.Input in : all) listed.add(in.id);
            for (LgTv.Input before : lgLast.values()) {
                if (listed.contains(before.id) || seen.containsKey(before.id) || doubt(before.id) >= 2) continue;
                seen.put(before.id, before);
                show.add(before);
                sig.append(before.id).append('|').append(before.label).append('|').append(before.on).append('|').append(before.kind).append(';');
            }
            java.util.Collections.sort(show, (a, b) -> a.id.compareTo(b.id));
            lgLast.clear();
            lgLast.putAll(seen);
            if (sig.toString().equals(lgInputsShown) && lgInputs != null) return;
            lgInputsShown = sig.toString();
            lgInputs = show;
            dirty = true;
            refreshApps();
        }

        @Override public void onStatus(String status) {
            android.widget.Toast.makeText(MainActivity.this, status, android.widget.Toast.LENGTH_LONG).show();
            if (status.startsWith("Verbunden")) { dirty = true; refreshApps(); }
        }
    };

    // While Home is in front: if the connection to the TV dropped (it can time out), connect again.
    private final Runnable lgKeepAlive = new Runnable() {
        @Override public void run() {
            if (!resumed || !lg.paired()) return;
            if (!lg.running()) lg.start(false);
            handler.postDelayed(this, 10_000);
        }
    };

    private int doubt(String id) {
        Integer n = lgDoubt.get(id);
        int next = n == null ? 1 : n + 1;
        if (next >= 2) lgDoubt.remove(id); else lgDoubt.put(id, next);
        return next;
    }

    // Before pairing: which LG TVs are on the network? For the menu, one entry each. About 4 s.
    private void lookForLg() {
        if (lgFound != null && !lgFound.isEmpty()) return;
        if (lgSearching) return; // the last result stays in the menu until a new one is in
        lgSearching = true;
        new Thread(() -> {
            List<LgTv.Found> all = lg.discoverAll();
            runOnUiThread(() -> { lgFound = all; lgSearching = false; });
        }, "lgtv-find").start();
    }

    // Pairs with the TV picked in the menu (null: search, for a network with a single LG TV).
    private void connectLg(LgTv.Found tv) {
        lg.stop();
        lg.start(true, tv); // the TV asks "Allow?"; LgTv reports progress with onStatus
    }

    // Hold OK on the settings icon: connect or disconnect the TV, or open Android's settings.
    private void showTvMenu(View anchor) {
        final boolean paired = lg.paired();
        String status = paired ? "Verbunden mit " + lg.model() : null;
        String[] items;
        String[] subs;
        boolean[] enabled;
        Boolean[] switches;
        if (paired) {
            items = new String[] {"Gerätestatus anzeigen", "Trennen", "Android-Einstellungen"};
            subs = new String[] {null, null, null};
            enabled = new boolean[] {true, true, true};
            switches = new Boolean[] {showDeviceDots(), null, null};
        } else {
            // One "Connect TV" per LG TV on the network, each with its name, so the right one can be
            // picked when there are several. A wrong one is simply disconnected again.
            final List<LgTv.Found> found = lgFound;
            int n = found == null || found.isEmpty() ? 1 : found.size();
            items = new String[n + 1];
            subs = new String[n + 1];
            enabled = new boolean[n + 1];
            switches = new Boolean[n + 1];
            for (int i = 0; i < n; i++) {
                items[i] = "Fernseher verbinden";
                subs[i] = found == null ? "suche …" : found.isEmpty() ? "kein LG im Netz" : found.get(i).name;
                enabled[i] = found != null && !found.isEmpty();
            }
            items[n] = "Android-Einstellungen";
            enabled[n] = true;
            if (found == null || found.isEmpty()) lookForLg();
        }
        final List<LgTv.Found> choices = lgFound;
        menu(anchor, status, items, subs, enabled, switches, which -> {
            if (which == items.length - 1) { open(SETTINGS); return; }
            if (paired && which == 0) { // the switch: the menu stays open, the dots follow at once
                boolean show = !showDeviceDots();
                getSharedPreferences(PREFS, 0).edit().putBoolean(SHOW_DEVICE_DOTS, show).apply();
                for (View dot : hdmiDots) dot.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
                return;
            }
            if (paired) {
                lg.forget();
                lgInputs = null;
                lgInputsShown = "";
                lgLast.clear();
                lgDoubt.clear();
                lgFound = null;
                buildTiles();
                android.widget.Toast.makeText(this, "Fernseher getrennt", android.widget.Toast.LENGTH_SHORT).show();
            } else if (choices != null && which < choices.size()) {
                connectLg(choices.get(which));
            }
        });
    }

    // An on/off switch for a menu row: a rounded track (orange when on) with a white knob.
    private Drawable switchDrawable(boolean on) {
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(px(17));
        track.setColor(on ? DOT_ORANGE : 0x40FFFFFF);
        track.setSize(px(64), px(34));
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColor(Color.WHITE);
        knob.setSize(px(26), px(26));
        LayerDrawable l = new LayerDrawable(new Drawable[] {track, knob});
        int edge = px(4), far = px(34);
        l.setLayerInset(1, on ? far : edge, edge, on ? edge : far, edge);
        return l;
    }

    // A small menu under an icon, in the same style as the app menu (dropdown), with an optional
    // status line at the top (green dot, not selectable), a grey note per entry, and entries that
    // can't be chosen right now. Kept on screen at the right edge.
    private void menu(final View anchor, String status, String[] items, String[] subs, boolean[] enabled,
                      final Boolean[] switches, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = px(10), rowH = px(64);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(RADIUS));
        bg.setColor(0xFF231D1F);
        bg.setStroke(Math.max(1, px(2)), 0x17FFFFFF);
        box.setBackground(bg);
        float widest = px(320);
        int h = 2 * pad;
        if (status != null) {
            TextView s = text(px(24), 0xCCFFFFFF, regular);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFF5DCAA5);
            dot.setSize(px(14), px(14));
            s.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            s.setCompoundDrawablePadding(px(12));
            s.setText(status);
            s.setSingleLine(true);
            s.setGravity(Gravity.CENTER_VERTICAL);
            s.setPadding(px(22), 0, px(22), 0);
            widest = Math.max(widest, s.getPaint().measureText(status) + px(14 + 12) + 2 * px(22) + 2 * pad);
            box.addView(s, new LinearLayout.LayoutParams(-1, px(52)));
            h += px(52);
        }
        View first = null;
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            final boolean on = enabled[i];
            final TextView t = text(px(26), on ? 0xD9FFFFFF : 0x66FFFFFF, regular);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(items[i]);
            if (subs[i] != null) {
                int from = sb.length();
                sb.append("   ").append(subs[i]);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.8f), from, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(0x8CFFFFFF), from, sb.length(), 0);
            }
            t.setText(sb);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(22), 0, px(22), 0);
            final boolean isSwitch = switches != null && switches[i] != null;
            final boolean[] state = {isSwitch && switches[i]};
            if (isSwitch) {
                t.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, switchDrawable(state[0]), null);
                t.setCompoundDrawablePadding(px(28));
            }
            widest = Math.max(widest, t.getPaint().measureText(sb.toString()) + 2 * px(22) + 2 * pad
                    + (isSwitch ? px(64 + 28) : 0));
            final GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(px(RADIUS - pad));
            focus.setColor(Color.TRANSPARENT);
            t.setBackground(focus);
            t.setFocusable(on);
            if (on) {
                if (first == null) first = t;
                t.setOnFocusChangeListener((v, has) -> {
                    focus.setColor(has ? 0x38FFFFFF : Color.TRANSPARENT);
                    t.setTextColor(has ? Color.WHITE : 0xD9FFFFFF);
                });
                t.setOnClickListener(v -> {
                    if (isSwitch) { // flips in place, the menu stays open
                        state[0] = !state[0];
                        t.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, switchDrawable(state[0]), null);
                    } else {
                        d.dismiss();
                    }
                    pick.at(which);
                });
            }
            box.addView(t, new LinearLayout.LayoutParams(-1, rowH));
            h += rowH;
        }
        d.setContentView(box);
        if (first != null) first.requestFocus();

        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int w = (int) Math.ceil(widest);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int y = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY()) + px(64);
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setGravity(Gravity.TOP | Gravity.START);
        android.view.WindowManager.LayoutParams lp = win.getAttributes();
        lp.x = Math.max(px(24), Math.min(at[0], screenW - w - px(24)));
        lp.y = y;
        lp.width = w;
        lp.height = h;
        win.setAttributes(lp);
        // The icon's name would sit right above the menu: hide it while the menu is open.
        final TextView caption = captionOf.get(anchor);
        if (caption != null) caption.setVisibility(View.INVISIBLE);
        d.setOnDismissListener(x -> {
            if (openMenu == d) openMenu = null;
            if (caption != null && anchor.isFocused()) caption.setVisibility(View.VISIBLE);
        });
        openMenu = d;
        d.show();
    }

    // The name set in Settings > Inputs (like "Bedroom"), else the TV's own name (like "HDMI 1").
    private String inputLabel(TvInputInfo in) {
        CharSequence custom = in.loadCustomLabel(this);
        if (custom != null && custom.length() > 0) return custom.toString();
        CharSequence label = in.loadLabel(this);
        return label != null && label.length() > 0 ? label.toString() : "HDMI";
    }

    private boolean userInstalled(String pkg) {
        try { return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    // Xiaomi TVs ship MiLan Pro but don't register it by name; every other TV gets Roboto.
    private static void loadFonts() {
        if (regular != null) return;
        regular = fromFile("/system/fonts/MILanPro_Normal.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        semibold = fromFile("/system/fonts/MILanPro_SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    private static Typeface fromFile(String path, Typeface fallback) {
        try { return new File(path).exists() ? Typeface.createFromFile(path) : fallback; }
        catch (Exception e) { return fallback; }
    }

    // Draws a banner once into a tile-sized bitmap (center-cropped). Some apps ship huge banners
    // that Android 9 refuses to draw ("Canvas: trying to draw too large bitmap"); this also saves RAM.
    private Drawable fit(Drawable d, int w, int h) {
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
            float s = Math.max((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            d.draw(new Canvas(b));
            return new BitmapDrawable(getResources(), b);
        } catch (Throwable t) {
            return d;
        }
    }

    private int px(int v) {
        return Math.round(v * scale);
    }

    private TextClock clock(String f12, String f24, int px, int color, Typeface tf) {
        TextClock c = new TextClock(this);
        c.setFormat12Hour(f12);
        c.setFormat24Hour(f24);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        c.setTextColor(color);
        c.setTypeface(tf);
        return c;
    }

    private TextView text(int px, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }
}
