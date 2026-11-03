package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The announcement file at announcement_url (config.xml). It is plain text: shown once its contents differ from what
 * the owner last dismissed. Checked when Home comes up, at most every few hours, and never polled in the background.
 */
final class Announcements {
    static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_CHARS = 4000;
    private static final int TIMEOUT_MS = 10_000;
    private static final String PREFS = "announcement";
    private static final String DISMISSED = "dismissed";

    /** The last check that got an answer, by SystemClock.elapsedRealtime; 0 before one has. */
    private static long lastCheck;
    private static boolean checking;

    private Announcements() { }

    /**
     * Fetches the file when a check is due and hands an unread announcement to show, on the main thread. A check that
     * gets no answer doesn't count, so the next return to Home or the network coming back tries again.
     */
    static void check(Context context, String url, Consumer<String> show) {
        if (url.isEmpty() || checking || !due(lastCheck, SystemClock.elapsedRealtime(), CHECK_INTERVAL_MS)) return;
        checking = true;
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String fetched = fetch(url);
            String text = fetched == null ? null : clean(fetched);
            main.post(() -> {
                checking = false;
                if (text == null) return;
                lastCheck = SystemClock.elapsedRealtime();
                if (unread(text, app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DISMISSED, null))) {
                    show.accept(text);
                }
            });
        }, "announcement").start();
    }

    /** Home could not show it just now: look again the next time Home comes up. */
    static void checkAgain() {
        lastCheck = 0;
    }

    static void dismissed(Context context, String text) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DISMISSED, fingerprint(text)).apply();
    }

    static boolean due(long last, long now, long interval) {
        return last == 0 || now < last || now - last >= interval;
    }

    static boolean unread(String text, String dismissed) {
        return !text.isEmpty() && !fingerprint(text).equals(dismissed);
    }

    /** Line ends and the outer space don't count as a change; control characters and any excess length are dropped. */
    static String clean(String raw) {
        StringBuilder text = new StringBuilder(Math.min(raw.length(), MAX_CHARS));
        for (int i = 0; i < raw.length() && text.length() < MAX_CHARS; i++) {
            char c = raw.charAt(i);
            int type = Character.getType(c);
            if (c == '\n' || c == '\t' || (type != Character.CONTROL && type != Character.FORMAT
                    && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR)) {
                text.append(c);
            }
        }
        return text.toString().trim();
    }

    static String fingerprint(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Android has SHA-256
        }
    }

    /** True for what the launcher will read: HTTPS only, and a website's HTML error page never shows as news. */
    static boolean acceptable(String url, int code, String contentType) {
        return url.toLowerCase(Locale.ROOT).startsWith("https://") && code == HttpURLConnection.HTTP_OK
                && contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/plain");
    }

    /** The file's text, or null when there is no answer or not an acceptable one. */
    private static String fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            if (!acceptable(connection.getURL().toString(), connection.getResponseCode(), connection.getContentType())) return null;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while (bytes.size() < MAX_BYTES && (count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
