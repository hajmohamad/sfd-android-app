#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
اسکریپت هوشمند ورود به سامانه تغذیه کالینان دانشگاه اصفهان (sfd.ui.ac.ir)
دارای:
۱. ورود خودکار با ماژول اختصاصی OCR
۲. نمایش موجودی دقیق کاربر (اعتبار سلف و رستوران آزاد)
۳. مدیریت سلف‌های پیش‌فرض ناهار، شام و صبحانه و انطباق نمایش با سلف انتخابی کاربر
۴. بررسی کامل ظرفیت غذاهای رستوران‌های آزاد برای تمامی وعده‌های صبحانه، ناهار و شام
۵. امکان مشاهده ظرفیت یک رستوران یا مقایسه همزمان تمام رستوران‌ها (یاس ۱ و یاس ۲)
"""

import os
import sys
import re
import json
import ssl
import time
import getpass
import argparse
import subprocess
import urllib.request
import urllib.parse
import http.cookiejar
from typing import Dict, Any, List, Optional, Tuple

try:
    from PIL import Image, ImageFilter
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

# رنگ‌های ترمینال برای خروجی شکیل و خوانا
class Colors:
    HEADER = '\033[95m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
    BOLD = '\033[1m'
    DIM = '\033[2m'
    RESET = '\033[0m'

def colorize(text: str, color: str) -> str:
    if sys.stdout.isatty():
        return f"{color}{text}{Colors.RESET}"
    return text


class CaptchaOCR:
    """موتور تشخیص کاراکتر نوری (OCR) بهینه‌شده برای کپچاهای سامانه کالینان"""
    def __init__(self, templates_path: Optional[str] = None):
        self.templates = {}
        if not templates_path:
            templates_path = os.path.join(os.path.dirname(__file__), "templates.json")
        if os.path.exists(templates_path):
            try:
                with open(templates_path, "r", encoding="utf-8") as f:
                    self.templates = json.load(f)
            except Exception:
                pass

    def solve(self, image_path: str) -> Tuple[Optional[str], float]:
        if not HAS_PIL or not self.templates:
            return None, 0.0

        try:
            img = Image.open(image_path).convert('L')
            thresh = img.point(lambda p: 255 if p > 190 else 0)
            closed = thresh.filter(ImageFilter.MaxFilter(3))
            
            slots = [(76, 96), (97, 117), (117, 138), (138, 158)]
            predicted_digits = []
            scores = []

            for x1, x2 in slots:
                cand = [[1 if closed.getpixel((x, y)) > 128 else 0 for x in range(x1, x2)] for y in range(8, 33)]
                active_y = [y for y in range(len(cand)) if any(cand[y])]
                active_x = [x for x in range(len(cand[0])) if any(cand[y][x] for y in range(len(cand)))]
                if not active_y or not active_x:
                    return None, 0.0
                cand = [[cand[y][x] for x in range(min(active_x), max(active_x) + 1)] for y in range(min(active_y), max(active_y) + 1)]

                ch, cw = len(cand), len(cand[0])
                best_digit = None
                best_iou = -1.0

                for d, templ in self.templates.items():
                    th, tw = len(templ), len(templ[0])
                    score_max = 0.0
                    for dy in range(-2, 3):
                        for dx in range(-2, 3):
                            inter = 0
                            union = 0
                            for y in range(max(ch, th)):
                                for x in range(max(cw, tw)):
                                    v1 = cand[y][x] if 0 <= y < ch and 0 <= x < cw else 0
                                    ty, tx = y + dy, x + dx
                                    v2 = templ[ty][tx] if 0 <= ty < th and 0 <= tx < tw else 0
                                    if v1 and v2:
                                        inter += 1
                                    if v1 or v2:
                                        union += 1
                            iou = inter / union if union > 0 else 0.0
                            if iou > score_max:
                                score_max = iou
                    if score_max > best_iou:
                        best_iou = score_max
                        best_digit = d

                predicted_digits.append(best_digit)
                scores.append(best_iou)

            avg_confidence = sum(scores) / len(scores) if scores else 0.0
            return "".join(predicted_digits), avg_confidence
        except Exception:
            return None, 0.0


class SFDClient:
    def __init__(self, base_url: str = "https://sfd.ui.ac.ir"):
        self.base_url = base_url.rstrip('/')
        
        self.ssl_context = ssl.create_default_context()
        self.ssl_context.check_hostname = False
        self.ssl_context.verify_mode = ssl.CERT_NONE
        
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=self.ssl_context),
            urllib.request.HTTPCookieProcessor(self.cookie_jar)
        )
        
        self.headers = {
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept-Language': 'fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7',
        }
        self.viewstate = ""
        self.viewstate_gen = ""

    def _request(self, path: str, data: Optional[Dict[str, str]] = None, referer: Optional[str] = None) -> str:
        url = path if path.startswith("http") else f"{self.base_url}{path}"
        headers = self.headers.copy()
        if referer:
            headers['Referer'] = referer
            
        encoded_data = None
        if data is not None:
            encoded_data = urllib.parse.urlencode(data).encode('utf-8')
            headers['Content-Type'] = 'application/x-www-form-urlencoded'

        req = urllib.request.Request(url, data=encoded_data, headers=headers)
        with self.opener.open(req, timeout=20) as resp:
            content = resp.read()
            return content.decode('utf-8', errors='ignore')

    def init_login_session(self) -> None:
        html = self._request("/")
        vs_match = re.search(r'id=\"__VIEWSTATE\"\s+value=\"([^\"]+)\"', html)
        vsg_match = re.search(r'id=\"__VIEWSTATEGENERATOR\"\s+value=\"([^\"]+)\"', html)
        
        if vs_match:
            self.viewstate = vs_match.group(1)
        if vsg_match:
            self.viewstate_gen = vsg_match.group(1)

    def download_captcha(self, save_path: str = "/tmp/sfd_captcha.png") -> str:
        captcha_url = f"{self.base_url}/UserControls/Captcha.ashx?rnd={int(time.time()*1000)}"
        req = urllib.request.Request(captcha_url, headers=self.headers)
        with self.opener.open(req, timeout=15) as resp:
            data = resp.read()
            with open(save_path, "wb") as f:
                f.write(data)
        return save_path

    def download_audio_captcha(self, save_path: str = "/tmp/sfd_captcha.wav") -> str:
        audio_url = f"{self.base_url}/UserControls/AudioPlayer.aspx"
        req = urllib.request.Request(audio_url, headers=self.headers)
        with self.opener.open(req, timeout=15) as resp:
            data = resp.read()
            with open(save_path, "wb") as f:
                f.write(data)
        return save_path

    @staticmethod
    def print_ascii_captcha(image_path: str) -> None:
        if not HAS_PIL:
            return
        try:
            img = Image.open(image_path).convert('L')
            w, h = img.size
            cropped = img.crop((70, 0, 165, h))
            cw, ch = cropped.size
            ascii_lines = []
            for y in range(0, ch, 2):
                row = []
                for x in range(cw):
                    val = cropped.getpixel((x, y))
                    if val > 190:
                        row.append("█")
                    else:
                        row.append(" ")
                ascii_lines.append("".join(row))
            
            print("\n" + colorize("┌" + "─"*97 + "┐", Colors.CYAN))
            print(colorize("│  پیش‌نمایش کد امنیتی در ترمینال:                                                                 │", Colors.CYAN))
            for line in ascii_lines:
                if "█" in line:
                    print(colorize(f"│  {line:95}│", Colors.YELLOW))
            print(colorize("└" + "─"*97 + "┘\n", Colors.CYAN))
        except Exception:
            pass

    def login(self, username: str, password: str, captcha_code: str) -> bool:
        post_data = {
            '__VIEWSTATE': self.viewstate,
            '__VIEWSTATEGENERATOR': self.viewstate_gen,
            '__VIEWSTATEENCRYPTED': '',
            'txtUsernamePlain': username,
            'txtPasswordPlain': password,
            'txtUsername': username,
            'txtPassword': password,
            'txtCaptcha': captcha_code,
            'btnLogin': 'ورود سمت سرور '
        }
        
        resp_html = self._request("/", data=post_data, referer=f"{self.base_url}/")
        
        if "کد امنیتی صحیح نمی باشد" in resp_html:
            raise ValueError("کد امنیتی اشتباه است.")
        if "کلمه عبور اشتباه است" in resp_html or "نام کاربری یا کلمه عبور صحیح نمی باشد" in resp_html:
            raise ValueError("نام کاربری یا کلمه عبور نادرست است.")
        if "خطا در کد امنیتی" in resp_html:
            raise ValueError("خطای سشن کد امنیتی.")
            
        if "Reservation.aspx" in resp_html or "خرید هفتگی غذای سلف" in resp_html or "cphMain_lblWeekDate" in resp_html:
            return True
            
        error_match = re.search(r'toastify\([^\)]*\'([^\']*خطا[^\']*)\'', resp_html)
        if error_match:
            raise ValueError(f"پیام خطا از سامانه: {error_match.group(1)}")
            
        return True

    def get_reservation_page(self) -> str:
        return self._request("/Reservation/Reservation.aspx", referer=f"{self.base_url}/")

    def get_cafeteria_page(self) -> str:
        return self._request("/Reservation/CafeteriaReservation.aspx", referer=f"{self.base_url}/Reservation/Reservation.aspx")

    def switch_cafeteria_restaurant(self, current_caf_html: str, restaurant_id: str) -> str:
        """تغییر رستوران آزاد (یاس ۱ یا ۲) با ارسال پست‌بک کامل فرم"""
        form_data = {}
        for m in re.finditer(r'<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"', current_caf_html):
            form_data[m.group(1)] = m.group(2)

        for m in re.finditer(r'<select[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</select>', current_caf_html, re.DOTALL):
            name = m.group(1)
            sel = re.search(r'<option[^>]*value=\"([^\"]*)\"[^>]*selected=\"selected\"', m.group(2))
            if not sel:
                sel = re.search(r'<option[^>]*value=\"([^\"]*)\"', m.group(2))
            if sel:
                form_data[name] = sel.group(1)

        form_data['__EVENTTARGET'] = 'ctl00$cphMain$drpSelfTitle_Lunch'
        form_data['__EVENTARGUMENT'] = ''
        form_data['ctl00$cphMain$drpSelfTitle_Lunch'] = restaurant_id
        if 'ctl00$cphMain$drpSelfTitle_Dinner' in form_data:
            form_data['ctl00$cphMain$drpSelfTitle_Dinner'] = restaurant_id

        req = urllib.request.Request(
            f"{self.base_url}/Reservation/CafeteriaReservation.aspx",
            data=urllib.parse.urlencode(form_data).encode('utf-8'),
            headers={
                'User-Agent': self.headers['User-Agent'],
                'Content-Type': 'application/x-www-form-urlencoded',
                'Referer': f"{self.base_url}/Reservation/CafeteriaReservation.aspx"
            }
        )

        with self.opener.open(req, timeout=20) as resp:
            return resp.read().decode('utf-8', errors='ignore')


def parse_student_info(html: str) -> Dict[str, str]:
    """استخراج مشخصات دانشجو، اعتبار سلف و اعتبار رستوران"""
    info = {
        "name": "نامشخص",
        "student_id": "نامشخص",
        "self_credit": "۰ ریال",
        "restaurant_credit": "۰ ریال",
        "week_range": "نامشخص"
    }
    
    user_match = re.search(r'کاربر:\s*([^\-]+)\s*-\s*([0-9]+)', html)
    if user_match:
        info["name"] = user_match.group(1).strip()
        info["student_id"] = user_match.group(2).strip()
        
    self_cred = re.search(r'id=\"[^\"]*SelfCredit[^\"]*\"[^>]*>([^<]+)<', html, re.I)
    if self_cred:
        info["self_credit"] = self_cred.group(1).strip()
    else:
        credits = re.findall(r'([0-9,]+)\s*ريال', html)
        if len(credits) >= 1:
            info["self_credit"] = f"{credits[0]} ریال"
        if len(credits) >= 2:
            info["restaurant_credit"] = f"{credits[1]} ریال"

    rest_cred = re.search(r'id=\"[^\"]*RestCredit[^\"]*\"[^>]*>([^<]+)<', html, re.I)
    if rest_cred:
        info["restaurant_credit"] = rest_cred.group(1).strip()
        
    week_match = re.search(r'id=\"cphMain_lblWeekDate\"[^>]*>([^<]+)<', html)
    if week_match:
        info["week_range"] = week_match.group(1).strip()
        
    return info


def extract_self_services(res_html: str) -> Dict[str, Dict[str, str]]:
    """استخراج تمام سلف‌های فعال برای صبحانه، ناهار و شام از صفحه رزرو"""
    selfs = {
        "صبحانه": {},
        "ناهار": {},
        "شام": {}
    }
    table_map = {
        "صبحانه": "cphMain_grdReservationBreakfast",
        "ناهار": "cphMain_grdReservationLunch",
        "شام": "cphMain_grdReservationDinner"
    }
    
    for meal, tbl_id in table_map.items():
        m = re.search(r'(<table[^>]*id=\"' + tbl_id + r'\".*?</table>)', res_html, re.DOTALL)
        if m:
            for sel in re.findall(r'<select[^>]*drpSelf[^>]*>(.*?)</select>', m.group(1), re.DOTALL):
                for val, text in re.findall(r'<option[^>]*value=\"([^\"]*)\"[^>]*>([^<]*)</option>', sel):
                    clean_text = text.strip()
                    if val != '-1' and clean_text and clean_text != '-':
                        selfs[meal][val] = clean_text
    return selfs


def extract_restaurants(caf_html: str) -> Dict[str, str]:
    """استخراج لیست تمام رستوران‌های آزاد (یاس ۱، یاس ۲ و ...)"""
    restaurants = {}
    lunch_select = re.search(r'<select[^>]*drpSelfTitle_Lunch[^>]*>(.*?)</select>', caf_html, re.DOTALL)
    if lunch_select:
        for val, name in re.findall(r'<option[^>]*value=\"([^\"]*)\"[^>]*>([^<]*)</option>', lunch_select.group(1)):
            clean_name = name.strip()
            if val != '-1' and clean_name:
                restaurants[val] = clean_name
    return restaurants


def parse_meal_table(html: str, table_id: str, default_self: str = "-") -> List[Dict[str, Any]]:
    """پارس کردن برنامه هفتگی غذای سلف و انطباق با سلف انتخابی کاربر"""
    table_match = re.search(r'(<table[^>]*id=\"' + table_id + r'\".*?</table>)', html, re.DOTALL)
    if not table_match:
        return []
        
    table_html = table_match.group(1)
    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', table_html, re.DOTALL)
    results = []
    
    for r in rows[1:]:
        cols = re.findall(r'<td[^>]*>(.*?)</td>', r, re.DOTALL)
        if len(cols) < 5:
            continue
            
        day_date = re.sub(r'<[^>]+>', ' ', cols[1]).strip()
        day_date = ' '.join(day_date.split())
        
        # سلف ثبت شده در سامانه
        self_match = re.search(r'<select[^>]*drpSelf[^>]*>.*?<option[^>]*selected=\"selected\"[^>]*>([^<]*)</option>', cols[2], re.DOTALL)
        raw_self_name = self_match.group(1).strip() if self_match else '-'
        if raw_self_name == '-1':
            raw_self_name = '-'
            
        # نام غذای رزرو شده
        food_match = re.search(r'<select[^>]*drpFood[^>]*>.*?<option[^>]*selected=\"selected\"[^>]*>([^<]*)</option>', cols[3], re.DOTALL)
        food_name = food_match.group(1).strip() if food_match else '-'
        if food_name == '-1':
            food_name = '-'
            
        all_options = re.findall(r'<option[^>]*value=\"([^\"]*)\"[^>]*>([^<]*)</option>', cols[3])
        available_foods = [opt[1].strip() for opt in all_options if opt[0] not in ('-1', '')]
            
        count_match = re.search(r'<select[^>]*drpFoodCount[^>]*>.*?<option[^>]*selected=\"selected\"[^>]*>([^<]*)</option>', cols[4], re.DOTALL)
        count_val = count_match.group(1).strip() if count_match else '0'
        
        status_match = re.search(r'title=\"([^\"]+)\"', cols[7]) if len(cols) > 7 else None
        status = status_match.group(1).strip() if status_match else ''
        
        price_match = re.search(r'([0-9,]+)\s*ریال', food_name)
        price = price_match.group(1) + " ریال" if price_match else "-"
        clean_food_name = re.sub(r'[0-9,]+\s*ریال', '', food_name).strip() if food_name != '-' else '-'
        
        is_reserved = (count_val != '0' and food_name != '-')
        
        # نمایش سلف: تطبیق با سلف انتخابی کاربر
        if default_self and default_self != '-':
            if is_reserved and raw_self_name != '-' and raw_self_name != default_self:
                display_self = f"{default_self} (رزرو سامانه: {raw_self_name})"
            else:
                display_self = default_self
        else:
            display_self = raw_self_name
            
        results.append({
            "day_date": day_date,
            "is_reserved": is_reserved,
            "count": int(count_val) if count_val.isdigit() else 0,
            "self": display_self,
            "raw_self": raw_self_name,
            "food": clean_food_name,
            "full_food": food_name,
            "price": price,
            "status": status,
            "available_foods": available_foods
        })
        
    return results


def parse_restaurant_all_meals_capacity(caf_html: str, restaurant_name: str) -> Dict[str, List[Dict[str, Any]]]:
    """استخراج دقیق غذاها، قیمت و ظرفیت باقیمانده برای هر ۳ وعده صبحانه، ناهار و شام رستوران آزاد"""
    results = {
        "صبحانه": [],
        "ناهار": [],
        "شام": []
    }
    meal_tables = [
        ("صبحانه", "cphMain_grdBreakfast"),
        ("ناهار", "cphMain_grdLunch"),
        ("شام", "cphMain_grdDinner")
    ]
    
    for meal_name, tbl_id in meal_tables:
        m = re.search(r'(<table[^>]*id=\"' + tbl_id + r'\".*?</table>)', caf_html, re.DOTALL)
        if not m:
            continue
            
        rows = re.findall(r'<tr[^>]*>(.*?)</tr>', m.group(1), re.DOTALL)
        for r in rows[1:]:
            cols = [re.sub(r'<[^>]+>', ' ', c).strip() for c in re.findall(r'<t[hd][^>]*>(.*?)</t[hd]>', r, re.DOTALL)]
            if len(cols) > 6:
                food_name = cols[1]
                if not food_name or "داده ای برای نمایش" in food_name:
                    continue
                subsidized_price = cols[2]
                remaining_str = cols[6]
                try:
                    rem_count = int(remaining_str)
                except ValueError:
                    rem_count = 0
                    
                results[meal_name].append({
                    "restaurant": restaurant_name,
                    "meal": meal_name,
                    "food": food_name,
                    "price": f"{subsidized_price} ریال",
                    "remaining": rem_count,
                    "is_available": rem_count > 0
                })
    return results


def load_config() -> Dict[str, Any]:
    config_path = os.path.join(os.path.dirname(__file__), "config.json")
    if os.path.exists(config_path):
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def save_config(config: Dict[str, Any]) -> None:
    config_path = os.path.join(os.path.dirname(__file__), "config.json")
    try:
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(config, f, ensure_ascii=False, indent=4)
    except Exception as e:
        print(colorize(f"خطا در ذخیره فایل تنظیمات: {e}", Colors.RED))


def setup_or_get_user_preferences(config: Dict[str, Any], available_selfs: Dict[str, Dict[str, str]], available_restaurants: Dict[str, str], force_change: bool = False) -> Dict[str, Any]:
    """مدیریت و ذخیره سلف‌های پیش‌فرض صبحانه، ناهار، شام و رستوران آزاد"""
    updated = False
    
    # ۱. سلف پیش‌فرض صبحانه
    breakfast_selfs = list(available_selfs.get("صبحانه", {}).values())
    if force_change or not config.get("default_breakfast_self"):
        if breakfast_selfs:
            print(colorize("\n🍳 لطفاً سلف پیش‌فرض صبحانه خود را انتخاب کنید:", Colors.YELLOW + Colors.BOLD))
            for idx, s in enumerate(breakfast_selfs, 1):
                print(f"   [{idx}] {s}")
            while True:
                choice = input(colorize("👉 شماره مورد نظر را وارد کنید [۱]: ", Colors.CYAN)).strip() or "1"
                if choice.isdigit() and 1 <= int(choice) <= len(breakfast_selfs):
                    config["default_breakfast_self"] = breakfast_selfs[int(choice) - 1]
                    updated = True
                    break
                print(colorize("شماره نامعتبر است.", Colors.RED))
        else:
            config["default_breakfast_self"] = "-"

    # ۲. سلف پیش‌فرض ناهار
    lunch_selfs = list(available_selfs.get("ناهار", {}).values())
    if force_change or not config.get("default_lunch_self"):
        if lunch_selfs:
            print(colorize("\n🍱 لطفاً سلف پیش‌فرض ناهار خود را انتخاب کنید:", Colors.YELLOW + Colors.BOLD))
            for idx, s in enumerate(lunch_selfs, 1):
                print(f"   [{idx}] {s}")
            while True:
                choice = input(colorize("👉 شماره مورد نظر را وارد کنید [۱]: ", Colors.CYAN)).strip() or "1"
                if choice.isdigit() and 1 <= int(choice) <= len(lunch_selfs):
                    config["default_lunch_self"] = lunch_selfs[int(choice) - 1]
                    updated = True
                    break
                print(colorize("شماره نامعتبر است.", Colors.RED))
        else:
            config["default_lunch_self"] = "-"

    # ۳. سلف پیش‌فرض شام
    dinner_selfs = list(available_selfs.get("شام", {}).values())
    if force_change or not config.get("default_dinner_self"):
        if dinner_selfs:
            print(colorize("\n🌙 لطفاً سلف پیش‌فرض شام خود را انتخاب کنید:", Colors.YELLOW + Colors.BOLD))
            for idx, s in enumerate(dinner_selfs, 1):
                print(f"   [{idx}] {s}")
            while True:
                choice = input(colorize("👉 شماره مورد نظر را وارد کنید [۱]: ", Colors.CYAN)).strip() or "1"
                if choice.isdigit() and 1 <= int(choice) <= len(dinner_selfs):
                    config["default_dinner_self"] = dinner_selfs[int(choice) - 1]
                    updated = True
                    break
                print(colorize("شماره نامعتبر است.", Colors.RED))
        else:
            config["default_dinner_self"] = "-"

    # ۴. رستوران پیش‌فرض
    rest_list = list(available_restaurants.items())
    if force_change or not config.get("default_restaurant"):
        if rest_list:
            print(colorize("\n🏪 لطفاً رستوران آزاد پیش‌فرض خود را انتخاب کنید:", Colors.YELLOW + Colors.BOLD))
            for idx, (rid, rname) in enumerate(rest_list, 1):
                print(f"   [{idx}] {rname}")
            while True:
                choice = input(colorize("👉 شماره مورد نظر را وارد کنید [۱]: ", Colors.CYAN)).strip() or "1"
                if choice.isdigit() and 1 <= int(choice) <= len(rest_list):
                    selected_id, selected_name = rest_list[int(choice) - 1]
                    config["default_restaurant_id"] = selected_id
                    config["default_restaurant"] = selected_name
                    updated = True
                    break
                print(colorize("شماره نامعتبر است.", Colors.RED))
                
    if updated:
        save_config(config)
        print(colorize("✅ تنظیمات پیش‌فرض با موفقیت ذخیره شد (دفعات بعد سوال پرسیده نمی‌شود).", Colors.GREEN + Colors.BOLD))
        
    return config


def print_main_report(user_info: Dict[str, str], config: Dict[str, Any], meals: Dict[str, List[Dict[str, Any]]]) -> None:
    """نمایش مشخصات کاربر، موجودی اعتبار و برنامه هفتگی سلف بر اساس سلف‌های انتخابی کاربر"""
    print("\n" + "=" * 80)
    print(colorize(" 🍽️  سامانه تغذیه کالینان دانشگاه اصفهان (sfd.ui.ac.ir) ", Colors.HEADER + Colors.BOLD).center(80))
    print("=" * 80)
    
    # موجودی و اعتبار کاربر
    print(colorize(" 💰 موجودی و اعتبار حساب شما:", Colors.CYAN + Colors.BOLD))
    print(f"   👤 {colorize('دانشجو:', Colors.BOLD)} {user_info['name']} ({user_info['student_id']})")
    print(f"   💳 {colorize('اعتبار سلف:', Colors.BOLD)} {colorize(user_info['self_credit'], Colors.GREEN + Colors.BOLD)}  |  {colorize('اعتبار رستوران آزاد:', Colors.BOLD)} {colorize(user_info['restaurant_credit'], Colors.YELLOW + Colors.BOLD)}")
    print(f"   📅 {colorize('بازه زمانی:', Colors.BOLD)} {user_info['week_range']}")
    
    def_brk = config.get("default_breakfast_self", "-")
    def_lunch = config.get("default_lunch_self", "-")
    def_dinner = config.get("default_dinner_self", "-")
    print(f"\n 📍 {colorize('مکان‌های سلف انتخابی شما:', Colors.BOLD)} صبحانه: {colorize(def_brk, Colors.CYAN)} | ناهار: {colorize(def_lunch, Colors.CYAN)} | شام: {colorize(def_dinner, Colors.CYAN)}")
    print(colorize("    (نکته: منو و کیفیت در کلیه مکان‌های سلف یکسان است. جهت تغییر سلف دیفالت از فلگ --change-defaults استفاده کنید.)", Colors.DIM))
    print("-" * 80)
    
    total_reserved_count = 0
    meal_icons = {"صبحانه": "🍳", "ناهار": "🍲", "شام": "🌙"}
    
    for meal_title, items in meals.items():
        icon = meal_icons.get(meal_title, "🍴")
        chosen_self = def_brk if meal_title == "صبحانه" else (def_lunch if meal_title == "ناهار" else def_dinner)
        print(f"\n{colorize(f' {icon} وعده {meal_title} (مکان سلف انتخابی: {chosen_self}):', Colors.YELLOW + Colors.BOLD)}")
        print(f" {'وضعیت':<4} {'روز و تاریخ':<18} {'مکان سلف':<24} {'غذای رزرو شده':<28} {'تعداد':<6} {'قیمت':<12}")
        print(" " + "-" * 82)
        
        meal_reserved_count = 0
        for item in items:
            if item['is_reserved']:
                status_icon = colorize("✅", Colors.GREEN)
                food_display = colorize(item['food'], Colors.GREEN)
                count_display = colorize(str(item['count']), Colors.BOLD + Colors.GREEN)
                meal_reserved_count += item['count']
                total_reserved_count += item['count']
            else:
                status_icon = colorize("❌", Colors.RED)
                food_display = colorize("- رزرو نشده -", Colors.RED)
                count_display = "0"
                
            self_display = item['self'] if item['self'] != '-' else '-'
            price_display = item['price'] if item['price'] != '-' else '-'
            
            print(f"  {status_icon:<3} {item['day_date']:<18} {self_display:<24} {food_display:<28} {count_display:<6} {price_display:<12}")
            
            if not item['is_reserved'] and item['available_foods']:
                avail_str = " ، ".join(item['available_foods'][:2])
                print(colorize(f"       ↳ منوی روز: {avail_str}", Colors.CYAN))

        print(f"    ↳ جمع غذاهای رزرو شده در این وعده: {colorize(str(meal_reserved_count), Colors.BOLD)} عدد")

    print("\n" + "=" * 80)
    print(f" 📊  {colorize('مجموع کل غذاهای رزرو شده شما در این هفته:', Colors.BOLD)} {colorize(str(total_reserved_count), Colors.GREEN + Colors.BOLD)} پرس غذا")
    print("=" * 80)


def print_restaurant_capacity_report(rest_name: str, meal_foods: Dict[str, List[Dict[str, Any]]], only_available: bool = True) -> None:
    """نمایش جامع ظرفیت و باقیمانده غذاهای رستوران آزاد برای تمام وعده‌های صبحانه، ناهار و شام"""
    print("\n" + "-" * 80)
    print(colorize(f" 🏪 گزارش ظرفیت غذاهای {rest_name} (امروز):", Colors.CYAN + Colors.BOLD))
    print(colorize("    (ظرفیت هر غذا در هر رستوران متفاوت است)", Colors.DIM))
    
    meal_icons = {"صبحانه": "🍳", "ناهار": "🍲", "شام": "🌙"}
    for meal_name in ["صبحانه", "ناهار", "شام"]:
        foods = meal_foods.get(meal_name, [])
        icon = meal_icons.get(meal_name, "🍴")
        display_items = [f for f in foods if f['is_available']] if only_available else foods
        
        print(f"\n {colorize(f'{icon} {meal_name} رستوران:', Colors.YELLOW + Colors.BOLD)}")
        if not display_items:
            if not foods:
                print(colorize("   (غذایی برای این وعده در این رستوران ارائه نشده است)", Colors.DIM))
            else:
                print(colorize("   (تمامی ظرفیت‌های این وعده به پایان رسیده است)", Colors.RED))
            continue
            
        print(f"  {'ردیف':<5} {'نام غذا':<42} {'قیمت یارانه ای':<15} {'ظرفیت باقیمانده':<15}")
        print("  " + "-" * 76)
        for idx, f in enumerate(display_items, 1):
            if f['remaining'] > 0:
                rem_str = colorize(f"🟢 {f['remaining']} پرس", Colors.GREEN + Colors.BOLD)
                food_str = colorize(f['food'], Colors.BOLD)
            else:
                rem_str = colorize("🔴 تمام شده", Colors.RED)
                food_str = colorize(f['food'], Colors.DIM)
            print(f"  {idx:<5} {food_str:<42} {f['price']:<15} {rem_str:<15}")


def main():
    parser = argparse.ArgumentParser(description="بررسی موجودی، سلف‌ها و ظرفیت رستوران‌های سامانه تغذیه کالینان (sfd.ui.ac.ir)")
    parser.add_argument("-u", "--username", help="نام کاربری (شماره دانشجویی)")
    parser.add_argument("-p", "--password", help="گذرواژه سامانه تغذیه")
    parser.add_argument("--change-defaults", "-s", action="store_true", help="تغییر مکان‌های سلف یا رستوران پیش‌فرض")
    parser.add_argument("--all-restaurants", "-a", action="store_true", help="بررسی و مقایسه ظرفیت تمام رستوران‌های آزاد (یاس ۱ و ۲)")
    parser.add_argument("--restaurant", "-r", help="مشاهده ظرفیت یک رستوران خاص (نام یا شماره شناسه)")
    parser.add_argument("--show-all-foods", action="store_true", help="نمایش غذاهای تمام‌شده رستوران در کنار غذاهای موجود")
    parser.add_argument("--manual", action="store_true", help="ورود دستی کد امنیتی")
    parser.add_argument("--json", action="store_true", help="خروجی در قالب JSON")
    
    args = parser.parse_args()
    config = load_config()
    
    username = args.username or config.get("username")
    password = args.password or config.get("password")
    base_url = config.get("base_url", "https://sfd.ui.ac.ir")
    
    if not username:
        username = input("نام کاربری (شماره دانشجویی): ").strip()
    if not password:
        password = getpass.getpass("گذرواژه: ").strip()
        
    ocr = CaptchaOCR()
    max_retries = 8
    retry_count = 0
    login_success = False
    
    print(colorize("🔄 در حال اتصال خودکار به سامانه تغذیه دانشگاه اصفهان...", Colors.CYAN))
    
    client = None
    while not login_success and retry_count < max_retries:
        retry_count += 1
        try:
            client = SFDClient(base_url=base_url)
            client.init_login_session()
            captcha_img_path = client.download_captcha()
        except Exception as conn_err:
            print(colorize(f"⚠️ خطای موقت ارتباط ({conn_err}). تلاش مجدد...", Colors.YELLOW))
            time.sleep(1.5)
            continue
        
        captcha_code = None
        if not args.manual and HAS_PIL and ocr.templates:
            pred_code, conf = ocr.solve(captcha_img_path)
            if pred_code and len(pred_code) == 4:
                conf_pct = int(conf * 100)
                print(colorize(f"🔍 [تلاش {retry_count}] تشخیص خودکار کد امنیتی با OCR: {pred_code} (دقت: {conf_pct}٪)", Colors.YELLOW))
                captcha_code = pred_code

        if not captcha_code and not args.manual and retry_count < max_retries:
            time.sleep(1.0)
            continue

        if not captcha_code:
            client.print_ascii_captcha(captcha_img_path)
            if sys.platform == "darwin":
                try:
                    subprocess.Popen(["open", captcha_img_path])
                except Exception:
                    pass
            prompt_text = colorize("🔢 لطفاً کد امنیتی ۴ رقمی را وارد کنید: ", Colors.YELLOW + Colors.BOLD)
            captcha_code = input(prompt_text).strip()

        print(colorize("🔐 در حال ارسال اطلاعات و احراز هویت...", Colors.CYAN))
        try:
            client.login(username, password, captcha_code)
            login_success = True
            print(colorize("🎉 با موفقیت به سامانه وارد شدید!", Colors.GREEN + Colors.BOLD))
        except ValueError as e:
            if "کد امنیتی" in str(e):
                print(colorize(f"⚠️ کد امنیتی پذیرفته نشد. تلاش مجدد خودکار...", Colors.YELLOW))
                time.sleep(1.2)
            else:
                print(colorize(f"❌ خطای احراز هویت: {e}", Colors.RED))
                sys.exit(1)

    if not login_success:
        print(colorize("⛔ ورود به سامانه ناموفق بود.", Colors.RED))
        sys.exit(1)

    # ۱. استخراج اطلاعات کاربری، موجودی و سلف‌ها
    print(colorize("📥 در حال دریافت اطلاعات کاربری، موجودی و سلف‌ها...", Colors.CYAN))
    res_html = client.get_reservation_page()
    user_info = parse_student_info(res_html)
    available_selfs = extract_self_services(res_html)
    
    # ۲. استخراج لیست رستوران‌ها
    print(colorize("📥 در حال دریافت اطلاعات رستوران‌های آزاد...", Colors.CYAN))
    caf_html = client.get_cafeteria_page()
    available_restaurants = extract_restaurants(caf_html)
    
    # ۳. مدیریت مکان‌های پیش‌فرض کاربر
    config = setup_or_get_user_preferences(config, available_selfs, available_restaurants, force_change=args.change_defaults)
    
    # ۴. استخراج غذاهای سلف منطبق با سلف انتخابی کاربر
    meals = {
        "صبحانه": parse_meal_table(res_html, "cphMain_grdReservationBreakfast", default_self=config.get("default_breakfast_self", "-")),
        "ناهار": parse_meal_table(res_html, "cphMain_grdReservationLunch", default_self=config.get("default_lunch_self", "-")),
        "شام": parse_meal_table(res_html, "cphMain_grdReservationDinner", default_self=config.get("default_dinner_self", "-")),
    }
    
    # ۵. استخراج ظرفیت رستوران‌های آزاد (صبحانه، ناهار و شام)
    target_restaurants = {}
    if args.all_restaurants:
        target_restaurants = available_restaurants
    elif args.restaurant:
        for rid, rname in available_restaurants.items():
            if args.restaurant in rid or args.restaurant in rname:
                target_restaurants[rid] = rname
        if not target_restaurants:
            print(colorize(f"رستوران '{args.restaurant}' یافت نشد. استفاده از رستوران دیفالت.", Colors.YELLOW))
            target_restaurants[config.get("default_restaurant_id", "13")] = config.get("default_restaurant", "رستوران یاس یک 1 *")
    else:
        def_rid = config.get("default_restaurant_id")
        def_rname = config.get("default_restaurant")
        if not def_rid or def_rid not in available_restaurants:
            def_rid = list(available_restaurants.keys())[0]
            def_rname = available_restaurants[def_rid]
        target_restaurants[def_rid] = def_rname

    restaurant_capacities = {}
    current_caf = caf_html
    for rid, rname in target_restaurants.items():
        current_lunch_sel = re.search(r'<select[^>]*drpSelfTitle_Lunch[^>]*>.*?<option[^>]*value=\"([^\"]*)\"[^>]*selected=\"selected\"', current_caf, re.DOTALL)
        curr_id = current_lunch_sel.group(1) if current_lunch_sel else ""
        if curr_id != rid:
            current_caf = client.switch_cafeteria_restaurant(current_caf, rid)
        restaurant_capacities[rname] = parse_restaurant_all_meals_capacity(current_caf, rname)

    # ۶. چاپ خروجی
    if args.json:
        output_data = {
            "user_info": user_info,
            "defaults": {
                "breakfast_self": config.get("default_breakfast_self"),
                "lunch_self": config.get("default_lunch_self"),
                "dinner_self": config.get("default_dinner_self"),
                "restaurant": config.get("default_restaurant")
            },
            "meals": meals,
            "restaurant_capacities": restaurant_capacities
        }
        print(json.dumps(output_data, ensure_ascii=False, indent=2))
    else:
        print_main_report(user_info, config, meals)
        
        only_avail = not args.show_all_foods
        for rname, meal_foods in restaurant_capacities.items():
            print_restaurant_capacity_report(rname, meal_foods, only_available=only_avail)
            
        if not args.all_restaurants and len(available_restaurants) > 1:
            print(colorize(f"\n💡 برای مشاهده و مقایسه ظرفیت تمام رستوران‌ها از دستور زیر استفاده کنید:", Colors.CYAN))
            print(colorize("   python3 check_food.py --all-restaurants", Colors.BOLD + Colors.YELLOW))


if __name__ == "__main__":
    main()
