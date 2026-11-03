package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The weather under the clock, from Open-Meteo (no key). Off until a city is chosen in Launcher settings: nothing is
 * asked of the service before that. Read when Home comes up, at most every half hour, and never while Home is away.
 */
final class Weather {
    static final long REFRESH_MS = 30 * 60 * 1000L;
    /** An older reading is not shown: no weather beats wrong weather. */
    static final long STALE_MS = 3 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 256 * 1024;
    private static final String PREFS = "weather";

    static final class Place {
        final String name, detail;
        final double latitude, longitude;

        Place(String name, String detail, double latitude, double longitude) {
            this.name = name;
            this.detail = detail;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class Reading {
        final double tempC, highC, lowC;
        final int code;
        final boolean day;
        final long time;

        Reading(double tempC, double highC, double lowC, int code, boolean day, long time) {
            this.tempC = tempC;
            this.highC = highC;
            this.lowC = lowC;
            this.code = code;
            this.day = day;
            this.time = time;
        }
    }

    private static Reading last;
    private static boolean fetching;

    private Weather() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The chosen city, or null while the weather is off. */
    static Place place(Context context) {
        SharedPreferences p = prefs(context);
        String name = p.getString("name", null);
        if (name == null) return null;
        try {
            return new Place(name, "", Double.parseDouble(p.getString("latitude", "")),
                    Double.parseDouble(p.getString("longitude", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void setPlace(Context context, Place place) {
        last = null;
        prefs(context).edit().putString("name", place.name)
                .putString("latitude", String.valueOf(place.latitude))
                .putString("longitude", String.valueOf(place.longitude)).apply();
    }

    static void turnOff(Context context) {
        last = null;
        prefs(context).edit().remove("name").remove("latitude").remove("longitude").apply();
    }

    static boolean fahrenheit(Context context) {
        return prefs(context).getBoolean("fahrenheit", false);
    }

    static void setFahrenheit(Context context, boolean fahrenheit) {
        prefs(context).edit().putBoolean("fahrenheit", fahrenheit).apply();
    }

    /** The reading to show now, or null when there is none or it has gone stale. */
    static Reading current() {
        return last != null && fresh(last.time, SystemClock.elapsedRealtime(), STALE_MS) ? last : null;
    }

    static boolean fresh(long time, long now, long limit) {
        return now >= time && now - time < limit;
    }

    /** Reads the weather when it is due and calls done on the main thread. A failed read keeps the last one. */
    static void refresh(Context context, Runnable done) {
        Place place = place(context);
        if (place == null || fetching) return;
        if (last != null && fresh(last.time, SystemClock.elapsedRealtime(), REFRESH_MS)) return;
        fetching = true;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Reading reading = null;
            try {
                reading = read(place);
            } catch (IOException | JSONException | RuntimeException ignored) {
            }
            Reading result = reading;
            main.post(() -> {
                fetching = false;
                // The city may have changed during the read; only a reading for the current one counts.
                Place now = place(context);
                if (result == null || now == null || now.latitude != place.latitude || now.longitude != place.longitude) return;
                last = result;
                done.run();
            });
        }, "weather").start();
    }

    private static Reading read(Place place) throws IOException, JSONException {
        JSONObject reply = get(String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=1", place.latitude, place.longitude));
        JSONObject current = reply.getJSONObject("current");
        JSONObject daily = reply.getJSONObject("daily");
        return new Reading(current.getDouble("temperature_2m"), daily.getJSONArray("temperature_2m_max").getDouble(0),
                daily.getJSONArray("temperature_2m_min").getDouble(0), current.getInt("weather_code"),
                current.getInt("is_day") == 1, SystemClock.elapsedRealtime());
    }

    /** Up to five places matching what was typed, best match first. Throws when the service can't be reached. */
    static List<Place> search(String name) throws IOException, JSONException {
        JSONObject reply = get("https://geocoding-api.open-meteo.com/v1/search?count=5&format=json&name="
                + URLEncoder.encode(name.trim(), "UTF-8") + "&language=" + Locale.getDefault().getLanguage());
        List<Place> places = new ArrayList<>();
        JSONArray results = reply.optJSONArray("results");
        if (results == null) return places;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String area = r.optString("admin1");
            String country = r.optString("country");
            String detail = area + (!area.isEmpty() && !country.isEmpty() ? ", " : "") + country;
            places.add(new Place(r.getString("name"), detail, r.getDouble("latitude"), r.getDouble("longitude")));
        }
        return places;
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("Weather reply too large");
                bytes.write(buffer, 0, count);
            }
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    /** Whole degrees, Fahrenheit converted before rounding. */
    static int degrees(double celsius, boolean fahrenheit) {
        return (int) Math.round(fahrenheit ? celsius * 9 / 5 + 32 : celsius);
    }

    /** The WMO weather code in words. */
    static int condition(int code, boolean day) {
        switch (code) {
            case 0: return day ? R.string.weather_sunny : R.string.weather_clear;
            case 1: return day ? R.string.weather_mostly_sunny : R.string.weather_mostly_clear;
            case 2: return R.string.weather_partly_cloudy;
            case 3: return R.string.weather_cloudy;
            case 45: case 48: return R.string.weather_fog;
            case 51: case 53: case 55: return R.string.weather_drizzle;
            case 56: case 57: case 66: case 67: return R.string.weather_freezing_rain;
            case 61: case 80: return R.string.weather_light_rain;
            case 63: case 81: return R.string.weather_rain;
            case 65: case 82: return R.string.weather_heavy_rain;
            case 71: case 77: case 85: return R.string.weather_light_snow;
            case 73: return R.string.weather_snow;
            case 75: case 86: return R.string.weather_heavy_snow;
            case 95: case 96: case 99: return R.string.weather_thunderstorm;
            default: return R.string.weather_unknown;
        }
    }
}
