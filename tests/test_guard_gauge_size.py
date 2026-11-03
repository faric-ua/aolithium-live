"""T86: картинка спідометра для бота не залежить від екрана телефона (скріни faric 26.10 17:31)."""
import pathlib
import unittest

APP = pathlib.Path(__file__).resolve().parents[1] / "android/app/src/main/java/ua/faric/aolithium"


class GuardGaugeSize(unittest.TestCase):
    def test_bot_gauge_has_fixed_size(self):
        art = (APP / "guard/GuardArt.kt").read_text(encoding="utf-8")
        self.assertIn("fixedSideDp = BOT_GAUGE_DP", art)
        # T87: картинка майже квадратна — Telegram у особистому чаті не обрізає верх і низ
        import re
        dp = int(re.search(r"const val BOT_GAUGE_DP = (\d+)", art).group(1))
        w = int(re.search(r"const val GAUGE_W_DP = (\d+)", art).group(1))
        # T95: спідометр більший (320), картинка ширша — висота ≈ 16 + 30 (назва) + 0,88 × бік + 8 + 3 рядки × 22 + 16
        h = 16 + 30 + 0.88 * dp + 8 + 3 * 22 + 16
        self.assertTrue(0.92 <= w / h <= 1.12, (w, h))
        self.assertTrue(dp >= 300, dp)  # faric 03.11 22:15: «спідометр збільшити»
        self.assertIn("textScale = BOT_TEXT_SCALE", art)

    def test_gauge_uses_fixed_size_before_screen(self):
        g = (APP / "ui/GaugeView.kt").read_text(encoding="utf-8")
        self.assertIn("val maxDp = fixedSideDp ?: minOf(MAX_DP, (resources.configuration.screenHeightDp", g)


if __name__ == "__main__":
    unittest.main()
