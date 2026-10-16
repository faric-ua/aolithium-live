#!/usr/bin/env bash
# Для Claude: зібрати й прогнати юніт-тести ядра (core/, без Android) у своєму середовищі — без Gradle і SDK.
# Kotlin-компілятор і JUnit беремо з Gradle, що лежить у /opt (так само робили в Renault 14.10).
# Телефон і CI цим не користуються: у CI тести йдуть через Gradle (android.yml).
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
LIB="${GRADLE_LIB:-$(ls -d /opt/gradle-*/lib 2>/dev/null | sort -V | tail -n 1)}"
[ -d "$LIB" ] || { echo "Не знайдено Gradle у /opt (потрібні kotlin-compiler-embeddable і junit)"; exit 1; }
jar() { ls "$LIB"/$1-[0-9]*.jar | head -n 1; }
CP_COMPILER="$(jar kotlin-compiler-embeddable):$(jar kotlin-stdlib):$(jar kotlin-script-runtime):$(jar kotlin-reflect):$(jar kotlin-daemon-embeddable):$(jar trove4j):$(jar annotations):$(jar kotlinx-coroutines-core-jvm)"
CP_RUN="$(jar kotlin-stdlib):$(jar junit):$(jar hamcrest-core)"
OUT="${TMPDIR:-/tmp}/aolithium-core-tests"
rm -rf "$OUT" && mkdir -p "$OUT"
SRC="$ROOT/android/app/src/main/java/ua/faric/aolithium/core"
TEST="$ROOT/android/app/src/test/java/ua/faric/aolithium/core"
java -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$CP_RUN" -d "$OUT" "$SRC"/*.kt "$TEST"/*.kt 2>&1 | grep -v "^warning:" || true
classes="$(cd "$TEST" && ls *Test.kt | sed 's/\.kt$//; s/^/ua.faric.aolithium.core./')"
java -cp "$OUT:$CP_RUN" org.junit.runner.JUnitCore $classes
