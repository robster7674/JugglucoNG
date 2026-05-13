package tk.glucodata.data.journal

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.data.HistoryDatabase

class JournalRepository {
    private companion object {
        const val PREFS_NAME = "tk.glucodata_preferences"
        const val DEFAULT_PRESETS_SEEDED_KEY = "journal_default_presets_seeded_v4"
        const val DEFAULT_FOODS_SEEDED_KEY = "journal_default_foods_seeded_v1"
    }

    private val database = HistoryDatabase.getInstance(Applic.app)
    private val dao = database.journalDao()
    private val prefs = Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun observeEntries(): Flow<List<JournalEntry>> {
        return dao.observeEntries().map { entries -> entries.map(JournalEntryEntity::toModel) }
    }

    fun observeInsulinPresets(): Flow<List<JournalInsulinPreset>> {
        return dao.observeInsulinPresets().map { presets -> presets.map(JournalInsulinPresetEntity::toModel) }
    }

    fun observeFoods(): Flow<List<JournalFood>> {
        return dao.observeFoods().map { foods -> foods.map(JournalFoodEntity::toModel) }
    }

    suspend fun ensureDefaultInsulinPresets() {
        if (prefs.getBoolean(DEFAULT_PRESETS_SEEDED_KEY, false)) return
        database.withTransaction {
            val existing = dao.getInsulinPresets()
            if (existing.isEmpty()) {
                dao.insertInsulinPresets(defaultPresets())
            } else {
                dao.insertInsulinPresets(mergeBuiltInPresets(existing))
            }
            prefs.edit().putBoolean(DEFAULT_PRESETS_SEEDED_KEY, true).apply()
        }
    }

    suspend fun upsertEntry(input: JournalEntryInput): Long {
        val existing = input.id?.let { dao.getEntryById(it) }
        val now = System.currentTimeMillis()
        val entity = JournalEntryEntity(
            id = existing?.id ?: (input.id ?: 0L),
            timestamp = input.timestamp,
            sensorSerial = input.sensorSerial?.takeIf { it.isNotBlank() },
            entryType = input.type.storageValue,
            title = input.title.trim(),
            note = input.note?.trim()?.takeIf { it.isNotBlank() },
            amount = input.amount,
            glucoseValueMgDl = input.glucoseValueMgDl,
            durationMinutes = input.durationMinutes,
            intensity = input.intensity?.storageValue,
            insulinPresetId = input.insulinPresetId,
            source = input.source.storageValue,
            sourceRecordId = input.sourceRecordId?.takeIf { it.isNotBlank() },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            foodId = input.foodId,
            proteinGrams = input.proteinGrams?.coerceAtLeast(0f),
            fatGrams = input.fatGrams?.coerceAtLeast(0f),
            nsUploadedAt = existing?.nsUploadedAt,
            nsRemoteId = existing?.nsRemoteId
        )
        return dao.upsertEntry(entity)
    }

    suspend fun deleteEntry(entryId: Long) {
        database.withTransaction {
            val existing = dao.getEntryById(entryId)
            val remoteId = existing?.nsRemoteId
            if (remoteId != null) {
                dao.enqueuePendingNightscoutDelete(
                    JournalPendingDeleteEntity(
                        entryId = entryId,
                        nsRemoteId = remoteId,
                        deletedAt = System.currentTimeMillis()
                    )
                )
            }
            dao.deleteEntryById(entryId)
        }
    }

    suspend fun upsertInsulinPreset(input: JournalInsulinPresetInput): Long {
        val existing = input.id?.let { dao.getInsulinPresetById(it) }
        val entity = JournalInsulinPresetEntity(
            id = existing?.id ?: (input.id ?: 0L),
            displayName = input.displayName.trim(),
            onsetMinutes = input.onsetMinutes.coerceAtLeast(0),
            durationMinutes = input.durationMinutes.coerceAtLeast(input.onsetMinutes.coerceAtLeast(0)),
            accentColor = input.accentColor,
            curveJson = input.curveJson.ifBlank {
                serializeJournalCurve(defaultJournalCurve(input.onsetMinutes, input.durationMinutes))
            },
            isBuiltIn = existing?.isBuiltIn ?: input.isBuiltIn,
            isArchived = input.isArchived,
            countsTowardIob = input.countsTowardIob,
            sortOrder = input.sortOrder
        )
        return dao.upsertInsulinPreset(entity)
    }

    suspend fun deleteInsulinPreset(presetId: Long) {
        dao.deleteInsulinPresetById(presetId)
    }

    suspend fun ensureDefaultFoods() {
        val seeded = prefs.getBoolean(DEFAULT_FOODS_SEEDED_KEY, false)
        database.withTransaction {
            val existing = dao.getFoods()
            if (seeded && existing.isNotEmpty()) return@withTransaction
            if (existing.isEmpty()) {
                dao.insertFoods(defaultFoods())
            }
            prefs.edit().putBoolean(DEFAULT_FOODS_SEEDED_KEY, true).apply()
        }
    }

    suspend fun upsertFood(input: JournalFoodInput): Long {
        val existing = input.id?.let { dao.getFoodById(it) }
        val now = System.currentTimeMillis()
        val entity = JournalFoodEntity(
            id = existing?.id ?: (input.id ?: 0L),
            displayName = input.displayName.trim(),
            carbsGrams = input.carbsGrams.coerceAtLeast(0f),
            proteinGrams = input.proteinGrams?.coerceAtLeast(0f),
            fatGrams = input.fatGrams?.coerceAtLeast(0f),
            absorptionMinutes = input.absorptionMinutes.coerceIn(15, 480),
            accentColor = input.accentColor,
            isBuiltIn = existing?.isBuiltIn ?: input.isBuiltIn,
            isArchived = input.isArchived,
            sortOrder = input.sortOrder,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        return dao.upsertFood(entity)
    }

    suspend fun deleteFood(foodId: Long) {
        dao.deleteFoodById(foodId)
    }

    private fun defaultPresets(): List<JournalInsulinPresetEntity> {
        val app = Applic.app
        return listOf(
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.rapidinsulin),
                onsetMinutes = 14,
                durationMinutes = 360,
                accentColor = 0xFF1565C0.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.RAPID_GENERIC)),
                isBuiltIn = true,
                isArchived = false,
                countsTowardIob = true,
                sortOrder = 0
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.longinsulin),
                onsetMinutes = 90,
                durationMinutes = 1440,
                accentColor = 0xFF6B7C3B.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.LONG_BASAL_GENERIC)),
                isBuiltIn = true,
                isArchived = false,
                countsTowardIob = false,
                sortOrder = 1
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.humaninsulin),
                onsetMinutes = 28,
                durationMinutes = 510,
                accentColor = 0xFF6A1B9A.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.HUMAN_REGULAR)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 2
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.aspart),
                onsetMinutes = 14,
                durationMinutes = 360,
                accentColor = 0xFF1976D2.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.ASPART)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 3
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.lispro),
                onsetMinutes = 12,
                durationMinutes = 330,
                accentColor = 0xFF00897B.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.LISPRO)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 4
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.glulisine),
                onsetMinutes = 15,
                durationMinutes = 480,
                accentColor = 0xFF00838F.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.GLULISINE)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 5
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.fiasp),
                onsetMinutes = 10,
                durationMinutes = 360,
                accentColor = 0xFF2E7D32.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.FIASP)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 6
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.urli),
                onsetMinutes = 15,
                durationMinutes = 370,
                accentColor = 0xFF00695C.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.URLI)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 7
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.afrezza),
                onsetMinutes = 12,
                durationMinutes = 210,
                accentColor = 0xFFEF6C00.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.AFREZZA)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 8
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.journal_preset_nph),
                onsetMinutes = 90,
                durationMinutes = 720,
                accentColor = 0xFFE67E22.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.NPH)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = true,
                sortOrder = 9
            ),
            JournalInsulinPresetEntity(
                displayName = app.getString(R.string.journal_preset_ultra_long_basal),
                onsetMinutes = 180,
                durationMinutes = 2160,
                accentColor = 0xFF3949AB.toInt(),
                curveJson = serializeJournalCurve(builtInJournalCurve(JournalBuiltInCurveProfile.ULTRA_LONG_BASAL)),
                isBuiltIn = true,
                isArchived = true,
                countsTowardIob = false,
                sortOrder = 10
            )
        )
    }

    private fun mergeBuiltInPresets(
        existing: List<JournalInsulinPresetEntity>
    ): List<JournalInsulinPresetEntity> {
        val builtInsByName = existing.filter { it.isBuiltIn }.associateBy { it.displayName }
        val builtInsBySortOrder = existing.filter { it.isBuiltIn }.associateBy { it.sortOrder }
        return defaultPresets().map { preset ->
            val existingMatch = builtInsByName[preset.displayName]
                ?: legacyBuiltInMatch(preset.sortOrder, builtInsBySortOrder)
            existingMatch?.copy(
                displayName = preset.displayName,
                onsetMinutes = preset.onsetMinutes,
                durationMinutes = preset.durationMinutes,
                accentColor = preset.accentColor,
                curveJson = preset.curveJson,
                isArchived = preset.isArchived,
                countsTowardIob = preset.countsTowardIob,
                sortOrder = preset.sortOrder
            ) ?: preset
        }
    }

    private fun legacyBuiltInMatch(
        newSortOrder: Int,
        builtInsBySortOrder: Map<Int, JournalInsulinPresetEntity>
    ): JournalInsulinPresetEntity? {
        val legacySortOrder = when (newSortOrder) {
            0 -> 1
            1 -> 4
            2 -> 2
            7 -> 0
            9 -> 3
            10 -> 5
            else -> null
        } ?: return null
        return builtInsBySortOrder[legacySortOrder]
    }

    private fun defaultFoods(): List<JournalFoodEntity> {
        val app = Applic.app
        val now = System.currentTimeMillis()
        return listOf(
            JournalFoodEntity(
                displayName = app.getString(R.string.journal_food_fast_carbs),
                carbsGrams = 15f,
                proteinGrams = 0f,
                fatGrams = 0f,
                absorptionMinutes = 45,
                accentColor = 0xFF4F7C58.toInt(),
                isBuiltIn = true,
                isArchived = false,
                sortOrder = 0,
                createdAt = now,
                updatedAt = now
            ),
            JournalFoodEntity(
                displayName = app.getString(R.string.journal_food_balanced_meal),
                carbsGrams = 45f,
                proteinGrams = 20f,
                fatGrams = 15f,
                absorptionMinutes = 120,
                accentColor = 0xFF5F7D4B.toInt(),
                isBuiltIn = true,
                isArchived = false,
                sortOrder = 1,
                createdAt = now,
                updatedAt = now
            ),
            JournalFoodEntity(
                displayName = app.getString(R.string.journal_food_slow_meal),
                carbsGrams = 60f,
                proteinGrams = 25f,
                fatGrams = 25f,
                absorptionMinutes = 240,
                accentColor = 0xFF8A7347.toInt(),
                isBuiltIn = true,
                isArchived = false,
                sortOrder = 2,
                createdAt = now,
                updatedAt = now
            ),
            JournalFoodEntity(
                displayName = app.getString(R.string.journal_food_protein_snack),
                carbsGrams = 10f,
                proteinGrams = 25f,
                fatGrams = 8f,
                absorptionMinutes = 180,
                accentColor = 0xFF6F6650.toInt(),
                isBuiltIn = true,
                isArchived = false,
                sortOrder = 3,
                createdAt = now,
                updatedAt = now
            )
        )
    }
}

private fun JournalEntryEntity.toModel(): JournalEntry {
    return JournalEntry(
        id = id,
        timestamp = timestamp,
        sensorSerial = sensorSerial,
        type = JournalEntryType.fromStorage(entryType),
        title = title,
        note = note,
        amount = amount,
        glucoseValueMgDl = glucoseValueMgDl,
        durationMinutes = durationMinutes,
        intensity = JournalIntensity.fromStorage(intensity),
        insulinPresetId = insulinPresetId,
        foodId = foodId,
        proteinGrams = proteinGrams,
        fatGrams = fatGrams,
        source = JournalEntrySource.fromStorage(source),
        sourceRecordId = sourceRecordId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun JournalInsulinPresetEntity.toModel(): JournalInsulinPreset {
    return JournalInsulinPreset(
        id = id,
        displayName = displayName,
        onsetMinutes = onsetMinutes,
        durationMinutes = durationMinutes,
        accentColor = accentColor,
        curveJson = curveJson,
        isBuiltIn = isBuiltIn,
        isArchived = isArchived,
        countsTowardIob = countsTowardIob,
        sortOrder = sortOrder
    )
}

private fun JournalFoodEntity.toModel(): JournalFood {
    return JournalFood(
        id = id,
        displayName = displayName,
        carbsGrams = carbsGrams,
        proteinGrams = proteinGrams,
        fatGrams = fatGrams,
        absorptionMinutes = absorptionMinutes,
        accentColor = accentColor,
        isBuiltIn = isBuiltIn,
        isArchived = isArchived,
        sortOrder = sortOrder
    )
}
