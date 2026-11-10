package ca.techshop.streamboxupdates;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the text file of apps and offers what is newer here or not on the box yet. One app is handled at a time: it is
 * downloaded and checked first, then any uninstall, then the install, so a bad download never costs the app in place.
 *
 * Android decides what can go without a prompt. An update of an app this one installed goes through silently on
 * Android 12 and newer; a first install, and every uninstall, shows Android's own confirmation unless this app is the
 * device owner. Whichever applies, the session is the same and Android answers with PENDING_USER_ACTION when it wants to ask.
 */
public final class UpdatesActivity extends Activity {
    private static final String ACTION_DONE = "ca.techshop.streamboxupdates.DONE";
    private static final int MAX_FILE_BYTES = 1 << 20;
    private static final int TIMEOUT_MS = 20_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private final List<Item> items = new ArrayList<>();
    private LinearLayout rows;
    private TextView status;
    private TextView updateAll;
    private Item working;
    private File download;
    /** The APKs inside download when it is a split bundle (XAPK); empty for a plain APK. */
    private List<String> downloadApks = new ArrayList<>();
    private boolean uninstalling;
    private int nextRequest;

    private static final class Item {
        final Catalog.Entry entry;
        long installed;
        TextView action;

        Item(Catalog.Entry entry) {
            this.entry = entry;
        }
    }

    private final BroadcastReceiver finished = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            if (removingOld != null) {
                removedOld(code == PackageInstaller.STATUS_SUCCESS);
                return;
            }
            if (removingApp != null) {
                removedApp(code == PackageInstaller.STATUS_SUCCESS);
                return;
            }
            if (working == null) return;
            if (uninstalling) uninstalled(code == PackageInstaller.STATUS_SUCCESS);
            else installed(code, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")   // only below Android 13, which has no such flag
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_updates);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        rows = findViewById(R.id.rows);
        status = findViewById(R.id.status);
        updateAll = findViewById(R.id.updateAll);
        updateAll.setOnClickListener(v -> updateEverything());
        findViewById(R.id.close).setOnClickListener(v -> onBackPressed());
        removeToggle = findViewById(R.id.removeMode);
        removeToggle.setOnClickListener(v -> toggleRemoveMode());
        findViewById(R.id.storage).setOnClickListener(v -> openStorage());
        findViewById(R.id.close).requestFocus();
        IntentFilter filter = new IntentFilter(ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(finished, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(finished, filter);
        showStorage();
        load();
    }

    /** Back from Settings or one of Android's prompts: what is installed, and what is allowed, may have changed. */
    @Override
    protected void onRestart() {
        super.onRestart();
        showStorage();
        View storage = findViewById(R.id.storage);
        boolean onStorage = storage.hasFocus();
        if (working == null && !items.isEmpty()) rebuild();
        if (onStorage) storage.requestFocus();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(finished);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** While an app is on its way, Back only cancels the ones after it: a half-done install has to report in first. */
    @Override
    public void onBackPressed() {
        if (removeMode && working == null && removingApp == null) {
            toggleRemoveMode();
            return;
        }
        if (working == null) {
            finish();
            return;
        }
        queue.clear();
        status.setText(R.string.finishing);
    }

    private void showStorage() {
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long total = stat.getTotalBytes();
        long free = stat.getAvailableBytes();
        ((TextView) findViewById(R.id.storageText)).setText(getString(R.string.storage, Catalog.size(free), Catalog.size(total)));
        ((ProgressBar) findViewById(R.id.storageBar)).setProgress(total == 0 ? 0 : (int) ((total - free) * 100 / total));
    }

    /** Not during an install: it may still need to put Android's prompt on screen. */
    private void openStorage() {
        if (working != null) return;
        try {
            startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void load() {
        status.setText(R.string.checking);
        String url = getString(R.string.updates_url);
        new Thread(() -> {
            String text = null;
            try {
                text = fetchText(url);
            } catch (IOException e) {
                // said below
            }
            String loaded = text;
            main.post(() -> {
                if (isDestroyed()) return;
                if (loaded == null) status.setText(getString(R.string.load_failed, url));
                else show(Catalog.parse(loaded, url));
            });
        }, "updates-file").start();
    }

    private static String fetchText(String address) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
        http.setConnectTimeout(TIMEOUT_MS);
        http.setReadTimeout(TIMEOUT_MS);
        http.setUseCaches(false);
        try {
            if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1; ) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_FILE_BYTES) throw new IOException("file too large");
                }
                return out.toString("UTF-8");
            }
        } finally {
            http.disconnect();
        }
    }

    private void show(Catalog catalog) {
        items.clear();
        for (Catalog.Entry entry : catalog.entries) items.add(new Item(entry));
        badLines = catalog.badLines;
        removals.clear();
        removals.addAll(catalog.removals);
        rebuild();
        offerSwitch();
    }

    // Switching from an older launcher to StreamBox. The old ones are removed only once Android says StreamBox is
    // Home, so the box is never left without one.

    static final String LAUNCHER = "com.gathin.tvhome";
    static final String[] OLD_LAUNCHERS = {"com.wolf.firelauncher", "com.spocky.projengmenu"};

    /** The launcher is being installed for a switch: when the queue empties, Home is chosen next. */
    private boolean switching;
    /** Android's Home picker was opened; checked when this screen comes back. */
    private boolean awaitingHome;
    private boolean pickerLeft;
    private final ArrayDeque<String> oldToRemove = new ArrayDeque<>();
    private String removingOld;
    private final List<String> notRemoved = new ArrayList<>();

    /** The package Android opens for Home, or null when it would ask. */
    private String currentHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null) return null;
        String pkg = info.activityInfo.packageName;
        return "android".equals(pkg) ? null : pkg;
    }

    private Item launcherItem() {
        for (Item item : items) if (item.entry.pkg.equals(LAUNCHER)) return item;
        return null;
    }

    private List<String> oldInstalled() {
        List<String> found = new ArrayList<>();
        for (String pkg : OLD_LAUNCHERS) if (installedVersion(pkg) >= 0) found.add(pkg);
        return found;
    }

    private String label(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private String labels(List<String> pkgs) {
        List<String> names = new ArrayList<>();
        for (String pkg : pkgs) names.add(label(pkg));
        return String.join(getString(R.string.and), names);
    }

    /**
     * Once each time this app opens: to switch, while StreamBox isn't Home and the file has it; or, once it is Home, to
     * uninstall the old launchers still here. Android goes straight to the new Home after the picker, so that second
     * question is how a switch finishes when this app was closed in the meantime.
     */
    private void offerSwitch() {
        if (isFinishing()) return;
        List<String> old = oldInstalled();
        if (LAUNCHER.equals(currentHome())) {
            if (old.isEmpty()) return;
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.cleanup_title)
                    .setMessage(getString(R.string.cleanup_message, labels(old)))
                    .setPositiveButton(R.string.cleanup_yes, (d, w) -> removeOld())
                    .setNegativeButton(R.string.switch_no, null)
                    .show();
            return;
        }
        if (launcherItem() == null) return;
        String message = getString(R.string.switch_message);
        if (!old.isEmpty()) message += "\n\n" + getString(R.string.switch_removes, labels(old));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.switch_title)
                .setMessage(message)
                .setPositiveButton(R.string.switch_yes, (d, w) -> startSwitch())
                .setNegativeButton(R.string.switch_no, null)
                .show();
    }

    private void startSwitch() {
        if (working != null) return;
        Item launcher = launcherItem();
        if (launcher == null) return;
        if (Catalog.state(launcher.entry, launcher.installed) == Catalog.State.CURRENT) {
            chooseHome();
            return;
        }
        switching = true;
        enqueue(launcher);
        next();
    }

    /** Called when the queue empties after a switch's install. */
    private void switchInstalled() {
        switching = false;
        if (installedVersion(LAUNCHER) < 0) return;   // the install failed, and said so
        if (LAUNCHER.equals(currentHome())) removeOld();
        else chooseHome();
    }

    /** Android's own picker: an app can't make itself or another app Home. */
    private void chooseHome() {
        try {
            awaitingHome = true;
            pickerLeft = false;
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            int hint = oldInstalled().isEmpty() ? R.string.switch_choose : R.string.switch_choose_then;
            Toast.makeText(this, getString(hint, label(LAUNCHER)), Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException e) {
            awaitingHome = false;
            say(getString(R.string.switch_no_picker));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (awaitingHome) pickerLeft = true;
    }

    /** onResume, not onRestart: this screen is translucent, so the picker may only pause it. */
    @Override
    protected void onResume() {
        super.onResume();
        if (!awaitingHome || !pickerLeft) return;
        awaitingHome = false;
        if (LAUNCHER.equals(currentHome())) {
            removeOld();
        } else {
            List<String> old = oldInstalled();
            say(old.isEmpty() ? getString(R.string.switch_not_home) : getString(R.string.switch_not_home_kept, labels(old)));
        }
    }

    private void removeOld() {
        oldToRemove.clear();
        notRemoved.clear();
        oldToRemove.addAll(oldInstalled());
        removeNextOld();
    }

    private void removeNextOld() {
        removingOld = oldToRemove.poll();
        if (removingOld != null) {
            say(getString(R.string.switch_removing, label(removingOld)));
            getPackageManager().getPackageInstaller().uninstall(removingOld, answer().getIntentSender());
            return;
        }
        rebuild();
        say(notRemoved.isEmpty() ? getString(R.string.switch_done)
                : getString(R.string.switch_done_kept, labels(notRemoved)));
    }

    private void removedOld(boolean done) {
        if (!done || installedVersion(removingOld) >= 0) notRemoved.add(removingOld);
        removeNextOld();
    }

    private void say(String text) {
        status.setVisibility(View.VISIBLE);
        status.setText(text);
    }

    private int badLines;

    // Apps the file lists as no longer working. A pill flips the list to those still on this box; each one is
    // uninstalled with Android's own prompt, one at a time.

    private final List<Catalog.Removal> removals = new ArrayList<>();
    private TextView removeToggle;
    private boolean removeMode;
    private Catalog.Removal removingApp;

    private List<Catalog.Removal> removalsHere() {
        List<Catalog.Removal> here = new ArrayList<>();
        for (Catalog.Removal r : removals) if (installedVersion(r.pkg) >= 0) here.add(r);
        return here;
    }

    private void toggleRemoveMode() {
        if (working != null || removingApp != null) return;
        removeMode = !removeMode;
        rebuild();
    }

    /** Shown while there is something to remove, or to get back from the removal list. */
    private void showRemoveToggle(int here) {
        removeToggle.setVisibility(here > 0 || removeMode ? View.VISIBLE : View.GONE);
        removeToggle.setText(removeMode ? getString(R.string.remove_back)
                : getResources().getQuantityString(R.plurals.remove_apps, here, here));
    }

    private void rebuildRemovals(List<Catalog.Removal> here) {
        updateAll.setVisibility(View.GONE);
        TextView heading = (TextView) LayoutInflater.from(this).inflate(R.layout.section, rows, false);
        heading.setText(R.string.section_remove);
        rows.addView(heading);
        for (Catalog.Removal r : here) rows.addView(removalRow(r));
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.remove_help);
        (rows.getChildCount() > 1 ? rows.getChildAt(1) : removeToggle).requestFocus();
    }

    private View removalRow(Catalog.Removal r) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_app, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(r.pkg));
        } catch (PackageManager.NameNotFoundException e) {
            icon.setImageResource(R.drawable.ic_new_app);
        }
        ((TextView) row.findViewById(R.id.name)).setText(r.name);
        String version = installedName(r.pkg);
        ((TextView) row.findViewById(R.id.detail)).setText(r.note.isEmpty() ? version
                : version.isEmpty() ? r.note : getString(R.string.remove_detail, version, r.note));
        TextView action = row.findViewById(R.id.action);
        action.setText(R.string.action_uninstall);
        row.setOnClickListener(v -> {
            if (working != null || removingApp != null) return;
            removingApp = r;
            action.setText(R.string.action_removing);
            getPackageManager().getPackageInstaller().uninstall(r.pkg, answer().getIntentSender());
        });
        return row;
    }

    private void removedApp(boolean done) {
        Catalog.Removal r = removingApp;
        removingApp = null;
        boolean gone = installedVersion(r.pkg) < 0;
        if (removalsHere().isEmpty()) removeMode = false;
        showStorage();
        rebuild();
        status.setVisibility(View.VISIBLE);
        status.setText(getString(gone ? R.string.remove_done : R.string.remove_kept, r.name));
    }

    private void rebuild() {
        rows.removeAllViews();
        List<Catalog.Removal> here = removalsHere();
        if (here.isEmpty()) removeMode = false;
        showRemoveToggle(here.size());
        if (removeMode) {
            rebuildRemovals(here);
            return;
        }
        List<Item> updates = new ArrayList<>();
        List<Item> fresh = new ArrayList<>();
        int current = 0;
        for (Item item : items) {
            item.installed = installedVersion(item.entry.pkg);
            switch (Catalog.state(item.entry, item.installed)) {
                case UPDATE: updates.add(item); break;
                case NEW: fresh.add(item); break;
                default: current++;
            }
        }
        section(R.string.section_updates, updates);
        section(R.string.section_new, fresh);
        updateAll.setVisibility(updates.isEmpty() ? View.GONE : View.VISIBLE);
        StringBuilder line = new StringBuilder();
        if (updates.isEmpty() && fresh.isEmpty()) line.append(getString(R.string.all_current));
        else if (current > 0) line.append(getResources().getQuantityString(R.plurals.up_to_date, current, current));
        if (badLines > 0) {
            if (line.length() > 0) line.append("  ·  ");
            line.append(getResources().getQuantityString(R.plurals.bad_lines, badLines, badLines));
        }
        status.setText(line);
        status.setVisibility(line.length() == 0 ? View.GONE : View.VISIBLE);
        View first = !updates.isEmpty() ? updateAll : rows.getChildCount() > 1 ? rows.getChildAt(1) : findViewById(R.id.close);
        first.requestFocus();
    }

    private void section(int title, List<Item> list) {
        if (list.isEmpty()) return;
        TextView heading = (TextView) LayoutInflater.from(this).inflate(R.layout.section, rows, false);
        heading.setText(title);
        rows.addView(heading);
        for (Item item : list) rows.addView(row(item));
    }

    private View row(Item item) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_app, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        Catalog.Entry entry = item.entry;
        boolean have = item.installed >= 0;
        try {
            if (have) icon.setImageDrawable(getPackageManager().getApplicationIcon(entry.pkg));
            else icon.setImageResource(R.drawable.ic_new_app);
        } catch (PackageManager.NameNotFoundException e) {
            icon.setImageResource(R.drawable.ic_new_app);
        }
        ((TextView) row.findViewById(R.id.name)).setText(entry.name);
        TextView detail = row.findViewById(R.id.detail);
        String version = have ? getString(R.string.version_change, installedName(entry.pkg), versionOf(entry)) : versionOf(entry);
        detail.setText(have && entry.uninstallFirst ? getString(R.string.erases_data, version) : version);
        item.action = row.findViewById(R.id.action);
        item.action.setText(have ? R.string.action_update : R.string.action_install);
        row.setOnClickListener(v -> {
            if (working != null) {
                if (working != item && !queue.contains(item)) enqueue(item);
                return;
            }
            enqueue(item);
            next();
        });
        return row;
    }

    private static String versionOf(Catalog.Entry entry) {
        return entry.versionName.isEmpty() ? String.valueOf(entry.versionCode) : entry.versionName;
    }

    private void enqueue(Item item) {
        queue.add(item);
        item.action.setText(R.string.action_waiting);
    }

    /** Updates that can go silently first, so the ones Android asks about come when they are all that is left. */
    private void updateEverything() {
        if (working != null) return;
        List<Item> later = new ArrayList<>();
        for (Item item : items) {
            if (Catalog.state(item.entry, item.installed) != Catalog.State.UPDATE) continue;
            if (item.entry.uninstallFirst) later.add(item);
            else enqueue(item);
        }
        for (Item item : later) enqueue(item);
        next();
    }

    private void next() {
        working = queue.poll();
        if (working == null) {
            showStorage();
            String left = status.getText().toString();
            rebuild();
            if (failure != null) {
                status.setVisibility(View.VISIBLE);
                status.setText(failure);
                failure = null;
            } else if (left.isEmpty()) {
                status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
            }
            if (switching) switchInstalled();
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            // Once per box, unless granted with: adb shell appops set <this app> REQUEST_INSTALL_PACKAGES allow
            queue.clear();
            working = null;
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.allow_installs);
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            rebuild();
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.allow_installs);
            return;
        }
        Item item = working;
        if (item.entry.uninstallFirst && item.installed >= 0 && !Catalog.mayUninstall(item.entry.pkg, getPackageName())) {
            failed(item, R.string.failed_protected);
            return;
        }
        item.action.setText(R.string.action_downloading);
        new Thread(() -> fetchAndCheck(item), "updates-download").start();
    }

    private String failure;

    private void failed(Item item, int message) {
        failed(item, getString(message));
    }

    private void failed(Item item, String message) {
        failure = item.entry.name + ": " + message;
        clearDownload();
        next();
    }

    private void fetchAndCheck(Item item) {
        File file = new File(getCacheDir(), "update-" + item.entry.pkg + ".apk");
        int problem = 0;
        try {
            problem = fetchApk(item, file);
        } catch (IOException | NoSuchAlgorithmException e) {
            problem = R.string.failed_download;
        }
        List<String> apks = new ArrayList<>();
        if (problem == 0) {
            try {
                problem = check(item, file, apks);
            } catch (IOException | RuntimeException e) {
                problem = R.string.failed_wrong_app;
            }
        }
        int result = problem;
        main.post(() -> {
            download = file;
            downloadApks = apks;
            if (isDestroyed()) {
                clearDownload();
                return;
            }
            if (result != 0) {
                failed(item, result);
            } else if (item.entry.uninstallFirst && item.installed >= 0) {
                uninstall(item);
            } else {
                install(item);
            }
        });
    }

    /**
     * Returns 0 when the download is the listed app, as a plain APK or as a split bundle (XAPK), whose APKs it adds to
     * apks. A bundle is checked by its base APK, unpacked for a moment.
     */
    private int check(Item item, File file, List<String> apks) throws IOException {
        List<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) entries.add(e.nextElement().getName());
            apks.addAll(SplitBundle.apks(entries));
            File apk = file;
            File base = new File(getCacheDir(), "check-" + item.entry.pkg + ".apk");
            try {
                if (!apks.isEmpty()) {
                    if (SplitBundle.hasObb(entries)) return R.string.failed_obb;
                    try (InputStream in = zip.getInputStream(zip.getEntry(SplitBundle.base(apks, item.entry.pkg)));
                         OutputStream out = new FileOutputStream(base)) {
                        copy(in, out);
                    }
                    apk = base;
                }
                PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                return info != null && item.entry.pkg.equals(info.packageName) ? 0 : R.string.failed_wrong_app;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                base.delete();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
    }

    /** Returns 0, or the message to show. */
    private int fetchApk(Item item, File file) throws IOException, NoSuchAlgorithmException {
        HttpURLConnection http = (HttpURLConnection) new URL(item.entry.url).openConnection();
        http.setConnectTimeout(TIMEOUT_MS);
        http.setReadTimeout(TIMEOUT_MS);
        try {
            if (http.getResponseCode() != 200) return R.string.failed_download;
            long length = http.getContentLengthLong();
            // Android unpacks what it installs, so it needs about as much again.
            if (length > 0 && length * 2 > new StatFs(getCacheDir().getPath()).getAvailableBytes()) return R.string.failed_space;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long done = 0;
            int shown = -1;
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) != -1; ) {
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    done += n;
                    int percent = length > 0 ? (int) (done * 100 / length) : -1;
                    if (percent != shown) {
                        shown = percent;
                        main.post(() -> {
                            if (item.action != null && percent >= 0) item.action.setText(getString(R.string.action_percent, percent));
                        });
                    }
                }
            }
            if (!item.entry.sha256.isEmpty() && !item.entry.sha256.equals(hex(digest.digest()))) return R.string.failed_checksum;
            return 0;
        } finally {
            http.disconnect();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }

    private PendingIntent answer() {
        Intent intent = new Intent(ACTION_DONE).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, ++nextRequest, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
    }

    private void uninstall(Item item) {
        uninstalling = true;
        item.action.setText(R.string.action_removing);
        getPackageManager().getPackageInstaller().uninstall(item.entry.pkg, answer().getIntentSender());
    }

    private void uninstalled(boolean done) {
        uninstalling = false;
        Item item = working;
        if (!done) {
            failed(item, R.string.failed_uninstall);
            return;
        }
        install(item);
    }

    private void install(Item item) {
        item.action.setText(R.string.action_installing);
        File file = download;
        List<String> apks = downloadApks;
        new Thread(() -> {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(item.entry.pkg);
                params.setSize(file.length());
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    if (apks.isEmpty()) {
                        try (InputStream in = new FileInputStream(file);
                             OutputStream out = session.openWrite("base.apk", 0, file.length())) {
                            copy(in, out);
                            session.fsync(out);
                        }
                    } else {
                        // A split bundle: every APK in it goes into the one session, straight from the zip.
                        try (ZipFile zip = new ZipFile(file)) {
                            for (String name : apks) {
                                ZipEntry entry = zip.getEntry(name);
                                try (InputStream in = zip.getInputStream(entry);
                                     OutputStream out = session.openWrite(name, 0, entry.getSize())) {
                                    copy(in, out);
                                    session.fsync(out);
                                }
                            }
                        }
                    }
                    session.commit(answer().getIntentSender());
                }
            } catch (IOException | RuntimeException e) {
                main.post(() -> {
                    if (!isDestroyed()) failed(item, R.string.failed_install);
                });
            }
        }, "updates-install").start();
    }

    private void installed(int code, String message) {
        Item item = working;
        if (code == PackageInstaller.STATUS_SUCCESS) {
            clearDownload();
            next();
        } else if (code == PackageInstaller.STATUS_FAILURE_ABORTED) {
            failed(item, R.string.failed_cancelled);
        } else if (code == PackageInstaller.STATUS_FAILURE_CONFLICT) {
            failed(item, R.string.failed_conflict);
        } else if (code == PackageInstaller.STATUS_FAILURE_STORAGE) {
            failed(item, R.string.failed_space);
        } else {
            failed(item, message == null ? getString(R.string.failed_install) : message);
        }
    }

    private void clearDownload() {
        if (download != null) {
            //noinspection ResultOfMethodCallIgnored
            download.delete();
            download = null;
            downloadApks = new ArrayList<>();
        }
    }

    private long installedVersion(String pkg) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private String installedName(String pkg) {
        try {
            String name = getPackageManager().getPackageInfo(pkg, 0).versionName;
            return name == null ? "" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
