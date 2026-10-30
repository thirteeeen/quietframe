package app.quietframe;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

// State shared by the Home button service and the home screen, which run in separate processes
// (so the home screen's memory can be reclaimed while you watch something). SharedPreferences
// caches per process and would go stale, so this is a tiny file read fresh every time.
final class RedirectState {
    private static final String FILE = "home_redirect.properties";

    private RedirectState() {}

    static synchronized Properties read(Context c) {
        Properties p = new Properties();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.isFile()) return p;
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException ignored) {
            // A damaged file reads as empty: redirects carry on with a clean slate.
        }
        return p;
    }

    // Written to a temporary file and renamed, so the other process never reads half a file.
    static synchronized boolean write(Context c, Properties p) {
        File f = new File(c.getFilesDir(), FILE);
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
            out.getFD().sync();
        } catch (IOException e) {
            return false;
        }
        return tmp.renameTo(f);
    }

    static long getLong(Properties p, String key, long fallback) {
        try {
            String v = p.getProperty(key);
            return v == null ? fallback : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
