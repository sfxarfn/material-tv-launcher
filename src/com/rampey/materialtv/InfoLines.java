package com.rampey.materialtv;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;

/**
 * The text lines under the clock, shared by Home and the screensaver: the weather line (from the companion app, since
 * this app has no network permission) and the prayer line with the next sun event.
 */
final class InfoLines {
    private static final Uri WEATHER_URI = Uri.parse("content://com.polkka.cctv.weather/now");

    private InfoLines() {
    }

    static final class Weather {
        final String text;
        final int icon;

        Weather(String text, int icon) {
            this.text = text;
            this.icon = icon;
        }
    }

    /** Off the main thread. Null when the companion is missing or has no reading yet. */
    static Weather weather(ContentResolver cr) {
        try {
            cr.call(WEATHER_URI, "refresh", null, null);
            try (Cursor c = cr.query(WEATHER_URI, null, null, null, null)) {
                if (c == null || !c.moveToFirst()) return null;
                String text = c.getString(1);
                // Rain on the way takes the place of the condition, so the line keeps its length.
                String soon = c.getColumnCount() > 6 ? rainWarning(c.getInt(3), c.getLong(6)) : "";
                if (!soon.isEmpty()) text = soon;
                String line = "Feels like " + c.getInt(0) + "°C" + (text == null || text.isEmpty() ? "" : "  ·  " + text);
                int icon = c.getColumnCount() > 4 ? weatherIcon(c.getInt(3), c.getInt(4) == 1) : 0;
                if (c.getColumnCount() > 5 && c.getInt(5) >= 0) line += "  ·  AQI " + c.getInt(5) + " " + aqiLabel(c.getInt(5));
                return new Weather(line, icon);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** "Next Salah — Maghrib in 1h 15m  ·  [sunset icon] 17:24": the next sun event is an icon and a time. */
    static CharSequence prayerLine(Context context, long now, int tint, int iconPx) {
        String[] sun = PrayerTimes.nextSun(now);
        SpannableStringBuilder line = new SpannableStringBuilder(PrayerTimes.nextLabel(now));
        Drawable icon = context.getDrawable("rise".equals(sun[0]) ? R.drawable.ic_wx_sunrise : R.drawable.ic_wx_sunset);
        line.append("  ·  ");
        if (icon != null) {
            icon = icon.mutate();
            icon.setBounds(0, 0, iconPx, iconPx);
            icon.setTint(tint);
            int at = line.length();
            line.append(" ");
            line.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BOTTOM), at, at + 1, 0);
            line.append(" ");
        }
        return line.append(sun[1]);
    }

    /** "Rain in 40m", or "" when it is already raining or none is forecast in the next two hours. */
    private static String rainWarning(int code, long rainAt) {
        boolean raining = (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
        if (rainAt <= 0 || raining) return "";
        long mins = Math.max(5, Math.round((rainAt - System.currentTimeMillis()) / 300_000.0) * 5);
        return "Rain in " + (mins < 60 ? mins + "m" : mins / 60 + "h " + mins % 60 + "m");
    }

    /** US AQI bands. */
    private static String aqiLabel(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Sensitive";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very unhealthy";
        return "Hazardous";
    }

    /** WMO weather code to an icon; day or night only matters for clear and partly cloudy. */
    private static int weatherIcon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code == 2) return day ? R.drawable.ic_wx_cloud_sun : R.drawable.ic_wx_cloud_moon;
        if (code == 45 || code == 48) return R.drawable.ic_wx_cloud_fog;
        if (code >= 51 && code <= 57) return R.drawable.ic_wx_cloud_drizzle;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_wx_cloud_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_cloud_snow;
        if (code >= 95) return R.drawable.ic_wx_cloud_lightning;
        return R.drawable.ic_wx_cloud;
    }
}
