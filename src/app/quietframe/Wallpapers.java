package app.quietframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// Built-in soft gradient "studies", plus any photos pushed to the TV:
//   adb push photo.jpg /sdcard/Android/data/app.quietframe/files/wallpapers/
final class Wallpapers {
    static final String DEFAULT = "g:dusk";

    // name, top colour, bottom colour, then glows as colour, x, y, radius (fractions of the width).
    private static final Object[][] STUDIES = {
            {"Dusk", 0xFF2B1E3F, 0xFFC86B5A, 0xFFF2CC8F, .78f, .72f, .45f, 0xFF6D597A, .18f, .25f, .55f},
            {"Harbour", 0xFF0B2545, 0xFF1B3B5F, 0xFF8DA9C4, .72f, .35f, .42f, 0xFFEEF4ED, .88f, .2f, .14f},
            {"Fern", 0xFF16302A, 0xFF3A5A40, 0xFFA3B18A, .3f, .85f, .5f, 0xFFDAD7CD, .82f, .18f, .28f},
            {"Sandstone", 0xFF5E4533, 0xFFC9965F, 0xFFFAEDCD, .7f, .3f, .42f, 0xFFA9714B, .2f, .85f, .5f},
            {"Midnight", 0xFF05070D, 0xFF111827, 0xFF1E3A8A, .8f, .2f, .5f, 0xFF312E81, .2f, .9f, .4f},
            {"Ember", 0xFF1A0F0A, 0xFF4A2216, 0xFFE76F51, .76f, .78f, .42f, 0xFFF4A261, .92f, .95f, .2f},
    };
    private static final List<String> PHOTO_TYPES = Arrays.asList("jpg", "jpeg", "png", "webp");

    private Wallpapers() {}

    static File dir(Context c) {
        File d = c.getExternalFilesDir("wallpapers");
        if (d != null && !d.exists()) d.mkdirs();
        return d;
    }

    static List<String> ids(Context c) {
        List<String> ids = new ArrayList<>();
        for (Object[] s : STUDIES) ids.add("g:" + ((String) s[0]).toLowerCase(Locale.ROOT));
        File d = dir(c);
        File[] files = d == null ? null : d.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) if (f.isFile() && PHOTO_TYPES.contains(extension(f))) ids.add("f:" + f.getName());
        }
        return ids;
    }

    static String name(String id) {
        if (id.startsWith("f:")) {
            String n = id.substring(2);
            int dot = n.lastIndexOf('.');
            return dot > 0 ? n.substring(0, dot) : n;
        }
        Object[] s = study(id);
        return s == null ? id : (String) s[0];
    }

    static String next(Context c, String current) {
        List<String> ids = ids(c);
        int i = ids.indexOf(current);
        return ids.get((i + 1) % ids.size());
    }

    // Slow: call off the main thread. Null if the photo has gone or can't be read.
    static Bitmap load(Context c, String id, int width, int height) {
        if (id.startsWith("f:")) {
            File d = dir(c);
            return d == null ? null : decode(new File(d, id.substring(2)), width, height);
        }
        Object[] s = study(id);
        return paint(s != null ? s : STUDIES[0]);
    }

    private static Object[] study(String id) {
        for (Object[] s : STUDIES) if (id.equals("g:" + ((String) s[0]).toLowerCase(Locale.ROOT))) return s;
        return null;
    }

    // Painted small; the ImageView scales it up, which keeps the glows soft and costs little memory.
    private static Bitmap paint(Object[] s) {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w * .25f, h, (int) s[1], (int) s[2], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        for (int i = 3; i + 3 < s.length; i += 4) {
            int colour = (int) s[i];
            float x = (float) s[i + 1] * w, y = (float) s[i + 2] * h, r = (float) s[i + 3] * w;
            p.setShader(new RadialGradient(x, y, r, new int[]{colour & 0xB3FFFFFF, colour & 0x33FFFFFF, colour & 0x00FFFFFF},
                    new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, p);
        }
        return b;
    }

    private static Bitmap decode(File f, int width, int height) {
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static String extension(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
