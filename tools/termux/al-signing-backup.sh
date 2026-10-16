#!/usr/bin/env bash
# Резервна копія ключа підпису в Documents/Aolithium Live/key-backup.
set -u
. "$(dirname -- "$0")/al-common.sh"
if [ ! -f "$AL_KEY" ] || [ ! -f "$AL_PROPS" ]; then
  fail "Ключа ще немає — спершу пункт 7."
  exit 1
fi
al_need_storage || exit 1
dest="$AL_DOCS/key-backup"
mkdir -p "$dest"
stamp="$(date +%Y%m%d-%H%M)"
cp "$AL_KEY" "$dest/aolithium-live-$stamp.jks"
cp "$AL_PROPS" "$dest/aolithium-live-signing-$stamp.properties"
ok "Копія: Documents/Aolithium Live/key-backup/ ($stamp)"
warn "Цей файл = право підписувати Aolithium Live. Нікому не пересилай і не клади в GitHub."
