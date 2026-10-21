# Aolithium Live — правила для Claude

Тут лише правила й карта. Стан — `STATUS.md`. Задачі — `TODO.md`. Історія рішень і проблем — `docs/PROJECT_LEDGER.md`.
Задум і повний план — `docs/PLAN.md`. Протоколи плат — `docs/PROTOCOL_JBD.md` (Aolithium), `docs/PROTOCOL_JK.md` (Logic Power, v0.4).

## Що це

Свій Android-застосунок для домашніх батарей faric, щоб **Aolithium «жив далі»**: батарею Aolithium 100 А·год зняли
з виробництва, застосунок прибрали з Google Play. Усередині батареї — стандартна плата **JBD** з відкритим протоколом
(Bluetooth LE). Пізніше в тому ж застосунку — батарея **Logic Power** (плата **JK BMS**, у мами faric).

## Власник

- **faric** (`faric-ua`). Працює **лише з телефона** (Samsung Galaxy A26 5G) через Termux-меню. Не програміст.
- Відповідати **лише українською** — кожне повідомлення, зокрема підсумки й короткі службові (faric 20.10: «faric-ua — україномовний проєкт»). Коротко, простими словами. Дії на телефоні — **тільки пунктами меню** (`AOLITHIUM LIVE: 2`), без команд.
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

- **Застосунок лише читає плату.** У плату йдуть тільки команди читання — JBD: `Jbd.READ_COMMANDS` (0x03, 0x04, 0x05, 0xAA),
  зібрані `Jbd.readCommand`; JK: `Jk.READ_COMMANDS` (0x96, 0x97), зібрані `Jk.readCommand`. Обидві відмовляють на будь-яку
  іншу. Контракт-тест `test_app_only_reads_the_board` це стереже.
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
- Повертати екран: застосунок **лише книжковий** (faric 19.10 04:25), `screenOrientation="portrait"`, аудит UI-06.

## Карта

| Що | Де |
|---|---|
| Ядро без Android: протокол JBD, розбір, знімок стану, формат чисел, впізнавання плат | `android/app/src/main/java/ua/faric/aolithium/core/` + тести `src/test/…` |
| Bluetooth: пошук, зв'язок з платою, демо, журнал зв'язку | `…/ble/` (`JbdLink`, `JkLink` — вибір у `LinkHolder.create` за типом плати) |
| JK BMS (v0.4): протокол, збирач кадрів, розкладки 24S/32S, демо-кадри | `core/Jk.kt` (+ `JkTest`), `docs/PROTOCOL_JK.md` |
| Запис у фоні (v0.2.5): служба з тихим сповіщенням, будильник раз на 5 хв, поступається екрану; читання при розблокуванні (v0.4.9, T40 — `USER_PRESENT` слухає служба, з маніфесту Android 8+ не доходить) | `…/bg/RecordService.kt`, `bg/BgReceiver.kt`, правила — `core/Background.kt`, налаштування — `data/BgSettings.kt` |
| Сповіщення (v0.3): правила — без Android, показ — канали Android, перемикачі — «Ще»; смужка «Заряджається» (v0.4.9, T39) | `core/Alerts.kt` (+ `AlertsTest`), `core/ChargeNote.kt` (+ тест), `bg/Notifier.kt`, `data/AlertSettings.kt` |
| Віджет (v0.3.1): що писати — без Android, малюнок — Canvas, оновлення; вибір батареї для кожного віджета (T22, v0.4.16) | `core/WidgetModel.kt`, `core/WidgetPick.kt` (+ тести), `widget/WidgetArt.kt`, `widget/Widgets.kt`, `widget/WidgetSetupActivity.kt`, `data/WidgetChoice.kt`, `res/xml/widget_*.xml` |
| «Мої батареї» (пам'ять телефона) | `…/data/Batteries.kt` |
| Значок на вибір (T63, v0.4.17): чотири `activity-alias` у маніфесті, увімкнений один; картка в «Ще» | `core/AppIcon.kt` (+ тест), `ui/AppIconUi.kt`, `res/mipmap-anydpi-v26/ic_launcher_*.xml`, `res/drawable/ic_launcher_fg_*.xml`; контракт — `tests/test_app_icon.py` |
| Оновлення з Google Диска (T37, з Track Catcher): правила без Android, мережа (єдине місце з інтернетом — контракт `test_internet_only_for_drive_update`), свій провайдер APK замість androidx, картка внизу головної, автопублікація автора. **Сховати все — `UpdateFeature.VISIBLE = false`** (код лишається). Вбудоване посилання — `DriveUpdate.BUILT_IN_MANIFEST_ID` (0.4.6: вбудовано, файли в AppsAndroidSync › AolithiumLive › App) | `core/DriveUpdate.kt` (+ `DriveUpdateTest`), `core/UpdateFeature.kt`, `update/DriveHttp.kt`, `update/UpdateApkProvider.kt`, `ui/UpdateUi.kt`; інструкція — розділ `update` |
| Сторож у Telegram (T48, v0.4.11): правила — без Android, розмова з ботом, Telegram, вкладений JSON; мережа — лише тут (`api.telegram.org`); потік опитування й черга; пам'ять; картка в «Ще». Вимкнено за замовчуванням, токен — лише в телефоні, ніколи в журнал | `core/Guard.kt`, `core/GuardBot.kt`, `core/Telegram.kt`, `core/JsonTree.kt` (+ тести), `guard/TelegramHttp.kt`, `guard/GuardRunner.kt`, `data/GuardSettings.kt`, `ui/GuardUi.kt`; інструкція — розділ `guard`; план — `docs/T48_TELEGRAM_PLAN.md` |
| Дані для перевірки на відстані (T64, v0.4.18): бот `/diag` → файл; паролі плати JK затерто; кнопка в «Журналі зв'язку» | `core/Diag.kt` (+ тест), `ble/DiagFrames.kt`, `guard/DiagReport.kt`, `TelegramHttp.sendDocument`, `RecordService.requestRead` |
| «Подзвонити» при серйозній тривозі (T50, v0.4.18): що серйозне, номери, кнопки шторки (ACTION_DIAL), номери в боті | `core/Emergency.kt` (+ тест), `Alerts.Event.urgent`, `Guard.Msg.urgent`, `bg/Notifier.kt`, `data/AlertSettings.callPlan`, «Сповіщення» в `ui/BatteryScreen.kt` |
| Екрани | `…/ui/` (`Palette.kt` — кольори, `Ui.kt` — кнопки/картки; `Overlay`, `LineIcon`, `FlowLayout` — з Track Catcher) |
| Інструкція (T7): одне джерело обома мовами → сторінка в застосунку (значок ? на головній) | `docs/guide/guide.html` → `scripts/build_guide.py` → `assets/guide/index.html`; картинки — `scripts/guide_images.py`; показ — `ui/GuideScreen.kt`; тест — `tests/test_guide.py` |
| Меню телефона | `menu.sh`, `tools/termux/al-*.sh` |
| CI | `.github/workflows/android.yml` (тести, лінтер, збірка, реліз), `checks.yml`, `ui-screens.yml` |
| Мова інтерфейсу (T12): `tr()`, вибір, перемикачі | `core/Lang.kt`, `data/LangSettings.kt`, `AolithiumApp.kt`, `docs/LANG_EN.md` |
| Огляд схожих застосунків (для нас, не для користувача) | `docs/COMPETITORS.md` |
| Аудити, контракти | `scripts/audit_*.py`, `tests/` |
| Перевірки для Claude без Gradle | `tools/dev/run_core_tests.sh`, `tools/dev/compile_android.sh` |

## Технічне

- Gradle 8.13 **без wrapper** (CI — setup-gradle; телефон — `~/gradle/gradle-8.13`). AGP 8.13.2, Kotlin 2.3.21, SDK 36, **minSdk 26**.
- **Жодних бібліотек** у застосунку (навіть androidx) — лише Android. Тести — JUnit.
- Тести ядра локально (без Gradle): `bash tools/dev/run_core_tests.sh` (Kotlin-компілятор і JUnit з `/opt/gradle-*/lib`).
- Компіляція всього застосунку локально: `ANDROID_JAR=… bash tools/dev/compile_android.sh` (android.jar — з GitHub
  `Reginer/aosp-android-jar`, тека `android-36`; там є й приховані API, тож справжня перевірка — CI). Сайт
  raw.githubusercontent закритий для середовища Claude, а git — ні: `git clone --depth 1 --filter=blob:none --sparse
  https://github.com/Reginer/aosp-android-jar aosp && git -C aosp sparse-checkout set android-36` (20.10).
- **Мова (T12):** кожен текст для людини — `tr("укр", "англ")`; одиниці — `Fmt.V/A/W/AH…`; словник і пастки —
  `docs/LANG_EN.md`; сторож — `scripts/audit_lang.py` (CI).
- **Правопис (T38):** українські терміни й граматика — `docs/SPELLING.md` (правильно / не так, спірне — спитати faric).
- Python: `python3 -m unittest discover -s tests`, `python3 scripts/audit_ui.py`, `python3 scripts/audit_repo.py`.
- Логи Actions недоступні — усе важливе CI виводить анотаціями: `gh api repos/faric-ua/aolithium-live/check-runs/<id>/annotations`.
- Знімки екранів без телефона: пуш у `diag/ui-screens` → емулятор (демо-режим) → PNG у гілці `diag/ui-screens-out`.
- **Інструкція (T7, як у Track Catcher):** текст — лише в `docs/guide/guide.html`, кожен шматок парою `class="uk"` / `class="en"`
  (тест стереже пари й відсутність кирилиці в англійській). Після правки — `python3 scripts/build_guide.py`. Новий екран чи
  змінився вигляд — свіжі знімки: пуш у `diag/ui-screens*` → `python3 scripts/guide_images.py <ui-screens>` (набір `g-uk-*`,
  `g-en-*` у `scripts/ci/ui-screens.sh`). Нова можливість — абзац / рядок / плитка в наявній групі, стиль не міняти.
  Той самий файл — артефакт «Інструкція Aolithium Live» (`--fragment`, картинки поруч).
- Ключ підпису: **свій** (`~/.android/aolithium-live.jks`, секрети `AL_*`), створює телефон (меню 7). Не ключ Track Catcher.
- Пакет `ua.faric.aolithium` (debug — `.dev`); стоїть поруч з оригінальним `com.ao.lithium` 1.3, нічого в ньому не чіпає.
