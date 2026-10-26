"""T86: картинка спідометра для бота не залежить від екрана телефона (скріни faric 26.10 17:31)."""
import pathlib
import unittest

APP = pathlib.Path(__file__).resolve().parents[1] / "android/app/src/main/java/ua/faric/aolithium"


class GuardGaugeSize(unittest.TestCase):
    def test_bot_gauge_has_fixed_size(self):
        art = (APP / "guard/GuardArt.kt").read_text(encoding="utf-8")
        self.assertIn("fixedSideDp = GaugeView.MAX_DP", art)

    def test_gauge_uses_fixed_size_before_screen(self):
        g = (APP / "ui/GaugeView.kt").read_text(encoding="utf-8")
        self.assertIn("val maxDp = fixedSideDp ?: minOf(MAX_DP, (resources.configuration.screenHeightDp", g)


if __name__ == "__main__":
    unittest.main()
