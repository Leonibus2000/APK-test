package com.max.apkscope;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Shows one file from inside an APK as decoded XML, text, or a hex dump — and can save it. */
public class FileViewerActivity extends Activity {

    static final String EXTRA_APK = "apk";
    static final String EXTRA_ENTRY = "entry";
    private static final int REQ_SAVE = 52;
    private static final int TEXT_CAP = 400 * 1024;
    private static final int HEX_CAP = 16 * 1024;

    private String apk, entry;
    private TextView body, info;
    private String plain = "";
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        apk = getIntent().getStringExtra(EXTRA_APK);
        entry = getIntent().getStringExtra(EXTRA_ENTRY);

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), 0);

        LinearLayout header = Ui.hbox(this);
        TextView back = Ui.text(this, "‹", 34, Ui.TEXT, false);
        back.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 14), Ui.dp(this, 4));
        back.setOnClickListener(v -> finish());
        header.addView(back);
        LinearLayout names = Ui.vbox(this);
        String fileName = entry.substring(entry.lastIndexOf('/') + 1);
        names.addView(Ui.single(Ui.text(this, fileName, 18, Ui.TEXT, true)));
        TextView path = Ui.text(this, entry, 11.5f, Ui.DIM, false);
        path.setMaxLines(2);
        names.addView(path);
        header.addView(names, Ui.lp(0, -2, 1f));
        root.addView(header);

        LinearLayout bar = Ui.hbox(this);
        info = Ui.text(this, "Reading…", 12, Ui.FAINT, false);
        bar.addView(info, Ui.lp(0, -2, 1f));
        TextView copy = Ui.chip(this, "Copy", Ui.CYAN);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(fileName, plain));
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        bar.addView(copy, Ui.margins(this, Ui.lp(-2, -2), 8, 0, 0, 0));
        TextView save = Ui.chip(this, "Save file", Ui.ACCENT);
        save.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, fileName);
            try { startActivityForResult(i, REQ_SAVE); }
            catch (Exception e) { Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show(); }
        });
        bar.addView(save, Ui.margins(this, Ui.lp(-2, -2), 8, 0, 0, 0));
        root.addView(bar, Ui.margins(this, Ui.lp(-1, -2), 2, 14, 0, 10));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, Ui.dp(this, 32));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        body = Ui.mono(this, "", 11.5f, Ui.TEXT);
        body.setTextIsSelectable(true);
        int p = Ui.dp(this, 14);
        body.setPadding(p, p, p, p);
        hs.setBackground(Ui.round(this, Ui.CARD, 18, Ui.STROKE));
        hs.setFillViewport(true);
        hs.addView(body);
        scroll.addView(hs);
        root.addView(scroll, Ui.lp(-1, 0, 1f));

        setContentView(root);
        load();
    }

    private void load() {
        exec.execute(() -> {
            String infoText;
            CharSequence shown;
            try (ZipFile z = new ZipFile(apk)) {
                ZipEntry ze = z.getEntry(entry);
                if (ze == null) throw new IllegalStateException("Entry not found");
                long size = ze.getSize();
                // Binary XML must be read whole to decode; everything else only needs a preview.
                long cap = entry.endsWith(".xml") ? Math.max(TEXT_CAP, Math.min(size, 8L * 1024 * 1024)) : TEXT_CAP;
                byte[] data = ApkAnalyzer.readAll(z, ze, cap);
                String sizes = Ui.size(size) + " (" + Ui.size(ze.getCompressedSize()) + " packed)";
                if (AxmlDecoder.isAxml(data)) {
                    plain = AxmlDecoder.decode(data);
                    shown = AppDetailActivity.highlightXml(plain);
                    infoText = "Binary XML · decoded · " + sizes;
                } else if (data.length >= 4 && data[0] == 'd' && data[1] == 'e' && data[2] == 'x') {
                    plain = "DEX bytecode. Its classes and strings are in the Classes and Strings tabs.\n\n" + hex(data);
                    shown = plain;
                    infoText = "DEX · " + sizes;
                } else if (looksText(data)) {
                    plain = new String(data, StandardCharsets.UTF_8);
                    if (size > data.length) plain += "\n\n… (showing first " + Ui.size(data.length) + " — use Save file for the rest)";
                    shown = plain;
                    infoText = "Text · " + sizes;
                } else {
                    plain = hex(data);
                    shown = plain;
                    infoText = describe(data) + " · " + sizes + " · hex of first " + Ui.size(Math.min(data.length, HEX_CAP));
                }
            } catch (Exception e) {
                plain = "Couldn't read this file: " + e;
                shown = plain;
                infoText = "Error";
            }
            final String it = infoText;
            final CharSequence sh = shown;
            ui.post(() -> {
                info.setText(it);
                body.setText(sh);
            });
        });
    }

    private static boolean looksText(byte[] d) {
        int n = Math.min(d.length, 8192);
        if (n == 0) return true;
        int bad = 0;
        for (int i = 0; i < n; i++) {
            int b = d[i] & 0xff;
            if (b == 0) return false;
            if (b < 0x09 || (b > 0x0d && b < 0x20)) bad++;
        }
        return bad * 100 / n < 3;
    }

    private static String describe(byte[] d) {
        if (d.length >= 4) {
            if ((d[0] & 0xff) == 0x7f && d[1] == 'E' && d[2] == 'L' && d[3] == 'F') return "Native library (ELF)";
            if (d[0] == 'P' && d[1] == 'K') return "ZIP archive";
            if (d[0] == 'M' && d[1] == 'Z') return ".NET / PE assembly";
            if ((d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "PNG image";
            if ((d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8) return "JPEG image";
            if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F') return "RIFF (WebP/WAV)";
            if (d[0] == 'O' && d[1] == 'g' && d[2] == 'g') return "Ogg audio";
            if (d[0] == 0x1B && d[1] == 'L' && d[2] == 'u' && d[3] == 'a') return "Compiled Lua";
            if (d[0] == 'U' && d[1] == 'n' && d[2] == 'i' && d[3] == 't') return "Unity asset bundle";
            if (d[0] == 'G' && d[1] == 'D' && d[2] == 'P' && d[3] == 'C') return "Godot pack";
            if ((d[0] & 0xff) == 0xAF && (d[1] & 0xff) == 0x1B && (d[2] & 0xff) == 0xB1 && (d[3] & 0xff) == 0xFA) return "IL2CPP metadata";
        }
        return "Binary";
    }

    private static String hex(byte[] d) {
        int n = Math.min(d.length, HEX_CAP);
        StringBuilder sb = new StringBuilder(n * 4 + 64);
        for (int off = 0; off < n; off += 16) {
            sb.append(String.format(Locale.US, "%08x  ", off));
            StringBuilder ascii = new StringBuilder(16);
            for (int i = 0; i < 16; i++) {
                if (off + i < n) {
                    int b = d[off + i] & 0xff;
                    sb.append(String.format(Locale.US, "%02x ", b));
                    ascii.append(b >= 0x20 && b < 0x7f ? (char) b : '·');
                } else {
                    sb.append("   ");
                }
                if (i == 7) sb.append(' ');
            }
            sb.append(' ').append(ascii).append('\n');
        }
        if (d.length > n) sb.append("…");
        return sb.toString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        exec.execute(() -> {
            String msg;
            try (ZipFile z = new ZipFile(apk);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Can't open destination");
                ZipEntry ze = z.getEntry(entry);
                try (InputStream in = z.getInputStream(ze)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                msg = "Saved ✓";
            } catch (Exception e) {
                msg = "Save failed: " + e.getMessage();
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
}
