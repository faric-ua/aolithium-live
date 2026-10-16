#!/usr/bin/env bash
# Знімки екранів Aolithium Live на емуляторі: книжкова й альбомна, шрифт 1.0 і 1.3, вікно після повороту.
# Запускається в CI (ui-screens.yml). Батареї в емуляторі нема — показуємо демо (ті самі байти, що шле плата JBD).
set -u
OUT="${1:-ui-screens}"
APK="android/app/build/outputs/apk/debug/app-debug.apk"
PKG="ua.faric.aolithium.dev"
ACT="$PKG/ua.faric.aolithium.ui.MainActivity"
mkdir -p "$OUT"

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "знімок: $1"; }
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
shot p03-battery-idle 4
scroll_down
shot p03b-battery-idle-cells
scroll_down
shot p03c-battery-idle-board
start --es demo CHARGING
shot p04-battery-charging 4
start --es demo COLD
shot p05-battery-cold 4
scroll_down
shot p05b-battery-cold-scrolled
start --es demo IDLE --es dialog log
shot p06-log-dialog 4
adb shell input keyevent KEYCODE_BACK
start --es demo IDLE --es dialog rename
shot p07-rename-dialog 4
rotate 1
shot l01-rename-after-rotation
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
start --es demo CHARGING
shot l02-battery-two-pane 4
start --es page HELP
shot l03-help-two-pane
start --es page HOME
shot l04-home-two-pane
rotate 0
start --es page HELP
shot p08-help
adb shell settings put system font_scale 1.3
sleep 2
start --es demo IDLE
shot p09-battery-font130 4
start --es page HOME
shot p10-home-font130
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
