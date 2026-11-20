package ir.ac.ui.sfd.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import ir.ac.ui.sfd.R
import ir.ac.ui.sfd.engine.HtmlParser
import ir.ac.ui.sfd.engine.PreferenceEngine
import ir.ac.ui.sfd.model.FoodOption
import ir.ac.ui.sfd.model.UserPreferences
import ir.ac.ui.sfd.network.SFDClient
import ir.ac.ui.sfd.ocr.CaptchaOCR
import ir.ac.ui.sfd.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AutoReserveReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "sfd_auto_reserve_channel"
        const val NOTIFICATION_ID_DEFICIT = 1001
        const val NOTIFICATION_ID_SUCCESS = 1002
        const val NOTIFICATION_ID_TEST = 1003

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "رزرو خودکار هفتگی"
                val descriptionText = "اعلان وضعیت رزرو خودکار و هشدارهای کسری موجودی حساب"
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                    enableVibration(true)
                }
                val notificationManager: NotificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        fun sendTestNotification(context: Context) {
            createNotificationChannel(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val message = "این یک پیام آزمایشی جهت تایید فعال بودن سیستم اعلان سامانه کالینان در گوشی شماست."

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_wallet)
                .setContentTitle("سامانه کالینان: اعلان آزمایشی فعال شد")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID_TEST, builder.build())
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                performScheduledAutoReserve(context)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                val spPrefs = context.getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
                val isDeficitActive = spPrefs.getBoolean("is_deficit_retry_active", false)
                val prefJson = spPrefs.getString("user_prefs", null)
                if (!prefJson.isNullOrBlank()) {
                    try {
                        val prefs = Gson().fromJson(prefJson, UserPreferences::class.java)
                        // If not in hourly deficit retry, re-arm regular weekly alarm
                        if (!isDeficitActive && prefs.autoReserveScheduled) {
                            AutoReserveScheduler.scheduleOrUpdate(context, prefs)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                pendingResult.finish()
            }
        }
    }

    private suspend fun performScheduledAutoReserve(context: Context) {
        val spAuth = context.getSharedPreferences("sfd_auth", Context.MODE_PRIVATE)
        val user = spAuth.getString("username", null)
        val pass = spAuth.getString("password", null)

        if (user.isNullOrBlank() || pass.isNullOrBlank()) return

        val spPrefs = context.getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
        val prefJson = spPrefs.getString("user_prefs", null)
        val preferences = if (!prefJson.isNullOrBlank()) {
            try {
                Gson().fromJson(prefJson, UserPreferences::class.java)
            } catch (e: Exception) {
                UserPreferences()
            }
        } else UserPreferences()

        if (!preferences.autoReserveScheduled) return

        val sfdClient = SFDClient(context)
        val captchaOcr = CaptchaOCR(context)

        // Login if needed
        if (!sfdClient.hasValidSession()) {
            var loggedIn = false
            for (attempt in 1..3) {
                try {
                    sfdClient.initLoginSession()
                    val bytes = sfdClient.downloadCaptcha()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val solution = captchaOcr.solve(bmp)
                    if (solution != null && solution.code.length == 4) {
                        val res = sfdClient.login(user, pass, solution.code)
                        if (res.isSuccess) {
                            loggedIn = true
                            break
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (!loggedIn) {
                // If login failed, retry in 1 hour if scheduled
                AutoReserveScheduler.scheduleHourlyRetry(context)
                return
            }
        }

        val html = sfdClient.getReservationPage()
        if (sfdClient.isSessionExpired(html)) {
            AutoReserveScheduler.scheduleHourlyRetry(context)
            return
        }

        // Always navigate to next week for auto-reserve
        val (changed, nextWeekHtml) = sfdClient.navigateReservationWeek(html, "next")
        if (!changed || nextWeekHtml.isBlank()) {
            // Next week is not published or not yet opened by university dining department
            // Schedule an hourly retry so it catches when the university opens next week's schedule
            AutoReserveScheduler.scheduleHourlyRetry(context)
            return
        }
        val targetHtml = nextWeekHtml

        val studentInfo = HtmlParser.parseStudentInfo(targetHtml)
        val currentCredit = studentInfo.selfCreditAmount

        val targetMeals = listOf("ناهار", "شام")

        // قانون رزرو هوشمند: اگر کاربر قبلاً در این هفته غذایی رزرو کرده باشد،
        // سیستم نباید خودکار برای او غذا رزرو کند تا برنامه غذایی انتخابی کاربر به هم نخورد.
        val hasAnyReservedMealInWeek = targetMeals.any { meal ->
            HtmlParser.parseSelfGridItems(targetHtml, meal).any { it.isReserved }
        }
        if (hasAnyReservedMealInWeek) {
            spPrefs.edit().putBoolean("is_deficit_retry_active", false).apply()
            AutoReserveScheduler.cancelHourlyRetry(context)
            AutoReserveScheduler.scheduleOrUpdate(context, preferences)
            return
        }

        var totalCost = 0L
        val planned = mutableListOf<Triple<String, ir.ac.ui.sfd.model.DayItem, FoodOption>>()

        for (meal in targetMeals) {
            val days = HtmlParser.parseSelfGridItems(targetHtml, meal)
            for (day in days) {
                if (day.isDisabled || day.isReserved || day.foods.isEmpty()) continue
                val (matched, unranked, _) = PreferenceEngine.rankFoodOptions(day.foods, meal, preferences)
                val chosenFood = when {
                    matched.isNotEmpty() -> matched[0]
                    !preferences.skipIfNoPreferenceMatch && unranked.isNotEmpty() -> unranked[0]
                    else -> null
                }
                if (chosenFood != null) {
                    planned.add(Triple(meal, day, chosenFood))
                    totalCost += chosenFood.priceInt
                }
            }
        }

        if (planned.isEmpty()) {
            // All planned meals for next week are already reserved or no matching options!
            // Clear deficit retry state, cancel hourly retry, and re-arm next week's alarm
            spPrefs.edit().putBoolean("is_deficit_retry_active", false).apply()
            AutoReserveScheduler.cancelHourlyRetry(context)
            AutoReserveScheduler.scheduleOrUpdate(context, preferences)
            return
        }

        // Check if balance is sufficient
        if (currentCredit < totalCost) {
            val deficit = totalCost - currentCredit
            if (preferences.notifyOnDeficit) {
                sendDeficitNotification(context, deficit, planned.size)
            }
            // Insufficient balance! Activate hourly retry to check and reserve every hour
            spPrefs.edit().putBoolean("is_deficit_retry_active", true).apply()
            AutoReserveScheduler.scheduleHourlyRetry(context)
            return
        }

        // Execute reservations
        var successCount = 0
        var runningHtml = targetHtml
        for ((meal, day, food) in planned) {
            val (ok, _, newHtml) = sfdClient.reserveSelfService(runningHtml, meal, day, food, day.selectedSelfId)
            if (ok) {
                successCount++
                if (newHtml.isNotBlank()) {
                    runningHtml = newHtml
                }
            }
        }

        if (successCount > 0) {
            sendSuccessNotification(context, successCount, totalCost)
            // Complete! Clear retry, cancel hourly alarm, and re-arm weekly alarm for next week
            spPrefs.edit().putBoolean("is_deficit_retry_active", false).apply()
            AutoReserveScheduler.cancelHourlyRetry(context)
            AutoReserveScheduler.scheduleOrUpdate(context, preferences)
        } else {
            // Transient error during reservation, retry next hour
            spPrefs.edit().putBoolean("is_deficit_retry_active", true).apply()
            AutoReserveScheduler.scheduleHourlyRetry(context)
        }
    }


    private fun sendDeficitNotification(context: Context, deficitRials: Long, mealCount: Int) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deficitTomans = deficitRials / 10
        val message = "دانشجوی گرامی، موجودی حساب شما برای رزرو خودکار $mealCount وعده هفته آینده کافی نیست (کسری: ${String.format("%,d", deficitTomans)} تومان). سامانه هر ۱ ساعت مجدداً موجودی را بررسی و در صورت شارژ، رزرو را تکمیل خواهد کرد."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle("سامانه کالینان: هشدار کسری موجودی حساب")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_DEFICIT, builder.build())
    }

    private fun sendSuccessNotification(context: Context, successCount: Int, totalCost: Long) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalTomans = totalCost / 10
        val message = "دانشجوی گرامی، تعداد $successCount وعده غذایی هفته آینده بر اساس اولویت‌های انتخابی شما با موفقیت رزرو گردید (جمع هزینه: ${String.format("%,d", totalTomans)} تومان). رزرو خودکار تکمیل شد."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check_circle_fill)
            .setContentTitle("سامانه کالینان: تایید رزرو خودکار هفته")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_SUCCESS, builder.build())
    }
}
