package app.quietframe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The home screen: a photograph, the time and your apps, driven entirely by the remote.
// Built in code rather than XML layouts so it stays light on budget TV chipsets.
public class HomeActivity extends Activity {
    private static final int MAX_HOME_APPS = 12;
    private static final long HOUR_MS = 60 * 60 * 1000;

    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    // Panels always use dark glass, whatever the Home background.
    private static final int PANEL = 0xF2181A1F;
    private static final int PANEL_INK = 0xFFFFFFFF;
    private static final int PANEL_INK_SOFT = 0xB3FFFFFF;
    private static final int PANEL_GLASS = 0x1FFFFFFF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Panel> panels = new ArrayList<>();

    private Prefs prefs;
    private float density;
    private FrameLayout root, home, overlay;
    private ImageView backdrop;
    private TextView clock, date;
    private View firstAppTile;
    private String lastAppTag, lastControlTag;

    private List<AppCatalog.App> catalog = new ArrayList<>();
    private boolean catalogLoaded;
    private int backdropRequest;
    // True while hidden with the wallpaper and Home views let go (see onStop).
    private boolean released;

    // Home colours, set by applyTheme().
    private boolean photo;
    private int ink, inkSoft, plate, plateInk, glass;

    private interface BackHandler {
        boolean onBack();
    }

    // Rebuilds a panel's contents, then focuses the view with this tag if it's still there.
    private interface Refresh {
        void run(String focusTag);
    }

    private static final class Panel {
        final View view;
        final View returnFocus;
        Refresh refresh;
        BackHandler back;

        Panel(View view, View returnFocus) {
            this.view = view;
            this.returnFocus = returnFocus;
        }
    }

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reloadCatalog();
        }
    };

    private final Runnable rotationTick = new Runnable() {
        @Override
        public void run() {
            if (photo && prefs.is(Prefs.ROTATION, "hourly") && panels.isEmpty()
                    && System.currentTimeMillis() - prefs.getLong(Prefs.ROTATED_AT) >= HOUR_MS) {
                advanceWallpaper();
                loadBackdrop();
            }
            ui.postDelayed(this, 60 * 1000);
        }
    };

    // ---- Lifecycle -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        density = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        home = new FrameLayout(this);
        home.setClipChildren(false);
        overlay = new FrameLayout(this);
        root.addView(backdrop, match());
        root.addView(home, match());
        root.addView(overlay, match());
        setContentView(root);

        if (saved == null && prefs.is(Prefs.ROTATION, "open")) advanceWallpaper();
        applyTheme();
        loadBackdrop();
        buildHome();
        reloadCatalog();

        IntentFilter pkg = new IntentFilter();
        pkg.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkg.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkg.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkg.addDataScheme("package");
        registerReceiver(packageReceiver, pkg);
    }

    // Hidden behind another app: let go of the wallpaper bitmap and the Home views so the app
    // being watched gets the memory. On a 1.4 GB TV that is worth a moment's redraw on return.
    @Override
    protected void onStop() {
        super.onStop();
        if (!panels.isEmpty()) return;
        released = true;
        ++backdropRequest;
        backdrop.setImageDrawable(null);
        home.removeAllViews();
        clock = null;
        date = null;
        firstAppTile = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!released) return;
        released = false;
        loadBackdrop();
        buildHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeRedirect.onHomeResumed(this);
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, time);
        updateClock();
        ui.postDelayed(rotationTick, 60 * 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(timeReceiver);
        ui.removeCallbacks(rotationTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        worker.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (panels.isEmpty()) {
            confirmLeave();
            return;
        }
        Panel top = panels.get(panels.size() - 1);
        if (top.back == null || !top.back.onBack()) closePanel();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && panels.isEmpty()) {
            openSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Background and theme --------------------------------------------------------------

    private void applyTheme() {
        String bg = prefs.get(Prefs.BACKGROUND);
        photo = "photo".equals(bg);
        boolean light = "light".equals(bg);
        ink = light ? 0xFF16171A : 0xFFFFFFFF;
        inkSoft = light ? 0xCC16171A : 0xD9FFFFFF;
        plate = light ? 0xF016171A : 0xF2FFFFFF;
        plateInk = light ? 0xFFFFFFFF : 0xFF111214;
        glass = photo ? 0x33FFFFFF : light ? 0x14000000 : 0x1AFFFFFF;
        // On a photo the wallpaper covers everything: painting a colour under it is wasted fill.
        if (photo) root.setBackground(null);
        else root.setBackgroundColor(light ? 0xFFECE9E3 : 0xFF111214);
        backdrop.setVisibility(photo ? View.VISIBLE : View.GONE);
    }

    // Darkens the photo just where text sits, so it stays legible on any picture.
    // Drawn as the wallpaper's foreground rather than as extra full-screen views: budget TV GPUs
    // are short of fill rate, and every full-screen layer costs a whole screen of pixels a frame.
    private void applyScrims() {
        if (!photo) {
            backdrop.setForeground(null);
            return;
        }
        boolean dock = prefs.is(Prefs.LAYOUT, "dock");
        backdrop.setForeground(new LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x8C000000, 0x00000000, 0x00000000}),
                new GradientDrawable(
                        dock ? GradientDrawable.Orientation.BOTTOM_TOP : GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0x99000000, 0x33000000, 0x00000000})}));
    }

    private void loadBackdrop() {
        if (!photo) {
            backdrop.setImageDrawable(null);
            return;
        }
        final String id = prefs.get(Prefs.WALLPAPER);
        final int request = ++backdropRequest;
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        final Context c = getApplicationContext();
        worker.execute(() -> {
            Bitmap b = Wallpapers.load(c, id, w, h);
            final Bitmap shown = b != null ? b : Wallpapers.load(c, Wallpapers.DEFAULT, w, h);
            ui.post(() -> {
                if (request != backdropRequest || isDestroyed()) return;
                backdrop.setImageBitmap(shown);
            });
        });
    }

    private void advanceWallpaper() {
        prefs.set(Prefs.WALLPAPER, Wallpapers.next(this, prefs.get(Prefs.WALLPAPER)));
        prefs.setLong(Prefs.ROTATED_AT, System.currentTimeMillis());
    }

    // ---- Apps ------------------------------------------------------------------------------

    private void reloadCatalog() {
        final Context c = getApplicationContext();
        worker.execute(() -> {
            final List<AppCatalog.App> apps = AppCatalog.load(c);
            ui.post(() -> {
                if (isDestroyed()) return;
                catalog = apps;
                catalogLoaded = true;
                if (prefs.homeApps() == null) prefs.setHomeApps(AppCatalog.defaults(apps));
                if (released) return;
                buildHome();
                if (!panels.isEmpty()) {
                    Panel top = panels.get(panels.size() - 1);
                    if (top.refresh != null) top.refresh.run(focusedTag());
                }
            });
        });
    }

    private AppCatalog.App find(String key) {
        for (AppCatalog.App a : catalog) if (a.key.equals(key)) return a;
        return null;
    }

    private List<AppCatalog.App> homeApps() {
        List<AppCatalog.App> out = new ArrayList<>();
        List<String> keys = prefs.homeApps();
        if (keys == null) return out;
        for (String k : keys) {
            AppCatalog.App a = find(k);
            if (a != null) out.add(a);
        }
        return out;
    }

    // Saves only installed apps, so uninstalled ones drop out the next time Home is edited.
    private List<String> homeKeys() {
        List<String> keys = new ArrayList<>();
        for (AppCatalog.App a : homeApps()) keys.add(a.key);
        return keys;
    }

    private void open(AppCatalog.App a) {
        prefs.set(Prefs.LAST_APP, a.key);
        if (!AppCatalog.launch(this, a)) toast("Couldn't open " + a.label);
    }

    private boolean toggleHome(AppCatalog.App a) {
        List<String> keys = homeKeys();
        if (keys.remove(a.key)) {
            toast(a.label + " removed from Home");
        } else if (keys.size() >= MAX_HOME_APPS) {
            toast("Home holds up to " + MAX_HOME_APPS + " apps. Remove one first.");
            return false;
        } else {
            keys.add(a.key);
            toast(a.label + " added to Home");
        }
        prefs.setHomeApps(keys);
        buildHome();
        return true;
    }

    // ---- Home ------------------------------------------------------------------------------

    private void buildHome() {
        View focused = getCurrentFocus();
        String focusTag = focused != null && focused.getParent() != null && isInside(focused, home)
                && focused.getTag() instanceof String ? (String) focused.getTag() : null;

        home.removeAllViews();
        firstAppTile = null;
        applyScrims();
        final boolean dock = prefs.is(Prefs.LAYOUT, "dock");
        int mx = px(56), my = px(36);

        LinearLayout clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        clock = text("", 88, LIGHT, ink);
        clock.setIncludeFontPadding(false);
        date = text("", 22, REGULAR, inkSoft);
        shade(clock);
        shade(date);
        clockBlock.addView(clock);
        clockBlock.addView(date, linear(-2, -2, 0, px(4), 0, 0));
        home.addView(clockBlock, frame(-2, -2, Gravity.TOP | Gravity.START, mx, my, 0, 0));
        updateClock();

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(dock ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        controls.setGravity(Gravity.END);
        if (prefs.is(Prefs.INPUTS, "on")) controls.addView(pill("Inputs", "c:inputs", v -> openInputs()));
        controls.addView(pill("All apps", "c:apps", v -> openAllApps()));
        controls.addView(pill("Settings", "c:settings", v -> openSettings()));
        for (int i = 0; i < controls.getChildCount(); i++) {
            View c = controls.getChildAt(i);
            ((LinearLayout.LayoutParams) c.getLayoutParams()).setMargins(px(dock ? 6 : 0), px(dock ? 0 : 4), 0, 0);
            c.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == (dock ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_LEFT)) return focusApps();
                return false;
            });
        }
        home.addView(controls, frame(-2, -2, (dock ? Gravity.TOP : Gravity.BOTTOM) | Gravity.END,
                0, dock ? my + px(8) : 0, mx - px(10), dock ? 0 : my));

        List<AppCatalog.App> apps = homeApps();
        LinearLayout strip = new LinearLayout(this);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        if (dock) {
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(mx, px(18), mx, px(10));
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            scroll.setFocusable(false);
            scroll.addView(strip);
            home.addView(scroll, frame(-1, -2, Gravity.BOTTOM, 0, 0, 0, my - px(10)));
        } else {
            strip.setOrientation(LinearLayout.VERTICAL);
            strip.setPadding(0, px(24), 0, px(24));
            ScrollView scroll = new ScrollView(this);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setVerticalFadingEdgeEnabled(true);
            scroll.setFadingEdgeLength(px(56));
            scroll.setFocusable(false);
            scroll.addView(strip);
            home.addView(scroll, frame(px(560), -1, Gravity.START, mx - px(18), px(170), 0, my));
        }
        for (AppCatalog.App a : apps) {
            View v = dock ? dockTile(a) : listRow(a);
            v.setOnKeyListener((view, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == (dock ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_RIGHT)) return focusControls();
                return false;
            });
            if (dock) strip.addView(v, linear(-2, -2, 0, 0, px(22), 0));
            else strip.addView(v, linear(-2, -2, 0, 0, 0, px(2)));
            if (firstAppTile == null) firstAppTile = v;
        }
        if (apps.isEmpty() && catalogLoaded) {
            TextView hint = text("No apps on Home yet. Open All apps and hold OK on an app to add it.", 20, REGULAR, inkSoft);
            shade(hint);
            strip.addView(hint);
        }

        if (panels.isEmpty()) focusHome(focusTag);
    }

    private static boolean isInside(View v, ViewGroup parent) {
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) if (p == parent) return true;
        return false;
    }

    private void focusHome(String tag) {
        View v = tag == null ? null : home.findViewWithTag(tag);
        if (v == null && lastAppTag != null) v = home.findViewWithTag(lastAppTag);
        if (v == null) v = home.findViewWithTag("a:" + prefs.get(Prefs.LAST_APP));
        if (v == null) v = firstAppTile;
        if (v == null) v = home.findViewWithTag("c:apps");
        if (v != null) v.requestFocus();
    }

    // Moving between the apps and the controls returns to where you were, not the nearest item.
    private boolean focusApps() {
        View v = lastAppTag == null ? null : home.findViewWithTag(lastAppTag);
        if (v == null) v = firstAppTile;
        return v != null && v.requestFocus();
    }

    private boolean focusControls() {
        View v = lastControlTag == null ? null : home.findViewWithTag(lastControlTag);
        if (v == null) v = home.findViewWithTag(prefs.is(Prefs.INPUTS, "on") ? "c:inputs" : "c:apps");
        return v != null && v.requestFocus();
    }

    private void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        String pattern = prefs.is(Prefs.CLOCK, "24") ? "H:mm" : "h:mm";
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
        date.setVisibility(prefs.is(Prefs.DATE, "on") ? View.VISIBLE : View.GONE);
    }

    private View dockTile(final AppCatalog.App a) {
        String labels = prefs.get(Prefs.LABELS);
        final View tile = appTile(a, 176, ink, glass, labels);
        tile.setOnClickListener(v -> open(a));
        tile.setOnLongClickListener(v -> {
            openEditor(a.key);
            return true;
        });
        return tile;
    }

    private View listRow(final AppCatalog.App a) {
        final String labels = prefs.get(Prefs.LABELS);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(18), px(10), px(24), px(10));
        row.setTag("a:" + a.key);
        focusable(row);
        if (!"names".equals(labels) && a.icon != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(a.icon);
            row.addView(icon, linear(px(40), px(40), 0, 0, px(18), 0));
        }
        final TextView name = text(a.label, 30, REGULAR, inkSoft);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        shade(name);
        if ("icons".equals(labels) && a.icon != null) name.setVisibility(View.GONE);
        row.addView(name);
        row.setOnFocusChangeListener((v, has) -> {
            row.setBackground(has ? rounded(plate, px(16)) : null);
            name.setTextColor(has ? plateInk : inkSoft);
            if (has) name.setShadowLayer(0, 0, 0, 0);
            else shade(name);
            if ("icons".equals(labels) && a.icon != null) name.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) lastAppTag = (String) row.getTag();
        });
        row.setOnClickListener(v -> open(a));
        row.setOnLongClickListener(v -> {
            openEditor(a.key);
            return true;
        });
        return row;
    }

    // A 16:9 card with the app's banner (or icon, or name) and a label underneath.
    private View appTile(final AppCatalog.App a, int widthDp, final int tint, int cardColour, final String labels) {
        final LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setClipChildren(false);
        tile.setTag("a:" + a.key);
        focusable(tile);

        final FrameLayout card = new FrameLayout(this);
        card.setBackground(rounded(cardColour, px(12)));
        card.setClipToOutline(true);
        boolean namesOnly = "names".equals(labels) || (a.banner == null && a.icon == null);
        if (!namesOnly && a.banner != null) {
            ImageView banner = new ImageView(this);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(a.banner);
            card.addView(banner, match());
        } else if (!namesOnly) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(a.icon);
            card.addView(icon, frame(px(56), px(56), Gravity.CENTER, 0, 0, 0, 0));
        } else {
            TextView name = text(a.label, 18, MEDIUM, tint);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setPadding(px(10), 0, px(10), 0);
            card.addView(name, match());
        }
        int w = px(widthDp);
        tile.addView(card, new LinearLayout.LayoutParams(w, w * 9 / 16));

        final TextView label = text(a.label, 15, REGULAR, tint);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        shade(label);
        final boolean revealOnFocus = "icons".equals(labels);
        label.setVisibility(namesOnly ? View.GONE : revealOnFocus ? View.INVISIBLE : View.VISIBLE);
        label.setAlpha(.8f);
        tile.addView(label, linear(w, -2, 0, px(10), 0, 0));

        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(12));
        ring.setStroke(px(3), tint);
        tile.setOnFocusChangeListener((v, has) -> {
            tile.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
            card.setForeground(has ? ring : null);
            label.setAlpha(has ? 1f : .8f);
            if (revealOnFocus && !namesOnly) label.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            if (has && isInside(tile, home)) lastAppTag = (String) tile.getTag();
        });
        return tile;
    }

    private TextView pill(String label, final String tag, View.OnClickListener click) {
        final TextView t = text(label, 18, MEDIUM, inkSoft);
        t.setPadding(px(22), px(10), px(22), px(10));
        t.setTag(tag);
        focusable(t);
        shade(t);
        t.setOnClickListener(click);
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(has ? rounded(plate, px(999)) : null);
            t.setTextColor(has ? plateInk : inkSoft);
            if (has) t.setShadowLayer(0, 0, 0, 0);
            else shade(t);
            if (has) lastControlTag = tag;
        });
        t.setLayoutParams(linear(-2, -2, 0, 0, 0, 0));
        return t;
    }

    // ---- Panels ----------------------------------------------------------------------------

    private Panel openPanel(View content, View focus, int dim) {
        View current = getCurrentFocus();
        if (!panels.isEmpty()) panels.get(panels.size() - 1).view.setVisibility(View.GONE);
        Panel p = new Panel(content, current);
        panels.add(p);
        overlay.addView(content, match());
        overlay.setBackgroundColor(dim);
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (focus != null) focus.requestFocus();
        return p;
    }

    private void closePanel() {
        if (panels.isEmpty()) return;
        Panel p = panels.remove(panels.size() - 1);
        overlay.removeView(p.view);
        if (!panels.isEmpty()) {
            Panel under = panels.get(panels.size() - 1);
            under.view.setVisibility(View.VISIBLE);
            String tag = p.returnFocus != null && p.returnFocus.getTag() instanceof String
                    ? (String) p.returnFocus.getTag() : null;
            // The panel underneath may show something the closed one changed, like the Home app count.
            if (under.refresh != null) under.refresh.run(tag);
            else if (p.returnFocus != null && p.returnFocus.isAttachedToWindow()) p.returnFocus.requestFocus();
            View f = getCurrentFocus();
            if (f == null || !isInside(f, (ViewGroup) under.view)) {
                View first = firstFocusable((ViewGroup) under.view);
                if (first != null) first.requestFocus();
            }
            return;
        }
        overlay.setBackground(null);
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (p.returnFocus != null && p.returnFocus.isAttachedToWindow() && isInside(p.returnFocus, home)) {
            p.returnFocus.requestFocus();
        } else {
            focusHome(null);
        }
    }

    private void closeAllPanels() {
        while (!panels.isEmpty()) closePanel();
    }

    // A dark glass card. Centred, or as a sheet on the right so Home stays visible beside it.
    private LinearLayout card(FrameLayout holder, String title, String subtitle, int widthDp, boolean sheet) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(PANEL, px(24)));
        card.setPadding(px(36), px(30), px(36), px(30));
        card.addView(text(title, 30, MEDIUM, PANEL_INK));
        if (subtitle != null) {
            card.addView(text(subtitle, 16, REGULAR, PANEL_INK_SOFT), linear(-1, -2, 0, px(6), 0, 0));
        }
        holder.addView(card, sheet
                ? frame(px(widthDp), -1, Gravity.END, 0, px(28), px(28), px(28))
                : frame(Math.min(px(widthDp), screenWidth() - px(56)), -2, Gravity.CENTER, 0, px(28), 0, px(28)));
        return card;
    }

    private LinearLayout panelRow(String label, String value, View.OnClickListener click) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(18), px(12), px(18), px(12));
        focusable(row);
        final TextView l = text(label, 19, REGULAR, PANEL_INK);
        final TextView r = text(value == null ? "" : value, 19, REGULAR, PANEL_INK_SOFT);
        r.setSingleLine(true);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(r, linear(-2, -2, px(16), 0, 0, 0));
        row.setOnFocusChangeListener((v, has) -> {
            row.setBackground(has ? rounded(0xF2FFFFFF, px(14)) : null);
            l.setTextColor(has ? 0xFF111214 : PANEL_INK);
            r.setTextColor(has ? 0xFF111214 : PANEL_INK_SOFT);
        });
        if (click != null) row.setOnClickListener(click);
        return row;
    }

    private TextView heading(String s) {
        TextView t = text(s.toUpperCase(Locale.getDefault()), 13, MEDIUM, PANEL_INK_SOFT);
        t.setLetterSpacing(.12f);
        t.setPadding(px(18), px(22), px(18), px(6));
        return t;
    }

    private String focusedTag() {
        View f = getCurrentFocus();
        return f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
    }

    private View focusTagIn(ViewGroup group, String tag) {
        return tag == null ? null : group.findViewWithTag(tag);
    }

    // ---- Settings --------------------------------------------------------------------------

    private void openSettings() {
        FrameLayout holder = new FrameLayout(this);
        LinearLayout card = card(holder, "Settings", null, 460, true);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(32));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        fillSettings(list, null);
        Panel p = openPanel(holder, list.findViewWithTag("s:" + Prefs.BACKGROUND), 0x59000000);
        p.refresh = tag -> fillSettings(list, tag);
    }

    private void fillSettings(final LinearLayout list, String focusTag) {
        list.removeAllViews();
        final Runnable refresh = () -> fillSettings(list, focusedTag());

        list.addView(heading("Appearance"));
        list.addView(option(Prefs.BACKGROUND, "Background", new String[]{"photo", "dark", "light"},
                new String[]{"Photo", "Dark", "Light"}, refresh));
        List<String> ids = Wallpapers.ids(this);
        String[] names = new String[ids.size()];
        for (int i = 0; i < names.length; i++) names[i] = Wallpapers.name(ids.get(i));
        list.addView(option(Prefs.WALLPAPER, "Wallpaper", ids.toArray(new String[0]), names, refresh));
        list.addView(option(Prefs.ROTATION, "Change wallpaper", new String[]{"fixed", "open", "hourly"},
                new String[]{"Never", "When Home opens", "Every hour"}, refresh));
        list.addView(option(Prefs.LAYOUT, "Layout", new String[]{"dock", "list"}, new String[]{"Dock", "List"}, refresh));
        list.addView(option(Prefs.LABELS, "App labels", new String[]{"both", "icons", "names"},
                new String[]{"Pictures and names", "Pictures", "Names"}, refresh));
        list.addView(option(Prefs.CLOCK, "Clock", new String[]{"12", "24"}, new String[]{"12-hour", "24-hour"}, refresh));
        list.addView(option(Prefs.DATE, "Date", new String[]{"on", "off"}, new String[]{"Show", "Hide"}, refresh));
        list.addView(option(Prefs.INPUTS, "Inputs button", new String[]{"on", "off"}, new String[]{"Show", "Hide"}, refresh));

        list.addView(heading("Home"));
        list.addView(tagged(panelRow("Apps on Home", homeKeys().size() + "  ›", v -> openEditor(null)), "s:editor"));
        list.addView(tagged(panelRow("Home button opens QuietFrame",
                HomeRedirect.isEnabled(this) ? "On  ›" : "Off  ›", v -> openHomeButtonHelp()), "s:redirect"));
        list.addView(tagged(panelRow("Open the TV's own home", "›", v -> leaveToTvHome()), "s:tvhome"));
        list.addView(tagged(panelRow("TV settings", "›", v -> startSafely(new Intent(Settings.ACTION_SETTINGS))), "s:tv"));

        list.addView(heading("About"));
        list.addView(tagged(panelRow("Your own wallpapers", "›", v -> openWallpaperHelp()), "s:photos"));
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        list.addView(tagged(panelRow("QuietFrame", version, null), "s:version"));

        View f = focusTagIn(list, focusTag);
        if (f != null) f.requestFocus();
    }

    private View tagged(View v, String tag) {
        v.setTag(tag);
        return v;
    }

    // Left/Right or OK steps through the choices, applying each one straight away.
    private View option(final String key, String label, final String[] values, final String[] names, final Runnable refresh) {
        int current = indexOf(values, prefs.get(key));
        final LinearLayout row = panelRow(label, "‹  " + (current >= 0 ? names[current] : names[0]) + "  ›", null);
        row.setTag("s:" + key);
        row.setOnClickListener(v -> step(key, values, 1, refresh));
        row.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) step(key, values, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, refresh);
            return true;
        });
        return row;
    }

    private void step(String key, String[] values, int dir, Runnable refresh) {
        int i = indexOf(values, prefs.get(key));
        String value = values[((i < 0 ? 0 : i) + dir + values.length) % values.length];
        prefs.set(key, value);
        switch (key) {
            case Prefs.WALLPAPER:
                // Choosing a wallpaper implies wanting to see it.
                if (!photo) prefs.set(Prefs.BACKGROUND, "photo");
                // Fall through.
            case Prefs.BACKGROUND:
                applyTheme();
                loadBackdrop();
                buildHome();
                break;
            case Prefs.ROTATION:
                prefs.setLong(Prefs.ROTATED_AT, System.currentTimeMillis());
                break;
            case Prefs.CLOCK:
            case Prefs.DATE:
                updateClock();
                break;
            default:
                buildHome();
        }
        refresh.run();
    }

    private static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return -1;
    }

    private void openHomeButtonHelp() {
        FrameLayout holder = new FrameLayout(this);
        LinearLayout card = card(holder, "Home button",
                "The TV handles the Home button itself. QuietFrame's accessibility service notices "
                        + "when the TV's own home opens and opens QuietFrame over it. Nothing is turned off, "
                        + "so switching it off puts Home straight back.\n\n"
                        + "Turn on: Accessibility › QuietFrame Home button. The install script can also do this for you.\n\n"
                        + "Right now it's " + (HomeRedirect.isEnabled(this) ? "on." : "off."), 620, false);
        LinearLayout row = panelRow("Open Accessibility settings", "›",
                v -> startSafely(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        card.addView(row, linear(-1, -2, 0, px(20), 0, 0));
        card.addView(panelRow("Back", "", v -> closePanel()));
        openPanel(holder, row, 0x99000000);
    }

    private void openWallpaperHelp() {
        java.io.File dir = Wallpapers.dir(this);
        String path = dir == null ? "/sdcard/Android/data/" + getPackageName() + "/files/wallpapers/" : dir.getPath() + "/";
        FrameLayout holder = new FrameLayout(this);
        LinearLayout card = card(holder, "Your own wallpapers",
                "Copy JPEG, PNG or WebP photos to this folder from a computer, then pick them under Wallpaper:\n\n"
                        + "adb push photo.jpg " + path + "\n\n"
                        + "Landscape photos at least 1920 × 1080 look best.", 680, false);
        LinearLayout back = panelRow("Back", "", v -> closePanel());
        card.addView(back, linear(-1, -2, 0, px(20), 0, 0));
        openPanel(holder, back, 0x99000000);
    }

    // ---- All apps --------------------------------------------------------------------------

    private void openAllApps() {
        FrameLayout holder = new FrameLayout(this);
        final LinearLayout card = card(holder, "All apps", "OK opens an app. Hold OK to add it to Home or remove it.", 900, false);
        ((FrameLayout.LayoutParams) card.getLayoutParams()).height = -1;
        LinearLayout edit = panelRow("Edit apps on Home", "›", v -> openEditor(null));
        edit.setTag("all:edit");
        card.addView(edit, linear(-1, -2, 0, px(14), 0, px(6)));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(32));
        scroll.setClipChildren(false);
        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setPadding(px(10), px(14), px(10), px(14));
        scroll.addView(grid);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        fillAllApps(grid, null);
        View first = firstFocusable(grid);
        Panel p = openPanel(holder, first != null ? first : edit, 0xCC000000);
        p.refresh = tag -> fillAllApps(grid, tag);
    }

    private void fillAllApps(final LinearLayout grid, String focusTag) {
        grid.removeAllViews();
        int cardWidth = Math.min(px(900), screenWidth() - px(56)) - px(72 + 20);
        int tileDp = 140, gapDp = 22;
        int columns = Math.max(3, cardWidth / px(tileDp + gapDp));
        List<String> onHome = homeKeys();
        LinearLayout row = null;
        for (int i = 0; i < catalog.size(); i++) {
            final AppCatalog.App a = catalog.get(i);
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                grid.addView(row, linear(-2, -2, 0, 0, 0, px(18)));
            }
            View tile = appTile(a, tileDp, PANEL_INK, PANEL_GLASS, "both");
            if (onHome.contains(a.key)) {
                TextView badge = text("On Home", 11, MEDIUM, 0xFF111214);
                badge.setBackground(rounded(0xF2FFFFFF, px(999)));
                badge.setPadding(px(8), px(2), px(8), px(2));
                FrameLayout c = (FrameLayout) ((ViewGroup) tile).getChildAt(0);
                c.addView(badge, frame(-2, -2, Gravity.TOP | Gravity.END, 0, px(6), px(6), 0));
            }
            tile.setOnClickListener(v -> open(a));
            tile.setOnLongClickListener(v -> {
                if (toggleHome(a)) fillAllApps(grid, "a:" + a.key);
                return true;
            });
            row.addView(tile, linear(-2, -2, 0, 0, px(gapDp), 0));
        }
        if (catalog.isEmpty()) grid.addView(text(catalogLoaded ? "No TV apps found." : "Loading…", 18, REGULAR, PANEL_INK_SOFT));
        View f = focusTagIn(grid, focusTag);
        if (f != null) f.requestFocus();
    }

    // ---- Apps on Home editor ---------------------------------------------------------------

    private final class Editor {
        final LinearLayout onHome = new LinearLayout(HomeActivity.this);
        final LinearLayout others = new LinearLayout(HomeActivity.this);
        String moving;

        void render(String focusTag) {
            onHome.removeAllViews();
            others.removeAllViews();
            final List<String> keys = homeKeys();
            onHome.addView(heading("On Home · " + keys.size() + " of " + MAX_HOME_APPS));
            for (final String key : keys) {
                final AppCatalog.App a = find(key);
                LinearLayout line = new LinearLayout(HomeActivity.this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout name = panelRow((key.equals(moving) ? "↕  " : "") + a.label,
                        key.equals(moving) ? "Up/Down, then OK" : "", v -> {
                            moving = key.equals(moving) ? null : key;
                            render("e:" + key);
                        });
                name.setTag("e:" + key);
                name.setOnKeyListener((v, code, e) -> {
                    if (!key.equals(moving)) return false;
                    if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) {
                        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                    }
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
                    List<String> order = homeKeys();
                    int i = order.indexOf(key), j = i + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    if (i < 0 || j < 0 || j >= order.size()) return true;
                    order.set(i, order.get(j));
                    order.set(j, key);
                    prefs.setHomeApps(order);
                    buildHome();
                    render("e:" + key);
                    return true;
                });
                line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout remove = panelRow("Remove", null, v -> {
                    List<String> order = homeKeys();
                    order.remove(key);
                    prefs.setHomeApps(order);
                    moving = null;
                    buildHome();
                    render("o:" + key);
                });
                remove.setTag("x:" + key);
                line.addView(remove, linear(-2, -2, px(6), 0, 0, 0));
                onHome.addView(line);
            }
            if (keys.isEmpty()) onHome.addView(text("Nothing yet. Pick apps on the right.", 17, REGULAR, PANEL_INK_SOFT));

            others.addView(heading("Add to Home"));
            for (final AppCatalog.App a : catalog) {
                if (keys.contains(a.key)) continue;
                LinearLayout row = panelRow(a.label, "+", v -> {
                    List<String> order = homeKeys();
                    if (order.size() >= MAX_HOME_APPS) {
                        toast("Home holds up to " + MAX_HOME_APPS + " apps. Remove one first.");
                        return;
                    }
                    order.add(a.key);
                    prefs.setHomeApps(order);
                    buildHome();
                    render("x:" + a.key);
                });
                row.setTag("o:" + a.key);
                others.addView(row);
            }
            View f = focusTagIn(onHome, focusTag);
            if (f == null) f = focusTagIn(others, focusTag);
            if (f == null && focusTag != null && focusTag.startsWith("o:")) {
                f = others.getChildCount() > 1 ? others.getChildAt(1) : null;
            }
            if (f == null) f = firstFocusable(onHome);
            if (f == null) f = firstFocusable(others);
            if (f != null) f.requestFocus();
        }
    }

    private static View firstFocusable(ViewGroup g) {
        ArrayList<View> found = g.getFocusables(View.FOCUS_DOWN);
        return found.isEmpty() ? null : found.get(0);
    }

    private void openEditor(String focusKey) {
        FrameLayout holder = new FrameLayout(this);
        LinearLayout card = card(holder, "Apps on Home",
                "OK on a name to move it, then Up/Down and OK to place it. Changes save as you go.", 900, false);
        ((FrameLayout.LayoutParams) card.getLayoutParams()).height = -1;
        final Editor editor = new Editor();
        editor.onHome.setOrientation(LinearLayout.VERTICAL);
        editor.others.setOrientation(LinearLayout.VERTICAL);
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        ScrollView left = new ScrollView(this);
        left.setVerticalScrollBarEnabled(false);
        left.addView(editor.onHome);
        ScrollView right = new ScrollView(this);
        right.setVerticalScrollBarEnabled(false);
        right.setVerticalFadingEdgeEnabled(true);
        right.setFadingEdgeLength(px(32));
        right.addView(editor.others);
        columns.addView(left, new LinearLayout.LayoutParams(0, -1, 1.3f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1);
        rp.leftMargin = px(28);
        columns.addView(right, rp);
        card.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        Panel p = openPanel(holder, null, 0xCC000000);
        editor.render(focusKey == null ? null : "e:" + focusKey);
        p.refresh = editor::render;
        p.back = () -> {
            if (editor.moving == null) return false;
            String key = editor.moving;
            editor.moving = null;
            editor.render("e:" + key);
            return true;
        };
    }

    // ---- Inputs and leaving ----------------------------------------------------------------

    private void openInputs() {
        FrameLayout holder = new FrameLayout(this);
        LinearLayout card = card(holder, "Inputs", null, 520, false);
        View first = null;
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> inputs = tim == null ? new ArrayList<TvInputInfo>() : tim.getTvInputList();
            for (final TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                CharSequence label = info.loadCustomLabel(this);
                if (TextUtils.isEmpty(label)) label = info.loadLabel(this);
                LinearLayout row = panelRow(String.valueOf(label), "›", v -> {
                    closeAllPanels();
                    startSafely(new Intent(Intent.ACTION_VIEW,
                            TvContract.buildChannelUriForPassthroughInput(info.getId())));
                });
                card.addView(row, linear(-1, -2, 0, first == null ? px(16) : 0, 0, 0));
                if (first == null) first = row;
            }
        } catch (RuntimeException ignored) {}
        if (first == null) {
            card.addView(text("This TV didn't list any HDMI inputs.", 17, REGULAR, PANEL_INK_SOFT),
                    linear(-1, -2, px(18), px(16), 0, px(8)));
            first = panelRow("Close", "", v -> closePanel());
            card.addView(first);
        }
        openPanel(holder, first, 0x99000000);
    }

    // Back on Home offers the TV's own home rather than doing nothing.
    private void confirmLeave() {
        FrameLayout holder = new FrameLayout(this);
        LinearLayout card = card(holder, "Open the TV's own home?",
                "Home goes to the TV's own home until you open QuietFrame again from its apps row, or restart the TV.", 560, false);
        LinearLayout stay = panelRow("Stay here", "", v -> closePanel());
        card.addView(stay, linear(-1, -2, 0, px(20), 0, 0));
        card.addView(panelRow("Open the TV's own home", "›", v -> leaveToTvHome()));
        openPanel(holder, stay, 0x99000000);
    }

    private void leaveToTvHome() {
        closeAllPanels();
        if (!HomeRedirect.openTvHome(this)) toast("Couldn't find the TV's own home screen.");
    }

    // ---- Small helpers ---------------------------------------------------------------------

    private void startSafely(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            toast("This TV can't open that screen.");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int px(float dp) {
        return Math.round(dp * density);
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private TextView text(String s, float sp, Typeface face, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(face);
        t.setTextColor(colour);
        return t;
    }

    // A soft shadow under Home text on photos.
    private void shade(TextView t) {
        if (photo) t.setShadowLayer(px(8), 0, px(1), 0x99000000);
        else t.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
    }

    private static void focusable(View v) {
        v.setFocusable(true);
        v.setClickable(true);
    }

    private static GradientDrawable rounded(int colour, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(radius);
        return g;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private static FrameLayout.LayoutParams frame(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams linear(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
}
