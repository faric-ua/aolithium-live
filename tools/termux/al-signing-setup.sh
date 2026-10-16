#!/usr/bin/env bash
# Один раз: створити власний ключ підпису Aolithium Live на телефоні і передати його в секрети GitHub.
# Ключ потрібен, щоб кожна нова версія ставилась поверх старої (без видалення застосунку).
set -u
. "$(dirname -- "$0")/al-common.sh"

if ! command -v keytool >/dev/null 2>&1; then
  fail "Немає Java (keytool)."
  info "Java 17 уже ставилась для збірки Dorama; якщо ні — напиши Claude."
  exit 1
fi
if ! al_gh_ready; then
  fail "gh не увійшов у GitHub — без цього ключ не передати."
  exit 1
fi
mkdir -p "$HOME/.android" && chmod 700 "$HOME/.android"

# Ключ уже в GitHub, а на цьому телефоні його немає (напр., старий телефон) — новий НЕ створюємо:
# він перезаписав би секрети, і нові версії перестали б ставитись поверх на основному телефоні.
if [ ! -f "$AL_KEY" ] && gh secret list -R "$AL_SLUG" 2>/dev/null | cut -f1 | grep -qx AL_KEYSTORE_B64; then
  fail "Ключ підпису вже є в GitHub — його створив інший телефон. Новий не створюю."
  info "На цьому телефоні ключ не потрібен: встановлюй через пункт 2 або 5."
  exit 1
fi

if [ -f "$AL_KEY" ] && [ -f "$AL_PROPS" ]; then
  ok "Ключ уже є на телефоні — новий не створюю (інакше нові версії не встановляться поверх)."
else
  if [ -f "$AL_KEY" ] || [ -f "$AL_PROPS" ]; then
    fail "Знайдено лише частину ключа ($AL_KEY / $AL_PROPS). Нічого не чіпаю — напиши Claude."
    exit 1
  fi
  pass="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 28)"
  if ! keytool -genkeypair -keystore "$AL_KEY" -storetype PKCS12 -storepass "$pass" -keypass "$pass" \
    -alias "$AL_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Aolithium Live, O=faric-ua, C=UA" >/dev/null 2>&1; then
    fail "keytool не створив ключ."
    exit 1
  fi
  umask 077
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' "$AL_KEY" "$pass" "$AL_ALIAS" "$pass" > "$AL_PROPS"
  chmod 600 "$AL_KEY" "$AL_PROPS"
  ok "Ключ створено: ~/.android/aolithium-live.jks"
fi

pass="$(al_prop storePassword)"
alias_name="$(al_prop keyAlias)"
cert="$(keytool -list -v -keystore "$AL_KEY" -storepass "$pass" -alias "$alias_name" 2>/dev/null \
  | sed -n 's/^.*SHA256: *//p' | head -n 1 | tr -d ': ' | tr 'A-F' 'a-f')"
if [ -z "$cert" ]; then
  fail "Не вдалося прочитати сертифікат ключа."
  exit 1
fi
info "Сертифікат: ${cert:0:16}…"

echo "Передаю ключ у секрети GitHub…"
base64 -w0 "$AL_KEY" | gh secret set AL_KEYSTORE_B64 -R "$AL_SLUG" >/dev/null || { fail "Не вдалося записати AL_KEYSTORE_B64"; exit 1; }
gh secret set AL_STORE_PASSWORD -R "$AL_SLUG" --body "$pass" >/dev/null || { fail "AL_STORE_PASSWORD"; exit 1; }
gh secret set AL_KEY_ALIAS -R "$AL_SLUG" --body "$alias_name" >/dev/null || { fail "AL_KEY_ALIAS"; exit 1; }
gh secret set AL_KEY_PASSWORD -R "$AL_SLUG" --body "$pass" >/dev/null || { fail "AL_KEY_PASSWORD"; exit 1; }
gh secret set AL_CERT_SHA256 -R "$AL_SLUG" --body "$cert" >/dev/null || { fail "AL_CERT_SHA256"; exit 1; }
ok "Ключ у GitHub (5 секретів)."

echo "Запускаю збірку релізу…"
if gh workflow run android.yml -R "$AL_SLUG" --ref main >/dev/null 2>&1; then
  ok "Збірка запущена. Через 5–7 хв — пункт 2 «Встановити останній реліз»."
else
  warn "Збірку не запущено автоматично — напиши Claude, він запустить."
fi
echo
info "Порада: пункт 8 — зробити резервну копію ключа."
