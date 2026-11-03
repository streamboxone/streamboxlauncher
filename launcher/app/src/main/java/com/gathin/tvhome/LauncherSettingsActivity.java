package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private static final int PICK_WALLPAPER = 41;
    private static final int PICK_BACKUP = 42;
    private TextView wallpaperChoice;
    private TextView weatherChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        findViewById(R.id.remoteButtonsChoice).setOnClickListener(v -> showRemoteButtons());
        findViewById(R.id.categoriesChoice).setOnClickListener(v -> showCategories());
        findViewById(R.id.backupChoice).setOnClickListener(v -> showBackup());
        weatherChoice = findViewById(R.id.weatherChoice);
        weatherChoice.setOnClickListener(v -> showWeatherSettings());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        List<File> added = CustomWallpapers.only(images);
        int removeRow = added.isEmpty() ? -1 : images.size() + 1;
        String[] labels = new String[images.size() + (added.isEmpty() ? 1 : 2)];
        for (int i = 0; i < images.size(); i++) labels[i] = wallpaperLabel(images.get(i), added);
        labels[images.size()] = getString(R.string.wallpaper_none);
        if (removeRow >= 0) labels[removeRow] = getString(R.string.wallpaper_remove);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == removeRow) {
                        showWallpaperRemover(added);
                        return;
                    }
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.wallpaper_add, (dialog, which) -> pickWallpaper())
                .show();
    }

    private String wallpaperLabel(File image, List<File> added) {
        int at = added.indexOf(image);
        return at >= 0 ? getString(R.string.wallpaper_custom, at + 1) : image.getName();
    }

    /** A file manager hands over the picture and can reach a USB drive; with several, the owner chooses which. */
    private void pickWallpaper() {
        pickFile("image/*", PICK_WALLPAPER, R.string.wallpaper_pick_with);
    }

    private void pickFile(String type, int request, int chooseWith) {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> pickers = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(pick, 0)) {
            if (CustomWallpapers.usablePicker(r.activityInfo.packageName)) pickers.add(r);
        }
        if (pickers.size() < 2) {
            if (pickers.size() == 1) pick.setClassName(pickers.get(0).activityInfo.packageName, pickers.get(0).activityInfo.name);
            startPicker(pick, request);
            return;
        }
        String[] labels = new String[pickers.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = pickers.get(i).loadLabel(pm).toString();
        new AlertDialog.Builder(this)
                .setTitle(chooseWith)
                .setItems(labels, (dialog, which) -> {
                    ResolveInfo r = pickers.get(which);
                    startPicker(pick.setClassName(r.activityInfo.packageName, r.activityInfo.name), request);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startPicker(Intent pick, int request) {
        try {
            startActivityForResult(pick, request);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.wallpaper_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        if (requestCode == PICK_BACKUP) {
            confirmRestore(uri);
            return;
        }
        if (requestCode != PICK_WALLPAPER) return;
        new Thread(() -> {
            File added = CustomWallpapers.add(this, uri);
            runOnUiThread(() -> {
                if (added == null) {
                    Toast.makeText(this, R.string.wallpaper_add_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, added.getName()).apply();
                changed();
                Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                refresh();
            });
        }, "wallpaper-add").start();
    }

    private void showWallpaperRemover(List<File> added) {
        String[] labels = new String[added.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = wallpaperLabel(added.get(i), added);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_remove)
                .setItems(labels, (dialog, which) -> {
                    File image = added.get(which);
                    if (image.getName().equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null))) {
                        choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    }
                    image.delete();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One file with the layout and settings, to keep or to set up another box. */
    private void showBackup() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_label)
                .setItems(new String[]{getString(R.string.backup_save), getString(R.string.backup_restore)}, (dialog, which) -> {
                    if (which == 0) saveBackup();
                    else pickFile("*/*", PICK_BACKUP, R.string.backup_pick_with);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveBackup() {
        new Thread(() -> {
            String saved = SettingsBackup.save(this);
            runOnUiThread(() -> Toast.makeText(this, saved == null ? getString(R.string.backup_save_failed)
                    : getString(R.string.backup_saved, saved), Toast.LENGTH_LONG).show());
        }, "backup-save").start();
    }

    private void confirmRestore(android.net.Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_restore)
                .setMessage(R.string.backup_restore_confirm)
                .setPositiveButton(R.string.backup_restore_go, (dialog, which) -> new Thread(() -> {
                    boolean restored = SettingsBackup.restore(this, uri);
                    runOnUiThread(() -> {
                        Toast.makeText(this, restored ? R.string.backup_restored : R.string.backup_restore_failed,
                                Toast.LENGTH_LONG).show();
                        if (!restored) return;
                        // Home rebuilds with what came in; this page's rows were read before it did.
                        setResult(RESULT_OK);
                        finish();
                    });
                }, "backup-restore").start())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The sections on Home; apps go into them from the tile menu. */
    private void showCategories() {
        List<String> names = AppCategories.names(this);
        String[] rows = new String[names.size() + 1];
        for (int i = 0; i < names.size(); i++) rows[i] = names.get(i);
        rows[names.size()] = getString(R.string.category_new);
        new AlertDialog.Builder(this)
                .setTitle(R.string.categories_label)
                .setItems(rows, (dialog, which) -> {
                    if (which < names.size()) showCategory(names.get(which));
                    else AppCategories.askName(this, R.string.category_new, getString(R.string.category_hint), name -> {
                        if (AppCategories.add(this, name)) changed();
                        else if (!AppCategories.normalize(name).isEmpty()) Toast.makeText(this, R.string.category_taken, Toast.LENGTH_LONG).show();
                    }, this::showCategories);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCategory(String name) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{getString(R.string.tile_rename), getString(R.string.category_move_up),
                        getString(R.string.category_move_down), getString(R.string.category_delete)}, (dialog, which) -> {
                    if (which == 0) {
                        AppCategories.askName(this, R.string.tile_rename, name, to -> {
                            if (AppCategories.rename(this, name, to)) changed();
                            else if (!AppCategories.normalize(to).isEmpty()) Toast.makeText(this, R.string.category_taken, Toast.LENGTH_LONG).show();
                        }, this::showCategories);
                    } else if (which == 3) {
                        new AlertDialog.Builder(this)
                                .setTitle(getString(R.string.category_delete_confirm, name))
                                .setPositiveButton(R.string.category_delete, (d, w) -> {
                                    AppCategories.remove(this, name);
                                    changed();
                                    showCategories();
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    } else {
                        AppCategories.move(this, name, which == 1 ? -1 : 1);
                        changed();
                        showCategories();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String weatherUnit() {
        return getString(Weather.fahrenheit(this) ? R.string.weather_fahrenheit : R.string.weather_celsius);
    }

    /** City and units; Turn off stops the launcher asking the weather service anything. */
    private void showWeatherSettings() {
        Weather.Place place = Weather.place(this);
        String[] rows = new String[place == null ? 2 : 3];
        rows[0] = place == null ? getString(R.string.weather_set_city) : getString(R.string.weather_city, place.name);
        rows[1] = getString(R.string.weather_units, weatherUnit());
        if (place != null) rows[2] = getString(R.string.weather_turn_off);
        new AlertDialog.Builder(this)
                .setTitle(R.string.weather_label)
                .setItems(rows, (dialog, which) -> {
                    if (which == 0) {
                        askWeatherCity();
                    } else if (which == 1) {
                        Weather.setFahrenheit(this, !Weather.fahrenheit(this));
                        changed();
                        refresh();
                        showWeatherSettings();
                    } else {
                        Weather.turnOff(this);
                        changed();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askWeatherCity() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.weather_city_hint);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        Runnable search = () -> {
            String name = String.valueOf(input.getText()).trim();
            if (!name.isEmpty()) searchWeatherCity(name);
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.weather_set_city)
                .setView(box)
                .setPositiveButton(android.R.string.search_go, (d, which) -> search.run())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            dialog.dismiss();
            search.run();
            return true;
        });
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        input.requestFocus();
    }

    private void searchWeatherCity(String name) {
        new Thread(() -> {
            List<Weather.Place> found;
            try {
                found = Weather.search(name);
            } catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
                found = null;
            }
            List<Weather.Place> places = found;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (places == null) {
                    Toast.makeText(this, R.string.weather_search_failed, Toast.LENGTH_LONG).show();
                } else if (places.isEmpty()) {
                    Toast.makeText(this, R.string.weather_no_match, Toast.LENGTH_LONG).show();
                } else {
                    pickWeatherCity(places);
                }
            });
        }, "weather-search").start();
    }

    private void pickWeatherCity(List<Weather.Place> places) {
        String[] labels = new String[places.size()];
        for (int i = 0; i < labels.length; i++) {
            Weather.Place place = places.get(i);
            labels[i] = place.detail.isEmpty() ? place.name : place.name + " (" + place.detail + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.weather_set_city)
                .setItems(labels, (dialog, which) -> {
                    Weather.setPlace(this, places.get(which));
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Add a button, then the ones already set. Works through InputKeyService, so it says when that helper is off. */
    private void showRemoteButtons() {
        if (!keyHelperOn()) {
            Toast.makeText(this, R.string.remote_buttons_helper_off, Toast.LENGTH_LONG).show();
            return;
        }
        List<Integer> buttons = ButtonMappings.buttons(this);
        String[] rows = new String[buttons.size() + 1];
        rows[0] = getString(R.string.remote_buttons_add);
        for (int i = 0; i < buttons.size(); i++) rows[i + 1] = buttonSummary(buttons.get(i));
        new AlertDialog.Builder(this)
                .setTitle(R.string.remote_buttons)
                .setItems(rows, (dialog, which) -> {
                    if (which == 0) captureButton();
                    else showButton(buttons.get(which - 1));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean keyHelperOn() {
        String on = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(getPackageName() + "/") && on.contains("InputKeyService");
    }

    private String buttonSummary(int code) {
        return getString(R.string.remote_button_summary, ButtonMappings.name(code),
                actionLabel(ButtonMappings.get(this, code, false)), actionLabel(ButtonMappings.get(this, code, true)));
    }

    private String actionLabel(String pkg) {
        if (pkg == null) return getString(R.string.remote_button_unset);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void captureButton() {
        AlertDialog prompt = new AlertDialog.Builder(this)
                .setTitle(R.string.remote_buttons_add)
                .setMessage(R.string.remote_buttons_press)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        prompt.setOnDismissListener(d -> ButtonMappings.capture(null));
        ButtonMappings.capture(code -> {
            prompt.dismiss();
            showButton(code);
        });
        prompt.show();
    }

    private void showButton(int code) {
        String[] rows = {
                getString(R.string.remote_button_tap, actionLabel(ButtonMappings.get(this, code, false))),
                getString(R.string.remote_button_hold, actionLabel(ButtonMappings.get(this, code, true))),
                getString(R.string.remote_button_remove)};
        new AlertDialog.Builder(this)
                .setTitle(ButtonMappings.name(code))
                .setItems(rows, (dialog, which) -> {
                    if (which == 2) {
                        ButtonMappings.remove(this, code);
                        showRemoteButtons();
                    } else {
                        pickButtonApp(code, which == 1);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickButtonApp(int code, boolean hold) {
        List<String[]> apps = launchableApps();
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.remote_button_unset);
        for (int i = 0; i < apps.size(); i++) labels[i + 1] = apps.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle(hold ? R.string.remote_button_hold_title : R.string.remote_button_tap_title)
                .setItems(labels, (dialog, which) -> {
                    ButtonMappings.set(this, code, hold, which == 0 ? null : apps.get(which - 1)[1]);
                    showButton(code);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** {label, package} for every app with a TV entry point, by name. */
    private List<String[]> launchableApps() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return apps;
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        List<String[]> apps = launchableApps();
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        Weather.Place weatherPlace = Weather.place(this);
        weatherChoice.setText(weatherPlace == null ? getString(R.string.weather_off)
                : getString(R.string.weather_state, weatherPlace.name, weatherUnit()));
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : CustomWallpapers.isCustom(wallpaperCurrent) ? getString(R.string.wallpaper_custom_name)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
