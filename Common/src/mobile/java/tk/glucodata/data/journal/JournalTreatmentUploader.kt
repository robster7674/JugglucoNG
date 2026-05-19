package tk.glucodata.data.journal

import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NightPost
import tk.glucodata.data.HistoryDatabase
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sends Kotlin Journal entries (insulin / carbs / fingerstick) to Nightscout
 * as treatments. Replaces the legacy C++ uploadtreatments() path that pulled
 * from Numdata. Invoked from the native upload loop via NightPost.
 *
 * Sync state is tracked per-row on JournalEntryEntity (nsUploadedAt, nsRemoteId);
 * deletes are queued in journal_pending_deletes so they survive process death.
 */
@Keep
object JournalTreatmentUploader {
    private const val LOG_ID = "JournalTreatmentUploader"
    private const val ID_PREFIX = "jng-j-"
    private const val LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000  // mirrors C++ nighttimeback (30 days)

    // Mirrors writetreatment(V3) acceptance: 200/201 always; 409 only on V3 (POST conflict).
    private fun isUploadOk(code: Int, useV3: Boolean): Boolean {
        if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED) return true
        if (useV3 && code == HttpURLConnection.HTTP_CONFLICT) return true
        return false
    }

    @JvmStatic
    @Keep
    fun uploadAll(useV3: Boolean): Boolean = runBlocking {
        try {
            uploadInternal(useV3)
        } catch (th: Throwable) {
            Log.e(LOG_ID, "uploadAll failed: ${Log.stackline(th)}")
            false
        }
    }

    private suspend fun uploadInternal(useV3: Boolean): Boolean {
        val baseUrl = Natives.getnightuploadurl()?.takeIf { it.isNotBlank() } ?: return true
        val secretHashed = if (useV3) null else hashedSecret(Natives.getnightuploadsecret())
        val dao = HistoryDatabase.getInstance(Applic.app).journalDao()
        val presetCache = HashMap<Long, JournalInsulinPresetEntity?>()

        for (tomb in dao.getPendingNightscoutDeletes()) {
            val deleteUrl = treatmentDeleteUrl(baseUrl, tomb.nsRemoteId, useV3)
            if (NightPost.deleteUrl(deleteUrl, secretHashed)) {
                dao.clearPendingNightscoutDelete(tomb.entryId)
            } else {
                Log.e(LOG_ID, "tombstone delete failed for entryId=${tomb.entryId}")
                return false
            }
        }

        val sinceMillis = System.currentTimeMillis() - LOOKBACK_MILLIS
        val pending = dao.getEntriesNeedingNightscoutUpload(sinceMillis)
        for (entry in pending) {
            if (!isSendableType(entry.entryType)) continue
            if (entry.source == JournalEntrySource.AAPS.storageValue) continue

            val remoteId = entry.nsRemoteId ?: (ID_PREFIX + entry.id.toString(16))
            // Re-upload: drop the old copy first (mirrors legacy delete-then-PUT/POST).
            if (entry.nsRemoteId != null) {
                NightPost.deleteUrl(treatmentDeleteUrl(baseUrl, remoteId, useV3), secretHashed)
            }

            val preset = entry.insulinPresetId?.let { id ->
                presetCache.getOrPut(id) { dao.getInsulinPresetById(id) }
            }
            val json = buildTreatmentJson(entry, remoteId, preset, useV3) ?: continue
            val postUrl = treatmentPostUrl(baseUrl, useV3)
            val code = NightPost.upload(postUrl, json.toByteArray(Charsets.UTF_8), secretHashed, !useV3)
            if (!isUploadOk(code, useV3)) {
                Log.e(LOG_ID, "upload failed entry id=${entry.id} code=$code")
                return false
            }
            dao.markEntryUploadedToNightscout(entry.id, remoteId, System.currentTimeMillis())
        }
        return true
    }

    private fun isSendableType(entryType: String): Boolean {
        val type = JournalEntryType.fromStorage(entryType)
        return type == JournalEntryType.INSULIN ||
            type == JournalEntryType.CARBS ||
            type == JournalEntryType.FINGERSTICK
    }

    private fun treatmentPostUrl(baseUrl: String, useV3: Boolean): String =
        baseUrl + if (useV3) "/api/v3/treatments" else "/api/v1/treatments"

    private fun treatmentDeleteUrl(baseUrl: String, remoteId: String, useV3: Boolean): String =
        baseUrl + (if (useV3) "/api/v3/treatments/" else "/api/v1/treatments/") + remoteId

    private fun buildTreatmentJson(
        entry: JournalEntryEntity,
        remoteId: String,
        preset: JournalInsulinPresetEntity?,
        useV3: Boolean
    ): String? {
        val type = JournalEntryType.fromStorage(entry.entryType)
        val sb = StringBuilder(256)
        sb.append('{')
        if (useV3) {
            sb.append("\"identifier\":\"").append(remoteId).append("\",")
        }
        sb.append("\"_id\":\"").append(remoteId).append("\",")
        sb.append("\"date\":").append(entry.timestamp).append(',')
        if (!useV3) {
            sb.append("\"created_at\":\"").append(formatIso8601(entry.timestamp)).append("\",")
            sb.append("\"enteredBy\":\"Juggluco\",")
        } else {
            sb.append("\"app\":\"Juggluco\",")
        }

        when (type) {
            JournalEntryType.INSULIN -> {
                val units = entry.amount ?: return null
                if (units <= 0f) return null
                val isLong = preset?.let { !it.countsTowardIob } ?: false
                sb.append("\"eventType\":\"")
                    .append(if (isLong) "Temp Basal" else "Correction Bolus")
                    .append("\",")
                sb.append("\"notes\":\"")
                    .append(if (isLong) "Long-Acting" else "Rapid-Acting")
                    .append("\",")
                sb.append("\"insulin\":").append(formatNumber(units))
                preset?.displayName?.let { name ->
                    sb.append(",\"insulinType\":\"").append(escapeJson(name)).append('"')
                }
            }
            JournalEntryType.CARBS -> {
                val grams = entry.amount ?: return null
                if (grams <= 0f) return null
                sb.append("\"eventType\":\"Carb Correction\",")
                sb.append("\"carbs\":").append(formatNumber(grams))
            }
            JournalEntryType.FINGERSTICK -> {
                val mgdl = entry.glucoseValueMgDl ?: return null
                if (mgdl <= 0f) return null
                sb.append("\"eventType\":\"BG Check\",")
                sb.append("\"glucose\":").append(formatNumber(mgdl)).append(',')
                sb.append("\"glucoseType\":\"Finger\",")
                sb.append("\"units\":\"mg/dl\"")
            }
            else -> return null
        }

        entry.note?.takeIf { it.isNotBlank() && type != JournalEntryType.INSULIN }?.let { note ->
            sb.append(",\"notes\":\"").append(escapeJson(note)).append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    private fun formatNumber(value: Float): String {
        // Match C++ "%g" output: drop trailing zeros, no exponent for typical doses.
        if (value == value.toInt().toFloat()) return value.toInt().toString()
        return value.toString()
    }

    private val isoFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun formatIso8601(epochMillis: Long): String =
        isoFormatter.get()!!.format(Date(epochMillis))

    private fun escapeJson(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\', '"' -> { out.append('\\'); out.append(ch) }
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') {
                    out.append("\\u").append(String.format(Locale.US, "%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }

    private fun hashedSecret(raw: String?): String? {
        val s = raw?.takeIf { it.isNotEmpty() } ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        }
        return hex.toString()
    }
}
