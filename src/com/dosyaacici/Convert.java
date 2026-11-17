package com.dosyaacici;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/** Pure-Java converters from document formats to simple HTML (no Android UI deps, so it is JVM-testable). */
public class Convert {
    static final int MAX = 30 * 1024 * 1024;

    // XML element -> HTML element, shared by Word (w:), PowerPoint (a:) and OpenDocument (text:/table:)
    static final Map<String, String> TAGS = new HashMap<String, String>();
    static {
        String[][] m = {
            {"w:p", "p"}, {"a:p", "p"}, {"text:p", "p"}, {"text:h", "h3"},
            {"w:tbl", "table"}, {"a:tbl", "table"}, {"table:table", "table"},
            {"w:tr", "tr"}, {"a:tr", "tr"}, {"table:table-row", "tr"},
            {"w:tc", "td"}, {"a:tc", "td"}, {"table:table-cell", "td"},
            {"text:list-item", "li"}, {"text:list", "ul"}};
        for (String[] p : m) TAGS.put(p[0], p[1]);
    }

    static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX) throw new Exception("Dosya çok büyük (30 MB sınırı)");
        }
        return out.toByteArray();
    }

    static Map<String, byte[]> unzip(byte[] data) throws Exception {
        Map<String, byte[]> m = new HashMap<String, byte[]>();
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data));
        for (ZipEntry e; (e = z.getNextEntry()) != null; )
            if (e.getName().endsWith(".xml")) m.put(e.getName(), read(z));
        return m;
    }

    static List<String> zipList(byte[] data) throws Exception {
        List<String> l = new ArrayList<String>();
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data));
        for (ZipEntry e; (e = z.getNextEntry()) != null; ) l.add(e.getName());
        return l;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Decodes text; returns null if the bytes look binary. Falls back to Turkish Windows codepage. */
    static String text(byte[] b) {
        for (int i = 0; i < Math.min(b.length, 8000); i++) if (b[i] == 0) return null;
        String s = new String(b, Charset.forName("UTF-8"));
        if (s.indexOf('�') >= 0) s = new String(b, Charset.forName("windows-1254"));
        return s;
    }

    static XmlPullParser parser(byte[] xml) throws Exception {
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new ByteArrayInputStream(xml), null);
        return p;
    }

    /**
     * Word/PowerPoint/OpenDocument body XML -> HTML paragraphs and tables.
     * With keys != null every w:t / a:t text run becomes a span data-k="entry#n" (n = run order) so it can be edited.
     */
    static void xml(byte[] data, StringBuilder out, String entry, List<String> keys) throws Exception {
        XmlPullParser p = parser(data);
        int skip = 0, run = 0;
        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            String n = p.getName();
            boolean isRun = keys != null && (n != null) && (n.equals("w:t") || n.equals("a:t"));
            if (e == XmlPullParser.START_TAG && isRun) {
                String k = entry + "#" + run++;
                keys.add(k);
                out.append("<span data-k=\"").append(k).append("\">");
            } else if (e == XmlPullParser.END_TAG && isRun) {
                out.append("</span>");
            } else if (e == XmlPullParser.START_TAG) {
                if (n.equals("w:instrText") || n.equals("w:delText")) skip++;
                String t = TAGS.get(n);
                if (t != null) out.append('<').append(t).append('>');
                else if (n.equals("w:tab") || n.equals("text:tab")) out.append("&emsp;");
                else if (n.equals("w:br") || n.equals("a:br") || n.equals("text:line-break")) out.append("<br>");
                else if (n.equals("text:s")) out.append("&nbsp;");
            } else if (e == XmlPullParser.END_TAG) {
                if (n.equals("w:instrText") || n.equals("w:delText")) skip--;
                String t = TAGS.get(n);
                if (t != null) out.append("</").append(t).append('>');
            } else if (e == XmlPullParser.TEXT && skip == 0) {
                out.append(esc(p.getText()));
            }
        }
    }

    /** Entry names like prefix1.xml, prefix2.xml ... sorted by number. */
    static List<String> numbered(Map<String, byte[]> z, String prefix) {
        final Pattern pat = Pattern.compile(Pattern.quote(prefix) + "(\\d+)\\.xml");
        List<String> l = new ArrayList<String>();
        for (String k : z.keySet()) if (pat.matcher(k).matches()) l.add(k);
        Collections.sort(l, new Comparator<String>() {
            public int compare(String a, String b) {
                Matcher x = pat.matcher(a), y = pat.matcher(b);
                x.matches(); y.matches();
                return Integer.parseInt(x.group(1)) - Integer.parseInt(y.group(1));
            }
        });
        return l;
    }

    static int column(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++)
            c = c * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        return c - 1;
    }

    static void xlsx(Map<String, byte[]> z, StringBuilder out) throws Exception {
        List<String> shared = new ArrayList<String>();
        byte[] ss = z.get("xl/sharedStrings.xml");
        if (ss != null) {
            XmlPullParser p = parser(ss);
            StringBuilder cur = null;
            boolean inT = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG && p.getName().equals("si")) cur = new StringBuilder();
                else if (e == XmlPullParser.START_TAG && p.getName().equals("t")) inT = true;
                else if (e == XmlPullParser.END_TAG && p.getName().equals("t")) inT = false;
                else if (e == XmlPullParser.END_TAG && p.getName().equals("si")) shared.add(cur.toString());
                else if (e == XmlPullParser.TEXT && inT && cur != null) cur.append(p.getText());
            }
        }
        int sheetNo = 1;
        for (String name : numbered(z, "xl/worksheets/sheet")) {
            out.append("<h3>Sayfa ").append(sheetNo++).append("</h3><table>");
            XmlPullParser p = parser(z.get(name));
            int col = 0;
            String type = null;
            StringBuilder val = null;
            boolean inV = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                String n = p.getName();
                if (e == XmlPullParser.START_TAG) {
                    if (n.equals("row")) { out.append("<tr>"); col = 0; }
                    else if (n.equals("c")) {
                        type = p.getAttributeValue(null, "t");
                        String r = p.getAttributeValue(null, "r");
                        if (r != null) for (int target = column(r); col < target; col++) out.append("<td></td>");
                        val = new StringBuilder();
                    } else if (n.equals("v") || n.equals("t")) inV = true;
                } else if (e == XmlPullParser.END_TAG) {
                    if (n.equals("row")) out.append("</tr>");
                    else if (n.equals("v") || n.equals("t")) inV = false;
                    else if (n.equals("c")) {
                        String v = val.toString();
                        if ("s".equals(type) && v.length() > 0) {
                            int i = Integer.parseInt(v.trim());
                            v = i < shared.size() ? shared.get(i) : "";
                        }
                        out.append("<td>").append(esc(v)).append("</td>");
                        col++;
                    }
                } else if (e == XmlPullParser.TEXT && inV && val != null) val.append(p.getText());
            }
            out.append("</table>");
        }
    }

    static String office(byte[] data, String ext) throws Exception { return office(data, ext, null); }

    /** Office Open XML / OpenDocument zip -> HTML body. keys != null: docx/pptx runs become editable (see xml()). */
    static String office(byte[] data, String ext, List<String> keys) throws Exception {
        try {
            if (ext.equals("doc") || ext.equals("dot")) return Legacy.doc(data);
            if (ext.equals("xls") || ext.equals("xlt")) return Legacy.xls(data);
        } catch (RuntimeException e) {
            throw Legacy.bad(); // offsets in a damaged binary file point outside the data
        }
        Map<String, byte[]> z = unzip(data);
        StringBuilder out = new StringBuilder();
        if (ext.equals("docx") || ext.equals("docm") || ext.equals("dotx")) {
            xml(need(z, "word/document.xml"), out, "word/document.xml", keys);
        } else if (ext.equals("xlsx") || ext.equals("xlsm")) {
            xlsx(z, out);
        } else if (ext.equals("pptx") || ext.equals("ppsx")) {
            int i = 1;
            for (String s : numbered(z, "ppt/slides/slide")) {
                out.append("<h3 class=slide>Slayt ").append(i++).append("</h3>");
                xml(z.get(s), out, s, keys);
            }
        } else {
            xml(need(z, "content.xml"), out, null, null); // odt, ods, odp: view only
        }
        return out.toString();
    }

    // WebView scripts for in-place editing of the data-k spans made by xml()
    static final String JS_EDIT_ON = "document.querySelectorAll('[data-k]').forEach(function(e){e.contentEditable='plaintext-only';"
        + "e.style.background='#FFF8E1';e.style.outline='1px dashed #FFB300'})";
    static final String JS_EDIT_OFF = "document.querySelectorAll('[data-k]').forEach(function(e){e.contentEditable='false';"
        + "e.style.background='';e.style.outline=''})";
    static final String JS_COLLECT = "(function(){var o={};document.querySelectorAll('[data-k]').forEach(function(e){"
        + "o[e.dataset.k]=e.textContent.replace(/\\u00a0/g,' ')});return JSON.stringify(o)})()";

    static final Pattern RUN = Pattern.compile("<(w|a):t(\\s[^>]*?)?(/>|>(.*?)</\\1:t>)", Pattern.DOTALL);

    /** Rewrites the zip with edited run texts. edits: "entry#n" -> new text, as produced by xml(). */
    static byte[] applyEdits(byte[] zip, Map<String, String> edits) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream out = new ZipOutputStream(bytes);
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
        Charset utf8 = Charset.forName("UTF-8");
        for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
            byte[] d = read(in);
            if (edits.containsKey(e.getName() + "#0")) {
                // shortcut: OOXML parts are assumed UTF-8 (Office always writes UTF-8)
                Matcher m = RUN.matcher(new String(d, utf8));
                StringBuffer sb = new StringBuffer();
                for (int k = 0; m.find(); k++) {
                    String v = edits.get(e.getName() + "#" + k);
                    if (v == null) continue;
                    String ns = m.group(1);
                    m.appendReplacement(sb, Matcher.quoteReplacement("<" + ns + ":t"
                        + (ns.equals("w") ? " xml:space=\"preserve\"" : "") + ">" + esc(v) + "</" + ns + ":t>"));
                }
                m.appendTail(sb);
                d = sb.toString().getBytes(utf8);
            }
            out.putNextEntry(new ZipEntry(e.getName()));
            out.write(d);
            out.closeEntry();
        }
        out.close();
        return bytes.toByteArray();
    }

    static byte[] need(Map<String, byte[]> z, String name) throws Exception {
        byte[] b = z.get(name);
        if (b == null) throw new Exception("Dosya bozuk veya desteklenmeyen biçimde");
        return b;
    }

    static final String CSS = "<meta name=viewport content='width=device-width,initial-scale=1'>"
        + "<style>body{font-family:sans-serif;padding:12px;line-height:1.5;color:#222;word-wrap:break-word}"
        + "table{border-collapse:collapse;margin:8px 0}td{border:1px solid #ccc;padding:4px 8px;vertical-align:top}"
        + "td p{margin:0}pre{white-space:pre-wrap;font-size:14px}img{max-width:100%}"
        + "h3.slide{border-top:2px solid #1E88E5;padding-top:8px;color:#1E88E5}</style>";

    static String page(String body) {
        return "<!doctype html><html><head><meta charset=utf-8>" + CSS + "</head><body>" + body + "</body></html>";
    }
}
