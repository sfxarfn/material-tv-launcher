package com.rampey.materialtv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
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

    /** A city the companion found for a typed name. */
    static final class City {
        final String label, name, zone;
        final double lat, lon;

        City(String label, String name, double lat, double lon, String zone) {
            this.label = label;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.zone = zone;
        }
    }

    /** Where the times and the weather are for, kept so the first screen after a restart already has it. */
    static void loadPlace(Context context) {
        SharedPreferences p = context.getSharedPreferences("place", Context.MODE_PRIVATE);
        if (p.contains("lat")) PrayerTimes.configure(p.getString("name", "Kota Batu"), Double.longBitsToDouble(p.getLong("lat", 0)),
                Double.longBitsToDouble(p.getLong("lon", 0)), p.getString("zone", "Asia/Jakarta"));
    }

    private static void savePlace(Context context, String name, double lat, double lon, String zone) {
        PrayerTimes.configure(name, lat, lon, zone);
        context.getSharedPreferences("place", Context.MODE_PRIVATE).edit().putString("name", name)
                .putLong("lat", Double.doubleToRawLongBits(lat)).putLong("lon", Double.doubleToRawLongBits(lon))
                .putString("zone", zone).apply();
    }

    /** Off the main thread: "auto" or "manual", as the companion has it; also the detected place. Null if there is no companion. */
    static String detect(Context context) {
        try {
            context.getContentResolver().call(WEATHER_URI, "detect", null, null);
            return mode(context);
        } catch (Exception e) {
            return null;
        }
    }

    /** Off the main thread: cities matching the text. */
    static List<City> search(Context context, String text) {
        List<City> found = new ArrayList<>();
        try {
            Bundle b = context.getContentResolver().call(WEATHER_URI, "search", text, null);
            if (b == null) return found;
            ArrayList<String> labels = b.getStringArrayList("labels"), names = b.getStringArrayList("names"), zones = b.getStringArrayList("zones");
            double[] lats = b.getDoubleArray("lats"), lons = b.getDoubleArray("lons");
            for (int i = 0; labels != null && i < labels.size(); i++) found.add(new City(labels.get(i), names.get(i), lats[i], lons[i], zones.get(i)));
        } catch (Exception ignored) {
        }
        return found;
    }

    /** Off the main thread: use this city from now on (manual). */
    static void setCity(Context context, City city) {
        try {
            Bundle extras = new Bundle();
            extras.putString("name", city.name);
            extras.putDouble("lat", city.lat);
            extras.putDouble("lon", city.lon);
            extras.putString("zone", city.zone);
            context.getContentResolver().call(WEATHER_URI, "set", null, extras);
            savePlace(context, city.name, city.lat, city.lon, city.zone);
        } catch (Exception ignored) {
        }
    }

    /** "auto" or "manual"; "auto" when the companion cannot be reached. */
    static String mode(Context context) {
        try (Cursor c = context.getContentResolver().query(WEATHER_URI, null, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getColumnCount() > 11) return c.getString(11);
        } catch (Exception ignored) {
        }
        return "auto";
    }

    /** Off the main thread. Null when the companion is missing; a Weather with empty text when it has no reading yet. */
    static Weather weather(Context context) {
        ContentResolver cr = context.getContentResolver();
        try {
            cr.call(WEATHER_URI, "refresh", null, null);
            try (Cursor c = cr.query(WEATHER_URI, null, null, null, null)) {
                if (c == null || !c.moveToFirst()) return null;
                if (c.getColumnCount() > 10) savePlace(context, c.getString(7), c.getDouble(8), c.getDouble(9), c.getString(10));
                if (c.isNull(0)) return new Weather("", 0);
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
        int jumuah = line.toString().indexOf("Jumuah");
        Drawable mosque = jumuah < 0 ? null : context.getDrawable(R.drawable.ic_mosque);
        if (mosque != null) {   // "Next Salah — [mosque] Jumuah in 1h 35m"
            mosque = mosque.mutate();
            mosque.setBounds(0, 0, iconPx, iconPx);
            mosque.setTint(tint);
            line.insert(jumuah, "  ");
            line.setSpan(new ImageSpan(mosque, ImageSpan.ALIGN_BOTTOM), jumuah, jumuah + 1, 0);
        }
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
