package ir.ac.ui.sfd.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import ir.ac.ui.sfd.BuildConfig
import ir.ac.ui.sfd.ads.AdManager
import ir.ac.ui.sfd.databinding.ActivityMainBinding
import ir.ac.ui.sfd.databinding.DialogAutoReservePreviewBinding
import ir.ac.ui.sfd.databinding.DialogLoginBinding
import ir.ac.ui.sfd.databinding.DialogPreferencesBinding
import ir.ac.ui.sfd.databinding.DialogTopupBinding
import ir.ac.ui.sfd.engine.HtmlParser
import ir.ac.ui.sfd.engine.PreferenceEngine
import ir.ac.ui.sfd.model.*
import ir.ac.ui.sfd.network.SFDClient
import ir.ac.ui.sfd.ocr.CaptchaOCR
import ir.ac.ui.sfd.ui.adapter.AutoReservePlanAdapter
import ir.ac.ui.sfd.ui.adapter.CafeteriaAdapter
import ir.ac.ui.sfd.ui.adapter.WeeklyDayAdapter
import ir.ac.ui.sfd.update.UpdateManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ir.ac.ui.sfd.worker.AutoReserveReceiver
import ir.ac.ui.sfd.worker.AutoReserveScheduler
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sfdClient: SFDClient
    private lateinit var captchaOcr: CaptchaOCR

    private lateinit var weeklyAdapter: WeeklyDayAdapter
    private lateinit var cafeteriaAdapter: CafeteriaAdapter

    private var currentResHtml: String = ""
    private var currentCafHtml: String = ""
    private var studentInfo: StudentInfo? = null
    private var preferences = UserPreferences()

    private var selectedMeal = "ناهار"
    private var selectedCafMeal = "ناهار"
    private var selectedRestaurantId = "13"
    private val restaurantMap = mutableMapOf<String, String>()
    private val globalSelfList = mutableListOf<SelfOption>()
    private var globalSelectedSelfId: String? = null
    private var globalSelectedSelfTitle: String? = null

    private var hasAutoAdvancedWeek = false
    private var isSwitchingRestaurant = false
    private var currentWeekOffset: Int = 0

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "مجوز اعلان‌ها تایید و فعال شد.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "توجه: جهت دریافت اعلان کسری موجودی، دسترسی اعلان موردنیاز است.", Toast.LENGTH_LONG).show()
        }
    }

    private val paymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "تاییدیه پرداخت دریافت شد! در حال به‌روزرسانی اطلاعات...", Toast.LENGTH_SHORT).show()
            currentCafHtml = ""
            refreshData()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sfdClient = SFDClient(this)
        captchaOcr = CaptchaOCR(this)
        loadPreferences()

        // Create notification channel and request runtime notification permission if needed (Android 13+)
        AutoReserveReceiver.createNotificationChannel(this)
        checkAndRequestNotificationPermission()

        setupRecyclerViews()
        setupListeners()

        val prefs = getSharedPreferences("sfd_auth", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)

        // Asynchronously check for remote updates and remote ads
        checkAppUpdate(isManual = false)
        setupAds()

        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            if (sfdClient.hasValidSession()) {
                // Persistent session is active! Open dashboard instantly without re-login or captcha
                refreshData()
            } else {
                performAutoLogin(savedUser, savedPass)
            }
        } else {
            showLoginDialog()
        }
    }

    private fun setupRecyclerViews() {
        weeklyAdapter = WeeklyDayAdapter(
            context = this,
            onReserveClicked = { dayItem, foodOption ->
                handleSelfReservation(dayItem, foodOption)
            },
            onCancelClicked = { dayItem ->
                handleCancelReservation(dayItem)
            }
        )
        binding.rvWeeklyDays.layoutManager = LinearLayoutManager(this)
        binding.rvWeeklyDays.adapter = weeklyAdapter

        cafeteriaAdapter = CafeteriaAdapter { cafeteriaItem ->
            handleCafeteriaOrder(cafeteriaItem)
        }
        binding.rvCafeteriaFoods.layoutManager = LinearLayoutManager(this)
        binding.rvCafeteriaFoods.adapter = cafeteriaAdapter
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            refreshData()
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) {
                    binding.sectionSelf.visibility = View.VISIBLE
                    binding.sectionCafeteria.visibility = View.GONE
                } else {
                    binding.sectionSelf.visibility = View.GONE
                    binding.sectionCafeteria.visibility = View.VISIBLE
                    loadCafeteriaData()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Meal filter chips for Self
        binding.cgMeals.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedMeal = when {
                checkedIds.contains(binding.chipDinner.id) -> "شام"
                checkedIds.contains(binding.chipBreakfast.id) -> "صبحانه"
                else -> "ناهار"
            }
            updateWeeklyMealList()
        }

        // Meal filter chips for Cafeteria
        binding.cgCafMeals.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedCafMeal = when {
                checkedIds.contains(binding.chipCafDinner.id) -> "شام"
                checkedIds.contains(binding.chipCafBreakfast.id) -> "صبحانه"
                else -> "ناهار"
            }
            setupRestaurantDropdown()
        }

        // Cafeteria day navigation buttons
        binding.btnNextCafDay.setOnClickListener {
            navigateCafDay("next")
        }
        binding.btnLastCafDay.setOnClickListener {
            navigateCafDay("last")
        }

        // Week navigation buttons
        binding.btnNextWeek.setOnClickListener {
            navigateWeek("next")
        }
        binding.btnLastWeek.setOnClickListener {
            navigateWeek("last")
        }

        // Topup button for self service
        binding.btnTopupSelf.setOnClickListener {
            showTopupDialog(isCafeteria = false)
        }
        binding.btnViewCafeteria.setOnClickListener {
            binding.tabLayout.getTabAt(1)?.select()
        }

        // Auto-reserve pipeline button (interactive preview & edit)
        binding.btnRunAutoReserve.setOnClickListener {
            showAutoReservePreviewDialog()
        }

        // Settings & Logout
        binding.btnSettings.setOnClickListener {
            showPreferencesDialog()
        }
        binding.btnLogout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("خروج از حساب")
                .setMessage("آیا می‌خواهید از حساب کاربری خود خارج شوید؟")
                .setPositiveButton("بله") { _, _ ->
                    sfdClient.cookieJar.clear()
                    getSharedPreferences("sfd_auth", Context.MODE_PRIVATE).edit().clear().apply()
                    showLoginDialog()
                }
                .setNegativeButton("انصراف", null)
                .show()
        }
    }

    private fun loadPreferences() {
        val sp = getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
        val json = sp.getString("user_prefs", null)
        if (!json.isNullOrBlank()) {
            try {
                preferences = Gson().fromJson(json, UserPreferences::class.java)
            } catch (e: Exception) {
                preferences = UserPreferences()
            }
        }
    }

    private fun savePreferences() {
        val sp = getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("user_prefs", Gson().toJson(preferences)).apply()
    }

    private fun showLoginDialog() {
        val dialogBinding = DialogLoginBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        val spAuth = getSharedPreferences("sfd_auth", Context.MODE_PRIVATE)
        dialogBinding.etStudentId.setText(spAuth.getString("username", ""))
        dialogBinding.etPassword.setText(spAuth.getString("password", ""))

        fun loadCaptcha(keepErrorVisible: Boolean = false) {
            if (!keepErrorVisible) {
                dialogBinding.cardLoginError.visibility = View.GONE
            }
            dialogBinding.tvLoginStatus.visibility = View.VISIBLE
            dialogBinding.tvLoginStatus.text = "در حال بارگذاری کد امنیتی..."
            lifecycleScope.launch {
                try {
                    sfdClient.initLoginSession()
                    val bytes = sfdClient.downloadCaptcha()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    dialogBinding.ivCaptcha.setImageBitmap(bmp)

                    val solution = captchaOcr.solve(bmp)
                    if (solution != null && solution.code.length == 4) {
                        dialogBinding.etCaptcha.setText(solution.code)
                        if (!keepErrorVisible) {
                            dialogBinding.tvLoginStatus.text = "کد امنیتی (${solution.code}) به‌طور خودکار تشخیص داده شد."
                        } else {
                            dialogBinding.tvLoginStatus.visibility = View.GONE
                        }
                    } else {
                        dialogBinding.etCaptcha.setText("")
                        dialogBinding.tvLoginStatus.text = "لطفاً کد تصویر را وارد نمایید."
                    }
                } catch (e: Exception) {
                    if (!keepErrorVisible) {
                        dialogBinding.cardLoginError.visibility = View.VISIBLE
                        dialogBinding.tvLoginError.text = "خطا در برقراری ارتباط با سرور: ${e.message}"
                        dialogBinding.tvLoginStatus.visibility = View.GONE
                    }
                }
            }
        }

        dialogBinding.btnRefreshCaptcha.setOnClickListener {
            loadCaptcha(keepErrorVisible = false)
        }

        dialogBinding.btnLogin.setOnClickListener {
            val user = dialogBinding.etStudentId.text.toString().trim()
            val pass = dialogBinding.etPassword.text.toString().trim()
            val captcha = dialogBinding.etCaptcha.text.toString().trim()

            if (user.isBlank() || pass.isBlank()) {
                dialogBinding.cardLoginError.visibility = View.VISIBLE
                dialogBinding.tvLoginError.text = "لطفاً شماره دانشجویی و کلمه عبور را تکمیل فرمایید."
                return@setOnClickListener
            }
            if (captcha.isBlank()) {
                dialogBinding.cardLoginError.visibility = View.VISIBLE
                dialogBinding.tvLoginError.text = "لطفاً کد امنیتی را وارد فرمایید."
                return@setOnClickListener
            }

            dialogBinding.cardLoginError.visibility = View.GONE
            dialogBinding.pbLogin.visibility = View.VISIBLE
            dialogBinding.btnLogin.isEnabled = false
            dialogBinding.tvLoginStatus.visibility = View.VISIBLE
            dialogBinding.tvLoginStatus.text = "در حال ورود به سامانه کالینان دانشگاه اصفهان..."

            lifecycleScope.launch {
                val res = sfdClient.login(user, pass, captcha)
                dialogBinding.pbLogin.visibility = View.GONE
                dialogBinding.btnLogin.isEnabled = true

                if (res.isSuccess) {
                    if (dialogBinding.cbRemember.isChecked) {
                        spAuth.edit()
                            .putString("username", user)
                            .putString("password", pass)
                            .apply()
                    }
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "ورود با موفقیت انجام شد!", Toast.LENGTH_SHORT).show()
                    refreshData()
                    showLoginAdIfAvailable {
                        // Ad completed
                    }
                } else {
                    val errMsg = res.exceptionOrNull()?.message ?: "خطای ناشناخته در ورود به سامانه"
                    dialogBinding.cardLoginError.visibility = View.VISIBLE
                    dialogBinding.tvLoginError.text = errMsg
                    dialogBinding.tvLoginStatus.visibility = View.GONE
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    Toast.makeText(this@MainActivity, errMsg, Toast.LENGTH_LONG).show()
                    loadCaptcha(keepErrorVisible = true)
                }
            }
        }

        dialog.show()
        loadCaptcha(keepErrorVisible = false)
    }

    private fun performAutoLogin(user: String, pass: String) {
        binding.swipeRefresh.isRefreshing = true
        binding.tvStudentProfile.text = "در حال ورود خودکار با مشخصات ذخیره‌شده..."
        lifecycleScope.launch {
            try {
                sfdClient.initLoginSession()
                val bytes = sfdClient.downloadCaptcha()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val solution = captchaOcr.solve(bmp)
                val code = solution?.code ?: ""

                if (code.length == 4) {
                    val loginRes = sfdClient.login(user, pass, code)
                    if (loginRes.isSuccess) {
                        refreshData()
                        showLoginAdIfAvailable {
                            // Ad completed
                        }
                        return@launch
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            binding.swipeRefresh.isRefreshing = false
            showLoginDialog()
        }
    }

    private fun refreshData() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                currentResHtml = sfdClient.getReservationPage()
                if (sfdClient.isSessionExpired(currentResHtml)) {
                    // Session expired on server! Perform seamless silent auto-login
                    val spAuth = getSharedPreferences("sfd_auth", Context.MODE_PRIVATE)
                    val u = spAuth.getString("username", null)
                    val p = spAuth.getString("password", null)
                    if (!u.isNullOrBlank() && !p.isNullOrBlank()) {
                        performAutoLogin(u, p)
                        return@launch
                    } else {
                        showLoginDialog()
                        return@launch
                    }
                }

                studentInfo = HtmlParser.parseStudentInfo(currentResHtml)

                currentWeekOffset = 0
                // Tuesday+ Rule or All Locked Rule:
                // If it is Tuesday or later OR if all days/meals in current week are locked (disabled),
                // automatically advance to next week so the user directly sees available days for next week.
                val cal = Calendar.getInstance()
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                val isLateInWeek = dayOfWeek == Calendar.TUESDAY ||
                                   dayOfWeek == Calendar.WEDNESDAY || 
                                   dayOfWeek == Calendar.THURSDAY || 
                                   dayOfWeek == Calendar.FRIDAY

                val lunchItems = HtmlParser.parseSelfGridItems(currentResHtml, "ناهار")
                val dinnerItems = HtmlParser.parseSelfGridItems(currentResHtml, "شام")
                val isCurrentWeekAllLocked = (lunchItems.isNotEmpty() && lunchItems.all { it.isDisabled }) &&
                                             (dinnerItems.isEmpty() || dinnerItems.all { it.isDisabled })

                if ((isLateInWeek || isCurrentWeekAllLocked) && !hasAutoAdvancedWeek) {
                    hasAutoAdvancedWeek = true
                    try {
                        val (changed, newHtml) = sfdClient.navigateReservationWeek(currentResHtml, "next")
                        if (changed && newHtml.isNotBlank()) {
                            currentResHtml = newHtml
                            currentWeekOffset = 1
                            studentInfo = HtmlParser.parseStudentInfo(currentResHtml)
                            val infoMsg = if (isCurrentWeekAllLocked) {
                                "🗓️ با توجه به اتمام مهلت و قفل بودن روزهای هفته جاری، تقویم هفته آینده بارگذاری شد."
                            } else {
                                "🗓️ نمایش خودکار هفته آینده (امکان رزرو برای هفته بعد فعال است)"
                            }
                            Toast.makeText(this@MainActivity, infoMsg, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                updateHeaderInfo()
                updateWeeklyMealList()

                // Also preload cafeteria
                currentCafHtml = sfdClient.getCafeteriaPage()
                setupRestaurantDropdown()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "خطا در دریافت اطلاعات: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun updateHeaderInfo() {
        val info = studentInfo ?: return
        binding.tvStudentProfile.text = "دانشجو: ${info.name} (${info.studentId})"
        binding.tvSelfCredit.text = info.selfCredit
        binding.tvRestCredit.text = info.restaurantCredit
        binding.tvCurrentWeek.text = info.weekRange
    }

    private fun updateWeeklyMealList() {
        if (currentResHtml.isBlank()) return
        val items = HtmlParser.parseSelfGridItems(currentResHtml, selectedMeal)
        setupGlobalSelfDropdown(items)
        weeklyAdapter.submitList(items)
    }

    private fun setupGlobalSelfDropdown(items: List<DayItem>) {
        val selfOptions = HtmlParser.extractAllSelfOptions(currentResHtml, selectedMeal)
        globalSelfList.clear()
        globalSelfList.addAll(selfOptions)

        if (globalSelfList.isEmpty()) {
            binding.cardGlobalSelf.visibility = View.GONE
            weeklyAdapter.setGlobalSelf(null, null)
            return
        }

        binding.cardGlobalSelf.visibility = View.VISIBLE
        val selfTitles = globalSelfList.map { it.title }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, selfTitles)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spGlobalSelf.adapter = spinnerAdapter

        val defaultTitle = when (selectedMeal) {
            "صبحانه" -> preferences.defaultBreakfastSelf
            "شام" -> preferences.defaultDinnerSelf
            else -> preferences.defaultLunchSelf
        }

        var preferredIdx = globalSelfList.indexOfFirst {
            it.title.trim() == defaultTitle.trim() || it.title.contains(defaultTitle) || defaultTitle.contains(it.title)
        }
        if (preferredIdx < 0) preferredIdx = 0

        val initialSelf = globalSelfList[preferredIdx]
        globalSelectedSelfId = initialSelf.id
        globalSelectedSelfTitle = initialSelf.title
        binding.spGlobalSelf.setSelection(preferredIdx)
        weeklyAdapter.setGlobalSelf(initialSelf.id, initialSelf.title)

        // Apply global self to day items that have it
        for (day in items) {
            if (day.selfOptions.any { it.id == initialSelf.id }) {
                day.selectedSelfId = initialSelf.id
            }
        }

        binding.spGlobalSelf.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val chosen = globalSelfList[pos]
                if (chosen.id != globalSelectedSelfId) {
                    globalSelectedSelfId = chosen.id
                    globalSelectedSelfTitle = chosen.title
                    when (selectedMeal) {
                        "صبحانه" -> preferences.defaultBreakfastSelf = chosen.title
                        "شام" -> preferences.defaultDinnerSelf = chosen.title
                        else -> preferences.defaultLunchSelf = chosen.title
                    }
                    savePreferences()

                    val currentItems = HtmlParser.parseSelfGridItems(currentResHtml, selectedMeal)
                    for (day in currentItems) {
                        if (day.selfOptions.any { it.id == chosen.id }) {
                            day.selectedSelfId = chosen.id
                        }
                    }
                    weeklyAdapter.setGlobalSelf(chosen.id, chosen.title)
                    weeklyAdapter.submitList(currentItems)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun navigateWeek(direction: String) {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val (changed, newHtml) = sfdClient.navigateReservationWeek(currentResHtml, direction)
                currentResHtml = newHtml
                if (changed) {
                    if (direction == "next") currentWeekOffset++ else currentWeekOffset--
                }
                studentInfo = HtmlParser.parseStudentInfo(currentResHtml)
                updateHeaderInfo()
                updateWeeklyMealList()
                if (changed) {
                    Toast.makeText(this@MainActivity, "تقویم تغییر یافت: ${studentInfo?.weekRange}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "خطا در تغییر هفته: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun setupRestaurantDropdown() {
        restaurantMap.clear()
        val (extractedMap, activeServerId) = HtmlParser.extractRestaurants(currentCafHtml, selectedCafMeal)
        restaurantMap.putAll(extractedMap)
        if (restaurantMap.isEmpty()) {
            binding.cardRestaurantSelector.visibility = View.GONE
            updateCafeteriaList()
            return
        }
        binding.cardRestaurantSelector.visibility = View.VISIBLE

        if (selectedRestaurantId.isBlank() || !restaurantMap.containsKey(selectedRestaurantId)) {
            selectedRestaurantId = activeServerId ?: restaurantMap.keys.firstOrNull() ?: ""
        }

        val names = restaurantMap.values.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.spRestaurants.onItemSelectedListener = null
        binding.spRestaurants.adapter = adapter

        val selectedIdx = restaurantMap.keys.indexOf(selectedRestaurantId).takeIf { it >= 0 } ?: 0
        binding.spRestaurants.setSelection(selectedIdx, false)

        binding.spRestaurants.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos < 0 || pos >= names.size) return
                val chosenName = names[pos]
                val chosenId = restaurantMap.entries.firstOrNull { it.value == chosenName }?.key ?: return
                if (chosenId != selectedRestaurantId && !isSwitchingRestaurant) {
                    selectedRestaurantId = chosenId
                    switchRestaurant(chosenId)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updateCafeteriaList()
    }

    private fun switchRestaurant(id: String) {
        if (isSwitchingRestaurant) return
        isSwitchingRestaurant = true
        selectedRestaurantId = id
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                currentCafHtml = sfdClient.switchCafeteriaRestaurant(currentCafHtml, id, selectedCafMeal)
                setupRestaurantDropdown()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "خطا در تغییر رستوران: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isSwitchingRestaurant = false
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun loadCafeteriaData() {
        if (currentCafHtml.isBlank()) {
            binding.swipeRefresh.isRefreshing = true
            lifecycleScope.launch {
                try {
                    currentCafHtml = sfdClient.getCafeteriaPage()
                    setupRestaurantDropdown()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        } else {
            updateCafeteriaList()
        }
    }

    private fun navigateCafDay(direction: String) {
        if (currentCafHtml.isBlank()) return
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val (changed, newHtml) = sfdClient.navigateCafeteriaDay(currentCafHtml, direction)
                if (newHtml.isNotBlank()) {
                    currentCafHtml = newHtml
                }
                setupRestaurantDropdown()
                val dateStr = HtmlParser.extractCafeteriaDate(currentCafHtml)
                binding.tvCurrentCafDate.text = dateStr
                if (changed) {
                    Toast.makeText(this@MainActivity, "تاریخ رستوران: $dateStr", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "خطا در تغییر روز: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun updateCafeteriaList() {
        if (currentCafHtml.isBlank()) return
        val dateStr = HtmlParser.extractCafeteriaDate(currentCafHtml)
        binding.tvCurrentCafDate.text = dateStr

        val items = HtmlParser.parseCafeteriaMealItems(currentCafHtml, selectedCafMeal)
        if (items.isEmpty()) {
            binding.layoutCafEmptyState.visibility = View.VISIBLE
            binding.rvCafeteriaFoods.visibility = View.GONE
        } else {
            binding.layoutCafEmptyState.visibility = View.GONE
            binding.rvCafeteriaFoods.visibility = View.VISIBLE
        }
        cafeteriaAdapter.submitList(items)
    }

    private fun handleSelfReservation(dayItem: DayItem, foodOption: FoodOption) {
        val currentCredit = studentInfo?.selfCreditAmount ?: 0L
        if (currentCredit < foodOption.priceInt) {
            val deficit = foodOption.priceInt - currentCredit
            val suggested = PreferenceEngine.calculateSmartTopup(deficit)

            MaterialAlertDialogBuilder(this)
                .setTitle("کسری موجودی سلف عادی")
                .setMessage("موجودی سلف شما کافی نیست.\nقیمت غذا: ${foodOption.priceStr}\nموجودی فعلی: ${studentInfo?.selfCredit}\nکسری: ${String.format("%,d", deficit)} ریال\n\nشارژ پیشنهادی: ${String.format("%,d", suggested)} ریال")
                .setPositiveButton("پرداخت درون‌برنامه‌ای در شاپرک") { _, _ ->
                    launchInAppPayment("${sfdClient.baseUrl}/ePay/ePay_Payment.aspx", suggested, "افزایش اعتبار سلف عادی")
                }
                .setNegativeButton("انصراف", null)
                .show()
            return
        }

        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val (ok, msg, newHtml) = sfdClient.reserveSelfService(currentResHtml, selectedMeal, dayItem, foodOption, dayItem.selectedSelfId)
                if (ok) {
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    Toast.makeText(this@MainActivity, "غذای «${foodOption.name}» با موفقیت رزرو شد.", Toast.LENGTH_SHORT).show()
                    if (newHtml.isNotBlank()) {
                        currentResHtml = newHtml
                        studentInfo = HtmlParser.parseStudentInfo(currentResHtml)
                        updateHeaderInfo()
                        updateWeeklyMealList()
                    }
                } else {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "خطا در رزرو: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun handleCancelReservation(dayItem: DayItem) {
        val foodName = dayItem.reservedFood.ifBlank { "رزرو شده" }
        MaterialAlertDialogBuilder(this)
            .setTitle("لغو رزرو غذای سلف")
            .setMessage("آیا از لغو رزرو غذای «$foodName» برای ${dayItem.dayDate} اطمینان دارید؟\n\nمبلغ این غذا به اعتبار حساب سلف شما باز خواهد گشت.")
            .setPositiveButton("بله، لغو شود") { _, _ ->
                binding.swipeRefresh.isRefreshing = true
                lifecycleScope.launch {
                    try {
                        val (ok, msg, newHtml) = sfdClient.cancelSelfService(currentResHtml, selectedMeal, dayItem)
                        if (ok) {
                            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                            Toast.makeText(this@MainActivity, "رزرو با موفقیت لغو شد و اعتبار بازگشت.", Toast.LENGTH_SHORT).show()
                            if (newHtml.isNotBlank()) {
                                currentResHtml = newHtml
                                studentInfo = HtmlParser.parseStudentInfo(currentResHtml)
                                updateHeaderInfo()
                                updateWeeklyMealList()
                            }
                        } else {
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "خطا در لغو رزرو: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        binding.swipeRefresh.isRefreshing = false
                    }
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun handleCafeteriaOrder(item: CafeteriaItem) {
        val currentCafItems = HtmlParser.parseCafeteriaMealItems(currentCafHtml, selectedCafMeal)
        val alreadyReserved = currentCafItems.firstOrNull { it.isReserved }
        if (alreadyReserved != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("عدم امکان سفارش مجدد")
                .setMessage("شما قبلاً غذای «${alreadyReserved.name}» را برای این وعده سفارش داده‌اید.\n\nطبق قوانین سامانه کالینان، در هر وعده صرفاً امکان رزرو ۱ غذا در رستوران آزاد وجود دارد.")
                .setPositiveButton("متوجه شدم", null)
                .show()
            return
        }

        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val (ok, msg, prepayUrl) = sfdClient.reserveCafeteriaFood(
                    currentCafHtml, selectedCafMeal, item, selectedRestaurantId
                )

                if (ok && !prepayUrl.isNullOrBlank()) {
                    val fullUrl = if (prepayUrl.startsWith("http")) prepayUrl else "${sfdClient.baseUrl}$prepayUrl"
                    val restName = restaurantMap[selectedRestaurantId] ?: "یاس"
                    Toast.makeText(this@MainActivity, "سفارش موقت ثبت شد؛ انتقال به درگاه شاپرک...", Toast.LENGTH_SHORT).show()
                    launchInAppPayment(fullUrl, item.priceInt, "پیش‌پرداخت غذای ${item.name} ($restName)")
                } else if (ok) {
                    Toast.makeText(this@MainActivity, "سفارش با موفقیت ثبت شد!", Toast.LENGTH_SHORT).show()
                    currentCafHtml = ""
                    refreshData()
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("عدم ثبت سفارش")
                        .setMessage(msg.ifBlank { "ثبت سفارش از رستوران آزاد در حال حاضر توسط سامانه کالینان امکان‌پذیر نیست." })
                        .setPositiveButton("باشه", null)
                        .show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "خطا: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun launchInAppPayment(url: String, amount: Long, title: String) {
        val cookies = sfdClient.cookieJar.getAllCookies().map { "${it.name}=${it.value}" }
        val intent = Intent(this, InAppPaymentActivity::class.java).apply {
            putExtra(InAppPaymentActivity.EXTRA_URL, url)
            putExtra(InAppPaymentActivity.EXTRA_AMOUNT, amount)
            putExtra(InAppPaymentActivity.EXTRA_TITLE, title)
            putStringArrayListExtra(InAppPaymentActivity.EXTRA_COOKIES, ArrayList(cookies))
        }
        paymentLauncher.launch(intent)
    }

    private fun showTopupDialog(isCafeteria: Boolean = false) {
        val dBinding = DialogTopupBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dBinding.root)
            .create()

        if (isCafeteria) {
            dBinding.tvTopupTitle.text = "افزایش اعتبار رستوران آزاد"
            dBinding.tvTopupInfo.text = "مبلغ پرداختی به کیف پول رستوران آزاد (یاس) شارژ می‌شود و در سفارش غذاهای رستوران آزاد اعمال می‌گردد."
        }

        fun updateTomanPreview(amountStr: String) {
            val rials = amountStr.replace(",", "").trim().toLongOrNull() ?: 0L
            val tomans = rials / 10
            dBinding.tvTomanHelper.text = "معادل: ${String.format("%,d", tomans)} تومان"
        }

        updateTomanPreview(dBinding.etTopupAmount.text?.toString() ?: "")

        dBinding.etTopupAmount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateTomanPreview(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dBinding.btnChip200.setOnClickListener {
            dBinding.etTopupAmount.setText("200000")
            dBinding.etTopupAmount.setSelection(dBinding.etTopupAmount.text?.length ?: 0)
        }
        dBinding.btnChip500.setOnClickListener {
            dBinding.etTopupAmount.setText("500000")
            dBinding.etTopupAmount.setSelection(dBinding.etTopupAmount.text?.length ?: 0)
        }
        dBinding.btnChip1000.setOnClickListener {
            dBinding.etTopupAmount.setText("1000000")
            dBinding.etTopupAmount.setSelection(dBinding.etTopupAmount.text?.length ?: 0)
        }
        dBinding.btnChip2000.setOnClickListener {
            dBinding.etTopupAmount.setText("2000000")
            dBinding.etTopupAmount.setSelection(dBinding.etTopupAmount.text?.length ?: 0)
        }

        dBinding.btnStartPayment.setOnClickListener {
            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            val amt = dBinding.etTopupAmount.text.toString().trim().toLongOrNull() ?: 1000000L
            dialog.dismiss()
            val targetUrl = "${sfdClient.baseUrl}/ePay/ePay_Payment.aspx"
            val title = if (isCafeteria) "افزایش اعتبار رستوران آزاد" else "افزایش اعتبار سلف عادی"
            launchInAppPayment(targetUrl, amt, title)
        }

        dialog.show()
    }

    private fun showPreferencesDialog() {
        val dBinding = DialogPreferencesBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dBinding.root)
            .create()

        dBinding.etLunchPriority.setText(preferences.lunchPriority.joinToString("، "))
        dBinding.etDinnerPriority.setText(preferences.dinnerPriority.joinToString("، "))
        dBinding.etBlacklist.setText(preferences.blacklist.joinToString("، "))
        dBinding.cbSkipIfNoMatch.isChecked = preferences.skipIfNoPreferenceMatch

        // Scheduled Auto-Reserve Bindings
        dBinding.swEnableSchedule.isChecked = preferences.autoReserveScheduled
        dBinding.llScheduleOptions.visibility = if (preferences.autoReserveScheduled) View.VISIBLE else View.GONE
        dBinding.swEnableSchedule.setOnCheckedChangeListener { _, isChecked ->
            dBinding.llScheduleOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && !isNotificationPermissionGranted()) {
                checkAndRequestNotificationPermission()
            }
        }

        val days = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه", "هر روز (روزانه)")
        val dayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, days)
        dayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dBinding.spScheduleDay.adapter = dayAdapter
        val dayIdx = days.indexOf(preferences.autoReserveDay).takeIf { it >= 0 } ?: 4
        dBinding.spScheduleDay.setSelection(dayIdx)

        var selectedHour = preferences.autoReserveHour
        var selectedMinute = preferences.autoReserveMinute

        fun updateTimeDisplay() {
            val hStr = String.format("%02d", selectedHour)
            val mStr = String.format("%02d", selectedMinute)
            dBinding.tvSelectedScheduleTime.text = "$hStr:$mStr (کلیک جهت تغییر)"
        }
        updateTimeDisplay()

        dBinding.btnPickScheduleTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    selectedHour = hourOfDay
                    selectedMinute = minute
                    updateTimeDisplay()
                },
                selectedHour,
                selectedMinute,
                true
            ).apply {
                setTitle("انتخاب زمان اجرای رزرو خودکار")
                show()
            }
        }

        dBinding.cbNotifyOnDeficit.isChecked = preferences.notifyOnDeficit
        dBinding.cbNotifyOnDeficit.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isNotificationPermissionGranted()) {
                checkAndRequestNotificationPermission()
            }
        }

        fun updateNotificationPermissionStatus() {
            if (isNotificationPermissionGranted()) {
                dBinding.tvNotificationStatus.text = "✅ سیستم اعلان در این دستگاه فعال است"
                dBinding.tvNotificationStatus.setTextColor(android.graphics.Color.parseColor("#15803D"))
            } else {
                dBinding.tvNotificationStatus.text = "⚠️ مجوز اعلان‌ها غیرفعال است (لمس جهت فعال‌سازی)"
                dBinding.tvNotificationStatus.setTextColor(android.graphics.Color.parseColor("#B45309"))
            }
        }
        updateNotificationPermissionStatus()

        dBinding.btnTestNotification.setOnClickListener {
            if (!isNotificationPermissionGranted()) {
                checkAndRequestNotificationPermission()
                Toast.makeText(this, "لطفاً ابتدا مجوز ارسال اعلان را تایید نمایید.", Toast.LENGTH_SHORT).show()
            } else {
                AutoReserveReceiver.sendTestNotification(this)
                Toast.makeText(this, "اعلان آزمایشی ارسال شد! نوار اعلان‌های بالای گوشی را بررسی نمایید.", Toast.LENGTH_LONG).show()
                updateNotificationPermissionStatus()
            }
        }

        dBinding.tvNotificationStatus.setOnClickListener {
            if (!isNotificationPermissionGranted()) {
                checkAndRequestNotificationPermission()
            }
        }

        // Remote Update & Ads Config Bindings
        dBinding.tvCurrentVersionBadge.text = "نسخه ${BuildConfig.VERSION_NAME}"
        dBinding.etUpdateUrl.setText(UpdateManager.getUpdateUrl(this))
        dBinding.etAdsUrl.setText(AdManager.getAdsUrl(this))

        dBinding.btnCheckAppUpdate.setOnClickListener {
            dBinding.btnCheckAppUpdate.isEnabled = false
            lifecycleScope.launch {
                try {
                    val info = UpdateManager.checkRemoteUpdate(this@MainActivity)
                    UpdateManager.promptUpdateIfNeeded(this@MainActivity, info, isManualCheck = true)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "خطا در بررسی بروزرسانی: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    dBinding.btnCheckAppUpdate.isEnabled = true
                }
            }
        }

        dBinding.btnSavePreferences.setOnClickListener {
            preferences.lunchPriority = dBinding.etLunchPriority.text.toString().split("،", ",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
            preferences.dinnerPriority = dBinding.etDinnerPriority.text.toString().split("،", ",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
            preferences.blacklist = dBinding.etBlacklist.text.toString().split("،", ",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
            preferences.skipIfNoPreferenceMatch = dBinding.cbSkipIfNoMatch.isChecked

            preferences.autoReserveScheduled = dBinding.swEnableSchedule.isChecked
            preferences.autoReserveDay = days[dBinding.spScheduleDay.selectedItemPosition.coerceIn(0, days.size - 1)]
            preferences.autoReserveHour = selectedHour
            preferences.autoReserveMinute = selectedMinute
            preferences.notifyOnDeficit = dBinding.cbNotifyOnDeficit.isChecked

            val newUpdateUrl = dBinding.etUpdateUrl.text?.toString() ?: ""
            val newAdsUrl = dBinding.etAdsUrl.text?.toString() ?: ""
            UpdateManager.setUpdateUrl(this, newUpdateUrl)
            AdManager.setAdsUrl(this, newAdsUrl)

            savePreferences()
            AutoReserveScheduler.scheduleOrUpdate(this, preferences)
            if (!preferences.autoReserveScheduled) {
                val spPrefs = getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
                spPrefs.edit().putBoolean("is_deficit_retry_active", false).apply()
            }

            dialog.dismiss()
            Toast.makeText(this, "تنظیمات اولویت‌ها، زمانبندی و بروزرسانی ذخیره شد.", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isNotificationPermissionGranted()) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showAutoReservePreviewDialog() {
        if (currentResHtml.isBlank()) {
            Toast.makeText(this, "ابتدا اطلاعات سلف بارگذاری شود.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                // Ensure the preview and auto-reserve plan always targets NEXT WEEK
                if (currentWeekOffset < 1) {
                    val (changed, nextHtml) = sfdClient.navigateReservationWeek(currentResHtml, "next")
                    if (changed && nextHtml.isNotBlank()) {
                        currentResHtml = nextHtml
                        currentWeekOffset = 1
                        studentInfo = HtmlParser.parseStudentInfo(currentResHtml)
                        updateHeaderInfo()
                        updateWeeklyMealList()
                    }
                }

                val targetMeals = listOf("ناهار", "شام")
                val planItems = mutableListOf<AutoReservePlanItem>()
                val allGlobalSelfs = HtmlParser.extractAllSelfOptions(currentResHtml, "ناهار")

                for (meal in targetMeals) {
                    val days = HtmlParser.parseSelfGridItems(currentResHtml, meal)
                    for (day in days) {
                        if (day.isDisabled || day.isReserved || day.foods.isEmpty()) continue

                        val (matched, unranked, _) = PreferenceEngine.rankFoodOptions(day.foods, meal, preferences)
                        val chosenFood = when {
                            matched.isNotEmpty() -> matched[0]
                            !preferences.skipIfNoPreferenceMatch && unranked.isNotEmpty() -> unranked[0]
                            else -> null
                        } ?: continue

                        val chosenIdx = day.foods.indexOfFirst { it.id == chosenFood.id }.coerceAtLeast(0)
                        val daySelfOptions = if (day.selfOptions.isNotEmpty()) day.selfOptions else allGlobalSelfs

                        val defaultSelfTitle = when (meal) {
                            "صبحانه" -> preferences.defaultBreakfastSelf
                            "شام" -> preferences.defaultDinnerSelf
                            else -> preferences.defaultLunchSelf
                        }

                        val preferredSelf = daySelfOptions.find {
                            it.title.trim() == defaultSelfTitle.trim() || it.title.contains(defaultSelfTitle)
                        } ?: daySelfOptions.firstOrNull()

                        planItems.add(
                            AutoReservePlanItem(
                                mealName = meal,
                                dayItem = day,
                                availableFoods = day.foods,
                                availableSelfs = daySelfOptions,
                                selectedFoodIndex = chosenIdx,
                                selectedSelfId = preferredSelf?.id ?: day.selectedSelfId,
                                selectedSelfTitle = preferredSelf?.title,
                                isSelectedForReservation = true
                            )
                        )
                    }
                }

                binding.swipeRefresh.isRefreshing = false

                if (planItems.isEmpty()) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("عدم یافتن وعده برای رزرو")
                        .setMessage("هیچ وعده جدیدی منطبق با اولویت‌های شما در این هفته یافت نشد.\n\nدر صورتی که مهلت هفته جاری به پایان رسیده است، از دکمه «هفته بعد» استفاده نمایید.")
                        .setPositiveButton("رفتن به هفته بعد") { _, _ ->
                            navigateWeek("next")
                        }
                        .setNegativeButton("بستن", null)
                        .show()
                    return@launch
                }

                renderAutoReservePreviewDialog(planItems)

            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this@MainActivity, "خطا در آماده‌سازی رزرو خودکار: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderAutoReservePreviewDialog(planItems: List<AutoReservePlanItem>) {
        val dBinding = DialogAutoReservePreviewBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dBinding.root)
            .create()

        fun updateSummary() {
            val selectedItems = planItems.filter { it.isSelectedForReservation && it.currentSelectedFood != null }
            val totalCost = selectedItems.sumOf { it.priceInt }
            val currentCredit = studentInfo?.selfCreditAmount ?: 0L

            dBinding.tvTotalMealsCount.text = "تعداد وعده‌ها: ${selectedItems.size} وعده"
            dBinding.tvTotalCost.text = "جمع کل: ${String.format("%,d", totalCost)} ریال"
            dBinding.tvTotalCostToman.text = "(معادل ${String.format("%,d", totalCost / 10)} تومان)"
            dBinding.tvCurrentBalance.text = "موجودی سلف شما: ${String.format("%,d", currentCredit)} ریال"

            if (selectedItems.isEmpty()) {
                dBinding.tvBalanceStatusBanner.text = "هیچ وعده‌ای انتخاب نشده است."
                dBinding.tvBalanceStatusBanner.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_warning)
                dBinding.tvBalanceStatusBanner.setTextColor(0xFFB45309.toInt())
                dBinding.btnConfirmPlan.isEnabled = false
                dBinding.btnConfirmPlan.text = "وعده‌ای انتخاب نشده"
            } else if (currentCredit >= totalCost) {
                val remainingTomans = (currentCredit - totalCost) / 10
                dBinding.tvBalanceStatusBanner.text = "✓ موجودی کافی است (باقیمانده پس از رزرو: ${String.format("%,d", remainingTomans)} تومان)"
                dBinding.tvBalanceStatusBanner.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_reserved)
                dBinding.tvBalanceStatusBanner.setTextColor(0xFF047857.toInt())
                dBinding.btnConfirmPlan.isEnabled = true
                dBinding.btnConfirmPlan.text = "تایید و ثبت نهایی رزروها"
                dBinding.btnConfirmPlan.setBackgroundColor(0xFF0A5C36.toInt())
            } else {
                val deficit = totalCost - currentCredit
                val deficitTomans = deficit / 10
                dBinding.tvBalanceStatusBanner.text = "⚠️ کسری موجودی: ${String.format("%,d", deficitTomans)} تومان | جهت تکمیل رزرو، حساب خود را شارژ کنید"
                dBinding.tvBalanceStatusBanner.setBackgroundResource(ir.ac.ui.sfd.R.drawable.bg_badge_warning)
                dBinding.tvBalanceStatusBanner.setTextColor(0xFFB45309.toInt())
                dBinding.btnConfirmPlan.isEnabled = true
                dBinding.btnConfirmPlan.text = "شارژ کسری در شاپرک"
                dBinding.btnConfirmPlan.setBackgroundColor(0xFFD97706.toInt())
            }
        }

        val planAdapter = AutoReservePlanAdapter(this, planItems) {
            updateSummary()
        }
        dBinding.rvPlanItems.layoutManager = LinearLayoutManager(this)
        dBinding.rvPlanItems.adapter = planAdapter

        updateSummary()

        dBinding.btnCancelPlan.setOnClickListener {
            dialog.dismiss()
        }

        dBinding.btnConfirmPlan.setOnClickListener {
            val selectedItems = planItems.filter { it.isSelectedForReservation && it.currentSelectedFood != null }
            if (selectedItems.isEmpty()) return@setOnClickListener

            val totalCost = selectedItems.sumOf { it.priceInt }
            val currentCredit = studentInfo?.selfCreditAmount ?: 0L

            if (currentCredit < totalCost) {
                val deficit = totalCost - currentCredit
                val suggested = PreferenceEngine.calculateSmartTopup(deficit)
                dialog.dismiss()
                launchInAppPayment("${sfdClient.baseUrl}/ePay/ePay_Payment.aspx", suggested, "شارژ کسری رزرو خودکار")
                return@setOnClickListener
            }

            dialog.dismiss()
            executePlanReservations(selectedItems)
        }

        dialog.show()
    }

    private fun executePlanReservations(selectedItems: List<AutoReservePlanItem>) {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                var successCount = 0
                var runningHtml = currentResHtml
                for (item in selectedItems) {
                    val food = item.currentSelectedFood ?: continue
                    val targetSelfId = item.selectedSelfId ?: item.dayItem.selectedSelfId
                    val (ok, _, newHtml) = sfdClient.reserveSelfService(runningHtml, item.mealName, item.dayItem, food, targetSelfId)
                    if (ok) {
                        successCount++
                        if (newHtml.isNotBlank()) {
                            runningHtml = newHtml
                        }
                    }
                }

                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                Toast.makeText(this@MainActivity, "تعداد $successCount وعده غذایی با موفقیت رزرو شد.", Toast.LENGTH_LONG).show()

                if (successCount > 0) {
                    AutoReserveScheduler.cancelHourlyRetry(this@MainActivity)
                    val spPrefs = getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE)
                    spPrefs.edit().putBoolean("is_deficit_retry_active", false).apply()
                }

                if (runningHtml.isNotBlank()) {
                    currentResHtml = runningHtml
                    studentInfo = HtmlParser.parseStudentInfo(currentResHtml)
                    updateHeaderInfo()
                    updateWeeklyMealList()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "خطا در ثبت رزروها: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showLoginAdIfAvailable(onFinished: () -> Unit) {
        lifecycleScope.launch {
            try {
                val adsCfg = AdManager.fetchAdsConfig(this@MainActivity)
                if (adsCfg != null && adsCfg.enabled) {
                    val loginAd = adsCfg.loginAd
                    if (loginAd != null && loginAd.imageUrl.isNotBlank()) {
                        AdManager.loadBitmap(loginAd.imageUrl)
                    }
                    AdManager.showLoginAdModal(this@MainActivity, adsCfg) {
                        onFinished()
                    }
                    return@launch
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            onFinished()
        }
    }

    private fun checkAppUpdate(isManual: Boolean) {
        lifecycleScope.launch {
            try {
                val info = UpdateManager.checkRemoteUpdate(this@MainActivity)
                UpdateManager.promptUpdateIfNeeded(this@MainActivity, info, isManualCheck = isManual)
            } catch (e: Exception) {
                if (isManual) {
                    Toast.makeText(this@MainActivity, "خطا در بررسی بروزرسانی: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupAds() {
        lifecycleScope.launch {
            try {
                val adsCfg = AdManager.fetchAdsConfig(this@MainActivity)
                if (adsCfg != null && adsCfg.enabled) {
                    val banner = adsCfg.bottomBanner
                    if (banner != null && banner.imageUrl.isNotBlank()) {
                        AdManager.loadBitmap(banner.imageUrl)
                    }
                    AdManager.bindBottomBanner(
                        activity = this@MainActivity,
                        container = binding.cardBottomBanner,
                        customLayout = binding.layoutBottomBannerCustom,
                        tvTitle = binding.tvBottomBannerTitle,
                        tvSub = binding.tvBottomBannerSub,
                        ivBanner = binding.ivBottomBanner,
                        btnCloseCustom = binding.btnDismissBottomBanner,
                        btnActionCustom = binding.btnBottomBannerAction,
                        yektanetLayout = binding.layoutBottomBannerYektanet,
                        wvYektanet = binding.wvBottomBannerYektanet,
                        btnCloseYektanet = binding.btnDismissYektanetBanner,
                        config = adsCfg
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
