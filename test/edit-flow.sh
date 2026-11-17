#!/bin/sh
# Edit a real .docx in Chromium, save it, and check LibreOffice can still open it.
# Needs: playwright (with chromium), libreoffice, libkxml2-java
set -e
cd "$(dirname "$0")/.."
rm -rf build/editflow
mkdir -p build/editflow
CP=build/editflow:/usr/share/java/kxml2.jar
javac -nowarn -cp /usr/share/java/kxml2.jar -d build/editflow src/com/dosyaacici/Convert.java src/com/dosyaacici/Legacy.java test/com/dosyaacici/EditFlow.java
java -cp $CP com.dosyaacici.EditFlow html test/files/t.docx build/editflow/page.html
node test/edit-flow.js build/editflow/page.html build/editflow/edits.properties
java -cp $CP com.dosyaacici.EditFlow apply test/files/t.docx build/editflow/page.html.keys build/editflow/edits.properties build/editflow/saved.docx
soffice --headless --convert-to "txt:Text (encoded):UTF8" --outdir build/editflow build/editflow/saved.docx >/dev/null 2>&1
cat build/editflow/saved.txt
grep -q "Değişti  çift boşluk" build/editflow/saved.txt && grep -q "Birinci paragraf & kalın metin." build/editflow/saved.txt && echo "EDIT FLOW OK"
