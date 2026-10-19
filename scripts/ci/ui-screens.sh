#!/usr/bin/env bash
# Знімки екранів Aolithium Live на емуляторі: книжкова й альбомна, шрифт 1.0 і 1.3, вікно після повороту.
# Запускається в CI (ui-screens.yml). Батареї в емуляторі нема — показуємо демо (ті самі байти, що шле плата JBD).
set -u
OUT="${1:-ui-screens}"
APK="android/app/build/outputs/apk/debug/app-debug.apk"
PKG="ua.faric.aolithium.dev"
ACT="$PKG/ua.faric.aolithium.ui.MainActivity"
mkdir -p "$OUT"

# Системне вікно «Pixel Launcher isn't responding» на повільному емуляторі закривало знімки (18.10) — ховаємо.
shot() {
  sleep "${2:-3}"
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  sleep 0.5
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "знімок: $1"
}
rotate() {
  adb shell settings put system user_rotation "$1"
  adb shell cmd window user-rotation lock "$1" >/dev/null 2>&1 || adb shell wm user-rotation lock "$1" >/dev/null 2>&1 || true
  sleep 4
}
start() { adb shell am start -n "$ACT" "$@" >/dev/null; }
scroll_down() { adb shell input swipe 540 1700 540 500 300; sleep 1; }

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system accelerometer_rotation 0
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1 || true
adb install -r "$APK"
adb logcat -c
rotate 0

start
shot p01-home-first-run 5
adb shell pm grant "$PKG" android.permission.BLUETOOTH_SCAN || true
adb shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT || true
adb shell svc bluetooth enable || true
start --es page HOME
shot p02-home-ready 4
start --es demo IDLE
shot p03-now-idle 4
scroll_down
shot p03b-now-idle-scrolled
start --es demo CHARGING
shot p04-now-charging 4
scroll_down
shot p04b-now-charging-scrolled
start --es demo DISCHARGING
shot p04c-now-discharging 4
scroll_down
shot p04d-now-discharging-scrolled
start --es demo COLD
shot p05-now-cold 4
start --es demo CHARGING --es dialog tab:CELLS
shot p06-cells 4
scroll_down
shot p06b-cells-scrolled
# T19: плитки без вирівнювання (усі однієї висоти, мітки в один рядок).
start --es demo IDLE --es dialog tab:CELLS
shot p06c-cells-idle 4
start --es demo IDLE --es dialog tab:CHARTS:D1
shot p07-charts 5
scroll_down
shot p07b-charts-scrolled
scroll_down
shot p07c-charts-scrolled2
scroll_down
shot p07d-charts-scrolled3
start --es demo IDLE --es dialog tab:BOARD
shot p08-board 4
scroll_down
shot p08b-board-scrolled
start --es demo IDLE --es dialog tab:MORE
shot p09-more 4
scroll_down
shot p09b-more-dictionary
scroll_down
shot p09c-more-alerts
# Віджет (v0.3.1): на домашній екран емулятора не поставиш — показуємо той самий малюнок у вікні.
start --es demo DISCHARGING --es dialog widgets
shot p17-widget-discharging 10
start --es demo CHARGING --es dialog widgets
shot p17b-widget-charging 10
# v0.4 (T5): плата JK BMS (Logic Power) — демо з кадрами JK: «Зараз», «Комірки» (балансир, опір дротів), «Плата».
start --es demo JK
shot p19-jk-now 5
start --es demo JK --es dialog tab:CELLS
shot p19b-jk-cells 4
scroll_down
shot p19c-jk-cells-scrolled
scroll_down
shot p19d-jk-cells-scrolled2
start --es demo JK --es dialog tab:BOARD
shot p19e-jk-board 4
scroll_down
shot p19f-jk-board-scrolled
start --es page HELP
scroll_down; scroll_down; scroll_down
shot p19g-help-demos
# T25 (відео faric 19.10 22:12): рядок «Зв'язок» сталої висоти — на всіх станах спідометр на тому самому місці.
for st in CONNECTING READING LOST FAILED LIVE; do
  start --es demo DISCHARGING --es dialog "status:$st"
  shot "p18-link-$st" 4
done
start --es demo DISCHARGING --es dialog link
shot p18-link-info 3
# Без зв'язку й без збережених даних (faric 18.10 21:48): усі вкладки з прочерками.
start --es demo OFFLINE
shot p14-offline-now 6
start --es demo OFFLINE --es dialog tab:CELLS
shot p15-offline-cells 3
start --es demo OFFLINE --es dialog tab:BOARD
shot p16-offline-board 3
start --es demo IDLE --es dialog info:zones
shot p10-info-zones 4
# Поворот телефона: з v0.2.5 застосунок лишається книжковим (faric 19.10 04:25) — знімки l0* мають бути вертикальні.
rotate 1
shot l01-info-after-rotation
adb shell input keyevent KEYCODE_BACK
start --es demo CHARGING
shot l02-now-two-pane 4
start --es demo IDLE --es dialog tab:CHARTS:D1
shot l03-charts-two-pane 5
start --es page HELP
shot l04-help-two-pane
start --es page HOME
shot l05-home-two-pane
rotate 0
start --es page HELP
shot p11-help
adb shell settings put system font_scale 1.3
sleep 2
start --es demo IDLE
shot p12-now-font130 4
start --es demo IDLE --es dialog tab:CELLS
shot p12b-cells-font130 4
start --es page HOME
shot p13-home-font130
for st in LOST LIVE; do
  start --es demo DISCHARGING --es dialog "status:$st"
  shot "p18-link-$st-font130" 4
done
adb shell settings put system font_scale 1.0

# Падіння лише нашого застосунку (системні застосунки емулятора теж іноді падають — їх не рахуємо).
adb logcat -d -b crash > "$OUT/crash-all.txt" || true
grep -A 30 "Process: $PKG" "$OUT/crash-all.txt" > "$OUT/crash.txt" || true
adb logcat -d -s AndroidRuntime:E > "$OUT/runtime-errors.txt" || true
adb shell wm size > "$OUT/display.txt" 2>&1 || true
if [ -s "$OUT/crash.txt" ]; then
  echo "::error title=UI screens::Aolithium Live падав на емуляторі: $(head -c 600 "$OUT/crash.txt" | tr '\n' ' ')"
  exit 1
fi
echo "::notice title=UI screens::$(ls "$OUT"/*.png | wc -l) знімків, падінь немає"
