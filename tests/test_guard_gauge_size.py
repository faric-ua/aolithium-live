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
        f = lambda n: float(re.search(r"const val %s = ([\d.]+)f" % n, art).group(1))
        # T96: висота = 16 + назва + 0,88 × бік + проміжок + 3 рядки + низ (як у gauge())
        h = 16 + f("HEAD_DP") + 0.88 * dp + f("GAP_DP") + 3 * f("LINE_DP") + f("BOTTOM_DP")
        # T87: 360×400 (0,9) Telegram обрізав у особистому чаті; тримаємо ≥ 0,93
        self.assertTrue(0.93 <= w / h <= 1.12, (w, h))
        # T96 (04.11) підкова була ≥ 360; T102 (faric 05.11 17:23 «почнемо з радіуса»): як у застосунку — MAX_DP GaugeView
        g0 = (APP / "ui/GaugeView.kt").read_text(encoding="utf-8")
        self.assertEqual(dp, int(re.search(r"const val MAX_DP = (\d+)", g0).group(1)), dp)
        self.assertLessEqual(dp, w - 32)  # влазить у картинку з полями по 16
        self.assertIn("slots = false", art)  # T101: розклад як у застосунку (смуги T97 — ні)
        # T98: великий шрифт на телефоні-сторожі (у мами) не роздуває числа — розміри картинки бота в dp
        self.assertIn("fixedText = true", art)
        g = (APP / "ui/GaugeView.kt").read_text(encoding="utf-8")
        self.assertNotIn("applyDimension(TypedValue.COMPLEX_UNIT_SP", g)  # лише через textUnit

    def test_ticks_size_does_not_depend_on_field_order(self):
        # T96: у 0.4.36 textScale стояв нижче фарб — поділки отримали розмір 0 і зникли
        g = (APP / "ui/GaugeView.kt").read_text(encoding="utf-8")
        self.assertLess(g.index("var textScale"), g.index("private val tick = Paint"))
        self.assertIn("tick.textSize = TypedValue.applyDimension(textUnit, tickSp", g)
        self.assertIn("listOf(0, 10, 20, 50, 90, 100)", g)

    def test_gauge_uses_fixed_size_before_screen(self):
        g = (APP / "ui/GaugeView.kt").read_text(encoding="utf-8")
        self.assertIn("val maxDp = fixedSideDp ?: minOf(MAX_DP, (resources.configuration.screenHeightDp", g)


if __name__ == "__main__":
    unittest.main()
