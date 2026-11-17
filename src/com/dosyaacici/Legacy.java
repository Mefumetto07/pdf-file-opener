package com.dosyaacici;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Old binary Office files: OLE2 compound file reader + Word 97 (.doc) text + Excel BIFF8 (.xls) cells. */
public class Legacy {
    static final int END = -2; // ENDOFCHAIN

    static int u8(byte[] b, int o) { return b[o] & 0xFF; }
    static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    static int i32(byte[] b, int o) { return u16(b, o) | u16(b, o + 2) << 16; }

    static Exception bad() { return new Exception("Dosya bozuk veya desteklenmeyen biçimde"); }

    // ---------------- OLE2 / Compound File Binary ----------------

    /** Returns every stream in the compound file by name (storage hierarchy ignored). */
    static Map<String, byte[]> cfb(byte[] d) throws Exception {
        if (d.length < 512 || i32(d, 0) != 0xE011CFD0) throw bad();
        int ss = 1 << u16(d, 0x1E), mss = 1 << u16(d, 0x20), cutoff = i32(d, 0x38);
        int per = ss / 4;
        List<Integer> fatSectors = new ArrayList<Integer>();
        for (int i = 0; i < 109; i++) { int s = i32(d, 0x4C + i * 4); if (s >= 0) fatSectors.add(s); }
        for (int s = i32(d, 0x44), guard = 0; s >= 0 && guard++ < 100000; s = i32(d, off(s, ss) + ss - 4))
            for (int i = 0; i < per - 1; i++) { int f = i32(d, off(s, ss) + i * 4); if (f >= 0) fatSectors.add(f); }
        int[] fat = new int[fatSectors.size() * per];
        for (int k = 0; k < fatSectors.size(); k++)
            for (int i = 0; i < per; i++) {
                int o = off(fatSectors.get(k), ss) + i * 4;
                fat[k * per + i] = o + 4 <= d.length ? i32(d, o) : END;
            }

        byte[] dir = chain(d, fat, i32(d, 0x30), ss, -1, ss);
        byte[] miniFat = chain(d, fat, i32(d, 0x3C), ss, -1, ss);
        int[] mfat = new int[miniFat.length / 4];
        for (int i = 0; i < mfat.length; i++) mfat[i] = i32(miniFat, i * 4);
        byte[] miniStream = chain(d, fat, i32(dir, 0x74), ss, i32(dir, 0x78), ss);

        Map<String, byte[]> streams = new HashMap<String, byte[]>();
        for (int e = 0; e + 128 <= dir.length; e += 128) {
            if (u8(dir, e + 0x42) != 2) continue; // streams only
            int nameLen = Math.max(0, Math.min(64, u16(dir, e + 0x40)) - 2);
            String name = new String(dir, e, nameLen, Charset.forName("UTF-16LE"));
            int start = i32(dir, e + 0x74), size = i32(dir, e + 0x78);
            streams.put(name, size < cutoff
                ? chain(miniStream, mfat, start, mss, size, 0)
                : chain(d, fat, start, ss, size, ss));
        }
        return streams;
    }

    static int off(int sector, int ss) { return (sector + 1) * ss; }

    /** Concatenates a sector chain. base = byte offset of sector 0 (ss for the file, 0 for the mini stream). */
    static byte[] chain(byte[] d, int[] fat, int s, int ss, int size, int base) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int guard = 0; s >= 0 && s < fat.length && guard < fat.length; s = fat[s], guard++) {
            int o = base + s * ss;
            if (o >= d.length) break;
            out.write(d, o, Math.min(ss, d.length - o));
            if (size >= 0 && out.size() >= size) break;
        }
        byte[] r = out.toByteArray();
        if (size >= 0 && r.length > size) r = java.util.Arrays.copyOf(r, size);
        return r;
    }

    // ---------------- Word 97-2003 (.doc) ----------------

    static String doc(byte[] data) throws Exception {
        Map<String, byte[]> s = cfb(data);
        byte[] w = s.get("WordDocument");
        if (w == null || w.length < 0x200) throw bad();
        int flags = u16(w, 0x0A);
        if ((flags & 0x0100) != 0) throw new Exception("Şifreli belge açılamaz");
        byte[] table = s.get((flags & 0x0200) != 0 ? "1Table" : "0Table");
        if (table == null) throw new Exception("Bu Word sürümü (Word 95 veya eskisi) desteklenmiyor");

        int o = 32;
        o += 2 + u16(w, o) * 2;            // fibRgW
        int lw = o + 2;
        o += 2 + u16(w, o) * 4;            // fibRgLw
        int ccpText = i32(w, lw + 3 * 4);  // length of the main body text
        o += 2;                            // cbRgFcLcb
        int fcClx = i32(w, o + 33 * 8), lcbClx = i32(w, o + 33 * 8 + 4);

        // CLX: skip Prc blocks (0x01), then the piece table (0x02)
        int p = fcClx, endClx = fcClx + lcbClx;
        while (p < endClx && table[p] == 1) p += 3 + u16(table, p + 1);
        if (p >= endClx || table[p] != 2) throw bad();
        int lcb = i32(table, p + 1), plc = p + 5, n = (lcb - 4) / 12;

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n && text.length() < ccpText; i++) {
            int cp0 = i32(table, plc + i * 4), cp1 = i32(table, plc + (i + 1) * 4);
            int fc = i32(table, plc + (n + 1) * 4 + i * 8 + 2);
            int count = Math.min(cp1 - cp0, ccpText - text.length());
            if ((fc & 0x40000000) != 0) {
                int start = (fc & ~0x40000000) / 2;
                text.append(new String(w, start, Math.min(count, w.length - start), Charset.forName("windows-1252")));
            } else {
                text.append(new String(w, fc, Math.min(count * 2, w.length - fc), Charset.forName("UTF-16LE")));
            }
        }
        return docHtml(text);
    }

    /** Word control characters -> HTML. Fields keep only their displayed result. */
    static String docHtml(CharSequence t) {
        StringBuilder out = new StringBuilder("<p>");
        int field = 0; // >0 while inside a field's code part
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == 0x13) { field++; continue; }
            if (c == 0x14 || c == 0x15) { if (field > 0) field--; continue; }
            if (field > 0) continue;
            if (c == '\r') out.append("</p><p>");
            else if (c == 0x07) {
                // shortcut: table cells shown as " | " separated lines; real grid needs paragraph properties (PAPX)
                boolean rowEnd = i + 1 < t.length() && t.charAt(i + 1) == 0x07;
                out.append(rowEnd ? "</p><p>" : " &nbsp;|&nbsp; ");
                if (rowEnd) i++;
            } else if (c == 0x0B) out.append("<br>");
            else if (c == 0x0C) out.append("</p><hr><p>");
            else if (c == '\t') out.append("&emsp;");
            else if (c == '&') out.append("&amp;");
            else if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c >= 0x20 && c != 0xFFFF) out.append(c);
        }
        return out.append("</p>").toString();
    }

    // ---------------- Excel 97-2003 (.xls, BIFF8) ----------------

    static String xls(byte[] data) throws Exception {
        Map<String, byte[]> s = cfb(data);
        byte[] b = s.get("Workbook");
        if (b == null) throw new Exception(s.containsKey("Book")
            ? "Bu Excel sürümü (Excel 95 veya eskisi) desteklenmiyor" : "Dosya bozuk veya desteklenmeyen biçimde");

        List<String> names = new ArrayList<String>();
        List<String> sst = new ArrayList<String>();
        List<TreeMap<Integer, TreeMap<Integer, String>>> sheets = new ArrayList<TreeMap<Integer, TreeMap<Integer, String>>>();
        TreeMap<Integer, TreeMap<Integer, String>> cur = null;
        int pendRow = -1, pendCol = -1, bofs = 0;

        for (int o = 0; o + 4 <= b.length; ) {
            int type = u16(b, o), len = u16(b, o + 2), d = o + 4;
            if (d + len > b.length) break;
            switch (type) {
                case 0x002F: throw new Exception("Şifreli tablo açılamaz");
                case 0x0809: // BOF: first is workbook globals, then one per sheet
                    if (bofs++ > 0) { cur = new TreeMap<Integer, TreeMap<Integer, String>>(); sheets.add(cur); }
                    break;
                case 0x0085: { // BOUNDSHEET
                    int[] pos = {d + 7};
                    names.add(str(b, pos, u8(b, d + 6)));
                    break;
                }
                case 0x00FC: { // SST, possibly spread over CONTINUE records
                    List<int[]> segs = new ArrayList<int[]>();
                    segs.add(new int[]{d + 8, d + len});
                    int q = d + len;
                    while (q + 4 <= b.length && u16(b, q) == 0x003C) {
                        segs.add(new int[]{q + 4, q + 4 + u16(b, q + 2)});
                        q += 4 + u16(b, q + 2);
                    }
                    readSst(b, segs, i32(b, d + 4), sst);
                    break;
                }
                case 0x00FD: // LABELSST
                    if (cur != null) { int i = i32(b, d + 6); put(cur, b, d, i < sst.size() ? sst.get(i) : ""); }
                    break;
                case 0x0204: { // LABEL
                    int[] pos = {d + 8};
                    if (cur != null) put(cur, b, d, str(b, pos, u16(b, d + 6)));
                    break;
                }
                case 0x0203: // NUMBER
                    if (cur != null) put(cur, b, d, num(Double.longBitsToDouble(i64(b, d + 6))));
                    break;
                case 0x027E: // RK
                    if (cur != null) put(cur, b, d, num(rk(i32(b, d + 6))));
                    break;
                case 0x00BD: { // MULRK
                    int row = u16(b, d), col = u16(b, d + 2);
                    for (int k = 0; k < (len - 6) / 6 && cur != null; k++)
                        cell(cur, row, col + k, num(rk(i32(b, d + 4 + k * 6 + 2))));
                    break;
                }
                case 0x0205: // BOOLERR
                    if (cur != null && u8(b, d + 7) == 0) put(cur, b, d, u8(b, d + 6) != 0 ? "DOĞRU" : "YANLIŞ");
                    break;
                case 0x0006: // FORMULA: cached result
                    if (cur == null) break;
                    if (u16(b, d + 12) != 0xFFFF) put(cur, b, d, num(Double.longBitsToDouble(i64(b, d + 6))));
                    else if (u8(b, d + 6) == 0) { pendRow = u16(b, d); pendCol = u16(b, d + 2); }
                    else if (u8(b, d + 6) == 1) put(cur, b, d, u8(b, d + 8) != 0 ? "DOĞRU" : "YANLIŞ");
                    break;
                case 0x0207: // STRING: text result of the preceding formula
                    if (cur != null && pendRow >= 0) {
                        int[] pos = {d + 2};
                        cell(cur, pendRow, pendCol, str(b, pos, u16(b, d)));
                        pendRow = -1;
                    }
                    break;
            }
            o = d + len;
        }

        StringBuilder out = new StringBuilder();
        for (int k = 0; k < sheets.size(); k++) {
            String title = k < names.size() ? names.get(k) : "Sayfa " + (k + 1);
            out.append("<h3>").append(Convert.esc(title)).append("</h3><table>");
            for (TreeMap<Integer, String> row : sheets.get(k).values()) {
                out.append("<tr>");
                for (int c = 0; c <= row.lastKey(); c++) {
                    String v = row.get(c);
                    out.append("<td>").append(v == null ? "" : Convert.esc(v)).append("</td>");
                }
                out.append("</tr>");
            }
            out.append("</table>");
        }
        return out.toString();
    }

    static long i64(byte[] b, int o) { return (i32(b, o) & 0xFFFFFFFFL) | ((long) i32(b, o + 4)) << 32; }

    static double rk(int v) {
        double d = (v & 2) != 0 ? (double) (v >> 2) : Double.longBitsToDouble(((long) (v & 0xFFFFFFFC)) << 32);
        return (v & 1) != 0 ? d / 100 : d;
    }

    // shortcut: dates and number formats are shown as raw numbers; applying FORMAT/XF records would fix that
    static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = String.valueOf(Math.round(d * 1e10) / 1e10);
        return s.contains("E") ? String.valueOf(d) : s;
    }

    static void put(TreeMap<Integer, TreeMap<Integer, String>> sheet, byte[] b, int d, String v) {
        cell(sheet, u16(b, d), u16(b, d + 2), v);
    }

    static void cell(TreeMap<Integer, TreeMap<Integer, String>> sheet, int row, int col, String v) {
        if (col > 255) return; // BIFF8 has 256 columns; anything above is corruption
        TreeMap<Integer, String> r = sheet.get(row);
        if (r == null) sheet.put(row, r = new TreeMap<Integer, String>());
        r.put(col, v);
    }

    /** XLUnicodeString body: flags byte at pos[0], then optional run/ext counts, then cch chars. Advances pos. */
    static String str(byte[] b, int[] pos, int cch) {
        int p = pos[0], fl = u8(b, p++);
        boolean wide = (fl & 1) != 0;
        int rich = 0, ext = 0;
        if ((fl & 8) != 0) { rich = u16(b, p); p += 2; }
        if ((fl & 4) != 0) { ext = i32(b, p); p += 4; }
        int n = Math.min(cch * (wide ? 2 : 1), b.length - p);
        pos[0] = p + n + rich * 4 + ext;
        return new String(b, p, n, Charset.forName(wide ? "UTF-16LE" : "ISO-8859-1"));
    }

    /** SST strings; a string's characters may continue in the next segment, which then starts with a new flags byte. */
    static void readSst(byte[] b, List<int[]> segs, int count, List<String> out) {
        int seg = 0, p = segs.get(0)[0];
        for (int i = 0; i < count; i++) {
            if (p >= segs.get(seg)[1]) { if (++seg >= segs.size()) return; p = segs.get(seg)[0]; }
            int cch = u16(b, p), fl = u8(b, p + 2);
            p += 3;
            int rich = 0, ext = 0;
            if ((fl & 8) != 0) { rich = u16(b, p); p += 2; }
            if ((fl & 4) != 0) { ext = i32(b, p); p += 4; }
            boolean wide = (fl & 1) != 0;
            StringBuilder s = new StringBuilder();
            while (s.length() < cch) {
                if (p >= segs.get(seg)[1]) {
                    if (++seg >= segs.size()) { out.add(s.toString()); return; }
                    p = segs.get(seg)[0];
                    wide = (u8(b, p++) & 1) != 0;
                }
                int avail = (segs.get(seg)[1] - p) / (wide ? 2 : 1);
                int take = Math.min(cch - s.length(), avail);
                if (take == 0) { p = segs.get(seg)[1]; continue; } // odd byte left: move on, don't spin
                s.append(new String(b, p, take * (wide ? 2 : 1), Charset.forName(wide ? "UTF-16LE" : "ISO-8859-1")));
                p += take * (wide ? 2 : 1);
            }
            out.add(s.toString());
            // skip formatting runs and extended data, which may also cross into the next segment
            for (int skip = rich * 4 + ext; skip > 0; ) {
                if (p >= segs.get(seg)[1]) { if (++seg >= segs.size()) return; p = segs.get(seg)[0]; }
                int step = Math.min(skip, segs.get(seg)[1] - p);
                p += step; skip -= step;
            }
        }
    }
}
