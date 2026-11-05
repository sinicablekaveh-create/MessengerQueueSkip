#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}
TOOLS="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
TDLIB_AAR=${TDLIB_AAR:?Set TDLIB_AAR}
OUT="$PWD/build-telegram"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/tdlib"
unzip -oq "$TDLIB_AAR" -d "$OUT/tdlib"
python3 - <<'PY'
from pathlib import Path
s=Path('app/src/main/AndroidManifest.xml').read_text().replace('<manifest xmlns:android=', '<manifest package="com.sinicable.threeib.telegram" android:versionCode="16" android:versionName="3.1.0" xmlns:android=',1)
s=s.replace('android:name=".', 'android:name="com.sinicable.messengerqueue.')
Path('build-telegram/AndroidManifest.xml').write_text(s)
PY
"$TOOLS/aapt2" compile --dir app/src/main/res -o "$OUT/resources.zip"
"$TOOLS/aapt2" link -o "$OUT/unsigned.apk" --manifest "$OUT/AndroidManifest.xml" -I "$ANDROID_JAR" --min-sdk-version 26 --target-sdk-version 35 -A app/src/main/assets "$OUT/resources.zip"
find app/src/main/java -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_JAR:$OUT/tdlib/libs/tdlib-classes.jar" -d "$OUT/classes"
jar cf "$OUT/app.jar" -C "$OUT/classes" .
"$TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" "$OUT/app.jar" "$OUT/tdlib/libs/tdlib-classes.jar"
python3 - <<'PY'
from pathlib import Path
import zipfile
p=Path('build-telegram')
with zipfile.ZipFile(p/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
 for f in (p/'dex').glob('*.dex'):z.write(f,f.name)
 for f in (p/'tdlib/jni').glob('*/*.so'):z.write(f,'lib/'+str(f.relative_to(p/'tdlib/jni')))
PY
"$TOOLS/zipalign" -f -P 16 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$OUT/signing.keystore" ]; then keytool -genkeypair -keystore "$OUT/signing.keystore" -alias threeib -storepass android -keypass android -dname "CN=THREE IB Personal Build" -keyalg RSA -keysize 2048 -validity 10000 -noprompt; fi
"$TOOLS/apksigner" sign --ks "$OUT/signing.keystore" --ks-pass pass:android --out "$OUT/THREE-IB-Telegram-v3.1.0.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$OUT/THREE-IB-Telegram-v3.0.0.apk"
