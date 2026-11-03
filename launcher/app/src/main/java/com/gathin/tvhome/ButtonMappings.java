package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.IntConsumer;

/**
 * Remote buttons set on the TV in Launcher settings. They join app_keys (Config.load), so InputKeyService opens them
 * the same way as a list in config.xml, and they win over it for the same button.
 */
final class ButtonMappings {
    static final String PREFS = "remote_buttons";
    private static final String TAP = "tap:";
    private static final String HOLD = "hold:";

    private static IntConsumer capture;
    private static int swallowUp;

    private ButtonMappings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<String> entries(Context context) {
        return entries(prefs(context).getAll());
    }

    /** Stored as "tap:190" or "hold:190" to a package; app_keys spells them "190:package" and "hold:190:package". */
    static List<String> entries(Map<String, ?> stored) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, ?> entry : stored.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(TAP)) entries.add(key.substring(TAP.length()) + ":" + entry.getValue());
            else if (key.startsWith(HOLD)) entries.add(key + ":" + entry.getValue());
        }
        return entries;
    }

    /** The mapped buttons in keycode order. */
    static List<Integer> buttons(Context context) {
        TreeSet<Integer> codes = new TreeSet<>();
        for (String key : prefs(context).getAll().keySet()) {
            try {
                codes.add(Integer.parseInt(key.substring(key.indexOf(':') + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return new ArrayList<>(codes);
    }

    static String get(Context context, int code, boolean hold) {
        return prefs(context).getString((hold ? HOLD : TAP) + code, null);
    }

    /** A null package clears that action. */
    static void set(Context context, int code, boolean hold, String pkg) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (pkg == null) editor.remove((hold ? HOLD : TAP) + code);
        else editor.putString((hold ? HOLD : TAP) + code, pkg);
        editor.apply();
    }

    static void remove(Context context, int code) {
        prefs(context).edit().remove(TAP + code).remove(HOLD + code).apply();
    }

    /** The button's name without the KEYCODE_ prefix, and its number for the ones Android has no name for. */
    static String name(int code) {
        String name = KeyEvent.keyCodeToString(code);
        return name.startsWith("KEYCODE_") ? name.substring("KEYCODE_".length()) : "#" + name;
    }

    /** Never captured: these keep the remote able to move around, leave the capture and get Home. */
    static boolean navigation(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_POWER:
                return true;
            default:
                return false;
        }
    }

    /** The next button pressed goes to listener; null stops listening. On the main thread, as InputKeyService is. */
    static void capture(IntConsumer listener) {
        capture = listener;
    }

    /** For InputKeyService: true when this press was captured, or is the release of one that was. */
    static boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && code == swallowUp) {
            swallowUp = 0;
            return true;
        }
        if (capture == null || navigation(code) || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        IntConsumer listener = capture;
        capture = null;
        swallowUp = code;
        listener.accept(code);
        return true;
    }
}
