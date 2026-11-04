package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The owner's labelled sections on Home, chosen on the TV. Each section is whole rows of the grid (a heading row, then
 * tiles, the last row padded out), so a position still divides by the columns into its row and column.
 */
final class AppCategories {
    static final int MAX_LENGTH = 24;
    private static final String PREFS = "app_categories";
    private static final String NAMES = "names";
    private static final String APP = "app.";

    private AppCategories() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The categories in the order Home shows them. */
    static List<String> names(Context context) {
        String joined = prefs(context).getString(NAMES, "");
        return joined.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(joined.split("\n")));
    }

    private static void saveNames(SharedPreferences.Editor editor, List<String> names) {
        editor.putString(NAMES, String.join("\n", names));
    }

    /** The app's category, or null when it has none. */
    static String of(Context context, String pkg) {
        return prefs(context).getString(APP + pkg, null);
    }

    /** Puts the app in this category; null takes it out of its category. */
    static void assign(Context context, String pkg, String category) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (category == null) editor.remove(APP + pkg);
        else editor.putString(APP + pkg, category);
        editor.apply();
    }

    /** Adds a category at the end. False when the name is blank or already taken. */
    static boolean add(Context context, String name) {
        String clean = normalize(name);
        List<String> names = names(context);
        if (clean.isEmpty() || taken(names, clean, null)) return false;
        names.add(clean);
        SharedPreferences.Editor editor = prefs(context).edit();
        saveNames(editor, names);
        editor.apply();
        return true;
    }

    /** Renames a category and keeps its apps. False when the new name is blank or belongs to another category. */
    static boolean rename(Context context, String from, String to) {
        String clean = normalize(to);
        List<String> names = names(context);
        int at = names.indexOf(from);
        if (at < 0 || clean.isEmpty() || taken(names, clean, from)) return false;
        names.set(at, clean);
        SharedPreferences.Editor editor = prefs(context).edit();
        saveNames(editor, names);
        for (Map.Entry<String, ?> e : prefs(context).getAll().entrySet()) {
            if (e.getKey().startsWith(APP) && from.equals(e.getValue())) editor.putString(e.getKey(), clean);
        }
        editor.apply();
        return true;
    }

    /** Deletes a category; its apps go back to having none. */
    static void remove(Context context, String name) {
        List<String> names = names(context);
        names.remove(name);
        SharedPreferences.Editor editor = prefs(context).edit();
        saveNames(editor, names);
        for (Map.Entry<String, ?> e : prefs(context).getAll().entrySet()) {
            if (e.getKey().startsWith(APP) && name.equals(e.getValue())) editor.remove(e.getKey());
        }
        editor.apply();
    }

    /** Moves a category one place up (-1) or down (1); nothing happens at either end. */
    static void move(Context context, String name, int step) {
        SharedPreferences.Editor editor = prefs(context).edit();
        saveNames(editor, moved(names(context), name, step));
        editor.apply();
    }

    static List<String> moved(List<String> names, String name, int step) {
        List<String> result = new ArrayList<>(names);
        int from = result.indexOf(name), to = from + step;
        if (from >= 0 && to >= 0 && to < result.size()) result.add(to, result.remove(from));
        return result;
    }

    /** One line (the names are stored a line each), trimmed to MAX_LENGTH. */
    static String normalize(String name) {
        if (name == null) return "";
        String clean = name.trim().replaceAll("\\s+", " ");
        return clean.length() > MAX_LENGTH ? clean.substring(0, MAX_LENGTH).trim() : clean;
    }

    /** Whether another category has this name, ignoring case; {@code except} may keep its own in new capitals. */
    static boolean taken(List<String> names, String name, String except) {
        for (String n : names) if (!n.equals(except) && n.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Asks for a category name on the keyboard; done gets what was typed, and is not called on Cancel. */
    static void askName(Context context, int title, String hint, Consumer<String> done, Runnable dismissed) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_LENGTH)});
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setHint(hint);
        TvDialog dialog = new TvDialog.Builder(context)
                .setTitle(title)
                .setInput(input)
                .setPositiveButton(android.R.string.ok, (d, which) -> done.accept(String.valueOf(input.getText())))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            done.accept(String.valueOf(input.getText()));
            dialog.dismiss();
            return true;
        });
        if (dismissed != null) dialog.setOnDismissListener(d -> dismissed.run());
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        input.requestFocus();
    }

    /** One place in the grid: a tile (its index in the list given to arrange), a heading, or a blank. */
    static final class Slot {
        final int tile;
        final String title;

        Slot(int tile, String title) {
            this.tile = tile;
            this.title = title;
        }
    }

    /**
     * The tiles as sections, in the order of {@code names}, each tile's order kept. Tiles with no category (or one that
     * no longer exists) go last, under {@code otherTitle}. An empty category is left out. Null when no tile is in a
     * category: then there is nothing to divide and Home has no headings.
     */
    static List<Slot> arrange(List<String> categoryOfTile, List<String> names, int columns, String otherTitle) {
        List<List<Integer>> groups = new ArrayList<>();
        for (int i = 0; i <= names.size(); i++) groups.add(new ArrayList<>());
        boolean any = false;
        for (int i = 0; i < categoryOfTile.size(); i++) {
            int group = names.indexOf(categoryOfTile.get(i));
            any |= group >= 0;
            groups.get(group >= 0 ? group : names.size()).add(i);
        }
        if (!any) return null;
        int last = names.size();
        while (groups.get(last).isEmpty()) last--;
        List<Slot> slots = new ArrayList<>();
        for (int g = 0; g <= last; g++) {
            List<Integer> tiles = groups.get(g);
            if (tiles.isEmpty()) continue;
            slots.add(new Slot(-1, g < names.size() ? names.get(g) : otherTitle));
            for (int i = 1; i < columns; i++) slots.add(new Slot(-1, ""));
            for (int tile : tiles) slots.add(new Slot(tile, null));
            // The last row stays short, as it always has; only a row with a heading after it needs filling.
            if (g < last) for (int i = tiles.size(); i % columns != 0; i++) slots.add(new Slot(-1, ""));
        }
        return slots;
    }

    /**
     * Where Up or Down goes from a tile: the nearest row with a tile in it, in the same column or else the closest one
     * to it. -1 when there is no such row.
     */
    static int vertical(boolean[] isTile, int columns, int position, boolean down) {
        int column = position % columns;
        for (int row = position / columns + (down ? 1 : -1); row >= 0 && row * columns < isTile.length; row += down ? 1 : -1) {
            for (int c = column; c >= 0; c--) {
                int at = row * columns + c;
                if (at < isTile.length && isTile[at]) return at;
            }
            for (int c = column + 1; c < columns; c++) {
                int at = row * columns + c;
                if (at < isTile.length && isTile[at]) return at;
            }
        }
        return -1;
    }
}
