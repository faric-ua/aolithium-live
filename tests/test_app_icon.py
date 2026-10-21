"""T63: значок на вибір — псевдоніми в маніфесті збігаються з core/AppIcon.kt.

Якщо тут розійдеться — застосунок зникне зі списку застосунків або перемикач значка вмикатиме неіснуючий псевдонім.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android/app/src/main"
MANIFEST = (MAIN / "AndroidManifest.xml").read_text(encoding="utf-8")
APP_ICON = (MAIN / "java/ua/faric/aolithium/core/AppIcon.kt").read_text(encoding="utf-8")


def aliases():
    out = {}
    for m in re.finditer(r"<activity-alias(.*?)</activity-alias>", MANIFEST, re.S):
        body = m.group(1)
        name = re.search(r'android:name="([^"]+)"', body).group(1)
        out[name] = {
            "enabled": re.search(r'android:enabled="(true|false)"', body).group(1) == "true",
            "icon": re.search(r'android:icon="@mipmap/([^"]+)"', body).group(1),
            "target": re.search(r'android:targetActivity="([^"]+)"', body).group(1),
            "launcher": "android.intent.category.LAUNCHER" in body,
        }
    return out


class AppIconContract(unittest.TestCase):
    def test_aliases_match_the_code(self):
        in_code = set(re.findall(r'"(ua\.faric\.aolithium\.Icon\w+)"', APP_ICON))
        self.assertEqual(4, len(in_code))
        self.assertEqual(in_code, set(aliases()))

    def test_exactly_one_icon_on_out_of_the_box(self):
        on = [n for n, a in aliases().items() if a["enabled"]]
        self.assertEqual(["ua.faric.aolithium.IconClassic"], on)

    def test_every_alias_is_a_launcher_entry_to_the_main_window(self):
        for name, a in aliases().items():
            self.assertTrue(a["launcher"], name)
            self.assertEqual(".ui.MainActivity", a["target"], name)
            self.assertTrue((MAIN / "res/mipmap-anydpi-v26" / f"{a['icon']}.xml").exists(), name)

    def test_main_window_is_not_a_launcher_itself_but_stays_reachable(self):
        main = re.search(r'<activity\s+android:name="\.ui\.MainActivity"(.*?)(/>|</activity>)', MANIFEST, re.S).group(1)
        self.assertIn('android:exported="true"', main)  # віджети, сповіщення, знімки CI (am start -n)
        # Інакше в списку застосунків було б два значки.
        whole = re.search(r'<activity\s+android:name="\.ui\.MainActivity".*?(/>|</activity>)', MANIFEST, re.S).group(0)
        self.assertNotIn("android.intent.category.LAUNCHER", whole)


if __name__ == "__main__":
    unittest.main()
