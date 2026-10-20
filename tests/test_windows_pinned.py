"""T47 (faric 20.10 22:53): у кожному вікні заголовок завжди згори, кнопки — завжди знизу, прокручується лише середина.

Вікна показує `host.overlay.show(...)`. Тест вимагає, щоб кожна функція, яка показує вікно, будувала його через
`overlay.dialog(...)` (шапку й кнопки той позначає сам) або сама позначала їх `Overlay.header(...)` і `Overlay.footer(...)`.
Плюс: журнал зв'язку у вікні — свіжіші записи зверху.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
UI = ROOT / "android/app/src/main/java/ua/faric/aolithium/ui"


def functions(text):
    """Грубо розрізати файл Kotlin на функції класу (відступ 4 пробіли; вкладені `fun step` лишаються всередині)."""
    parts = re.split(r"\n(?=    (?:private |internal |override )*fun )", text)
    return parts


class WindowsPinnedTest(unittest.TestCase):
    def test_every_window_pins_title_and_buttons(self):
        bad = []
        for f in sorted(UI.glob("*.kt")):
            if f.name == "Overlay.kt":
                continue
            for part in functions(f.read_text(encoding="utf-8")):
                if "overlay.show(" not in part:
                    continue
                ok = "overlay.dialog(" in part or ("Overlay.header(" in part and "Overlay.footer(" in part)
                if not ok:
                    name = re.search(r"fun (\w+)", part)
                    bad.append(f"{f.name}: {name.group(1) if name else '?'}")
        self.assertEqual(bad, [], "вікна без закріпленої шапки/кнопок: " + ", ".join(bad))

    def test_overlay_pins_header_and_footer(self):
        s = (UI / "Overlay.kt").read_text(encoding="utf-8")
        self.assertIn("box.addView(header(ui.title(", s)
        self.assertIn("box.addView(footer(ui.grid(", s)
        self.assertIn("LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)", s,
                      "середина вікна має бути ScrollView з вагою 1 — інакше кнопки виїдуть за екран")

    def test_log_newest_first(self):
        s = (UI / "BatteryScreen.kt").read_text(encoding="utf-8")
        self.assertIn("LinkLog.newestFirst()", s)
        self.assertNotIn("LinkLog.text().ifBlank", s)


if __name__ == "__main__":
    unittest.main()
