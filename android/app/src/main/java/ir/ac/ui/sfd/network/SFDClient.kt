package ir.ac.ui.sfd.network

import ir.ac.ui.sfd.engine.HtmlParser
import ir.ac.ui.sfd.model.CafeteriaItem
import ir.ac.ui.sfd.model.DayItem
import ir.ac.ui.sfd.model.FoodOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.jsoup.Jsoup
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SFDClient(context: android.content.Context? = null, val baseUrl: String = "https://sfd.ui.ac.ir") {

    val cookieJar = PersistentCookieJar(context)
    private val client: OkHttpClient

    var viewState: String = ""
    var viewStateGen: String = ""

    fun hasValidSession(): Boolean = cookieJar.hasValidSession()

    fun isSessionExpired(html: String): Boolean {
        return html.contains("txtUsername") || html.contains("btnLogin") || html.contains("ورود سمت سرور")
    }

    init {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .cookieJar(cookieJar)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private suspend fun request(
        path: String,
        data: Map<String, String>? = null,
        referer: String? = null
    ): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else "$baseUrl$path"
        val reqBuilder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")

        if (referer != null) {
            reqBuilder.header("Referer", referer)
        }

        if (data != null) {
            val formBuilder = FormBody.Builder()
            for ((k, v) in data) {
                formBuilder.add(k, v)
            }
            reqBuilder.post(formBuilder.build())
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code !in 300..399) {
                throw Exception("HTTP Error ${response.code}: ${response.message}")
            }
            response.body?.string() ?: ""
        }
    }

    suspend fun initLoginSession(): Boolean = withContext(Dispatchers.IO) {
        val html = request("/")
        val vsMatch = Pattern.compile("id=\"__VIEWSTATE\"\\s+value=\"([^\"]+)\"").matcher(html)
        val vsgMatch = Pattern.compile("id=\"__VIEWSTATEGENERATOR\"\\s+value=\"([^\"]+)\"").matcher(html)

        if (vsMatch.find()) viewState = vsMatch.group(1) ?: ""
        if (vsgMatch.find()) viewStateGen = vsgMatch.group(1) ?: ""

        viewState.isNotBlank()
    }

    suspend fun downloadCaptcha(): ByteArray = withContext(Dispatchers.IO) {
        val captchaUrl = "$baseUrl/UserControls/Captcha.ashx?rnd=${System.currentTimeMillis()}"
        val req = Request.Builder()
            .url(captchaUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
            .build()
        client.newCall(req).execute().use { response ->
            response.body?.bytes() ?: throw Exception("Failed to download captcha image")
        }
    }

    suspend fun login(username: String, pass: String, captcha: String): kotlin.Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (viewState.isBlank()) {
                initLoginSession()
            }

            val postData = mapOf(
                "__VIEWSTATE" to viewState,
                "__VIEWSTATEGENERATOR" to viewStateGen,
                "__VIEWSTATEENCRYPTED" to "",
                "txtUsernamePlain" to username,
                "txtPasswordPlain" to pass,
                "txtUsername" to username,
                "txtPassword" to pass,
                "txtCaptcha" to captcha,
                "btnLogin" to "ورود سمت سرور "
            )

            val respHtml = request("/", postData, referer = "$baseUrl/")

            // 1. Check for immediate success indicators in response
            val isDirectSuccess = (respHtml.contains("Reservation.aspx") ||
                respHtml.contains("خرید هفتگی غذای سلف") ||
                respHtml.contains("cphMain_lblWeekDate") ||
                respHtml.contains("cphMain") ||
                respHtml.contains("خروج") ||
                respHtml.contains("Logout")
            ) && !respHtml.contains("txtPasswordPlain") && !respHtml.contains("btnLogin")

            if (isDirectSuccess) {
                return@withContext kotlin.Result.success(true)
            }

            // 2. Fallback check: Did ASP.NET establish a session?
            if (hasValidSession() && !respHtml.contains("lblLoginError")) {
                try {
                    val resPage = getReservationPage()
                    if (resPage.contains("cphMain") || resPage.contains("خرید هفتگی غذای سلف") || resPage.contains("Reservation.aspx")) {
                        return@withContext kotlin.Result.success(true)
                    }
                } catch (e: Exception) {
                    // Ignore, fallback to error extraction
                }
            }

            // 3. Extract server error
            val loginError = HtmlParser.extractLoginError(respHtml)
            if (loginError != null) {
                return@withContext kotlin.Result.failure(Exception(loginError))
            }

            kotlin.Result.failure(Exception("احراز هویت انجام نشد. لطفاً نام کاربری، رمز عبور و کد امنیتی را بررسی نمایید."))
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }

    suspend fun getReservationPage(): String = withContext(Dispatchers.IO) {
        request("/Reservation/Reservation.aspx", referer = "$baseUrl/")
    }

    suspend fun getCafeteriaPage(): String = withContext(Dispatchers.IO) {
        request("/Reservation/CafeteriaReservation.aspx", referer = "$baseUrl/Reservation/Reservation.aspx")
    }

    suspend fun switchCafeteriaRestaurant(currentHtml: String, restaurantId: String, mealName: String = "ناهار"): String = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(currentHtml)
        val formData = mutableMapOf<String, String>()

        for (input in doc.select("input")) {
            val name = input.attr("name")
            val value = input.attr("value")
            if (name.isNotBlank()) formData[name] = value
        }
        for (select in doc.select("select")) {
            val name = select.attr("name")
            val selOpt = select.select("option[selected]").first() ?: select.select("option").first()
            if (name.isNotBlank() && selOpt != null) {
                formData[name] = selOpt.attr("value")
            }
        }

        formData.keys.removeAll { key ->
            listOf("btnSave", "imgbtn", "btnOk", "btnLogin", "btnPay").any { key.contains(it) }
        }

        val targetSuffix = when (mealName) {
            "شام" -> "Dinner"
            "صبحانه" -> "Breakfast"
            else -> "Lunch"
        }

        val restSelect = doc.select("select[name*=drpSelfTitle_$targetSuffix]").first()
            ?: doc.select("select[name*=drpSelfTitle]").first()
        val restSelectName = restSelect?.attr("name") ?: "ctl00\$cphMain\$drpSelfTitle_$targetSuffix"

        formData[restSelectName] = restaurantId
        formData["ctl00\$cphMain\$drpSelfTitle_Lunch"] = restaurantId
        formData["ctl00\$cphMain\$drpSelfTitle_Dinner"] = restaurantId
        formData["ctl00\$cphMain\$drpSelfTitle_Breakfast"] = restaurantId

        formData["__EVENTTARGET"] = restSelectName
        formData["__EVENTARGUMENT"] = ""

        request("/Reservation/CafeteriaReservation.aspx", formData, referer = "$baseUrl/Reservation/CafeteriaReservation.aspx")
    }

    suspend fun navigateCafeteriaDay(currentHtml: String, direction: String = "next"): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(currentHtml)
        val formData = mutableMapOf<String, String>()

        for (input in doc.select("input")) {
            val name = input.attr("name")
            val value = input.attr("value")
            if (name.isNotBlank()) formData[name] = value
        }
        for (select in doc.select("select")) {
            val name = select.attr("name")
            val selOpt = select.select("option[selected]").first() ?: select.select("option").first()
            if (name.isNotBlank() && selOpt != null) {
                formData[name] = selOpt.attr("value")
            }
        }

        formData.keys.removeAll { key ->
            listOf("btnSave", "imgbtn", "btnOk", "btnLogin", "btnPay").any { key.contains(it) }
        }

        val searchTerms = if (direction == "next") {
            listOf("NextDay", "Next", "next")
        } else {
            listOf("LastDay", "Last", "Prev", "Previous", "last")
        }

        var targetBtnName: String? = null
        for (imgInput in doc.select("input[type=image]")) {
            val name = imgInput.attr("name")
            if (searchTerms.any { term -> name.contains(term, ignoreCase = true) }) {
                targetBtnName = name
                break
            }
        }

        if (targetBtnName == null) {
            targetBtnName = if (direction == "next") "ctl00\$cphMain\$imgbtnNextDay" else "ctl00\$cphMain\$imgbtnLastDay"
        }

        formData["$targetBtnName.x"] = "18"
        formData["$targetBtnName.y"] = "18"
        formData["__EVENTTARGET"] = ""
        formData["__EVENTARGUMENT"] = ""

        val newHtml = request("/Reservation/CafeteriaReservation.aspx", formData, referer = "$baseUrl/Reservation/CafeteriaReservation.aspx")

        val oldDate = HtmlParser.extractCafeteriaDate(currentHtml)
        val newDate = HtmlParser.extractCafeteriaDate(newHtml)

        val changed = newDate.isNotBlank() && newDate != oldDate
        Pair(changed, newHtml)
    }

    suspend fun navigateReservationWeek(currentHtml: String, direction: String = "next"): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(currentHtml)
        val formData = mutableMapOf<String, String>()

        for (input in doc.select("input")) {
            val name = input.attr("name")
            val value = input.attr("value")
            if (name.isNotBlank()) formData[name] = value
        }
        for (select in doc.select("select")) {
            val name = select.attr("name")
            val selOpt = select.select("option[selected]").first() ?: select.select("option").first()
            if (name.isNotBlank() && selOpt != null) {
                formData[name] = selOpt.attr("value")
            }
        }

        formData.keys.removeAll { key ->
            listOf("btnSave", "imgbtn", "btnOk", "btnLogin").any { key.contains(it) }
        }

        val btnName = if (direction == "next") "ctl00\$cphMain\$imgbtnNextWeek" else "ctl00\$cphMain\$imgbtnLastWeek"
        formData["$btnName.x"] = "18"
        formData["$btnName.y"] = "18"
        formData["__EVENTTARGET"] = ""
        formData["__EVENTARGUMENT"] = ""

        val newHtml = request("/Reservation/Reservation.aspx", formData, referer = "$baseUrl/Reservation/Reservation.aspx")

        val oldW = Pattern.compile("id=\"[^\"]*lblWeekDate[^\"]*\"[^>]*>([^<]+)<").matcher(currentHtml)
        val newW = Pattern.compile("id=\"[^\"]*lblWeekDate[^\"]*\"[^>]*>([^<]+)<").matcher(newHtml)
        val oldStr = if (oldW.find()) oldW.group(1)?.trim() ?: "" else ""
        val newStr = if (newW.find()) newW.group(1)?.trim() ?: "" else ""

        val changed = newStr.isNotBlank() && newStr != oldStr
        Pair(changed, newHtml)
    }

    suspend fun reserveSelfService(
        currentHtml: String,
        mealName: String,
        dayItem: DayItem,
        foodOption: FoodOption,
        selfId: String? = null
    ): Triple<Boolean, String, String> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(currentHtml)
        val formData = mutableMapOf<String, String>()

        for (input in doc.select("input")) {
            val type = input.attr("type").lowercase()
            val name = input.attr("name")
            if (name.isBlank()) continue

            if (type == "checkbox") {
                if (input.hasAttr("checked")) {
                    formData[name] = "on"
                }
            } else {
                formData[name] = input.attr("value")
            }
        }
        for (select in doc.select("select")) {
            val name = select.attr("name")
            val selOpt = select.select("option[selected]").first() ?: select.select("option").first()
            if (name.isNotBlank() && selOpt != null) {
                formData[name] = selOpt.attr("value")
            }
        }

        formData.keys.removeAll { key ->
            listOf("btnSave", "imgbtn", "btnOk", "btnLogin").any { key.contains(it) }
        }

        if (dayItem.chkName.isNotBlank()) formData[dayItem.chkName] = "on"
        if (dayItem.foodCtlName.isNotBlank()) formData[dayItem.foodCtlName] = foodOption.id
        if (dayItem.countCtlName.isNotBlank()) formData[dayItem.countCtlName] = "1"
        val targetSelfId = selfId ?: dayItem.selectedSelfId
        if (dayItem.selfCtlName.isNotBlank() && !targetSelfId.isNullOrBlank() && targetSelfId != "-1") {
            formData[dayItem.selfCtlName] = targetSelfId
        }

        val (btnKey, btnVal) = when (mealName) {
            "صبحانه" -> "ctl00\$cphMain\$btnSaveBreakfast" to "ذخیره صبحانه"
            "شام" -> "ctl00\$cphMain\$btnSaveDinner" to "ذخیره شام"
            else -> "ctl00\$cphMain\$btnSaveLunch" to "ذخیره ناهار"
        }
        formData[btnKey] = btnVal
        formData["__EVENTTARGET"] = ""
        formData["__EVENTARGUMENT"] = ""

        val respHtml = request("/Reservation/Reservation.aspx", formData, referer = "$baseUrl/Reservation/Reservation.aspx")

        val toastErr = HtmlParser.extractToastError(respHtml)
        if (toastErr != null) {
            return@withContext Triple(false, toastErr, respHtml)
        }

        if (respHtml.contains("اعتبار شما کافی نمی باشد") || respHtml.contains("کسری موجودی") || respHtml.contains("عدم موجودی")) {
            return@withContext Triple(false, "اعتبار سلف شما برای رزرو این غذا کافی نیست.", respHtml)
        }

        Triple(true, "رزرو با موفقیت ثبت شد.", respHtml)
    }

    suspend fun cancelSelfService(
        currentHtml: String,
        mealName: String,
        dayItem: DayItem
    ): Triple<Boolean, String, String> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(currentHtml)
        val formData = mutableMapOf<String, String>()

        for (input in doc.select("input")) {
            val type = input.attr("type").lowercase()
            val name = input.attr("name")
            if (name.isBlank()) continue

            if (type == "checkbox") {
                if (input.hasAttr("checked") && name != dayItem.chkName) {
                    formData[name] = "on"
                }
            } else {
                formData[name] = input.attr("value")
            }
        }
        for (select in doc.select("select")) {
            val name = select.attr("name")
            val selOpt = select.select("option[selected]").first() ?: select.select("option").first()
            if (name.isNotBlank() && selOpt != null) {
                formData[name] = selOpt.attr("value")
            }
        }

        formData.keys.removeAll { key ->
            listOf("btnSave", "imgbtn", "btnOk", "btnLogin").any { key.contains(it) }
        }

        // Explicitly ensure target day's checkbox is removed (unchecked)
        if (dayItem.chkName.isNotBlank()) {
            formData.remove(dayItem.chkName)
        }

        val (btnKey, btnVal) = when (mealName) {
            "صبحانه" -> "ctl00\$cphMain\$btnSaveBreakfast" to "ذخیره صبحانه"
            "شام" -> "ctl00\$cphMain\$btnSaveDinner" to "ذخیره شام"
            else -> "ctl00\$cphMain\$btnSaveLunch" to "ذخیره ناهار"
        }
        formData[btnKey] = btnVal
        formData["__EVENTTARGET"] = ""
        formData["__EVENTARGUMENT"] = ""

        val respHtml = request("/Reservation/Reservation.aspx", formData, referer = "$baseUrl/Reservation/Reservation.aspx")

        val toastErr = HtmlParser.extractToastError(respHtml)
        if (toastErr != null) {
            return@withContext Triple(false, toastErr, respHtml)
        }

        Triple(true, "رزرو این وعده با موفقیت لغو شد و مبلغ به اعتبار سلف بازگشت.", respHtml)
    }

    suspend fun reserveCafeteriaFood(
        currentHtml: String,
        mealName: String,
        item: CafeteriaItem,
        restaurantId: String? = null
    ): Triple<Boolean, String, String?> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(currentHtml)
        val formData = mutableMapOf<String, String>()

        for (input in doc.select("input")) {
            val name = input.attr("name")
            val value = input.attr("value")
            val type = input.attr("type").lowercase()
            if (name.isNotBlank()) {
                if (type == "checkbox" || type == "radio") {
                    if (input.hasAttr("checked")) {
                        formData[name] = if (value.isNotBlank()) value else "on"
                    }
                } else {
                    formData[name] = value
                }
            }
        }
        for (select in doc.select("select")) {
            val name = select.attr("name")
            val selOpt = select.select("option[selected]").first() ?: select.select("option").first()
            if (name.isNotBlank() && selOpt != null) {
                formData[name] = selOpt.attr("value")
            }
        }

        val oldCredit = doc.select("input[name*=hdnCreditId], input[id*=hdnCreditId]").firstOrNull()?.attr("value")?.trim() ?: "0"

        // Ensure all hdnCreditId fields are reset to 0 for a new order
        doc.select("input[name*=hdnCreditId], input[id*=hdnCreditId]").forEach { el ->
            val name = el.attr("name")
            if (name.isNotBlank()) formData[name] = "0"
        }
        formData["ctl00\$cphMain\$hdnCreditId"] = "0"

        formData.keys.removeAll { key ->
            listOf("btnSave", "imgbtn", "btnOk", "btnLogin", "btnPay").any { key.contains(it) } ||
            (key.contains("chk", ignoreCase = true) && key != item.chkName)
        }

        // Reset all other meal counts to 0 to prevent violating the 1-meal per slot rule
        for (k in formData.keys.toList()) {
            if ((k.contains("Count", ignoreCase = true) || k.contains("drpNormalCount", ignoreCase = true)) && k != item.countName) {
                formData[k] = "0"
            }
        }

        if (item.chkName.isNotBlank()) formData[item.chkName] = "on"
        if (item.countName.isNotBlank()) formData[item.countName] = "1"

        if (restaurantId != null) {
            formData["ctl00\$cphMain\$drpSelfTitle_Lunch"] = restaurantId
            formData["ctl00\$cphMain\$drpSelfTitle_Dinner"] = restaurantId
            formData["ctl00\$cphMain\$drpSelfTitle_Breakfast"] = restaurantId
        }

        val (btnKey, defaultBtnVal) = when (mealName) {
            "صبحانه" -> "ctl00\$cphMain\$btnSaveBreakfast" to "ذخیره "
            "شام" -> "ctl00\$cphMain\$btnSaveDinner" to "ذخیره "
            else -> "ctl00\$cphMain\$btnSaveLunch" to "ذخیره ناهار"
        }
        val btnEl = doc.select("input[name=$btnKey]").firstOrNull()
        val btnVal = btnEl?.attr("value")?.takeIf { it.isNotBlank() } ?: defaultBtnVal
        formData[btnKey] = btnVal
        formData["__EVENTTARGET"] = ""
        formData["__EVENTARGUMENT"] = ""

        val respHtml = request("/Reservation/CafeteriaReservation.aspx", formData, referer = "$baseUrl/Reservation/CafeteriaReservation.aspx")

        val toastErr = HtmlParser.extractToastError(respHtml)
        if (toastErr != null) {
            return@withContext Triple(false, toastErr, null)
        }

        if (respHtml.contains("ظرفیت به پایان رسیده")) {
            return@withContext Triple(false, "ظرفیت این غذا در رستوران به پایان رسیده است.", null)
        }

        val docResp = Jsoup.parse(respHtml)
        val serverErr = docResp.select("#lblError, #lblMessage, #cphMain_lblError, .alert-danger").firstOrNull()?.text()?.trim()
        if (!serverErr.isNullOrBlank() && serverErr != "-" && !serverErr.contains("موفق")) {
            return@withContext Triple(false, serverErr, null)
        }

        var creditId: String? = null

        // 1. Check for explicit JavaScript call generated by Calinan on successful save
        val scriptPatterns = listOf(
            Pattern.compile("openBankWindow\\s*\\(\\s*['\"]?([1-9]\\d*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("CafeteriaReservation_SelectBank\\.aspx\\?CreditId=([1-9]\\d*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("CreditId=([1-9]\\d*)", Pattern.CASE_INSENSITIVE)
        )
        for (p in scriptPatterns) {
            val m = p.matcher(respHtml)
            while (m.find()) {
                val found = m.group(1)
                if (!found.isNullOrBlank() && found != "0" && found != oldCredit) {
                    creditId = found
                    break
                }
            }
            if (!creditId.isNullOrBlank()) break
        }

        // 2. Check for newly set hdnCreditId in DOM
        if (creditId.isNullOrBlank()) {
            val newHdn = docResp.select("input[name*=hdnCreditId], input[id*=hdnCreditId]").firstOrNull()?.attr("value")?.trim() ?: "0"
            if (newHdn.isNotBlank() && newHdn != "0" && newHdn != oldCredit) {
                creditId = newHdn
            }
        }

        // 3. Fallback: if Calinan generated a creditId matching oldCredit only if respHtml explicitly opened it
        if (creditId.isNullOrBlank()) {
            for (p in scriptPatterns) {
                val m = p.matcher(respHtml)
                if (m.find()) {
                    val found = m.group(1)
                    if (!found.isNullOrBlank() && found != "0") {
                        creditId = found
                        break
                    }
                }
            }
        }

        if (!creditId.isNullOrBlank()) {
            val prepayUrl = "/Reservation/CafeteriaReservation_SelectBank.aspx?CreditId=$creditId&ReserveType=daily"
            return@withContext Triple(true, "سفارش موقت با موفقیت ثبت شد. در حال انتقال به درگاه پرداخت شاپرک...", prepayUrl)
        }

        if (respHtml.contains("tblSelectBank") || respHtml.contains("dataLstBank") || respHtml.contains("CafeteriaReservation_SelectBank")) {
            val prepayUrl = "/Reservation/CafeteriaReservation_SelectBank.aspx"
            return@withContext Triple(true, "ورود مستقیم به صفحه پرداخت رستوران.", prepayUrl)
        }

        if (respHtml.contains("با موفقیت ثبت شد") || respHtml.contains("ثبت گردید")) {
            return@withContext Triple(true, "سفارش با موفقیت ثبت شد!", null)
        }

        Triple(false, "امکان ثبت سفارش در رستوران انتخابی وجود ندارد. لطفاً مهلت یا ظرفیت رستوران را بررسی فرمایید.", null)
    }
}
