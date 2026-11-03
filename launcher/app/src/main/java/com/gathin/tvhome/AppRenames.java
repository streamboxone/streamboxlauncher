package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** Tile names chosen on the TV from the tile menu. Kept apart from Config's settings, so a theme reset leaves them. */
final class AppRenames {
    static final int MAX_LENGTH = 40;
    private static final String PREFS = "app_renames";

    private AppRenames() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The owner's name for pkg, or null when it has none. */
    static String get(Context context, String pkg) {
        return prefs(context).getString(pkg, null);
    }

    /** The name to show: the owner's when there is one, otherwise the given label. */
    static String label(Context context, String pkg, String label) {
        String custom = get(context, pkg);
        return custom != null ? custom : label;
    }

    /** Saves the name; a blank one clears it, so the tile goes back to the app's own name. */
    static void set(Context context, String pkg, String name) {
        String clean = normalize(name);
        SharedPreferences.Editor editor = prefs(context).edit();
        if (clean.isEmpty()) editor.remove(pkg);
        else editor.putString(pkg, clean);
        editor.apply();
    }

    static String normalize(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH).trim() : trimmed;
    }
}
