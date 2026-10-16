#!/usr/bin/env bash
# Спільне для скриптів меню Aolithium Live.
AL_SLUG="faric-ua/aolithium-live"
AL_REPO="${AL_REPO:-$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)}"
AL_DOCS="/storage/emulated/0/Documents/Aolithium Live"
AL_KEY="$HOME/.android/aolithium-live.jks"
AL_PROPS="$HOME/.android/aolithium-live-signing.properties"
AL_ALIAS="aolithiumlive"

ok() { printf '✅ %s\n' "$*"; }
info() { printf '   %s\n' "$*"; }
warn() { printf '⚠️  %s\n' "$*"; }
fail() { printf '❌ %s\n' "$*"; }

al_version() {
  sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$AL_REPO/android/app/build.gradle.kts" 2>/dev/null | head -n 1
}

al_need_storage() {
  if ! mkdir -p "$AL_DOCS" 2>/dev/null; then
    fail "Termux не має доступу до пам'яті телефона."
    info "Один раз: команда termux-setup-storage → «Дозволити»."
    return 1
  fi
}

al_gh_ready() {
  command -v gh >/dev/null 2>&1 && gh auth status -h github.com >/dev/null 2>&1
}

al_prop() {
  sed -n "s/^$1=//p" "$AL_PROPS" 2>/dev/null | head -n 1
}

# Відкрити папку пам'яті телефона в «Моїх файлах». Аргумент — шлях від /storage/emulated/0,
# напр. "Documents/Aolithium Live/apk/v0.1.4". Три спроби, як у Renault (A26 і M30s відкривають по-різному).
al_open_dir() {
  local enc uri
  enc="$(printf '%s' "$1" | sed 's/%/%25/g; s/ /%20/g; s#/#%2F#g')"
  uri="content://com.android.externalstorage.documents/document/primary%3A$enc"
  if am start -a android.intent.action.VIEW -d "$uri" -t vnd.android.document/directory 2>&1 | grep -qi "error"; then
    :
  else
    return 0
  fi
  if am start -a android.intent.action.OPEN_DOCUMENT_TREE --eu android.provider.extra.INITIAL_URI "$uri" 2>&1 | grep -qi "error"; then
    return 1
  fi
  return 0
}

al_open() {
  # Відкрити файл (APK → встановлення).
  if command -v termux-open >/dev/null 2>&1; then
    termux-open --view "$1" >/dev/null 2>&1 || termux-open "$1" >/dev/null 2>&1
  else
    return 1
  fi
}
