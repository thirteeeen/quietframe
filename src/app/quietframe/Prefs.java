package app.quietframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Every saved choice. Values are plain strings so a setting row can cycle through them.
final class Prefs {
    static final String BACKGROUND = "background";   // photo | dark | light
    static final String WALLPAPER = "wallpaper";     // a Wallpapers id
    static final String ROTATION = "rotation";       // fixed | open | hourly
    static final String LAYOUT = "layout";           // dock | list
    static final String LABELS = "labels";           // both | icons | names
    static final String CLOCK = "clock";             // 12 | 24
    static final String DATE = "date";               // on | off
    static final String INPUTS = "inputs";           // on | off
    static final String PHOTOS = "photos";           // on | off: the button that opens the screensaver settings
    static final String LAST_APP = "last_app";
    static final String ROTATED_AT = "rotated_at";
    private static final String HOME_APPS = "home_apps";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("quietframe", 0);
    }

    String get(String key) {
        return sp.getString(key, defaultFor(key));
    }

    boolean is(String key, String value) {
        return value.equals(get(key));
    }

    void set(String key, String value) {
        sp.edit().putString(key, value).apply();
    }

    long getLong(String key) {
        return sp.getLong(key, 0);
    }

    void setLong(String key, long value) {
        sp.edit().putLong(key, value).apply();
    }

    // Null until the first catalogue load picks defaults.
    List<String> homeApps() {
        String s = sp.getString(HOME_APPS, null);
        if (s == null) return null;
        return s.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s.split("\\|")));
    }

    void setHomeApps(List<String> keys) {
        sp.edit().putString(HOME_APPS, TextUtils.join("|", keys)).apply();
    }

    private static String defaultFor(String key) {
        switch (key) {
            case BACKGROUND: return "photo";
            case WALLPAPER: return Wallpapers.DEFAULT;
            case ROTATION: return "fixed";
            case LAYOUT: return "dock";
            case LABELS: return "both";
            case CLOCK: return "12";
            case DATE: return "on";
            case INPUTS: return "on";
            case PHOTOS: return "on";
            default: return "";
        }
    }
}
