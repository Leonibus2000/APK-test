package com.max.apkscope;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes Android binary XML (AndroidManifest.xml and compiled res/*.xml inside an APK)
 * back into readable XML text.
 */
final class AxmlDecoder {
    private AxmlDecoder() {}

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_RES_MAP = 0x0180;
    private static final int CHUNK_NS_START = 0x0100;
    private static final int CHUNK_NS_END = 0x0101;
    private static final int CHUNK_TAG_START = 0x0102;
    private static final int CHUNK_TAG_END = 0x0103;
    private static final int CHUNK_TEXT = 0x0104;

    private static final Map<Integer, String> ANDROID_ATTRS = new HashMap<>();
    static {
        ANDROID_ATTRS.put(0x01010000, "theme");
        ANDROID_ATTRS.put(0x01010001, "label");
        ANDROID_ATTRS.put(0x01010002, "icon");
        ANDROID_ATTRS.put(0x01010003, "name");
        ANDROID_ATTRS.put(0x01010006, "permission");
        ANDROID_ATTRS.put(0x0101000f, "debuggable");
        ANDROID_ATTRS.put(0x01010010, "exported");
        ANDROID_ATTRS.put(0x01010011, "process");
        ANDROID_ATTRS.put(0x01010018, "authorities");
        ANDROID_ATTRS.put(0x0101001d, "launchMode");
        ANDROID_ATTRS.put(0x0101001e, "screenOrientation");
        ANDROID_ATTRS.put(0x0101001f, "configChanges");
        ANDROID_ATTRS.put(0x01010020, "description");
        ANDROID_ATTRS.put(0x01010024, "value");
        ANDROID_ATTRS.put(0x01010025, "resource");
        ANDROID_ATTRS.put(0x01010026, "mimeType");
        ANDROID_ATTRS.put(0x01010027, "scheme");
        ANDROID_ATTRS.put(0x01010028, "host");
        ANDROID_ATTRS.put(0x0101020c, "minSdkVersion");
        ANDROID_ATTRS.put(0x0101021b, "versionCode");
        ANDROID_ATTRS.put(0x0101021c, "versionName");
        ANDROID_ATTRS.put(0x01010270, "targetSdkVersion");
        ANDROID_ATTRS.put(0x01010271, "maxSdkVersion");
        ANDROID_ATTRS.put(0x01010280, "allowBackup");
        ANDROID_ATTRS.put(0x0101028e, "required");
        ANDROID_ATTRS.put(0x010102b7, "installLocation");
        ANDROID_ATTRS.put(0x010103af, "hardwareAccelerated");
        ANDROID_ATTRS.put(0x0101040a, "largeHeap");
        ANDROID_ATTRS.put(0x01010473, "supportsRtl");
        ANDROID_ATTRS.put(0x010104ec, "usesCleartextTraffic");
        ANDROID_ATTRS.put(0x01010527, "networkSecurityConfig");
        ANDROID_ATTRS.put(0x0101052c, "roundIcon");
        ANDROID_ATTRS.put(0x01010572, "compileSdkVersion");
        ANDROID_ATTRS.put(0x01010573, "compileSdkVersionCodename");
        ANDROID_ATTRS.put(0x01010591, "extractNativeLibs");
        ANDROID_ATTRS.put(0x0101054c, "appComponentFactory");
    }

    static boolean isAxml(byte[] b) {
        return b != null && b.length > 8
                && (b[0] & 0xff) == 0x03 && b[1] == 0x00
                && (b[2] & 0xff) == 0x08 && b[3] == 0x00;
    }

    static String decode(byte[] b) {
        if (!isAxml(b)) return "(not a binary XML file)";
        String[] strings = new String[0];
        int[] resIds = new int[0];
        Map<String, String> nsPrefix = new HashMap<>();
        List<String[]> pendingNs = new ArrayList<>();
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        int depth = 0;
        int pos = u16(b, 2);

        try {
            while (pos + 8 <= b.length) {
                int type = u16(b, pos);
                int hsz = u16(b, pos + 2);
                int size = i32(b, pos + 4);
                if (size < 8 || pos + size > b.length) break;

                switch (type) {
                    case CHUNK_STRING_POOL:
                        strings = readPool(b, pos);
                        break;
                    case CHUNK_RES_MAP: {
                        int n = (size - hsz) / 4;
                        resIds = new int[n];
                        for (int i = 0; i < n; i++) resIds[i] = i32(b, pos + hsz + i * 4);
                        break;
                    }
                    case CHUNK_NS_START: {
                        String prefix = str(strings, i32(b, pos + 16));
                        String uri = str(strings, i32(b, pos + 20));
                        nsPrefix.put(uri, prefix);
                        pendingNs.add(new String[]{prefix, uri});
                        break;
                    }
                    case CHUNK_NS_END:
                        break;
                    case CHUNK_TAG_START: {
                        int ns = i32(b, pos + 16);
                        String name = str(strings, i32(b, pos + 20));
                        int attrStart = u16(b, pos + 24);
                        int attrSize = u16(b, pos + 26);
                        int attrCount = u16(b, pos + 28);
                        if (attrSize == 0) attrSize = 20;

                        indent(out, depth);
                        out.append('<').append(withPrefix(nsPrefix, strings, ns, name));
                        String pad = spaces(depth * 4 + 4);
                        for (String[] p : pendingNs) {
                            out.append('\n').append(pad).append("xmlns:").append(p[0])
                                    .append("=\"").append(esc(p[1])).append('"');
                        }
                        pendingNs.clear();

                        // attrStart is relative to the tag extension, which follows the node header
                        int a = pos + hsz + attrStart;
                        for (int i = 0; i < attrCount; i++) {
                            int off = a + i * attrSize;
                            if (off + 20 > b.length) break;
                            int ans = i32(b, off);
                            int aname = i32(b, off + 4);
                            int raw = i32(b, off + 8);
                            int dtype = b[off + 15] & 0xff;
                            int data = i32(b, off + 16);
                            String attrName = attrName(strings, resIds, aname);
                            String prefix = ans != -1 ? nsPrefix.get(str(strings, ans)) : null;
                            out.append('\n').append(pad);
                            if (prefix != null && !prefix.isEmpty()) out.append(prefix).append(':');
                            out.append(attrName).append("=\"")
                                    .append(esc(value(strings, dtype, raw, data))).append('"');
                        }

                        // Peek: if the very next chunk closes this tag, self-close it.
                        int next = pos + size;
                        if (next + 8 <= b.length && u16(b, next) == CHUNK_TAG_END) {
                            int nsize = i32(b, next + 4);
                            out.append(" />\n");
                            pos = next + Math.max(nsize, 8);
                            continue;
                        }
                        out.append(">\n");
                        depth++;
                        break;
                    }
                    case CHUNK_TAG_END: {
                        depth = Math.max(0, depth - 1);
                        int ns = i32(b, pos + 16);
                        String name = str(strings, i32(b, pos + 20));
                        indent(out, depth);
                        out.append("</").append(withPrefix(nsPrefix, strings, ns, name)).append(">\n");
                        break;
                    }
                    case CHUNK_TEXT: {
                        String t = str(strings, i32(b, pos + 16));
                        if (!t.trim().isEmpty()) {
                            indent(out, depth);
                            out.append(esc(t.trim())).append('\n');
                        }
                        break;
                    }
                    default:
                        break;
                }
                pos += size;
            }
        } catch (RuntimeException e) {
            out.append("\n<!-- decode stopped: ").append(e.getClass().getSimpleName()).append(" -->\n");
        }
        return out.toString();
    }

    private static String withPrefix(Map<String, String> nsPrefix, String[] s, int ns, String name) {
        if (ns == -1) return name;
        String p = nsPrefix.get(str(s, ns));
        return (p == null || p.isEmpty()) ? name : p + ":" + name;
    }

    private static String attrName(String[] s, int[] resIds, int idx) {
        String n = str(s, idx);
        if (!n.isEmpty()) return n;
        if (idx >= 0 && idx < resIds.length) {
            String known = ANDROID_ATTRS.get(resIds[idx]);
            if (known != null) return known;
            return String.format(Locale.US, "attr_0x%08x", resIds[idx]);
        }
        return "attr_" + idx;
    }

    private static final float[] RADIX = {1f / 256f, 1f / 32768f, 1f / 8388608f, 1f / 2147483648f};
    private static final String[] DIM_UNITS = {"px", "dp", "sp", "pt", "in", "mm", "", ""};
    private static final String[] FRAC_UNITS = {"%", "%p", "", "", "", "", "", ""};

    private static float complex(int data) {
        return (data & 0xffffff00) * RADIX[(data >> 4) & 3];
    }

    private static String num(float f) {
        if (f == (long) f) return Long.toString((long) f);
        return String.format(Locale.US, "%.3f", f).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String value(String[] s, int type, int raw, int data) {
        switch (type) {
            case 0x03: return raw >= 0 ? str(s, raw) : str(s, data);
            case 0x01:
                if (data == 0) return "@null";
                return ((data >>> 24) == 0x01 ? "@android:0x" : "@0x") + String.format(Locale.US, "%08x", data);
            case 0x02: return "?0x" + String.format(Locale.US, "%08x", data);
            case 0x04: return num(Float.intBitsToFloat(data));
            case 0x05: return num(complex(data)) + DIM_UNITS[data & 0xf & 7];
            case 0x06: return num(complex(data) * 100f) + FRAC_UNITS[data & 0xf & 7];
            case 0x10: return Integer.toString(data);
            case 0x11: return "0x" + Integer.toHexString(data);
            case 0x12: return data != 0 ? "true" : "false";
            case 0x1c: case 0x1d: case 0x1e: case 0x1f:
                return String.format(Locale.US, "#%08X", data);
            default:
                if (raw >= 0) return str(s, raw);
                return "0x" + Integer.toHexString(data);
        }
    }

    private static String[] readPool(byte[] b, int p) {
        int hsz = u16(b, p + 2);
        int count = i32(b, p + 8);
        int flags = i32(b, p + 16);
        int stringsStart = i32(b, p + 20);
        boolean utf8 = (flags & 0x100) != 0;
        if (count < 0 || count > 2_000_000) count = 0;
        String[] s = new String[count];
        int offs = p + hsz;
        for (int i = 0; i < count; i++) {
            try {
                int so = p + stringsStart + i32(b, offs + i * 4);
                if (utf8) {
                    int l1 = b[so] & 0xff;
                    so += (l1 & 0x80) != 0 ? 2 : 1;
                    int blen = b[so] & 0xff;
                    if ((blen & 0x80) != 0) {
                        blen = ((blen & 0x7f) << 8) | (b[so + 1] & 0xff);
                        so += 2;
                    } else {
                        so += 1;
                    }
                    s[i] = new String(b, so, blen, StandardCharsets.UTF_8);
                } else {
                    int len = u16(b, so);
                    so += 2;
                    if ((len & 0x8000) != 0) {
                        len = ((len & 0x7fff) << 16) | u16(b, so);
                        so += 2;
                    }
                    s[i] = new String(b, so, len * 2, StandardCharsets.UTF_16LE);
                }
            } catch (RuntimeException e) {
                s[i] = "";
            }
        }
        return s;
    }

    private static String str(String[] s, int i) {
        if (i < 0 || i >= s.length || s[i] == null) return "";
        return s[i];
    }

    private static String esc(String s) {
        StringBuilder r = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': r.append("&amp;"); break;
                case '<': r.append("&lt;"); break;
                case '>': r.append("&gt;"); break;
                case '"': r.append("&quot;"); break;
                case '\n': r.append("&#10;"); break;
                default: r.append(c);
            }
        }
        return r.toString();
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("    ");
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    static int u16(byte[] b, int p) {
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8);
    }

    static int i32(byte[] b, int p) {
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 24);
    }
}
