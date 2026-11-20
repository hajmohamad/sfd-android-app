package ir.ac.ui.sfd.worker
 
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import ir.ac.ui.sfd.model.UserPreferences

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val spPrefs = context.getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
            val prefJson = spPrefs.getString("user_prefs", null)
            if (!prefJson.isNullOrBlank()) {
                try {
                    val prefs = Gson().fromJson(prefJson, UserPreferences::class.java)
                    if (prefs.autoReserveScheduled) {
                        val isDeficitActive = spPrefs.getBoolean("is_deficit_retry_active", false)
                        if (isDeficitActive) {
                            AutoReserveScheduler.scheduleHourlyRetry(context)
                        } else {
                            AutoReserveScheduler.scheduleOrUpdate(context, prefs)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
