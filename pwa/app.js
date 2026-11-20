/**
 * SFD PWA - Main Application Logic
 * Comprehensive client for sfd.ui.ac.ir via Cloudflare Worker proxy
 */

// Configuration & State
const state = {
  workerUrl: localStorage.getItem("sfd_worker_url") || "",
  cookie: localStorage.getItem("sfd_cookie") || "",
  rememberCreds: JSON.parse(localStorage.getItem("sfd_creds") || "null"),
  preferences: JSON.parse(localStorage.getItem("sfd_preferences") || "null") || {
    skipIfNoPreferenceMatch: true,
    lunchPriority: [
      "چلو کباب کوبیده",
      "زرشک پلو با مرغ",
      "چلو جوجه کباب",
      "چلو خورشت قورمه سبزی",
      "چلو خورشت قیمه"
    ],
    dinnerPriority: [
      "شنیسل مرغ",
      "پیتزا",
      "خوراک مرغ",
      "ساندویچ ماکارونی"
    ],
    blacklist: [
      "خوراک لوبیا",
      "کوکو سبزی"
    ],
    defaultLunchSelf: "اختران (برادران)",
    defaultDinnerSelf: "شهید فهمیده",
    defaultRestaurantId: "13"
  },
  studentInfo: null,
  currentTab: "self", // "self" | "cafeteria"
  selectedMeal: "ناهار", // "صبحانه" | "ناهار" | "شام"
  selectedCafMeal: "ناهار",
  selectedRestaurantId: "13",
  restaurants: {},
  selfHtml: "",
  cafHtml: "",
  selfDays: [],
  cafItems: [],
  viewState: "",
  viewStateGen: "",
  isLoading: false
};

// Utilities
function toPersianDigits(str) {
  if (str === null || str === undefined) return "";
  const farsiDigits = ["۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹"];
  return str.toString().replace(/\d/g, x => farsiDigits[x]);
}

function formatRial(amount) {
  if (!amount && amount !== 0) return "۰ ریال";
  return toPersianDigits(amount.toLocaleString()) + " ریال";
}

function parseRial(str) {
  if (!str) return 0;
  const digits = str.replace(/[^\d]/g, "");
  return digits ? parseInt(digits, 10) : 0;
}

function showToast(message, type = "info") {
  const container = document.getElementById("toast-container");
  if (!container) return;

  const toast = document.createElement("div");
  toast.className = `toast toast-${type}`;
  toast.innerHTML = `
    <span class="toast-icon">${type === "success" ? "✓" : type === "error" ? "✕" : "ℹ"}</span>
    <span class="toast-msg">${message}</span>
  `;
  container.appendChild(toast);

  setTimeout(() => {
    toast.classList.add("fade-out");
    setTimeout(() => toast.remove(), 400);
  }, 3500);
}

function showLoading(show, message = "در حال پردازش...") {
  state.isLoading = show;
  const loader = document.getElementById("global-loader");
  const msgEl = document.getElementById("global-loader-msg");
  if (loader) {
    loader.style.display = show ? "flex" : "none";
    if (msgEl) msgEl.textContent = message;
  }
}

// Text Normalization & Matching
function normalizePersian(text) {
  if (!text) return "";
  return text
    .replace(/ي/g, "ی")
    .replace(/ك/g, "ک")
    .replace(/ة/g, "ه")
    .replace(/\u200c/g, " ") // Half-space
    .replace(/[\u064B-\u065F]/g, "") // Diacritics
    .replace(/[\s\t\n\r]+/g, " ")
    .trim();
}

function normalizeSpelling(text) {
  let s = normalizePersian(text);
  const replacements = [
    ["خورشت", "خورش"],
    ["قورمه", "قرمه"],
    ["شنیسل", "شنسل"],
    ["اسپاگتی", "ماکارونی"],
    ["همبرگر", "برگر"]
  ];
  for (const [k, v] of replacements) {
    s = s.replace(new RegExp(k, "g"), v);
  }
  return s;
}

function matchesSearchTerm(candidateName, term) {
  const normCandidate = normalizeSpelling(candidateName);
  const normTerm = normalizeSpelling(term);
  if (normCandidate.includes(normTerm)) return true;

  const termWords = normTerm.split(" ").filter(w => w.length > 2 && !["چلو", "پلو", "خورش"].includes(w));
  if (termWords.length > 0 && termWords.every(w => normCandidate.includes(w))) {
    return true;
  }
  return false;
}

function isBlacklisted(foodName, blacklist) {
  for (const item of blacklist) {
    if (item && item.trim() && matchesSearchTerm(foodName, item)) {
      return true;
    }
  }
  return false;
}

// Network Layer (Cloudflare Worker Proxy)
class SFDAPI {
  static getTargetUrl(path) {
    let base = state.workerUrl.trim();
    if (base.endsWith("/")) base = base.slice(0, -1);
    const cleanPath = path.startsWith("/") ? path : `/${path}`;
    return `${base}${cleanPath}`;
  }

  static async request(path, options = {}) {
    if (!state.workerUrl) {
      throw new Error("آدرس Cloudflare Worker تنظیم نشده است. لطفاً در بخش تنظیمات آن را وارد کنید.");
    }

    const url = this.getTargetUrl(path);
    const headers = new Headers();

    // Attach Session Cookie
    if (state.cookie) {
      headers.set("X-SFD-Cookie", state.cookie);
    }
    if (options.referer) {
      headers.set("X-SFD-Referer", options.referer);
    }

    let body = options.body;
    if (options.data && typeof options.data === "object") {
      headers.set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
      const params = new URLSearchParams();
      for (const [k, v] of Object.entries(options.data)) {
        params.append(k, v);
      }
      body = params.toString();
    }

    const method = options.method || (body ? "POST" : "GET");

    try {
      const response = await fetch(url, {
        method,
        headers,
        body
      });

      // Update cookie store from X-SFD-Set-Cookie
      const newCookie = response.headers.get("X-SFD-Set-Cookie");
      if (newCookie) {
        this.mergeCookie(newCookie);
      }

      if (options.isBlob) {
        return await response.blob();
      }

      const text = await response.text();

      // Check if session expired
      if (this.isSessionExpired(text) && !path.includes("UserControls") && path !== "/") {
        console.warn("Session expired detected in response");
        // Open login dialog if not already on login
      }

      return text;
    } catch (err) {
      console.error(`Request to ${path} failed:`, err);
      throw err;
    }
  }

  static mergeCookie(newCookieStr) {
    if (!newCookieStr) return;
    const cookieMap = {};

    // Parse existing
    if (state.cookie) {
      state.cookie.split(";").forEach(c => {
        const parts = c.split("=");
        if (parts.length >= 2) cookieMap[parts[0].trim()] = parts.slice(1).join("=").trim();
      });
    }

    // Merge new
    newCookieStr.split(";").forEach(c => {
      const parts = c.split("=");
      if (parts.length >= 2) cookieMap[parts[0].trim()] = parts.slice(1).join("=").trim();
    });

    const combined = Object.entries(cookieMap).map(([k, v]) => `${k}=${v}`).join("; ");
    state.cookie = combined;
    localStorage.setItem("sfd_cookie", combined);
  }

  static isSessionExpired(html) {
    return html.includes("txtUsername") || html.includes("btnLogin") || html.includes("ورود سمت سرور");
  }

  static async initLoginSession() {
    const html = await this.request("/");
    const parser = new DOMParser();
    const doc = parser.parseFromString(html, "text/html");

    const vs = doc.querySelector("#__VIEWSTATE")?.value || "";
    const vsg = doc.querySelector("#__VIEWSTATEGENERATOR")?.value || "";

    state.viewState = vs;
    state.viewStateGen = vsg;
    return !!vs;
  }

  static async fetchCaptchaBlob() {
    const rnd = Date.now();
    return await this.request(`/UserControls/Captcha.ashx?rnd=${rnd}`, { isBlob: true });
  }

  static async login(username, password, captcha) {
    const postData = {
      "__VIEWSTATE": state.viewState,
      "__VIEWSTATEGENERATOR": state.viewStateGen,
      "__VIEWSTATEENCRYPTED": "",
      "txtUsernamePlain": username,
      "txtPasswordPlain": password,
      "txtUsername": username,
      "txtPassword": password,
      "txtCaptcha": captcha,
      "btnLogin": "ورود سمت سرور "
    };

    const respHtml = await this.request("/", {
      data: postData,
      referer: "https://sfd.ui.ac.ir/"
    });

    const err = HtmlParser.extractLoginError(respHtml);
    if (err) {
      throw new Error(err);
    }

    if (
      respHtml.includes("Reservation.aspx") ||
      respHtml.includes("خرید هفتگی غذای سلف") ||
      respHtml.includes("cphMain_lblWeekDate") ||
      respHtml.includes("cphMain")
    ) {
      return true;
    }

    throw new Error("ورود ناموفق بود. لطفاً نام کاربری و رمز عبور را بررسی فرمایید.");
  }
}

// HTML Parsers (DOMParser based)
class HtmlParser {
  static parseStudentInfo(html) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    let name = "نامشخص";
    let studentId = "نامشخص";
    let selfCredit = "۰ ریال";
    let restCredit = "۰ ریال";
    let weekRange = "نامشخص";

    const userMatch = html.match(/کاربر:\s*([^-]+)\s*-\s*([0-9]+)/);
    if (userMatch) {
      name = userMatch[1].trim();
      studentId = userMatch[2].trim();
    }

    const selfEl = doc.querySelector("[id*=lblCredit], [id*=SelfCredit]");
    if (selfEl && selfEl.textContent.trim()) {
      selfCredit = selfEl.textContent.trim();
    }

    const restEl = doc.querySelector("[id*=lblCafeteriaCredit], [id*=RestCredit]");
    if (restEl && restEl.textContent.trim()) {
      restCredit = restEl.textContent.trim();
    }

    const weekEl = doc.querySelector("#cphMain_lblWeekDate");
    if (weekEl) {
      weekRange = weekEl.textContent.trim();
    }

    return {
      name,
      studentId,
      selfCredit,
      restaurantCredit: restCredit,
      weekRange,
      selfAmount: parseRial(selfCredit),
      restAmount: parseRial(restCredit)
    };
  }

  static parseSelfGrid(html, mealName = "ناهار") {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const tableId = mealName === "صبحانه" ? "cphMain_grdReservationBreakfast" :
                    mealName === "شام" ? "cphMain_grdReservationDinner" : "cphMain_grdReservationLunch";

    const table = doc.querySelector(`#${tableId}`);
    if (!table) return [];

    const rows = table.querySelectorAll("tr");
    const days = [];

    for (let i = 1; i < rows.length; i++) {
      const r = rows[i];
      const cols = r.querySelectorAll("td");
      if (cols.length < 5) continue;

      const dayDate = cols[1].textContent.trim().replace(/\s+/g, " ");
      const chkInput = cols[0].querySelector("input[type=checkbox]") || r.querySelector("input[type=checkbox]");
      const chkName = chkInput ? chkInput.name : "";

      const isDisabled = !chkInput || chkInput.disabled;
      const isReserved = !!chkInput && chkInput.checked;

      // Self location select
      const selfSelect = cols[2].querySelector("select");
      const selfCtlName = selfSelect ? selfSelect.name : "";
      const selfOptions = [];
      let currentSelf = "-1";
      if (selfSelect) {
        selfSelect.querySelectorAll("option").forEach(opt => {
          const v = opt.value;
          const t = opt.textContent.trim();
          if (v !== "-1" && t && t !== "-") {
            selfOptions.push({ id: v, title: t });
          }
          if (opt.selected) currentSelf = v;
        });
      }

      // Foods select
      const foodSelect = cols[3].querySelector("select");
      const foodCtlName = foodSelect ? foodSelect.name : "";
      const foods = [];
      let reservedFoodText = "";

      if (foodSelect) {
        foodSelect.querySelectorAll("option").forEach(opt => {
          const v = opt.value;
          const fText = opt.textContent.trim();
          if (isReserved && opt.selected) {
            reservedFoodText = fText;
          }
          if (v === "-1" || !v) return;

          const pMatch = fText.match(/([0-9,]+)\s*ریال/);
          const priceInt = pMatch ? parseRial(pMatch[1]) : 0;
          const cleanName = fText.replace(/[0-9,]+\s*ریال/g, "").trim();

          foods.push({
            id: v,
            name: cleanName,
            fullName: fText,
            priceInt,
            priceStr: formatRial(priceInt)
          });
        });
      }

      // Sort foods by price
      foods.sort((a, b) => a.priceInt - b.priceInt);

      const countSelect = cols[4].querySelector("select");
      const countCtlName = countSelect ? countSelect.name : "";

      let reservedSelfTitle = "";
      if (selfSelect) {
        const selOpt = selfSelect.querySelector("option[selected]");
        if (selOpt) reservedSelfTitle = selOpt.textContent.trim();
      }
      if (!reservedSelfTitle) {
        const rawSelf = cols[2].textContent.trim();
        if (rawSelf && rawSelf !== "-") reservedSelfTitle = rawSelf;
      }

      days.push({
        dayDate,
        isDisabled,
        isReserved,
        reservedFood: reservedFoodText,
        chkName,
        selfCtlName,
        currentSelf,
        foodCtlName,
        countCtlName,
        selfOptions,
        foods,
        reservedSelfTitle,
        selectedFoodId: foods[0]?.id || "",
        selectedSelfId: currentSelf !== "-1" ? currentSelf : (selfOptions[0]?.id || "")
      });
    }

    return days;
  }

  static parseCafeteria(html, mealName = "ناهار") {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const tableId = mealName === "صبحانه" ? "cphMain_grdBreakfast" :
                    mealName === "شام" ? "cphMain_grdDinner" : "cphMain_grdLunch";

    const table = doc.querySelector(`#${tableId}`);
    if (!table) return [];

    const rows = table.querySelectorAll("tr");
    const items = [];

    for (let i = 1; i < rows.length; i++) {
      const r = rows[i];
      const cols = r.querySelectorAll("td, th");
      if (cols.length > 6) {
        const foodName = cols[1].textContent.trim();
        if (!foodName || foodName.includes("داده ای برای نمایش")) continue;

        const priceInt = parseRial(cols[2].textContent);
        const remStr = cols[6].textContent.trim();
        const remCount = parseInt(remStr, 10) || 0;

        const chkInput = r.querySelector("input[type=checkbox]");
        const chkName = chkInput ? chkInput.name : "";
        const isReserved = r.innerHTML.includes("chkReserve") && r.innerHTML.includes('checked="checked"') ||
                           r.innerHTML.includes("hdnIsReserved") && r.innerHTML.includes('value="True"');

        const countSelect = r.querySelector("select[name*=drpNormalCount]");
        const countName = countSelect ? countSelect.name : "";

        items.push({
          name: foodName,
          priceInt,
          priceStr: formatRial(priceInt),
          remaining: remCount,
          isAvailable: remCount > 0,
          isReserved,
          chkName,
          countName
        });
      }
    }

    items.sort((a, b) => {
      if (a.isAvailable && !b.isAvailable) return -1;
      if (!a.isAvailable && b.isAvailable) return 1;
      return a.priceInt - b.priceInt;
    });

    return items;
  }

  static parseRestaurants(html, mealName = "ناهار") {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const suffix = mealName === "شام" ? "Dinner" : mealName === "صبحانه" ? "Breakfast" : "Lunch";
    const select = doc.querySelector(`select[name*=drpSelfTitle_${suffix}]`) ||
                   doc.querySelector(`select[name*=drpSelfTitle]`);

    if (!select) return { map: {}, selected: null };

    const map = {};
    let selected = null;
    select.querySelectorAll("option").forEach(opt => {
      const v = opt.value;
      const t = opt.textContent.trim();
      if (v !== "-1" && t) {
        map[v] = t;
      }
      if (opt.selected) selected = v;
    });

    return { map, selected };
  }

  static extractFormData(html) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const data = {};

    doc.querySelectorAll("input").forEach(input => {
      const name = input.name;
      const type = (input.type || "").toLowerCase();
      if (!name) return;

      if (type === "checkbox") {
        if (input.checked) data[name] = "on";
      } else {
        data[name] = input.value || "";
      }
    });

    doc.querySelectorAll("select").forEach(select => {
      const name = select.name;
      const selOpt = select.querySelector("option[selected]") || select.querySelector("option");
      if (name && selOpt) {
        data[name] = selOpt.value;
      }
    });

    // Remove action buttons
    for (const key of Object.keys(data)) {
      if (["btnSave", "imgbtn", "btnOk", "btnLogin", "btnPay"].some(btn => key.includes(btn))) {
        delete data[key];
      }
    }

    return data;
  }

  static extractLoginError(html) {
    const doc = new DOMParser().parseFromString(html, "text/html");
    const loginErr = doc.querySelector("#lblLoginError, #cphMain_lblLoginError, #lblError, #cphMain_lblError, .alert-danger, .error-message");
    if (loginErr) {
      const t = loginErr.textContent.trim();
      if (t && t !== "-" && !t.includes("لطفا نام کاربری") && !t.includes("لطفا کد امنیتی")) {
        return t;
      }
    }

    const toastMatch = html.match(/toastify\([^)]*'([^']*خطا[^']*)'/);
    if (toastMatch) return toastMatch[1];

    if (html.includes("کد امنیتی صحیح نمی باشد") || html.includes("کد امنیتی اشتباه است")) {
      return "کد امنیتی اشتباه است. لطفاً کد جدید تصویر را وارد فرمایید.";
    }
    if (html.includes("نام کاربری یا کلمه عبور اشتباه است") || html.includes("نام کاربری یا کلمه عبور صحیح نمی باشد")) {
      return "نام کاربری یا کلمه عبور اشتباه است.";
    }
    if (html.includes("کلمه عبور اشتباه است")) {
      return "کلمه عبور وارد شده اشتباه است.";
    }
    if (html.includes("خطا در کد امنیتی")) {
      return "کد امنیتی منقضی شده است. لطفاً کد جدید را وارد نمایید.";
    }
    if (html.includes("حساب کاربری شما غیرفعال است") || html.includes("کاربر مسدود است")) {
      return "حساب کاربری شما در سامانه مسدود یا غیرفعال است.";
    }
    if ((html.includes("txtUsername") || html.includes("btnLogin")) && !html.includes("Reservation.aspx") && !html.includes("cphMain_lblWeekDate")) {
      return "احراز هویت انجام نشد. لطفاً نام کاربری، رمز عبور و کد امنیتی را بررسی نمایید.";
    }
    return null;
  }
}

// UI Controller
class AppUI {
  static init() {
    this.bindEvents();
    this.checkWorkerConfig();
  }

  static checkWorkerConfig() {
    if (!state.workerUrl) {
      this.openSettingsModal(true);
    } else if (state.cookie) {
      this.loadDashboard();
    } else {
      this.openLoginModal();
    }
  }

  static bindEvents() {
    // Tab switching (Self vs Cafeteria)
    document.querySelectorAll(".segmented-btn").forEach(btn => {
      btn.addEventListener("click", e => {
        document.querySelectorAll(".segmented-btn").forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        state.currentTab = btn.dataset.tab;
        this.renderView();
      });
    });

    // Meal chips switching
    document.querySelectorAll(".chip-meal").forEach(chip => {
      chip.addEventListener("click", e => {
        document.querySelectorAll(".chip-meal").forEach(c => c.classList.remove("active"));
        chip.classList.add("active");
        state.selectedMeal = chip.dataset.meal;
        if (state.currentTab === "self") {
          this.renderSelfGrid();
        } else {
          this.fetchCafeteria();
        }
      });
    });

    // Top action buttons
    document.getElementById("btn-refresh")?.addEventListener("click", () => this.loadDashboard(true));
    document.getElementById("btn-settings")?.addEventListener("click", () => this.openSettingsModal());
    document.getElementById("btn-logout")?.addEventListener("click", () => this.handleLogout());

    // Navigation weeks
    document.getElementById("btn-prev-week")?.addEventListener("click", () => this.navigateWeek("prev"));
    document.getElementById("btn-next-week")?.addEventListener("click", () => this.navigateWeek("next"));

    // Quick Smart Auto-Reserve button
    document.getElementById("btn-smart-auto-reserve")?.addEventListener("click", () => this.openAutoReservePlan());

    // Restaurant dropdown for Cafeteria
    document.getElementById("select-restaurant")?.addEventListener("change", e => {
      state.selectedRestaurantId = e.target.value;
      this.switchRestaurant(e.target.value);
    });

    // Topup balance triggers
    document.getElementById("btn-topup-self")?.addEventListener("click", () => this.openTopupModal("self"));
    document.getElementById("btn-topup-caf")?.addEventListener("click", () => this.openTopupModal("cafeteria"));
  }

  static async loadDashboard(isManualRefresh = false) {
    if (!state.cookie) {
      this.openLoginModal();
      return;
    }

    showLoading(true, "در حال بارگذاری اطلاعات سلف...");
    try {
      const [selfHtml, cafHtml] = await Promise.all([
        SFDAPI.request("/Reservation/Reservation.aspx"),
        SFDAPI.request("/Reservation/CafeteriaReservation.aspx")
      ]);

      if (SFDAPI.isSessionExpired(selfHtml)) {
        showToast("نشست شما منقضی شده است. لطفاً مجدداً وارد شوید.", "error");
        this.openLoginModal();
        return;
      }

      state.selfHtml = selfHtml;
      state.cafHtml = cafHtml;

      // Extract Student info
      state.studentInfo = HtmlParser.parseStudentInfo(selfHtml);
      this.updateHeaderInfo();

      // Extract restaurants
      const restData = HtmlParser.parseRestaurants(cafHtml, state.selectedMeal);
      state.restaurants = restData.map;
      if (restData.selected) state.selectedRestaurantId = restData.selected;
      this.renderRestaurantSelect();

      this.renderView();
      if (isManualRefresh) {
        showToast("اطلاعات با موفقیت به‌روزرسانی شد.", "success");
      }
    } catch (e) {
      showToast(e.message || "خطا در اتصال به سامانه دانشگاه", "error");
    } finally {
      showLoading(false);
    }
  }

  static updateHeaderInfo() {
    const info = state.studentInfo;
    if (!info) return;

    document.getElementById("user-name").textContent = info.name;
    document.getElementById("user-id").textContent = toPersianDigits(info.studentId);
    document.getElementById("balance-self").textContent = info.selfCredit;
    document.getElementById("balance-caf").textContent = info.restaurantCredit;
    document.getElementById("week-label").textContent = info.weekRange;
  }

  static renderRestaurantSelect() {
    const select = document.getElementById("select-restaurant");
    if (!select) return;
    select.innerHTML = "";

    for (const [id, title] of Object.entries(state.restaurants)) {
      const opt = document.createElement("option");
      opt.value = id;
      opt.textContent = title;
      if (id === state.selectedRestaurantId) opt.selected = true;
      select.appendChild(opt);
    }
  }

  static renderView() {
    const selfView = document.getElementById("view-self");
    const cafView = document.getElementById("view-cafeteria");
    const restSelectBox = document.getElementById("box-restaurant-select");
    const weekNavBox = document.getElementById("box-week-nav");

    if (state.currentTab === "self") {
      selfView.style.display = "block";
      cafView.style.display = "none";
      if (restSelectBox) restSelectBox.style.display = "none";
      if (weekNavBox) weekNavBox.style.display = "flex";
      this.renderSelfGrid();
    } else {
      selfView.style.display = "none";
      cafView.style.display = "block";
      if (restSelectBox) restSelectBox.style.display = "block";
      if (weekNavBox) weekNavBox.style.display = "none";
      this.renderCafeteriaGrid();
    }
  }

  static renderSelfGrid() {
    state.selfDays = HtmlParser.parseSelfGrid(state.selfHtml, state.selectedMeal);
    const container = document.getElementById("self-days-list");
    if (!container) return;
    container.innerHTML = "";

    if (state.selfDays.length === 0) {
      container.innerHTML = `<div class="empty-state">غذایی برای وعده ${state.selectedMeal} در این هفته تعریف نشده است.</div>`;
      return;
    }

    state.selfDays.forEach((day, idx) => {
      const card = document.createElement("div");
      card.className = `day-card ${day.isReserved ? "reserved" : ""} ${day.isDisabled ? "disabled" : ""}`;

      let statusBadge = "";
      if (day.isReserved) {
        statusBadge = `<span class="badge badge-success">✓ رزرو شده</span>`;
      } else if (day.isDisabled) {
        statusBadge = `<span class="badge badge-secondary">غیرفعال / منقضی</span>`;
      } else {
        statusBadge = `<span class="badge badge-primary">قابل رزرو</span>`;
      }

      let foodsOptionsHtml = "";
      day.foods.forEach(f => {
        foodsOptionsHtml += `<option value="${f.id}" ${f.id === day.selectedFoodId ? "selected" : ""}>${f.name} (${f.priceStr})</option>`;
      });

      let selfLocationsHtml = "";
      day.selfOptions.forEach(s => {
        selfLocationsHtml += `<option value="${s.id}" ${s.id === day.selectedSelfId ? "selected" : ""}>${s.title}</option>`;
      });

      card.innerHTML = `
        <div class="day-card-header">
          <div class="day-title-box">
            <span class="day-title">${day.dayDate}</span>
            ${statusBadge}
          </div>
          ${day.isReserved ? `<div class="reserved-info">غذای رزرو شده: <strong>${day.reservedFood}</strong></div>` : ""}
        </div>

        ${!day.isDisabled && !day.isReserved ? `
          <div class="day-card-body">
            <div class="form-group">
              <label>انتخاب غذا:</label>
              <select class="input-select select-food" data-idx="${idx}">
                ${foodsOptionsHtml}
              </select>
            </div>
            ${day.selfOptions.length > 0 ? `
              <div class="form-group">
                <label>محل تحویل (سلف):</label>
                <select class="input-select select-self" data-idx="${idx}">
                  ${selfLocationsHtml}
                </select>
              </div>
            ` : ""}
            <button class="btn btn-primary btn-reserve-day" data-idx="${idx}">ثبت رزرو غذا</button>
          </div>
        ` : ""}

        ${day.isReserved && !day.isDisabled ? `
          <div class="day-card-footer">
            <button class="btn btn-danger-outline btn-cancel-day" data-idx="${idx}">لغو رزرو این وعده</button>
          </div>
        ` : ""}
      `;

      container.appendChild(card);
    });

    // Bind item events
    container.querySelectorAll(".select-food").forEach(s => {
      s.addEventListener("change", e => {
        const idx = parseInt(e.target.dataset.idx, 10);
        state.selfDays[idx].selectedFoodId = e.target.value;
      });
    });

    container.querySelectorAll(".select-self").forEach(s => {
      s.addEventListener("change", e => {
        const idx = parseInt(e.target.dataset.idx, 10);
        state.selfDays[idx].selectedSelfId = e.target.value;
      });
    });

    container.querySelectorAll(".btn-reserve-day").forEach(b => {
      b.addEventListener("click", e => {
        const idx = parseInt(e.target.dataset.idx, 10);
        this.executeSingleSelfReserve(state.selfDays[idx]);
      });
    });

    container.querySelectorAll(".btn-cancel-day").forEach(b => {
      b.addEventListener("click", e => {
        const idx = parseInt(e.target.dataset.idx, 10);
        this.executeSingleSelfCancel(state.selfDays[idx]);
      });
    });
  }

  static renderCafeteriaGrid() {
    state.cafItems = HtmlParser.parseCafeteria(state.cafHtml, state.selectedMeal);
    const container = document.getElementById("caf-items-list");
    if (!container) return;
    container.innerHTML = "";

    if (state.cafItems.length === 0) {
      container.innerHTML = `<div class="empty-state">هیچ غذایی در رستوران انتخاب‌شده برای این وعده موجود نیست.</div>`;
      return;
    }

    state.cafItems.forEach((item, idx) => {
      const card = document.createElement("div");
      card.className = `food-card ${!item.isAvailable ? "sold-out" : ""} ${item.isReserved ? "reserved" : ""}`;

      let badge = "";
      if (item.isReserved) {
        badge = `<span class="badge badge-success">✓ رزرو شده</span>`;
      } else if (!item.isAvailable) {
        badge = `<span class="badge badge-secondary">ظرفیت تکمیل</span>`;
      } else {
        badge = `<span class="badge badge-warning">ظرفیت: ${toPersianDigits(item.remaining)}</span>`;
      }

      card.innerHTML = `
        <div class="food-card-content">
          <div class="food-name">${item.name}</div>
          <div class="food-meta">
            <span class="food-price">${item.priceStr}</span>
            ${badge}
          </div>
        </div>
        <div class="food-card-action">
          ${item.isAvailable && !item.isReserved ? `
            <button class="btn btn-primary btn-reserve-caf" data-idx="${idx}">رزرو</button>
          ` : ""}
        </div>
      `;

      container.appendChild(card);
    });

    container.querySelectorAll(".btn-reserve-caf").forEach(b => {
      b.addEventListener("click", e => {
        const idx = parseInt(e.target.dataset.idx, 10);
        this.executeCafeteriaReserve(state.cafItems[idx]);
      });
    });
  }

  static async navigateWeek(direction) {
    showLoading(true, "در حال تغییر هفته...");
    try {
      const formData = HtmlParser.extractFormData(state.selfHtml);
      const btnName = direction === "next" ? "ctl00$cphMain$imgbtnNextWeek" : "ctl00$cphMain$imgbtnLastWeek";
      formData[`${btnName}.x`] = "18";
      formData[`${btnName}.y`] = "18";
      formData["__EVENTTARGET"] = "";
      formData["__EVENTARGUMENT"] = "";

      const newHtml = await SFDAPI.request("/Reservation/Reservation.aspx", {
        data: formData,
        referer: "https://sfd.ui.ac.ir/Reservation/Reservation.aspx"
      });

      state.selfHtml = newHtml;
      state.studentInfo = HtmlParser.parseStudentInfo(newHtml);
      this.updateHeaderInfo();
      this.renderSelfGrid();
      showToast("هفته با موفقیت تغییر یافت.", "success");
    } catch (e) {
      showToast("خطا در تغییر هفته: " + e.message, "error");
    } finally {
      showLoading(false);
    }
  }

  static async switchRestaurant(restaurantId) {
    showLoading(true, "در حال تغییر رستوران...");
    try {
      const formData = HtmlParser.extractFormData(state.cafHtml);
      const suffix = state.selectedMeal === "شام" ? "Dinner" : state.selectedMeal === "صبحانه" ? "Breakfast" : "Lunch";
      const selectName = `ctl00$cphMain$drpSelfTitle_${suffix}`;

      formData[selectName] = restaurantId;
      formData["__EVENTTARGET"] = selectName;
      formData["__EVENTARGUMENT"] = "";

      const newHtml = await SFDAPI.request("/Reservation/CafeteriaReservation.aspx", {
        data: formData,
        referer: "https://sfd.ui.ac.ir/Reservation/CafeteriaReservation.aspx"
      });

      state.cafHtml = newHtml;
      this.renderCafeteriaGrid();
    } catch (e) {
      showToast("خطا در بارگذاری رستوران: " + e.message, "error");
    } finally {
      showLoading(false);
    }
  }

  static async executeSingleSelfReserve(day) {
    const selectedFood = day.foods.find(f => f.id === day.selectedFoodId) || day.foods[0];
    if (!selectedFood) return;

    // Check balance
    if (state.studentInfo && state.studentInfo.selfAmount < selectedFood.priceInt) {
      const deficit = selectedFood.priceInt - state.studentInfo.selfAmount;
      if (confirm(`موجودی سلف شما کافی نیست.\nکسری: ${formatRial(deficit)}\n\nآیا مایل به شارژ حساب هستید؟`)) {
        this.openTopupModal("self", deficit);
      }
      return;
    }

    showLoading(true, `در حال رزرو ${selectedFood.name}...`);
    try {
      const formData = HtmlParser.extractFormData(state.selfHtml);
      if (day.chkName) formData[day.chkName] = "on";
      if (day.foodCtlName) formData[day.foodCtlName] = selectedFood.id;
      if (day.countCtlName) formData[day.countCtlName] = "1";
      if (day.selfCtlName && day.selectedSelfId) formData[day.selfCtlName] = day.selectedSelfId;

      const btnKey = state.selectedMeal === "صبحانه" ? "ctl00$cphMain$btnSaveBreakfast" :
                     state.selectedMeal === "شام" ? "ctl00$cphMain$btnSaveDinner" : "ctl00$cphMain$btnSaveLunch";
      const btnVal = state.selectedMeal === "صبحانه" ? "ذخیره صبحانه" :
                     state.selectedMeal === "شام" ? "ذخیره شام" : "ذخیره ناهار";
      formData[btnKey] = btnVal;
      formData["__EVENTTARGET"] = "";
      formData["__EVENTARGUMENT"] = "";

      const respHtml = await SFDAPI.request("/Reservation/Reservation.aspx", {
        data: formData,
        referer: "https://sfd.ui.ac.ir/Reservation/Reservation.aspx"
      });

      const toastErr = HtmlParser.extractLoginError(respHtml);
      if (toastErr) {
        showToast(toastErr, "error");
      } else {
        showToast("غذای انتخابی با موفقیت رزرو گردید.", "success");
      }

      state.selfHtml = respHtml;
      state.studentInfo = HtmlParser.parseStudentInfo(respHtml);
      this.updateHeaderInfo();
      this.renderSelfGrid();
    } catch (e) {
      showToast("خطا در رزرو: " + e.message, "error");
    } finally {
      showLoading(false);
    }
  }

  static async executeSingleSelfCancel(day) {
    if (!confirm(`آیا از لغو رزرو روز ${day.dayDate} اطمینان دارید؟`)) return;

    showLoading(true, "در حال لغو رزرو...");
    try {
      const formData = HtmlParser.extractFormData(state.selfHtml);
      if (day.chkName) delete formData[day.chkName]; // uncheck target

      const btnKey = state.selectedMeal === "صبحانه" ? "ctl00$cphMain$btnSaveBreakfast" :
                     state.selectedMeal === "شام" ? "ctl00$cphMain$btnSaveDinner" : "ctl00$cphMain$btnSaveLunch";
      const btnVal = state.selectedMeal === "صبحانه" ? "ذخیره صبحانه" :
                     state.selectedMeal === "شام" ? "ذخیره شام" : "ذخیره ناهار";
      formData[btnKey] = btnVal;
      formData["__EVENTTARGET"] = "";
      formData["__EVENTARGUMENT"] = "";

      const respHtml = await SFDAPI.request("/Reservation/Reservation.aspx", {
        data: formData,
        referer: "https://sfd.ui.ac.ir/Reservation/Reservation.aspx"
      });

      showToast("رزرو با موفقیت لغو شد و هزینه بازگردانده شد.", "success");
      state.selfHtml = respHtml;
      state.studentInfo = HtmlParser.parseStudentInfo(respHtml);
      this.updateHeaderInfo();
      this.renderSelfGrid();
    } catch (e) {
      showToast("خطا در لغو رزرو: " + e.message, "error");
    } finally {
      showLoading(false);
    }
  }

  static async executeCafeteriaReserve(item) {
    showLoading(true, `در حال ثبت سفارش ${item.name}...`);
    try {
      const formData = HtmlParser.extractFormData(state.cafHtml);
      if (item.chkName) formData[item.chkName] = "on";
      if (item.countName) formData[item.countName] = "1";

      const btnKey = state.selectedMeal === "صبحانه" ? "ctl00$cphMain$btnSaveBreakfast" :
                     state.selectedMeal === "شام" ? "ctl00$cphMain$btnSaveDinner" : "ctl00$cphMain$btnSaveLunch";
      formData[btnKey] = "ذخیره ";
      formData["__EVENTTARGET"] = "";
      formData["__EVENTARGUMENT"] = "";

      const respHtml = await SFDAPI.request("/Reservation/CafeteriaReservation.aspx", {
        data: formData,
        referer: "https://sfd.ui.ac.ir/Reservation/CafeteriaReservation.aspx"
      });

      // Check if requires bank payment
      const cidMatch = respHtml.match(/CafeteriaReservation_SelectBank\.aspx\?CreditId=(\d+)/);
      if (cidMatch) {
        const creditId = cidMatch[1];
        const payUrl = `https://sfd.ui.ac.ir/Reservation/CafeteriaReservation_SelectBank.aspx?CreditId=${creditId}&ReserveType=daily`;
        showToast("سفارش موقت ثبت شد. هدایت به درگاه پرداخت...", "info");
        window.open(payUrl, "_blank");
      } else {
        showToast("سفارش شما در رستوران ثبت شد.", "success");
      }

      state.cafHtml = respHtml;
      this.renderCafeteriaGrid();
    } catch (e) {
      showToast("خطا در سفارش: " + e.message, "error");
    } finally {
      showLoading(false);
    }
  }

  // Smart Auto-Reserve Plan Algorithm
  static openAutoReservePlan() {
    if (state.currentTab !== "self") {
      showToast("رزرو هوشمند هفتگی مخصوص سلف عادی دانشگاه است.", "info");
      return;
    }

    const priorityList = state.selectedMeal === "شام" ? state.preferences.dinnerPriority : state.preferences.lunchPriority;
    const planItems = [];
    let totalCost = 0;

    for (const day of state.selfDays) {
      if (day.isDisabled || day.isReserved || day.foods.length === 0) continue;

      let chosenFood = null;
      let matchedTerm = null;

      // 1. Try matching priority
      for (const term of priorityList) {
        const found = day.foods.find(f => !isBlacklisted(f.name, state.preferences.blacklist) && matchesSearchTerm(f.name, term));
        if (found) {
          chosenFood = found;
          matchedTerm = term;
          break;
        }
      }

      // 2. If no priority match, pick cheapest non-blacklisted (unless skipped)
      if (!chosenFood && !state.preferences.skipIfNoPreferenceMatch) {
        chosenFood = day.foods.find(f => !isBlacklisted(f.name, state.preferences.blacklist)) || day.foods[0];
      }

      if (chosenFood) {
        totalCost += chosenFood.priceInt;
        planItems.push({
          day,
          food: chosenFood,
          matchedTerm: matchedTerm || "پیش‌فرض",
          selfId: day.selectedSelfId
        });
      }
    }

    if (planItems.length === 0) {
      alert("هیچ وعده جدیدی برای رزرو خودکار یافت نشد (تمام روزها رزرو شده‌اند یا با اولویت‌های شما مطابقت ندارند).");
      return;
    }

    const modal = document.getElementById("modal-plan");
    const container = document.getElementById("plan-items-container");
    container.innerHTML = "";

    planItems.forEach(item => {
      const row = document.createElement("div");
      row.className = "plan-row";
      row.innerHTML = `
        <div class="plan-row-title">
          <strong>${item.day.dayDate}</strong>
          <span class="plan-food-name">${item.food.name}</span>
        </div>
        <div class="plan-row-meta">
          <span class="plan-price">${item.food.priceStr}</span>
          <span class="plan-badge">${item.matchedTerm}</span>
        </div>
      `;
      container.appendChild(row);
    });

    const currentCredit = state.studentInfo?.selfAmount || 0;
    const deficit = totalCost - currentCredit;

    document.getElementById("plan-total-cost").textContent = formatRial(totalCost);
    document.getElementById("plan-current-credit").textContent = formatRial(currentCredit);

    const deficitBox = document.getElementById("plan-deficit-box");
    const btnExecute = document.getElementById("btn-execute-plan");

    if (deficit > 0) {
      deficitBox.style.display = "block";
      document.getElementById("plan-deficit-amount").textContent = formatRial(deficit);
      btnExecute.textContent = "افزایش اعتبار سلف و رزرو";
      btnExecute.onclick = () => {
        this.closeModal("modal-plan");
        this.openTopupModal("self", deficit);
      };
    } else {
      deficitBox.style.display = "none";
      btnExecute.textContent = `تایید و رزرو (${toPersianDigits(planItems.length)} وعده)`;
      btnExecute.onclick = () => {
        this.closeModal("modal-plan");
        this.executeSmartPlan(planItems);
      };
    }

    modal.style.display = "flex";
  }

  static async executeSmartPlan(planItems) {
    showLoading(true, `در حال رزرو هوشمند ${toPersianDigits(planItems.length)} وعده...`);
    let successCount = 0;

    for (let i = 0; i < planItems.length; i++) {
      const item = planItems[i];
      showLoading(true, `رزرو (${toPersianDigits(i + 1)} از ${toPersianDigits(planItems.length)}): ${item.food.name}`);

      try {
        const formData = HtmlParser.extractFormData(state.selfHtml);
        if (item.day.chkName) formData[item.day.chkName] = "on";
        if (item.day.foodCtlName) formData[item.day.foodCtlName] = item.food.id;
        if (item.day.countCtlName) formData[item.day.countCtlName] = "1";
        if (item.day.selfCtlName && item.selfId) formData[item.day.selfCtlName] = item.selfId;

        const btnKey = state.selectedMeal === "صبحانه" ? "ctl00$cphMain$btnSaveBreakfast" :
                       state.selectedMeal === "شام" ? "ctl00$cphMain$btnSaveDinner" : "ctl00$cphMain$btnSaveLunch";
        const btnVal = state.selectedMeal === "صبحانه" ? "ذخیره صبحانه" :
                       state.selectedMeal === "شام" ? "ذخیره شام" : "ذخیره ناهار";
        formData[btnKey] = btnVal;

        const respHtml = await SFDAPI.request("/Reservation/Reservation.aspx", {
          data: formData,
          referer: "https://sfd.ui.ac.ir/Reservation/Reservation.aspx"
        });

        state.selfHtml = respHtml;
        successCount++;
        // Small delay between requests
        await new Promise(r => setTimeout(r, 600));
      } catch (err) {
        console.error("Failed to reserve day:", item.day.dayDate, err);
      }
    }

    showLoading(false);
    showToast(`${toPersianDigits(successCount)} وعده با موفقیت رزرو شد!`, "success");
    state.studentInfo = HtmlParser.parseStudentInfo(state.selfHtml);
    this.updateHeaderInfo();
    this.renderSelfGrid();
  }

  // Modals & Authentication
  static async openLoginModal() {
    const modal = document.getElementById("modal-login");
    modal.style.display = "flex";

    // Fill saved creds if exists
    if (state.rememberCreds) {
      document.getElementById("login-username").value = state.rememberCreds.username || "";
      document.getElementById("login-password").value = state.rememberCreds.password || "";
      document.getElementById("login-remember").checked = true;
    }

    await this.refreshCaptcha();

    // Login submit
    document.getElementById("form-login").onsubmit = async e => {
      e.preventDefault();
      const u = document.getElementById("login-username").value.trim();
      const p = document.getElementById("login-password").value.trim();
      const c = document.getElementById("login-captcha").value.trim();
      const remember = document.getElementById("login-remember").checked;

      if (!u || !p || !c) {
        showToast("لطفاً تمامی فیلدها را وارد نمایید.", "error");
        return;
      }

      showLoading(true, "در حال احراز هویت و ورود...");
      try {
        await SFDAPI.login(u, p, c);
        showToast("ورود با موفقیت انجام شد.", "success");

        if (remember) {
          localStorage.setItem("sfd_creds", JSON.stringify({ username: u, password: p }));
        } else {
          localStorage.removeItem("sfd_creds");
        }

        this.closeModal("modal-login");
        this.loadDashboard();
      } catch (err) {
        showToast(err.message, "error");
        await this.refreshCaptcha();
      } finally {
        showLoading(false);
      }
    };

    document.getElementById("btn-refresh-captcha")?.addEventListener("click", () => this.refreshCaptcha());
  }

  static async refreshCaptcha() {
    const imgEl = document.getElementById("captcha-img");
    const inputEl = document.getElementById("login-captcha");
    const hintEl = document.getElementById("captcha-ocr-hint");

    if (hintEl) hintEl.textContent = "در حال دریافت و حل خودکار کپچا...";
    try {
      await SFDAPI.initLoginSession();
      const blob = await SFDAPI.fetchCaptchaBlob();
      imgEl.src = URL.createObjectURL(blob);

      // Solve locally with ocr.js
      const result = await window.captchaOcr.solve(blob);
      if (result && result.code && result.code.length === 4) {
        inputEl.value = result.code;
        if (hintEl) hintEl.innerHTML = `کد ۴ رقمی شناسایی شد: <strong>${result.code}</strong> (دقت: ${(result.confidence * 100).toFixed(0)}٪)`;
      } else {
        if (hintEl) hintEl.textContent = "کپچا به صورت دستی وارد شود.";
      }
    } catch (e) {
      if (hintEl) hintEl.textContent = "خطا در اتصال به ورکر یا دریافت کپچا.";
    }
  }

  static handleLogout() {
    if (!confirm("آیا مایل به خروج از حساب کاربری هستید؟")) return;
    state.cookie = "";
    localStorage.removeItem("sfd_cookie");
    this.openLoginModal();
  }

  static openSettingsModal(forceWorkerSetup = false) {
    const modal = document.getElementById("modal-settings");
    modal.style.display = "flex";

    document.getElementById("setting-worker-url").value = state.workerUrl;
    document.getElementById("setting-lunch-priority").value = state.preferences.lunchPriority.join("\n");
    document.getElementById("setting-dinner-priority").value = state.preferences.dinnerPriority.join("\n");
    document.getElementById("setting-blacklist").value = state.preferences.blacklist.join("\n");
    document.getElementById("setting-skip-unmatched").checked = state.preferences.skipIfNoPreferenceMatch;

    document.getElementById("btn-save-settings").onclick = () => {
      const newUrl = document.getElementById("setting-worker-url").value.trim();
      state.workerUrl = newUrl;
      localStorage.setItem("sfd_worker_url", newUrl);

      state.preferences.lunchPriority = document.getElementById("setting-lunch-priority").value
        .split("\n").map(s => s.trim()).filter(Boolean);
      state.preferences.dinnerPriority = document.getElementById("setting-dinner-priority").value
        .split("\n").map(s => s.trim()).filter(Boolean);
      state.preferences.blacklist = document.getElementById("setting-blacklist").value
        .split("\n").map(s => s.trim()).filter(Boolean);
      state.preferences.skipIfNoPreferenceMatch = document.getElementById("setting-skip-unmatched").checked;

      localStorage.setItem("sfd_preferences", JSON.stringify(state.preferences));
      showToast("تنظیمات با موفقیت ذخیره شد.", "success");
      this.closeModal("modal-settings");

      if (forceWorkerSetup && newUrl) {
        this.openLoginModal();
      }
    };
  }

  static openTopupModal(type = "self", deficit = 0) {
    const modal = document.getElementById("modal-topup");
    modal.style.display = "flex";

    const titleEl = document.getElementById("topup-modal-title");
    titleEl.textContent = type === "self" ? "افزایش اعتبار سلف عادی" : "افزایش اعتبار رستوران آزاد";

    const inputAmt = document.getElementById("topup-amount");
    let suggested = 1000000;
    if (deficit > 0) {
      suggested = Math.ceil(deficit / 500000) * 500000;
      suggested = Math.max(suggested, 1000000);
    }
    inputAmt.value = suggested;

    document.querySelectorAll(".chip-topup-quick").forEach(chip => {
      chip.onclick = () => {
        inputAmt.value = chip.dataset.amt;
      };
    });

    document.getElementById("btn-start-payment").onclick = () => {
      const amt = parseInt(inputAmt.value, 10) || suggested;
      const targetUrl = "https://sfd.ui.ac.ir/ePay/ePay_Payment.aspx";
      this.closeModal("modal-topup");

      // Open payment gateway in new tab with cookie hint
      const win = window.open(targetUrl, "_blank");
      if (!win) {
        window.location.href = targetUrl;
      }
      showToast(`درگاه شاپرک با مبلغ ${formatRial(amt)} در تب جدید باز شد.`, "info");
    };
  }

  static closeModal(modalId) {
    const modal = document.getElementById(modalId);
    if (modal) modal.style.display = "none";
  }
}

// Start on DOM ready
document.addEventListener("DOMContentLoaded", () => {
  AppUI.init();
});
