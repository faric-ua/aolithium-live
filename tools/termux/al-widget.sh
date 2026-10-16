#!/usr/bin/env bash
# Ярлик «Aolithium Live» для Termux:Widget — копія з репо (termux-widget/) у ~/.shortcuts.
# --quiet: тихо, лише якщо ярлика ще немає або він застарів (викликає menu.sh при кожному запуску).
set -u
. "$(dirname -- "$0")/al-common.sh"
quiet=0
[ "${1:-}" = "--quiet" ] && quiet=1
src="$AL_REPO/termux-widget/Aolithium Live"
dest_dir="$HOME/.shortcuts"
dest="$dest_dir/Aolithium Live"
[ -f "$src" ] || { [ "$quiet" = 1 ] || fail "Немає $src"; exit 1; }
if [ "$quiet" = 1 ] && cmp -s "$src" "$dest" 2>/dev/null; then
  exit 0
fi
mkdir -p "$dest_dir"
chmod 700 "$dest_dir"
install -m 700 "$src" "$dest"
if [ "$quiet" = 1 ]; then
  echo "✅ Ярлик «Aolithium Live» для Termux:Widget оновлено."
else
  ok "Ярлик «Aolithium Live» є в Termux:Widget."
  info "Тепер: віджет Termux:Widget на головному екрані → кнопка оновлення (⟳) → «Aolithium Live»."
  info "Віджета ще нема? Тримай палець на головному екрані → Віджети → Termux:Widget."
fi
