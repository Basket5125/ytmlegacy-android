package com.ytmlegacy.android.player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Repacks a fragmented (DASH) audio MP4 into a classic one: ftyp + moov (full sample tables) +
 * mdat. The audio samples are copied untouched; only the container changes. Needed for players
 * that cannot read fragments (Android 2.x, some vendor builds of 4.x).
 */
public final class Mp4Classic {

    private Mp4Classic() { }

    /** Growable int array: sample tables of an hour-long track stay small. */
    private static final class Ints {
        int[] data = new int[1024];
        int size;

        void add(int v) {
            if (size == data.length) {
                int[] bigger = new int[size * 2];
                System.arraycopy(data, 0, bigger, 0, size);
                data = bigger;
            }
            data[size++] = v;
        }
    }

    /** One run of contiguous samples (a trun) in the source file. */
    private static final class Run {
        long sourceOffset;
        long byteSize;
        int samples;
    }

    /** True when the file starts like a fragmented MP4 (has an mvex in its moov). */
    public static boolean isFragmented(File file) throws IOException {
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            long pos = 0;
            long len = in.length();
            while (pos + 8 <= len) {
                long[] h = header(in, pos, len);
                if (h == null) return false;
                String type = typeAt(in, pos + 4);
                if ("moov".equals(type)) {
                    byte[] moov = read(in, pos, (int) h[0]);
                    return find(moov, 8, moov.length, "mvex") != null;
                }
                if ("moof".equals(type)) return true;
                pos += h[0];
            }
            return false;
        } finally {
            in.close();
        }
    }

    public static void convert(File source, File target) throws IOException {
        RandomAccessFile in = new RandomAccessFile(source, "r");
        try {
            convert(in, target);
        } finally {
            in.close();
        }
    }

    private static void convert(RandomAccessFile in, File target) throws IOException {
        long len = in.length();
        byte[] moov = null;
        List<Run> runs = new ArrayList<Run>();
        Ints sizes = new Ints();
        Ints durations = new Ints();
        long[] trex = {0, 0}; // default duration, default size
        long pos = 0;
        while (pos + 8 <= len) {
            long[] h = header(in, pos, len);
            if (h == null) break;
            long size = h[0];
            String type = typeAt(in, pos + 4);
            if ("moov".equals(type)) {
                if (size > 4 * 1024 * 1024) throw new IOException("moov too large");
                moov = read(in, pos, (int) size);
                int[] mvex = find(moov, 8, moov.length, "mvex");
                if (mvex != null) {
                    int[] t = find(moov, mvex[0] + 8, mvex[0] + mvex[1], "trex");
                    if (t != null) {
                        // trex: fullbox(4) track_ID(4) desc_index(4) duration(4) size(4) flags(4)
                        trex[0] = u32(moov, t[0] + 8 + 12);
                        trex[1] = u32(moov, t[0] + 8 + 16);
                    }
                }
            } else if ("moof".equals(type)) {
                if (size > 4 * 1024 * 1024) throw new IOException("moof too large");
                byte[] moof = read(in, pos, (int) size);
                readMoof(moof, pos, trex, runs, sizes, durations);
            }
            pos += size;
        }
        if (moov == null) throw new IOException("no moov");
        if (runs.isEmpty()) throw new IOException("no fragments");

        int[] trak = find(moov, 8, moov.length, "trak");
        if (trak == null) throw new IOException("no trak");
        int[] mdia = find(moov, trak[0] + 8, trak[0] + trak[1], "mdia");
        int[] mdhd = mdia == null ? null : find(moov, mdia[0] + 8, mdia[0] + mdia[1], "mdhd");
        int[] mvhd = find(moov, 8, moov.length, "mvhd");
        if (mdhd == null || mvhd == null) throw new IOException("incomplete moov");
        long mediaScale = timescale(moov, mdhd[0]);
        long movieScale = timescale(moov, mvhd[0]);
        long mediaDuration = 0;
        for (int i = 0; i < durations.size; i++) mediaDuration += durations.data[i] & 0xffffffffL;
        long movieDuration = mediaScale == 0 ? 0 : mediaDuration * movieScale / mediaScale;

        byte[] ftyp = ftyp();
        // Build moov once with zero offsets to learn its size, then again with real offsets.
        long[] offsets = new long[runs.size()];
        byte[] newMoov = buildMoov(moov, trak, sizes, durations, runs, offsets, mediaDuration, movieDuration);
        long dataStart = ftyp.length + newMoov.length + 8;
        long mdatPayload = 0;
        for (int i = 0; i < runs.size(); i++) {
            offsets[i] = dataStart + mdatPayload;
            mdatPayload += runs.get(i).byteSize;
        }
        if (dataStart + mdatPayload > 0xffffffffL) throw new IOException("file too large");
        newMoov = buildMoov(moov, trak, sizes, durations, runs, offsets, mediaDuration, movieDuration);

        File tmp = new File(target.getPath() + ".tmp");
        OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(tmp), 65536);
        boolean ok = false;
        try {
            out.write(ftyp);
            out.write(newMoov);
            byte[] mdatHeader = new byte[8];
            putU32(mdatHeader, 0, mdatPayload + 8);
            putType(mdatHeader, 4, "mdat");
            out.write(mdatHeader);
            byte[] buffer = new byte[65536];
            for (Run run : runs) {
                in.seek(run.sourceOffset);
                long left = run.byteSize;
                while (left > 0) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (n < 0) throw new IOException("source ended inside a fragment");
                    out.write(buffer, 0, n);
                    left -= n;
                }
            }
            out.flush();
            ok = true;
        } finally {
            out.close();
            if (!ok) tmp.delete();
        }
        if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
        if (!tmp.renameTo(target)) throw new IOException("cannot rename " + tmp);
    }

    private static void readMoof(byte[] moof, long moofStart, long[] trex, List<Run> runs,
                                 Ints sizes, Ints durations) throws IOException {
        int pos = 8;
        while (pos + 8 <= moof.length) {
            int size = (int) u32(moof, pos);
            if (size < 8 || pos + size > moof.length) break;
            if ("traf".equals(type(moof, pos + 4))) readTraf(moof, pos, size, moofStart, trex, runs, sizes, durations);
            pos += size;
        }
    }

    private static void readTraf(byte[] b, int traf, int trafSize, long moofStart, long[] trex,
                                 List<Run> runs, Ints sizes, Ints durations) throws IOException {
        int[] tfhd = find(b, traf + 8, traf + trafSize, "tfhd");
        if (tfhd == null) throw new IOException("no tfhd");
        int p = tfhd[0] + 8;
        int flags = (int) (u32(b, p) & 0xffffff);
        p += 8; // version/flags + track_ID
        long base = moofStart; // default-base-is-moof, and the implicit base of the first traf
        if ((flags & 0x1) != 0) {
            base = u64(b, p);
            p += 8;
        }
        if ((flags & 0x2) != 0) p += 4;
        long defDuration = trex[0];
        long defSize = trex[1];
        if ((flags & 0x8) != 0) {
            defDuration = u32(b, p);
            p += 4;
        }
        if ((flags & 0x10) != 0) {
            defSize = u32(b, p);
            p += 4;
        }
        long next = -1;
        int pos = traf + 8;
        while (pos + 8 <= traf + trafSize) {
            int size = (int) u32(b, pos);
            if (size < 8) break;
            if ("trun".equals(type(b, pos + 4))) {
                int q = pos + 8;
                int tf = (int) (u32(b, q) & 0xffffff);
                q += 4;
                int count = (int) u32(b, q);
                q += 4;
                long dataStart = next >= 0 ? next : base;
                if ((tf & 0x1) != 0) {
                    dataStart = base + (int) u32(b, q); // signed offset
                    q += 4;
                }
                if ((tf & 0x4) != 0) q += 4; // first-sample-flags
                Run run = new Run();
                run.sourceOffset = dataStart;
                run.samples = count;
                for (int i = 0; i < count; i++) {
                    long d = defDuration;
                    long s = defSize;
                    if ((tf & 0x100) != 0) {
                        d = u32(b, q);
                        q += 4;
                    }
                    if ((tf & 0x200) != 0) {
                        s = u32(b, q);
                        q += 4;
                    }
                    if ((tf & 0x400) != 0) q += 4;
                    if ((tf & 0x800) != 0) q += 4;
                    durations.add((int) d);
                    sizes.add((int) s);
                    run.byteSize += s;
                }
                if (count > 0) runs.add(run);
                next = dataStart + run.byteSize;
            }
            pos += size;
        }
    }

    // ------------------------------------------------------------------ output boxes

    private static byte[] ftyp() {
        byte[] b = new byte[28];
        putU32(b, 0, 28);
        putType(b, 4, "ftyp");
        putType(b, 8, "M4A ");
        putU32(b, 12, 0);
        putType(b, 16, "M4A ");
        putType(b, 20, "mp42");
        putType(b, 24, "isom");
        return b;
    }

    private static byte[] buildMoov(byte[] moov, int[] trak, Ints sizes, Ints durations, List<Run> runs,
                                    long[] offsets, long mediaDuration, long movieDuration) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int pos = 8;
        while (pos + 8 <= moov.length) {
            int size = (int) u32(moov, pos);
            if (size < 8 || pos + size > moov.length) break;
            String type = type(moov, pos + 4);
            if ("mvhd".equals(type)) {
                byte[] mvhd = slice(moov, pos, size);
                setDuration(mvhd, 0, movieDuration, true);
                body.write(mvhd);
            } else if ("trak".equals(type) && pos == trak[0]) {
                body.write(buildTrak(slice(moov, pos, size), sizes, durations, runs, offsets,
                        mediaDuration, movieDuration));
            } else if (!"mvex".equals(type) && !"trak".equals(type)) {
                body.write(moov, pos, size); // udta, etc.
            }
            pos += size;
        }
        return box("moov", body.toByteArray());
    }

    private static byte[] buildTrak(byte[] trak, Ints sizes, Ints durations, List<Run> runs, long[] offsets,
                                    long mediaDuration, long movieDuration) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int pos = 8;
        while (pos + 8 <= trak.length) {
            int size = (int) u32(trak, pos);
            if (size < 8 || pos + size > trak.length) break;
            String type = type(trak, pos + 4);
            if ("tkhd".equals(type)) {
                byte[] tkhd = slice(trak, pos, size);
                setDuration(tkhd, 0, movieDuration, false);
                body.write(tkhd);
            } else if ("mdia".equals(type)) {
                body.write(buildMdia(slice(trak, pos, size), sizes, durations, runs, offsets, mediaDuration));
            } else if (!"edts".equals(type)) {
                body.write(trak, pos, size);
            }
            pos += size;
        }
        return box("trak", body.toByteArray());
    }

    private static byte[] buildMdia(byte[] mdia, Ints sizes, Ints durations, List<Run> runs, long[] offsets,
                                    long mediaDuration) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int pos = 8;
        while (pos + 8 <= mdia.length) {
            int size = (int) u32(mdia, pos);
            if (size < 8 || pos + size > mdia.length) break;
            String type = type(mdia, pos + 4);
            if ("mdhd".equals(type)) {
                byte[] mdhd = slice(mdia, pos, size);
                setDuration(mdhd, 0, mediaDuration, true);
                body.write(mdhd);
            } else if ("minf".equals(type)) {
                byte[] minf = slice(mdia, pos, size);
                body.write(buildMinf(minf, sizes, durations, runs, offsets));
            } else {
                body.write(mdia, pos, size);
            }
            pos += size;
        }
        return box("mdia", body.toByteArray());
    }

    private static byte[] buildMinf(byte[] minf, Ints sizes, Ints durations, List<Run> runs, long[] offsets)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int pos = 8;
        while (pos + 8 <= minf.length) {
            int size = (int) u32(minf, pos);
            if (size < 8 || pos + size > minf.length) break;
            if ("stbl".equals(type(minf, pos + 4))) {
                int[] stsd = find(minf, pos + 8, pos + size, "stsd");
                if (stsd == null) throw new IOException("no stsd");
                body.write(buildStbl(slice(minf, stsd[0], stsd[1]), sizes, durations, runs, offsets));
            } else {
                body.write(minf, pos, size);
            }
            pos += size;
        }
        return box("minf", body.toByteArray());
    }

    private static byte[] buildStbl(byte[] stsd, Ints sizes, Ints durations, List<Run> runs, long[] offsets)
            throws IOException {
        ByteArrayOutputStream stbl = new ByteArrayOutputStream();
        stbl.write(stsd);
        // stts: (count, delta) runs
        ByteArrayOutputStream stts = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(stts);
        int entries = 0;
        int i = 0;
        while (i < durations.size) {
            int value = durations.data[i];
            int count = 0;
            while (i < durations.size && durations.data[i] == value) {
                count++;
                i++;
            }
            d.writeInt(count);
            d.writeInt(value);
            entries++;
        }
        stbl.write(fullBox("stts", entries, stts.toByteArray()));
        // stsc: one chunk per run; an entry whenever the samples-per-chunk changes
        ByteArrayOutputStream stsc = new ByteArrayOutputStream();
        d = new DataOutputStream(stsc);
        entries = 0;
        int last = -1;
        for (int c = 0; c < runs.size(); c++) {
            int n = runs.get(c).samples;
            if (n != last) {
                d.writeInt(c + 1);
                d.writeInt(n);
                d.writeInt(1);
                entries++;
                last = n;
            }
        }
        stbl.write(fullBox("stsc", entries, stsc.toByteArray()));
        // stsz: sample_size 0, count, sizes
        ByteArrayOutputStream stsz = new ByteArrayOutputStream();
        d = new DataOutputStream(stsz);
        d.writeInt(0);
        d.writeInt(sizes.size);
        for (int s = 0; s < sizes.size; s++) d.writeInt(sizes.data[s]);
        byte[] stszBody = stsz.toByteArray();
        ByteArrayOutputStream stszBox = new ByteArrayOutputStream();
        DataOutputStream sb = new DataOutputStream(stszBox);
        sb.writeInt(stszBody.length + 12);
        sb.writeBytes("stsz");
        sb.writeInt(0);
        sb.write(stszBody);
        stbl.write(stszBox.toByteArray());
        // stco
        ByteArrayOutputStream stco = new ByteArrayOutputStream();
        d = new DataOutputStream(stco);
        for (long offset : offsets) d.writeInt((int) offset);
        stbl.write(fullBox("stco", offsets.length, stco.toByteArray()));
        return box("stbl", stbl.toByteArray());
    }

    /** A full box (version 0, flags 0) holding an entry count and entries. */
    private static byte[] fullBox(String type, int count, byte[] entries) {
        byte[] b = new byte[16 + entries.length];
        putU32(b, 0, b.length);
        putType(b, 4, type);
        putU32(b, 8, 0);
        putU32(b, 12, count);
        System.arraycopy(entries, 0, b, 16, entries.length);
        return b;
    }

    private static byte[] box(String type, byte[] body) {
        byte[] b = new byte[body.length + 8];
        putU32(b, 0, b.length);
        putType(b, 4, type);
        System.arraycopy(body, 0, b, 8, body.length);
        return b;
    }

    /**
     * Writes the duration of an mvhd/mdhd (after timescale) or tkhd (after track_ID+reserved).
     * Version 0 boxes hold 32-bit times, version 1 64-bit.
     */
    private static void setDuration(byte[] box, int at, long duration, boolean afterTimescale) {
        int version = box[at + 8] & 0xff;
        int p = at + 12;
        if (afterTimescale) {
            p += version == 1 ? 16 + 4 : 8 + 4; // creation, modification, timescale
        } else {
            p += version == 1 ? 16 + 8 : 8 + 8; // creation, modification, track_ID, reserved
        }
        if (version == 1) {
            putU32(box, p, duration >>> 32);
            putU32(box, p + 4, duration & 0xffffffffL);
        } else {
            putU32(box, p, Math.min(duration, 0xffffffffL));
        }
    }

    private static long timescale(byte[] b, int box) {
        int version = b[box + 8] & 0xff;
        return u32(b, box + 12 + (version == 1 ? 16 : 8));
    }

    // ------------------------------------------------------------------ byte helpers

    /** {size} of the box at pos (handles 64-bit sizes), null when broken. */
    private static long[] header(RandomAccessFile in, long pos, long len) throws IOException {
        in.seek(pos);
        long size = in.readInt() & 0xffffffffL;
        in.readInt();
        if (size == 1) size = in.readLong();
        else if (size == 0) size = len - pos;
        if (size < 8 || pos + size > len + 0) {
            if (size >= 8 && pos + size > len) return new long[]{len - pos};
            return null;
        }
        return new long[]{size};
    }

    private static String typeAt(RandomAccessFile in, long pos) throws IOException {
        byte[] t = new byte[4];
        in.seek(pos);
        in.readFully(t);
        return new String(t, "ISO-8859-1");
    }

    private static byte[] read(RandomAccessFile in, long pos, int size) throws IOException {
        byte[] b = new byte[size];
        in.seek(pos);
        in.readFully(b);
        return b;
    }

    /** {offset, size} of the first child box of a type in [start, end). */
    private static int[] find(byte[] b, int start, int end, String wanted) {
        int pos = start;
        while (pos + 8 <= end) {
            int size = (int) u32(b, pos);
            if (size < 8 || pos + size > end) return null;
            if (wanted.equals(type(b, pos + 4))) return new int[]{pos, size};
            pos += size;
        }
        return null;
    }

    private static String type(byte[] b, int at) {
        return new String(b, at, 4, java.nio.charset.Charset.forName("ISO-8859-1"));
    }

    private static byte[] slice(byte[] b, int at, int size) {
        byte[] out = new byte[size];
        System.arraycopy(b, at, out, 0, size);
        return out;
    }

    private static long u32(byte[] b, int at) {
        return ((b[at] & 0xffL) << 24) | ((b[at + 1] & 0xffL) << 16) | ((b[at + 2] & 0xffL) << 8) | (b[at + 3] & 0xffL);
    }

    private static long u64(byte[] b, int at) {
        return (u32(b, at) << 32) | u32(b, at + 4);
    }

    private static void putU32(byte[] b, int at, long v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static void putType(byte[] b, int at, String type) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) type.charAt(i);
    }
}
