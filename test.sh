#!/bin/sh
# JVM self-check of the document converters. Needs: apt-get install libkxml2-java
set -e
cd "$(dirname "$0")"
rm -rf build/test && mkdir -p build/test
javac -nowarn -cp /usr/share/java/kxml2.jar -d build/test src/com/dosyaacici/Convert.java src/com/dosyaacici/Legacy.java test/com/dosyaacici/ConvertTest.java
java -Dstdout.encoding=UTF-8 -cp build/test:/usr/share/java/kxml2.jar com.dosyaacici.ConvertTest
