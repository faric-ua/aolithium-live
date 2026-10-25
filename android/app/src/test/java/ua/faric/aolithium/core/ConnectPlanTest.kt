package ua.faric.aolithium.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** T46: свіжий пошук перед підключенням (журнал faric 20.10 22:21–22:51). */
class ConnectPlanTest {
    @Test fun fresh_device_from_search_is_used_on_first_try() {
        assertEquals(ConnectPlan.First.CACHED, ConnectPlan.first(seenAgoMs = 2_000L, retry = false))
        assertEquals(ConnectPlan.First.CACHED, ConnectPlan.first(seenAgoMs = ConnectPlan.FRESH_MS, retry = false))
    }

    @Test fun stale_or_missing_device_means_scan_first() {
        // Так було в журналі: запис з 22:23, фон о 22:26…22:46 підключався до нього — і 133.
        assertEquals(ConnectPlan.First.SCAN, ConnectPlan.first(seenAgoMs = 3 * 60_000L, retry = false))
        assertEquals(ConnectPlan.First.SCAN, ConnectPlan.first(seenAgoMs = null, retry = false))
    }

    @Test fun every_retry_scans_again() {
        assertEquals(ConnectPlan.First.SCAN, ConnectPlan.first(seenAgoMs = 1_000L, retry = true))
    }

    @Test fun after_scan() {
        assertEquals(ConnectPlan.AfterScan.FOUND, ConnectPlan.afterScan(found = true, hasCached = true, quick = true))
        assertEquals(ConnectPlan.AfterScan.CACHED, ConnectPlan.afterScan(found = false, hasCached = true, quick = false))
        assertEquals(ConnectPlan.AfterScan.DIRECT, ConnectPlan.afterScan(found = false, hasCached = false, quick = true))
        assertEquals(ConnectPlan.AfterScan.FAIL, ConnectPlan.afterScan(found = false, hasCached = false, quick = false))
    }

    @Test fun background_retry_goes_direct_by_address() {
        // T84 п. 6 (Realme, 19:51): тричі «пристрій з минулого пошуку» → 133; тепер друга й третя — напряму за адресою.
        assertEquals(ConnectPlan.AfterScan.CACHED, ConnectPlan.afterScan(found = false, hasCached = true, quick = true, retry = false))
        assertEquals(ConnectPlan.AfterScan.DIRECT, ConnectPlan.afterScan(found = false, hasCached = true, quick = true, retry = true))
        // екран — як було (без «напряму»)
        assertEquals(ConnectPlan.AfterScan.CACHED, ConnectPlan.afterScan(found = false, hasCached = true, quick = false, retry = true))
        assertEquals(ConnectPlan.AfterScan.FOUND, ConnectPlan.afterScan(found = true, hasCached = true, quick = true, retry = true))
    }
}
