package com.max.apkscope;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Opens an app's APK(s) and pulls out everything the inspector shows. */
final class ApkAnalyzer {
    private ApkAnalyzer() {}

    interface Progress { void on(String message); }

    static final class Entry {
        String name;
        long size;
        long compressed;
        String apkPath;
        String apkName;
    }

    static final class Engine {
        String name;
        int color;
        String summary;
        String logic;
        String tools;
        int difficulty; // 1 easy .. 5 very hard

        Engine(String name, int color, int difficulty, String summary, String logic, String tools) {
            this.name = name;
            this.color = color;
            this.difficulty = difficulty;
            this.summary = summary;
            this.logic = logic;
            this.tools = tools;
        }
    }

    static final class Result {
        final List<Entry> entries = new ArrayList<>();
        String manifest = "(AndroidManifest.xml not found)";
        final List<String> classes = new ArrayList<>();
        final List<String> strings = new ArrayList<>();
        final Set<String> abis = new TreeSet<>();
        final Set<String> nativeLibs = new TreeSet<>();
        final Map<String, Integer> keywordHits = new LinkedHashMap<>();
        int dexCount;
        long totalSize;
        float obfuscation;
        Engine engine;
        String error;
    }

    static final String[] KEYWORDS = {
            "coin", "gold", "gem", "diamond", "money", "cash", "crystal", "energy",
            "life", "health", "score", "level", "xp", "premium", "unlock", "vip", "noads", "save"
    };

    private static final long MAX_DEX_BYTES = 256L * 1024 * 1024;

    static Result analyze(List<String> apkPaths, Progress progress) {
        Result r = new Result();
        Set<String> classSet = new HashSet<>();
        Set<String> stringSet = new HashSet<>();
        Set<String> names = new HashSet<>();

        for (int idx = 0; idx < apkPaths.size(); idx++) {
            String path = apkPaths.get(idx);
            File f = new File(path);
            r.totalSize += f.length();
            progress.on("Opening " + f.getName() + "…");
            try (ZipFile z = new ZipFile(f)) {
                List<ZipEntry> dexes = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    String n = ze.getName();
                    Entry e = new Entry();
                    e.name = n;
                    e.size = ze.getSize();
                    e.compressed = ze.getCompressedSize();
                    e.apkPath = path;
                    e.apkName = f.getName();
                    r.entries.add(e);
                    names.add(n);

                    if (n.matches("classes\\d*\\.dex")) dexes.add(ze);
                    if (idx == 0 && n.equals("AndroidManifest.xml")) {
                        progress.on("Decoding manifest…");
                        r.manifest = AxmlDecoder.decode(readAll(z, ze, 16L * 1024 * 1024));
                    }
                    if (n.startsWith("lib/") && n.endsWith(".so")) {
                        String[] parts = n.split("/");
                        if (parts.length >= 3) r.abis.add(parts[1]);
                        r.nativeLibs.add(parts[parts.length - 1]);
                    }
                }
                for (ZipEntry dz : dexes) {
                    progress.on("Reading " + dz.getName() + "…");
                    byte[] d = readAll(z, dz, MAX_DEX_BYTES);
                    if (DexReader.read(d, classSet, stringSet) >= 0) r.dexCount++;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError ex) {
                r.error = f.getName() + ": " + ex.getClass().getSimpleName()
                        + (ex.getMessage() != null ? " — " + ex.getMessage() : "");
            }
        }

        progress.on("Sorting…");
        r.classes.addAll(classSet);
        Collections.sort(r.classes);
        r.strings.addAll(stringSet);
        Collections.sort(r.strings, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(r.entries, (a, b) -> a.name.compareTo(b.name));

        // Obfuscation estimate: share of classes with 1–2 letter names (R8/ProGuard style)
        int shortNames = 0, counted = 0;
        for (String c : r.classes) {
            String simple = c.substring(c.lastIndexOf('.') + 1);
            if (simple.startsWith("R$") || simple.equals("R") || simple.equals("BuildConfig")) continue;
            int dollar = simple.indexOf('$');
            if (dollar > 0) simple = simple.substring(0, dollar);
            counted++;
            if (simple.length() <= 2) shortNames++;
        }
        r.obfuscation = counted == 0 ? 0f : shortNames / (float) counted;

        progress.on("Scanning for value names…");
        List<String> lower = new ArrayList<>(r.strings.size());
        for (String s : r.strings) lower.add(s.toLowerCase(Locale.ROOT));
        for (String k : KEYWORDS) {
            int hits = 0;
            for (String s : lower) if (s.contains(k)) hits++;
            r.keywordHits.put(k, hits);
        }

        r.engine = detectEngine(names, r);
        return r;
    }

    static byte[] readAll(ZipFile z, ZipEntry e, long cap) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            long declared = e.getSize();
            int initial = (int) Math.min(Math.max(declared, 8192), Math.min(cap, Integer.MAX_VALUE - 16));
            ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                int take = (int) Math.min(n, cap - total);
                out.write(buf, 0, take);
                total += take;
                if (total >= cap) break;
            }
            return out.toByteArray();
        }
    }

    private static boolean hasSuffix(Set<String> names, String suffix) {
        for (String n : names) if (n.endsWith(suffix)) return true;
        return false;
    }

    private static boolean hasPrefix(Set<String> names, String prefix) {
        for (String n : names) if (n.startsWith(prefix)) return true;
        return false;
    }

    private static boolean hasLib(Result r, String lib) {
        return r.nativeLibs.contains(lib);
    }

    static Engine detectEngine(Set<String> names, Result r) {
        boolean unity = hasLib(r, "libunity.so") || hasPrefix(names, "assets/bin/Data/");
        if (unity) {
            if (hasLib(r, "libil2cpp.so")) {
                return new Engine("Unity · IL2CPP", Ui.ORANGE, 4,
                        "Unity game where the C# code was compiled to native ARM code.",
                        "Game logic is machine code inside lib/<abi>/libil2cpp.so. Class, field and method names are kept in "
                                + "assets/bin/Data/Managed/Metadata/global-metadata.dat (unless the developer encrypted it).",
                        "On a PC: run Il2CppDumper on libil2cpp.so + global-metadata.dat to get every class, field and method "
                                + "offset (look for things like CoinManager / AddCoins / get_Gold). Then patch the method in libil2cpp.so "
                                + "with Ghidra or a hex editor (e.g. make a getter return a big number), put it back in the APK and re-sign.");
            }
            if (hasSuffix(names, "Managed/Assembly-CSharp.dll")) {
                return new Engine("Unity · Mono", Ui.GREEN, 2,
                        "Unity game that ships its C# code as .NET DLLs — the easiest kind to mod.",
                        "All game logic is in assets/bin/Data/Managed/Assembly-CSharp.dll (plus maybe Assembly-CSharp-firstpass.dll).",
                        "On a PC: open Assembly-CSharp.dll in dnSpyEx, search for coin / gold / currency classes, right-click a "
                                + "method → Edit Method (C#), change the value, compile, save the DLL. Put it back in the APK, re-sign, install. "
                                + "You can grab the DLL straight from the Files tab here.");
            }
            return new Engine("Unity", Ui.ORANGE, 3,
                    "Unity game (scripting backend not recognised).",
                    "Look in assets/bin/Data/ and lib/<abi>/ in the Files tab.",
                    "If you see libil2cpp.so use Il2CppDumper; if you see Managed/*.dll use dnSpyEx.");
        }
        if (hasLib(r, "libUE4.so") || hasLib(r, "libUnreal.so")) {
            return new Engine("Unreal Engine", Ui.PINK, 5,
                    "Unreal Engine game.",
                    "Logic is native C++ in libUE4.so/libUnreal.so; blueprints and data are in .pak files (often in an OBB download).",
                    "Very hard without root: needs Ghidra on a ~100 MB native library plus UE pak tools. Not a good first target.");
        }
        if (hasLib(r, "libgodot_android.so") || hasSuffix(names, ".pck")) {
            return new Engine("Godot", Ui.CYAN, 2,
                    "Godot game.",
                    "GDScript code and scenes are packed in a .pck file under assets/ (or appended to the library).",
                    "On a PC: GDRE Tools (gdsdecomp) can recover the whole project from the .pck, including scripts. Edit, re-export, re-sign. "
                            + "Encrypted .pck files need the key from the library first.");
        }
        if (hasLib(r, "libcocos2dlua.so") || hasPrefix(names, "assets/src/") && (hasSuffix(names, ".lua") || hasSuffix(names, ".luac"))) {
            return new Engine("Cocos2d-x · Lua", Ui.YELLOW, 2,
                    "Cocos2d-x game scripted in Lua.",
                    "Scripts live in assets/src/ (and assets/res/). Plain .lua files are readable right here in the Files tab; "
                            + ".luac files are compiled and often XXTEA-encrypted.",
                    "Plain .lua: edit the numbers directly, repack, re-sign. Encrypted .luac: the key/sign is usually a string "
                            + "inside libcocos2dlua.so, then decrypt and decompile with unluac.");
        }
        if (hasLib(r, "libcocos2djs.so") || hasLib(r, "libcocos.so")) {
            return new Engine("Cocos · JavaScript", Ui.YELLOW, 2,
                    "Cocos game scripted in JavaScript.",
                    "JS lives in assets/ as .js (readable) or .jsc (XXTEA-encrypted + gzipped).",
                    "Readable .js: edit and repack. .jsc: find the XXTEA key in libcocos2djs.so, decrypt, edit, re-encrypt.");
        }
        if (hasLib(r, "libflutter.so")) {
            return new Engine("Flutter", Ui.CYAN, 5,
                    "Flutter app (Dart).",
                    "Dart code is AOT-compiled to native code in lib/<abi>/libapp.so.",
                    "Hard: tools like blutter can recover some structure from libapp.so, then you patch native code. Rarely worth it.");
        }
        if (names.contains("assets/index.android.bundle") || hasLib(r, "libreactnativejni.so")) {
            return new Engine("React Native", Ui.CYAN, 2,
                    "React Native app (JavaScript).",
                    "All app logic is in assets/index.android.bundle — plain JS text, or Hermes bytecode if it starts with binary data.",
                    "Plain JS: open it in the Files tab, search for the value, edit on PC, repack, re-sign. Hermes: hermes-dec to read it.");
        }
        if (hasPrefix(names, "assets/www/") || hasPrefix(names, "assets/public/index.html")) {
            return new Engine("HTML5 · Cordova/Capacitor", Ui.GREEN, 1,
                    "Web app/game wrapped in a WebView.",
                    "Everything is HTML/JS in assets/www/ or assets/public/ — readable in the Files tab.",
                    "Edit the JS (search for coins/score variables), repack, re-sign. Easiest case after Mono Unity.");
        }
        if (hasLib(r, "libyoyo.so")) {
            return new Engine("GameMaker", Ui.PINK, 3,
                    "GameMaker game.",
                    "If assets/game.droid exists it holds the bytecode + data; otherwise it's a YYC build compiled into libyoyo.so.",
                    "game.droid: UndertaleModTool can open and edit it (variables, scripts). YYC builds are native and much harder.");
        }
        if (hasLib(r, "libdmengine.so")) {
            return new Engine("Defold", Ui.CYAN, 3,
                    "Defold game (Lua).",
                    "Lua scripts are compiled into game.arcd / game.arci archives in assets/.",
                    "Extract the archive with community Defold unpackers, decompile the Lua, edit, repack.");
        }
        if (hasLib(r, "libmonodroid.so") || hasLib(r, "libmonosgen-2.0.so")) {
            return new Engine("Xamarin / .NET", Ui.GREEN, 2,
                    ".NET app (Xamarin or MAUI).",
                    "C# code is in .NET assemblies under assemblies/ or packed in assemblies.blob.",
                    "Unpack assemblies.blob (pyxamstore), then edit the DLLs in dnSpyEx like a Mono Unity game.");
        }
        if (hasLib(r, "libgdx.so")) {
            return new Engine("libGDX", Ui.GREEN, 2,
                    "libGDX game (Java/Kotlin).",
                    "Game logic is regular Java bytecode in classes*.dex — the Classes tab lists it.",
                    "On a PC: read it with jadx, edit the matching smali after `apktool d`, rebuild with `apktool b`, re-sign.");
        }
        return new Engine("Native Android (Java/Kotlin)", Ui.ACCENT, 2,
                "Regular Android app — logic is in the DEX bytecode.",
                "Everything is in classes*.dex. The Classes and Strings tabs show what's inside"
                        + (r.nativeLibs.isEmpty() ? "." : "; there are also native libraries, which may hold some logic."),
                "On a PC: read the code with jadx-gui, then `apktool d` the APK, edit the .smali file, `apktool b`, zipalign + apksigner, install.");
    }
}
