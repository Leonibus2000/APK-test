package com.max.apkscope;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home screen: every installed app, searchable. */
public class MainActivity extends Activity {

    static final class AppEntry {
        String label, pkg, version;
        boolean system;
        long size;
        int splits;
        Drawable icon;
        ApplicationInfo info;
    }

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText search;
    private TextView status;
    private TextView sysToggle;
    private BaseAdapter adapter;
    private boolean showSystem = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), 0);

        LinearLayout titleRow = Ui.hbox(this);
        TextView dot = Ui.text(this, "◆", 20, Ui.ACCENT, true);
        titleRow.addView(dot, Ui.margins(this, Ui.lp(-2, -2), 0, 0, 10, 0));
        titleRow.addView(Ui.text(this, "APK Scope", 28, Ui.TEXT, true));
        root.addView(titleRow);
        root.addView(Ui.text(this, "Pick an app to look inside: engine, code, strings, files.", 14, Ui.DIM, false),
                Ui.margins(this, Ui.lp(-1, -2), 0, 4, 0, 0));

        search = Ui.search(this, "Search apps or package names");
        root.addView(search, Ui.margins(this, Ui.lp(-1, -2), 0, 18, 0, 0));

        LinearLayout bar = Ui.hbox(this);
        status = Ui.text(this, "Loading apps…", 12.5f, Ui.DIM, false);
        bar.addView(status, Ui.lp(0, -2, 1f));
        sysToggle = Ui.chip(this, "System apps: off", Ui.DIM);
        sysToggle.setOnClickListener(v -> {
            showSystem = !showSystem;
            styleToggle();
            applyFilter();
        });
        bar.addView(sysToggle);
        root.addView(bar, Ui.margins(this, Ui.lp(-1, -2), 2, 12, 0, 6));

        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(0x00000000));
        list.setDividerHeight(Ui.dp(this, 8));
        list.setSelector(new ColorDrawable(0x00000000));
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 32));
        list.setFastScrollEnabled(true);
        root.addView(list, Ui.lp(-1, 0, 1f));

        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Intent i = new Intent(this, AppDetailActivity.class);
            i.putExtra(AppDetailActivity.EXTRA_PKG, shown.get(pos).pkg);
            startActivity(i);
        });
        search.addTextChangedListener(Ui.watcher(this::applyFilter));

        setContentView(root);
        loadApps();
    }

    private void styleToggle() {
        int color = showSystem ? Ui.CYAN : Ui.DIM;
        sysToggle.setText(showSystem ? "System apps: on" : "System apps: off");
        sysToggle.setTextColor(color);
        sysToggle.setBackground(Ui.pressable(this, Ui.alpha(color, 0x1F), 99, Ui.alpha(color, 0x55)));
    }

    private void loadApps() {
        exec.execute(() -> {
            PackageManager pm = getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);
            List<AppEntry> res = new ArrayList<>();
            for (PackageInfo pi : pkgs) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || ai.sourceDir == null) continue;
                AppEntry e = new AppEntry();
                e.info = ai;
                e.pkg = pi.packageName;
                e.label = String.valueOf(ai.loadLabel(pm));
                e.version = pi.versionName == null ? "" : pi.versionName;
                e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                e.size = new File(ai.sourceDir).length();
                if (ai.splitSourceDirs != null) {
                    e.splits = ai.splitSourceDirs.length;
                    for (String s : ai.splitSourceDirs) e.size += new File(s).length();
                }
                if (!e.system) e.icon = ai.loadIcon(pm); // system icons load lazily
                res.add(e);
            }
            res.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            ui.post(() -> {
                all.clear();
                all.addAll(res);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        int userCount = 0;
        for (AppEntry e : all) {
            if (!e.system) userCount++;
            if (!showSystem && e.system) continue;
            if (q.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(q) || e.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(e);
            }
        }
        if (!all.isEmpty()) {
            status.setText(String.format(Locale.US, "%d shown · %d installed apps · %d system", shown.size(), userCount, all.size() - userCount));
        }
        adapter.notifyDataSetChanged();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                h = new Holder();
                LinearLayout row = Ui.hbox(MainActivity.this);
                row.setBackground(Ui.pressable(MainActivity.this, Ui.CARD, 18, Ui.STROKE));
                int p = Ui.dp(MainActivity.this, 12);
                row.setPadding(p, p, Ui.dp(MainActivity.this, 14), p);

                h.icon = new ImageView(MainActivity.this);
                int s = Ui.dp(MainActivity.this, 46);
                row.addView(h.icon, Ui.margins(MainActivity.this, Ui.lp(s, s), 0, 0, 12, 0));

                LinearLayout col = Ui.vbox(MainActivity.this);
                h.label = Ui.single(Ui.text(MainActivity.this, "", 16, Ui.TEXT, true));
                h.pkg = Ui.single(Ui.text(MainActivity.this, "", 12, Ui.DIM, false));
                col.addView(h.label);
                col.addView(h.pkg);
                row.addView(col, Ui.lp(0, -2, 1f));

                LinearLayout right = Ui.vbox(MainActivity.this);
                right.setGravity(android.view.Gravity.END);
                h.size = Ui.text(MainActivity.this, "", 12, Ui.CYAN, true);
                h.version = Ui.single(Ui.text(MainActivity.this, "", 11, Ui.FAINT, false));
                h.version.setMaxWidth(Ui.dp(MainActivity.this, 90));
                right.addView(h.size);
                right.addView(h.version);
                row.addView(right, Ui.margins(MainActivity.this, Ui.lp(-2, -2), 8, 0, 0, 0));

                convert = row;
                convert.setTag(h);
            } else {
                h = (Holder) convert.getTag();
            }
            AppEntry e = shown.get(pos);
            if (e.icon == null) e.icon = e.info.loadIcon(getPackageManager());
            h.icon.setImageDrawable(e.icon);
            h.label.setText(e.label);
            h.pkg.setText(e.pkg);
            h.size.setText(Ui.size(e.size) + (e.splits > 0 ? " · " + (e.splits + 1) + " APKs" : ""));
            h.version.setText(e.version.isEmpty() ? "" : "v" + e.version);
            return convert;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView label, pkg, size, version;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }
}
