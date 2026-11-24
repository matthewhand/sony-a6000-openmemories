#!/usr/bin/env bash
# Reproducible offline Android API 10 (DEX 035) unsigned APK builder.
# Runs pure-Java unit tests first, then compiles, packages, zipaligns, and verifies the APK.
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API10="${API10_JAR:-}"
API23="${API23_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
NATIVE_APK="${NATIVE_APK:-}"
JAVAC="${JAVAC:-/usr/lib/jvm/java-8-openjdk-amd64/bin/javac}"
DX="${DX_BIN:-/usr/lib/android-sdk/build-tools/debian/dx}"
AAPT="${AAPT_BIN:-$(command -v aapt)}"
ZIPALIGN="${ZIPALIGN_BIN:-$(command -v zipalign)}"
APKSIGNER="${APKSIGNER_BIN:-$(command -v apksigner || true)}"

PACKAGE_ID=""
APP_LABEL=""
VERSION_CODE=""
VERSION_NAME="0.11.0"
OUT_APK=""
SKIP_TESTS=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --package-id) PACKAGE_ID="$2"; shift 2 ;;
        --app-label) APP_LABEL="$2"; shift 2 ;;
        --version-code) VERSION_CODE="$2"; shift 2 ;;
        --version-name) VERSION_NAME="$2"; shift 2 ;;
        --out) OUT_APK="$2"; shift 2 ;;
        --api10-jar) API10="$2"; shift 2 ;;
        --native-apk) NATIVE_APK="$2"; shift 2 ;;
        --skip-tests) SKIP_TESTS=1; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$API10" ] || [ -z "$NATIVE_APK" ]; then
    echo "FAIL: set API10_JAR and NATIVE_APK (or use --api10-jar / --native-apk) to pinned public inputs" >&2
    exit 2
fi

if [ -z "$OUT_APK" ]; then
    OUT_APK="/dev/shm/openmemories-tweak-unsigned.apk"
fi

if [ "$SKIP_TESTS" -eq 0 ]; then
    "$ROOT/scripts/run-tests.sh"
fi

test -f "$API10" || { echo "FAIL: missing API 10 android.jar at $API10" >&2; exit 1; }
test -f "$API23" || { echo "FAIL: missing aapt resource jar at $API23" >&2; exit 1; }
test -f "$NATIVE_APK" || { echo "FAIL: missing pinned native APK at $NATIVE_APK" >&2; exit 1; }

printf '%s  %s\n' 80a61a3a3d50e2dd22492cdd96cf56c8b1523c83662ae3def59fd52b28675a47 "$NATIVE_APK" | sha256sum -c --status
printf '%s  %s\n' 949a2bef1ffe62e81af5d8919d17dfcc1309c43c "$API10" | sha1sum -c --status

TMP="$(mktemp -d "${TMPDIR:-/dev/shm}/om-tweak-build-XXXXXXXX")"
trap 'rm -rf -- "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/gen" "$TMP/classes" "$TMP/lib/armeabi" "$TMP/res"

cp -r "$ROOT/app/src/main/res/." "$TMP/res/"
cp "$ROOT/app/src/main/AndroidManifest.xml" "$TMP/AndroidManifest.xml"

python3 - "$TMP/AndroidManifest.xml" "$TMP/res/values/strings.xml" "$PACKAGE_ID" "$APP_LABEL" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import re, sys
manifest_path, strings_path, pkg_id, app_label, vcode, vname = sys.argv[1:]
m = open(manifest_path, "r", encoding="utf-8").read()
if pkg_id:
    m = re.sub(r'package="[^"]+"', f'package="{pkg_id}"', m, count=1)
    # Qualify relative android:name=".Foo" references when package changes
    m = re.sub(r'android:name="\.([A-Za-z0-9_.]+)"', r'android:name="com.github.ma1co.openmemories.tweak.\1"', m)
if vcode:
    m = re.sub(r'android:versionCode="[^"]+"', f'android:versionCode="{vcode}"', m, count=1)
if vname:
    m = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{vname}"', m, count=1)
open(manifest_path, "w", encoding="utf-8").write(m)
if app_label:
    s = open(strings_path, "r", encoding="utf-8").read()
    s = re.sub(r'<string name="app_name">[^<]*</string>', f'<string name="app_name">{app_label}</string>', s, count=1)
    open(strings_path, "w", encoding="utf-8").write(s)
PY

"$AAPT" package -f -M "$TMP/AndroidManifest.xml" -S "$TMP/res" -I "$API23" \
    -J "$TMP/gen" --custom-package com.github.ma1co.openmemories.tweak -F "$TMP/base.apk"

cat > "$TMP/gen/BuildConfig.java" <<EOF
package com.github.ma1co.openmemories.tweak;
public final class BuildConfig {
    public static final String VERSION_NAME = "$VERSION_NAME";
    public static final boolean DEBUG = false;
}
EOF

JAVA_SRCS=()
while IFS= read -r -d '' f; do JAVA_SRCS+=("$f"); done < <(find "$ROOT/app/src/main/java" "$TMP/gen" -name '*.java' -print0 | sort -z)
"$JAVAC" -source 7 -target 7 -encoding UTF-8 -cp "$API10" -d "$TMP/classes" "${JAVA_SRCS[@]}"
"$DX" --dex --output="$TMP/classes.dex" "$TMP/classes"

for lib in libadbd.so libprotectiontweak.so libtweak.so; do
    unzip -p "$NATIVE_APK" "lib/armeabi/$lib" > "$TMP/lib/armeabi/$lib"
done
(cd "$TMP" && "$AAPT" add base.apk classes.dex lib/armeabi/libadbd.so lib/armeabi/libprotectiontweak.so lib/armeabi/libtweak.so >/dev/null)

"$ZIPALIGN" -f 4 "$TMP/base.apk" "$TMP/aligned.apk"
"$ZIPALIGN" -c -v 4 "$TMP/aligned.apk" >/dev/null

if [ -n "$APKSIGNER" ] && "$APKSIGNER" verify --min-sdk-version 10 "$TMP/aligned.apk" >/dev/null 2>&1; then
    echo "FAIL: unsigned output APK unexpectedly passed signature verification" >&2
    exit 1
fi

python3 - "$TMP/aligned.apk" "$NATIVE_APK" <<'PY'
import sys, zipfile
target, native = sys.argv[1:]
with zipfile.ZipFile(target) as a, zipfile.ZipFile(native) as b:
    assert a.testzip() is None
    dex = a.read("classes.dex")
    assert dex[:8] == b"dex\n035\0", f"unexpected dex header {dex[:8]!r}"
    for lib in ("libadbd.so", "libprotectiontweak.so", "libtweak.so"):
        assert a.read("lib/armeabi/" + lib) == b.read("lib/armeabi/" + lib), f"native lib mismatch: {lib}"
    assert not any(n.startswith("META-INF/") for n in a.namelist()), "expected unsigned APK"
print("BUILD_CHECK: PASS (API10, DEX035, pinned native libs, unsigned)")
PY

mkdir -p "$(dirname "$OUT_APK")"
cp -- "$TMP/aligned.apk" "$OUT_APK"
chmod 600 "$OUT_APK"
sha256sum "$OUT_APK"
