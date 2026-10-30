package app.quietframe;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

// Makes the remote's Home button open QuietFrame on Google TV.
//
// Google TV handles Home before any app sees the key, and turning off its launcher
// (com.google.android.apps.tv.launcherx) has bootlooped some devices. So nothing is turned off:
// this service is told when Google TV's main home window opens and starts QuietFrame over it.
// Switching the service off in Settings > Accessibility puts Home straight back.
//
// Approach adapted from TV Detox's HomeButton (github.com/Ansub/tv-detox, MIT).
public class HomeRedirect extends AccessibilityService {
    static final String GOOGLE_TV = "com.google.android.apps.tv.launcherx";
    static final ComponentName GOOGLE_TV_HOME =
            new ComponentName(GOOGLE_TV, GOOGLE_TV + ".home.HomeActivity");

    // Google TV fires several window events per Home press.
    private static final long DEBOUNCE_MS = 1500;
    // A redirect that hasn't reached HomeActivity.onResume in this long counts as failed.
    private static final long LAUNCH_GRACE_MS = 3000;
    private static final int MAX_FAILURES = 3;

    private static long lastRedirect = -DEBOUNCE_MS;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!GOOGLE_TV.equals(String.valueOf(event.getPackageName()))) return;
        if (!isHomeWindow(String.valueOf(event.getClassName()))) return;
        if (!setupComplete() || pausedThisBoot(this)) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastRedirect < DEBOUNCE_MS) return;

        SharedPreferences p = prefs(this);
        long pending = p.getLong("pending", -1);
        if (pending >= 0 && now >= pending && now - pending < LAUNCH_GRACE_MS) return;
        int failures = p.getInt("failures", 0) + (pending >= 0 ? 1 : 0);
        if (failures >= MAX_FAILURES) {
            // QuietFrame keeps failing to open: leave Home with Google TV until it's opened by hand.
            p.edit().remove("pending").putInt("failures", failures).commit();
            return;
        }
        // Written before launching, in case a crash takes this service down with the activity.
        if (!p.edit().putLong("pending", now).putInt("failures", failures).commit()) return;
        lastRedirect = now;
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void onInterrupt() {}

    // Only the main home screen, not Google TV's search, apps or settings pages.
    private static boolean isHomeWindow(String cls) {
        return GOOGLE_TV_HOME.getClassName().equals(cls)
                || (cls.startsWith(GOOGLE_TV) && cls.endsWith(".HomeActivity"));
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

    // Opens Google TV's home and keeps Home there until QuietFrame is opened again or the TV restarts.
    static boolean openGoogleTv(Context c) {
        if (!prefs(c).edit().putLong("paused_boot", bootTime()).commit()) return false;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(new Intent(home).setComponent(GOOGLE_TV_HOME));
            return true;
        } catch (RuntimeException e) {
            try {
                c.startActivity(new Intent(home).setPackage(GOOGLE_TV));
                return true;
            } catch (RuntimeException e2) {
                prefs(c).edit().remove("paused_boot").commit();
                return false;
            }
        }
    }
}
