package com.dosyaacici;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Run: ./test.sh  (plain asserts, no test framework) */
public class ConvertTest {
    static byte[] zip(String... nameAndContent) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(b);
        for (int i = 0; i < nameAndContent.length; i += 2) {
            z.putNextEntry(new ZipEntry(nameAndContent[i]));
            z.write(nameAndContent[i + 1].getBytes("UTF-8"));
        }
        z.close();
        return b.toByteArray();
    }

    static byte[] file(String name) throws Exception {
        return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("test/files", name));
    }

    static void check(String html, String expected) {
        if (!html.contains(expected)) throw new AssertionError("missing <" + expected + "> in: " + html);
    }

    public static void main(String[] a) throws Exception {
        String docx = Convert.office(zip("word/document.xml",
            "<w:document xmlns:w='x'><w:body><w:p><w:r><w:t>Merhaba &amp; </w:t></w:r><w:r><w:instrText>GIZLI</w:instrText><w:t>dünya</w:t></w:r></w:p>"
            + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"), "docx");
        check(docx, "<p>Merhaba &amp; dünya</p>");
        check(docx, "<table><tr><td><p>A1</p></td></tr></table>");
        if (docx.contains("GIZLI")) throw new AssertionError("field code leaked: " + docx);

        String xlsx = Convert.office(zip(
            "xl/sharedStrings.xml", "<sst><si><t>Ad</t></si><si><r><t>Soy</t></r><r><t>ad</t></r></si></sst>",
            "xl/worksheets/sheet2.xml", "<worksheet><sheetData><row><c r='A1'><v>2</v></c></row></sheetData></worksheet>",
            "xl/worksheets/sheet1.xml", "<worksheet><sheetData><row r='1'><c r='A1' t='s'><v>0</v></c><c r='C1' t='s'><v>1</v></c></row>"
            + "<row><c r='B2' t='inlineStr'><is><t>x&lt;y</t></is></c></row></sheetData></worksheet>"), "xlsx");
        check(xlsx, "<h3>Sayfa 1</h3><table><tr><td>Ad</td><td></td><td>Soyad</td></tr><tr><td></td><td>x&lt;y</td></tr></table>");
        check(xlsx, "<h3>Sayfa 2</h3><table><tr><td>2</td></tr>");

        String pptx = Convert.office(zip(
            "ppt/slides/slide10.xml", "<p:sld xmlns:p='p' xmlns:a='a'><a:p><a:r><a:t>On</a:t></a:r></a:p></p:sld>",
            "ppt/slides/slide2.xml", "<p:sld xmlns:p='p' xmlns:a='a'><a:p><a:r><a:t>Iki</a:t></a:r></a:p></p:sld>"), "pptx");
        if (pptx.indexOf("Iki") > pptx.indexOf("On")) throw new AssertionError("slide order: " + pptx);

        String odt = Convert.office(zip("content.xml",
            "<office:document-content xmlns:office='o' xmlns:text='t'><office:body><text:h>Baslik</text:h><text:p>a<text:s/>b</text:p></office:body></office:document-content>"), "odt");
        check(odt, "<h3>Baslik</h3><p>a&nbsp;b</p>");

        if (Convert.text(new byte[]{'a', 0, 'b'}) != null) throw new AssertionError("binary not detected");
        if (!Convert.text(new byte[]{(byte) 0xFE}).equals("ş")) throw new AssertionError("cp1254 fallback");
        if (Convert.column("AB12") != 27) throw new AssertionError("column");
        // real Word 97 / Excel 97 files written by LibreOffice (test/files)
        String doc = Convert.office(file("t.doc"), "doc");
        check(doc, "<p>Başlık şğüıöç</p><p>Birinci paragraf &amp; kalın metin.</p><p>Alan: bağlantı</p>");
        check(doc, "<p>Elma &nbsp;|&nbsp; Armut</p><p>Son satır</p>");
        if (doc.contains("HYPERLINK")) throw new AssertionError("field code leaked: " + doc);
        String xls = Convert.office(file("t.xls"), "xls");
        check(xls, "<h3>t</h3><table><tr><td>Ad</td><td>Yaş</td><td>Şehir</td></tr><tr><td>Ayşe</td><td>30</td><td>İstanbul</td></tr>");
        check(xls, "<tr><td>Ömer</td><td>41.5</td><td>Muğla</td></tr><tr><td></td><td></td><td>71.5</td></tr>");
        String big = Convert.office(file("big.xls"), "xls"); // shared strings span CONTINUE records
        check(big, "<td>Satır 1500 ğüşıöç uzun metin 10500</td><td>1500</td><td>x1500</td>");
        check(big, "<td>Satır 2999 ğüşıöç uzun metin 20993</td><td>2999</td><td>x2999</td>");
        if (big.split("<tr>").length != 3001) throw new AssertionError("row count");
        // damaged files must fail with a readable message, never hang
        java.util.Random r = new java.util.Random(1);
        for (String n : new String[]{"t.doc", "t.xls", "big.xls"}) {
            byte[] orig = file(n);
            for (int k = 0; k < 100; k++) {
                byte[] b = java.util.Arrays.copyOf(orig, k % 3 == 0 ? r.nextInt(orig.length) : orig.length);
                for (int j = 0; j < 20 && b.length > 0; j++) b[r.nextInt(b.length)] = (byte) r.nextInt();
                try { Convert.office(b, n.substring(n.indexOf('.') + 1)); }
                catch (RuntimeException e) { throw new AssertionError(n + " leaked " + e); }
                catch (Exception expected) { }
            }
        }
        System.out.println("ConvertTest OK");
    }
}
