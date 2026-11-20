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
    private static final String HELPER_ASKED = "helper_asked";
    private static final String NO_QUESTION = "load_without_asking";
    private static final String SWITCHING = "switch_pending";

    private LayoutOffer() { }

    static boolean due(Context context, String address) {
        if (address.isEmpty() || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)) return false;
        return fresh(context);
    }

    /**
     * first_run_helper_offer: after the layout question (or with none), a fresh install asks once to turn on the Home
     * button helper when it is off. Android lets only the person turn on an accessibility service, in Settings.
     */
    static boolean helperDue(Context context, String layoutAddress) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(HELPER_ASKED, false)) return false;
        if (!layoutAddress.isEmpty() && !prefs.getBoolean(ASKED, false)) return false;
        return !helperOn(context) && fresh(context);
    }

    static void helperAsked(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(HELPER_ASKED, true).apply();
    }

    static boolean helperOn(Context context) {
        String on = android.provider.Settings.Secure.getString(context.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(context.getPackageName() + "/" + HomeKeyService.class.getName());
    }

    private static boolean fresh(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.firstInstallTime == info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * A switch from StreamBox Updates is under way: the person already chose StreamBox, so the layout loads without the
     * question. Set before Android's Home question, because Android opens the new Home before the answer comes back.
     */
    static void switching(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(NO_QUESTION, on).putBoolean(SWITCHING, on).commit();
    }

    /** True once, on the first Home during a switch: StreamBox Updates still has the old launchers to remove. */
    static boolean takeSwitch(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SWITCHING, false)) return false;
        prefs.edit().putBoolean(SWITCHING, false).apply();
        return true;
    }

    static void switchDone(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SWITCHING, false).apply();
    }

    static boolean withoutAsking(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(NO_QUESTION, false);
    }

    static void asked(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply();
    }
}
