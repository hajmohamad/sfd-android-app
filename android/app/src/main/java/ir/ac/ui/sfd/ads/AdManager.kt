package ir.ac.ui.sfd.ads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.gson.Gson
import com.google.gson.JsonParser
import ir.ac.ui.sfd.databinding.DialogAdLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class AdItem(
    val enabled: Boolean = true,
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val targetUrl: String = "",
    val ctaText: String = "مشاهده",
    val displaySeconds: Int = 4
)

data class TapsellConfig(
    val appKey: String = "",
    val loginZoneId: String = "",
    val bottomZoneId: String = ""
)

data class YektanetConfig(
    val scriptUrl: String = "https://cdn.yektanet.com/superscript-v2/d1VRLx1w/native-rasafilm-dl2.xyz-4067/yn_pub.js",
    val domain: String = "https://rasafilm-dl2.xyz/",
    val bottomPosId: String = "ynpos-21129",
    val loginPosId: String = "ynpos-21130"
)

data class AdsConfig(
    val enabled: Boolean = true,
    val provider: String = "custom", // "custom", "tapsell", "yektanet"
    val loginAd: AdItem? = null,
    val bottomBanner: AdItem? = null,
    val tapsell: TapsellConfig? = null,
    val yektanet: YektanetConfig? = null
)

object AdManager {

    const val DEFAULT_ADS_URL = "https://raw.githubusercontent.com/hajmohamad/sfd-android-app/main/app_config.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var cachedConfig: AdsConfig? = null
    private val imageCache = mutableMapOf<String, Bitmap>()

    fun getAdsUrl(context: Context): String {
        val sp = context.getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
        val saved = sp.getString("ads_url", null)
        if (saved.isNullOrBlank() || saved.contains("mohamadrahi/sfd")) {
            return DEFAULT_ADS_URL
        }
        return saved
    }

    fun setAdsUrl(context: Context, url: String) {
        val sp = context.getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("ads_url", url.trim()).apply()
    }

    val DEFAULT_FALLBACK_CONFIG = AdsConfig(
        enabled = true,
        provider = "custom",
        loginAd = AdItem(
            enabled = true,
            title = "کانال اطلاع‌رسانی سلف دانشگاه اصفهان",
            subtitle = "عضویت در کانال دانشجویی",
            description = "عضویت در کانال جهت دریافت منوی روزانه، اخبار رزرو غذاها و پیگیری امور رفاهی",
            imageUrl = "",
            targetUrl = "https://t.me/ui_sfd",
            ctaText = "عضویت در کانال تلگرام 🚀",
            displaySeconds = 3
        ),
        bottomBanner = AdItem(
            enabled = true,
            title = "سامانه اتوماسیون تغذیه دانشگاه اصفهان",
            subtitle = "ساعت رزرو ناهار: ۰۸:۵۵ الی ۱۵:۵۵ | شام: ۱۶:۰۰ الی ۲۱:۳۰",
            description = "پرتال تغذیه و رزرو غذای دانشجویی",
            imageUrl = "",
            targetUrl = "https://sfd.ui.ac.ir",
            ctaText = "مشاهده پورتال"
        )
    )

    suspend fun fetchAdsConfig(context: Context): AdsConfig = withContext(Dispatchers.IO) {
        try {
            val url = getAdsUrl(context)
            if (url.isNotBlank()) {
                val fullUrl = if (url.contains("?")) "$url&t=${System.currentTimeMillis()}" else "$url?t=${System.currentTimeMillis()}"
                val req = Request.Builder().url(fullUrl).header("Cache-Control", "no-cache").build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val json = resp.body?.string()
                    if (!json.isNullOrBlank()) {
                        val jsonObj = JsonParser.parseString(json).asJsonObject
                        val cfg = if (jsonObj.has("ads")) {
                            Gson().fromJson(jsonObj.get("ads"), AdsConfig::class.java)
                        } else {
                            Gson().fromJson(json, AdsConfig::class.java)
                        }
                        if (cfg != null) {
                            cachedConfig = cfg
                            return@withContext cfg
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        cachedConfig ?: DEFAULT_FALLBACK_CONFIG
    }

    suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        if (imageCache.containsKey(url)) return@withContext imageCache[url]

        try {
            val req = Request.Builder().url(url).build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val bytes = resp.body?.bytes() ?: return@withContext null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                imageCache[url] = bmp
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    fun openUrlSafely(context: Context, targetUrl: String) {
        if (targetUrl.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun buildYektanetHtml(posId: String, scriptUrl: String): String {
        return """
            <!DOCTYPE html>
            <html lang="fa" dir="rtl">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    html, body {
                        background-color: transparent !important;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        overflow: hidden;
                        width: 100%;
                        height: 100%;
                    }
                    .yn-bnr {
                        width: 100% !important;
                        text-align: center;
                        margin: 0 auto;
                    }
                </style>
            </head>
            <body>
                <div class="yn-bnr" id="$posId"></div>
                <script>
                !function(e,t,n){
                    e.yektanetAnalyticsObject=n,
                    e[n]=e[n]||function(){e[n].q.push(arguments)},
                    e[n].q=e[n].q||[];
                    var a=t.getElementsByTagName("head")[0],
                        r=new Date,
                        c="$scriptUrl?v="
                            +r.getFullYear().toString()+"0"+r.getMonth()+"0"+r.getDate()+"0"+r.getHours(),
                        s=t.createElement("link");
                    s.rel="preload",
                    s.as="script",
                    s.href=c,
                    a.appendChild(s);
                    var l=t.createElement("script");
                    l.async=!0,
                    l.src=c,
                    a.appendChild(l);
                }(window,document,"yektanet");
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setupYektanetWebView(webView: WebView, context: Context, posId: String, scriptUrl: String, domain: String) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.setBackgroundColor(0) // Transparent
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                openUrlSafely(context, url)
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                openUrlSafely(context, url)
                return true
            }
        }
        val html = buildYektanetHtml(posId, scriptUrl)
        val base = if (domain.isNotBlank()) domain else "https://rasafilm-dl2.xyz/"
        webView.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
    }

    /**
     * نمایش تبلیغ ورودی (Interstitial / Splash modal during login)
     */
    fun showLoginAdModal(activity: Activity, config: AdsConfig?, onDismissed: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed || config == null || !config.enabled) {
            onDismissed()
            return
        }

        val isYektanet = config.provider.equals("yektanet", ignoreCase = true)
        val customAd = config.loginAd
        if (!isYektanet && (customAd == null || !customAd.enabled)) {
            onDismissed()
            return
        }

        val binding = DialogAdLoginBinding.inflate(activity.layoutInflater)
        val dialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (isYektanet) {
            binding.layoutCustomAd.visibility = View.GONE
            binding.layoutYektanetAd.visibility = View.VISIBLE
            val yk = config.yektanet ?: YektanetConfig()
            val posId = yk.loginPosId.ifBlank { "ynpos-21130" }
            setupYektanetWebView(binding.wvAdLoginYektanet, activity, posId, yk.scriptUrl, yk.domain)
        } else if (customAd != null) {
            binding.layoutYektanetAd.visibility = View.GONE
            binding.layoutCustomAd.visibility = View.VISIBLE

            binding.tvAdTitle.text = customAd.title.ifBlank { "پیام حامی مالی برنامه" }
            binding.tvAdSubtitle.text = customAd.description.ifBlank { customAd.subtitle }

            if (customAd.imageUrl.isNotBlank()) {
                val bmp = imageCache[customAd.imageUrl]
                if (bmp != null) {
                    binding.ivAdImage.setImageBitmap(bmp)
                    binding.ivAdImage.visibility = View.VISIBLE
                } else {
                    binding.ivAdImage.visibility = View.GONE
                }
            } else {
                binding.ivAdImage.visibility = View.GONE
            }

            binding.btnAdAction.text = customAd.ctaText.ifBlank { "مشاهده و اطلاعات بیشتر 🔗" }
            binding.btnAdAction.setOnClickListener {
                openUrlSafely(activity, customAd.targetUrl)
                dialog.dismiss()
                onDismissed()
            }
        }

        var remainingSeconds = (customAd?.displaySeconds ?: 4).coerceIn(2, 10)
        binding.btnSkipAd.text = "رد آگهی ($remainingSeconds)"
        binding.btnSkipAd.isEnabled = false

        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                remainingSeconds--
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return

                if (remainingSeconds <= 0) {
                    binding.btnSkipAd.text = "رد آگهی ✕"
                    binding.btnSkipAd.isEnabled = true
                } else {
                    binding.btnSkipAd.text = "رد آگهی ($remainingSeconds)"
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(runnable, 1000)

        binding.btnSkipAd.setOnClickListener {
            dialog.dismiss()
            onDismissed()
        }

        dialog.show()
    }

    /**
     * اتصال بنر پایینی در MainActivity (پشتیبانی از تبلیغ گیت‌هاب و اسکریپت یکتانت)
     */
    fun bindBottomBanner(
        activity: Activity,
        container: View,
        customLayout: View,
        tvTitle: TextView,
        tvSub: TextView,
        ivBanner: ImageView,
        btnCloseCustom: View,
        btnActionCustom: View,
        yektanetLayout: View,
        wvYektanet: WebView,
        btnCloseYektanet: View,
        config: AdsConfig?
    ) {
        if (config == null || !config.enabled) {
            container.visibility = View.GONE
            return
        }

        val isYektanet = config.provider.equals("yektanet", ignoreCase = true)

        if (isYektanet) {
            customLayout.visibility = View.GONE
            yektanetLayout.visibility = View.VISIBLE
            container.visibility = View.VISIBLE

            val yk = config.yektanet ?: YektanetConfig()
            val posId = yk.bottomPosId.ifBlank { "ynpos-21129" }
            setupYektanetWebView(wvYektanet, activity, posId, yk.scriptUrl, yk.domain)

            btnCloseYektanet.setOnClickListener {
                container.visibility = View.GONE
            }
        } else {
            val banner = config.bottomBanner
            if (banner == null || !banner.enabled) {
                container.visibility = View.GONE
                return
            }

            yektanetLayout.visibility = View.GONE
            customLayout.visibility = View.VISIBLE
            container.visibility = View.VISIBLE

            tvTitle.text = banner.title.ifBlank { "تبلیغات" }
            tvSub.text = banner.subtitle.ifBlank { banner.description }

            if (banner.imageUrl.isNotBlank() && imageCache.containsKey(banner.imageUrl)) {
                ivBanner.setImageBitmap(imageCache[banner.imageUrl])
                ivBanner.visibility = View.VISIBLE
            } else {
                ivBanner.visibility = View.GONE
            }

            btnCloseCustom.setOnClickListener {
                container.visibility = View.GONE
            }

            val clickListener = View.OnClickListener {
                openUrlSafely(activity, banner.targetUrl)
            }
            container.setOnClickListener(clickListener)
            btnActionCustom.setOnClickListener(clickListener)
        }
    }
}
