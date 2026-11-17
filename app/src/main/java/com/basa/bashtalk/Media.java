package com.basa.bashtalk;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Pomoćne funkcije za zvuk (µ-law, WAV) i fajlove medija. */
public final class Media {

    private Media() { }

    static final short[] ULAW_DEC = new short[256];
    static {
        for (int i = 0; i < 256; i++) {
            int x = ~i & 0xff;
            int sign = x & 0x80;
            int exp = (x >> 4) & 7;
            int man = x & 0x0f;
            int s = (((man << 3) + 0x84) << exp) - 0x84;
            ULAW_DEC[i] = (short) (sign != 0 ? -s : s);
        }
    }

    static byte ulaw(int pcm) {
        int sign = (pcm >> 8) & 0x80;
        if (sign != 0) pcm = -pcm;
        if (pcm > 32635) pcm = 32635;
        pcm += 0x84;
        int exp = 7;
        for (int mask = 0x4000; (pcm & mask) == 0 && exp > 0; exp--, mask >>= 1) { }
        int man = (pcm >> (exp + 3)) & 0x0f;
        return (byte) ~(sign | (exp << 4) | man);
    }

    static File dir(Context c, String sub) {
        File d = new File(c.getFilesDir(), sub);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String url(File f) { return "file://" + f.getAbsolutePath(); }

    /** µ-law bajtovi → WAV (PCM16 mono) fajl. */
    static void writeWavFromUlaw(File f, byte[] ulaw, int off, int len, int rate) throws Exception {
        int dataLen = len * 2;
        try (OutputStream o = new FileOutputStream(f)) {
            byte[] h = new byte[44];
            put(h, 0, "RIFF");
            le32(h, 4, 36 + dataLen);
            put(h, 8, "WAVE");
            put(h, 12, "fmt ");
            le32(h, 16, 16);
            le16(h, 20, 1);
            le16(h, 22, 1);
            le32(h, 24, rate);
            le32(h, 28, rate * 2);
            le16(h, 32, 2);
            le16(h, 34, 16);
            put(h, 36, "data");
            le32(h, 40, dataLen);
            o.write(h);
            byte[] buf = new byte[4096];
            int bi = 0;
            for (int i = 0; i < len; i++) {
                short s = ULAW_DEC[ulaw[off + i] & 0xff];
                buf[bi++] = (byte) s;
                buf[bi++] = (byte) (s >> 8);
                if (bi == buf.length) { o.write(buf); bi = 0; }
            }
            if (bi > 0) o.write(buf, 0, bi);
        }
    }

    static void writeFile(File f, byte[] b) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) { o.write(b); }
    }

    private static void put(byte[] h, int o, String s) { for (int i = 0; i < 4; i++) h[o + i] = (byte) s.charAt(i); }
    private static void le32(byte[] h, int o, int v) { h[o] = (byte) v; h[o + 1] = (byte) (v >> 8); h[o + 2] = (byte) (v >> 16); h[o + 3] = (byte) (v >> 24); }
    private static void le16(byte[] h, int o, int v) { h[o] = (byte) v; h[o + 1] = (byte) (v >> 8); }

    static String hex(long v) { return String.format("%016x", v); }
}
