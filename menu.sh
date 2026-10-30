#!/usr/bin/env bash
# Aolithium Live — меню для Termux (як у Track Catcher).
# Правило: номери пунктів ніколи не зсуваються; нове — наступним вільним номером.
set -u

REPO="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$REPO/tools/termux"
export AL_REPO="$REPO"
cd "$REPO" || exit 1

# Раз на запуск — тихо оновити меню з GitHub і перезапуститись.
# al-sync.sh: 0 — оновлено, 10 — уже актуально, інше — проблема (показати й чекати Enter).
if [ -z "${AL_MENU_SYNCED:-}" ]; then
  export AL_MENU_SYNCED=1
  bash "$TOOLS/al-sync.sh" --quiet
  rc=$?
  if [ "$rc" = 0 ]; then
    exec bash "$REPO/menu.sh"
  elif [ "$rc" != 10 ]; then
    echo
    read -r -p "Натисни Enter, щоб відкрити меню…" _ || exit 0  # Termux закрили не пунктом 0 — вийти, а не крутитись (2026-10-30)
  fi
fi

# Ярлик Termux:Widget завжди відповідає репо (ставиться/оновлюється тихо).
bash "$TOOLS/al-widget.sh" --quiet 2>/dev/null || true

pause_menu() {
  echo
  read -r -p "Натисни Enter, щоб повернутись у меню…" _ || exit 0  # Termux закрили не пунктом 0 — вийти, а не крутитись (2026-10-30)
}

# Шапка меню: велика назва між смужками (faric 2026-10-16 02:12 — «щоб одразу видно, яке це меню»).
# Букви — з напівблоків ▀▄█ (шрифт 3×5), ширина 37 знаків. Вузький екран — проста шапка одним рядком.
show_banner() {
  local c="\033[1;36m" d="\033[0;36m" z="\033[0m" cols
  cols="$(stty size 2>/dev/null | cut -d' ' -f2)"
  local line="━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  if [ "${cols:-80}" -ge 37 ] 2>/dev/null; then
    echo -e "${d}${line}${z}"
    echo -e "${c} █▀█ █▀█ █   ▀█▀ ▀█▀ █ █ ▀█▀ █ █ █▄█${z}"
    echo -e "${c} █▀█ █ █ █    █   █  █▀█  █  █ █ █▀█${z}"
    echo -e "${c} ▀ ▀ ▀▀▀ ▀▀▀ ▀▀▀  ▀  ▀ ▀ ▀▀▀ ▀▀▀ ▀ ▀${z}"
    echo -e "${c}           █   ▀█▀ █ █ █▀▀${z}"
    echo -e "${c}           █    █  █ █ █▀${z}"
    echo -e "${c}           ▀▀▀ ▀▀▀  ▀  ▀▀▀${z}"
    echo -e "${d}${line}${z}"
  else
    echo -e "${c}━━ AOLITHIUM LIVE ━━${z}"
  fi
}

run_tool() {
  clear
  bash "$TOOLS/$1"
  pause_menu
}

installed_build() {
  # versionCode встановленого Aolithium Live (якщо Android дозволяє Termux це побачити)
  pm list packages --show-versioncode ua.faric.aolithium 2>/dev/null | sed -n 's/.*package:ua\.faric\.aolithium versionCode:\([0-9]*\).*/\1/p' | head -n 1
}

while true; do
  clear
  version="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$REPO/android/app/build.gradle.kts" 2>/dev/null | head -n 1)"
  code="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$REPO/android/app/build.gradle.kts" 2>/dev/null | head -n 1)"
  inst="$(installed_build)"
  show_banner
  echo "$(git branch --show-current 2>/dev/null) · $(git rev-parse --short HEAD 2>/dev/null) · нова версія v${version:-?} (build ${code:-?})"
  if [ -n "$inst" ]; then
    if [ "$inst" = "$code" ]; then echo "На телефоні: build $inst — найновіша"; else echo "На телефоні: build $inst — є новіша, пункт 2"; fi
  fi
  echo
  echo " ВСТАНОВИТИ / ОНОВИТИ ЗАСТОСУНОК"
  echo "  2 — Встановити нову версію з GitHub"
  echo " 10 — Вікно встановлення сховалось? Відкрити ще раз"
  echo "  5 — Встановити версію, зібрану на іншому телефоні"
  echo " 13 — Відкрити папку з останнім APK"
  echo "  4 — Телефон без Termux (Huawei): як поставити"
  echo
  echo " ЩОДНЯ"
  echo "  1 — Оновити це меню"
  echo "  3 — Статус: що на GitHub і що на телефоні"
  echo "  6 — Командний рядок (shell). Назад у меню: exit"
  echo
  echo " ЗБІРКА НА ЦЬОМУ ТЕЛЕФОНІ"
  echo " 11 — Зібрати тут і встановити (і відправити для іншого телефона)"
  echo " 12 — Зібрати тут без інтернету"
  echo
  echo " ПЕРШЕ НАЛАШТУВАННЯ (один раз)"
  echo "  7 — Ключ підпису: створити (лише на основному телефоні)"
  echo "  8 — Ключ підпису: резервна копія"
  echo "  9 — Ярлик меню на головному екрані (Termux:Widget)"
  echo
  echo "  0 — Вийти"
  echo
  read -r -p "Вибір: " choice || exit 0  # Termux закрили не пунктом 0 — вийти, а не крутитись (2026-10-30)
  case "$choice" in
    1)
      clear
      bash "$TOOLS/al-sync.sh"
      rc=$?
      if [ "$rc" = 0 ]; then
        # оновилось — меню перезапускається саме, з новим кодом
        echo "Перезапускаю меню…"
        sleep 1
        exec bash "$REPO/menu.sh"
      elif [ "$rc" = 10 ]; then
        sleep 1
      else
        pause_menu
      fi
      ;;
    2) run_tool al-install-latest.sh ;;
    3) run_tool al-status.sh ;;
    4) run_tool al-other-phone.sh ;;
    5) run_tool al-install-phone-build.sh ;;
    6)
      clear
      echo "Shell у папці Aolithium Live. Щоб повернутись у меню — набери: exit"
      echo
      (cd "$REPO" && bash -l) || true
      # після exit — меню знову, з автооновленням (раптом щось змінилось)
      unset AL_MENU_SYNCED
      exec bash "$REPO/menu.sh"
      ;;
    7) run_tool al-signing-setup.sh ;;
    8) run_tool al-signing-backup.sh ;;
    9) run_tool al-widget.sh ;;
    10) run_tool al-reopen-install.sh ;;
    13) run_tool al-open-apk-folder.sh ;;
    11) run_tool al-local-build.sh ;;
    12)
      clear
      bash "$TOOLS/al-local-build.sh" --offline
      pause_menu
      ;;
    0 | q | Q) exit 0 ;;
    *) ;;
  esac
done
