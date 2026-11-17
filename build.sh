#!/bin/sh
# Builds dosya-acici.apk without Gradle. Needs (Ubuntu/Debian):
#   apt-get install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner openjdk-17-jdk
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/classes
aapt package -f -M AndroidManifest.xml -S res -I "$SDK" -F build/unsigned.apk
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$SDK" -d build/classes src/com/dosyaacici/*.java
dalvik-exchange --dex --output=build/classes.dex build/classes
(cd build && zip -q unsigned.apk classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias dosyaacici -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Dosya Acici" -noprompt
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out dosya-acici.apk build/aligned.apk
echo "OK: dosya-acici.apk"
