#!/usr/bin/env bash
# Відкрити в «Моїх файлах» папку з останнім APK Aolithium Live (з GitHub, зібраним тут чи на іншому телефоні).
set -u
. "$(dirname -- "$0")/al-common.sh"
apk="$(ls -t "$AL_DOCS"/apk/*/*.apk 2>/dev/null | head -n 1)"
if [ -z "$apk" ]; then
  fail "На цьому телефоні ще немає жодного APK Aolithium Live."
  info "Пункт 2 — завантажити з GitHub; пункт 11 — зібрати тут."
  exit 1
fi
folder="$(basename "$(dirname "$apk")")"
echo "Папка: Documents › Aolithium Live › apk › $folder"
info "APK: $(basename "$apk")"
if al_open_dir "Documents/Aolithium Live/apk/$folder"; then
  ok "Відкрито. Торкнись APK, щоб встановити."
else
  warn "Android не відкрив папку сам. Відкрий «Мої файли» → Documents → Aolithium Live → apk → $folder."
fi
