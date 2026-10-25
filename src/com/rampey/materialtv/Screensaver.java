package com.rampey.materialtv;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * The launcher's screensaver. The clock, date, weather and prayer lines sit exactly where they do on Home. Behind them:
 * a slideshow of the photos on the TV (the media store), or, when there are none, generated Material You-style
 * wallpapers (ShapeScene) where every hour a new palette and arrangement crossfades in. Shows the song if music plays.
 */
public class Screensaver extends DreamService {
    private static final long FRAME_MS = 0;           // every display frame: 60 fps on a 60 Hz TV (owner choice)
    private static final long PALETTE_EVERY = 60 * 60_000L;   // a new wallpaper every hour (owner choice)
    private static final long SLIDE_MS = 30_000L;     // each photo stays this long, with a slow zoom
    private static final long FADE_MS = 1600L;
    private static final int MAX_PHOTOS = 300;
    private static final String AXES = "'wght' 560, 'opsz' 24, 'ROND' 0";   // the Home text style

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ShapeScene scene;
    private View scrim;
    private ImageView photoA, photoB;
    private boolean frontIsA;
    private ValueAnimator zoom;
    private LinearLayout block;
    private TextView clock, date, weather, prayer, song;
    private final Map<Integer, Typeface> clockFonts = new HashMap<>();
    private final AtomicBoolean weatherBusy = new AtomicBoolean();
    private long weatherAt;
    private long lastMinute = -1;
    private int mutedNow;          // colour of the secondary lines; the prayer line's icon follows it
    private double hue;
    private long seed;
    private long last;
    private boolean running;

    private List<android.net.Uri> photos = new ArrayList<>();
    private boolean photoMode;
    private int photoIndex;

    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (!running || photoMode) return;
            long now = SystemClock.uptimeMillis();
            if (last == 0 || now - last >= FRAME_MS) {
                scene.invalidate();
                last = now;
            }
            root.postOnAnimation(this);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date d = new Date();
            String time = new SimpleDateFormat("h:mm", Locale.getDefault()).format(d);
            if (clock.getText().length() == 0) {
                clock.setText(time);
                clock.setTypeface(clockFont(clockWeight()));
            } else if (!time.contentEquals(clock.getText())) {
                changeClock(time);
            }
            long minute = d.getTime() / 60_000L;
            if (minute != lastMinute) {
                lastMinute = minute;
                date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(d));
                prayer.setText(InfoLines.prayerLine(Screensaver.this, d.getTime(), mutedNow, dp(20)));
            }
            if (System.currentTimeMillis() - weatherAt > 10 * 60_000L && weatherBusy.compareAndSet(false, true)) {
                weatherAt = System.currentTimeMillis();
                new Thread(Screensaver.this::refreshWeather, "weather").start();
            }
            updateSong();
            handler.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000) + 5);
        }
    };

    private final Runnable shift = new Runnable() {
        @Override public void run() {
            if (photoMode) return;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            ShapeScene next = sceneForNow();
            next.setAlpha(0f);
            ShapeScene old = scene;
            root.addView(next, root.indexOfChild(old) + 1,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scene = next;
            next.animate().alpha(1f).setDuration(2400).setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction(() -> root.removeView(old)).start();
            recolorText(next.onColor, next.mutedColor, next.accentColor);
            handler.postDelayed(this, PALETTE_EVERY);
        }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        hue = (System.currentTimeMillis() / 1000L) % 360;
        seed = System.currentTimeMillis();
        setContentView(build());
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        last = 0;
        root.postOnAnimation(drift);
        handler.post(tick);
        handler.postDelayed(shift, PALETTE_EVERY);
        loadPhotos();
    }

    @Override public void onDreamingStopped() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        root.removeCallbacks(drift);
        if (zoom != null) zoom.cancel();
        super.onDreamingStopped();
    }

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(new View(this));   // index 0 placeholder so scenes always sit under the text
        scene = sceneForNow();
        root.addView(scene, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        photoA = photoView();
        photoB = photoView();
        root.addView(photoA, match());
        root.addView(photoB, match());
        // A soft shade from the top-left corner keeps the text readable on any photo.
        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xB3000000, 0x00000000}));
        scrim.setVisibility(View.GONE);
        root.addView(scrim, match());

        // The same block, sizes and padding as the top-left of Home.
        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        mutedNow = scene.mutedColor;
        clock = text(72, scene.onColor, "'wght' 300, 'opsz' 48, 'ROND' 0");
        clock.setIncludeFontPadding(false);
        date = text(15, mutedNow, AXES);
        date.setPadding(dp(2), dp(2), 0, 0);
        weather = text(15, mutedNow, AXES);
        weather.setPadding(dp(2), dp(4), 0, 0);
        weather.setGravity(Gravity.CENTER_VERTICAL);
        weather.setVisibility(View.GONE);
        prayer = text(15, mutedNow, AXES);
        prayer.setPadding(dp(2), dp(4), 0, 0);
        song = text(15, scene.accentColor, AXES);
        song.setPadding(dp(2), dp(8), 0, 0);
        song.setSingleLine(true);
        song.setMaxWidth(dp(380));
        song.setEllipsize(android.text.TextUtils.TruncateAt.END);
        block.addView(clock);
        block.addView(date);
        block.addView(weather);
        block.addView(prayer);
        block.addView(song);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP);
        bl.setMargins(dp(48), dp(36), 0, 0);   // Home: 48dp side padding, 32dp top, plus the bar's 4dp
        root.addView(block, bl);
        return root;
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setVisibility(View.GONE);
        return v;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---- photos ----

    /** Off the main thread: the newest photos in the media store, shuffled. None (or no permission): the generated wallpapers stay. */
    private void loadPhotos() {
        new Thread(() -> {
            List<android.net.Uri> found = new ArrayList<>();
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    MediaStore.Images.Media.DATA + " NOT LIKE ?", new String[]{"%/Screenshots/%"},
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext() && found.size() < MAX_PHOTOS) {
                    found.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
                }
            } catch (RuntimeException ignored) {
            }
            Collections.shuffle(found);
            handler.post(() -> startSlideshow(found));
        }, "photos").start();
    }

    private void startSlideshow(List<android.net.Uri> found) {
        if (!running || found.isEmpty()) return;
        photos = found;
        photoMode = true;
        photoIndex = 0;
        scene.setVisibility(View.GONE);
        scrim.setVisibility(View.VISIBLE);
        recolorText(0xFFFFFFFF, 0xE6FFFFFF, 0xFFFFFFFF);
        nextPhoto(0);
    }

    /** Decode the next photo off the main thread, then crossfade it in; skip files that cannot be read. */
    private void nextPhoto(int failed) {
        if (!running || !photoMode) return;
        final android.net.Uri uri = photos.get(photoIndex++ % photos.size());
        new Thread(() -> {
            final Bitmap b = decode(uri);
            handler.post(() -> {
                if (!running || !photoMode) return;
                if (b == null) {
                    if (failed < photos.size()) nextPhoto(failed + 1);
                    return;
                }
                showPhoto(b);
                handler.postDelayed(() -> nextPhoto(0), SLIDE_MS);
            });
        }, "photo").start();
    }

    private void showPhoto(Bitmap b) {
        final ImageView in = frontIsA ? photoB : photoA, out = frontIsA ? photoA : photoB;
        frontIsA = !frontIsA;
        if (zoom != null) zoom.cancel();
        in.setImageBitmap(b);
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.bringToFront();
        scrim.bringToFront();
        block.bringToFront();
        out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            if (out.getAlpha() == 0f) {
                out.setImageDrawable(null);
                out.setVisibility(View.GONE);
            }
        }).start();
        zoom = ValueAnimator.ofFloat(0f, 1f).setDuration(SLIDE_MS + FADE_MS);
        zoom.setInterpolator(new android.view.animation.LinearInterpolator());
        zoom.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float s = 1f + 0.06f * t;
            in.setScaleX(s);
            in.setScaleY(s);
            in.setAlpha(Math.min(1f, t * (SLIDE_MS + FADE_MS) / FADE_MS));
        });
        zoom.start();
    }

    /** The photo scaled down to about the screen size (the TV has 1 GB of RAM), turned upright by its EXIF tag. */
    private Bitmap decode(android.net.Uri uri) {
        try {
            int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) return null;
            int degrees = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                int tag = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                degrees = tag == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : tag == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : tag == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) {
            }
            if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                Bitmap turned = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (turned != b) b.recycle();
                b = turned;
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- default wallpapers ----

    // Time of day -> hue and brightness. Keyframes: hour, hue (degrees), light (0 night .. 1 midday).
    private static final double[][] SKY = {
            {0, 265, 0.0}, {5, 250, 0.05}, {7, 35, 0.45}, {10, 75, 0.8}, {13, 205, 1.0},
            {16, 190, 0.85}, {18, 25, 0.6}, {19.5, 340, 0.35}, {21, 285, 0.1}, {24, 265, 0.0}};

    /** A new wallpaper for the current time of day, with a little randomness in hue. */
    private ShapeScene sceneForNow() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        double h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60.0;
        int i = 0;
        while (i < SKY.length - 2 && h >= SKY[i + 1][0]) i++;
        double t = (h - SKY[i][0]) / (SKY[i + 1][0] - SKY[i][0]);
        double from = SKY[i][1], to = SKY[i + 1][1];
        double d = ((to - from + 540) % 360) - 180;            // shortest way around the color wheel
        double jitter = ((seed >>> 33) % 25) - 12;              // +-12 degrees so days differ
        hue = (from + d * t + jitter + 360) % 360;
        float light = (float) (SKY[i][2] + (SKY[i + 1][2] - SKY[i][2]) * t);
        return new ShapeScene(this, hue, light, seed);
    }

    /** Glide the text colors to a new palette (a new wallpaper, or white over photos). */
    private void recolorText(int onColor, int mutedColor, int accentColor) {
        int c0 = clock.getCurrentTextColor(), d0 = date.getCurrentTextColor(), s0 = song.getCurrentTextColor();
        ArgbEvaluator argb = new ArgbEvaluator();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(2400);
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            int muted = (int) argb.evaluate(t, d0, mutedColor);
            mutedNow = muted;
            clock.setTextColor((int) argb.evaluate(t, c0, onColor));
            date.setTextColor(muted);
            weather.setTextColor(muted);
            prayer.setTextColor(muted);
            song.setTextColor((int) argb.evaluate(t, s0, accentColor));
            Drawable icon = weather.getCompoundDrawablesRelative()[0];
            if (icon != null) icon.setTint(muted);
            lastMinute = -1;   // the prayer line's icon takes the new colour on the next tick
        });
        a.start();
    }

    // ---- weather, clock, song ----

    /** Off the main thread: the companion's weather reading; nothing shows if it has none. */
    private void refreshWeather() {
        InfoLines.Weather w = InfoLines.weather(getContentResolver());
        weatherBusy.set(false);
        handler.post(() -> {
            if (w == null || w.text.isEmpty()) return;
            weather.setText(w.text);
            Drawable d = w.icon == 0 ? null : getDrawable(w.icon);
            if (d != null) {
                d.setBounds(0, 0, dp(20), dp(20));
                d.setTint(mutedNow);
                weather.setCompoundDrawablesRelative(d, null, null, null);
                weather.setCompoundDrawablePadding(dp(8));
            }
            weather.setVisibility(View.VISIBLE);
        });
    }

    /** Clock weight through the day, as on Home: 250 at night, rising to 430 by mid-morning, easing back after sunset. */
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
                t = Typeface.DEFAULT;
            }
            clockFonts.put(w, t);
        }
        return t;
    }

    /** A new minute, as on Home: the old time lifts away, the new one settles in with a short weight swell. */
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

    /** "♪ Title · Artist" while music plays (needs the Now Playing listener the launcher already uses). */
    private void updateSong() {
        String line = "";
        try {
            MediaSessionManager m = getSystemService(MediaSessionManager.class);
            for (MediaController c : m.getActiveSessions(new ComponentName(this, MediaListener.class))) {
                PlaybackState st = c.getPlaybackState();
                MediaMetadata md = c.getMetadata();
                if (st == null || md == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                CharSequence title = md.getText(MediaMetadata.METADATA_KEY_TITLE);
                CharSequence artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
                if (title == null) continue;
                line = "♪  " + title + (artist != null ? "  ·  " + artist : "");
                break;
            }
        } catch (SecurityException ignored) {
        }
        song.setText(line);
        song.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private TextView text(float sp, int color, String axes) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        try {
            t.setTypeface(new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf").setFontVariationSettings(axes).build());
        } catch (RuntimeException ignored) {
        }
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
