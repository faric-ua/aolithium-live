"""T45: порада «як часто заряджати до 100 %» має бути однаковою скрізь.

20.10 faric помітив: на «спідометрі» (зона «Повна») — «раз на 1–2 тижні», а в довіднику
«Як берегти батарею» — «раз на 2–4 тижні». Тест збирає всі такі проміжки в текстах
застосунку й інструкції та вимагає, щоб він був один.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE = ROOT / "android/app/src/main/java/ua/faric/aolithium/core"
FILES = [CORE / "Zones.kt", CORE / "Glossary.kt", ROOT / "docs/guide/guide.html"]

UK = re.compile(r"раз на\W+(\d+–\d+) тижн", re.S)
EN = re.compile(r"every (\d+–\d+) weeks")


def intervals(pattern):
    found = {}
    for f in FILES:
        if not f.exists():
            continue
        text = f.read_text(encoding="utf-8")
        for m in pattern.finditer(text):
            found.setdefault(m.group(1), []).append(f.name)
    return found


class CareAdviceTest(unittest.TestCase):
    def test_full_charge_interval_is_the_same_everywhere(self):
        uk = intervals(UK)
        en = intervals(EN)
        self.assertTrue(uk, "порада «раз на N–M тижні» зникла з текстів")
        self.assertEqual(len(uk), 1, f"різні проміжки українською: {uk}")
        self.assertEqual(len(en), 1, f"різні проміжки англійською: {en}")
        self.assertEqual(set(uk), set(en), f"українська {set(uk)} ≠ англійська {set(en)}")


if __name__ == "__main__":
    unittest.main()
