package com.rampey.materialtv;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Material 3 Expressive home screen for TV: the background and accents take their color from the app in focus,
 * one dock of your most-used apps, everything else on an Apps page. No network, no tracking.
 */
public class HomeActivity extends Activity {

    private static final int SIDE = 48, DOCK_PAD = 8, SCROLL_PAD = 18, GAP = 14, RING = 6, APP_MARGIN = 9;

    /** Material's "emphasized decelerate" curve for things arriving on screen. */
    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> seedCache = new ConcurrentHashMap<>();
    private final Map<Integer, Palette> paletteCache = new ConcurrentHashMap<>();
    private final Map<Integer, Typeface> fonts = new HashMap<>();
    private PackageManager pm;
    private Palette palette = Palette.of(Palette.DEFAULT_SEED);

    private GradientDrawable backdrop;
    private CookieDrawable cookie;
    private ImageView shape;
    /**
     * The background shape drifts at 3 degrees a second, on every display frame (60 fps on a 60 Hz TV). It only runs
     * while Home is on screen; the shape is a hardware layer, so each frame just rotates a texture.
     */
    private long driftLast;
    private static final long DRIFT_FRAME_MS = 0;   // every display frame (60 fps on a 60 Hz TV)
    private boolean drifting;
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (!drifting) return;
            long now = android.os.SystemClock.uptimeMillis();
            // Every display frame: 60 fps on a 60 Hz TV (owner choice; ~50% of one core, only while Home is up).
            if (driftLast == 0 || now - driftLast >= DRIFT_FRAME_MS) {
                if (driftLast > 0) shape.setRotation((shape.getRotation() + 0.003f * (now - driftLast)) % 360f);
                driftLast = now;
            }
            shape.postOnAnimation(this);
        }
    };
    private final Runnable prebuildApps = new Runnable() {
        @Override public void run() {
            if (!HomeActivity.this.showingApps) fillApps();
        }
    };
    private GradientDrawable dockShape;
    private View homePage;
    private ScrollView appsPage;
    private GridLayout appsGrid;
    private LinearLayout dock;
    private TextView clock;
    private TextView date;
    private TextView prayer;
    private TextView weather;
    private View nightDim;
    private ValueAnimator dimAnimator;
    private static final int[] DIM_LEVELS = {0, 20, 40, 60};   // % black at full night; the choice is kept in the "home" prefs
    private ButtonGroup nav;
    private ButtonGroup settingsPill;
    private View topFade;   // fade in the dark background colour over the top of the Apps page while it is scrolled
    private GradientDrawable fadeShape;

    // Camera card: a still from the optional CCTV companion app, refreshed only while Home is on screen. This app
    // stays offline: the companion holds the network permission and the camera login.
    private static final Uri CAM_URI = Uri.parse("content://com.polkka.cctv.snapshot/frame");
    private static final String CAM_PKG = "com.polkka.cctv";
    private static final long CAM_REFRESH_MS = 15000;
    // Weather comes from the companion app too (it holds the network permission); shown only while it has a reading.
    private static final long WEATHER_REFRESH_MS = 10 * 60_000L;
    private final AtomicBoolean weatherBusy = new AtomicBoolean();
    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (weatherBusy.compareAndSet(false, true)) new Thread(HomeActivity.this::weatherRefresh, "weather").start();
            handler.postDelayed(this, WEATHER_REFRESH_MS);
        }
    };
    private View camRing;
    private ImageView camImage;
    private TextView camLabel;
    private boolean camHasImage;
    private final AtomicBoolean camBusy = new AtomicBoolean();
    private final Runnable camTick = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (camBusy.compareAndSet(false, true)) new Thread(HomeActivity.this::camRefresh, "cam").start();
            handler.postDelayed(this, CAM_REFRESH_MS);
        }
    };

    // Focus settles before the expensive part runs, so holding a direction stays smooth.
    private String pendingPkg;
    private CharSequence pendingLabel;
    private Drawable pendingArt;
    private final Runnable settle = new Runnable() {
        @Override public void run() {
            applyFocus(pendingPkg, pendingLabel, pendingArt);
        }
    };

    // Now Playing.
    private MediaSessionManager sessions;
    private ComponentName mediaListener;
    private MediaController playing;
    private View nowCard;
    private ImageView nowArt;
    private TextView nowTitle;
    private TextView nowArtist;
    private WavyProgress nowProgress;
    private final Map<String, EqualizerBadge> dockBadges = new HashMap<>();
    private final Map<String, View> deviceDots = new HashMap<>();   // input id -> "on" dot
    private final android.media.tv.TvInputManager.TvInputCallback inputCallback = new android.media.tv.TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) {
            showDeviceState(inputId, state);
        }

        // An HDMI device names itself over CEC a little after it appears ("Playback 1" -> "PlayStation 5").
        @Override public void onInputAdded(String inputId) {
            refreshDockSoon();
        }

        @Override public void onInputRemoved(String inputId) {
            refreshDockSoon();
        }

        @Override public void onInputUpdated(String inputId) {
            refreshDockSoon();
        }

        @Override public void onTvInputInfoUpdated(android.media.tv.TvInputInfo info) {
            refreshDockSoon();
        }
    };
    private final Runnable refreshDock = () -> {
        if (drifting) fillDock();
    };

    private void refreshDockSoon() {
        handler.removeCallbacks(refreshDock);
        handler.postDelayed(refreshDock, 500);
    }
    private ImageView nowButton;
    private GradientDrawable nowShape;
    private GradientDrawable nowButtonShape;
    // The callbacks hand over the session's metadata and state; they are kept rather than fetched again from the
    // controller, which would re-parcel the whole bundle (album art included) several times a second.
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            liveMeta = metadata;
            showNowPlaying();
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            liveState = state;
            showNowPlaying();
        }

        @Override public void onSessionDestroyed() {
            pickSession();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> pickSession();
    private boolean showingApps;
    private String appsKey = "";
    private String dockKey = "";
    private ValueAnimator colorAnimator;
    private final Map<Integer, Typeface> clockFonts = new HashMap<>();

    /** Clock weight through the day: 250 at night, rising to 430 by mid-morning, easing back after sunset. */
    private static int clockWeight() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        float h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f;
        float[][] keys = {{0, 250}, {5, 250}, {9, 430}, {17, 430}, {21, 300}, {24, 250}};
        for (int i = 0; i < keys.length - 1; i++) {
            if (h >= keys[i][0] && h <= keys[i + 1][0]) {
                float t = (h - keys[i][0]) / (keys[i + 1][0] - keys[i][0]);
                return Math.round(keys[i][1] + (keys[i + 1][1] - keys[i][1]) * t);
            }
        }
        return 300;
    }

    private Typeface clockFont(int weight) {
        int w = Math.round(weight / 10f) * 10;
        Typeface t = clockFonts.get(w);
        if (t == null) {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setFontVariationSettings("'wght' " + w + ", 'opsz' 48, 'ROND' 0").build();
            } catch (RuntimeException e) {
                t = font(300);
            }
            clockFonts.put(w, t);
        }
        return t;
    }

    /** A new minute: the old time lifts away, the new one settles in with a short weight swell. */
    private void changeClock(String time) {
        int base = clockWeight();
        clock.animate().alpha(0f).translationY(-dp(6)).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                .withEndAction(() -> {
                    clock.setText(time);
                    clock.setTranslationY(dp(8));
                    clock.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL)
                            .setDuration(Motion.DEFAULT_SPATIAL_MS).start();
                    ValueAnimator swell = ValueAnimator.ofInt(base + 320, base).setDuration(700);
                    swell.setInterpolator(Motion.DEFAULT_SPATIAL);
                    swell.addUpdateListener(a -> clock.setTypeface(clockFont((int) a.getAnimatedValue())));
                    swell.start();
                }).start();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            String time = new SimpleDateFormat("h:mm", Locale.getDefault()).format(now);
            if (clock.getText().length() == 0) {
                clock.setText(time);
                clock.setTypeface(clockFont(clockWeight()));
            } else if (!time.contentEquals(clock.getText())) {
                changeClock(time);
            }
            int sleepLeft = sleepLeft();
            date.setText(new SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now) + "  \u00B7  " + PrayerTimes.hijri(now.getTime())
                    + "  \u00B7  " + PrayerTimes.placeName() + (sleepLeft > 0 ? "  \u00B7  Sleep " + formatLeft(sleepLeft) : ""));
            settingsPill.setLabel(1, sleepLeft > 0 ? formatLeft(sleepLeft) : "");
            updateNightDim(now.getTime());
            prayer.setText(InfoLines.prayerLine(HomeActivity.this, now.getTime(), palette.onSurfaceVariant, dp(20)));
            handler.postDelayed(this, sleepLeft > 0 ? 1_000 : 15_000);   // every second while a sleep countdown shows
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        InfoLines.loadPlace(this);
        setContentView(buildRoot());
    }

    @Override protected void onResume() {
        super.onResume();
        fillDock();
        if (showingApps) fillApps();
        handler.removeCallbacks(tick);
        handler.post(tick);
        drifting = true;
        driftLast = 0;
        shape.removeCallbacks(drift);
        shape.postOnAnimation(drift);
        // Build the Apps page quietly once Home is showing, so opening it later is instant.
        handler.removeCallbacks(prebuildApps);
        handler.postDelayed(prebuildApps, 1500);
        foreground = true;
        // onPause drops a pending focus colour; bring it back, or the screen keeps the default purple until focus moves.
        if (pendingPkg != null) {
            handler.removeCallbacks(settle);
            handler.postDelayed(settle, 120);
        }
        handler.removeCallbacks(camTick);
        handler.post(camTick);
        handler.removeCallbacks(weatherTick);
        handler.post(weatherTick);
        startNowPlaying();
        android.media.tv.TvInputManager inputs = getSystemService(android.media.tv.TvInputManager.class);
        if (inputs != null) {
            inputs.registerCallback(inputCallback, handler);
            for (String id : deviceDots.keySet()) showDeviceState(id, inputs.getInputState(id));
        }
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        // Nothing animates while another app is on screen: no CPU or GPU use in the background.
        drifting = false;
        shape.removeCallbacks(drift);
        handler.removeCallbacks(prebuildApps);
        if (colorAnimator != null) colorAnimator.end();
        nav.stopAnimations();
        settingsPill.stopAnimations();
        handler.removeCallbacks(settle);
        // The media session stays watched (it only updates fields, never views) so the card remembers what played
        // while another app had the screen; it is let go in onDestroy.
        foreground = false;
        dismissOptions();
        handler.removeCallbacks(camTick);
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(checkResumed);
        android.media.tv.TvInputManager inputs = getSystemService(android.media.tv.TvInputManager.class);
        if (inputs != null) inputs.unregisterCallback(inputCallback);
        hideLoader();
    }

    @Override protected void onDestroy() {
        stopNowPlaying();
        handler.removeCallbacks(expire);
        handler.removeCallbacks(checkResumed);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showHome();
    }


    @Override public void onBackPressed() {
        if (panelBack()) return;
        if (showingApps) showHome();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (showingApps) showHome(); else showApps();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- layout ----

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        rootFrame = root;
        backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{palette.surfaceBright, palette.surface});
        root.setBackground(backdrop);

        // One Expressive "cookie" shape behind everything, drifting slowly and turning a little more when focus
        // moves. It sits on its own GPU layer, so each step only moves the layer; nothing is redrawn.
        cookie = new CookieDrawable(palette.primaryContainer, 9, 0.08f);
        ImageView big = new ImageView(this);
        big.setImageDrawable(cookie);
        big.setAlpha(0.55f);
        big.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        FrameLayout.LayoutParams bigLp = new FrameLayout.LayoutParams(dp(560), dp(560), Gravity.END | Gravity.TOP);
        bigLp.setMargins(0, -dp(150), -dp(120), 0);
        root.addView(big, bigLp);
        shape = big;

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(SIDE), dp(32), dp(SIDE), dp(28));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(column, match());

        nowCard = buildNowPlaying();
        FrameLayout.LayoutParams nowLp = new FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        nowLp.setMargins(0, dp(140), dp(SIDE), 0);
        root.addView(nowCard, nowLp);

        camRing = buildCamCard();

        View topBar = buildTopBar();
        topBar.setTranslationZ(dp(2));   // drawn above the page, so scrolled tiles pass under the clock and pills
        column.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout pages = new FrameLayout(this);
        pages.setClipChildren(false);   // a focused tile grows past the page edge
        column.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        homePage = buildHomePage();
        pages.addView(homePage, match());

        appsPage = new ScrollView(this);
        appsPage.setVerticalScrollBarEnabled(false);
        appsPage.setClipToPadding(false);
        appsPage.setClipChildren(false);
        LinearLayout appsColumn = new LinearLayout(this);
        appsColumn.setOrientation(LinearLayout.VERTICAL);
        appsColumn.setClipChildren(false);
        TextView appsTitle = text("All apps", 34, palette.onSurface, 760);
        appsTitle.setPadding(dp(4), dp(28), 0, dp(8));
        appsColumn.addView(appsTitle);
        appsGrid = new GridLayout(this);
        appsGrid.setColumnCount(5);
        appsGrid.setClipChildren(false);
        appsGrid.setPadding(0, dp(12), 0, dp(32));
        appsColumn.addView(appsGrid);
        appsPage.addView(appsColumn, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        appsPage.setVisibility(View.GONE);
        pages.addView(appsPage, match());

        // A fade in the dark end of the background colours across the full width, from the top of the screen to just past the page's top edge. It sits above
        // the tiles and below the header, and grows in as the page scrolls, so tiles and the background shape fade out
        // before they reach the clock.
        topFade = new View(this);
        fadeShape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0, 0, 0});
        tintFade(palette.surface);
        topFade.setBackground(fadeShape);
        topFade.setAlpha(0f);
        topFade.setVisibility(View.GONE);
        pages.addView(topFade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        pages.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int[] loc = new int[2];
            pages.getLocationOnScreen(loc);
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = loc[1] + dp(50);
            FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) topFade.getLayoutParams();
            if (fp.width != width || fp.height != height || fp.topMargin != -loc[1] || fp.leftMargin != -loc[0]) {
                fp.width = width;
                fp.height = height;
                fp.topMargin = -loc[1];
                fp.leftMargin = -loc[0];
                topFade.requestLayout();
            }
        });
        appsPage.setOnScrollChangeListener((v, x, y, ox, oy) -> topFade.setAlpha(Math.min(1f, y / (float) dp(70))));
        // Night dim: a black veil over everything, as deep as the sun is low (see PrayerTimes.nightLevel).
        nightDim = new View(this);
        nightDim.setBackgroundColor(0xFF000000);
        nightDim.setAlpha(nightMax() * PrayerTimes.nightLevel(System.currentTimeMillis()));
        nightDim.setFocusable(false);
        root.addView(nightDim, match());
        return root;
    }

    /** Ease the night dim to where the sun puts it now; called from the clock tick, so it follows dusk and dawn. */
    private void updateNightDim(long now) {
        if (dimPreview) return;
        fadeDimTo(nightMax() * PrayerTimes.nightLevel(now), 4000);
    }

    private void fadeDimTo(float target, long ms) {
        if (Math.abs(nightDim.getAlpha() - target) < 0.004f) return;
        if (dimAnimator != null) dimAnimator.cancel();
        dimAnimator = ValueAnimator.ofFloat(nightDim.getAlpha(), target).setDuration(ms);
        dimAnimator.setInterpolator(Motion.DEFAULT_EFFECTS);
        dimAnimator.addUpdateListener(a -> nightDim.setAlpha((float) a.getAnimatedValue()));
        dimAnimator.start();
    }

    /** The chosen darkness at full night, 0 to 0.6. */
    private float nightMax() {
        return getSharedPreferences("home", MODE_PRIVATE).getInt("dim", 40) / 100f;
    }

    private boolean dimPreview;

    /** Show a level at full strength while its row is highlighted, whatever the time of day. Leaving the page without picking undoes it. */
    private void previewDim(int percent) {
        dimPreview = true;
        fadeDimTo(percent / 100f, 500);
    }

    private final Runnable endDimPreview = () -> {
        dimPreview = false;
        updateNightDim(System.currentTimeMillis());
    };

    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        // Room for a focused button to grow without its edge being cut off.
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(0, dp(4), dp(6), dp(4));

        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = text("", 72, palette.onSurface, 300);
        clock.setIncludeFontPadding(false);
        when.addView(clock);
        date = text("", 15, palette.onSurfaceVariant, 500);
        date.setPadding(dp(2), dp(2), 0, 0);
        date.setGravity(Gravity.CENTER_VERTICAL);
        when.addView(date);
        weather = text("", 15, palette.onSurfaceVariant, 500);
        weather.setPadding(dp(2), dp(4), 0, 0);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        weather.setVisibility(View.GONE);
        when.addView(weather);
        prayer = text("", 15, palette.onSurfaceVariant, 500);
        prayer.setPadding(dp(2), dp(4), 0, 0);
        when.addView(prayer);
        bar.addView(when, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP));

        // Home and Apps are tabs: the page follows focus. Inputs, Sleep timer and Settings are their own strip and need a click.
        nav = new ButtonGroup(this, font(600), palette,
                new String[]{"Home", "Apps"},
                new int[]{R.drawable.ic_home, R.drawable.ic_apps},
                new boolean[]{true, true},
                index -> {
                    if (index == 0) showHome(true);
                    else showApps(true);
                });
        nav.setFocusListener(index -> {
            if (index == 0 && showingApps) showHome(false);
            else if (index == 1 && !showingApps) showApps(false);
        });
        nav.select(0);
        nav.button(1).setId(View.generateViewId());   // Up from the first row of apps lands here, not on Home
        settingsPill = new ButtonGroup(this, font(600), palette,
                new String[]{"", "", "Settings"},
                new int[]{R.drawable.ic_cable, R.drawable.ic_timer, R.drawable.ic_settings},
                new boolean[]{false, false, false},
                index -> {
                    if (index == 0) showInputsMenu(settingsPill.button(0));
                    else if (index == 1) showSleepMenu(settingsPill.button(1));
                    else showSettingsMenu(settingsPill.button(2));
                });
        settingsPill.evenGaps();
        settingsPill.button(0).setContentDescription("Inputs");
        settingsPill.button(1).setContentDescription("Sleep timer");
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setClipChildren(false);
        tabs.addView(nav);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.setMarginStart(dp(12));
        tabs.addView(settingsPill, pillLp);
        bar.addView(tabs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));
        return bar;
    }

    private View buildHomePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.BOTTOM);
        page.setClipChildren(false);

        // No big title: the camera card takes its place, as far above the dock as the dock is above the screen edge.
        LinearLayout.LayoutParams camLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        camLp.setMarginStart(-dp(RING));
        camLp.bottomMargin = dp(28 - RING);
        page.addView(camRing, camLp);

        // The dock: a rounded tonal container holding the favorites, like Material's floating toolbar.
        FrameLayout dockFrame = new FrameLayout(this);
        dockFrame.setClipChildren(false);
        dockShape = new GradientDrawable();
        dockShape.setCornerRadius(dp(40));
        dockShape.setColor(withAlpha(palette.container, 0xB3));
        dockFrame.setBackground(dockShape);
        dockFrame.setPadding(dp(DOCK_PAD), dp(6), dp(DOCK_PAD), dp(6));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setClipChildren(false);
        scroller.setPadding(dp(SCROLL_PAD), dp(18), dp(SCROLL_PAD), dp(18));
        dock = new LinearLayout(this);
        dock.setClipChildren(false);
        scroller.addView(dock);
        dockFrame.addView(scroller);
        page.addView(dockFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return page;
    }

    // ---- content ----

    private static final int DOCK_SIZE = 6;
    /** Never in the dock: settings, the stock home screen and system helpers. */
    private static final java.util.Set<String> NOT_IN_DOCK = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.tv.settings", "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith",
            "com.google.android.katniss", "com.android.vending", "com.android.systemui"));

    /** The dock: most-used apps of the last 14 days (left = most used), topped up from the TV's other installed apps. */
    private List<String> dockApps() {
        List<String> pinned = pins();
        int size = Math.max(0, DOCK_SIZE - pinned.size());
        List<String> out = new ArrayList<>();
        // Screensaver apps rack up "use" while the screensaver runs: they never count toward the dock.
        java.util.Set<String> screensavers = new java.util.HashSet<>();
        for (ResolveInfo r : pm.queryIntentServices(new Intent("android.service.dreams.DreamService"), 0))
            screensavers.add(r.serviceInfo.packageName);
        try {
            android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> used = new ArrayList<>(usm.queryAndAggregateUsageStats(now - 14L * 86_400_000L, now).values());
            used.sort((a, b) -> Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));
            for (android.app.usage.UsageStats u : used) {
                if (out.size() >= size || u.getTotalTimeInForeground() < 60_000) break;
                String pkg = u.getPackageName();
                if (pkg.equals(getPackageName()) || NOT_IN_DOCK.contains(pkg) || screensavers.contains(pkg) || pinned.contains(pkg) || launchIntent(pkg) == null) continue;
                out.add(pkg);
            }
        } catch (RuntimeException ignored) {
            // No usage access: the dock fills from installed apps below.
        }
        // HDMI devices that name themselves (CEC), e.g. "PlayStation 5", take the TV input app's place.
        String inputApp = inputApp();
        List<String> devices = cecDevices();
        int at = inputApp == null ? -1 : out.indexOf(inputApp);
        if (at >= 0 && !devices.isEmpty()) {
            out.remove(at);
            out.addAll(at, devices);
            while (out.size() > size) out.remove(out.size() - 1);
        } else if (!devices.isEmpty()) {
            // Not used much yet (a fresh TV): consoles still get a tile when there's room.
            for (String d : devices) if (out.size() < size) out.add(d);
        }
        boolean devicesShown = !devices.isEmpty() && out.contains(devices.get(0));
        // Top up from this TV's own installed apps: most recently used first, then the newest installs.
        if (out.size() < size) {
            java.util.Map<String, Long> lastUsed = new java.util.HashMap<>();
            try {
                android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
                long now = System.currentTimeMillis();
                for (android.app.usage.UsageStats u : usm.queryAndAggregateUsageStats(now - 90L * 86_400_000L, now).values())
                    lastUsed.put(u.getPackageName(), u.getLastTimeUsed());
            } catch (RuntimeException ignored) {
            }
            List<String> rest = new ArrayList<>();
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                String pkg = info.activityInfo.packageName;
                if (!out.contains(pkg) && !pinned.contains(pkg) && !rest.contains(pkg) && !pkg.equals(getPackageName()) && !NOT_IN_DOCK.contains(pkg)
                        && !screensavers.contains(pkg) && !(devicesShown && pkg.equals(inputApp))) rest.add(pkg);
            }
            rest.sort((a, b) -> {
                int byUse = Long.compare(lastUsed.getOrDefault(b, 0L), lastUsed.getOrDefault(a, 0L));
                return byUse != 0 ? byUse : Long.compare(installTime(b), installTime(a));
            });
            for (String pkg : rest) {
                if (out.size() >= size) break;
                out.add(pkg);
            }
        }
        List<String> all = new ArrayList<>(pinned);
        all.addAll(out);
        dockPinned = pinned.size();
        return all;
    }

    private static final int MAX_PINS = 5;
    private int dockPinned;

    /** Apps pinned to the front of the dock, in the order the user set. Uninstalled apps drop out. */
    private List<String> pins() {
        List<String> out = new ArrayList<>();
        String raw = getSharedPreferences("home", MODE_PRIVATE).getString("pins", "");
        for (String p : raw.split(",")) if (!p.isEmpty() && !out.contains(p) && launchIntent(p) != null) out.add(p);
        return out;
    }

    /** op: 0 pin, 1 unpin, 2 move left, 3 move right. */
    private void changePins(String pkg, int op) {
        List<String> pins = pins();
        int at = pins.indexOf(pkg);
        if (op == 0 && at < 0 && pins.size() < MAX_PINS) pins.add(pkg);
        else if (op == 1 && at >= 0) pins.remove(at);
        else if (op == 2 && at > 0) Collections.swap(pins, at, at - 1);
        else if (op == 3 && at >= 0 && at < pins.size() - 1) Collections.swap(pins, at, at + 1);
        getSharedPreferences("home", MODE_PRIVATE).edit().putString("pins", TextUtils.join(",", pins)).apply();
        dockKey = null;
        fillDock();
        if (!showingApps && op != 1) focusDockTile(pkg);
    }

    private void focusDockTile(String pkg) {
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (pkg.equals(v.getTag())) {
                v.requestFocus();
                return;
            }
        }
    }

    /** CEC-named HDMI devices: dock key "input:<id>" -> {label, parent input id}. */
    private final Map<String, String[]> inputDevices = new HashMap<>();

    private List<String> cecDevices() {
        List<String> keys = new ArrayList<>();
        inputDevices.clear();
        try {
            android.media.tv.TvInputManager tim = getSystemService(android.media.tv.TvInputManager.class);
            if (tim == null) return keys;
            for (android.media.tv.TvInputInfo in : tim.getTvInputList()) {
                if (in.getType() != android.media.tv.TvInputInfo.TYPE_HDMI || in.getParentId() == null) continue;
                CharSequence label = in.loadLabel(this);
                if (label == null || label.length() == 0) continue;
                String key = "input:" + in.getId();
                inputDevices.put(key, new String[]{label.toString(), in.getParentId()});
                // Consoles first.
                if (isConsole(label.toString())) keys.add(0, key); else keys.add(key);
            }
        } catch (RuntimeException ignored) {
        }
        return keys;
    }

    /** The app that plays HDMI inputs (the one a passthrough link opens), e.g. "Live TV". */
    private String inputApp() {
        ResolveInfo r = pm.resolveActivity(new Intent(Intent.ACTION_VIEW,
                android.media.tv.TvContract.buildChannelUriForPassthroughInput("x")), 0);
        return r != null && r.activityInfo != null ? r.activityInfo.packageName : null;
    }

    private static boolean isConsole(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        return l.contains("playstation") || l.matches(".*\\bps[2-5]\\b.*") || l.contains("xbox")
                || l.contains("nintendo") || l.contains("switch");
    }

    /**
     * A 16:9 banner for an HDMI device, Material You themed-icon style: a deep tone of the console's brand hue with a
     * large Material icon in a light tone of the same hue. No text (the name sits under the tile) and no logos.
     */
    private Drawable inputBanner(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        int brand = 0xFF5F6368;
        if (l.contains("playstation") || l.matches(".*\\bps[2-5]\\b.*")) brand = 0xFF0047AB;
        else if (l.contains("xbox")) brand = 0xFF107C10;
        else if (l.contains("nintendo") || l.contains("switch")) brand = 0xFFE60012;
        palettes.TonalPalette tones = palettes.TonalPalette.fromInt(brand);
        int icon = isConsole(label) ? R.drawable.ic_gamepad : R.drawable.ic_input_tv;
        Bitmap b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(tones.tone(30));
        Drawable d = getDrawable(icon).mutate();
        d.setTint(tones.tone(90));
        int size = 250;
        d.setBounds((640 - size) / 2, (360 - size) / 2, (640 + size) / 2, (360 + size) / 2);
        d.draw(c);
        return new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }

    private Intent dockIntent(String key) {
        String[] dev = inputDevices.get(key);
        if (dev != null) return new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(dev[1]));
        return launchIntent(key);
    }

    private long installTime(String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).firstInstallTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private void fillDock() {
        List<String> present = dockApps();
        // Kept between visits; rebuild only when the dock's order or an HDMI device's name changes.
        StringBuilder names = new StringBuilder();
        for (String p : present) {
            String[] dev = inputDevices.get(p);
            if (dev != null) names.append('|').append(dev[0]);
        }
        String key = TextUtils.join(",", present) + names + '#' + dockPinned;
        if (key.equals(dockKey) && dock.getChildCount() > 0) {
            if (!showingApps) dock.getChildAt(0).requestFocus();
            return;
        }
        dockKey = key;
        dock.removeAllViews();
        dockBadges.clear();
        deviceDots.clear();
        warmPalettes(present);
        int n = Math.max(1, present.size());
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        boolean divider = dockPinned > 0 && dockPinned < present.size();
        float inner = screenDp - 2 * (SIDE + DOCK_PAD + SCROLL_PAD) - (n - 1) * GAP - n * 2 * RING - (divider ? 2 + GAP : 0);
        int tileDp = (int) Math.max(96, Math.min(220, inner / n));
        int i = 0;
        for (String pkg : present) {
            if (divider && i == dockPinned) addDockDivider(tileDp);
            Intent intent = dockIntent(pkg);
            View tile = tile(pkg, intent, tileDp, true);
            tile.setTag(pkg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(GAP));
            dock.addView(tile, lp);
            arrive(tile, i++);
        }
        if (!showingApps && dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
        updateBadges();
    }

    /** A thin vertical line between the pinned apps and the recent ones. */
    private void addDockDivider(int tileDp) {
        int h = tileDp * 9 / 16;
        View line = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(1));
        d.setColor(withAlpha(palette.onSurfaceVariant, 0x80));
        line.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(2), dp(h * 8 / 10));
        lp.setMarginStart(dp(GAP));
        lp.topMargin = dp(RING) + dp(h / 10);
        dock.addView(line, lp);
    }

    private void fillApps() {
        Set<String> seen = new LinkedHashSet<>();
        List<ResolveInfo> found = new ArrayList<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                found.add(info);
            }
        }
        // The grid is kept between visits; rebuild only when the set of installed apps changed.
        String key = TextUtils.join(",", seen);
        if (key.equals(appsKey) && appsGrid.getChildCount() > 0) return;
        appsKey = key;
        appsGrid.removeAllViews();
        // Read each label once (loading a label is slow), then sort.
        Map<ResolveInfo, String> labels = new HashMap<>();
        for (ResolveInfo info : found) labels.put(info, String.valueOf(info.loadLabel(pm)));
        Collections.sort(found, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        // Five columns that exactly fill the width between the side margins (tile + focus-ring room + gaps).
        int cols = appsGrid.getColumnCount();
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float inner = screenDp - 2 * SIDE - cols * (2 * RING + 2 * APP_MARGIN);
        int tileDp = (int) Math.min(170, inner / cols);
        for (ResolveInfo info : found) {
            String pkg = info.activityInfo.packageName;
            Intent intent = launchIntent(pkg);
            if (intent == null) continue;
            View tile = tile(pkg, intent, tileDp, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(dp(APP_MARGIN), dp(10), dp(APP_MARGIN), dp(16));
            // Up from the first row goes to the Apps tab: the nearest tab by position can be Home, which would switch the page.
            if (appsGrid.getChildCount() < cols) {
                View card = firstFocusable(tile);
                if (card != null) card.setNextFocusUpId(nav.button(1).getId());
            }
            appsGrid.addView(tile, lp);
        }
    }

    private static View firstFocusable(View v) {
        if (v.isFocusable()) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusable(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** A 16:9 card: the app's TV banner, or its icon on a tonal card, with the name underneath. */
    private View tile(String pkg, Intent intent, int widthDp, boolean inDock) {
        int w = dp(widthDp);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);

        CharSequence name = pkg;
        Drawable banner = null;
        Drawable icon = null;
        String[] device = inputDevices.get(pkg);
        if (device != null) {
            name = device[0];
            banner = inputBanner(device[0]);
        } else try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            name = pm.getApplicationLabel(ai);
            banner = pm.getApplicationBanner(ai);
            if (banner == null) icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        final CharSequence label = name;
        final Drawable art = banner != null ? banner : icon;

        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(radius);
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);
        ImageView image = new ImageView(this);
        if (banner != null) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageDrawable(banner);
            card.addView(image, match());
        } else {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(icon);
            card.addView(image, new FrameLayout.LayoutParams(h / 2, h / 2, Gravity.CENTER));
        }
        card.setContentDescription(label);

        // Material focus: the card grows with a little spring, its corners tighten, and a ring in the
        // accent color appears just outside it. The whole screen re-tints to the app's color.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);   // the ring sits in the gap around the card; badges draw above it
        ringFrame.addView(card, new FrameLayout.LayoutParams(w, h));

        // Expressive loading indicator: a small shape that morphs while the app takes its time to open.
        CookieDrawable loaderShape = new CookieDrawable(palette.primary, 4, 0.18f);
        ImageView loader = new ImageView(this);
        loader.setImageDrawable(loaderShape);
        loader.setVisibility(View.GONE);
        ringFrame.addView(loader, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        card.setOnClickListener(v -> pressAndLaunch(ringFrame, loader, loaderShape, intent));
        if (device == null) card.setOnLongClickListener(v -> {
            showAppOptions(card, pkg);
            return true;
        });

        TextView caption = text(label, inDock ? 15 : 14, palette.onSurfaceVariant, 560);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0, dp(10), 0, 0);
        caption.setAlpha(inDock ? 0f : 0.85f);

        card.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.10f : 1f;
            ringFrame.animate().scaleX(scale).scaleY(scale)
                    .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
            card.setOutlineSpotShadowColor(Color.TRANSPARENT);   // no cast shadow: it peeked out at the bottom corners with a hard edge
            card.setOutlineAmbientShadowColor(Color.TRANSPARENT);
            card.animate().translationZ(focused ? dp(14) : 0)
                    .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.DEFAULT_EFFECTS_MS).start();
            ObjectAnimator corners = ObjectAnimator.ofFloat(cardShape, "cornerRadius", cardShape.getCornerRadius(), focused ? radius * 0.62f : radius)
                    .setDuration(Motion.FAST_SPATIAL_MS);
            corners.setInterpolator(Motion.FAST_SPATIAL);
            // The clip follows the background's outline; without this it keeps the old, rounder corners and the card shows through.
            corners.addUpdateListener(an -> card.invalidateOutline());
            corners.start();
            ring.setCornerRadius((focused ? radius * 0.62f : radius) + dp(RING));
            fadeRing(ring, focused);
            if (inDock) shiftNeighbors(holder, focused);
            caption.animate().cancel();
            if (inDock && focused) {
                caption.setVisibility(View.VISIBLE);
                caption.animate().alpha(1f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
            } else if (inDock) {
                caption.animate().alpha(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                        .withEndAction(() -> caption.setVisibility(View.GONE)).start();
            } else {
                caption.animate().alpha(focused ? 1f : 0.85f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
            }
            caption.setTextColor(focused ? palette.onSurface : palette.onSurfaceVariant);
            if (focused) onTileFocused(pkg, label, art);
        });

        // Status lives under the tile, never on the artwork: an equalizer for the app that's playing, a green dot for
        // an HDMI device that's on. Focused, the name appears beside it.
        LinearLayout captionRow = new LinearLayout(this);
        captionRow.setOrientation(LinearLayout.HORIZONTAL);
        captionRow.setGravity(Gravity.CENTER);
        captionRow.setClipChildren(false);
        caption.setPadding(0, 0, 0, 0);
        caption.setMaxWidth(w + dp(48) - dp(30));
        if (inDock) caption.setVisibility(View.GONE);
        if (inDock && device != null) {
            View dot = new View(this);
            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(0xFF6DD58C);                 // Material green, tone 80: "on"
            dot.setBackground(dotShape);
            dot.setVisibility(View.GONE);
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP | Gravity.END);
            dl.setMargins(0, dp(RING + 6), dp(RING + 6), 0);
            ringFrame.addView(dot, dl);
            String inputId = pkg.substring("input:".length());
            deviceDots.put(inputId, dot);
            android.media.tv.TvInputManager tim = getSystemService(android.media.tv.TvInputManager.class);
            if (tim != null) showDeviceState(inputId, tim.getInputState(inputId));
        }
        captionRow.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(ringFrame, new LinearLayout.LayoutParams(w + dp(2 * RING), h + dp(2 * RING)));
        // Fixed height so the dock never jumps; a little wider than the tile (negative margins keep the spacing) so a
        // name with a status mark beside it still fits.
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(w + dp(48), dp(32));
        rl.topMargin = dp(8);
        rl.leftMargin = -dp(24);
        rl.rightMargin = -dp(24);
        if (!inDock) holder.addView(captionRow, rl);
        return holder;
    }

    /** The focus ring fades in (fast effects) in the palette's focus color instead of snapping on. */
    private void fadeRing(GradientDrawable ring, boolean focused) {
        int color = palette.inverseSurface;
        ValueAnimator a = ValueAnimator.ofInt(focused ? 0 : 255, focused ? 255 : 0).setDuration(Motion.FAST_EFFECTS_MS);
        a.setInterpolator(Motion.FAST_EFFECTS);
        a.addUpdateListener(an -> ring.setStroke(dp(3), (color & 0x00FFFFFF) | ((int) an.getAnimatedValue() << 24)));
        a.start();
    }

    /** Standard button-group behavior on the dock: the neighbors of the focused tile make a little room. */
    private void shiftNeighbors(View holder, boolean focused) {
        if (!(holder.getParent() instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) holder.getParent();
        int at = row.indexOfChild(holder);
        for (int i = 0; i < row.getChildCount(); i++) {
            float dx = !focused ? 0 : i == at - 1 ? -dp(8) : i == at + 1 ? dp(8) : 0;
            if (!focused && i != at - 1 && i != at + 1) continue;
            row.getChildAt(i).animate().translationX(dx)
                    .setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
        }
    }

    private View activeLoader;
    private ValueAnimator loaderAnim;
    private final Runnable[] pendingLoader = new Runnable[1];

    /** Press feedback (a quick squash), then launch; if the app hasn't appeared after 300 ms, a shape loader morphs. */
    private void pressAndLaunch(View tile, ImageView loader, CookieDrawable shape, Intent intent) {
        tile.animate().scaleX(1.0f).scaleY(1.0f).setInterpolator(Motion.FAST_SPATIAL).setDuration(120)
                .withEndAction(() -> tile.animate().scaleX(1.10f).scaleY(1.10f)
                        .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start()).start();
        launch(intent);
        if (pendingLoader[0] != null) handler.removeCallbacks(pendingLoader[0]);
        pendingLoader[0] = () -> {
            hideLoader();
            activeLoader = loader;
            shape.setColor(palette.primary);
            loader.setAlpha(0f);
            loader.setVisibility(View.VISIBLE);
            loader.animate().alpha(1f).setDuration(Motion.FAST_EFFECTS_MS).start();
            final int[] seq = {4, 6, 7, 9, 12, 7, 6};
            final int[] step = {0};
            loaderAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(420);
            loaderAnim.setRepeatCount(ValueAnimator.INFINITE);
            loaderAnim.setInterpolator(Motion.DEFAULT_SPATIAL);
            loaderAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationRepeat(android.animation.Animator a) {
                    step[0] = (step[0] + 1) % seq.length;
                    shape.morphTo(seq[step[0]], seq[step[0]] == 4 ? 0.18f : 0.10f);
                }
            });
            shape.morphTo(seq[0], 0.18f);
            loaderAnim.addUpdateListener(an -> {
                shape.setProgress((float) an.getAnimatedValue());
                loader.setRotation(loader.getRotation() + 2.5f);
            });
            loaderAnim.start();
        };
        handler.postDelayed(pendingLoader[0], 300);
    }

    private void hideLoader() {
        if (pendingLoader[0] != null) handler.removeCallbacks(pendingLoader[0]);
        if (loaderAnim != null) loaderAnim.cancel();
        if (activeLoader != null) activeLoader.setVisibility(View.GONE);
        activeLoader = null;
    }

    private void onTileFocused(String pkg, CharSequence label, Drawable art) {
        pendingPkg = pkg;
        pendingLabel = label;
        pendingArt = art;
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, 120);
    }

    /** Runs once focus has rested for a moment: the title, the background shape and the colors follow the app. */
    private void applyFocus(String pkg, CharSequence label, Drawable art) {
        if (pkg == null) return;
        morphShape(pkg);
        retint(paletteFor(pkg, art));
    }

    private Palette paletteFor(String pkg, Drawable art) {
        Integer seed = seedCache.get(pkg);
        if (seed == null) {
            seed = Palette.seedFrom(art);
            seedCache.put(pkg, seed);
        }
        Palette p = paletteCache.get(seed);
        if (p == null) {
            p = Palette.of(seed);
            paletteCache.put(seed, p);
        }
        return p;
    }

    /** Work out every favorite's colors on a background thread, so the first focus on each app costs nothing. */
    private void warmPalettes(List<String> packages) {
        new Thread(() -> {
            for (String pkg : packages) {
                if (seedCache.containsKey(pkg)) continue;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    Drawable art = pm.getApplicationBanner(ai);
                    if (art == null) art = pm.getApplicationIcon(ai);
                    int seed = Palette.seedFrom(art);
                    seedCache.put(pkg, seed);
                    if (!paletteCache.containsKey(seed)) paletteCache.put(seed, Palette.of(seed));
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
        }, "palette-warmup").start();
    }

    /** Each app gets its own Expressive shape; moving between apps morphs one outline into the next. */
    private void morphShape(String pkg) {
        // Material 3 Expressive shapes (MaterialShapes): Cookie 9, Cookie 12, Clover 4-leaf, Clover 8-leaf, Sunny,
        // Soft burst, Cookie 6, Cookie 7, Cookie 4.
        int[] lobes = {9, 12, 4, 8, 14, 10, 6, 7, 4};
        float[] depths = {0.08f, 0.06f, 0.26f, 0.16f, 0.05f, 0.11f, 0.12f, 0.10f, 0.16f};
        float[] sharp = {1f, 1f, 0.55f, 0.65f, 1f, 2.4f, 1f, 1f, 1f};
        int k = Math.abs(pkg.hashCode()) % lobes.length;
        cookie.morphTo(lobes[k], depths[k], sharp[k]);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(Motion.DEFAULT_SPATIAL_MS);
        a.setInterpolator(Motion.DEFAULT_SPATIAL);
        a.addUpdateListener(an -> cookie.setProgress((float) an.getAnimatedValue()));
        a.start();
    }

    /** Cross-fade every tinted surface from the current palette to the next. */
    private void tintFade(int surface) {
        // Strongest at the very top, easing out through the header zone and gone just below it.
        fadeShape.setColors(new int[]{withAlpha(surface, 0xF5), withAlpha(surface, 0xEB), withAlpha(surface, 0xA0), withAlpha(surface, 0)});
    }

    private void retint(Palette next) {
        if (next.seed == palette.seed) return;
        final Palette from = palette;
        palette = next;
        if (colorAnimator != null) colorAnimator.cancel();
        ArgbEvaluator argb = new ArgbEvaluator();
        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.setDuration(Motion.DEFAULT_EFFECTS_MS);
        colorAnimator.setInterpolator(Motion.DEFAULT_EFFECTS);
        colorAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            int top = (int) argb.evaluate(t, from.surfaceBright, next.surfaceBright);
            int bottom = (int) argb.evaluate(t, from.surface, next.surface);
            backdrop.setColors(new int[]{top, bottom});
            tintFade(bottom);
            cookie.setColor((int) argb.evaluate(t, from.primaryContainer, next.primaryContainer));
            dockShape.setColor(withAlpha((int) argb.evaluate(t, from.container, next.container), 0xB3));
        });
        colorAnimator.start();
        nav.setPalette(next);
        settingsPill.setPalette(next);
        styleNowPlaying();
        // The weather icon and the sun icon in the prayer line follow the palette, so they always share one colour.
        android.graphics.drawable.Drawable wx = weather.getCompoundDrawablesRelative()[0];
        if (wx != null) wx.setTint(next.onSurfaceVariant);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private Intent launchIntent(String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    // ---- pages ----

    private void showHome() {
        showHome(true);
    }

    private void showApps() {
        showApps(true);
    }

    /** takeFocus false: the page follows focus on a tab, so focus has to stay on the tab. */
    private void showHome(boolean takeFocus) {
        showingApps = false;
        appsPage.setVisibility(View.GONE);
        homePage.setVisibility(View.VISIBLE);
        topFade.setVisibility(View.GONE);
        date.setVisibility(View.VISIBLE);
        prayer.setVisibility(View.VISIBLE);
        updateWeatherVisibility();
        nav.select(0);
        updateCamVisibility();
        if (nowCard != null) showNowPlaying();
        if (takeFocus && dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    private void showApps(boolean takeFocus) {
        showingApps = true;
        fillApps();
        homePage.setVisibility(View.GONE);
        appsPage.setVisibility(View.VISIBLE);
        appsPage.scrollTo(0, 0);
        topFade.setAlpha(0f);
        topFade.setVisibility(View.VISIBLE);
        date.setVisibility(View.GONE);
        prayer.setVisibility(View.GONE);
        updateWeatherVisibility();
        nav.select(1);
        updateCamVisibility();
        if (nowCard != null) nowCard.setVisibility(View.GONE);
        if (takeFocus && appsGrid.getChildCount() > 0) appsGrid.getChildAt(0).requestFocus();
    }

    // ---- App options (long press) ----

    /** Long press on an app: App settings, plus Uninstall for apps the user installed (system apps can't be removed). */
    private PopupWindow optionsPopup;

    private void dismissOptions() {
        if (optionsPopup != null) optionsPopup.dismiss();
        optionsPopup = null;
    }

    /** A small tonal popup beside the tile (above it, or below when there is no room), like a tooltip with actions. */
    private void showAppOptions(View anchor, String pkg) {
        boolean removable = false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            removable = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        dismissOptions();
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(palette.containerHigh);
        menu.setBackground(bg);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        PopupWindow pw = new PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setOnDismissListener(() -> {
            if (optionsPopup == pw) optionsPopup = null;
        });
        List<String> pins = pins();
        int at = pins.indexOf(pkg);
        if (at >= 0) {
            addOption(menu, pw, "Unpin from Home", () -> changePins(pkg, 1));
        } else if (pins.size() < MAX_PINS) {
            addOption(menu, pw, "Pin to Home", () -> changePins(pkg, 0));
        }
        addOption(menu, pw, "App settings", () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        if (removable) addOption(menu, pw, "Uninstall", () -> launch(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))));

        // With two or more pinned apps, Left and Right move this one while the popup is open.
        boolean canReorder = at >= 0 && pins.size() >= 2;
        if (canReorder) {
            TextView hint = text("Left / Right to reorder", 13, palette.onSurfaceVariant, 500);
            hint.setPadding(dp(20), dp(8), dp(20), dp(6));
            menu.addView(hint);
            for (int i = 0; i < menu.getChildCount(); i++) {
                View item = menu.getChildAt(i);
                if (!item.isFocusable()) continue;
                item.setOnKeyListener((v, keyCode, event) -> {
                    if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                    if (event.getAction() == KeyEvent.ACTION_DOWN)
                        reorderPinned(pkg, keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, pw, anchor);
                    return true;
                });
            }
        }

        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = menu.getMeasuredWidth(), h = menu.getMeasuredHeight();
        int[] xy = popupSpot(anchor, w, h);
        optionsPopup = pw;
        pw.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, xy[0], xy[1]);
        menu.setAlpha(0f);
        menu.animate().alpha(1f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
        menu.getChildAt(0).requestFocus();
    }

    private int[] popupSpot(View anchor, int w, int h) {
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int x = Math.max(dp(16), Math.min(loc[0] + anchor.getWidth() / 2 - w / 2, screenW - w - dp(16)));
        int gap = dp(RING) + dp(18);
        int y = loc[1] - h - gap;
        if (y < dp(16)) y = loc[1] + anchor.getHeight() + gap;
        return new int[]{x, y};
    }

    /** Swap a pinned app with its neighbour in place (the tile views are reused), then bring the popup along. */
    private void reorderPinned(String pkg, int dir, PopupWindow pw, View anchor) {
        List<String> pins = pins();
        int at = pins.indexOf(pkg), to = at + dir;
        if (at < 0 || to < 0 || to >= pins.size() || dock.getChildCount() <= Math.max(at, to)) return;
        Collections.swap(pins, at, to);
        getSharedPreferences("home", MODE_PRIVATE).edit().putString("pins", TextUtils.join(",", pins)).apply();
        dockKey = null;
        int lo = Math.min(at, to), hi = Math.max(at, to);
        View vlo = dock.getChildAt(lo), vhi = dock.getChildAt(hi);
        ViewGroup.LayoutParams lpLo = vlo.getLayoutParams(), lpHi = vhi.getLayoutParams();
        dock.removeViewAt(hi);
        dock.removeViewAt(lo);
        dock.addView(vhi, lo, lpLo);
        dock.addView(vlo, hi, lpHi);
        (dir < 0 ? vhi : vlo).requestFocus();   // the tile that moved keeps the focus
        View menu = pw.getContentView();
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = menu.getMeasuredWidth(), h = menu.getMeasuredHeight();
        dock.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                dock.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (optionsPopup != pw) return;
                int[] xy = popupSpot(anchor, w, h);
                pw.update(xy[0], xy[1], -1, -1);
            }
        });
    }

    private TextView addOption(LinearLayout menu, PopupWindow pw, String label, Runnable action) {
        TextView item = text(label, 18, palette.onSurface, 560);
        item.setFocusable(true);
        item.setClickable(true);
        item.setMinWidth(dp(190));
        item.setPadding(dp(20), dp(13), dp(20), dp(13));
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(15));
        fill.setColor(Color.TRANSPARENT);
        item.setBackground(fill);
        item.setOnFocusChangeListener((v, focused) -> {
            fill.setColor(focused ? palette.inverseSurface : Color.TRANSPARENT);
            item.setTextColor(focused ? palette.inverseOnSurface : palette.onSurface);
        });
        item.setOnClickListener(v -> {
            pw.dismiss();
            action.run();
        });
        menu.addView(item);
        return item;
    }

    // ---- Settings pill menu (long press): sleep timer and inputs ----

    private static final String SLEEP_KEY = "tv_device_sleep_timer_key";   // the TV's own timer (read only: apps cannot write it)

    private int sleepMinutes() {
        int own = HomeKeyService.sleepSetMinutes();
        return own > 0 ? own : Settings.System.getInt(getContentResolver(), SLEEP_KEY, 0) * 10;
    }

    /** Seconds until the TV turns itself off, or 0 when no timer runs. */
    private int sleepLeft() {
        int own = HomeKeyService.sleepLeftSeconds();
        if (own > 0) return own;
        if (sleepMinutes() == 0) return 0;
        try {
            long at = Long.parseLong(Settings.Global.getString(getContentResolver(), "tv_device_sleep_time"));
            long ms = at - System.currentTimeMillis();
            return ms > 0 ? (int) (ms / 1000) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static final int[] SLEEP_MINUTES = {0, 15, 20, 25, 30, 45, 60};

    private void setSleep(int seconds) {
        if (!HomeKeyService.setSleep(seconds)) launch(new Intent(Settings.ACTION_SETTINGS));   // helper service off: use the TV's own timer
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Settings: the launcher's own settings, or Android's. */
    private void showSettingsMenu(View anchor) {
        LinearLayout menu = newMenu();
        PopupWindow pw = newPopup(menu);
        addOption(menu, pw, "Launcher Settings", this::showLauncherSettings);
        addOption(menu, pw, "System Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS)));
        presentMenu(anchor, menu, pw, null);
    }

    // ---- Launcher Settings: a right-hand panel in the style of the TV's own Settings ----

    private static final class Row {
        final String title, sub;
        final boolean checked;
        final Runnable action, onFocus;   // onFocus: when the row is highlighted (null for none)

        Row(String title, String sub, boolean checked, Runnable action, Runnable onFocus) {
            this.title = title;
            this.sub = sub;
            this.checked = checked;
            this.action = action;
            this.onFocus = onFocus;
        }
    }

    private FrameLayout rootFrame, settingsPanel;
    private LinearLayout panelRows;
    private TextView panelTitle;
    private int panelPage;   // 0 closed, 1 launcher settings, 2 night dim

    private static String dimLabel(int percent) {
        return percent == 0 ? "Off" : percent == 20 ? "Light" : percent == 40 ? "Medium" : "Strong";
    }

    private void showLauncherSettings() {
        dismissOptions();
        if (settingsPanel == null) {
            settingsPanel = new FrameLayout(this);
            settingsPanel.setBackgroundColor(0xFF202124);
            settingsPanel.setElevation(dp(16));
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            panelTitle = text("", 24, 0xFFFFFFFF, 760);
            panelTitle.setGravity(Gravity.CENTER_VERTICAL);
            panelTitle.setPadding(dp(24), 0, dp(24), 0);
            panelTitle.setBackgroundColor(0xFF1A1B1E);
            column.addView(panelTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));
            panelRows = new LinearLayout(this);
            panelRows.setOrientation(LinearLayout.VERTICAL);
            column.addView(panelRows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            settingsPanel.addView(column, match());
        }
        boolean opening = panelPage == 0;
        panelPage = 1;
        int dim = getSharedPreferences("home", MODE_PRIVATE).getInt("dim", 40);
        java.util.List<Row> main = new ArrayList<>();
        main.add(new Row("Night dim", dim == 0 ? "Off" : dimLabel(dim) + " \u00B7 " + dim + "%", false, this::showNightDimPage, null));
        main.add(new Row("Location", ("auto".equals(InfoLines.mode(this)) ? "Automatic \u00B7 " : "") + PrayerTimes.placeName(), false, this::showLocationPage, null));
        showPanelRows("Launcher settings", main, 0, false);
        endDimPreview.run();   // back from Night dim (or just opened): the dim follows the sun again
        if (opening) {
            rootFrame.addView(settingsPanel, new FrameLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
            settingsPanel.setTranslationX(dp(360));
            settingsPanel.animate().translationX(0).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
        }
    }

    private void showNightDimPage() {
        panelPage = 2;
        int current = getSharedPreferences("home", MODE_PRIVATE).getInt("dim", 40);
        java.util.List<Row> rows = new ArrayList<>();
        int at = 0;
        for (int i = 0; i < DIM_LEVELS.length; i++) {
            final int percent = DIM_LEVELS[i];
            if (percent == current) at = i;
            rows.add(new Row(dimLabel(percent), percent == 0 ? null : percent + "% at full night", percent == current, () -> {
                getSharedPreferences("home", MODE_PRIVATE).edit().putInt("dim", percent).apply();
                showLauncherSettings();
            }, () -> previewDim(percent)));
        }
        showPanelRows("Night dim", rows, at, false);
    }

    /** Where the weather and the prayer times are for: found from this TV's IP address, or a city you search for. */
    private void showLocationPage() {
        panelPage = 3;
        boolean auto = "auto".equals(InfoLines.mode(this));
        java.util.List<Row> rows = new ArrayList<>();
        rows.add(new Row("Detect automatically", "From this TV's IP address" + (auto ? " \u00B7 " + PrayerTimes.placeName() : ""), auto, () -> {
            new Thread(() -> {
                InfoLines.detect(this);
                weatherRefresh();
                runOnUiThread(() -> { if (panelPage == 3) showLocationPage(); });
            }, "detect").start();
        }, null));
        rows.add(new Row("Search for a city", auto ? "Type a city name" : PrayerTimes.placeName(), !auto, () -> showSearchPage("", null), null));
        showPanelRows("Location", rows, auto ? 0 : 1, false);
    }

    private void showSearchPage(String typed, java.util.List<InfoLines.City> results) {
        panelPage = 4;
        java.util.List<Row> rows = new ArrayList<>();
        if (results != null) {
            for (InfoLines.City city : results) {
                rows.add(new Row(city.label, null, false, () -> new Thread(() -> {
                    InfoLines.setCity(this, city);
                    weatherRefresh();
                    runOnUiThread(() -> { if (panelPage == 4) showLocationPage(); });
                }, "city").start(), null));
            }
            if (results.isEmpty()) rows.add(new Row("No city found", "Try another name", false, () -> { }, null));
        }
        showPanelRows("Search for a city", rows, 0, true);
        android.widget.EditText box = new android.widget.EditText(this);
        box.setText(typed);
        box.setHint("Type a city name");
        box.setHintTextColor(0xFF9AA0A6);
        box.setTextColor(0xFFE8EAED);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        box.setTypeface(font(560));
        box.setSingleLine(true);
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.setBackgroundColor(0xFF2E3033);
        box.setPadding(dp(24), dp(16), dp(24), dp(16));
        box.setOnEditorActionListener((v, action, event) -> {
            String q = v.getText().toString();
            new Thread(() -> {
                java.util.List<InfoLines.City> found = InfoLines.search(this, q);
                runOnUiThread(() -> { if (panelPage == 4) showSearchPage(q, found); });
            }, "search").start();
            return true;
        });
        // Focus stays in the panel: Left and Right do nothing here (typing uses the keyboard).
        box.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || (keyCode == KeyEvent.KEYCODE_DPAD_UP)));
        panelRows.addView(box, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.requestFocus();
    }

    private void showPanelRows(String title, java.util.List<Row> rows, int focusAt, boolean openTop) {
        panelTitle.setText(title);
        panelRows.removeAllViews();
        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(24), dp(14), dp(24), dp(14));
            row.setFocusable(true);
            row.setClickable(true);
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.addView(text(r.title, 18, 0xFFE8EAED, 560));
            if (r.sub != null) {
                TextView sub = text(r.sub, 14, 0xFF9AA0A6, 500);
                sub.setPadding(0, dp(2), 0, 0);
                words.addView(sub);
            }
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (r.checked) row.addView(text("\u2713", 20, 0xFFE8EAED, 760));
            row.setBackgroundColor(Color.TRANSPARENT);
            row.setOnFocusChangeListener((v, focused) -> {
                row.setBackgroundColor(focused ? 0xFF3C4043 : Color.TRANSPARENT);
                if (focused && r.onFocus != null) r.onFocus.run();
            });
            row.setOnClickListener(v -> r.action.run());
            // Focus stays inside the panel: Left and Right do nothing, and Up and Down stop at the ends.
            row.setOnKeyListener((v, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (keyCode == KeyEvent.KEYCODE_DPAD_UP && index == 0 && !openTop)
                    || (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.size() - 1)));
            panelRows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (panelRows.getChildCount() > 0) panelRows.getChildAt(Math.min(focusAt, panelRows.getChildCount() - 1)).requestFocus();
    }

    /** Back: from Night dim to Launcher settings, from there out of the panel. True when the panel took the key. */
    private boolean panelBack() {
        if (panelPage == 0) return false;
        if (panelPage == 2 || panelPage == 3) {
            showLauncherSettings();
            return true;
        }
        if (panelPage == 4) {
            showLocationPage();
            return true;
        }
        panelPage = 0;
        final FrameLayout p = settingsPanel;
        p.animate().translationX(dp(360)).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                .withEndAction(() -> rootFrame.removeView(p)).start();
        settingsPill.button(2).requestFocus();
        return true;
    }

    private void showSleepMenu(View anchor) {
        LinearLayout menu = newMenu();
        PopupWindow pw = newPopup(menu);
        int current = HomeKeyService.sleepSetMinutes();
        int left = sleepLeft();
        addHeader(menu, left > 0 ? "Sleep timer  ·  " + formatLeft(left) + " left" : "Sleep timer");
        TextView focus = null;
        for (int m : SLEEP_MINUTES) {
            String label = m == 0 ? "Off" : m + " minutes";
            TextView item = addOption(menu, pw, m == current || (m == 0 && current == 0) ? label + "  ·  on" : label, () -> setSleep(m * 60));
            if (m == current) focus = item;
        }
        presentMenu(anchor, menu, pw, focus);
    }

    private LinearLayout newMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(palette.containerHigh);
        menu.setBackground(bg);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        return menu;
    }

    private PopupWindow newPopup(LinearLayout menu) {
        PopupWindow pw = new PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setOnDismissListener(() -> {
            if (optionsPopup == pw) optionsPopup = null;
        });
        return pw;
    }

    private void addHeader(LinearLayout menu, String label) {
        TextView header = text(label, 13, palette.onSurfaceVariant, 500);
        header.setPadding(dp(20), dp(8), dp(20), dp(6));
        menu.addView(header);
    }

    private void presentMenu(View anchor, LinearLayout menu, PopupWindow pw, TextView focus) {
        dismissOptions();
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] xy = popupSpot(anchor, menu.getMeasuredWidth(), menu.getMeasuredHeight());
        optionsPopup = pw;
        pw.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, xy[0], xy[1]);
        menu.setAlpha(0f);
        menu.animate().alpha(1f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
        if (focus == null) {
            for (int i = 0; i < menu.getChildCount() && focus == null; i++)
                if (menu.getChildAt(i).isFocusable()) focus = (TextView) menu.getChildAt(i);
        }
        if (focus != null) focus.requestFocus();
    }

    /** 0:05, 28:05, 1:28:05 */
    private static String formatLeft(int seconds) {
        int h = seconds / 3600, m = seconds % 3600 / 60, sec = seconds % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    private static int inputRank(TvInputInfo in) {
        int type = in.getType();
        return type == TvInputInfo.TYPE_HDMI ? 0 : type == TvInputInfo.TYPE_TUNER ? 2 : 1;
    }

    private void showInputsMenu(View anchor) {
        LinearLayout menu = newMenu();
        PopupWindow pw = newPopup(menu);
        addHeader(menu, "Inputs");
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>();
            for (TvInputInfo in : tim.getTvInputList()) {
                // Hardware inputs only (not apps' preview inputs); CEC devices are on the dock.
                if (in.getId().matches(".*/HW\\d+") && in.getParentId() == null) inputs.add(in);
            }
            // HDMI first, then other wired inputs, the tuner last; by name within each.
            inputs.sort((x, y) -> {
                int rx = inputRank(x), ry = inputRank(y);
                return rx != ry ? Integer.compare(rx, ry) : String.valueOf(x.loadLabel(this)).compareToIgnoreCase(String.valueOf(y.loadLabel(this)));
            });
            for (TvInputInfo in : inputs) {
                String label = in.getType() == TvInputInfo.TYPE_TUNER ? "Live TV" : String.valueOf(in.loadLabel(this));
                if (in.isPassthroughInput() && tim.getInputState(in.getId()) == TvInputManager.INPUT_STATE_CONNECTED)
                    label += "  ·  connected";
                final Intent go = in.isPassthroughInput()
                        ? new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.getId()))
                        : launchIntent("com.android.tv");
                if (go != null) addOption(menu, pw, label, () -> launch(go));
            }
        }
        presentMenu(anchor, menu, pw, null);
    }

    // ---- Weather ----

    private void updateWeatherVisibility() {
        weather.setVisibility(!showingApps && weather.getText().length() > 0 ? View.VISIBLE : View.GONE);
    }

    /** Off the main thread: ask the companion for the current weather. No companion or no reading: the line stays hidden. */
    private void weatherRefresh() {
        InfoLines.Weather w = InfoLines.weather(HomeActivity.this);
        weatherBusy.set(false);
        handler.post(() -> {
            if (w != null && w.text.length() > 0) weather.setText(w.text);
            if (w != null && w.icon != 0) {
                android.graphics.drawable.Drawable d = getDrawable(w.icon);
                if (d != null) {
                    d.setBounds(0, 0, dp(20), dp(20));
                    d.setTint(palette.onSurfaceVariant);
                    weather.setCompoundDrawablesRelative(d, null, null, null);
                    weather.setCompoundDrawablePadding(dp(8));
                }
            }
            updateWeatherVisibility();
        });
    }

    // ---- Camera card ----

    private View buildCamCard() {
        int w = dp(200);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(radius);
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);
        camImage = new ImageView(this);
        camImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(camImage, match());
        camLabel = text("", 12, Color.WHITE, 560);
        camLabel.setSingleLine(true);
        camLabel.setEllipsize(TextUtils.TruncateAt.END);
        camLabel.setPadding(dp(10), dp(4), dp(10), dp(4));
        camLabel.setBackgroundColor(0x99000000);
        card.addView(camLabel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        card.setContentDescription("Camera");

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);
        ringFrame.addView(card, new FrameLayout.LayoutParams(w, h));
        ringFrame.setVisibility(View.GONE);

        card.setOnClickListener(v -> launch(new Intent().setComponent(new ComponentName(CAM_PKG, CAM_PKG + ".LiveActivity"))));
        card.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1.10f : 1f;
            ringFrame.animate().scaleX(scale).scaleY(scale)
                    .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
            card.setOutlineSpotShadowColor(Color.TRANSPARENT);   // no cast shadow: it peeked out at the bottom corners with a hard edge
            card.setOutlineAmbientShadowColor(Color.TRANSPARENT);
            card.animate().translationZ(focused ? dp(14) : 0)
                    .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.DEFAULT_EFFECTS_MS).start();
            ObjectAnimator corners = ObjectAnimator.ofFloat(cardShape, "cornerRadius", cardShape.getCornerRadius(), focused ? radius * 0.62f : radius)
                    .setDuration(Motion.FAST_SPATIAL_MS);
            corners.setInterpolator(Motion.FAST_SPATIAL);
            // The clip follows the background's outline; without this it keeps the old, rounder corners and the card shows through.
            corners.addUpdateListener(an -> card.invalidateOutline());
            corners.start();
            ring.setCornerRadius((focused ? radius * 0.62f : radius) + dp(RING));
            fadeRing(ring, focused);
        });
        return ringFrame;
    }

    private void updateCamVisibility() {
        boolean show = camHasImage && !showingApps;
        int visibility = show ? View.VISIBLE : View.GONE;
        if (camRing.getVisibility() == visibility) return;
        // A view that turns visible takes focus when nothing has it yet: keep it with the dock instead.
        View focused = getCurrentFocus();
        camRing.setVisibility(visibility);
        if (!show) return;
        if (focused != null) focused.requestFocus();
        else if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    /** Off the main thread: ask the companion for a fresh still, then read it. Missing companion: the card stays hidden. */
    private void camRefresh() {
        try {
            android.content.ContentResolver cr = getContentResolver();
            cr.call(CAM_URI, "refresh", null, null);
            String name = null;
            long updated = 0;
            try (Cursor c = cr.query(CAM_URI, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    name = c.getString(0);
                    updated = c.getLong(1);
                }
            }
            Bitmap bmp = null;
            if (updated > 0) {
                try (ParcelFileDescriptor pfd = cr.openFileDescriptor(CAM_URI, "r")) {
                    if (pfd != null) bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                }
            }
            final Bitmap fb = bmp;
            final String fn = name;
            final long fu = updated;
            handler.post(() -> showCam(fb, fn, fu));
        } catch (Exception e) {
            handler.post(() -> showCam(null, null, 0));
        } finally {
            camBusy.set(false);
        }
    }

    private void showCam(Bitmap bmp, String name, long updated) {
        if (bmp != null) {
            camImage.setImageBitmap(bmp);
            camHasImage = true;
            long age = (System.currentTimeMillis() - updated) / 1000;
            camLabel.setText((name == null ? "Camera" : name) + (age > 90 ? " · offline" : ""));
        }
        updateCamVisibility();
    }

    // ---- Now Playing ----

    private View buildNowPlaying() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(14), dp(12));
        nowShape = new GradientDrawable();
        // Use the measured height so the card stays a pill when text or font scaling changes its height.
        card.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                nowShape.setCornerRadius((bottom - top) / 2f));
        card.setBackground(nowShape);
        card.setVisibility(View.GONE);

        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artShape = new GradientDrawable();
        artShape.setCornerRadius(dp(Focus.MEDIA));
        artShape.setColor(0x33FFFFFF);
        nowArt.setBackground(artShape);
        nowArt.setClipToOutline(true);
        card.addView(nowArt, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, dp(10), 0);
        TextView label = text("NOW PLAYING", 12, palette.onSurfaceVariant, 760);
        label.setLetterSpacing(0.2f);
        words.addView(label);
        nowTitle = text("", 16, palette.onSurface, 760);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowTitle.setPadding(0, dp(3), 0, 0);
        words.addView(nowTitle);
        nowArtist = text("", 14, palette.onSurfaceVariant, 500);
        nowArtist.setSingleLine(true);
        nowArtist.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(nowArtist);
        nowProgress = new WavyProgress(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        pl.topMargin = dp(6);
        words.addView(nowProgress, pl);
        card.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        nowButton = new ImageView(this);
        nowButton.setScaleType(ImageView.ScaleType.CENTER);
        nowButtonShape = new GradientDrawable();
        nowButtonShape.setShape(GradientDrawable.OVAL);
        nowButton.setBackground(nowButtonShape);
        nowButton.setFocusable(true);
        nowButton.setClickable(true);
        nowButton.setContentDescription("Play or pause");
        nowButton.setOnClickListener(v -> togglePlayback());
        nowButton.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
            styleNowPlaying();
        });
        card.addView(nowButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        styleNowPlaying();
        return card;
    }

    private void styleNowPlaying() {
        if (nowShape == null) return;
        nowShape.setColor(withAlpha(palette.container, 0xD9));
        boolean focused = nowButton.hasFocus();
        nowButtonShape.setColor(focused ? palette.inverseSurface : palette.primary);
        nowButton.setImageTintList(ColorStateList.valueOf(focused ? palette.inverseOnSurface : palette.onPrimary));
        nowTitle.setTextColor(palette.onSurface);
        nowArtist.setTextColor(palette.onSurfaceVariant);
        nowProgress.setColors(palette.primary, withAlpha(palette.onSurfaceVariant, 0x55));
    }

    private void startNowPlaying() {
        try {
            if (sessions == null) {
                sessions = getSystemService(MediaSessionManager.class);
                mediaListener = new ComponentName(this, MediaListener.class);
            }
            if (!listening) {
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, mediaListener);
                listening = true;
            }
            pickSession();
        } catch (SecurityException notAllowed) {
            // Notification access not granted: the card simply stays hidden.
            nowCard.setVisibility(View.GONE);
        }
    }

    private void stopNowPlaying() {
        if (listening) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignored) {
            }
            listening = false;
        }
        if (playing != null) playing.unregisterCallback(mediaCallback);
        playing = null;
        liveMeta = null;
        liveState = null;
    }

    private void pickSession() {
        if (!listening) return;   // a change posted before onDestroy let the listener go: nothing to pick for
        MediaController best = null;
        MediaMetadata bestMeta = null;
        PlaybackState bestState = null;
        try {
            for (MediaController c : sessions.getActiveSessions(mediaListener)) {
                MediaMetadata m = c.getMetadata();
                if (m == null) continue;
                PlaybackState st = c.getPlaybackState();
                if (best == null || (st != null && st.getState() == PlaybackState.STATE_PLAYING)) {
                    best = c;
                    bestMeta = m;
                    bestState = st;
                }
            }
        } catch (SecurityException e) {
            best = null;
        }
        // getActiveSessions hands out a new controller object for the same session each time: the callback is
        // registered on exactly one object per token, so keep that object when the token is unchanged (a register or
        // unregister on a fresh twin would leave the old registration writing liveMeta/liveState for a stale session).
        boolean same = playing != null && best != null && best.getSessionToken().equals(playing.getSessionToken());
        if (same) {
            best = playing;
        } else {
            if (playing != null) playing.unregisterCallback(mediaCallback);
            if (best != null) {
                best.registerCallback(mediaCallback, handler);
                sessionPlayFailed = false;
            }
        }
        playing = best;
        liveMeta = bestMeta;
        liveState = bestState;
        showNowPlaying();
    }

    /** The device's "on" dot: visible while the HDMI device reports connected (on), hidden in standby or unplugged. */
    private void showDeviceState(String inputId, int state) {
        View dot = deviceDots.get(inputId);
        if (dot == null) return;
        boolean on = state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
        if (on == (dot.getVisibility() == View.VISIBLE)) return;
        if (on) {
            dot.setScaleX(0f);
            dot.setScaleY(0f);
            dot.setVisibility(View.VISIBLE);
            dot.animate().scaleX(1f).scaleY(1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
        } else {
            dot.animate().scaleX(0f).scaleY(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                    .withEndAction(() -> dot.setVisibility(View.GONE)).start();
        }
    }

    /** Show the equalizer badge on the dock tile of the app that's playing (if it's in the dock). */
    private void updateBadges() {
        PlaybackState st = playing != null ? playing.getPlaybackState() : null;
        String pkg = st != null && st.getState() == PlaybackState.STATE_PLAYING ? playing.getPackageName() : null;
        for (Map.Entry<String, EqualizerBadge> e : dockBadges.entrySet()) {
            boolean on = e.getKey().equals(pkg);
            EqualizerBadge b = e.getValue();
            if (on && b.getVisibility() != View.VISIBLE) {
                b.setAlpha(0f);
                b.setScaleX(0.6f);
                b.setScaleY(0.6f);
                b.setVisibility(View.VISIBLE);
                b.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
            } else if (!on && b.getVisibility() == View.VISIBLE) {
                b.animate().alpha(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                        .withEndAction(() -> b.setVisibility(View.GONE)).start();
            }
        }
    }

    // The last thing that played, kept after its app lets the session go (YouTube stops as soon as you leave it),
    // with whatever the app gave us to get back to it: an item id/uri or the session's own activity when it posts
    // one (Spotify and YouTube on this TV post neither, so for them play reopens the app and the card is a reminder).
    // The fields follow every session callback, also while another app has the screen; views are touched on Home only.
    private static final long REMEMBER_MS = 6 * 3_600_000L;
    private static final long RESUME_CHECK_MS = 1500;
    private boolean listening;               // the active-sessions listener is registered (until onDestroy)
    private boolean foreground;              // between onResume and onPause: the card may be drawn
    private MediaMetadata liveMeta;          // what the picked session last reported (one fetch per pick, then callbacks)
    private PlaybackState liveState;
    private CharSequence lastTitle, lastArtist;
    private Bitmap lastArt, shownArt;
    private String lastPkg, lastItem, lastMediaId, lastMediaUri;
    private PendingIntent lastSessionActivity;
    private int lastState;
    private float lastSpeed;
    private long lastPosition, lastUpdateTime, lastDuration, lastSeenAt;
    private boolean wasActive, showingRemembered, sessionPlayFailed;
    private final Runnable expire = () -> {
        forget();
        if (foreground && nowCard != null) showNowPlaying();
    };
    // play() went to a session that only said it could play: if nothing is playing after a moment, go on to the app.
    private final Runnable checkResumed = () -> {
        if (!foreground || !showingRemembered) return;
        sessionPlayFailed = true;
        resumeRemembered();
    };

    private void showNowPlaying() {
        MediaMetadata m = playing != null ? liveMeta : null;
        PlaybackState st = playing != null ? liveState : null;
        int state = st != null ? st.getState() : PlaybackState.STATE_NONE;
        boolean running = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        boolean active = m != null && (running || state == PlaybackState.STATE_PAUSED);
        if (active) remember(m, st);
        else if (wasActive) freeze();
        wasActive = active;
        if (!foreground || nowCard == null) return;
        updateBadges();
        if (!active && !showingApps && lastPkg != null
                && System.currentTimeMillis() - lastSeenAt < REMEMBER_MS && launchIntent(lastPkg) != null) {
            showRemembered();
            return;
        }
        showingRemembered = false;
        if (!active || showingApps) {
            if (nowCard.getVisibility() == View.VISIBLE) {
                nowCard.animate().alpha(0f).setDuration(200).withEndAction(() -> nowCard.setVisibility(View.GONE)).start();
            }
            return;
        }
        handler.removeCallbacks(checkResumed);
        nowTitle.setText(lastTitle);
        nowArtist.setText(lastArtist);
        showArt();
        nowProgress.setVisibility(lastDuration > 0 ? View.VISIBLE : View.GONE);
        nowProgress.setState(st.getPosition(), st.getLastPositionUpdateTime(), st.getPlaybackSpeed(), lastDuration, running);
        nowButton.setImageResource(running ? R.drawable.ic_pause : R.drawable.ic_play);
        styleNowPlaying();
        // Cancel a pending fade-out first: its end action would otherwise hide the card again.
        nowCard.animate().cancel();
        if (nowCard.getVisibility() != View.VISIBLE || nowCard.getAlpha() < 1f) {
            nowCard.setAlpha(0f);
            nowCard.setTranslationY(dp(10));
            nowCard.setVisibility(View.VISIBLE);
            nowCard.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
        }
    }

    /**
     * Note what the live session shows, on every callback, so the card can bring it back once the session is gone.
     * A session reports several times a second, so anything that costs a binder or PackageManager call (the session
     * activity, the app label standing in for a missing artist) or copies the art is done once per item only.
     */
    private void remember(MediaMetadata m, PlaybackState st) {
        CharSequence title = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        String pkg = playing.getPackageName();
        String item = pkg + "|" + m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) + "|" + title + "|" + artist + "|"
                + m.getText(MediaMetadata.METADATA_KEY_ALBUM);
        if (!item.equals(lastItem)) {
            lastItem = item;
            lastTitle = title != null ? title : "";
            lastArtist = artist != null ? artist : appLabel(pkg);
            lastPkg = pkg;
            lastSessionActivity = playing.getSessionActivity();
            lastArt = null;
        }
        // The art copies made the card flicker: fetch it for a new item only, or until it arrives for the current one.
        if (lastArt == null) {
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            lastArt = art;
        }
        lastMediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        lastMediaUri = m.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        lastDuration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        lastState = st.getState();
        lastPosition = st.getPosition();
        lastUpdateTime = st.getLastPositionUpdateTime();
        lastSpeed = st.getPlaybackSpeed();
        lastSeenAt = System.currentTimeMillis();
        if (lastState == PlaybackState.STATE_PLAYING || lastState == PlaybackState.STATE_BUFFERING) sessionPlayFailed = false;
        handler.removeCallbacks(expire);
    }

    /** The session stopped or went away: pin the position it had at this moment and start the 6 h clock. */
    private void freeze() {
        lastPosition = rememberedPosition();
        lastState = PlaybackState.STATE_PAUSED;
        lastSpeed = 0f;
        lastUpdateTime = android.os.SystemClock.elapsedRealtime();
        lastSeenAt = System.currentTimeMillis();
        sessionPlayFailed = false;
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, REMEMBER_MS);
    }

    /** Where the remembered item is now: the last reported position, run forward while it was playing, within its length. */
    private long rememberedPosition() {
        long p = lastPosition;
        if (lastState == PlaybackState.STATE_PLAYING) {
            p += (long) ((android.os.SystemClock.elapsedRealtime() - lastUpdateTime) * lastSpeed);
        }
        if (lastDuration > 0) p = Math.min(p, lastDuration);
        return Math.max(0, p);
    }

    /** Six hours on: nothing to come back to, and no artwork or app intent kept around (the view lets go of it too). */
    private void forget() {
        lastPkg = lastItem = lastMediaId = lastMediaUri = null;
        lastTitle = lastArtist = null;
        lastArt = shownArt = null;
        if (nowArt != null) nowArt.setImageBitmap(null);
        lastSessionActivity = null;
        lastPosition = lastDuration = 0;
        handler.removeCallbacks(expire);
    }

    private void showArt() {
        if (shownArt == lastArt) return;
        nowArt.setImageBitmap(lastArt);
        shownArt = lastArt;
    }

    /** The remembered card: paused at where you left off; play brings that item back (see resumeRemembered). */
    private void showRemembered() {
        showingRemembered = true;
        nowTitle.setText(lastTitle != null ? lastTitle : "");
        nowArtist.setText(lastArtist != null ? lastArtist : appLabel(lastPkg));
        showArt();
        nowProgress.setVisibility(lastDuration > 0 ? View.VISIBLE : View.GONE);
        nowProgress.setState(rememberedPosition(), android.os.SystemClock.elapsedRealtime(), 0f, lastDuration, false);
        // Never fake it: the pause icon appears only once a session reports playing again (through showNowPlaying).
        nowButton.setImageResource(R.drawable.ic_play);
        styleNowPlaying();
        nowCard.animate().cancel();
        if (nowCard.getVisibility() != View.VISIBLE || nowCard.getAlpha() < 1f) {
            nowCard.setAlpha(1f);
            nowCard.setTranslationY(0);
            nowCard.setVisibility(View.VISIBLE);
        }
    }

    private void togglePlayback() {
        if (showingRemembered) {
            resumeRemembered();
            return;
        }
        if (playing == null) return;
        PlaybackState st = playing.getPlaybackState();
        boolean running = st != null
                && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
        if (running) playing.getTransportControls().pause();
        else playing.getTransportControls().play();
    }

    /**
     * Play the remembered item the most direct way its app still allows, in this order: the session itself if it
     * quietly came back (keeps playing in the background), a deep link to the exact item where we can read its id,
     * the session's own activity for apps whose ids we cannot read, and last the app as it would open from the dock.
     */
    private void resumeRemembered() {
        if (lastPkg == null) return;
        handler.removeCallbacks(checkResumed);
        PlaybackState st = playing != null && lastPkg.equals(playing.getPackageName()) ? playing.getPlaybackState() : null;
        if (!sessionPlayFailed && st != null && st.getState() != PlaybackState.STATE_PLAYING
                && (st.getActions() & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0) {
            playing.getTransportControls().play();
            handler.postDelayed(checkResumed, RESUME_CHECK_MS);
            return;
        }
        Intent deep = resumeIntent(lastPkg, lastMediaId, lastMediaUri, rememberedPosition());
        if (deep != null && pm.resolveActivity(deep, 0) != null) {
            launch(deep);
            return;
        }
        if (lastSessionActivity != null) {
            try {
                lastSessionActivity.send();
                return;
            } catch (PendingIntent.CanceledException gone) {
                lastSessionActivity = null;
            }
        }
        Intent back = launchIntent(lastPkg);
        if (back != null) launch(back);
    }

    private static final String YOUTUBE = "com.google.android.youtube.tv";
    // An 11-character YouTube video id: after a watch?v= / youtu.be/ / embed/ marker in a URL, or standing alone in
    // anything that is not a URL. A bare token must carry a digit, capital, '-' or '_' so that an ordinary word is not
    // taken for an id ("recommended" and "googlevideo" are 11 letters too). Checked on the JVM with the same patterns:
    //   dQw4w9WgXcQ, yt:video:dQw4w9WgXcQ, watch?v=.., watch?feature=share&v=..&t=10, youtu.be/.., /embed/.. -> the id
    //   recommended, PL<34-char playlist id>, spotify:track:.., https://r3---sn-abc.googlevideo.com/.. -> nothing
    private static final Pattern YOUTUBE_URL_ID = Pattern.compile("(?:[?&]v=|youtu\\.be/|/embed/|/v/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");
    private static final Pattern YOUTUBE_BARE_ID = Pattern.compile("(?<![A-Za-z0-9_-])(?=[a-z]*[A-Z0-9_-])([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");

    /**
     * A deep link to the remembered item for the apps whose ids we know how to read, or null to leave it to the
     * session's own activity or a plain launch. Measured on the TV on 2026-10-18, neither app we care most about
     * gives us an item id, so for both this returns null and play reopens the app:
     *   Spotify  posts neither METADATA_KEY_MEDIA_ID nor MEDIA_URI (only display icon, duration, album-art uri,
     *            advertisement, album, title, artist, album art) and has no session activity.
     *   YouTube  (com.google.android.youtube.tv) posts only DURATION, an empty ALBUM and TITLE (ARTIST/ART possibly
     *            filtered); no MEDIA_ID, no MEDIA_URI, no session activity, empty extras, null queue.
     * The branches below are kept for apps or builds that do post such ids; their formats are UNVERIFIED:
     *   "spotify:track:<22 chars>" style URIs are opened as is; an 11-character YouTube video id, bare or inside a
     *   watch / youtu.be URL, is opened as https://www.youtube.com/watch?v=<id> with &t=<seconds> when a position is
     *   known; any other https URL is opened in the same app.
     */
    static Intent resumeIntent(String pkg, String mediaId, String mediaUri, long positionMs) {
        String[] keys = {mediaUri, mediaId};
        for (String k : keys) {
            if (k != null && k.startsWith("spotify:")) return view(pkg, k);
        }
        if (YOUTUBE.equals(pkg)) {
            String id = youtubeId(mediaUri);
            if (id == null) id = youtubeId(mediaId);
            if (id != null) {
                String url = "https://www.youtube.com/watch?v=" + id;
                if (positionMs > 0) url += "&t=" + positionMs / 1000;
                return view(pkg, url);
            }
        }
        for (String k : keys) {
            if (k != null && k.startsWith("https://")) return view(pkg, k);
        }
        return null;
    }

    private static Intent view(String pkg, String uri) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg);
    }

    private static String youtubeId(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = (s.contains("://") ? YOUTUBE_URL_ID : YOUTUBE_BARE_ID).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private CharSequence appLabel(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---- motion ----

    private void arrive(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(dp(18));
        v.animate().alpha(1f).translationY(0).setStartDelay(40L * index).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
    }

    // ---- helpers ----

    /** Google Sans Flex at a given weight, standard terminals (axes 'wght', 'opsz'; 'ROND' 0); falls back to sans-serif. */
    private Typeface font(int requested) {
        int weight = requested <= 400 ? 300 : requested <= 650 ? 560 : requested <= 790 ? 760 : 820;
        Typeface t = fonts.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setFontVariationSettings("'wght' " + weight + ", 'opsz' 24, 'ROND' 0")
                        .build();
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
            fonts.put(weight, t);
        }
        return t;
    }

    private TextView text(CharSequence value, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(weight));
        return t;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
