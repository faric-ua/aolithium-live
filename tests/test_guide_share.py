"""Інструкція «для поширення» (faric 04.11 20:39): один HTML-файл, картинки всередині, без особистого.

`python3 scripts/build_guide.py --share OUT`. Тест ловить, коли інструкцію змінили так, що заміна не знайшлась
(скрипт падає) або особисте проскочило (телефони faric, мама, Termux-меню, Claude).
"""
import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("build_guide", ROOT / "scripts/build_guide.py")
bg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bg)


class GuideShare(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.html = bg.share_page()
        cls.text = re.sub(r'data:image/[^"]+', "", cls.html)

    def test_images_are_inside(self):
        self.assertNotIn('src="img/', self.html)
        self.assertGreaterEqual(self.html.count('src="data:image/webp;base64,'), 20)

    def test_nothing_personal(self):
        for w in bg.SHARE_FORBIDDEN:
            self.assertIsNone(re.search(w, self.text), w)
        self.assertNotIn("<!--", self.text)

    def test_install_is_generic(self):
        self.assertIn("Перший раз — файл APK", self.text)
        self.assertIn("First time — the APK file", self.text)

    def test_both_languages_and_script_stay(self):
        self.assertIn('class="uk"', self.text)
        self.assertIn('class="en"', self.text)
        self.assertIn("<script>", self.text)


if __name__ == "__main__":
    unittest.main()
