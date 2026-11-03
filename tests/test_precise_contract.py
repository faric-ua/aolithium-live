"""T91 «Точний запис»: ніколи не лишається ввімкненим «назавжди».

faric 03.11: увімкнув — пише; закрив застосунок — вимкнено; екран погас на 5 хв — вимкнено.
Тут стережемо те, що легко зламати непомітно: стан не пишеться на диск, служба є в маніфесті, закриття її зупиняє.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/app/src/main"
SRC = MAIN / "java/ua/faric/aolithium"
MANIFEST = (MAIN / "AndroidManifest.xml").read_text(encoding="utf-8")
SERVICE = (SRC / "bg/PreciseService.kt").read_text(encoding="utf-8")
ACTIVITY = (SRC / "ui/MainActivity.kt").read_text(encoding="utf-8")
HOLDER = (SRC / "ble/LinkHolder.kt").read_text(encoding="utf-8")


class PreciseContract(unittest.TestCase):
    def test_service_declared_as_connected_device(self):
        m = re.search(r'<service\s+android:name="\.bg\.PreciseService"(.*?)/>', MANIFEST, re.S)
        self.assertIsNotNone(m, "PreciseService нема в маніфесті")
        self.assertIn('android:exported="false"', m.group(1))
        self.assertIn('android:foregroundServiceType="connectedDevice"', m.group(1))

    def test_state_lives_only_in_memory(self):
        # після перезапуску застосунку — завжди вимкнено: жодних налаштувань чи файлів
        for bad in ("SharedPreferences", "getSharedPreferences", "openFileOutput", "START_STICKY\n", "return START_STICKY"):
            self.assertNotIn(bad, SERVICE, bad)

    def test_closing_the_app_stops_it(self):
        self.assertIn("override fun onTaskRemoved", SERVICE)
        self.assertRegex(SERVICE, r"onTaskRemoved[\s\S]{0,200}PreciseMode\.stop\(Precise\.Stop\.CLOSED\)")
        self.assertRegex(ACTIVITY, r"override fun onDestroy\(\)[\s\S]{0,400}isFinishing[\s\S]{0,80}PreciseMode\.stop\(Precise\.Stop\.CLOSED\)")

    def test_locked_screen_is_watched(self):
        self.assertIn("ACTION_SCREEN_OFF", SERVICE)
        self.assertIn("ACTION_USER_PRESENT", SERVICE)
        self.assertIn("PreciseMode.check(", SERVICE)

    def test_link_kept_only_while_precise(self):
        self.assertIn("PreciseMode.isOn(l.address)", HOLDER)
        self.assertIn("fun onPreciseStopped", HOLDER)


if __name__ == "__main__":
    unittest.main()
