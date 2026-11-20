package ir.ac.ui.sfd.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import ir.ac.ui.sfd.model.UserPreferences
import java.util.Calendar

object AutoReserveScheduler {

    private const val ALARM_REQUEST_CODE_WEEKLY = 4401
    private const val ALARM_REQUEST_CODE_HOURLY = 4402

    fun scheduleOrUpdate(context: Context, preferences: UserPreferences) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AutoReserveReceiver::class.java).apply {
            action = "ir.ac.ui.sfd.ACTION_AUTO_RESERVE_WEEKLY"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE_WEEKLY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (!preferences.autoReserveScheduled) {
            alarmManager.cancel(pendingIntent)
            cancelHourlyRetry(context)
            return
        }

        val targetTime = calculateNextTargetTime(preferences.autoReserveDay, preferences.autoReserveHour, preferences.autoReserveMinute)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent)
            }
        } catch (e: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun scheduleHourlyRetry(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AutoReserveReceiver::class.java).apply {
            action = "ir.ac.ui.sfd.ACTION_AUTO_RESERVE_HOURLY_RETRY"
            putExtra("is_hourly_retry", true)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE_HOURLY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + (60 * 60 * 1000L) // 1 hour from now

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    fun cancelHourlyRetry(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AutoReserveReceiver::class.java).apply {
            action = "ir.ac.ui.sfd.ACTION_AUTO_RESERVE_HOURLY_RETRY"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE_HOURLY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AutoReserveReceiver::class.java).apply {
            action = "ir.ac.ui.sfd.ACTION_AUTO_RESERVE_WEEKLY"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE_WEEKLY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        cancelHourlyRetry(context)
    }

    private fun calculateNextTargetTime(dayName: String, targetHour: Int, targetMinute: Int): Long {
        val targetDayOfWeek = when (dayName.trim()) {
            "شنبه" -> Calendar.SATURDAY
            "یکشنبه" -> Calendar.SUNDAY
            "دوشنبه" -> Calendar.MONDAY
            "سه‌شنبه" -> Calendar.TUESDAY
            "چهارشنبه" -> Calendar.WEDNESDAY
            "پنج‌شنبه" -> Calendar.THURSDAY
            "جمعه" -> Calendar.FRIDAY
            else -> null // for "هر روز (روزانه)" or fallback
        }

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (targetDayOfWeek != null) {
            while (target.get(Calendar.DAY_OF_WEEK) != targetDayOfWeek || target.timeInMillis <= now.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            // Daily / Everyday mode
            if (target.timeInMillis <= now.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        return target.timeInMillis
    }
}
