package com.max.apkscope;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Inspector for one app: Overview · Manifest · Classes · Strings · Files. */
public class AppDetailActivity extends Activity {

    static final String EXTRA_PKG = "pkg";
    private static final int REQ_EXPORT = 41;
    private static final String[] TABS = {"Overview", "Manifest", "Classes", "Strings", "Files"};

    private String pkg;
    private PackageInfo pi;
    private ApplicationInfo ai;
    private final List<String> apkPaths = new ArrayList<>();

    private ApkAnalyzer.Result result;
    private FrameLayout content;
    private TextView loading;
    private final TextView[] tabViews = new TextView[TABS.length];
    private final View[] pages = new View[TABS.length];
    private FilterList<String> stringsList;
    private int current = 0;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkg = getIntent().getStringExtra(EXTRA_PKG);
        PackageManager pm = getPackageManager();
        try {
            try {
                pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            } catch (RuntimeException tooBig) {
                pi = pm.getPackageInfo(pkg, 0);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(this, "App not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        ai = pi.applicationInfo;
        apkPaths.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) apkPaths.add(s);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), 0);

        // Header
        LinearLayout header = Ui.hbox(this);
        TextView back = Ui.text(this, "‹", 34, Ui.TEXT, false);
        back.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 14), Ui.dp(this, 4));
        back.setOnClickListener(v -> finish());
        header.addView(back);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(ai.loadIcon(pm));
        int is = Ui.dp(this, 52);
        header.addView(icon, Ui.margins(this, Ui.lp(is, is), 0, 0, 14, 0));
        LinearLayout names = Ui.vbox(this);
        names.addView(Ui.single(Ui.text(this, String.valueOf(ai.loadLabel(pm)), 20, Ui.TEXT, true)));
        names.addView(Ui.single(Ui.text(this, pkg, 12, Ui.DIM, false)));
        names.addView(Ui.single(Ui.text(this, "v" + (pi.versionName == null ? "?" : pi.versionName)
                + " · " + (apkPaths.size() > 1 ? apkPaths.size() + " APKs" : "1 APK"), 12, Ui.FAINT, false)));
        header.addView(names, Ui.lp(0, -2, 1f));
        root.addView(header);

        // Tabs
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = Ui.hbox(this);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, TABS[i], 14, Ui.DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
            t.setOnClickListener(v -> selectTab(idx));
            tabViews[i] = t;
            tabRow.addView(t, Ui.margins(this, Ui.lp(-2, -2), 0, 0, 8, 0));
        }
        tabScroll.addView(tabRow);
        root.addView(tabScroll, Ui.margins(this, Ui.lp(-1, -2), 0, 16, 0, 12));

        content = new FrameLayout(this);
        root.addView(content, Ui.lp(-1, 0, 1f));

        loading = Ui.text(this, "Analyzing APK…", 14, Ui.DIM, false);
        loading.setGravity(Gravity.CENTER);

        setContentView(root);
        selectTab(0);

        exec.execute(() -> {
            ApkAnalyzer.Result r = ApkAnalyzer.analyze(apkPaths, msg -> ui.post(() -> loading.setText(msg)));
            ui.post(() -> {
                if (isFinishing()) return;
                result = r;
                selectTab(current);
            });
        });
    }

    private void selectTab(int i) {
        current = i;
        for (int t = 0; t < tabViews.length; t++) {
            boolean sel = t == i;
            tabViews[t].setTextColor(sel ? 0xFF0A0A12 : Ui.DIM);
            tabViews[t].setBackground(sel ? Ui.round(this, Ui.ACCENT, 99, 0) : Ui.pressable(this, Ui.CARD, 99, Ui.STROKE));
        }
        content.removeAllViews();
        if (result == null) {
            content.addView(loading, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        if (pages[i] == null) pages[i] = buildPage(i);
        content.addView(pages[i], new FrameLayout.LayoutParams(-1, -1));
    }

    private View buildPage(int i) {
        switch (i) {
            case 0: return buildOverview();
            case 1: return buildManifest();
            case 2: return buildClasses();
            case 3: return buildStrings();
            default: return buildFiles();
        }
    }

    // ───────────────────────── Overview ─────────────────────────

    private View buildOverview() {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, Ui.dp(this, 32));
        LinearLayout col = Ui.vbox(this);
        scroll.addView(col);

        if (result.error != null) {
            LinearLayout err = Ui.card(this);
            err.addView(Ui.text(this, "Some parts couldn't be read", 15, Ui.PINK, true));
            err.addView(Ui.text(this, result.error, 13, Ui.DIM, false));
            col.addView(err, Ui.margins(this, Ui.lp(-1, -2), 0, 0, 0, 12));
        }

        // Engine card
        ApkAnalyzer.Engine en = result.engine;
        LinearLayout eng = Ui.card(this);
        eng.addView(Ui.text(this, "ENGINE", 11, Ui.FAINT, true));
        LinearLayout engRow = Ui.hbox(this);
        engRow.addView(Ui.text(this, en.name, 22, en.color, true), Ui.lp(0, -2, 1f));
        engRow.addView(difficultyView(en.difficulty));
        eng.addView(engRow, Ui.margins(this, Ui.lp(-1, -2), 0, 4, 0, 0));
        eng.addView(Ui.text(this, en.summary, 14, Ui.TEXT, false), Ui.margins(this, Ui.lp(-1, -2), 0, 6, 0, 0));
        eng.addView(sectionLabel("WHERE THE GAME LOGIC LIVES"));
        eng.addView(Ui.text(this, en.logic, 13.5f, Ui.DIM, false));
        eng.addView(sectionLabel("HOW TO CHANGE VALUES (ON A PC)"));
        eng.addView(Ui.text(this, en.tools, 13.5f, Ui.DIM, false));
        col.addView(eng, Ui.margins(this, Ui.lp(-1, -2), 0, 0, 0, 12));

        // Value hunt card
        LinearLayout hunt = Ui.card(this);
        hunt.addView(Ui.text(this, "VALUE HUNT", 11, Ui.FAINT, true));
        hunt.addView(Ui.text(this, "Names found in the code. Tap one to see every matching string.", 13, Ui.DIM, false),
                Ui.margins(this, Ui.lp(-1, -2), 0, 4, 0, 8));
        FlowLayout flow = new FlowLayout(this, Ui.dp(this, 8));
        boolean any = false;
        for (Map.Entry<String, Integer> kv : result.keywordHits.entrySet()) {
            if (kv.getValue() == 0) continue;
            any = true;
            TextView chip = Ui.chip(this, kv.getKey() + "  " + kv.getValue(), Ui.YELLOW);
            final String word = kv.getKey();
            chip.setOnClickListener(v -> {
                selectTab(3);
                if (stringsList != null) stringsList.setQuery(word);
            });
            flow.addView(chip);
        }
        if (!any) hunt.addView(Ui.text(this, "No obvious value names in the DEX strings. For Unity/native games the names live in "
                + "the engine files instead (see Engine above).", 13, Ui.DIM, false));
        else hunt.addView(flow);
        col.addView(hunt, Ui.margins(this, Ui.lp(-1, -2), 0, 0, 0, 12));

        // Code stats
        LinearLayout code = Ui.card(this);
        code.addView(Ui.text(this, "CODE", 11, Ui.FAINT, true));
        kv(code, "DEX files", String.valueOf(result.dexCount));
        kv(code, "Classes", String.format(Locale.US, "%,d", result.classes.size()));
        kv(code, "Strings", String.format(Locale.US, "%,d", result.strings.size()));
        kv(code, "Files in APK", String.format(Locale.US, "%,d", result.entries.size()));
        String obf = result.classes.isEmpty() ? "—"
                : result.obfuscation > 0.5f ? String.format(Locale.US, "Heavy (%.0f%% short names)", result.obfuscation * 100)
                : result.obfuscation > 0.15f ? String.format(Locale.US, "Partial (%.0f%%)", result.obfuscation * 100)
                : "Little or none";
        kv(code, "Obfuscation", obf);
        kv(code, "CPU ABIs", result.abis.isEmpty() ? "none (pure Java/Kotlin)" : String.join(", ", result.abis));
        if (!result.nativeLibs.isEmpty()) kv(code, "Native libs", String.join("\n", result.nativeLibs));
        col.addView(code, Ui.margins(this, Ui.lp(-1, -2), 0, 0, 0, 12));

        // App info
        LinearLayout info = Ui.card(this);
        info.addView(Ui.text(this, "APP", 11, Ui.FAINT, true));
        kv(info, "Package", pkg);
        kv(info, "Version", pi.versionName == null ? "?" : pi.versionName);
        kv(info, "Version code", String.valueOf(Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode));
        kv(info, "Min SDK", String.valueOf(ai.minSdkVersion));
        kv(info, "Target SDK", String.valueOf(ai.targetSdkVersion));
        kv(info, "Installed from", installer());
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        kv(info, "First installed", df.format(new Date(pi.firstInstallTime)));
        kv(info, "Last updated", df.format(new Date(pi.lastUpdateTime)));
        kv(info, "Total size", Ui.size(result.totalSize));
        kv(info, "Debuggable", (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 ? "yes" : "no");
        kv(info, "Allow backup", (ai.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0 ? "yes" : "no");
        kv(info, "Save data", ai.dataDir + "\n(private — only readable with root)");
        StringBuilder paths = new StringBuilder();
        for (String p : apkPaths) paths.append(paths.length() > 0 ? "\n" : "").append(p);
        kv(info, "APK files", paths.toString());
        col.addView(info, Ui.margins(this, Ui.lp(-1, -2), 0, 0, 0, 12));

        // Permissions
        if (pi.requestedPermissions != null && pi.requestedPermissions.length > 0) {
            LinearLayout perms = Ui.card(this);
            perms.addView(Ui.text(this, "PERMISSIONS · " + pi.requestedPermissions.length, 11, Ui.FAINT, true));
            StringBuilder sb = new StringBuilder();
            for (String p : pi.requestedPermissions) {
                String shortName = p.startsWith("android.permission.") ? p.substring(19) : p;
                sb.append(sb.length() > 0 ? "\n" : "").append(shortName);
            }
            TextView pt = Ui.mono(this, sb.toString(), 12, Ui.DIM);
            pt.setTextIsSelectable(true);
            perms.addView(pt, Ui.margins(this, Ui.lp(-1, -2), 0, 8, 0, 0));
            col.addView(perms, Ui.margins(this, Ui.lp(-1, -2), 0, 0, 0, 12));
        }

        // Actions
        TextView export = Ui.button(this, apkPaths.size() > 1 ? "Export APKs (.apks bundle)" : "Export APK", Ui.ACCENT);
        export.setOnClickListener(v -> startExport());
        col.addView(export, Ui.margins(this, Ui.lp(-1, -2), 0, 4, 0, 8));

        LinearLayout row = Ui.hbox(this);
        TextView launch = Ui.ghostButton(this, "Launch", Ui.TEXT);
        launch.setOnClickListener(v -> {
            Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
            if (li != null) startActivity(li);
            else Toast.makeText(this, "This app has no launcher screen", Toast.LENGTH_SHORT).show();
        });
        row.addView(launch, Ui.margins(this, Ui.lp(0, -2, 1f), 0, 0, 4, 0));
        TextView settings = Ui.ghostButton(this, "App settings", Ui.TEXT);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg))));
        row.addView(settings, Ui.margins(this, Ui.lp(0, -2, 1f), 4, 0, 0, 0));
        col.addView(row);

        TextView note = Ui.text(this, "Patched APKs must be re-signed, so you have to uninstall the original first (its save data "
                + "goes with it). Games that check purchases or coins on a server, or verify their own signature, won't accept changes.",
                12, Ui.FAINT, false);
        col.addView(note, Ui.margins(this, Ui.lp(-1, -2), 4, 14, 4, 0));
        return scroll;
    }

    private View difficultyView(int level) {
        LinearLayout box = Ui.vbox(this);
        box.setGravity(Gravity.END);
        LinearLayout dots = Ui.hbox(this);
        int color = level <= 2 ? Ui.GREEN : level == 3 ? Ui.YELLOW : level == 4 ? Ui.ORANGE : Ui.PINK;
        for (int i = 1; i <= 5; i++) {
            View d = new View(this);
            d.setBackground(Ui.round(this, i <= level ? color : Ui.CARD2, 99, 0));
            int s = Ui.dp(this, 8);
            dots.addView(d, Ui.margins(this, Ui.lp(s, s), 2, 0, 0, 0));
        }
        box.addView(dots);
        String label = level <= 1 ? "easy" : level == 2 ? "moddable" : level == 3 ? "medium" : level == 4 ? "hard" : "very hard";
        box.addView(Ui.text(this, label, 10.5f, color, true), Ui.margins(this, Ui.lp(-2, -2), 0, 3, 0, 0));
        return box;
    }

    private TextView sectionLabel(String s) {
        TextView t = Ui.text(this, s, 11, Ui.ACCENT, true);
        t.setLayoutParams(Ui.margins(this, Ui.lp(-1, -2), 0, 14, 0, 4));
        return t;
    }

    private void kv(LinearLayout parent, String k, String v) {
        LinearLayout row = Ui.hbox(this);
        row.setGravity(Gravity.TOP);
        TextView key = Ui.text(this, k, 13, Ui.DIM, false);
        row.addView(key, Ui.lp(Ui.dp(this, 112), -2));
        TextView val = Ui.text(this, v, 13, Ui.TEXT, false);
        val.setTextIsSelectable(true);
        row.addView(val, Ui.lp(0, -2, 1f));
        parent.addView(row, Ui.margins(this, Ui.lp(-1, -2), 0, 9, 0, 0));
    }

    private String installer() {
        try {
            String ins;
            if (Build.VERSION.SDK_INT >= 30) ins = getPackageManager().getInstallSourceInfo(pkg).getInstallingPackageName();
            else ins = getPackageManager().getInstallerPackageName(pkg);
            if (ins == null) return "unknown / sideloaded";
            if (ins.equals("com.android.vending")) return "Google Play";
            return ins;
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ───────────────────────── Manifest ─────────────────────────

    private View buildManifest() {
        LinearLayout col = Ui.vbox(this);
        LinearLayout bar = Ui.hbox(this);
        bar.addView(Ui.text(this, "AndroidManifest.xml · decoded", 12.5f, Ui.DIM, false), Ui.lp(0, -2, 1f));
        TextView copy = Ui.chip(this, "Copy", Ui.CYAN);
        copy.setOnClickListener(v -> copy("Manifest", result.manifest));
        bar.addView(copy);
        col.addView(bar, Ui.margins(this, Ui.lp(-1, -2), 2, 0, 0, 10));

        ScrollView scroll = new ScrollView(this);
        TextView tv = Ui.mono(this, highlightXml(result.manifest), 11.5f, Ui.TEXT);
        tv.setTextIsSelectable(true);
        tv.setBackground(Ui.round(this, Ui.CARD, 18, Ui.STROKE));
        int p = Ui.dp(this, 14);
        tv.setPadding(p, p, p, p);
        scroll.addView(tv);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, Ui.dp(this, 32));
        col.addView(scroll, Ui.lp(-1, 0, 1f));
        return col;
    }

    static CharSequence highlightXml(String xml) {
        if (xml.length() > 600_000) return xml;
        SpannableString sp = new SpannableString(xml);
        color(sp, Pattern.compile("</?[\\w:.\\-]+|/?>|<\\?xml|\\?>"), 0, Ui.ACCENT);
        color(sp, Pattern.compile("\\s([\\w:.\\-]+)="), 1, Ui.CYAN);
        color(sp, Pattern.compile("\"[^\"]*\""), 0, Ui.GREEN);
        return sp;
    }

    private static void color(SpannableString sp, Pattern p, int group, int color) {
        Matcher m = p.matcher(sp);
        while (m.find()) {
            sp.setSpan(new ForegroundColorSpan(color), m.start(group), m.end(group), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ───────────────────────── Classes ─────────────────────────

    private View buildClasses() {
        if (result.classes.isEmpty()) {
            return emptyState("No DEX classes", "This app has no readable Java/Kotlin code. Its logic is in native or engine files — check the Overview.");
        }
        FilterList<String> fl = new FilterList<>(this, result.classes, "Search classes (e.g. coin, shop, save)", "classes",
                false, 2,
                s -> s,
                (s, title, sub, side) -> {
                    int dot = s.lastIndexOf('.');
                    title.setText(dot >= 0 ? s.substring(dot + 1) : s);
                    sub.setText(dot >= 0 ? s.substring(0, dot) : "(default package)");
                },
                s -> copy("Class", s),
                null);
        return fl.view;
    }

    // ───────────────────────── Strings ─────────────────────────

    private View buildStrings() {
        if (result.strings.isEmpty()) {
            return emptyState("No strings", "No DEX string table was found in this app.");
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.hbox(this);
        hs.addView(chips);
        FilterList<String> fl = new FilterList<>(this, result.strings, "Search strings, field & method names", "strings",
                true, 3,
                s -> s,
                (s, title, sub, side) -> {
                    title.setText(s);
                    if (s.length() > 120) side.setText(s.length() + " ch");
                },
                s -> copy("String", s),
                hs);
        String[] quick = {"coin", "gold", "gem", "money", "score", "premium", "unlock", "save", "http"};
        for (String q : quick) {
            TextView c = Ui.chip(this, q, Ui.YELLOW);
            c.setOnClickListener(v -> fl.setQuery(q));
            chips.addView(c, Ui.margins(this, Ui.lp(-2, -2), 0, 0, 8, 0));
        }
        TextView clear = Ui.chip(this, "clear", Ui.DIM);
        clear.setOnClickListener(v -> fl.setQuery(""));
        chips.addView(clear);
        stringsList = fl;
        return fl.view;
    }

    // ───────────────────────── Files ─────────────────────────

    private View buildFiles() {
        boolean multi = apkPaths.size() > 1;
        FilterList<ApkAnalyzer.Entry> fl = new FilterList<>(this, result.entries, "Search files (e.g. .dll, .lua, assets/)", "files",
                true, 2,
                e -> e.name,
                (e, title, sub, side) -> {
                    title.setText(e.name);
                    if (multi) sub.setText(e.apkName);
                    side.setText(e.size >= 0 ? Ui.size(e.size) : "?");
                },
                e -> {
                    Intent i = new Intent(this, FileViewerActivity.class);
                    i.putExtra(FileViewerActivity.EXTRA_APK, e.apkPath);
                    i.putExtra(FileViewerActivity.EXTRA_ENTRY, e.name);
                    startActivity(i);
                },
                null);
        return fl.view;
    }

    private View emptyState(String title, String body) {
        LinearLayout box = Ui.vbox(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 60));
        TextView t = Ui.text(this, title, 18, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        TextView b = Ui.text(this, body, 14, Ui.DIM, false);
        b.setGravity(Gravity.CENTER);
        box.addView(b, Ui.margins(this, Ui.lp(-2, -2), 0, 8, 0, 0));
        return box;
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, label + " copied", Toast.LENGTH_SHORT).show();
    }

    // ───────────────────────── Export ─────────────────────────

    private void startExport() {
        boolean multi = apkPaths.size() > 1;
        String base = pkg + "_" + (pi.versionName == null ? "app" : pi.versionName.replaceAll("[^\\w.\\-]", "_"));
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(multi ? "application/octet-stream" : "application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_TITLE, base + (multi ? ".apks" : ".apk"));
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show();
        exec.execute(() -> {
            String msg;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Can't open destination");
                byte[] buf = new byte[256 * 1024];
                if (apkPaths.size() == 1) {
                    try (InputStream in = new FileInputStream(apkPaths.get(0))) {
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                } else {
                    ZipOutputStream zos = new ZipOutputStream(out);
                    for (int k = 0; k < apkPaths.size(); k++) {
                        File f = new File(apkPaths.get(k));
                        zos.putNextEntry(new ZipEntry(k == 0 ? "base.apk" : f.getName()));
                        try (InputStream in = new FileInputStream(f)) {
                            int n;
                            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                        }
                        zos.closeEntry();
                    }
                    zos.finish();
                }
                msg = "Exported ✓";
            } catch (Exception e) {
                msg = "Export failed: " + e.getMessage();
            }
            final String m = msg;
            ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    /** Wraps children onto new lines, for the keyword chips. */
    static final class FlowLayout extends android.view.ViewGroup {
        private final int gap;

        FlowLayout(Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int maxW = MeasureSpec.getSize(wSpec);
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                x += w + gap;
                rowH = Math.max(rowH, h);
            }
            setMeasuredDimension(maxW, y + rowH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l;
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                ch.layout(x, y, x + w, y + h);
                x += w + gap;
                rowH = Math.max(rowH, h);
            }
        }
    }
}
