package ua.faric.aolithium.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * T37: оновлення з Google Диска — перенесено з Track Catcher (`DriveUpdateTest` там): посилання, файл версії,
 * перевірки APK, перевірка публікації. Своє: назви файлів Aolithium Live, порожнє вбудоване посилання, автопублікація,
 * перемикач видимості, обидві мови.
 */
class DriveUpdateTest {
    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345"
    private val sha = "a".repeat(64)
    private val pkg = "ua.faric.aolithium"

    @Before fun uk() { Lang.current = Lang.Code.UK }
    @After fun reset() { Lang.current = Lang.Code.UK }

    @Test
    fun ownFileNamesAndFormat() {
        assertEquals("Aolithium Live.apk", DriveUpdate.APK_FILE_NAME)
        assertEquals("aolithium-live-onovlennia.json", DriveUpdate.VERSION_FILE_NAME)
        assertEquals("aolithium-live-update/1", DriveUpdate.FORMAT)
    }

    /** 0.4.6: файли створено заздалегідь — вбудовані обидва посилання; без вбудованого перевірка бере лише збережене. */
    @Test
    fun builtInLinksPointToCreatedFiles() {
        // 0.4.6: посилання вбудовані (файли створено в AppsAndroidSync › AolithiumLive › App).
        assertEquals("1s6SVbZuXEszmYC71S9avvTW9ilhsaOGB", DriveUpdate.BUILT_IN_MANIFEST_ID)
        assertEquals("1cI2Db86Rb3SpapcLVnuapj-Xxgy-Y4LC", DriveUpdate.BUILT_IN_APK_ID)
        assertTrue(DriveUpdate.filesBuiltIn)
        assertEquals(emptyList<String>(), DriveUpdate.manifestCandidates(null, null, ""))
        assertEquals(listOf(id), DriveUpdate.manifestCandidates(null, id, ""))
    }

    @Test
    fun featureSwitchIsOn() {
        // T37: «не видаляти, а сховати» — перемикач у коді. Вимкнули — цей тест нагадає оновити інструкцію.
        assertTrue(UpdateFeature.VISIBLE)
    }

    @Test
    fun linksFromDriveApp() {
        assertEquals(id, DriveUpdate.fileIdFromLink("https://drive.google.com/file/d/$id/view?usp=sharing"))
        assertEquals(id, DriveUpdate.fileIdFromLink("  https://drive.google.com/open?id=$id  "))
        assertEquals(id, DriveUpdate.fileIdFromLink("https://drive.google.com/uc?id=$id&export=download"))
        assertEquals(id, DriveUpdate.fileIdFromLink(id))
    }

    @Test
    fun notFileLinks() {
        assertNull(DriveUpdate.fileIdFromLink("https://drive.google.com/drive/folders/$id"))
        assertNull(DriveUpdate.fileIdFromLink("https://example.com/file/d/$id/view"))
        assertNull(DriveUpdate.fileIdFromLink("привіт"))
        assertNull(DriveUpdate.fileIdFromLink(""))
        assertNull(DriveUpdate.fileIdFromLink(null))
    }

    @Test
    fun manifestRoundTrip() {
        val m = DriveUpdate.Manifest(pkg, 21, "0.4.5", sha, 7_400_000, id, 1_760_000_000_000)
        val json = m.toJson()
        assertTrue(json.contains("\"format\": \"aolithium-live-update/1\""))
        assertTrue(json.contains("\"apkLink\": \"https://drive.google.com/file/d/$id/view\""))
        assertEquals(m, DriveUpdate.Manifest.parse(json))
    }

    @Test
    fun manifestWithEscapesAndExtraFields() {
        val text = "{\"format\":\"aolithium-live-update/1\",\"package\":\"$pkg\",\"versionCode\":21," +
            "\"versionName\":\"0.4.5 \\\"тест\\\" \\u0041\",\"apkSha256\":\"${sha.uppercase()}\",\"apkSize\":10," +
            "\"apkId\":\"$id\",\"note\":null,\"beta\":false}"
        val m = DriveUpdate.Manifest.parse(text)
        assertEquals("0.4.5 \"тест\" A", m.versionName)
        assertEquals(sha, m.apkSha256)
        assertEquals(0L, m.publishedAt)
    }

    @Test
    fun placeholderSaysNotPublishedYet() {
        assertMessage(DriveUpdate.PLACEHOLDER, "ще не опублікував")
    }

    @Test
    fun badManifests() {
        assertMessage("<!doctype html><html>", "не файл версії")
        assertMessage("{\"format\":\"track-catcher-update/1\"}", "інший формат") // файл іншого застосунку faric
        assertMessage("{\"format\":\"aolithium-live-update/1\",\"package\":\"$pkg\"}", "неповний")
        assertMessage("{\"format\":\"aolithium-live-update/1\",\"a\":{\"b\":1}}", "не файл версії")
        assertMessage("{\"format\":\"aolithium-live-update/1\"} хвіст", "не файл версії")
    }

    /** Track Catcher T46: не той файл — посилання забувається; «ще не опубліковано» / «неповний» — ні (це справа автора). */
    @Test
    fun wrongFileIsSeparate() {
        assertTrue(isWrongFile("<!doctype html><html>"))
        assertTrue(isWrongFile("{\"format\":\"track-catcher-update/1\"}"))
        assertTrue(isWrongFile("PK\u0003\u0004бінарне"))
        assertFalse(isWrongFile(DriveUpdate.PLACEHOLDER))
        assertFalse(isWrongFile("{\"format\":\"aolithium-live-update/1\",\"package\":\"$pkg\"}"))
    }

    /** Track Catcher T46 (14.10 22:19): faric вставив посилання на APK → «Файл на Диску завеликий (6,1 МБ)» без пояснення. */
    @Test
    fun apkLinkMessageExplains() {
        val text = DriveUpdate.linkIsApkMessage(6_420_826)
        assertTrue(text, text.contains("APK, 6,1 МБ"))
        assertTrue(text.contains(DriveUpdate.VERSION_FILE_NAME))
        assertTrue(text.contains("Поділитися посиланням на оновлення"))
        assertFalse(DriveUpdate.linkIsApkMessage(-1).contains("МБ)"))
    }

    @Test
    fun englishMessages() {
        Lang.current = Lang.Code.EN
        val text = DriveUpdate.linkIsApkMessage(6_420_826)
        assertTrue(text, text.contains("APK, 6.1 MB"))
        assertTrue(text.contains("Share the update link"))
        assertEquals("7.1 MB", DriveUpdate.formatMb(7_444_889))
        try {
            DriveUpdate.Manifest.parse(DriveUpdate.PLACEHOLDER)
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("not published"))
            assertFalse(e.message!!, Regex("[А-Яа-яІіЇїЄє]").containsMatchIn(e.message!!))
        }
    }

    @Test
    fun candidatesOrder() {
        val built = "B".repeat(33)
        assertEquals(listOf(id), DriveUpdate.manifestCandidates(id, "S".repeat(33), built))
        assertEquals(listOf("S".repeat(33), built), DriveUpdate.manifestCandidates(null, "S".repeat(33), built))
        assertEquals(listOf(built), DriveUpdate.manifestCandidates(null, built, built))
        assertEquals(listOf(built), DriveUpdate.manifestCandidates(" ", "", built))
        assertEquals(emptyList<String>(), DriveUpdate.manifestCandidates(null, null, ""))
    }

    @Test
    fun newestWinsOverSavedOldFile() {
        // Track Catcher T56: збережене посилання на старий файл не має перекривати вбудований новий.
        fun m(code: Long) = DriveUpdate.Manifest(pkg, code, "0.4.$code", "a".repeat(64), 1, "X".repeat(33), 0)
        assertEquals("built", DriveUpdate.newest(listOf("saved" to m(16), "built" to m(22)))!!.first)
        assertEquals("saved", DriveUpdate.newest(listOf("saved" to m(22), "built" to m(22)))!!.first)
        assertEquals("saved", DriveUpdate.newest(listOf("saved" to m(23), "built" to m(22)))!!.first)
        assertEquals(null, DriveUpdate.newest(emptyList()))
    }

    private fun isWrongFile(text: String): Boolean = try {
        DriveUpdate.Manifest.parse(text)
        fail("мало не прочитатись: $text")
        false
    } catch (e: DriveUpdate.WrongFileException) {
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    @Test
    fun verdicts() {
        val m = DriveUpdate.Manifest(pkg, 21, "0.4.5", sha, 10, id, 0)
        assertEquals(DriveUpdate.Verdict.NEWER, DriveUpdate.compare(m, pkg, 20))
        assertEquals(DriveUpdate.Verdict.SAME, DriveUpdate.compare(m, pkg, 21))
        assertEquals(DriveUpdate.Verdict.OLDER, DriveUpdate.compare(m, pkg, 22))
        assertEquals(DriveUpdate.Verdict.OTHER_PACKAGE, DriveUpdate.compare(m, "$pkg.dev", 1))
    }

    @Test
    fun downloadedChecks() {
        val m = DriveUpdate.Manifest(pkg, 21, "0.4.5", sha, 10, id, 0)
        val cert = "c".repeat(64)
        assertNull(DriveUpdate.checkDownloaded(m, sha, 10, pkg, 21, cert, cert))
        assertNull(DriveUpdate.checkDownloaded(m, sha.uppercase(), 10, pkg, 21, null, cert)) // ключ не прочитався — вирішить Android
        assertNotNull(DriveUpdate.checkDownloaded(m, sha, 11, pkg, 21, cert, cert))
        assertNotNull(DriveUpdate.checkDownloaded(m, "b".repeat(64), 10, pkg, 21, cert, cert))
        assertNotNull(DriveUpdate.checkDownloaded(m, sha, 10, null, 21, cert, cert))
        assertNotNull(DriveUpdate.checkDownloaded(m, sha, 10, "com.ao.lithium", 21, cert, cert)) // оригінальний Aolithium 1.3
        assertNotNull(DriveUpdate.checkDownloaded(m, sha, 10, pkg, 20, cert, cert))
        assertTrue(DriveUpdate.checkDownloaded(m, sha, 10, pkg, 21, "d".repeat(64), cert)!!.contains("іншим ключем"))
    }

    @Test
    fun responseSniffing() {
        val html = "  \n<!DOCTYPE html><html>".toByteArray()
        assertTrue(DriveUpdate.looksLikeHtml(null, html, html.size))
        assertTrue(DriveUpdate.looksLikeHtml("text/html; charset=utf-8", ByteArray(0), 0))
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
        assertFalse(DriveUpdate.looksLikeHtml("application/octet-stream", zip, zip.size))
        assertTrue(DriveUpdate.looksLikeApk(zip, zip.size))
        assertFalse(DriveUpdate.looksLikeApk(html, html.size))
    }

    @Test
    fun shaAndUrls() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DriveUpdate.sha256Hex(DriveUpdate.newSha256().digest(ByteArray(0))),
        )
        assertEquals("https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t", DriveUpdate.downloadUrl(id))
        assertEquals("7,1 МБ", DriveUpdate.formatMb(7_444_889))
    }

    private fun assertMessage(text: String, part: String) {
        try {
            DriveUpdate.Manifest.parse(text)
            fail("мало не прочитатись: $text")
        } catch (e: IllegalArgumentException) {
            assertTrue("«${e.message}» має містити «$part»", e.message!!.contains(part))
        }
    }

    /** Track Catcher 0.1.45 (faric 20.10 00:59: «Google Диск відповів кодом 500») — такі коди пробуємо ще раз. */
    @Test fun temporaryGoogleErrorsAreRetried() {
        assertEquals(true, DriveUpdate.retryable(500))
        assertEquals(true, DriveUpdate.retryable(503))
        assertEquals(true, DriveUpdate.retryable(429))
        assertEquals(false, DriveUpdate.retryable(404))
        assertEquals(false, DriveUpdate.retryable(200))
    }

    /** Track Catcher 0.1.48: «Опубліковано й перевірено» — лише коли файл версії з інтернету рівно наш. */
    @Test
    fun publishedOnlyWhenOnlineManifestIsExactlyOurs() {
        val sha = "b".repeat(64)
        val online = DriveUpdate.Manifest(pkg, 21, "0.4.5", sha, 6_948_040, "X".repeat(33), 0)
        assertEquals(true, DriveUpdate.manifestIsOurs(online, 21, sha.uppercase(), 6_948_040))
        assertEquals(false, DriveUpdate.manifestIsOurs(online.copy(versionCode = 20), 21, sha, 6_948_040)) // на Диску ще стара
        assertEquals(false, DriveUpdate.manifestIsOurs(online, 21, "c".repeat(64), 6_948_040)) // та сама версія, інший APK
        assertEquals(false, DriveUpdate.manifestIsOurs(online, 21, sha, 1))
        assertEquals(false, DriveUpdate.manifestIsOurs(null, 21, sha, 6_948_040))
    }

    @Test
    fun quietCheckOncePerDay() {
        val day = DriveUpdate.AUTO_CHECK_MS
        val now = 10 * day
        assertTrue(DriveUpdate.autoCheckDue(on = true, hasLink = true, lastAt = 0, now = now))
        assertTrue(DriveUpdate.autoCheckDue(true, true, now - day, now))
        assertFalse(DriveUpdate.autoCheckDue(true, true, now - day + 1, now))
        assertFalse(DriveUpdate.autoCheckDue(false, true, 0, now)) // вимкнено «Ні»
        assertFalse(DriveUpdate.autoCheckDue(true, false, 0, now)) // нема посилання (поки вбудоване порожнє)
        assertTrue(DriveUpdate.autoCheckDue(true, true, now + 1000, now)) // годинник перевели назад
    }

    /** T37: автор увімкнув «Публікувати автоматично» — нова встановлена версія сама йде на Диск, раз. */
    @Test
    fun autoPublishOnlyForNewerInstalledVersion() {
        val now = 1_000_000_000L
        val retry = DriveUpdate.AUTO_PUBLISH_RETRY_MS
        fun due(on: Boolean = true, ready: Boolean = true, installed: Long = 21, published: Long = 20, tried: Long = 0, at: Long = 0) =
            DriveUpdate.autoPublishDue(on, ready, installed, published, tried, at, now)
        assertTrue(due())
        assertFalse(due(on = false)) // за замовчуванням вимкнено
        assertFalse(due(ready = false)) // файли на Диску ще не налаштовані
        assertFalse(due(published = 21)) // цю вже опубліковано й перевірено
        assertFalse(due(published = 22))
        assertFalse(due(tried = 21, at = now - 60_000)) // щойно не вдалось — не на кожне відкриття
        assertTrue(due(tried = 21, at = now - retry)) // через 30 хв — ще раз
        assertTrue(due(tried = 20, at = now - 60_000)) // невдача була зі старою версією — нова пробує одразу
        assertTrue(due(tried = 21, at = now + 60_000)) // годинник перевели назад
    }
}
