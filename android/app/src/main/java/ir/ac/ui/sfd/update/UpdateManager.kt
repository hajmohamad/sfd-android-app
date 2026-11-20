package ir.ac.ui.sfd.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import ir.ac.ui.sfd.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val minRequiredVersion: Int = 1,
    val isForceUpdate: Boolean = false,
    val title: String? = null,
    val message: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String = "",
    val directApkUrl: String? = null
)

object UpdateManager {

    // Default remote repository configuration URL for version check
    // Can be overridden in SharedPreferences (sfd_prefs -> update_url)
    const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/hajmohamad/sfd-android-app/main/app_config.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun getUpdateUrl(context: Context): String {
        val sp = context.getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
        val saved = sp.getString("update_url", null)
        if (saved.isNullOrBlank() || saved.contains("mohamadrahi/sfd")) {
            return DEFAULT_UPDATE_URL
        }
        return saved
    }

    fun setUpdateUrl(context: Context, url: String) {
        val sp = context.getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("update_url", url.trim()).apply()
    }

    suspend fun checkRemoteUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val targetUrl = getUpdateUrl(context)
            if (targetUrl.isBlank()) return@withContext null

            // Add timestamp query parameter to bypass GitHub raw CDN caching
            val fullUrl = if (targetUrl.contains("?")) "$targetUrl&t=${System.currentTimeMillis()}" else "$targetUrl?t=${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "SFD-Android-Client/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null

            val json = resp.body?.string() ?: return@withContext null
            val jsonObj = com.google.gson.JsonParser.parseString(json).asJsonObject
            val targetJson = if (jsonObj.has("version")) jsonObj.get("version") else jsonObj
            Gson().fromJson(targetJson, UpdateInfo::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun promptUpdateIfNeeded(activity: Activity, info: UpdateInfo?, isManualCheck: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (info == null) {
            if (isManualCheck) {
                Toast.makeText(activity, "خطا در بررسی بروزرسانی یا ارتباط با سرور", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val currentVersionCode = BuildConfig.VERSION_CODE
        val isNewerAvailable = info.versionCode > currentVersionCode

        if (!isNewerAvailable) {
            if (isManualCheck) {
                Toast.makeText(activity, "شما از آخرین نسخه برنامه (${BuildConfig.VERSION_NAME}) استفاده می‌کنید.", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Determine if this is a mandatory/forced update
        val isForce = info.isForceUpdate || currentVersionCode < info.minRequiredVersion

        val dialogTitle = info.title?.ifBlank { null }
            ?: if (isForce) "⚠️ بروزرسانی اجباری برنامه" else "🚀 بروزرسانی جدید در دسترس است"

        val baseMsg = info.message?.ifBlank { null }
            ?: "نسخه جدیدی از سامانه تغذیه کالینان (نسخه ${info.versionName}) منتشر شده است."

        val notes = if (!info.releaseNotes.isNullOrBlank()) {
            "\n\nتغییرات نسخه جدید:\n${info.releaseNotes}"
        } else ""

        val fullMessage = "$baseMsg$notes"

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(dialogTitle)
            .setMessage(fullMessage)
            .setCancelable(!isForce)

        builder.setPositiveButton("دانلود و بروزرسانی 🚀") { _, _ ->
            try {
                val targetUrl = info.directApkUrl?.ifBlank { null } ?: info.downloadUrl
                if (targetUrl.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                    activity.startActivity(intent)
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "خطا در باز کردن لینک دانلود: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            if (isForce) {
                activity.finishAffinity()
            }
        }

        if (isForce) {
            builder.setNegativeButton("خروج از برنامه") { _, _ ->
                activity.finishAffinity()
            }
        } else {
            builder.setNegativeButton("بعداً", null)
        }

        builder.show()
    }
}
