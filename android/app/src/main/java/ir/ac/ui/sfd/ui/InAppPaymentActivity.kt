package ir.ac.ui.sfd.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ir.ac.ui.sfd.databinding.ActivityPaymentBinding

class InAppPaymentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaymentBinding
    private var paymentAmount: Long = 0L
    private var initialUrl: String = ""
    private var isSuccessHandled = false

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_COOKIES = "extra_cookies"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initialUrl = intent.getStringExtra(EXTRA_URL) ?: "https://sfd.ui.ac.ir/ePay/ePay_Payment.aspx"
        paymentAmount = intent.getLongExtra(EXTRA_AMOUNT, 0L)
        val customTitle = intent.getStringExtra(EXTRA_TITLE) ?: "درگاه پرداخت درون‌برنامه‌ای"
        val cookies = intent.getStringArrayListExtra(EXTRA_COOKIES) ?: arrayListOf()

        binding.tvPaymentToolbarTitle.text = customTitle
        binding.btnReturnToApp.setOnClickListener {
            handleReturnToApp()
        }

        // Setup WebView settings and clients first
        setupWebView()

        // Configure CookieManager
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        // Clear any stale/guest sessions from WebView's internal cache
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        for (cookieStr in cookies) {
            val raw = cookieStr.split(";").first().trim()
            if (!raw.contains("=")) continue
            val name = raw.substringBefore("=").trim()
            val value = raw.substringAfter("=").trim()
            if (name.isBlank()) continue

            // 1. Host-only cookies (how IIS/ASP.NET Session State originally expects)
            cookieManager.setCookie("https://sfd.ui.ac.ir", "$name=$value; path=/")
            cookieManager.setCookie("https://sfd.ui.ac.ir/", "$name=$value; path=/")
            cookieManager.setCookie("https://sfd.ui.ac.ir/Reservation/", "$name=$value; path=/Reservation/")
            cookieManager.setCookie("https://sfd.ui.ac.ir/Reservation/", "$name=$value; path=/")
            cookieManager.setCookie("https://sfd.ui.ac.ir/ePay/", "$name=$value; path=/ePay/")
            cookieManager.setCookie("https://sfd.ui.ac.ir/ePay/", "$name=$value; path=/")

            // 2. Explicitly for target initialUrl
            cookieManager.setCookie(initialUrl, "$name=$value; path=/")

            // 3. Domain cookies
            cookieManager.setCookie("https://sfd.ui.ac.ir", "$name=$value; domain=sfd.ui.ac.ir; path=/")
            cookieManager.setCookie("https://sfd.ui.ac.ir", "$name=$value; domain=.ui.ac.ir; path=/")
        }
        cookieManager.flush()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleReturnToApp()
            }
        })

        val extraHeaders = HashMap<String, String>().apply {
            val refererUrl = if (initialUrl.contains("Cafeteria")) {
                "https://sfd.ui.ac.ir/Reservation/CafeteriaReservation.aspx"
            } else if (initialUrl.contains("ePay")) {
                "https://sfd.ui.ac.ir/Reservation/Reservation.aspx"
            } else {
                "https://sfd.ui.ac.ir/Reservation/Reservation.aspx"
            }
            put("Referer", refererUrl)
            put("Origin", "https://sfd.ui.ac.ir")
            put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            put("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
        }
        binding.webView.loadUrl(initialUrl, extraHeaders)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                    binding.loadingLayout.visibility = View.GONE
                } else {
                    binding.progressBar.visibility = View.VISIBLE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                val currentHost = Uri.parse(url ?: "").host ?: ""
                binding.tvPaymentToolbarSubtitle.text = if (currentHost.contains("shaparak.ir")) {
                    "🔒 متصل به درگاه رسمی شاپرک ($currentHost)"
                } else {
                    currentHost
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE

                val currentUrl = url ?: ""

                // 1. If we are on ePay_Payment.aspx, inject the payment amount
                if (currentUrl.contains("ePay_Payment.aspx") && paymentAmount > 0L) {
                    val formatted = String.format("%,d", paymentAmount)
                    val js = """
                        (function() {
                            var amt = document.getElementById("cphMain_txtPaymentAmount");
                            if (amt) {
                                amt.value = "$formatted";
                                amt.style.backgroundColor = "#e8f5e9";
                                amt.style.borderColor = "#2e7d32";
                                amt.style.fontWeight = "bold";
                            }
                            var header = document.querySelector(".header") || document.body;
                            var notice = document.createElement("div");
                            notice.style.cssText = "background:#1b5e20;color:#fff;padding:10px;text-align:center;font-size:12px;border-radius:4px;margin:8px;direction:rtl;";
                            notice.innerHTML = "🔐 <strong>مبلغ $formatted ریال به‌صورت خودکار در کادر درج گردید.</strong> لطفاً درگاه بانک را انتخاب نمایید.";
                            document.body.insertBefore(notice, document.body.firstChild);
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }

                // 2. If on CafeteriaReservation_SelectBank.aspx (initial bank selection)
                if (currentUrl.contains("CafeteriaReservation_SelectBank.aspx") && !currentUrl.contains("Return") && !currentUrl.contains("Callback")) {
                    val formatted = if (paymentAmount > 0L) String.format("%,d", paymentAmount) else ""
                    val js = """
                        (function() {
                            if (!document.getElementById('sfd_prepay_banner')) {
                                var notice = document.createElement("div");
                                notice.id = 'sfd_prepay_banner';
                                notice.style.cssText = "background:#0284c7;color:#fff;padding:12px;text-align:center;font-size:12.5px;border-radius:6px;margin:8px;direction:rtl;box-shadow:0 2px 8px rgba(0,0,0,0.2);";
                                notice.innerHTML = "🍽️ <strong>پیش‌پرداخت غذای رستوران آزاد (یاس)</strong>${if (formatted.isNotEmpty()) "<br>مبلغ: $formatted ریال" else ""}<br><small>لطفاً درگاه بانکی را انتخاب کرده و دکمه پرداخت را لمس نمایید (مهلت ۳ دقیقه).</small>";
                                document.body.insertBefore(notice, document.body.firstChild);
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }

                // 3. Check for Bank Callbacks: ePay_Callback.aspx or CafeteriaReservation_SelectBank.aspx
                if (currentUrl.contains("ePay_Callback.aspx") || currentUrl.contains("CafeteriaReservation_SelectBank.aspx") || currentUrl.contains("Return")) {
                    webView.evaluateJavascript("document.body.innerText") { bodyText ->
                        val cleanText = bodyText ?: ""
                        val isPaid = (cleanText.contains("تراکنش با موفقیت") ||
                                cleanText.contains("عملیات موفق") ||
                                cleanText.contains("کد پیگیری") ||
                                cleanText.contains("شماره تراکنش") ||
                                cleanText.contains("پرداخت با موفقیت") ||
                                cleanText.contains("با موفقیت ثبت شد")) &&
                                !cleanText.contains("ناموفق") &&
                                !cleanText.contains("انصراف")

                        if (isPaid && !isSuccessHandled) {
                            isSuccessHandled = true
                            Toast.makeText(this@InAppPaymentActivity, "🎉 پرداخت با موفقیت در شاپرک تایید شد!", Toast.LENGTH_LONG).show()

                            Handler(Looper.getMainLooper()).postDelayed({
                                val data = Intent().apply {
                                    putExtra("is_success", true)
                                }
                                setResult(Activity.RESULT_OK, data)
                                finish()
                            }, 1800)
                        } else if (cleanText.contains("ناموفق") || cleanText.contains("انصراف") || cleanText.contains("خطا در پرداخت")) {
                            Toast.makeText(this@InAppPaymentActivity, "⚠️ تراکنش لغو شد یا ناموفق بود.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // 4. Check if page unexpectedly redirected to Calinan login
                if ((currentUrl.contains("Default.aspx") || currentUrl.contains("Login.aspx") || currentUrl == "https://sfd.ui.ac.ir/" || currentUrl == "https://sfd.ui.ac.ir") && !isSuccessHandled) {
                    webView.evaluateJavascript("document.body.innerText") { bodyText ->
                        val text = bodyText ?: ""
                        if (text.contains("کد امنیتی") || text.contains("ورود سمت سرور") || text.contains("نام کاربری")) {
                            Toast.makeText(this@InAppPaymentActivity, "⚠️ نشست منقضی شد یا درگاه مستقیم پاسخ نداد. لطفاً مجدداً تلاش کنید.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleUrlScheme(uri)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                return handleUrlScheme(Uri.parse(url))
            }

            private fun handleUrlScheme(uri: Uri): Boolean {
                val scheme = uri.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    return false
                }
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    true
                }
            }
        }
    }

    private fun handleReturnToApp() {
        if (isSuccessHandled) {
            val data = Intent().apply {
                putExtra("is_success", true)
            }
            setResult(Activity.RESULT_OK, data)
            finish()
            return
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("بازگشت به برنامه")
            .setMessage("آیا مایلید از درگاه پرداخت خارج شده و به برنامه بازگردید؟")
            .setPositiveButton("بله، بازگشت به برنامه") { _, _ ->
                finish()
            }
            .setNegativeButton("ادامه پرداخت", null)
            .show()
    }
}
