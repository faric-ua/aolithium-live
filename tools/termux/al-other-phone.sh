#!/usr/bin/env bash
# Телефон без Termux (Huawei Mate 10 Lite, бабусин Redmi…): як поставити Aolithium Live.
# Репо публічне — APK можна взяти просто браузером зі сторінки релізів.
set -u
. "$(dirname -- "$0")/al-common.sh"
echo "Aolithium Live на телефоні без Termux — два способи:"
echo
echo "СПОСІБ 1 — браузером на тому телефоні"
echo "   Відкрий у браузері (Chrome) адресу:"
echo
echo "   github.com/$AL_SLUG/releases/latest"
echo
echo "   Унизу «Assets» → Aolithium-Live-v…apk → завантажити → відкрити → «Встановити»."
echo "   Android спитає дозвіл ставити з браузера — дозволити."
echo
echo "СПОСІБ 2 — передати файл з цього телефона"
echo "   Пункт 2 (завантажити) → потім відкриється папка з APK (пункт 13)."
echo "   Довге натискання на APK → «Поділитися» → Quick Share / Bluetooth → той телефон."
echo
info "Застосунок потребує Android 8 або новіший і Bluetooth LE."
info "На Android 8–11 він попросить дозвіл на місцезнаходження і ввімкнену геолокацію — так Android шукає Bluetooth."
