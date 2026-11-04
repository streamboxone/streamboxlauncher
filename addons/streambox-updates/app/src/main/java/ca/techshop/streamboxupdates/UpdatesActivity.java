package ca.techshop.streamboxupdates;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
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
import java.util.List;
import java.util.Locale;

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
        rebuild();
    }

    private int badLines;

    private void rebuild() {
        rows.removeAllViews();
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
        if (problem == 0) {
            PackageInfo info = getPackageManager().getPackageArchiveInfo(file.getPath(), 0);
            if (info == null || !item.entry.pkg.equals(info.packageName)) problem = R.string.failed_wrong_app;
        }
        int result = problem;
        main.post(() -> {
            download = file;
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
        new Thread(() -> {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(item.entry.pkg);
                params.setSize(file.length());
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream in = new FileInputStream(file);
                         OutputStream out = session.openWrite("base.apk", 0, file.length())) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
                        session.fsync(out);
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
