package tk.glucodata.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.BuildConfig
import tk.glucodata.data.calibration.CalibrationDatabase
import tk.glucodata.data.calibration.CalibrationEntity
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalInsulinPresetEntity
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object ExportPackageExporter {
    private const val SCHEMA = "tk.glucodata.export-package"
    private const val SCHEMA_VERSION = 1

    data class ExportRequest(
        val includeSettings: Boolean,
        val includeHistory: Boolean,
        val includeCalibrations: Boolean,
        val historyDays: Long?
    ) {
        val hasSelection: Boolean
            get() = includeSettings || includeHistory || includeCalibrations

        val isSettingsOnly: Boolean
            get() = includeSettings && !includeHistory && !includeCalibrations
    }

    data class ExportSummary(
        val settingsIncluded: Boolean,
        val historyReadings: Int,
        val journalEntries: Int,
        val journalFoods: Int,
        val insulinPresets: Int,
        val calibrations: Int
    )

    data class CachedExport(
        val file: File,
        val fileName: String,
        val mimeType: String
    )

    suspend fun exportToUri(
        context: Context,
        uri: Uri,
        request: ExportRequest
    ): Result<ExportSummary> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                require(request.hasSelection) { "No export content selected" }
                val (payload, summary) = buildPayload(appContext, request)
                val outputStream = appContext.contentResolver.openOutputStream(uri)
                    ?: error("Could not open export destination")
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(payload.toString(2))
                    writer.write("\n")
                }
                summary
            }
        }
    }

    suspend fun writeToCache(
        context: Context,
        request: ExportRequest
    ): Result<CachedExport> {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                require(request.hasSelection) { "No export content selected" }
                val (payload, _) = buildPayload(appContext, request)
                val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                exportDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("Juggluco_") }
                    ?.forEach { it.delete() }

                val fileName = suggestedFileName(request)
                val file = File(exportDir, fileName)
                OutputStreamWriter(file.outputStream(), StandardCharsets.UTF_8).use { writer ->
                    writer.write(payload.toString(2))
                    writer.write("\n")
                }
                CachedExport(
                    file = file,
                    fileName = fileName,
                    mimeType = mimeTypeFor(request)
                )
            }
        }
    }

    fun mimeTypeFor(request: ExportRequest): String = "application/json"

    fun suggestedFileName(request: ExportRequest): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
        val label = when {
            request.includeSettings && request.includeHistory && request.includeCalibrations -> "Everything"
            request.includeSettings && !request.includeHistory && !request.includeCalibrations -> "Settings"
            request.includeHistory && !request.includeSettings && !request.includeCalibrations -> "Data"
            request.includeCalibrations && !request.includeSettings && !request.includeHistory -> "Calibrations"
            else -> "Package"
        }
        return "Juggluco_${label}_$date.json"
    }

    private suspend fun buildPayload(
        context: Context,
        request: ExportRequest
    ): Pair<JSONObject, ExportSummary> {
        if (request.isSettingsOnly) {
            return SettingsExporter.buildExportPayload(context) to ExportSummary(
                settingsIncluded = true,
                historyReadings = 0,
                journalEntries = 0,
                journalFoods = 0,
                insulinPresets = 0,
                calibrations = 0
            )
        }

        val sections = JSONArray()
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("containsSensitiveData", request.includeSettings)
            .put("containsHealthData", request.includeHistory || request.includeCalibrations)
            .put("app", buildAppInfo(context))

        if (request.includeSettings) {
            sections.put("settings")
            root.put("settings", SettingsExporter.buildExportPayload(context, includeJournalData = false))
        }

        val historySummary = if (request.includeHistory) {
            sections.put("history")
            val (history, summary) = buildHistorySection(context, request.historyDays)
            root.put("history", history)
            summary
        } else {
            HistorySummary()
        }

        val calibrationCount = if (request.includeCalibrations) {
            sections.put("calibrations")
            val (calibrations, count) = buildCalibrationSection(context)
            root.put("calibrations", calibrations)
            count
        } else {
            0
        }

        root.put("sections", sections)

        return root to ExportSummary(
            settingsIncluded = request.includeSettings,
            historyReadings = historySummary.readings,
            journalEntries = historySummary.journalEntries,
            journalFoods = historySummary.journalFoods,
            insulinPresets = historySummary.insulinPresets,
            calibrations = calibrationCount
        )
    }

    private fun buildAppInfo(context: Context): JSONObject {
        return JSONObject()
            .put("packageName", context.packageName)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("locale", Locale.getDefault().toLanguageTag())
    }

    private data class HistorySummary(
        val readings: Int = 0,
        val journalEntries: Int = 0,
        val journalFoods: Int = 0,
        val insulinPresets: Int = 0
    )

    private suspend fun buildHistorySection(
        context: Context,
        historyDays: Long?
    ): Pair<JSONObject, HistorySummary> {
        val database = HistoryDatabase.getInstance(context)
        val endMillis = System.currentTimeMillis()
        val startMillis = historyDays
            ?.let { endMillis - TimeUnit.DAYS.toMillis(it.coerceAtLeast(1L)) }
            ?: 0L
        val readings = database.historyDao().getReadingsSince(startMillis)
        val journalEntries = if (startMillis > 0L) {
            database.journalDao().getEntriesBetween(startMillis, endMillis)
        } else {
            database.journalDao().getEntries()
        }
        val insulinPresets = database.journalDao().getInsulinPresets()
        val foods = database.journalDao().getFoods()

        return JSONObject()
            .put("rangeStartEpochMillis", if (startMillis > 0L) startMillis else JSONObject.NULL)
            .put("rangeEndEpochMillis", endMillis)
            .put("storedUnit", "mg/dL")
            .put(
                "readings",
                JSONArray().also { array ->
                    readings.forEach { array.put(it.toJson()) }
                }
            )
            .put(
                "journalEntries",
                JSONArray().also { array ->
                    journalEntries.forEach { array.put(it.toJson()) }
                }
            )
            .put(
                "journalInsulinPresets",
                JSONArray().also { array ->
                    insulinPresets.forEach { array.put(it.toJson()) }
                }
            )
            .put(
                "journalFoods",
                JSONArray().also { array ->
                    foods.forEach { array.put(it.toJson()) }
                }
            ) to HistorySummary(
            readings = readings.size,
            journalEntries = journalEntries.size,
            journalFoods = foods.size,
            insulinPresets = insulinPresets.size
        )
    }

    private suspend fun buildCalibrationSection(context: Context): Pair<JSONObject, Int> {
        CalibrationManager.init(context)
        val rows = CalibrationDatabase.getInstance(context)
            .calibrationDao()
            .getAllSync()
            .sortedByDescending { it.timestamp }

        return JSONObject()
            .put("version", 1)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("rawEnabled", CalibrationManager.isEnabledForRaw.value)
            .put("autoEnabled", CalibrationManager.isEnabledForAuto.value)
            .put("hideInitialWhenCalibrated", CalibrationManager.hideInitialWhenCalibrated.value)
            .put("applyToPast", CalibrationManager.applyToPast.value)
            .put("lockPastHistory", CalibrationManager.lockPastHistory.value)
            .put("overwriteSensorValues", CalibrationManager.overwriteSensorValues.value)
            .put("visualContinuity", CalibrationManager.visualContinuity.value)
            .put("rawAlgorithm", CalibrationManager.getAlgorithmForMode(isRawMode = true).storageValue)
            .put("autoAlgorithm", CalibrationManager.getAlgorithmForMode(isRawMode = false).storageValue)
            .put(
                "calibrations",
                JSONArray().also { array ->
                    rows.forEach { array.put(it.toJson()) }
                }
            ) to rows.size
    }

    private fun HistoryReading.toJson(): JSONObject {
        return JSONObject()
            .put("timestamp", timestamp)
            .put("sensorSerial", sensorSerial)
            .put("valueMgDl", value.toDouble())
            .put("rawValueMgDl", rawValue.toDouble())
            .put("rate", rate?.toDouble() ?: JSONObject.NULL)
    }

    private fun JournalEntryEntity.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("timestamp", timestamp)
            .put("sensorSerial", sensorSerial ?: JSONObject.NULL)
            .put("entryType", entryType)
            .put("title", title)
            .put("note", note ?: JSONObject.NULL)
            .put("amount", amount?.toDouble() ?: JSONObject.NULL)
            .put("glucoseValueMgDl", glucoseValueMgDl?.toDouble() ?: JSONObject.NULL)
            .put("durationMinutes", durationMinutes ?: JSONObject.NULL)
            .put("intensity", intensity ?: JSONObject.NULL)
            .put("insulinPresetId", insulinPresetId ?: JSONObject.NULL)
            .put("foodId", foodId ?: JSONObject.NULL)
            .put("proteinGrams", proteinGrams?.toDouble() ?: JSONObject.NULL)
            .put("fatGrams", fatGrams?.toDouble() ?: JSONObject.NULL)
            .put("source", source)
            .put("sourceRecordId", sourceRecordId ?: JSONObject.NULL)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("nsUploadedAt", nsUploadedAt ?: JSONObject.NULL)
            .put("nsRemoteId", nsRemoteId ?: JSONObject.NULL)
    }

    private fun JournalInsulinPresetEntity.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("onsetMinutes", onsetMinutes)
            .put("durationMinutes", durationMinutes)
            .put("accentColor", accentColor)
            .put("curveJson", curveJson)
            .put("isBuiltIn", isBuiltIn)
            .put("isArchived", isArchived)
            .put("countsTowardIob", countsTowardIob)
            .put("sortOrder", sortOrder)
    }

    private fun JournalFoodEntity.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("carbsGrams", carbsGrams.toDouble())
            .put("proteinGrams", proteinGrams?.toDouble() ?: JSONObject.NULL)
            .put("fatGrams", fatGrams?.toDouble() ?: JSONObject.NULL)
            .put("absorptionMinutes", absorptionMinutes)
            .put("accentColor", accentColor)
            .put("isBuiltIn", isBuiltIn)
            .put("isArchived", isArchived)
            .put("sortOrder", sortOrder)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
    }

    private fun CalibrationEntity.toJson(): JSONObject {
        return JSONObject()
            .put("timestamp", timestamp)
            .put("sensorId", sensorId)
            .put("sensorValue", sensorValue.toDouble())
            .put("sensorValueRaw", sensorValueRaw.toDouble())
            .put("userValue", userValue.toDouble())
            .put("isEnabled", isEnabled)
            .put("isRawMode", isRawMode)
    }
}
