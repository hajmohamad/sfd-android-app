package ir.ac.ui.sfd.engine

import ir.ac.ui.sfd.model.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.regex.Pattern

object HtmlParser {

    fun parseStudentInfo(html: String): StudentInfo {
        var name = "نامشخص"
        var studentId = "نامشخص"
        var selfCredit = "۰ ریال"
        var restCredit = "۰ ریال"
        var weekRange = "نامشخص"

        // Student Name & ID: "کاربر: نام - شماره دانشجویی"
        val userMatcher = Pattern.compile("کاربر:\\s*([^-]+)\\s*-\\s*([0-9]+)").matcher(html)
        if (userMatcher.find()) {
            name = userMatcher.group(1)?.trim() ?: "نامشخص"
            studentId = userMatcher.group(2)?.trim() ?: "نامشخص"
        }

        val doc: Document = Jsoup.parse(html)

        // Self credit
        val selfEl = doc.select("[id*=lblCredit], [id*=SelfCredit]").first()
        if (selfEl != null && selfEl.text().isNotBlank()) {
            selfCredit = selfEl.text().trim()
        }

        // Restaurant credit
        val restEl = doc.select("[id*=lblCafeteriaCredit], [id*=RestCredit]").first()
        if (restEl != null && restEl.text().isNotBlank()) {
            restCredit = restEl.text().trim()
        }

        if (selfEl == null && restEl == null) {
            val credMatcher = Pattern.compile("([0-9,]+)\\s*ريال").matcher(html)
            val credits = mutableListOf<String>()
            while (credMatcher.find()) {
                credits.add("${credMatcher.group(1)} ریال")
            }
            if (credits.size >= 1) selfCredit = credits[0]
            if (credits.size >= 2) restCredit = credits[1]
        }

        val weekEl = doc.select("#cphMain_lblWeekDate").first()
        if (weekEl != null) {
            weekRange = weekEl.text().trim()
        }

        val selfAmount = parseRialAmount(selfCredit)
        val restAmount = parseRialAmount(restCredit)

        return StudentInfo(
            name = name,
            studentId = studentId,
            selfCredit = selfCredit,
            restaurantCredit = restCredit,
            weekRange = weekRange,
            selfCreditAmount = selfAmount,
            restaurantCreditAmount = restAmount
        )
    }

    fun parseRialAmount(amountStr: String): Long {
        return try {
            val clean = amountStr.replace("[^0-9]".toRegex(), "")
            if (clean.isBlank()) 0L else clean.toLong()
        } catch (e: Exception) {
            0L
        }
    }

    fun parseSelfGridItems(html: String, mealName: String): List<DayItem> {
        val tableId = when (mealName) {
            "صبحانه" -> "cphMain_grdReservationBreakfast"
            "شام" -> "cphMain_grdReservationDinner"
            else -> "cphMain_grdReservationLunch"
        }

        val doc = Jsoup.parse(html)
        val table = doc.select("#$tableId").first() ?: return emptyList()
        val rows = table.select("tr")
        if (rows.size <= 1) return emptyList()

        val items = mutableListOf<DayItem>()
        for (i in 1 until rows.size) {
            val r = rows[i]
            val cols = r.select("td")
            if (cols.size < 5) continue

            val dayDate = cols[1].text().trim().replace("\\s+".toRegex(), " ")

            val chkInput = cols[0].select("input[type=checkbox]").first()
                ?: r.select("input[type=checkbox]").first()
            val chkName = chkInput?.attr("name") ?: ""

            // In Calinan:
            // - If there is no checkbox or it has 'disabled', the day is closed/disabled
            // - If and only if the checkbox has 'checked', the food is already reserved
            val isDisabled = (chkInput == null) || chkInput.hasAttr("disabled")
            val isReserved = chkInput != null && chkInput.hasAttr("checked")

            val selfSelect = cols[2].select("select").first()
            val selfCtlName = selfSelect?.attr("name") ?: ""
            val selfOptions = mutableListOf<SelfOption>()
            var currentSelf = "-1"
            if (selfSelect != null) {
                for (opt in selfSelect.select("option")) {
                    val v = opt.attr("value")
                    val t = opt.text().trim()
                    if (v != "-1" && t.isNotBlank() && t != "-") {
                        selfOptions.add(SelfOption(v, t))
                    }
                    if (opt.hasAttr("selected")) {
                        currentSelf = v
                    }
                }
            }

            val foodSelect = cols[3].select("select").first()
            val foodCtlName = foodSelect?.attr("name") ?: ""
            val foods = mutableListOf<FoodOption>()
            var reservedFoodText = ""
            if (foodSelect != null) {
                for (opt in foodSelect.select("option")) {
                    val v = opt.attr("value")
                    val fText = opt.text().trim()
                    if (isReserved && opt.hasAttr("selected")) {
                        reservedFoodText = fText
                    }
                    if (v == "-1" || v.isBlank()) continue

                    val pMatch = Pattern.compile("([0-9,]+)\\s*ریال").matcher(fText)
                    val priceInt = if (pMatch.find()) {
                        parseRialAmount(pMatch.group(1) ?: "0")
                    } else 0L
                    val priceStr = if (priceInt > 0) "${String.format("%,d", priceInt)} ریال" else "-"
                    val cleanName = fText.replace("[0-9,]+\\s*ریال".toRegex(), "").trim()

                    foods.add(
                        FoodOption(
                            id = v,
                            name = cleanName,
                            fullName = fText,
                            priceInt = priceInt,
                            priceStr = priceStr,
                            isAvailable = true
                        )
                    )
                }
            }

            // Sort self foods from cheapest to most expensive
            foods.sortBy { it.priceInt }

            val countSelect = cols[4].select("select").first()
            val countCtlName = countSelect?.attr("name") ?: ""

            var reservedSelfTitle = ""
            if (selfSelect != null) {
                val selOpt = selfSelect.select("option[selected]").first()
                if (selOpt != null) {
                    reservedSelfTitle = selOpt.text().trim()
                }
            }
            if (reservedSelfTitle.isBlank()) {
                val rawSelf = cols[2].text().trim()
                if (rawSelf.isNotBlank() && rawSelf != "-") {
                    reservedSelfTitle = rawSelf
                }
            }

            items.add(
                DayItem(
                    dayDate = dayDate,
                    isDisabled = isDisabled,
                    isReserved = isReserved,
                    reservedFood = reservedFoodText,
                    chkName = chkName,
                    selfCtlName = selfCtlName,
                    currentSelf = currentSelf,
                    foodCtlName = foodCtlName,
                    countCtlName = countCtlName,
                    selfOptions = selfOptions,
                    foods = foods,
                    selectedFoodIndex = 0,
                    selectedSelfId = if (currentSelf != "-1") currentSelf else selfOptions.firstOrNull()?.id,
                    reservedSelfTitle = reservedSelfTitle
                )
            )
        }
        return items
    }

    fun extractAllSelfOptions(html: String, mealName: String): List<SelfOption> {
        val items = parseSelfGridItems(html, mealName)
        val map = linkedMapOf<String, String>()
        for (item in items) {
            for (opt in item.selfOptions) {
                if (opt.id != "-1" && opt.id.isNotBlank()) {
                    map[opt.id] = opt.title
                }
            }
        }
        return map.map { SelfOption(it.key, it.value) }
    }

    fun extractCafeteriaDate(html: String): String {
        if (html.isBlank()) return "امروز"
        val doc = Jsoup.parse(html)
        val dateEl = doc.select("#cphMain_lblDate, #lblDate, #cphMain_txtDate, [id*=lblDate], [id*=txtDate]").first()
        if (dateEl != null) {
            val text = if (dateEl.tagName() == "input") dateEl.attr("value") else dateEl.text()
            if (text.isNotBlank() && text != "-") return text.trim()
        }

        // Regex search for Persian weekday and date
        val mFull = Pattern.compile("(شنبه|یکشنبه|دوشنبه|سه\\s*شنبه|چهارشنبه|پنج\\s*شنبه|جمعه)[^<>\\n\\r]{0,35}[0-9۰-۹]{4}/[0-9۰-۹]{1,2}/[0-9۰-۹]{1,2}").matcher(html)
        if (mFull.find()) {
            val res = mFull.group(0)?.trim()
            if (!res.isNullOrBlank()) return res
        }

        val mDate = Pattern.compile("[0-9۰-۹]{4}/[0-9۰-۹]{1,2}/[0-9۰-۹]{1,2}").matcher(html)
        if (mDate.find()) {
            val res = mDate.group(0)?.trim()
            if (!res.isNullOrBlank()) return res
        }

        return "امروز"
    }

    fun parseCafeteriaMealItems(html: String, mealName: String): List<CafeteriaItem> {
        val targetSuffix = when (mealName) {
            "صبحانه" -> "Breakfast"
            "شام" -> "Dinner"
            else -> "Lunch"
        }

        val doc = Jsoup.parse(html)
        var table = doc.select("#cphMain_grd$targetSuffix, [id$=grd$targetSuffix], [id*=grd$targetSuffix], #grd$targetSuffix").first()
        if (table == null) {
            val tables = doc.select("table")
            for (t in tables) {
                val h = t.html()
                if (h.contains("chkReserve") || h.contains("drpNormalCount")) {
                    table = t
                    break
                }
            }
        }
        if (table == null) return emptyList()

        val rows = table.select("tr")
        if (rows.size <= 1) return emptyList()

        val items = mutableListOf<CafeteriaItem>()
        for (i in 1 until rows.size) {
            val r = rows[i]
            val cols = r.select("td, th")
            if (cols.size < 3) continue

            val rowText = r.text().trim()
            if (rowText.contains("داده ای برای نمایش") || rowText.contains("موردی یافت نشد")) continue

            var foodName = if (cols.size > 1) cols[1].text().trim() else ""
            if (foodName.isBlank() || foodName.matches("^[0-9]+$".toRegex())) {
                for (col in cols) {
                    val ct = col.text().trim()
                    if (ct.isNotBlank() && !ct.contains("ریال") && !ct.matches("^[0-9,]+$".toRegex()) && ct.length > 2) {
                        foodName = ct
                        break
                    }
                }
            }
            if (foodName.isBlank()) continue

            var priceInt = 0L
            for (col in cols) {
                val ct = col.text()
                if (ct.contains("ریال") || ct.contains(",")) {
                    val p = parseRialAmount(ct)
                    if (p > 0) {
                        priceInt = p
                        break
                    }
                }
            }
            if (priceInt == 0L && cols.size > 2) {
                priceInt = parseRialAmount(cols[2].text())
            }
            val priceStr = if (priceInt > 0) "${String.format("%,d", priceInt)} ریال" else "-"

            var remCount = 0
            if (cols.size > 6) {
                remCount = cols[6].text().trim().toIntOrNull() ?: 0
            }
            if (remCount == 0) {
                for (idx in (cols.size - 1) downTo 2) {
                    val t = cols[idx].text().trim()
                    if (t.matches("^[0-9]+$".toRegex())) {
                        remCount = t.toIntOrNull() ?: 0
                        break
                    }
                }
            }

            val chkInput = r.select("input[type=checkbox]").first()
            val chkName = chkInput?.attr("name") ?: ""
            val chkChecked = chkInput != null && chkInput.hasAttr("checked")

            val hdnReservedEl = r.select("input[name*=hdnIsReserved], input[id*=hdnIsReserved]").first()
            val hdnVal = hdnReservedEl?.attr("value")?.trim() ?: ""
            val hdnReserved = hdnVal.equals("True", ignoreCase = true) || hdnVal == "1"

            val countSelect = r.select("select[name*=drpNormalCount], select[name*=Count]").first()
            val countName = countSelect?.attr("name") ?: ""
            val countOpt = countSelect?.select("option[selected]")?.first()
            val countVal = countOpt?.attr("value")?.toIntOrNull() ?: 0

            val isReserved = chkChecked || hdnReserved || countVal > 0

            val isDisabled = chkInput?.hasAttr("disabled") == true

            items.add(
                CafeteriaItem(
                    name = foodName,
                    priceInt = priceInt,
                    priceStr = priceStr,
                    remaining = remCount,
                    isAvailable = !isDisabled && (remCount > 0 || isReserved),
                    isReserved = isReserved,
                    chkName = chkName,
                    countName = countName
                )
            )
        }

        // Available items first (sorted by price ascending), sold out items at the end
        items.sortWith(
            compareByDescending<CafeteriaItem> { it.isAvailable && (it.remaining > 0 || it.isReserved) }
                .thenBy { it.priceInt }
        )

        return items
    }

    fun extractRestaurants(html: String, mealName: String = "ناهار"): Pair<Map<String, String>, String?> {
        val doc = Jsoup.parse(html)
        val targetSuffix = when (mealName) {
            "شام" -> "Dinner"
            "صبحانه" -> "Breakfast"
            else -> "Lunch"
        }
        val select = doc.select("select[name*=drpSelfTitle_$targetSuffix]").first()
            ?: doc.select("select[name*=drpSelfTitle]").first()

        val result = linkedMapOf<String, String>()
        var selectedId: String? = null
        if (select != null) {
            for (opt in select.select("option")) {
                val v = opt.attr("value")
                val t = opt.text().trim()
                if (v != "-1" && t.isNotBlank()) {
                    result[v] = t
                }
                if (opt.hasAttr("selected")) {
                    selectedId = v
                }
            }
        }

        // Fallback for University of Isfahan free restaurants (Yas 1 and Yas 2)
        if (result.isEmpty()) {
            result["13"] = "رستوران یاس یک (۱) *"
            result["14"] = "رستوران یاس دو (۲) **"
            if (selectedId == null) selectedId = "13"
        }

        return Pair(result, selectedId)
    }

    fun extractToastError(html: String): String? {
        val m = Pattern.compile("toastify\\([^)]*'([^']*خطا[^']*)'").matcher(html)
        if (m.find()) {
            return m.group(1)
        }
        return null
    }

    fun extractLoginError(html: String): String? {
        val doc = Jsoup.parse(html)
        // 1. Check dedicated Calinan server error labels (exclude static client-side hidden spans)
        val loginErr = doc.select("#lblLoginError, #cphMain_lblLoginError, #lblError, #cphMain_lblError, .alert-danger, .error-message").firstOrNull()
        if (loginErr != null) {
            val t = loginErr.text().trim()
            if (t.isNotBlank() && t != "-" && !t.contains("لطفا نام کاربری") && !t.contains("لطفا کد امنیتی")) {
                return t
            }
        }

        // 2. Check toastify errors
        val toast = extractToastError(html)
        if (toast != null) return toast

        // 3. Check explicit Calinan server error phrases
        if (html.contains("کد امنیتی صحیح نمی باشد") || html.contains("کد امنیتی اشتباه است")) {
            return "کد امنیتی اشتباه است. لطفاً کد جدید تصویر را وارد فرمایید."
        }
        if (html.contains("نام کاربری یا کلمه عبور اشتباه است") || html.contains("نام کاربری یا کلمه عبور صحیح نمی باشد")) {
            return "نام کاربری یا کلمه عبور اشتباه است."
        }
        if (html.contains("کلمه عبور اشتباه است")) {
            return "کلمه عبور وارد شده اشتباه است."
        }
        if (html.contains("خطا در کد امنیتی")) {
            return "خطای منقضی شدن کد امنیتی. تصویر را تازه‌سازی نمایید."
        }
        if (html.contains("حساب کاربری شما غیرفعال است") || html.contains("کاربر مسدود است")) {
            return "حساب کاربری شما در سامانه مسدود یا غیرفعال است."
        }

        // 4. If still on the login page and not logged in
        if ((html.contains("txtUsername") || html.contains("btnLogin") || html.contains("txtPassword")) &&
            !html.contains("Reservation.aspx") &&
            !html.contains("cphMain_lblWeekDate") &&
            !html.contains("خرید هفتگی غذای سلف")
        ) {
            return "احراز هویت انجام نشد. لطفاً نام کاربری، رمز عبور و کد امنیتی را بررسی نمایید."
        }
        return null
    }
}
