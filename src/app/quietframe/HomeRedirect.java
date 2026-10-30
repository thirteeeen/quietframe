package app.quietframe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;

// Makes the remote's Home button open QuietFrame on Google TV and Android TV.
//
// The TV's own launcher handles Home before any app sees the key, and turning that launcher off
// has bootlooped some devices. So nothing is turned off: this service is told when the TV's main
// home window opens and starts QuietFrame over it. Switching the service off in
// Settings > Accessibility puts Home straight back.
//
// Approach adapted from TV Detox's HomeButton (github.com/Ansub/tv-detox, MIT).
public class HomeRedirect extends AccessibilityService {
    // See what it decides with: adb logcat -s QuietFrame
    private static final String TAG = "QuietFrame";

    // Home screens we know, used when Android can't say which launcher is the system home.
    // Google TV, then the classic Android TV launcher (BAUHN and other Android TV 9-11 sets).
    private static final ComponentName[] KNOWN_HOMES = {
            new ComponentName("com.google.android.apps.tv.launcherx",
                    "com.google.android.apps.tv.launcherx.home.HomeActivity"),
            new ComponentName("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity"),
    };

    // Launchers fire several window events per Home press.
    private static final long DEBOUNCE_MS = 1500;
    // A redirect that hasn't reached HomeActivity.onResume in this long counts as failed.
    private static final long LAUNCH_GRACE_MS = 3000;
    private static final int MAX_FAILURES = 3;

    private static long lastRedirect = -DEBOUNCE_MS;

    private List<ComponentName> homes = new ArrayList<>();

    @Override
    protected void onServiceConnected() {
        homes = homes(this);
        Log.i(TAG, "Home button service connected; watching " + homes);
        // Only hear from the home screen's own package, not every app's windows.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && !homes.isEmpty()) {
            String[] packages = new String[homes.size()];
            for (int i = 0; i < packages.length; i++) packages[i] = homes.get(i).getPackageName();
            info.packageNames = packages;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = String.valueOf(event.getPackageName()), cls = String.valueOf(event.getClassName());
        if (!isHomeWindow(pkg, cls)) {
            Log.i(TAG, "Ignored window " + pkg + "/" + cls);
            return;
        }
        if (!setupComplete() || pausedThisBoot(this)) {
            Log.i(TAG, "Home opened, but redirect is paused (TV setup, or the TV's own home was chosen)");
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastRedirect < DEBOUNCE_MS) return;

        SharedPreferences p = prefs(this);
        long pending = p.getLong("pending", -1);
        if (pending >= 0 && now >= pending && now - pending < LAUNCH_GRACE_MS) return;
        int failures = p.getInt("failures", 0) + (pending >= 0 ? 1 : 0);
        if (failures >= MAX_FAILURES) {
            // QuietFrame keeps failing to open: leave Home with the TV until it's opened by hand.
            p.edit().remove("pending").putInt("failures", failures).commit();
            Log.w(TAG, "QuietFrame failed to open " + failures + " times; redirect stopped until it's opened by hand");
            return;
        }
        // Written before launching, in case a crash takes this service down with the activity.
        if (!p.edit().putLong("pending", now).putInt("failures", failures).commit()) return;
        lastRedirect = now;
        Log.i(TAG, "Home opened (" + cls + "); opening QuietFrame");
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't open QuietFrame", e);
        }
    }

    @Override
    public void onInterrupt() {}

    // Only the main home screen, not the launcher's search, apps or settings pages.
    private boolean isHomeWindow(String pkg, String cls) {
        for (ComponentName home : homes) {
            if (!home.getPackageName().equals(pkg)) continue;
            if (home.getClassName().equals(cls)) return true;
            // Google TV has renamed its home activity before; any *.HomeActivity in it is the home.
            if (pkg.equals(KNOWN_HOMES[0].getPackageName()) && cls.endsWith(".HomeActivity")) return true;
        }
        return false;
    }

    // The launcher Android opens for Home, then any known home screens that are installed.
    static List<ComponentName> homes(Context c) {
        List<ComponentName> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        // With no default, Android returns its chooser, which lives in the "android" package.
        if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)
                && !c.getPackageName().equals(ri.activityInfo.packageName)) {
            out.add(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        }
        for (ComponentName known : KNOWN_HOMES) {
            if (out.contains(known)) continue;
            try {
                pm.getActivityInfo(known, 0);
                out.add(known);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return out;
    }

    // Leave Home alone while the TV is still being set up.
    private boolean setupComplete() {
        return Settings.Secure.getInt(getContentResolver(), "user_setup_complete", 1) == 1;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home_redirect", 0);
    }

    private static long bootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    private static boolean pausedThisBoot(Context c) {
        SharedPreferences p = prefs(c);
        return p.contains("paused_boot") && Math.abs(bootTime() - p.getLong("paused_boot", 0)) < 10000;
    }

    // QuietFrame is on screen: the last redirect worked, and Home should come here again.
    static void onHomeResumed(Context c) {
        prefs(c).edit().remove("pending").remove("failures").remove("paused_boot").commit();
        lastRedirect = -DEBOUNCE_MS;
    }

    static boolean isEnabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        String full = new ComponentName(c, HomeRedirect.class).flattenToString();
        String brief = new ComponentName(c, HomeRedirect.class).flattenToShortString();
        for (String s : list.split(":")) if (s.equals(full) || s.equals(brief)) return true;
        return false;
    }

    // Opens the TV's own home and keeps Home there until QuietFrame is opened again or the TV restarts.
    static boolean openTvHome(Context c) {
        if (!prefs(c).edit().putLong("paused_boot", bootTime()).commit()) return false;
        for (ComponentName home : homes(c)) {
            try {
                c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setComponent(home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException ignored) {}
        }
        prefs(c).edit().remove("paused_boot").commit();
        return false;
    }
}
