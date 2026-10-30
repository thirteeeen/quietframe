package app.quietframe;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// The TV apps the TV's own launcher would show in its apps row.
final class AppCatalog {
    static final class App {
        final ComponentName component;
        final String key;
        final String label;
        final Drawable icon;
        final Drawable banner;

        App(ComponentName component, String label, Drawable icon, Drawable banner) {
            this.component = component;
            this.key = component.flattenToShortString();
            this.label = label;
            this.icon = icon;
            this.banner = banner;
        }
    }

    // Picked for a fresh install, in this order, when installed. Matched on the app's name so it
    // survives package renames. Leans Australian, since that's where BAUHN TVs are sold.
    private static final String[] DEFAULTS = {
            "YouTube", "Netflix", "Disney+", "Prime Video", "ABC iview", "SBS On Demand",
            "7plus", "9Now", "10", "10 Play", "Stan", "Kayo", "BINGE", "Plex", "Spotify",
    };
    private static final int DEFAULT_COUNT = 7;

    private AppCatalog() {}

    // Slow (icons and banners): call off the main thread.
    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<App> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            if (ri.activityInfo == null || c.getPackageName().equals(ri.activityInfo.packageName)) continue;
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            Drawable banner = null;
            Drawable icon = null;
            try {
                banner = ri.activityInfo.loadBanner(pm);
                icon = ri.loadIcon(pm);
            } catch (RuntimeException ignored) {}
            apps.add(new App(cn, String.valueOf(ri.loadLabel(pm)).trim(), icon, banner));
        }
        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(apps, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                return collator.compare(a.label, b.label);
            }
        });
        return apps;
    }

    static List<String> defaults(List<App> apps) {
        List<String> keys = new ArrayList<>();
        for (String name : DEFAULTS) {
            for (App a : apps) {
                if (keys.size() < DEFAULT_COUNT && normalise(a.label).equals(normalise(name))
                        && !keys.contains(a.key)) keys.add(a.key);
            }
        }
        for (App a : apps) {
            if (keys.size() >= Math.min(DEFAULT_COUNT, 4)) break;
            if (!keys.contains(a.key)) keys.add(a.key);
        }
        return keys;
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+]", "");
    }

    static boolean launch(Context c, App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent fallback = c.getPackageManager().getLeanbackLaunchIntentForPackage(a.component.getPackageName());
            if (fallback == null) return false;
            try {
                c.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException e2) {
                return false;
            }
        }
    }
}
