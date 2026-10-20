"""Збірка, реліз і безпека Aolithium Live: правила сім'ї застосунків faric + свої (старі Android, лише читання)."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/app/src/main/java/ua/faric/aolithium"
GRADLE = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
ROOT_GRADLE = (ROOT / "android/build.gradle.kts").read_text(encoding="utf-8")
CI = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
MANIFEST = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")


def kotlin_sources():
    return sorted(APP.rglob("*.kt"))


class BuildContract(unittest.TestCase):
    def test_versions_match_family(self):
        self.assertIn('"com.android.application") version "8.13.2"', ROOT_GRADLE)
        self.assertIn('"org.jetbrains.kotlin.android") version "2.3.21"', ROOT_GRADLE)
        self.assertIn("compileSdk = 36", GRADLE)
        self.assertIn("targetSdk = 36", GRADLE)

    def test_min_sdk_is_android_8(self):
        # Huawei Mate 10 Lite (Android 8) — один із трьох телефонів faric для батарей.
        self.assertIn("minSdk = 26", GRADLE)

    def test_own_package_and_signer(self):
        self.assertIn('applicationId = "ua.faric.aolithium"', GRADLE)
        self.assertNotRegex(GRADLE + CI, r"RENAULT_|VISUALIZER_|YTM_|dorama-alpha|TC_")

    def test_no_libraries_in_app(self):
        # Лише Android — менше для збірки на телефоні; тести — JUnit.
        deps = re.findall(r'^\s*(\w+)\("([^"]+)"\)', GRADLE.split("dependencies {", 1)[1], re.M)
        self.assertEqual([("testImplementation", "junit:junit:4.13.2")], deps)

    def test_release_is_not_debuggable_and_not_minified(self):
        block = re.search(r"release \{(.*?)\n        \}", GRADLE, re.S).group(1)
        self.assertIn("isMinifyEnabled = false", block)
        self.assertNotIn("isDebuggable = true", block)

    def test_version_line_has_commit(self):
        self.assertIn('buildConfigField("String", "GIT_SHA"', GRADLE)
        main = (APP / "ui/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("BuildConfig.GIT_SHA", main)

    def test_publish_only_from_main_and_only_signed(self):
        self.assertIn("github.ref == 'refs/heads/main' && github.event_name != 'pull_request'", CI)
        self.assertIn("if: env.PUBLISH == 'true' && env.SIGNED == 'true'", CI)
        self.assertIn("--prerelease", CI)
        self.assertIn("уже опубліковано", CI)  # тег не перезаписується
        self.assertIn('"$CERT" != "$AL_EXPECTED_CERT"', CI)
        self.assertIn("retention-days: 14", CI)

    def test_errors_are_annotations(self):
        # Claude не бачить логів Actions — помилки мають виходити анотаціями.
        self.assertIn("::error title=Unit tests::", CI)
        self.assertIn("::error title=Android build::", CI)
        self.assertIn("::error title=Lint (NewApi)::", CI)
        self.assertIn("::notice title=APK_SHA256::", CI)

    def test_lint_guards_old_android(self):
        self.assertIn(":app:lintDebug", CI)
        self.assertIn('checkOnly += setOf("NewApi")', GRADLE)
        self.assertIn("abortOnError = true", GRADLE)


class BluetoothContract(unittest.TestCase):
    def test_permissions_for_old_and_new_android(self):
        for perm, max_sdk in (("BLUETOOTH", "30"), ("BLUETOOTH_ADMIN", "30"), ("ACCESS_FINE_LOCATION", "30")):
            self.assertRegex(MANIFEST, rf'android.permission.{perm}" android:maxSdkVersion="{max_sdk}"')
        self.assertRegex(MANIFEST, r'BLUETOOTH_SCAN"\s+android:usesPermissionFlags="neverForLocation"')
        self.assertIn("android.permission.BLUETOOTH_CONNECT", MANIFEST)
        self.assertIn('android:allowBackup="false"', MANIFEST)

    def test_internet_only_for_drive_update_and_guard(self):
        # Дані батареї нікуди не йдуть, крім Telegram-бота власника, і лише коли він сам увімкнув сторожа (T48).
        # До T37 тут було «INTERNET у маніфесті нема взагалі». З T37 (оновлення з Google Диска) — мережею користується
        # update/DriveHttp.kt, лише з адресами Диска. З T48 (faric 21.10 01:53, «Сторож (Telegram)») — ще
        # guard/TelegramHttp.kt, лише api.telegram.org, і кличе його лише guard/GuardRunner.kt.
        self.assertIn("android.permission.INTERNET", MANIFEST)
        self.assertIn("android.permission.REQUEST_INSTALL_PACKAGES", MANIFEST)
        self.assertIn('android:name=".update.UpdateApkProvider"', MANIFEST)
        self.assertRegex(MANIFEST, r'UpdateApkProvider"\s+android:authorities="\$\{applicationId\}\.updates"\s+android:exported="false"')
        self.assertIn("const val VISIBLE", (APP / "core/UpdateFeature.kt").read_text(encoding="utf-8"))
        net = re.compile(r"openConnection|java\.net\.|\bSocket\b|HttpURLConnection|WebSocket|okhttp")
        users = sorted(p.relative_to(APP).as_posix() for p in kotlin_sources() if net.search(p.read_text(encoding="utf-8")))
        self.assertEqual(["guard/TelegramHttp.kt", "update/DriveHttp.kt"], users)
        drive = (APP / "core/DriveUpdate.kt").read_text(encoding="utf-8") + (APP / "update/DriveHttp.kt").read_text(encoding="utf-8")
        self.assertEqual({"drive.google.com", "drive.usercontent.google.com"}, set(re.findall(r"https://([a-z0-9.-]+)/", drive)))
        tg = (APP / "core/Telegram.kt").read_text(encoding="utf-8")
        self.assertIn('const val HOST = "api.telegram.org"', tg)
        self.assertEqual(set(), set(re.findall(r"https://([a-z0-9.-]+)/", tg)) - {"$HOST"} - set())
        callers = sorted(p.relative_to(APP).as_posix() for p in kotlin_sources()
                         if "TelegramHttp" in p.read_text(encoding="utf-8") and not p.name == "TelegramHttp.kt")
        self.assertEqual(["guard/GuardRunner.kt"], callers)
        # Шари, що говорять з батареєю й тримають її дані, мережі не бачать узагалі.
        for layer in ("ble", "bg", "data", "widget"):
            for p in (APP / layer).rglob("*.kt"):
                text = p.read_text(encoding="utf-8")
                self.assertNotIn("DriveHttp", text, p.name)
                self.assertNotIn("TelegramHttp", text, p.name)

    def test_guard_is_off_by_default(self):
        # T48: сторож нічого не шле, поки власник сам не ввімкнув і не вставив токен свого бота.
        g = (APP / "data/GuardSettings.kt").read_text(encoding="utf-8")
        self.assertIn('prefs.getBoolean("on", false)', g)
        runner = (APP / "guard/GuardRunner.kt").read_text(encoding="utf-8")
        self.assertIn("if (!st.enabled || st.token.isEmpty())", runner)
        # Токен (змінна) — ніколи в журнал зв'язку: ні «$token», ні «${st.token}», ні «+ token».
        leak = re.compile(r"\$\{?[A-Za-z_.]*[tT]oken\b|\+\s*[A-Za-z_.]*[tT]oken\b")
        for p in kotlin_sources():
            for line in p.read_text(encoding="utf-8").splitlines():
                if "LinkLog.add" in line:
                    self.assertIsNone(leak.search(line), f"{p.name}: {line.strip()}")

    def test_app_only_reads_the_board(self):
        """Застосунок лише читає: у плату йдуть тільки команди читання, зібрані Jbd.readCommand."""
        jbd = (APP / "core/Jbd.kt").read_text(encoding="utf-8")
        self.assertIn("val READ_COMMANDS = setOf(CMD_BASIC, CMD_CELLS, CMD_HARDWARE, CMD_ERROR_COUNTS)", jbd)
        self.assertIn("require(cmd in READ_COMMANDS)", jbd)
        writers = sorted(p.name for p in kotlin_sources() if "writeCharacteristic(" in p.read_text(encoding="utf-8"))
        self.assertEqual(["JbdLink.kt", "JkLink.kt"], writers)
        # v0.4: JK BMS — лише «хто ти» (0x97) і «комірки» (0x96), зібрані Jk.readCommand; решта команд JK — запис.
        jk = (APP / "core/Jk.kt").read_text(encoding="utf-8")
        self.assertIn("val READ_COMMANDS = setOf(CMD_DEVICE_INFO, CMD_CELL_INFO)", jk)
        self.assertIn("require(cmd in READ_COMMANDS)", jk)
        jk_link = (APP / "ble/JkLink.kt").read_text(encoding="utf-8")
        self.assertIn("val bytes = Jk.readCommand(cmd)", jk_link)
        self.assertNotIn("responseFrame", jk_link)
        self.assertNotIn("JkDemo", jk_link)
        link = (APP / "ble/JbdLink.kt").read_text(encoding="utf-8")
        self.assertIn("val bytes = Jbd.readCommand(cmd)", link)
        self.assertNotIn("responseFrame", link)
        for cmd in ("0xE1", "0xE0", "0xE2", "0x0A", "0xFA"):
            self.assertNotIn(cmd, link, f"у JbdLink не має бути команд запису ({cmd})")

    def test_both_gatt_callbacks(self):
        # Android 13+ і старші кличуть різні onCharacteristicChanged.
        for name in ("ble/JbdLink.kt", "ble/JkLink.kt"):
            link = (APP / name).read_text(encoding="utf-8")
            self.assertIn("c: BluetoothGattCharacteristic, value: ByteArray)", link, name)
            self.assertIn("if (Build.VERSION.SDK_INT >= 33) return", link, name)
            self.assertIn("BluetoothDevice.TRANSPORT_LE", link, name)
        link = (APP / "ble/JbdLink.kt").read_text(encoding="utf-8")
        self.assertIn("c: BluetoothGattCharacteristic, value: ByteArray)", link)
        self.assertIn("if (Build.VERSION.SDK_INT >= 33) return", link)
        self.assertIn("BluetoothDevice.TRANSPORT_LE", link)

    def test_disconnects_when_hidden(self):
        holder = (APP / "ble/LinkHolder.kt").read_text(encoding="utf-8")
        self.assertIn("GRACE_MS = 5_000L", holder)
        screen = (APP / "ui/BatteryScreen.kt").read_text(encoding="utf-8")
        stop = screen[screen.index("override fun onStop()"):screen.index("override fun save(")]
        self.assertIn("LinkHolder.release(this)", stop)

    def test_background_recording_is_polite(self):
        """v0.2.5: фон — служба «пристрої поблизу» з тихим сповіщенням; поступається екрану; лише читає (через JbdLink)."""
        self.assertIn('android:foregroundServiceType="connectedDevice"', MANIFEST)
        self.assertIn("FOREGROUND_SERVICE_CONNECTED_DEVICE", MANIFEST)
        self.assertIn("MY_PACKAGE_REPLACED", MANIFEST)  # після оновлення «поверх» запис вмикається сам
        svc = (APP / "bg/RecordService.kt").read_text(encoding="utf-8")
        self.assertIn("LinkHolder.create(this, addr, quick = true)", svc)  # v0.4: JBD чи JK — один вибір
        holder = (APP / "ble/LinkHolder.kt").read_text(encoding="utf-8")
        self.assertIn("JkLink(ctx, address, quick) else JbdLink(ctx, address, quick)", holder)
        self.assertIn("LinkHolder.holds(addr)", svc)
        self.assertIn("l.stop()", svc)  # після знімка батарею відпускаємо
        holder = (APP / "ble/LinkHolder.kt").read_text(encoding="utf-8")
        self.assertIn("RecordService.yieldTo(address)", holder)

    def test_screen_does_not_rotate(self):
        # faric 19.10 04:25: «потрібно заборонити переворот екрану».
        self.assertIn('android:screenOrientation="portrait"', MANIFEST)

    def test_back_on_android_16(self):
        main = (APP / "ui/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("registerOnBackInvokedCallback", main)
        self.assertIn("override fun onBackPressed()", main)

    def test_new_api_calls_are_guarded(self):
        """Урок Dorama T94: виклик API новішого за мін. версію без перевірки закриває застосунок на старому телефоні."""
        guarded = {
            "setDecorFitsSystemWindows": 30,
            "onBackInvokedDispatcher": 33,
            "isLocationEnabled": 28,
            "writeDescriptor(cccd, value)": 33,
            "writeCharacteristic(c, bytes, type)": 33,
            # T37 (оновлення з Диска): ключ підпису й номер збірки APK — новим способом лише з Android 9.
            "GET_SIGNING_CERTIFICATES": 28,
            "longVersionCode": 28,
            "signingInfo": 28,
        }
        for p in kotlin_sources():
            text = p.read_text(encoding="utf-8")
            for call, api in guarded.items():
                for m in re.finditer(re.escape(call), text):
                    before = text[max(0, m.start() - 700):m.start()]
                    self.assertRegex(before, rf"SDK_INT (>= {api}|< {api})", f"{p.name}: {call} без перевірки Android {api}")


class ProtocolDocs(unittest.TestCase):
    def test_sources_credited(self):
        third = (ROOT / "THIRD_PARTY.md").read_text(encoding="utf-8")
        self.assertIn("aiobmsble", third)
        self.assertIn("Apache-2.0", third)
        self.assertIn("esphome-jbd-bms", third)
        proto = (ROOT / "docs/PROTOCOL_JBD.md").read_text(encoding="utf-8")
        self.assertIn("DD A5 03 00 FF FD 77", proto)


if __name__ == "__main__":
    unittest.main()
