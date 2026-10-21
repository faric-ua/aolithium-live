# Чужі напрацювання в Aolithium Live

Код застосунку — свій. Знання протоколу й зразки байтів — з відкритих проєктів:

| Що | Звідки | Ліцензія | Як використано |
|---|---|---|---|
| Протокол плати JBD: команди, кадр, сума, поля 0x03/0x04/0x05, біти захистів, ознаки модулів JBD (початки адрес, назви) | **aiobmsble** — https://github.com/patman15/aiobmsble (`aiobmsble/bms/jbd_bms.py`, `docs/jbd_bms.md`) | Apache-2.0 | знання, переписано своїм кодом (`core/Jbd.kt`, `JbdParser.kt`, `BmsMatch.kt`) |
| Справжні кадри відповідей плат JBD для тестів | aiobmsble, `tests/bms/test_jbd_bms.py` | Apache-2.0 | байти як тестові дані (`JbdTest.kt`), з посиланням |
| Масштаб «великої батареї», лічильники захистів (0xAA), розбір дати, порядок підключення | **esphome-jbd-bms** — https://github.com/syssi/esphome-jbd-bms (`components/jbd_bms_ble/jbd_bms_ble.cpp`) | Apache-2.0 | знання, переписано своїм кодом |
| Ознаки JK BMS у пошуку (служба FFE0, коди виробника) | aiobmsble, `aiobmsble/bms/jikong_bms.py` | Apache-2.0 | знання (`BmsMatch.kt`) |
| Протокол JK BMS: команди 0x96/0x97, кадр 300 байт і сума, поля кадру 0x02 (розкладка JK02_32S, зсув −32 для JK02_24S), рядки кадру 0x03, датчики температури | aiobmsble, `aiobmsble/bms/jikong_bms.py` | Apache-2.0 | знання (`Jk.kt`, `docs/PROTOCOL_JK.md`) |
| Повна таблиця полів JK02 (опір дротів, балансир, ключі, підігрів, етап заряду) і біти помилок JK02 | **esphome-jk-bms** — https://github.com/syssi/esphome-jk-bms (`components/jk_bms_ble/jk_bms_ble.cpp`, `__init__.py`) | Apache-2.0 | знання (`Jk.kt`, `Protection.kt`) |
| Справжні кадри плат JK BMS (JK02_24S програма 10, JK02_32S програми 14, 15, 19 — «хто ти», комірки, налаштування) для тестів; поля кадру налаштувань 0x01 | esphome-jk-bms, `tests/components/jk_bms_ble/frames_jk02_*.h`, `components/jk_bms_ble/jk_bms_ble.cpp` (`decode_jk02_settings_`) | Apache-2.0 | байти як тестові дані (`JkRealFrames.kt`, `JkRealFramesTest.kt`), з посиланням; поля — знання (`Jk.kt`) |
| «Aolithium batteries» = плата JBD | **BMS_BLE-HA** — https://github.com/patman15/BMS_BLE-HA | Apache-2.0 | підтвердження, яка плата всередині |
| Інтерфейсні блоки (`Overlay`, `FlowLayout`, `LineIcon`, `SvgPath`, `FlowRows`), Termux-меню, CI | **Track Catcher** — https://github.com/faric-ua/faric-track-catcher | той самий автор (faric-ua) | скопійовано й пристосовано |

Код застосунку Aolithium 1.3 (`com.ao.lithium`) **не копіювався й не розбирався**.

Назва «Aolithium» — торгова марка її власника; цей застосунок — неофіційний, для власної батареї.
