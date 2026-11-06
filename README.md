# APK Scope

An app inspector for Android that works **without root**. Pick any installed app and see:

- **Overview**: detected game engine (Unity Mono/IL2CPP, Godot, Cocos, Flutter, React Native, HTML5, GameMaker, Unreal, plain Java...), where the game logic lives, how hard it is to mod, and which PC tools to use. It also shows "value hunt" counts for names like coin, gold and gem, plus app info, permissions, and export.
- **Manifest**: the decoded `AndroidManifest.xml` with syntax colours.
- **Classes**: every Java/Kotlin class in the DEX files, searchable.
- **Strings**: the full DEX string table, including field and method names like `addCoins`, with quick-filter chips.
- **Files**: every file in the APK. Tap one to view it as decoded XML, text, or hex, and save it out (for example `Assembly-CSharp.dll`, `.lua` scripts, `index.android.bundle`).
- **Export**: saves the APK, or an `.apks` bundle for split apps, to your storage.

## Why it can't edit values on the phone

Without root, Android stops one app from reading another app's memory or its private save files. Changing values therefore means patching the APK on a PC. The Overview tab tells you which tool fits each engine.

## Build the APK (no Android Studio needed)

1. Create a new GitHub repo and upload the contents of this folder, including the hidden `.github` folder.
2. GitHub builds the APK automatically. Open **Actions → Build APK → the latest run → Artifacts → APKScope-apk**.
3. Unzip it, copy `app-debug.apk` to your phone, and install it. You'll need to allow installs from that source.

You can also open the folder in Android Studio and press Run.

## Typical modding flow (offline single-player games)

| Engine | What to do on a PC |
|---|---|
| Unity · Mono | Export `Assembly-CSharp.dll` from the Files tab, then edit it in **dnSpyEx** (Edit Method → C#), put it back, and re-sign |
| Unity · IL2CPP | Run **Il2CppDumper** on `libil2cpp.so` + `global-metadata.dat`, then patch the method in Ghidra or a hex editor |
| Java / Kotlin / libGDX | Read the code with **jadx-gui**, then `apktool d`, edit the smali, and `apktool b` |
| HTML5 / React Native / Lua | Edit the JS or Lua text directly, then repack |

After patching, run `zipalign -p 4 in.apk out.apk`, then `apksigner sign --ks my.keystore out.apk`. Uninstall the original first, because the signature changed. That also deletes its save data.

This won't work on games that keep coins or purchases on a server, or that check their own signature.
