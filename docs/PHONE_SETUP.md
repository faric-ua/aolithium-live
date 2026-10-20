# Телефон: перший запуск Aolithium Live

## Основний телефон (A26) — один раз поставити меню

У Termux вставити один рядок (єдина команда; далі — лише пункти меню):

```
git clone https://github.com/faric-ua/aolithium-live ~/aolithium-live && bash ~/aolithium-live/menu.sh
```

Ярлик «Aolithium Live» для Termux:Widget ставиться сам, щойно меню відкрилось. На віджеті — оновлення (⟳) → «Aolithium Live».
Пункт **9** — те саме вручну з підказкою.

## Один раз — ключ підпису (лише на A26)

Меню → **7**: створює **свій** ключ `~/.android/aolithium-live.jks` (не Track Catcher), передає в секрети GitHub
(`AL_KEYSTORE_B64`, `AL_STORE_PASSWORD`, `AL_KEY_ALIAS`, `AL_KEY_PASSWORD`, `AL_CERT_SHA256`) і запускає збірку релізу.
Потрібні Java 17 (`keytool`) і `gh`, що увійшов у GitHub, — вони вже є після Renault/Dorama/Track Catcher.
Меню → **8** — резервна копія ключа в `Documents/Aolithium Live/key-backup/` (нікому не пересилати).

## Встановити / оновити

Меню → **2**: останній реліз з GitHub, перевірка SHA-256, копія в `Documents/Aolithium Live/apk/vX.Y.Z/` і вікно встановлення.

## M30s (запасний)

Той самий рядок `git clone …` у Termux. Ключ там **не створювати** (пункт 7 сам відмовить). Встановлення — пункт **2** або **5**.

## Huawei Mate 10 Lite (Android 8, без Termux)

Меню на A26 → **4** показує обидва способи:
1. На Huawei у браузері: `github.com/faric-ua/aolithium-live/releases/latest` → «Assets» → `Aolithium-Live-v….apk` → встановити.
2. З A26: пункт 2 → пункт 13 (папка з APK) → «Поділитися» → Quick Share / Bluetooth → Huawei.

## Дозволи в застосунку

| Android | Що спитає | Навіщо |
|---|---|---|
| 12 і новіші (A26) | «Пристрої поблизу» | шукати й підключати батарею |
| 8–11 (M30s, Huawei, Redmi) | «Місцезнаходження» + увімкнена геолокація | так Android дозволяє пошук Bluetooth LE; місцезнаходження застосунок не читає |

## Резерв — збірка на телефоні

Меню → **11** (як Dorama DEV 17 і Track Catcher 11): потрібні Java 17, `~/android-sdk`, ARM64 `aapt2`, `~/gradle/gradle-8.13`
і ключ з пункту 7. Перша збірка завантажує Android Gradle Plugin і Kotlin з інтернету; далі пункт **12** — без інтернету.
Журнал збірки — `Documents/Aolithium Live/logs/`.
