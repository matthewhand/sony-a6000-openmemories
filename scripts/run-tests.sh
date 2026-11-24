#!/usr/bin/env bash
# Compile and execute pure-Java unit tests offline (Java 7 compatibility, zero external deps).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVAC="${JAVAC:-/usr/lib/jvm/java-8-openjdk-amd64/bin/javac}"
JAVA="${JAVA:-/usr/lib/jvm/java-8-openjdk-amd64/bin/java}"
if [ ! -x "$JAVAC" ]; then JAVAC="$(command -v javac)"; fi
if [ ! -x "$JAVA" ]; then JAVA="$(command -v java)"; fi

TMP="$(mktemp -d "${TMPDIR:-/dev/shm}/om-tweak-tests-XXXXXXXX")"
trap 'rm -rf -- "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/classes"

# Compile pure-Java production classes (exclude Android-framework-dependent sources).
PURE_SOURCES=()
while IFS= read -r -d '' f; do
    if ! grep -qE '^import android\.|^import com\.sony\.|\bItemActivity\.|\bNativeTweak\b' "$f"; then
        PURE_SOURCES+=("$f")
    fi
done < <(find "$ROOT/app/src/main/java" -name '*.java' -print0 | sort -z)

TEST_SOURCES=()
while IFS= read -r -d '' f; do
    TEST_SOURCES+=("$f")
done < <(find "$ROOT/tests/src" -name '*Tests.java' -print0 | sort -z)

if [ "${#TEST_SOURCES[@]}" -eq 0 ]; then
    echo "FAIL: no *Tests.java files found under tests/src" >&2
    exit 1
fi

"$JAVAC" -source 7 -target 7 -encoding UTF-8 -d "$TMP/classes" "${PURE_SOURCES[@]}" "${TEST_SOURCES[@]}"

TOTAL_SUITES=0
for test_file in "${TEST_SOURCES[@]}"; do
    rel="${test_file#"$ROOT/tests/src/"}"
    cls="${rel%.java}"
    cls="${cls//\//.}"
    "$JAVA" -cp "$TMP/classes" "$cls"
    TOTAL_SUITES=$((TOTAL_SUITES + 1))
done
echo "ALL_TESTS_PASS: $TOTAL_SUITES test suite(s)"
