# Aolithium Live — правила для Claude

Тут лише правила й карта. Стан — `STATUS.md`. Задачі — `TODO.md`. Історія рішень і проблем — `docs/PROJECT_LEDGER.md`.
Задум і повний план — `docs/PLAN.md`. Протокол плати — `docs/PROTOCOL_JBD.md`.

## Що це

Свій Android-застосунок для домашніх батарей faric, щоб **Aolithium «жив далі»**: батарею Aolithium 100 А·год зняли
з виробництва, застосунок прибрали з Google Play. Усередині батареї — стандартна плата **JBD** з відкритим протоколом
(Bluetooth LE). Пізніше в тому ж застосунку — батарея **Logic Power** (плата **JK BMS**, у мами faric).

## Власник

- **faric** (`faric-ua`). Працює **лише з телефона** (Samsung Galaxy A26 5G) через Termux-меню. Не програміст.
- Відповідати українською, коротко, простими словами. Дії на телефоні — **тільки пунктами меню** (`AOLITHIUM LIVE: 2`), без команд.
- Кожна робоча відповідь закінчується блоком **«Від тебе зараз:»**. **Повний формат відповіді — `docs/RESPONSE_ALGORITHM.md`**
  (однаковий у всіх репо faric-ua; різні лише меню).
- Усе, що faric має скопіювати, — **окремим блоком коду**; перед блоком — куди вставити.
- Під час перевірки на телефоні — **один крок за раз**. Якщо faric каже «поки нічого не роби» — лише записати й чекати.
- Нове від faric під час тестів — спершу в `TODO.md`; дрібниці — пачкою, не окремими версіями.

## Старт сесії

1. `CLAUDE.md` → `STATUS.md` (розділ «Точка зупинки») → `TODO.md` → верх `docs/PROJECT_LEDGER.md`.
   Плюс нові записи в `docs/INSPECTOR.md` (пише чат «GitHub Storage» — інспектор усіх репо): прочитати, прийняти; не згоден — сказати faric.
2. Неясно, що робити, — одне питання.

## Цикл роботи

1. Код + тести у своїй копії → гілка `feat/…` / `fix/…` / `docs/…` → PR у `main`.
2. Зелений CI (`Checks`, `Android`) → мерж (squash) самому. Репо **публічне** — хвилини Actions безкоштовні.
3. Нова версія для телефона = підняти `versionName` (X.Y.Z) **і** `versionCode` в `android/app/build.gradle.kts`.
   Мерж у `main` сам публікує реліз `vX.Y.Z` (лише з ключем підпису `AL_*`, лише якщо такого тегу ще нема).
4. Дати faric шлях: `AOLITHIUM LIVE: 2` і що перевірити (до 3 кроків).
5. Результат («пас / не пас», скрін) — у `STATUS.md` (таблиця + журнал) і `docs/PROJECT_LEDGER.md`.

## Безпека батареї — головне правило

- **Застосунок лише читає плату.** У плату йдуть тільки команди читання (`Jbd.READ_COMMANDS`: 0x03, 0x04, 0x05, 0xAA),
  зібрані `Jbd.readCommand` — вона відмовляє на будь-яку іншу. Контракт-тест `test_app_only_reads_the_board` це стереже.
- Запис налаштувань, вмикання/вимикання ключів — лише з етапу v0.5, лише після **резервної копії** налаштувань у файл
  і окремого «так» faric. До того — не додавати навіть «на потім».
- Показники — довідка, не захист: батарею захищає сама плата. У текстах не обіцяти безпеку.

## Телефони faric для перевірки

| Телефон | Android | Коли |
|---|---|---|
| Samsung Galaxy A26 5G | 15/16 | основний; Termux-меню, ключ підпису |
| Samsung M30s | 11 | **запасний** — лише коли faric скаже, що вільний; не нагадувати |
| Huawei Mate 10 Lite | 8 | перший телефон faric; **без Termux** → ставити через браузер (`AOLITHIUM LIVE: 4`) |

Звідси **мін. Android 8 (API 26)**: Android 8–11 шукають Bluetooth LE лише з дозволом на місцезнаходження й увімкненою
геолокацією (`ble/BtAccess.kt`); Android 12+ — «Пристрої поблизу» з `neverForLocation`.
Новий API — лише з перевіркою `Build.VERSION.SDK_INT` (урок Dorama T94); CI-лінтер `NewApi` і тест `test_new_api_calls_are_guarded`.

## Заборонено

- Просити видалити застосунок, чистити дані, перевстановлювати. Видаляти чи міняти Aolithium 1.3 на телефонах.
- Класти в репо ключі, паролі, **справжню адресу Bluetooth батареї faric** (лише в пам'яті телефона; у тестах — вигадані
  `A4:C1:37:00:00:01`), записи екрана, журнали з телефона. `scripts/audit_repo.py` у CI.
- Копіювати чи розбирати код застосунку Aolithium — знання протоколу лише з відкритих джерел (`THIRD_PARTY.md`).
- Перезаписувати тег релізу; публікувати APK без стабільного ключа.
- Зсувати номери пунктів меню (нове — наступним вільним номером).
- Стандартні сірі діалоги/кнопки Android, кольори поза `Palette.kt`, галочки ✓ (`scripts/audit_ui.py`).

## Карта

| Що | Де |
|---|---|
| Ядро без Android: протокол JBD, розбір, знімок стану, формат чисел, впізнавання плат | `android/app/src/main/java/ua/faric/aolithium/core/` + тести `src/test/…` |
| Bluetooth: пошук, зв'язок з платою, демо, журнал зв'язку | `…/ble/` |
| «Мої батареї» (пам'ять телефона) | `…/data/Batteries.kt` |
| Екрани | `…/ui/` (`Palette.kt` — кольори, `Ui.kt` — кнопки/картки; `Overlay`, `LineIcon`, `FlowLayout` — з Track Catcher) |
| Меню телефона | `menu.sh`, `tools/termux/al-*.sh` |
| CI | `.github/workflows/android.yml` (тести, лінтер, збірка, реліз), `checks.yml`, `ui-screens.yml` |
| Аудити, контракти | `scripts/audit_*.py`, `tests/` |
| Перевірки для Claude без Gradle | `tools/dev/run_core_tests.sh`, `tools/dev/compile_android.sh` |

## Технічне

- Gradle 8.13 **без wrapper** (CI — setup-gradle; телефон — `~/gradle/gradle-8.13`). AGP 8.13.2, Kotlin 2.3.21, SDK 36, **minSdk 26**.
- **Жодних бібліотек** у застосунку (навіть androidx) — лише Android. Тести — JUnit.
- Тести ядра локально (без Gradle): `bash tools/dev/run_core_tests.sh` (Kotlin-компілятор і JUnit з `/opt/gradle-*/lib`).
- Компіляція всього застосунку локально: `ANDROID_JAR=… bash tools/dev/compile_android.sh` (android.jar — з GitHub
  `Reginer/aosp-android-jar`, тека `android-36`; там є й приховані API, тож справжня перевірка — CI).
- Python: `python3 -m unittest discover -s tests`, `python3 scripts/audit_ui.py`, `python3 scripts/audit_repo.py`.
- Логи Actions недоступні — усе важливе CI виводить анотаціями: `gh api repos/faric-ua/aolithium-live/check-runs/<id>/annotations`.
- Знімки екранів без телефона: пуш у `diag/ui-screens` → емулятор (демо-режим) → PNG у гілці `diag/ui-screens-out`.
- Ключ підпису: **свій** (`~/.android/aolithium-live.jks`, секрети `AL_*`), створює телефон (меню 7). Не ключ Track Catcher.
- Пакет `ua.faric.aolithium` (debug — `.dev`); стоїть поруч з оригінальним `com.ao.lithium` 1.3, нічого в ньому не чіпає.
