package tk.glucodata.drivers.anytime

import tk.glucodata.drivers.VirtualGlucoseSensorBridge

internal class AnytimeHistoryCaughtUpCooldown(
    private val cooldownMs: Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var caughtUpNextRequestId: Int = -1
    private var caughtUpAtMs: Long = 0L

    @Synchronized
    fun markCaughtUp(nextRequestId: Int) {
        caughtUpNextRequestId = nextRequestId.coerceAtLeast(0)
        caughtUpAtMs = nowMs()
    }

    @Synchronized
    fun clearIfNewerData(glucoseId: Int) {
        val caughtUpNext = caughtUpNextRequestId
        if (caughtUpNext >= 0 && glucoseId >= caughtUpNext) {
            caughtUpNextRequestId = -1
            caughtUpAtMs = 0L
        }
    }

    @Synchronized
    fun shouldSuppressBackfill(
        startId: Int,
        stopBeforeId: Int,
        reason: String,
        lastGlucoseId: Int,
    ): Boolean {
        if (reason.startsWith("user-requested")) return false
        val caughtUpNext = caughtUpNextRequestId
        if (caughtUpNext < 0 || stopBeforeId != Int.MAX_VALUE) return false
        if (startId < caughtUpNext) return false
        if (lastGlucoseId >= caughtUpNext) return false
        val elapsed = nowMs() - caughtUpAtMs
        return elapsed in 0 until cooldownMs
    }
}

internal data class AnytimePendingHistoryRoomImport(
    val glucoseId: Int,
    val source: AnytimeAlgorithm.Source,
    val priority: Int,
    val rawMgdl: Float,
    val temperatureC: Float,
    val reading: VirtualGlucoseSensorBridge.Reading,
)

internal class AnytimeHistoryRoomImportBuffer {
    private val pending = LinkedHashMap<Int, AnytimePendingHistoryRoomImport>()
    private val seenPriorities = HashMap<Int, Int>()

    @Synchronized
    fun queue(sampleMs: Long, result: AnytimeAlgorithm.Result): Boolean {
        val priority = priority(result.source)
        val seenPriority = seenPriorities[result.glucoseId]
        val pendingPriority = pending[result.glucoseId]?.priority
        val bestKnownPriority = maxOf(seenPriority ?: 0, pendingPriority ?: 0)
        if (bestKnownPriority >= priority) return false

        val raw = if (result.rawMgdl.isNaN()) result.mgdl else result.rawMgdl
        pending[result.glucoseId] = AnytimePendingHistoryRoomImport(
            glucoseId = result.glucoseId,
            source = result.source,
            priority = priority,
            rawMgdl = raw,
            temperatureC = result.temperatureC,
            reading = VirtualGlucoseSensorBridge.Reading(
                timestampMs = sampleMs,
                glucoseMgdl = result.mgdl,
                rawMgdl = raw,
            ),
        )
        return true
    }

    @Synchronized
    fun drain(): List<AnytimePendingHistoryRoomImport> {
        if (pending.isEmpty()) return emptyList()
        return pending.values.toList().also {
            pending.clear()
        }
    }

    @Synchronized
    fun markImported(imports: List<AnytimePendingHistoryRoomImport>) {
        imports.forEach { item ->
            val previousPriority = seenPriorities[item.glucoseId] ?: 0
            if (item.priority > previousPriority) {
                seenPriorities[item.glucoseId] = item.priority
            }
        }
    }

    private fun priority(source: AnytimeAlgorithm.Source): Int = when (source) {
        AnytimeAlgorithm.Source.NATIVE -> 2
        AnytimeAlgorithm.Source.LINEAR -> 1
    }
}
