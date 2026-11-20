package com.gathin.tvhome;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Asks Android, with its own one-tap question, to make this launcher Home, for an updater that is switching a box
 * over (StreamBox Updates opens it with startActivityForResult). The result is OK only when this launcher is Home
 * afterwards; RESULT_FIRST_USER means this Android has no such question, and the caller falls back to the Home picker.
 * A launcher made Home this way loads first_run_layout_url without asking, since the person chose to switch.
 * Android opens the new Home as soon as it is chosen, before this gets the answer, so MainActivity's first Home hands
 * the screen back to the updater (LayoutOffer.takeSwitch) to finish removing the old launchers.
 */
public class HomeRequestActivity extends Activity {
    static final String ACTION = "com.gathin.tvhome.action.REQUEST_HOME";
    private static final int ASK = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) return;   // the question is already up
        if (Build.VERSION.SDK_INT < 29) {
            unsupported();
            return;
        }
        RoleManager roles = getSystemService(RoleManager.class);
        if (roles == null || !roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
            unsupported();
        } else if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            done(true);
        } else {
            try {
                LayoutOffer.switching(this, true);
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), ASK);
            } catch (RuntimeException e) {
                LayoutOffer.switching(this, false);
                unsupported();
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != ASK || Build.VERSION.SDK_INT < 29) return;
        RoleManager roles = getSystemService(RoleManager.class);
        done(roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME));
    }

    private void done(boolean home) {
        if (home) LayoutOffer.switchDone(this);
        else LayoutOffer.switching(this, false);
        setResult(home ? RESULT_OK : RESULT_CANCELED);
        finish();
    }

    private void unsupported() {
        setResult(RESULT_FIRST_USER);
        finish();
    }
}
