package com.dosyaacici;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Helper for test/edit-flow.js: "html in.docx out.html" or "apply in.docx keys.txt edits.properties out.docx". */
public class EditFlow {
    public static void main(String[] a) throws Exception {
        byte[] doc = Files.readAllBytes(Paths.get(a[1]));
        if (a[0].equals("html")) {
            List<String> keys = new ArrayList<String>();
            Files.write(Paths.get(a[2]), Convert.page(Convert.office(doc, "docx", keys)).getBytes("UTF-8"));
            Files.write(Paths.get(a[2] + ".keys"), keys);
            Files.write(Paths.get(a[2] + ".js"), (Convert.JS_EDIT_ON + "\n" + Convert.JS_COLLECT).getBytes("UTF-8"));
        } else {
            Properties p = new Properties();
            p.load(new FileInputStream(a[3]));
            Map<String, String> edits = new HashMap<String, String>();
            for (String k : Files.readAllLines(Paths.get(a[2]))) edits.put(k, p.getProperty(k, ""));
            FileOutputStream o = new FileOutputStream(a[4]);
            o.write(Convert.applyEdits(doc, edits));
            o.close();
        }
    }
}
