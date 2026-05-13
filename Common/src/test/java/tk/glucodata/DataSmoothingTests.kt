package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSmoothingTests {
    @Test
    fun collapsePointsForDisplaySkipsOpenBucket() {
        val points = (0..7).map { minute ->
            GlucosePoint(
                minute * 60_000L,
                (100 + minute).toFloat(),
                (90 + minute).toFloat()
            )
        }

        val collapsed = DataSmoothing.collapsePointsForDisplay(
            points = points,
            smoothingMinutes = 3,
            nowMillis = (7 * 60_000L) + 30_000L
        )

        assertEquals(listOf(2L * 60_000L, 5L * 60_000L), collapsed.map { it.timestamp })
    }

    @Test
    fun collapsePointsForDisplayFallsBackToLatestWhenOnlyOpenBucketExists() {
        val points = listOf(
            GlucosePoint(6L * 60_000L, 100f, 90f),
            GlucosePoint(7L * 60_000L, 101f, 91f)
        )

        val collapsed = DataSmoothing.collapsePointsForDisplay(
            points = points,
            smoothingMinutes = 3,
            nowMillis = (7 * 60_000L) + 30_000L
        )

        assertEquals(listOf(7L * 60_000L), collapsed.map { it.timestamp })
    }

    @Test
    fun shouldSmoothExchangeOutputsIsExplicitOnly() {
        assertFalse(
            DataSmoothing.shouldSmoothExchangeOutputs(
                smoothingMinutes = 5,
                exchangeOutputsOnly = false
            )
        )

        assertTrue(
            DataSmoothing.shouldSmoothExchangeOutputs(
                smoothingMinutes = 5,
                exchangeOutputsOnly = true
            )
        )
    }

    @Test
    fun shouldSmoothExchangeOutputsRequiresEnabledSmoothingWindow() {
        assertFalse(
            DataSmoothing.shouldSmoothExchangeOutputs(
                smoothingMinutes = 0,
                exchangeOutputsOnly = true
            )
        )
    }

    @Test
    fun shouldCollapseExchangeOutputsRequiresEffectiveExchangeSmoothing() {
        assertFalse(
            DataSmoothing.shouldCollapseExchangeOutputs(
                smoothingMinutes = 5,
                exchangeOutputsOnly = false,
                collapseChunks = true
            )
        )

        assertTrue(
            DataSmoothing.shouldCollapseExchangeOutputs(
                smoothingMinutes = 5,
                exchangeOutputsOnly = true,
                collapseChunks = true
            )
        )
    }
}
