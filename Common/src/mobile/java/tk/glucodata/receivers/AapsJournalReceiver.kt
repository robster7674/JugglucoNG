package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tk.glucodata.data.journal.AapsJournalImport

class AapsJournalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AapsJournalImport.isEnabled(context)) return

        val pendingResult = goAsync()
        importScope.launch {
            try {
                val result = AapsJournalImport.handleIntent(context.applicationContext, intent)
                if (result.importedEntries > 0 || result.deletedEntries > 0) {
                    Log.i(
                        TAG,
                        "AAPS journal import: imported=${result.importedEntries} " +
                            "deleted=${result.deletedEntries} skipped=${result.skippedTreatments}"
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "AAPS journal import failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AapsJournalReceiver"
        val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
