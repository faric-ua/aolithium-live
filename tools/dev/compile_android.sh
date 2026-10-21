#!/usr/bin/env bash
# Для Claude: перевірити, що весь Kotlin застосунку компілюється, — без Gradle і Android SDK.
# Потрібен android.jar (API 36) — шлях у ANDROID_JAR. Звідки взяти: публічний репо
# github.com/Reginer/aosp-android-jar (тека android-36). Він містить і приховані API, тож
# «скомпілювалось тут» ≠ «скомпілюється в CI» — справжня перевірка лише CI (android.yml).
# BuildConfig (його генерує Gradle) — тимчасова заглушка.
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
: "${ANDROID_JAR:?вкажи ANDROID_JAR=/шлях/до/android.jar}"
LIB="${GRADLE_LIB:-$(ls -d /opt/gradle-*/lib 2>/dev/null | sort -V | tail -n 1)}"
jar() { ls "$LIB"/$1-[0-9]*.jar | head -n 1; }
CP_COMPILER="$(jar kotlin-compiler-embeddable):$(jar kotlin-stdlib):$(jar kotlin-script-runtime):$(jar kotlin-reflect):$(jar kotlin-daemon-embeddable):$(jar trove4j):$(jar annotations):$(jar kotlinx-coroutines-core-jvm)"
TMP="${TMPDIR:-/tmp}/aolithium-android-compile"
rm -rf "$TMP" && mkdir -p "$TMP/stub/ua/faric/aolithium" "$TMP/out"
VERSION="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$ROOT/android/app/build.gradle.kts" | head -n 1)"
CODE="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$ROOT/android/app/build.gradle.kts" | head -n 1)"
cat > "$TMP/stub/ua/faric/aolithium/BuildConfig.kt" <<EOF
package ua.faric.aolithium
object BuildConfig {
    const val DEBUG = false
    const val VERSION_NAME = "$VERSION"
    const val VERSION_CODE = $CODE
    const val GIT_SHA = "local"
}
EOF
# R (теж генерує Gradle) — заглушка з іменами файлів у res/drawable і res/layout (віджет з v0.3.1).
{
  echo "package ua.faric.aolithium"
  echo "object R {"
  echo "  object drawable {"
  for f in "$ROOT"/android/app/src/main/res/drawable/*.xml; do echo "    const val $(basename "$f" .xml) = 0"; done
  echo "  }"
  # T63 (v0.4.17): значки на вибір — R.mipmap з res/mipmap-anydpi-v26.
  echo "  object mipmap {"
  for f in "$ROOT"/android/app/src/main/res/mipmap-anydpi-v26/*.xml; do echo "    const val $(basename "$f" .xml) = 0"; done
  echo "  }"
  # Віджет (v0.3.1): R.layout і R.id з res/layout.
  echo "  object layout {"
  for f in "$ROOT"/android/app/src/main/res/layout/*.xml; do [ -e "$f" ] && echo "    const val $(basename "$f" .xml) = 0"; done
  echo "  }"
  echo "  object id {"
  cat "$ROOT"/android/app/src/main/res/layout/*.xml 2>/dev/null | grep -o '@+id/[A-Za-z0-9_]*' | sort -u | sed 's#@+id/#    const val #; s#$# = 0#'
  echo "  }"
  echo "}"
} > "$TMP/stub/ua/faric/aolithium/R.kt"
java -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$ANDROID_JAR:$(jar kotlin-stdlib)" -d "$TMP/out" \
  $(find "$ROOT/android/app/src/main/java" -name '*.kt') "$TMP/stub/ua/faric/aolithium/BuildConfig.kt" "$TMP/stub/ua/faric/aolithium/R.kt"
echo "Компіляція: OK"
