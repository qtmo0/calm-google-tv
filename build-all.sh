#!/bin/sh
# Baut alle drei Apps aus dem Quellcode neu und legt sie nach apk/. Nur nötig, wenn du Code änderst.
# Braucht Java 17 und das Android SDK (Platform 34, Build-Tools 34.0.0):
#   brew install openjdk@17 && brew install --cask android-commandlinetools
#   sdkmanager "platforms;android-34" "build-tools;34.0.0"
set -e
cd "$(dirname "$0")"
sh source/homescreen/build.sh && cp source/homescreen/out/tvhome.apk apk/homescreen.apk
sh source/screensaver/build.sh && cp source/screensaver/out/oleddream.apk apk/screensaver.apk
sh source/remote-keys/build.sh && cp source/remote-keys/out/tvtasten.apk apk/remote-keys.apk
echo "Fertig: apk/ ist aktuell. Einzeln aufspielen: adb install -r apk/<name>.apk"
