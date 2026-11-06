package com.max.apkscope;

import java.util.Collection;

/** Minimal DEX parser: pulls out class names and the string table. */
final class DexReader {
    private DexReader() {}

    /** @return number of classes defined in this dex, or -1 if it isn't a dex file. */
    static int read(byte[] d, Collection<String> classesOut, Collection<String> stringsOut) {
        if (d == null || d.length < 0x70 || d[0] != 'd' || d[1] != 'e' || d[2] != 'x') return -1;
        int strSize = AxmlDecoder.i32(d, 0x38);
        int strOff = AxmlDecoder.i32(d, 0x3C);
        int typeSize = AxmlDecoder.i32(d, 0x40);
        int typeOff = AxmlDecoder.i32(d, 0x44);
        int cdSize = AxmlDecoder.i32(d, 0x60);
        int cdOff = AxmlDecoder.i32(d, 0x64);
        if (strSize < 0 || typeSize < 0 || cdSize < 0) return -1;

        String[] s = new String[strSize];
        for (int i = 0; i < strSize; i++) {
            int idPos = strOff + i * 4;
            if (idPos + 4 > d.length) break;
            int p = AxmlDecoder.i32(d, idPos);
            if (p < 0 || p >= d.length) { s[i] = ""; continue; }
            while (p < d.length && (d[p] & 0x80) != 0) p++; // skip uleb128 length
            p++;
            int e = p;
            while (e < d.length && d[e] != 0) e++;
            s[i] = mutf8(d, Math.min(p, d.length), e);
        }

        int[] types = new int[typeSize];
        for (int i = 0; i < typeSize; i++) {
            int pos = typeOff + i * 4;
            types[i] = pos + 4 <= d.length ? AxmlDecoder.i32(d, pos) : -1;
        }

        int defined = 0;
        for (int i = 0; i < cdSize; i++) {
            int pos = cdOff + i * 32;
            if (pos + 4 > d.length) break;
            int classIdx = AxmlDecoder.i32(d, pos);
            if (classIdx < 0 || classIdx >= typeSize) continue;
            int si = types[classIdx];
            if (si < 0 || si >= strSize || s[si] == null) continue;
            classesOut.add(descriptorToName(s[si]));
            defined++;
        }

        for (String str : s) {
            if (str == null || str.trim().isEmpty() || isNoise(str)) continue;
            stringsOut.add(str);
        }
        return defined;
    }

    static String descriptorToName(String desc) {
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }
        return desc;
    }

    /** Skips type descriptors and method "shorty" signatures, which are just clutter. */
    private static boolean isNoise(String s) {
        char c0 = s.charAt(0);
        if ((c0 == 'L' || c0 == '[') && s.endsWith(";") && s.indexOf(' ') < 0) return true;
        if (s.length() <= 12) {
            boolean shorty = true;
            for (int i = 0; i < s.length(); i++) {
                if ("VZBSCIJFDL".indexOf(s.charAt(i)) < 0) { shorty = false; break; }
            }
            if (shorty) return true;
        }
        return false;
    }

    private static String mutf8(byte[] d, int p, int end) {
        if (end <= p) return "";
        char[] out = new char[end - p];
        int n = 0;
        while (p < end) {
            int a = d[p++] & 0xff;
            if (a < 0x80) {
                out[n++] = (char) a;
            } else if ((a & 0xe0) == 0xc0 && p < end) {
                int b = d[p++] & 0x3f;
                out[n++] = (char) (((a & 0x1f) << 6) | b);
            } else if ((a & 0xf0) == 0xe0 && p + 1 < end) {
                int b = d[p++] & 0x3f;
                int c = d[p++] & 0x3f;
                out[n++] = (char) (((a & 0x0f) << 12) | (b << 6) | c);
            } else {
                out[n++] = '�';
            }
        }
        return new String(out, 0, n);
    }
}
