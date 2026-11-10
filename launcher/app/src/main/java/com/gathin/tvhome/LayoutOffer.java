package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * first_run_layout_url: on the first Home after a fresh install, ask once whether to load the layout at that address.
 * An install that was updated never asks, so a box already set up keeps its layout. The answer is kept in its own
 * preferences file, outside the backup, so a restore can't bring the question back.
 */
final class LayoutOffer {
    private static final String PREFS = "layout_offer";
    private static final String ASKED = "asked";

    private LayoutOffer() { }

    static boolean due(Context context, String address) {
        if (address.isEmpty() || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)) return false;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.firstInstallTime == info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static void asked(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply();
    }
}
