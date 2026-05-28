package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeHistoryBackfillStateTests {

    @Test
    fun caughtUpCooldownSuppressesImmediateSameIdBackfillUntilNewerDataArrives() {
        var nowMs = 10_000L
        val cooldown = AnytimeHistoryCaughtUpCooldown(
            cooldownMs = 120_000L,
            nowMs = { nowMs },
        )

        cooldown.markCaughtUp(nextRequestId = 101)

        assertTrue(
            cooldown.shouldSuppressBackfill(
                startId = 101,
                stopBeforeId = Int.MAX_VALUE,
                reason = "reconnect",
                lastGlucoseId = 100,
            )
        )
        assertFalse(
            cooldown.shouldSuppressBackfill(
                startId = 101,
                stopBeforeId = Int.MAX_VALUE,
                reason = "user-requested-clean",
                lastGlucoseId = 100,
            )
        )
        assertFalse(
            cooldown.shouldSuppressBackfill(
                startId = 101,
                stopBeforeId = 120,
                reason = "finite-gap",
                lastGlucoseId = 100,
            )
        )

        cooldown.clearIfNewerData(glucoseId = 101)

        assertFalse(
            cooldown.shouldSuppressBackfill(
                startId = 101,
                stopBeforeId = Int.MAX_VALUE,
                reason = "reconnect",
                lastGlucoseId = 101,
            )
        )

        cooldown.markCaughtUp(nextRequestId = 102)
        nowMs += 120_000L

        assertFalse(
            cooldown.shouldSuppressBackfill(
                startId = 102,
                stopBeforeId = Int.MAX_VALUE,
                reason = "reconnect",
                lastGlucoseId = 101,
            )
        )
    }

    @Test
    fun historyImportBufferBatchesDedupesAndLetsNativeReplaceLinearBeforeImport() {
        val buffer = AnytimeHistoryRoomImportBuffer()

        assertTrue(buffer.queue(sampleMs = 1_000L, result = result(12, AnytimeAlgorithm.Source.LINEAR)))
        assertFalse(buffer.queue(sampleMs = 1_000L, result = result(12, AnytimeAlgorithm.Source.LINEAR)))
        assertTrue(buffer.queue(sampleMs = 1_000L, result = result(12, AnytimeAlgorithm.Source.NATIVE)))
        assertTrue(buffer.queue(sampleMs = 2_000L, result = result(13, AnytimeAlgorithm.Source.LINEAR)))

        val batch = buffer.drain()

        assertEquals(2, batch.size)
        assertEquals(listOf(12, 13), batch.map { it.glucoseId })
        assertEquals(AnytimeAlgorithm.Source.NATIVE, batch[0].source)
        assertEquals(100f, batch[0].reading.glucoseMgdl, 0.001f)
        assertEquals(2_000L, batch[1].reading.timestampMs)

        buffer.markImported(batch)

        assertFalse(buffer.queue(sampleMs = 1_000L, result = result(12, AnytimeAlgorithm.Source.LINEAR)))
        assertFalse(buffer.queue(sampleMs = 1_000L, result = result(12, AnytimeAlgorithm.Source.NATIVE)))
        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun restoredCursorFallsBackToCachedRawTailWhenPrefIsAhead() {
        assertEquals(
            687,
            sanitizeRestoredGlucoseId(
                persistedLastId = 6_841,
                cachedRawMaxId = 687,
                rollbackThreshold = 48,
            )
        )
        assertEquals(
            720,
            sanitizeRestoredGlucoseId(
                persistedLastId = 720,
                cachedRawMaxId = 687,
                rollbackThreshold = 48,
            )
        )
    }

    @Test
    fun liveIdRollbackStartsNewSensorSession() {
        assertTrue(
            liveIdLooksRolledBack(
                liveId = 491,
                previousMaxId = 6_841,
                rollbackThreshold = 48,
            )
        )
        assertFalse(
            liveIdLooksRolledBack(
                liveId = 727,
                previousMaxId = 729,
                rollbackThreshold = 48,
            )
        )
    }

    private fun result(
        glucoseId: Int,
        source: AnytimeAlgorithm.Source,
    ): AnytimeAlgorithm.Result =
        AnytimeAlgorithm.Result(
            glucoseId = glucoseId,
            mmol = 5.55f,
            mgdlTimes10 = 1_000,
            ibNa = 1f,
            iwNa = 2f,
            temperatureC = 32f,
            trend = 0,
            errorCode = 0,
            warnCode = 0,
            source = source,
            rawMgdl = 95f,
        )
}
