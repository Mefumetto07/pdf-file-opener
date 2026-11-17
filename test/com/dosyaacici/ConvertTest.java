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
        System.out.println("ConvertTest OK");
    }
}
