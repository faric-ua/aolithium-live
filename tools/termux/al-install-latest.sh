#!/usr/bin/env bash
# Завантажити останній реліз з GitHub, перевірити SHA-256, відкрити встановлення.
set -u
. "$(dirname -- "$0")/al-common.sh"
al_need_storage || exit 1

echo "Шукаю останній реліз Aolithium Live…"
tag=""
if al_gh_ready; then
  tag="$(gh release list -R "$AL_SLUG" -L 10 --exclude-drafts --json tagName -q '.[].tagName' 2>/dev/null | grep -v '^phone-build$' | head -n 1 || true)"
fi
if [ -z "$tag" ]; then
  tag="$(curl -fsSL "https://api.github.com/repos/$AL_SLUG/releases?per_page=10" 2>/dev/null \
    | grep -o '"tag_name": *"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/' | grep -v '^phone-build$' | head -n 1)"
fi
if [ -z "$tag" ]; then
  fail "Релізів ще немає."
  info "Перший раз: пункт 7 (ключ підпису) — він сам запустить збірку. Через 5–7 хв — знову пункт 2."
  exit 1
fi
version="${tag#v}"
apk="Aolithium-Live-v$version.apk"
tmp="$HOME/.cache/aolithium-live/$tag"
mkdir -p "$tmp"
rm -f "$tmp/$apk" "$tmp/$apk.sha256"

echo "Реліз: $tag. Завантажую…"
if al_gh_ready; then
  gh release download "$tag" -R "$AL_SLUG" -p "$apk" -p "$apk.sha256" -D "$tmp" --clobber >/dev/null 2>&1 || true
fi
base="https://github.com/$AL_SLUG/releases/download/$tag"
[ -s "$tmp/$apk" ] || curl -fsSL -o "$tmp/$apk" "$base/$apk"
[ -s "$tmp/$apk.sha256" ] || curl -fsSL -o "$tmp/$apk.sha256" "$base/$apk.sha256"
if [ ! -s "$tmp/$apk" ] || [ ! -s "$tmp/$apk.sha256" ]; then
  fail "Не вдалося завантажити $apk."
  exit 1
fi
if ! (cd "$tmp" && sha256sum -c "$apk.sha256" >/dev/null 2>&1); then
  fail "Контрольна сума не збігається — файл пошкоджений. Не встановлюю."
  exit 1
fi
ok "SHA-256 збігається."

dest="$AL_DOCS/apk/v$version"
mkdir -p "$dest"
cp -f "$tmp/$apk" "$tmp/$apk.sha256" "$dest/"
(cd "$dest" && sha256sum -c "$apk.sha256" >/dev/null 2>&1) || { fail "Копія в Documents пошкоджена."; exit 1; }
ok "Збережено: Documents/Aolithium Live/apk/v$version/$apk"
echo
echo "Відкриваю встановлення. Натисни «Встановити» (перший раз) або «Оновити»."
echo "Очікується: Aolithium Live $version"
if ! al_open "$dest/$apk"; then
  warn "Не відкрилось автоматично. Відкрий файл у «Моїх файлах»: Documents → Aolithium Live → apk → v$version."
fi
