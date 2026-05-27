package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.Notify
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.Log
import tk.glucodata.logic.CustomAlertManager

/**
 * Handles notification actions for alerts (snooze, dismiss).
 */
class AlarmActionReceiver : BroadcastReceiver() {
    
    companion object {
        private const val LOG_ID = "AlarmActionReceiver"
        const val ACTION_SNOOZE = "tk.glucodata.ACTION_SNOOZE"
        const val ACTION_DISMISS = "tk.glucodata.ACTION_DISMISS"
        const val ACTION_IGNORE = "tk.glucodata.ACTION_IGNORE"
        const val EXTRA_ALERT_TYPE_ID = "alert_type_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val customAlertId = intent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID)
        val fallbackAlertTypeId = intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1)
        val resolvedAlertType = AlertType.fromId(Notify.resolveAlertKind(fallbackAlertTypeId))
        
        when (intent.action) {
            ACTION_DISMISS -> {
                Log.i(LOG_ID, "Dismiss action received for alert type: $resolvedAlertType")
                Notify.stopalarm()
                if (customAlertId != null) {
                    CustomAlertManager.dismissAlert(customAlertId)
                } else {
                    Notify.cancelCurrentRetrySession("notification-dismiss")
                    resolvedAlertType?.let {
                        SnoozeManager.clearSnooze(it)
                        AlertStateTracker.onAlertDismissed(it)
                    }
                }
                Notify.cancelAlertNotification()
            }
            
            ACTION_SNOOZE -> {
                Log.i(LOG_ID, "Snooze action received for alert type: $resolvedAlertType")
                
                // Get snooze duration (from intent or default from config)
                val snoozeMinutes = if (intent.hasExtra(EXTRA_SNOOZE_MINUTES)) {
                    intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 15)
                } else if (customAlertId != null) {
                    15
                } else {
                    resolvedAlertType?.let { AlertRepository.loadConfig(it).defaultSnoozeMinutes } ?: 15
                }
                
                if (customAlertId != null) {
                    CustomAlertManager.snoozeAlert(customAlertId, snoozeMinutes)
                    Notify.cancelCurrentRetrySession("notification-snooze-custom-before-stop")
                } else {
                    resolvedAlertType?.let {
                        SnoozeManager.snooze(it, snoozeMinutes)
                        AlertStateTracker.resetState(it)
                        Log.i(LOG_ID, "Snoozed ${it.name} for $snoozeMinutes minutes")
                    }
                    Notify.cancelCurrentRetrySession("notification-snooze-before-stop")
                }
                Notify.stopalarm()
                Notify.cancelCurrentRetrySession("notification-snooze-after-stop")
                Notify.cancelAlertNotification()
            }

            ACTION_IGNORE -> {
                Log.i(LOG_ID, "Ignore action received for alert type: $resolvedAlertType")
                Notify.stopalarm()
                if (customAlertId != null) {
                    CustomAlertManager.ignoreAlert(customAlertId)
                }
                Notify.cancelAlertNotification()
            }
        }
    }
}
