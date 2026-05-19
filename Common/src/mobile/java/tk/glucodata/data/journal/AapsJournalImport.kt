package tk.glucodata.data.journal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tk.glucodata.R
import java.security.MessageDigest
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

object AapsJournalImport {
    private const val TAG = "AapsJournalImport"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val SOURCE_PREFIX = "aaps"
    private const val MIN_VALID_EPOCH_MS = 946_684_800_000L

    const val PREF_KEY = "journal_aaps_import_enabled"
    const val ACTION_NEW_TREATMENT = "info.nightscout.client.NEW_TREATMENT"
    const val ACTION_CHANGED_TREATMENT = "info.nightscout.client.CHANGED_TREATMENT"
    const val ACTION_REMOVED_TREATMENT = "info.nightscout.client.REMOVED_TREATMENT"

    private val supportedActions = setOf(
        ACTION_NEW_TREATMENT,
        ACTION_CHANGED_TREATMENT,
        ACTION_REMOVED_TREATMENT
    )

    private val treatmentExtraKeys = listOf("treatments", "treatment", "data")

    data class ImportResult(
        val importedEntries: Int,
        val deletedEntries: Int,
        val skippedTreatments: Int
    )

    fun isEnabled(context: Context): Boolean {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY, enabled)
            .apply()
    }

    suspend fun handleIntent(context: Context, intent: Intent): ImportResult {
        val action = intent.action ?: return ImportResult(0, 0, 0)
        if (action !in supportedActions) return ImportResult(0, 0, 0)

        val treatments = extractTreatmentObjects(intent.extras)
        if (treatments.isEmpty()) return ImportResult(0, 0, 0)

        val repository = JournalRepository()
        if (action == ACTION_REMOVED_TREATMENT) {
            val sourceIds = treatments.flatMap { treatment ->
                val timestamp = treatment.optTreatmentTimestampMillis()
                treatment.sourceBaseId(timestamp).allEntrySourceIds()
            }
            repository.deleteEntriesBySourceRecordIds(sourceIds)
            return ImportResult(importedEntries = 0, deletedEntries = sourceIds.distinct().size, skippedTreatments = 0)
        }

        val needsInsulinPreset = treatments.any { it.optPositiveFloat("insulin", "enteredInsulin", "bolus") != null }
        val insulinPresets = if (needsInsulinPreset) {
            repository.ensureDefaultInsulinPresets()
            repository.getInsulinPresetsSnapshot()
        } else {
            emptyList()
        }

        var imported = 0
        var deleted = 0
        var skipped = 0
        for (treatment in treatments) {
            val parsed = treatment.toJournalInputs(context, insulinPresets)
            if (parsed == null) {
                skipped++
                continue
            }
            for (input in parsed.inputs) {
                repository.upsertEntry(input)
                imported++
            }
            if (action == ACTION_CHANGED_TREATMENT) {
                val importedSourceIds = parsed.inputs.mapNotNull { it.sourceRecordId }.toSet()
                val staleSourceIds = parsed.candidateSourceRecordIds.filterNot { it in importedSourceIds }
                repository.deleteEntriesBySourceRecordIds(staleSourceIds)
                deleted += staleSourceIds.size
            }
        }
        return ImportResult(importedEntries = imported, deletedEntries = deleted, skippedTreatments = skipped)
    }

    private data class ParsedTreatment(
        val inputs: List<JournalEntryInput>,
        val candidateSourceRecordIds: List<String>
    )

    private fun JSONObject.toJournalInputs(
        context: Context,
        insulinPresets: List<JournalInsulinPreset>
    ): ParsedTreatment? {
        val timestamp = optTreatmentTimestampMillis() ?: return null
        val baseId = sourceBaseId(timestamp)
        val eventType = optNonBlankString("eventType", "eventtype", "type")
        val note = buildNote(eventType, optNonBlankString("notes", "note"), optNonBlankString("enteredBy", "device"))
        val titleSuffix = eventType?.takeIf { it.isNotBlank() && !it.equals("Note", ignoreCase = true) }
        val inputs = ArrayList<JournalEntryInput>(2)

        val carbs = optFiniteFloat("carbs", "carb", "enteredCarbs", "grams")
            ?.takeIf { abs(it) >= 0.001f }
        if (carbs != null) {
            inputs.add(
                JournalEntryInput(
                    timestamp = timestamp,
                    type = JournalEntryType.CARBS,
                    title = titleSuffix ?: context.getString(R.string.journal_aaps_carbs_title),
                    note = note,
                    amount = carbs,
                    durationMinutes = optDurationMinutes(),
                    proteinGrams = optPositiveFloat("protein", "proteinGrams"),
                    fatGrams = optPositiveFloat("fat", "fatGrams"),
                    source = JournalEntrySource.AAPS,
                    sourceRecordId = baseId.entrySourceId("carbs")
                )
            )
        }

        val insulin = optPositiveFloat("insulin", "enteredInsulin", "bolus")
        if (insulin != null) {
            val preset = chooseInsulinPreset(insulinPresets)
            inputs.add(
                JournalEntryInput(
                    timestamp = timestamp,
                    type = JournalEntryType.INSULIN,
                    title = preset?.displayName ?: titleSuffix ?: context.getString(R.string.journal_aaps_insulin_title),
                    note = note,
                    amount = insulin,
                    insulinPresetId = preset?.id,
                    source = JournalEntrySource.AAPS,
                    sourceRecordId = baseId.entrySourceId("insulin")
                )
            )
        }

        if (inputs.isEmpty()) return null
        return ParsedTreatment(
            inputs = inputs,
            candidateSourceRecordIds = baseId.allEntrySourceIds()
        )
    }

    private fun chooseInsulinPreset(presets: List<JournalInsulinPreset>): JournalInsulinPreset? {
        return presets
            .filter { !it.isArchived && it.countsTowardIob }
            .minByOrNull { it.sortOrder }
            ?: presets.filter { it.countsTowardIob }.minByOrNull { it.sortOrder }
    }

    @Suppress("DEPRECATION")
    private fun extractTreatmentObjects(extras: Bundle?): List<JSONObject> {
        if (extras == null) return emptyList()
        val result = ArrayList<JSONObject>()
        for (key in treatmentExtraKeys) {
            appendJsonValue(extras.get(key), result)
        }
        if (result.isEmpty()) {
            for (key in extras.keySet()) {
                if (key.equals("treatments", ignoreCase = true) || key.equals("treatment", ignoreCase = true)) {
                    appendJsonValue(extras.get(key), result)
                }
            }
        }
        return result
    }

    private fun appendJsonValue(value: Any?, out: MutableList<JSONObject>) {
        when (value) {
            null -> return
            is JSONObject -> out.add(value)
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    value.optJSONObject(index)?.let(out::add)
                }
            }
            is String -> appendJsonString(value, out)
            is Iterable<*> -> value.forEach { appendJsonValue(it, out) }
            is Array<*> -> value.forEach { appendJsonValue(it, out) }
            else -> appendJsonString(value.toString(), out)
        }
    }

    private fun appendJsonString(raw: String, out: MutableList<JSONObject>) {
        val text = raw.trim()
        if (text.isEmpty()) return
        try {
            when {
                text.startsWith("[") -> appendJsonValue(JSONArray(text), out)
                text.startsWith("{") -> {
                    val root = JSONObject(text)
                    val nested = root.optJSONArray("treatments")
                        ?: root.optJSONArray("treatment")
                    if (nested != null) {
                        appendJsonValue(nested, out)
                    } else {
                        out.add(root)
                    }
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Ignoring malformed AAPS treatment payload", e)
        }
    }

    private fun JSONObject.sourceBaseId(timestamp: Long?): String {
        val direct = optNonBlankString("_id", "id", "identifier", "NSCLIENT_ID", "pumpId")
        if (direct != null) return direct
        val fingerprint = listOf(
            timestamp ?: 0L,
            optNonBlankString("eventType", "eventtype", "type").orEmpty(),
            optFiniteFloat("carbs", "carb", "enteredCarbs", "grams")?.toString().orEmpty(),
            optPositiveFloat("insulin", "enteredInsulin", "bolus")?.toString().orEmpty(),
            optNonBlankString("notes", "note").orEmpty()
        ).joinToString("|")
        return "hash:${fingerprint.sha256Short()}"
    }

    private fun String.entrySourceId(kind: String): String = "$SOURCE_PREFIX:$this:$kind"

    private fun String.allEntrySourceIds(): List<String> {
        return listOf(entrySourceId("carbs"), entrySourceId("insulin"))
    }

    private fun JSONObject.optTreatmentTimestampMillis(): Long? {
        for (key in listOf("date", "mills", "millis", "timestamp", "time", "createdAt")) {
            val normalized = optEpochMillis(key)
            if (normalized != null) return normalized
        }
        for (key in listOf("created_at", "dateString", "createdAt", "timestamp", "time")) {
            val parsed = optNonBlankString(key)?.let(::parseDateString)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun JSONObject.optEpochMillis(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        val longValue = when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }?.toLong()
            is String -> value.trim().toDoubleOrNull()?.toLong()
            else -> null
        } ?: return null
        val millis = if (longValue in 1L until 10_000_000_000L) longValue * 1000L else longValue
        return millis.takeIf { it >= MIN_VALID_EPOCH_MS }
    }

    private fun parseDateString(text: String): Long? {
        try {
            return Instant.parse(text).toEpochMilli().takeIf { it >= MIN_VALID_EPOCH_MS }
        } catch (ignored: DateTimeParseException) {
        }

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return format.parse(text)?.time?.takeIf { it >= MIN_VALID_EPOCH_MS }
            } catch (ignored: ParseException) {
            }
        }
        return null
    }

    private fun JSONObject.optDurationMinutes(): Int? {
        val minutes = optFiniteFloat("duration", "durationMinutes", "absorptionTime", "absorptionMinutes")
            ?: return null
        if (minutes <= 0f) return null
        return minutes.toInt().coerceIn(15, 480)
    }

    private fun JSONObject.optPositiveFloat(vararg keys: String): Float? {
        return optFiniteFloat(*keys)?.takeIf { it > 0.0001f }
    }

    private fun JSONObject.optFiniteFloat(vararg keys: String): Float? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            val parsed = when (value) {
                is Number -> value.toFloat()
                is String -> value.trim().replace(',', '.').toFloatOrNull()
                else -> null
            }
            if (parsed != null && parsed.isFinite()) return parsed
        }
        return null
    }

    private fun JSONObject.optNonBlankString(vararg keys: String): String? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val text = optString(key, "").trim()
            if (text.isNotBlank() && text != "null") return text
        }
        return null
    }

    private fun buildNote(vararg parts: String?): String? {
        val unique = LinkedHashSet<String>()
        parts
            .flatMap { it.orEmpty().split('\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(unique::add)
        return unique.joinToString(" | ").takeIf { it.isNotBlank() }
    }

    private fun String.sha256Short(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
