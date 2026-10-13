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
import math
import select
import socket
import threading
import http.server
import getpass
import argparse
import subprocess
import webbrowser
import urllib.request
import urllib.parse
import http.cookiejar
import asyncio
try:
    import websockets
    HAS_WEBSOCKETS = True
except ImportError:
    HAS_WEBSOCKETS = False
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


def extract_selected_option_value(select_inner_html: str) -> str:
    """
    استخراج مقدار گزینه انتخاب شده در تگ select
    پشتیبانی همزمان از حالاتی که selected قبل یا بعد از value قرار گرفته است
    """
    m = re.search(r'<option(?=[^>]*\bselected\b)[^>]*value=[\"\']([^\"\']*)[\"\']', select_inner_html, re.I)
    if not m:
        m = re.search(r'<option[^>]*value=[\"\']([^\"\']*)[\"\'][^>]*\bselected\b', select_inner_html, re.I)
    if not m:
        m = re.search(r'<option[^>]*value=[\"\']([^\"\']*)[\"\']', select_inner_html, re.I)
    return m.group(1) if m else ""


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

    def _request(self, path: str, data: Optional[Dict[str, str]] = None, referer: Optional[str] = None, retries: int = 3) -> str:
        url = path if path.startswith("http") else f"{self.base_url}{path}"
        headers = self.headers.copy()
        if referer:
            headers['Referer'] = referer
            
        encoded_data = None
        if data is not None:
            encoded_data = urllib.parse.urlencode(data).encode('utf-8')
            headers['Content-Type'] = 'application/x-www-form-urlencoded'

        for attempt in range(retries):
            try:
                req = urllib.request.Request(url, data=encoded_data, headers=headers)
                with self.opener.open(req, timeout=25) as resp:
                    content = resp.read()
                    return content.decode('utf-8', errors='ignore')
            except (urllib.error.URLError, ConnectionResetError, TimeoutError, OSError) as e:
                if attempt < retries - 1:
                    time.sleep(1.2 * (attempt + 1))
                    continue
                raise

    def init_login_session(self) -> None:
        html = self._request("/")
        vs_match = re.search(r'id=\"__VIEWSTATE\"\s+value=\"([^\"]+)\"', html)
        vsg_match = re.search(r'id=\"__VIEWSTATEGENERATOR\"\s+value=\"([^\"]+)\"', html)
        
        if vs_match:
            self.viewstate = vs_match.group(1)
        if vsg_match:
            self.viewstate_gen = vsg_match.group(1)

    def download_captcha(self, save_path: str = "/tmp/sfd_captcha.png", retries: int = 4) -> str:
        captcha_url = f"{self.base_url}/UserControls/Captcha.ashx?rnd={int(time.time()*1000)}"
        for attempt in range(retries):
            try:
                req = urllib.request.Request(captcha_url, headers=self.headers)
                with self.opener.open(req, timeout=20) as resp:
                    data = resp.read()
                    with open(save_path, "wb") as f:
                        f.write(data)
                return save_path
            except (urllib.error.URLError, ConnectionResetError, TimeoutError, OSError):
                if attempt < retries - 1:
                    time.sleep(1.0 * (attempt + 1))
                    continue
                raise

    def download_audio_captcha(self, save_path: str = "/tmp/sfd_captcha.wav", retries: int = 3) -> str:
        audio_url = f"{self.base_url}/UserControls/AudioPlayer.aspx"
        for attempt in range(retries):
            try:
                req = urllib.request.Request(audio_url, headers=self.headers)
                with self.opener.open(req, timeout=20) as resp:
                    data = resp.read()
                    with open(save_path, "wb") as f:
                        f.write(data)
                return save_path
            except (urllib.error.URLError, ConnectionResetError, TimeoutError, OSError):
                if attempt < retries - 1:
                    time.sleep(1.0 * (attempt + 1))
                    continue
                raise

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
            
        if "Reservation.aspx" in resp_html or "خرید هفتگی غذای سلف" in resp_html or "cphMain_lblWeekDate" in resp_html or "cphMain" in resp_html:
            return True
            
        error_match = re.search(r'toastify\([^\)]*\'([^\']*خطا[^\']*)\'', resp_html)
        if error_match:
            raise ValueError(f"پیام خطا از سامانه: {error_match.group(1)}")
            
        raise ValueError("احراز هویت انجام نشد (احتمالاً کد امنیتی نادرست است).")

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
            val = extract_selected_option_value(m.group(2))
            if val:
                form_data[m.group(1)] = val

        form_data['__EVENTTARGET'] = 'ctl00$cphMain$drpSelfTitle_Lunch'
        form_data['__EVENTARGUMENT'] = ''
        form_data['ctl00$cphMain$drpSelfTitle_Lunch'] = str(restaurant_id)
        if 'ctl00$cphMain$drpSelfTitle_Dinner' in form_data:
            form_data['ctl00$cphMain$drpSelfTitle_Dinner'] = str(restaurant_id)
        if 'ctl00$cphMain$drpSelfTitle_Breakfast' in form_data:
            form_data['ctl00$cphMain$drpSelfTitle_Breakfast'] = str(restaurant_id)

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
        
    self_cred = re.search(r'id=[\"\'][^\"\']*(?:lblCredit|SelfCredit)[^\"\']*[\"\'][^>]*>([^<]+)<', html, re.I)
    if self_cred:
        info["self_credit"] = self_cred.group(1).strip()

    rest_cred = re.search(r'id=[\"\'][^\"\']*(?:lblCafeteriaCredit|RestCredit)[^\"\']*[\"\'][^>]*>([^<]+)<', html, re.I)
    if rest_cred:
        info["restaurant_credit"] = rest_cred.group(1).strip()

    if not self_cred and not rest_cred:
        credits = re.findall(r'([0-9,]+)\s*ريال', html)
        if len(credits) >= 1:
            info["self_credit"] = f"{credits[0]} ریال"
        if len(credits) >= 2:
            info["restaurant_credit"] = f"{credits[1]} ریال"
        
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


# ==============================================================================
# بخش اولویت‌بندی غذاها، انطباق فازی و مدیریت هوشمند رزرو و درگاه پرداخت
# ==============================================================================

GENERIC_FOOD_WORDS = {
    'چلو', 'پلو', 'خوراک', 'ساندویچ', 'بسته', 'با', 'و', 'یا', 'نان',
    'دوغ', 'نوشیدنی', 'نیم', 'پرس', 'گرم', 'گرمی', 'عدد', 'عددی', 'بزرگ',
    'کوچک', 'متوسط', 'مخصوص', 'ساده', 'قوطی', 'لیوان', 'بسته بندی'
}

def parse_rial_amount(amount_str: Any) -> int:
    """تبدیل رشته ریال به عدد صحیح"""
    if isinstance(amount_str, (int, float)):
        return int(amount_str)
    if not amount_str:
        return 0
    clean = re.sub(r'[^0-9]', '', str(amount_str))
    return int(clean) if clean else 0


def normalize_persian(text: str) -> str:
    """استانداردسازی متون فارسی، حذف نیم‌فاصله، نویسه‌های زائد و ارقام"""
    if not text:
        return ""
    t = str(text)
    t = re.sub(r'[\u200c\u200b\u200e\u200f]', ' ', t)
    t = t.replace('ي', 'ی').replace('ك', 'ک').replace('ة', 'ه').replace('آ', 'ا')
    persian_digits = '۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩'
    english_digits = '01234567890123456789'
    for pd, ed in zip(persian_digits, english_digits):
        t = t.replace(pd, ed)
    t = re.sub(r'[\(\)\[\]\{\}\+\-_/\\:,!؟\*0-9]', ' ', t)
    t = re.sub(r'\s+', ' ', t).strip()
    return t


def normalize_spelling(text: str) -> str:
    """یکسان‌سازی املای متداول غذاها در دانشگاه اصفهان"""
    synonyms = {
        'شینسل': 'شنیسل',
        'قورمه': 'قرمه',
        'خورشت': 'خورش',
        'استانبولی': 'استامبلی',
        'باقلا': 'باقالا',
        'کباب کوبیده': 'کوبیده',
        'جوجه کباب': 'جوجه',
        'کتف و بال': 'بال'
    }
    t = text
    for k, v in synonyms.items():
        t = t.replace(k, v)
    return t


class PreferenceEngine:
    """موتور انطباق فازی و رتبه‌بندی غذاها بر اساس سلیقه کاربر"""

    @staticmethod
    def match_food(food_name: str, priority_list: List[str], blacklist: List[str]) -> Tuple[Optional[int], Optional[str], float]:
        n_food = normalize_spelling(normalize_persian(food_name))
        food_tokens = set(n_food.split())

        # ۱. بررسی لیست سیاه (Blacklist)
        for b in blacklist:
            n_b = normalize_spelling(normalize_persian(b))
            if not n_b:
                continue
            b_tokens = set(n_b.split()) - GENERIC_FOOD_WORDS
            if not b_tokens:
                b_tokens = set(n_b.split())
            if n_b in n_food or b_tokens.issubset(food_tokens):
                return -1, b, -1.0

        # ۲. بررسی لیست اولویت‌ها
        for rank, p in enumerate(priority_list, 1):
            n_p = normalize_spelling(normalize_persian(p))
            if not n_p:
                continue

            # الف) زیررشته مستقیم
            if n_p in n_food:
                return rank, p, 1.0

            p_tokens = set(n_p.split())
            dist_tokens = p_tokens - GENERIC_FOOD_WORDS
            if not dist_tokens:
                dist_tokens = p_tokens

            # ب) تطابق کامل کلمات کلیدی اصلی
            if dist_tokens.issubset(food_tokens):
                return rank, p, 0.95

            # ج) تطابق بیش از ۸۰٪ کلمات
            overlap = dist_tokens.intersection(food_tokens)
            if len(overlap) >= len(dist_tokens) and len(overlap) > 0:
                return rank, p, 0.85

        return None, None, 0.0

    @classmethod
    def rank_food_options(cls, foods: List[Dict[str, Any]], meal_name: str, preferences_cfg: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
        if meal_name == "ناهار":
            priorities = preferences_cfg.get("lunch_priority") or preferences_cfg.get("food_preferences") or []
        elif meal_name == "شام":
            priorities = preferences_cfg.get("dinner_priority") or preferences_cfg.get("food_preferences") or []
        else:
            priorities = preferences_cfg.get("food_preferences") or preferences_cfg.get("lunch_priority") or []

        blacklist = preferences_cfg.get("blacklist") or []

        matched_list = []
        unranked_list = []
        blacklisted_list = []

        for item in foods:
            food_name = item.get("name", "")
            rank, matched_p, score = cls.match_food(food_name, priorities, blacklist)

            item_copy = dict(item)
            item_copy["rank"] = rank
            item_copy["matched_priority"] = matched_p
            item_copy["match_score"] = score

            if rank == -1:
                blacklisted_list.append(item_copy)
            elif rank is not None:
                matched_list.append(item_copy)
            else:
                unranked_list.append(item_copy)

        def rem_key(x):
            rem = x.get("remaining", 0)
            return rem if isinstance(rem, int) else 9999

        matched_list.sort(key=lambda x: (x["rank"], -x["match_score"], -rem_key(x)))
        unranked_list.sort(key=lambda x: -rem_key(x))

        return matched_list, unranked_list, blacklisted_list


def parse_cafeteria_meal_items(caf_html: str, meal_name: str) -> List[Dict[str, Any]]:
    """استخراج تمام غذاهای ارائه شده در رستوران آزاد (کافه‌تریا) به همراه جزئیات کامل ردیف‌ها و کنترل‌ها"""
    table_map = {
        "صبحانه": "cphMain_grdBreakfast",
        "ناهار": "cphMain_grdLunch",
        "شام": "cphMain_grdDinner"
    }
    tbl_id = table_map.get(meal_name, "cphMain_grdLunch")
    m = re.search(r'(<table[^>]*id=\"' + tbl_id + r'\".*?</table>)', caf_html, re.DOTALL)
    if not m:
        return []

    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', m.group(1), re.DOTALL)
    items = []
    for r in rows[1:]:
        cols = [re.sub(r'<[^>]+>', ' ', c).strip() for c in re.findall(r'<t[hd][^>]*>(.*?)</t[hd]>', r, re.DOTALL)]
        if len(cols) > 6:
            food_name = cols[1]
            if not food_name or "داده ای برای نمایش" in food_name:
                continue

            subsidized_price = parse_rial_amount(cols[2])
            remaining_str = cols[6]
            try:
                rem_count = int(remaining_str)
            except ValueError:
                rem_count = 0

            chk_m = re.search(r'<input[^>]+type=[\"\']checkbox[\"\'][^>]*name=[\"\']([^\"\']+)[\"\']', r)
            chk_name = chk_m.group(1) if chk_m else ""
            
            chk_checked = 'chkReserve' in r and 'checked="checked"' in r
            hdn_reserved = bool(re.search(r'hdnIsReserved[^\"]*\"\s+value=\"True\"', r))
            is_reserved = chk_checked or hdn_reserved

            count_m = re.search(r'<select[^>]+name=[\"\']([^\"\']*drpNormalCount[^\"\']*)[\"\']', r)
            count_name = count_m.group(1) if count_m else ""

            items.append({
                "name": food_name,
                "price_int": subsidized_price,
                "price_str": f"{subsidized_price:,} ریال",
                "remaining": rem_count,
                "is_available": rem_count > 0,
                "is_reserved": is_reserved,
                "chk_name": chk_name,
                "count_name": count_name
            })
    return items


def check_already_reserved(caf_html: str, res_html: str, meal_name: str) -> Tuple[bool, str]:
    """بررسی اینکه آیا کاربر قبلاً برای این وعده غذایی رزرو کرده است یا خیر"""
    caf_items = parse_cafeteria_meal_items(caf_html, meal_name)
    for it in caf_items:
        if it.get("is_reserved"):
            return True, f"رستوران: {it['name']}"

    table_map = {
        "صبحانه": "cphMain_grdReservationBreakfast",
        "ناهار": "cphMain_grdReservationLunch",
        "شام": "cphMain_grdReservationDinner"
    }
    tbl_id = table_map.get(meal_name, "cphMain_grdReservationLunch")
    m = re.search(r'(<table[^>]*id=\"' + tbl_id + r'\".*?</table>)', res_html, re.DOTALL)
    if m:
        for r in re.findall(r'<tr[^>]*>(.*?)</tr>', m.group(1), re.DOTALL):
            if 'checked="checked"' in r:
                name_m = re.search(r'<select[^>]*drpFood[^>]*>.*?<option[^>]*selected=\"selected\"[^>]*>([^<]+)</option>', r, re.DOTALL)
                if name_m:
                    return True, f"سلف: {name_m.group(1).strip()}"
                return True, "سلف دانشجویی"

    return False, ""


def reserve_cafeteria_food(
    client: SFDClient,
    current_caf_html: str,
    meal_name: str,
    food_item: Dict[str, Any],
    restaurant_id: Optional[str] = None
) -> Tuple[bool, str, str, Optional[str]]:
    """
    ارسال درخواست ثبت رزرو غذا در رستوران آزاد (کافه‌تریا)
    انتخاب باکس غذا، تنظیم تعداد برابر ۱، کلیک روی ذخیره و دریافت آدرس صفحه پیش‌پرداخت (Pre-Payment)
    """
    form_data = {}
    for m in re.finditer(r'<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"', current_caf_html):
        form_data[m.group(1)] = m.group(2)

    for m in re.finditer(r'<select[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</select>', current_caf_html, re.DOTALL):
        val = extract_selected_option_value(m.group(2))
        if val:
            form_data[m.group(1)] = val

    for k in list(form_data.keys()):
        if any(x in k for x in ['btnSave', 'imgbtn', 'btnOk', 'btnLogin', 'btnPay']):
            del form_data[k]

    chk_name = food_item.get("chk_name")
    count_name = food_item.get("count_name")
    if chk_name:
        form_data[chk_name] = "on"
    if count_name:
        form_data[count_name] = "1"

    # اطمینان از مطابقت کامل دراپ‌داون‌های رستوران آزاد با شناسه رستوران انتخابی
    if restaurant_id:
        for d_key in ['ctl00$cphMain$drpSelfTitle_Lunch', 'ctl00$cphMain$drpSelfTitle_Dinner', 'ctl00$cphMain$drpSelfTitle_Breakfast']:
            if d_key in form_data:
                form_data[d_key] = str(restaurant_id)

    btn_map = {
        "صبحانه": ("ctl00$cphMain$btnSaveBreakfast", "ذخیره "),
        "ناهار": ("ctl00$cphMain$btnSaveLunch", "ذخیره ناهار"),
        "شام": ("ctl00$cphMain$btnSaveDinner", "ذخیره ")
    }
    btn_key, btn_val = btn_map.get(meal_name, ("ctl00$cphMain$btnSaveLunch", "ذخیره ناهار"))
    form_data[btn_key] = btn_val
    form_data["__EVENTTARGET"] = ""
    form_data["__EVENTARGUMENT"] = ""

    resp_html = client._request(
        "/Reservation/CafeteriaReservation.aspx",
        data=form_data,
        referer=f"{client.base_url}/Reservation/CafeteriaReservation.aspx"
    )

    err_match = re.search(r'toastify\([^\)]*\'([^\']*خطا[^\']*)\'', resp_html)
    if err_match:
        return False, err_match.group(1), resp_html, None

    if "ظرفیت به پایان رسیده" in resp_html:
        return False, "ظرفیت این غذا در رستوران به پایان رسیده است.", resp_html, None

    # بررسی استخراج CreditId و لینک صفحه پیش‌پرداخت (CafeteriaReservation_SelectBank.aspx)
    prepay_url = None
    cid_match = re.search(r'CafeteriaReservation_SelectBank\.aspx\?CreditId=(\d+)', resp_html)
    if not cid_match:
        cid_match = re.search(r'openBankWindow\s*\(\s*[\'"]?(\d+)', resp_html)
    if not cid_match:
        cid_match = re.search(r'name=[\"\'][^\"\']*hdnCreditId[^\"\']*[\"\']\s+[^>]*value=[\"\']([1-9]\d*)[\"\']', resp_html)
    if not cid_match:
        cid_match = re.search(r'id=[\"\'][^\"\']*hdnCreditId[^\"\']*[\"\']\s+[^>]*value=[\"\']([1-9]\d*)[\"\']', resp_html)
    if not cid_match:
        cid_match = re.search(r'value=[\"\']([1-9]\d*)[\"\'][^>]*name=[\"\'][^\"\']*hdnCreditId', resp_html)

    if cid_match:
        credit_id = cid_match.group(1)
        prepay_url = f"/Reservation/CafeteriaReservation_SelectBank.aspx?CreditId={credit_id}&ReserveType=daily"
        return True, "سفارش موقت با موفقیت در سامانه ثبت شد و آماده ورود به صفحه پیش‌پرداخت است.", resp_html, prepay_url

    if "tblSelectBank" in resp_html or "dataLstBank" in resp_html:
        m_cid = re.search(r'CreditId=(\d+)', resp_html)
        if m_cid:
            prepay_url = f"/Reservation/CafeteriaReservation_SelectBank.aspx?CreditId={m_cid.group(1)}&ReserveType=daily"
        else:
            prepay_url = "/Reservation/CafeteriaReservation_SelectBank.aspx"
        return True, "ورود مستقیم به صفحه پیش‌پرداخت رستوران انجام شد.", resp_html, prepay_url

    return True, "عملیات رزرو با موفقیت در سامانه ثبت شد.", resp_html, None


def calculate_smart_topup(deficit: int, payment_settings: Dict[str, Any]) -> int:
    """محاسبه هوشمند مبلغ شارژ بر اساس گام گرد کردن یا حداقل شارژ شتاب"""
    round_step = payment_settings.get("round_topup_to_nearest", 500000)
    default_topup = payment_settings.get("default_topup_amount", 1000000)
    
    if deficit <= 0:
        return default_topup
        
    rounded = math.ceil(deficit / round_step) * round_step
    return max(rounded, default_topup, 100000)


class ChromeCDPSession:
    """
    مدیریت نشست مستقیم و احراز هویت شده مرورگر کروم با تزریق کوکی‌ها از طریق Chrome DevTools Protocol (CDP)
    این ماژول کوکی‌های معتبر لاگین (ASP.NET_SessionId، keyId و __AntiXsrfToken) را مستقیماً درون مرورگر کروم
    تزریق می‌کند تا تمام تراکنش‌ها و صفحات بازگشت بانک (ePay_Callback.aspx) بدون نیاز به لاگین مجدد و
    به‌صورت بومی در دامنه دانشگاه انجام شوند.
    """
    def __init__(self, client: SFDClient, topup_amount: int = 0, initial_url: str = ""):
        self.client = client
        self.topup_amount = topup_amount
        self.initial_url = initial_url or f"{client.base_url}/ePay/ePay_Payment.aspx"
        self.chrome_proc: Optional[subprocess.Popen] = None
        self.port: int = 0
        self.callback_received: bool = False
        self.callback_is_success: bool = False
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None

    @staticmethod
    def find_chrome_binary() -> Optional[str]:
        candidates = [
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        ]
        for c in candidates:
            if os.path.exists(c):
                return c
        for cmd in ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser"]:
            try:
                res = subprocess.run(["which", cmd], capture_output=True, text=True)
                p = res.stdout.strip()
                if p and os.path.exists(p):
                    return p
            except Exception:
                pass
        return None

    def start(self) -> bool:
        if not HAS_WEBSOCKETS:
            return False
        chrome_bin = self.find_chrome_binary()
        if not chrome_bin:
            return False

        s = socket.socket()
        s.bind(('127.0.0.1', 0))
        self.port = s.getsockname()[1]
        s.close()

        prof_dir = os.path.expanduser('~/.sfd_chrome_profile')
        os.makedirs(prof_dir, exist_ok=True)

        try:
            self.chrome_proc = subprocess.Popen([
                chrome_bin,
                f'--remote-debugging-port={self.port}',
                f'--user-data-dir={prof_dir}',
                '--no-first-run',
                '--no-default-browser-check',
                'about:blank'
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            return False

        self._thread = threading.Thread(target=self._run_cdp_loop)
        self._thread.daemon = True
        self._thread.start()
        return True

    def _run_cdp_loop(self) -> None:
        asyncio.run(self._cdp_async_worker())

    async def _cdp_async_worker(self) -> None:
        ws_url = None
        for _ in range(35):
            if self._stop_event.is_set():
                return
            await asyncio.sleep(0.2)
            try:
                resp = urllib.request.urlopen(f'http://127.0.0.1:{self.port}/json', timeout=2)
                tabs = json.loads(resp.read().decode())
                if tabs:
                    ws_url = tabs[0].get('webSocketDebuggerUrl')
                    if ws_url:
                        break
            except Exception:
                pass

        if not ws_url:
            return

        try:
            async with websockets.connect(ws_url) as ws:
                msg_id = 1
                async def send(method, params=None):
                    nonlocal msg_id
                    msg_id += 1
                    await ws.send(json.dumps({'id': msg_id, 'method': method, 'params': params or {}}))
                    return msg_id

                await send('Page.enable')
                await send('Network.enable')
                await send('Runtime.enable')

                cookies = []
                for c in self.client.cookie_jar:
                    cookies.append({
                        'name': c.name,
                        'value': c.value,
                        'domain': 'sfd.ui.ac.ir',
                        'path': '/',
                        'secure': True,
                        'httpOnly': bool(c.has_nonstandard_attr('httponly') or c.has_nonstandard_attr('HttpOnly'))
                    })
                await send('Network.setCookies', {'cookies': cookies})
                await send('Page.navigate', {'url': self.initial_url})

                while not self._stop_event.is_set():
                    try:
                        msg = await asyncio.wait_for(ws.recv(), timeout=1.0)
                        data = json.loads(msg)
                        method = data.get('method')
                        if method == 'Page.loadEventFired' and 'ePay_Payment.aspx' in self.initial_url:
                            fmt_amt = f'{self.topup_amount:,}' if self.topup_amount else ''
                            js = f'''(function() {{
                                var amt = document.getElementById("cphMain_txtPaymentAmount");
                                if (amt && "{fmt_amt}") {{
                                    amt.value = "{fmt_amt}";
                                    amt.style.borderColor = "#2e7d32";
                                    amt.style.backgroundColor = "#e8f5e9";
                                }}
                                if (!document.getElementById('sfd_auth_banner')) {{
                                    var b = document.createElement("div");
                                    b.id = 'sfd_auth_banner';
                                    b.style.cssText = "background: linear-gradient(135deg, #1b5e20, #2e7d32); color: #fff; padding: 12px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 13px; text-align: center; border-bottom: 3px solid #004d40; box-shadow: 0 4px 12px rgba(0,0,0,0.25); position: sticky; top: 0; z-index: 9999999; direction: rtl;";
                                    b.innerHTML = "🔐 <strong>نشست فعال و احراز هویت مستقیم توسط دستیار هوشمند کالینان</strong> | درگاه شاپرک بدون نیاز به لاگین مجدد باز خواهد شد و پس از پرداخت یا انصراف در شاپرک، تاییدیه بانکی به‌طور مستقیم در همین صفحه و ترمینال ثبت می‌گردد.";
                                    document.body.insertBefore(b, document.body.firstChild);
                                }}
                            }})();'''
                            await send('Runtime.evaluate', {'expression': js})

                        elif method == 'Page.frameNavigated':
                            nav_url = data.get('params', {}).get('frame', {}).get('url', '')
                            if 'ePay_Callback.aspx' in nav_url or 'CafeteriaReservation_SelectBank.aspx' in nav_url:
                                self.callback_received = True

                    except asyncio.TimeoutError:
                        pass
        except Exception:
            pass

    def stop(self) -> None:
        self._stop_event.set()


class AuthenticatedPaymentBridge:
    """
    سرور پراکسی تونل احراز هویت محلی
    این ماژول یک سرور موقت روی لوکال‌هاست ایجاد می‌کند که تمام درخواست‌های مرورگر کاربر
    را با کوکی‌های نشست فعال پایتون (ASP.NET_SessionId، keyId و __AntiXsrfToken) به سامانه تغذیه می‌فرستد.
    همچنین بنر احراز هویت و مبالغ لازم را به‌طور خودکار در صفحات افزایش اعتبار یا پیش‌پرداخت تزریق می‌کند.
    """
    def __init__(self, client: SFDClient, topup_amount: int = 0, page_type: str = "self", restaurant_id: str = "14"):
        self.client = client
        self.topup_amount = topup_amount
        self.page_type = page_type  # "self" یا "cafeteria"
        self.restaurant_id = str(restaurant_id) if restaurant_id else "14"
        self.server: Optional[http.server.ThreadingHTTPServer] = None
        self.thread: Optional[threading.Thread] = None
        self.port: int = 0
        self.callback_received: bool = False
        self.callback_is_success: bool = False

    def start(self, initial_path: str = "/ePay/ePay_Payment.aspx") -> str:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(('127.0.0.1', 0))
        self.port = s.getsockname()[1]
        s.close()

        parent = self

        class TunnelHandler(http.server.BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass  # جلوگیری از نمایش لاگ‌های مزاحم در خروجی کنسول

            def do_OPTIONS(self):
                self.send_response(200)
                self.send_header('Access-Control-Allow-Origin', '*')
                self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
                self.send_header('Access-Control-Allow-Headers', '*')
                self.send_header('Access-Control-Allow-Private-Network', 'true')
                self.send_header('Content-Length', '0')
                self.end_headers()

            def _get_interceptor_script(self) -> str:
                return f"""
                <script type="text/javascript">
                (function() {{
                    var tunnelOrigin = window.location.origin;
                    var getCbUrl = function() {{
                        return tunnelOrigin + (window.location.pathname.indexOf("Cafeteria") !== -1 ? "/Reservation/CafeteriaReservation_SelectBank.aspx" : "/ePay/ePay_Callback.aspx");
                    }};

                    // ۱. رهگیری قطعی ارسال کلیه فرم‌ها از جمله فراخوانی‌های دستی form.submit()
                    var _realSubmit = HTMLFormElement.prototype.submit;
                    HTMLFormElement.prototype.submit = function() {{
                        try {{
                            var cb = getCbUrl();
                            var rInputs = this.querySelectorAll('input[name="RedirectURL"], input[name="RedirectUrl"], input[name="revertURL"]');
                            for (var i = 0; i < rInputs.length; i++) {{
                                rInputs[i].value = cb;
                            }}
                        }} catch(e) {{}}
                        return _realSubmit.apply(this, arguments);
                    }};

                    // ۲. رهگیری ساخت اینپوت‌های RedirectURL در DOM
                    var _realCreateElement = document.createElement;
                    document.createElement = function(tagName) {{
                        var el = _realCreateElement.apply(document, arguments);
                        if (tagName && String(tagName).toLowerCase() === 'input') {{
                            var _realSetAttribute = el.setAttribute;
                            el.setAttribute = function(name, val) {{
                                if (name && (name.toLowerCase() === 'value') && el.name && 
                                    (el.name.toLowerCase().indexOf('redirect') !== -1 || el.name.toLowerCase().indexOf('revert') !== -1)) {{
                                    val = getCbUrl();
                                }}
                                return _realSetAttribute.call(el, name, val);
                            }};
                        }}
                        return el;
                    }};

                    // ۳. تعریف پراکسی‌های متغیرهای سراسری postToSadad و postToBehdad
                    function wrapBank(fn) {{
                        return function() {{
                            var args = Array.prototype.slice.call(arguments);
                            var cb = getCbUrl();
                            if (args.length >= 7) {{
                                args[6] = cb; // پارامتر revertURL در postToSadad
                            }} else if (args.length >= 4) {{
                                args[3] = cb; // پارامتر RedirectUrl در postToBehdad
                            }}
                            return fn.apply(this, args);
                        }};
                    }}

                    var _sadadVal = window.postToSadad;
                    if (typeof _sadadVal === 'function') _sadadVal = wrapBank(_sadadVal);
                    Object.defineProperty(window, 'postToSadad', {{
                        configurable: true,
                        enumerable: true,
                        get: function() {{ return _sadadVal; }},
                        set: function(fn) {{ _sadadVal = wrapBank(fn); }}
                    }});

                    var _behdadVal = window.postToBehdad;
                    if (typeof _behdadVal === 'function') _behdadVal = wrapBank(_behdadVal);
                    Object.defineProperty(window, 'postToBehdad', {{
                        configurable: true,
                        enumerable: true,
                        get: function() {{ return _behdadVal; }},
                        set: function(fn) {{ _behdadVal = wrapBank(fn); }}
                    }});
                }})();
                </script>
                """

            def do_GET(self):
                target_url = f"{parent.client.base_url}{self.path}"
                req = urllib.request.Request(target_url, headers={
                    'User-Agent': parent.client.headers['User-Agent'],
                    'Referer': f"{parent.client.base_url}/"
                })
                try:
                    with parent.client.opener.open(req, timeout=25) as resp:
                        content = resp.read()
                        status = resp.status
                        headers = resp.getheaders()

                        # بازنویسی مستقیم ارجاعات در متن کد اسکریپت
                        tunnel_cb_str = f"window.location.origin + '/ePay/ePay_Callback.aspx'".encode('utf-8')
                        content = re.sub(
                            rb'hdnrevertURL\.setAttribute\(\s*["\']value["\']\s*,\s*revertURL\s*\)',
                            rb'hdnrevertURL.setAttribute("value", ' + tunnel_cb_str + rb')',
                            content
                        )
                        content = re.sub(
                            rb'hdnRedirectUrl\.setAttribute\(\s*["\']value["\']\s*,\s*RedirectUrl\s*\)',
                            rb'hdnRedirectUrl.setAttribute("value", ' + tunnel_cb_str + rb')',
                            content
                        )
                        content = re.sub(
                            rb'hdnRedirectURL\.setAttribute\(\s*["\']value["\']\s*,\s*RedirectURL\s*\)',
                            rb'hdnRedirectURL.setAttribute("value", ' + tunnel_cb_str + rb')',
                            content
                        )

                        tunnel_cb = f"http://127.0.0.1:{parent.port}/ePay/ePay_Callback.aspx".encode('utf-8')
                        tunnel_caf_cb = f"http://127.0.0.1:{parent.port}/Reservation/CafeteriaReservation_SelectBank.aspx".encode('utf-8')
                        content = content.replace(b"https://sfd.ui.ac.ir/ePay/ePay_Callback.aspx", tunnel_cb)
                        content = content.replace(b"http://sfd.ui.ac.ir/ePay/ePay_Callback.aspx", tunnel_cb)
                        content = content.replace(b"https://sfd.ui.ac.ir/Reservation/CafeteriaReservation_SelectBank.aspx", tunnel_caf_cb)
                        content = content.replace(b"http://sfd.ui.ac.ir/Reservation/CafeteriaReservation_SelectBank.aspx", tunnel_caf_cb)

                        if "ePay_Payment.aspx" in self.path:
                            html = content.decode('utf-8', errors='ignore')
                            formatted_amt = f"{parent.topup_amount:,}"
                            html = re.sub(
                                r'(id=\"cphMain_txtPaymentAmount\"[^>]*value=\")[^\"]*(\")',
                                r'\g<1>' + formatted_amt + r'\g<2>',
                                html
                            )
                            html = re.sub(
                                r'(name=\"ctl00\$cphMain\$txtPaymentAmount\"[^>]*value=\")[^\"]*(\")',
                                r'\g<1>' + formatted_amt + r'\g<2>',
                                html
                            )
                            banner = f"""
                            <div style="background: linear-gradient(135deg, #1b5e20, #2e7d32); color: #ffffff; padding: 12px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 13px; text-align: center; border-bottom: 3px solid #004d40; box-shadow: 0 4px 12px rgba(0,0,0,0.25); position: sticky; top: 0; z-index: 9999999; direction: rtl;">
                                🔐 <strong>نشست فعال و احراز هویت شده توسط دستیار هوشمند کالینان</strong> | 
                                مبلغ پیشنهادی افزایش اعتبار: <span style="background: #ffeb3b; color: #000; padding: 2px 8px; border-radius: 4px; font-weight: bold;">{formatted_amt} ریال</span> به‌صورت خودکار در کادر مبلغ درج گردید.
                                <br><span style="font-size: 11px; opacity: 0.9;">پس از انتخاب درگاه و انجام پرداخت در شاپرک، تاییدیه بانکی و افزایش موجودی به‌طور خودکار در همین صفحه و ترمینال ثبت خواهد شد.</span>
                            </div>
                            """
                            html = re.sub(r'(<body[^>]*>)', r'\1\n' + banner, html, count=1, flags=re.IGNORECASE)
                            html = re.sub(r'(<head[^>]*>)', r'\1\n' + self._get_interceptor_script(), html, count=1, flags=re.IGNORECASE)
                            content = html.encode('utf-8')

                        elif "ePay_Callback.aspx" in self.path:
                            print(colorize(f"\n   📥 [پل پرداخت] پاسخ شاپرک دریافت شد (GET /ePay/ePay_Callback.aspx) - در حال وریفای با سامانه دانشگاه...", Colors.CYAN + Colors.BOLD))
                            parent.callback_received = True
                            html = content.decode('utf-8', errors='ignore')
                            is_paid = any(k in html for k in ["تراکنش با موفقیت", "عملیات موفق", "کد پیگیری", "شماره تراکنش", "موفق"]) and "ناموفق" not in html
                            parent.callback_is_success = is_paid

                            if is_paid:
                                banner = f"""
                                <div style="background: linear-gradient(135deg, #1b5e20, #2e7d32); color: #ffffff; padding: 16px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 14px; text-align: center; border-bottom: 3px solid #004d40; box-shadow: 0 4px 12px rgba(0,0,0,0.3); position: sticky; top: 0; z-index: 9999999; direction: rtl;">
                                    🎉 <strong>پرداخت با موفقیت در شاپرک تایید شد و اعتبار حساب شما افزایش یافت!</strong>
                                    <br><span style="font-size: 12px; opacity: 0.95;">مشخصات تراکنش در جدول زیر ثبت گردید. برنامه در ترمینال ادامه فرآیند رزرو را پیگیری می‌کند؛ می‌توانید این برگه را ببندید.</span>
                                </div>
                                """
                            else:
                                banner = f"""
                                <div style="background: linear-gradient(135deg, #c62828, #b71c1c); color: #ffffff; padding: 14px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 13px; text-align: center; border-bottom: 3px solid #7f0000; box-shadow: 0 4px 12px rgba(0,0,0,0.3); position: sticky; top: 0; z-index: 9999999; direction: rtl;">
                                    ⚠️ <strong>نتیجه تراکنش بانکی در سامانه دریافت گردید.</strong> جزئیات و کد پیگیری در جدول زیر درج شده است.
                                </div>
                                """
                            html = re.sub(r'(<body[^>]*>)', r'\1\n' + banner, html, count=1, flags=re.IGNORECASE)
                            content = html.encode('utf-8')

                        elif "CafeteriaReservation_SelectBank.aspx" in self.path:
                            html = content.decode('utf-8', errors='ignore')
                            is_paid = any(k in html for k in ["تراکنش با موفقیت", "عملیات موفق", "کد پیگیری", "شماره تراکنش", "موفقیت"])
                            if is_paid:
                                parent.callback_received = True
                                parent.callback_is_success = True
                                banner = f"""
                                <div style="background: linear-gradient(135deg, #1b5e20, #2e7d32); color: #ffffff; padding: 14px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 14px; text-align: center; border-bottom: 3px solid #004d40; box-shadow: 0 4px 12px rgba(0,0,0,0.3); position: sticky; top: 0; z-index: 9999999; direction: rtl;">
                                    🎉 <strong>پرداخت با موفقیت انجام شد و رزرو شما نهایی گردید!</strong>
                                    <br><span style="font-size: 12px; opacity: 0.95;">در حال هدایت خودکار به صفحه جدول غذاهای تایید شده رستوران...</span>
                                </div>
                                <script>
                                    setTimeout(function() {{
                                        window.location.href = '/Reservation/CafeteriaReservation.aspx?IsRedirect=True';
                                    }}, 2000);
                                </script>
                                """
                            else:
                                banner = f"""
                                <div style="background: linear-gradient(135deg, #0d47a1, #1565c0); color: #ffffff; padding: 13px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 13px; text-align: center; border-bottom: 3px solid #002171; box-shadow: 0 4px 12px rgba(0,0,0,0.3); position: sticky; top: 0; z-index: 9999999; direction: rtl;">
                                    🍽️ <strong>پیش‌پرداخت غذای رستوران آزاد | احراز هویت خودکار فعال است</strong>
                                    <br><span style="font-size: 12px; opacity: 0.95;">سفارش موقت غذای شما در سامانه کالینان ثبت شد. لطفاً بر روی آیکون درگاه بانک کلیک نمایید تا به شاپرک متصل شوید.</span>
                                </div>
                                """
                            html = re.sub(r'(<body[^>]*>)', r'\1\n' + banner, html, count=1, flags=re.IGNORECASE)
                            html = re.sub(r'(<head[^>]*>)', r'\1\n' + self._get_interceptor_script(), html, count=1, flags=re.IGNORECASE)
                            content = html.encode('utf-8')

                        elif "CafeteriaReservation.aspx" in self.path:
                            html = content.decode('utf-8', errors='ignore')
                            if parent.restaurant_id:
                                try:
                                    m_l = re.search(r'<select[^>]*drpSelfTitle_Lunch[^>]*>(.*?)</select>', html, re.DOTALL)
                                    curr_rid = extract_selected_option_value(m_l.group(1)) if m_l else ""
                                    if curr_rid != parent.restaurant_id:
                                        html = parent.client.switch_cafeteria_restaurant(html, parent.restaurant_id)
                                except Exception:
                                    pass
                            banner = f"""
                            <div style="background: linear-gradient(135deg, #1b5e20, #2e7d32); color: #ffffff; padding: 13px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 13px; text-align: center; border-bottom: 3px solid #004d40; box-shadow: 0 4px 12px rgba(0,0,0,0.25); position: sticky; top: 0; z-index: 9999999; direction: rtl;">
                                ✅ <strong>سفارش غذای رستوران آزاد با موفقیت ثبت، پرداخت و نهایی شد!</strong> وضعیت رزرو در جدول زیر قابل مشاهده است.
                            </div>
                            """
                            html = re.sub(r'(<body[^>]*>)', r'\1\n' + banner, html, count=1, flags=re.IGNORECASE)
                            content = html.encode('utf-8')

                        self.send_response(status)
                        for k, v in headers:
                            if k.lower() in ['content-type', 'location', 'set-cookie']:
                                self.send_header(k, v)
                        self.send_header('Access-Control-Allow-Origin', '*')
                        self.send_header('Access-Control-Allow-Private-Network', 'true')
                        self.send_header('Content-Length', str(len(content)))
                        self.end_headers()
                        self.wfile.write(content)
                except Exception as e:
                    self.send_error(500, f"Tunnel Error: {e}")

            def do_POST(self):
                clen = int(self.headers.get('Content-Length', 0))
                pdata = self.rfile.read(clen)
                target_url = f"{parent.client.base_url}{self.path}"
                hdrs = {
                    'User-Agent': parent.client.headers['User-Agent'],
                    'Referer': f"{parent.client.base_url}{self.path}",
                    'Origin': parent.client.base_url,
                    'Content-Type': self.headers.get('Content-Type', 'application/x-www-form-urlencoded')
                }
                if 'X-MicrosoftAjax' in self.headers:
                    hdrs['X-MicrosoftAjax'] = self.headers['X-MicrosoftAjax']
                if 'X-Requested-With' in self.headers:
                    hdrs['X-Requested-With'] = self.headers['X-Requested-With']

                req = urllib.request.Request(target_url, data=pdata, headers=hdrs)
                try:
                    with parent.client.opener.open(req, timeout=30) as resp:
                        content = resp.read()
                        status = resp.status
                        headers = resp.getheaders()

                        # بازنویسی ارجاعات callback به آدرس محلی در پاسخ‌های POST و AJAX Delta
                        tunnel_cb_str = f"window.location.origin + '/ePay/ePay_Callback.aspx'".encode('utf-8')
                        content = re.sub(
                            rb'hdnrevertURL\.setAttribute\(\s*["\']value["\']\s*,\s*revertURL\s*\)',
                            rb'hdnrevertURL.setAttribute("value", ' + tunnel_cb_str + rb')',
                            content
                        )
                        content = re.sub(
                            rb'hdnRedirectUrl\.setAttribute\(\s*["\']value["\']\s*,\s*RedirectUrl\s*\)',
                            rb'hdnRedirectUrl.setAttribute("value", ' + tunnel_cb_str + rb')',
                            content
                        )
                        content = re.sub(
                            rb'hdnRedirectURL\.setAttribute\(\s*["\']value["\']\s*,\s*RedirectURL\s*\)',
                            rb'hdnRedirectURL.setAttribute("value", ' + tunnel_cb_str + rb')',
                            content
                        )

                        tunnel_cb = f"http://127.0.0.1:{parent.port}/ePay/ePay_Callback.aspx".encode('utf-8')
                        tunnel_caf_cb = f"http://127.0.0.1:{parent.port}/Reservation/CafeteriaReservation_SelectBank.aspx".encode('utf-8')
                        content = content.replace(b"https://sfd.ui.ac.ir/ePay/ePay_Callback.aspx", tunnel_cb)
                        content = content.replace(b"http://sfd.ui.ac.ir/ePay/ePay_Callback.aspx", tunnel_cb)
                        content = content.replace(b"https://sfd.ui.ac.ir/Reservation/CafeteriaReservation_SelectBank.aspx", tunnel_caf_cb)
                        content = content.replace(b"http://sfd.ui.ac.ir/Reservation/CafeteriaReservation_SelectBank.aspx", tunnel_caf_cb)

                        if "ePay_Callback.aspx" in self.path:
                            print(colorize(f"\n   📥 [پل پرداخت] پاسخ شاپرک دریافت شد (POST /ePay/ePay_Callback.aspx) - در حال وریفای با سامانه دانشگاه...", Colors.CYAN + Colors.BOLD))
                            parent.callback_received = True
                            html = content.decode('utf-8', errors='ignore')
                            is_paid = any(k in html for k in ["تراکنش با موفقیت", "عملیات موفق", "کد پیگیری", "شماره تراکنش", "موفق"]) and "ناموفق" not in html
                            parent.callback_is_success = is_paid

                            if is_paid:
                                banner = f"""
                                <div style="background: linear-gradient(135deg, #1b5e20, #2e7d32); color: #ffffff; padding: 16px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 14px; text-align: center; border-bottom: 3px solid #004d40; box-shadow: 0 4px 12px rgba(0,0,0,0.3); position: sticky; top: 0; z-index: 9999999; direction: rtl;">
                                    🎉 <strong>پرداخت با موفقیت در شاپرک تایید شد و اعتبار حساب شما افزایش یافت!</strong>
                                    <br><span style="font-size: 12px; opacity: 0.95;">مشخصات تراکنش در جدول زیر ثبت گردید. برنامه در ترمینال ادامه فرآیند رزرو را پیگیری می‌کند؛ می‌توانید این برگه را ببندید.</span>
                                </div>
                                """
                            else:
                                banner = f"""
                                <div style="background: linear-gradient(135deg, #c62828, #b71c1c); color: #ffffff; padding: 14px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 13px; text-align: center; border-bottom: 3px solid #7f0000; box-shadow: 0 4px 12px rgba(0,0,0,0.3); position: sticky; top: 0; z-index: 9999999; direction: rtl;">
                                    ⚠️ <strong>نتیجه تراکنش بانکی در سامانه دریافت گردید.</strong> جزئیات و کد پیگیری در جدول زیر درج شده است.
                                </div>
                                """
                            html = re.sub(r'(<body[^>]*>)', r'\1\n' + banner, html, count=1, flags=re.IGNORECASE)
                            content = html.encode('utf-8')

                        elif "CafeteriaReservation_SelectBank.aspx" in self.path:
                            html = content.decode('utf-8', errors='ignore')
                            is_paid = any(k in html for k in ["تراکنش با موفقیت", "عملیات موفق", "کد پیگیری", "شماره تراکنش", "موفقیت"])
                            if is_paid:
                                parent.callback_received = True
                                parent.callback_is_success = True
                                banner = f"""
                                <div style="background: linear-gradient(135deg, #1b5e20, #2e7d32); color: #ffffff; padding: 14px 20px; font-family: Tahoma, 'B Yekan', sans-serif; font-size: 14px; text-align: center; border-bottom: 3px solid #004d40; box-shadow: 0 4px 12px rgba(0,0,0,0.3); position: sticky; top: 0; z-index: 9999999; direction: rtl;">
                                    🎉 <strong>پرداخت با موفقیت انجام شد و رزرو شما نهایی گردید!</strong>
                                    <br><span style="font-size: 12px; opacity: 0.95;">در حال هدایت خودکار به صفحه جدول غذاهای تایید شده رستوران...</span>
                                </div>
                                <script>
                                    setTimeout(function() {{
                                        window.location.href = '/Reservation/CafeteriaReservation.aspx?IsRedirect=True';
                                    }}, 2000);
                                </script>
                                """
                                html = re.sub(r'(<body[^>]*>)', r'\1\n' + banner, html, count=1, flags=re.IGNORECASE)
                                content = html.encode('utf-8')

                        self.send_response(resp.status)
                        for k, v in resp.getheaders():
                            if k.lower() in ['content-type', 'location', 'set-cookie']:
                                self.send_header(k, v)
                        self.send_header('Access-Control-Allow-Origin', '*')
                        self.send_header('Access-Control-Allow-Private-Network', 'true')
                        self.send_header('Content-Length', str(len(content)))
                        self.end_headers()
                        self.wfile.write(content)
                except Exception as e:
                    self.send_error(500, f"Tunnel Error: {e}")

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', self.port), TunnelHandler)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.daemon = True
        self.thread.start()
        return f"http://127.0.0.1:{self.port}{initial_path}"

    def stop(self) -> None:
        if self.server:
            try:
                self.server.shutdown()
                self.server.server_close()
            except Exception:
                pass


def launch_authenticated_payment_session(
    client: SFDClient,
    topup_amount: int = 0,
    page_type: str = "self",
    initial_path: str = "/ePay/ePay_Payment.aspx",
    restaurant_id: str = "14",
    auto_open_browser: bool = True
) -> Tuple[Any, str]:
    """
    راه‌اندازی هوشمند نشست پرداخت با احراز هویت مستقیم
    ابتدا تلاش می‌کند مرورگر کروم را با تزریق مستقیم کوکی‌های نشست (CDP) باز کند تا
    تمام بازگشت‌ها و تاییدهای بانکی مستقیماً در دامنه رسمی دانشگاه انجام شود.
    در صورت عدم دسترسی به کروم یا وب‌سوکت، به سرور تونل احراز هویت محلی سوئیچ می‌کند.
    """
    initial_url = f"{client.base_url}{initial_path}"
    if auto_open_browser and HAS_WEBSOCKETS:
        try:
            cdp = ChromeCDPSession(client, topup_amount=topup_amount, initial_url=initial_url)
            if cdp.start():
                return cdp, initial_url
        except Exception:
            pass

    bridge = AuthenticatedPaymentBridge(
        client,
        topup_amount=topup_amount,
        page_type=page_type,
        restaurant_id=restaurant_id
    )
    tunnel_url = bridge.start(initial_path=initial_path)
    if auto_open_browser:
        try:
            webbrowser.open(tunnel_url)
        except Exception:
            pass
    return bridge, tunnel_url


def prompt_and_wait_for_restaurant_payment(
    client: SFDClient,
    food_name: str,
    food_price: int,
    prepay_url: str,
    meal_name: str,
    payment_settings: Dict[str, Any],
    restaurant_id: str = "14"
) -> bool:
    """
    مدیریت هوشمند صفحه پیش‌پرداخت غذای رستوران (کافه‌تریا)
    ایجاد نشست احراز هویت شده و باز کردن مستقیم در مرورگر جهت پرداخت شاپرک در مهلت ۳ دقیقه‌ای کالینان
    """
    direct_payment_url = f"{client.base_url}{prepay_url}"
    auto_open = payment_settings.get("auto_open_browser", True)
    session, target_url = launch_authenticated_payment_session(
        client,
        topup_amount=food_price,
        page_type="cafeteria",
        initial_path=prepay_url,
        restaurant_id=restaurant_id,
        auto_open_browser=auto_open
    )

    print("\n" + colorize("=" * 80, Colors.CYAN))
    print(colorize(" 🍽️ ورود به مرحله پیش‌پرداخت غذای رستوران آزاد (یاس ۱ و ۲) ", Colors.HEADER + Colors.BOLD).center(80))
    print(colorize("=" * 80, Colors.CYAN))
    print(colorize(f" 🍱 غذای انتخاب‌شده: «{food_name}» ({meal_name})", Colors.BOLD + Colors.YELLOW))
    print(colorize(f" 💳 مبلغ قابل پرداخت: {food_price:,} ریال", Colors.BOLD + Colors.GREEN))
    print(colorize(" ⚠️  توجه: سفارش موقت با موفقیت ثبت شد. طبق قوانین سامانه کالینان، شما حداکثر ۳ دقیقه برای پرداخت در درگاه بانکی مهلت دارید.", Colors.YELLOW + Colors.BOLD))
    if isinstance(session, ChromeCDPSession):
        print(colorize(" 🔒 نشست احراز هویت شده فعال: مرورگر کروم مستقیماً با نشست فعال و لاگین‌شده در کالینان باز شد.", Colors.GREEN + Colors.BOLD))
        print(colorize(f" 🔗 لینک پرداخت فعال (احراز هویت شده):\n    {target_url}", Colors.BOLD + Colors.BLUE))
    elif "127.0.0.1" in target_url:
        print(colorize(" 🔒 نشست احراز هویت شده فعال: مرورگر شما از طریق تونل محلی احراز هویت شده باز شد.", Colors.GREEN))
        print(colorize(f" 🔗 لینک پرداخت فعال (لاگین خودکار):\n    {target_url}", Colors.BOLD + Colors.BLUE))
        print(colorize(f" 🌐 لینک مستقیم سامانه (در صورت نیاز):\n    {direct_payment_url}", Colors.DIM))
    else:
        print(colorize(f" 🔗 لینک پرداخت:\n    {direct_payment_url}", Colors.BOLD + Colors.BLUE))
    print(colorize("=" * 80, Colors.CYAN) + "\n")

    poll_interval = payment_settings.get("poll_interval_seconds", 5)
    max_wait = min(payment_settings.get("max_poll_wait_seconds", 180), 180)  # مهلت ۳ دقیقه‌ای کالینان

    print(colorize("⏳ در انتظار تکمیل پرداخت توسط شما در درگاه شاپرک...", Colors.YELLOW + Colors.BOLD))
    print(colorize("💡 پس از پرداخت در درگاه بانک، کلید [Enter] را در این ترمینال فشار دهید (یا سیستم هر چند ثانیه خودکار استعلام می‌کند).", Colors.CYAN))
    print(colorize("   (برای انصراف کلید Ctrl+C را فشار دهید)\n", Colors.DIM))

    start_time = time.time()
    last_poll = 0

    try:
        while time.time() - start_time < max_wait:
            user_pressed_enter = False
            if sys.stdin.isatty():
                try:
                    rlist, _, _ = select.select([sys.stdin], [], [], 0.0)
                    if rlist:
                        sys.stdin.readline()
                        user_pressed_enter = True
                except Exception:
                    pass

            now = time.time()
            cb_triggered = session and getattr(session, 'callback_received', False)
            if user_pressed_enter or cb_triggered or (now - last_poll >= poll_interval):
                last_poll = now
                if user_pressed_enter:
                    print(colorize("🔄 استعلام فوری وضعیت رزرو رستوران به درخواست کاربر...", Colors.CYAN))
                elif cb_triggered:
                    print(colorize("📥 تاییدیه بازگشت از درگاه شاپرک دریافت شد! در حال استعلام وضعیت سفارش...", Colors.CYAN + Colors.BOLD))
                    session.callback_received = False

                try:
                    caf_page = client.get_cafeteria_page()
                    if restaurant_id:
                        caf_page = client.switch_cafeteria_restaurant(caf_page, restaurant_id)
                    res_page = client.get_reservation_page()
                    is_res, res_text = check_already_reserved(caf_page, res_page, meal_name)
                    elapsed = int(now - start_time)

                    # استعلام تکمیلی از صفحه پیش‌پرداخت در صورتی که هنوز در جدول رستوران ثبت نشده باشد
                    if not is_res:
                        try:
                            ver_html = client._request(prepay_url)
                            if any(w in ver_html for w in ["تراکنش با موفقیت", "عملیات موفق", "کد پیگیری", "شماره تراکنش", "پرداخت با موفقیت"]):
                                is_res = True
                                res_text = f"رستوران: {food_name} (تایید بانکی)"
                        except Exception:
                            pass

                    if is_res:
                        print(colorize(f"\n🎉 پرداخت با موفقیت تایید شد! رزرو غذای رستوران نهایی گردید: «{res_text}» (زمان سپری‌شده: {elapsed} ثانیه)", Colors.GREEN + Colors.BOLD))
                        return True
                    else:
                        remaining_time = max(0, int(max_wait - elapsed))
                        print(colorize(f"   ⏳ [{elapsed}s / باقی‌مانده: {remaining_time}s] در انتظار تکمیل پرداخت در درگاه بانکی...", Colors.DIM))
                except Exception:
                    pass

            time.sleep(1.0)
    except KeyboardInterrupt:
        print(colorize("\n🛑 عملیات انتظار توسط کاربر لغو گردید.", Colors.YELLOW))
        return False
    finally:
        if session:
            session.stop()

    print(colorize(f"\n❌ مهلت ۳ دقیقه‌ای پیش‌پرداخت به پایان رسید یا پرداخت انجام نشد.", Colors.RED))
    return False


def prompt_and_wait_for_payment(client: SFDClient, food_price: int, current_credit: int, credit_type: str, payment_settings: Dict[str, Any]) -> Tuple[bool, int]:
    """
    مدیریت هوشمند کسری موجودی، ایجاد نشست احراز هویت شده برای مرورگر و انتظار برای تایید افزایش اعتبار
    """
    deficit = food_price - current_credit
    topup_amount = calculate_smart_topup(deficit, payment_settings)
    direct_payment_url = f"{client.base_url}/ePay/ePay_Payment.aspx"
    auto_open = payment_settings.get("auto_open_browser", True)

    session, target_url = launch_authenticated_payment_session(
        client,
        topup_amount=topup_amount,
        page_type="self",
        initial_path="/ePay/ePay_Payment.aspx",
        auto_open_browser=auto_open
    )

    print("\n" + colorize("!" * 80, Colors.YELLOW))
    print(colorize(f"⚠️  موجودی شما کافی نیست (موجودی: {current_credit:,} ریال، قیمت غذا: {food_price:,} ریال، کسری: {deficit:,} ریال).", Colors.YELLOW + Colors.BOLD))
    print(colorize(f"💳  مبلغ پیشنهادی جهت افزایش اعتبار: {topup_amount:,} ریال", Colors.CYAN + Colors.BOLD))
    if isinstance(session, ChromeCDPSession):
        print(colorize("🔒  نشست احراز هویت شده فعال: مرورگر کروم مستقیماً با نشست فعال و لاگین‌شده در کالینان باز شد.", Colors.GREEN + Colors.BOLD))
        print(colorize(f"🔗  لینک پرداخت فعال (احراز هویت شده):\n    {target_url}", Colors.BOLD + Colors.BLUE))
    elif "127.0.0.1" in target_url:
        print(colorize("🔒  نشست احراز هویت شده فعال: مرورگر شما مستقیماً با نشست فعال و لاگین‌شده باز خواهد شد.", Colors.GREEN + Colors.BOLD))
        print(colorize(f"🔗  لینک تونل پرداخت (لاگین خودکار):\n    {target_url}", Colors.BOLD + Colors.BLUE))
        print(colorize(f"🌐  لینک مستقیم سامانه (در صورت نیاز):\n    {direct_payment_url}", Colors.DIM))
    else:
        print(colorize(f"🔗  لینک ورود به درگاه پرداخت:\n    {direct_payment_url}", Colors.BOLD + Colors.BLUE))
    print(colorize("!" * 80, Colors.YELLOW) + "\n")

    poll_interval = payment_settings.get("poll_interval_seconds", 5)
    max_wait = payment_settings.get("max_poll_wait_seconds", 300)

    print(colorize("⏳ در انتظار افزایش اعتبار توسط شما...", Colors.YELLOW + Colors.BOLD))
    print(colorize("💡 پس از پرداخت موفق در درگاه بانک، کلید [Enter] را در این ترمینال فشار دهید (یا سیستم هر چند ثانیه خودکار استعلام می‌کند).", Colors.CYAN))
    print(colorize("   (برای انصراف کلید Ctrl+C را فشار دهید)\n", Colors.DIM))

    start_time = time.time()
    last_poll = 0
    updated_credit = current_credit

    try:
        while time.time() - start_time < max_wait:
            user_pressed_enter = False
            if sys.stdin.isatty():
                try:
                    rlist, _, _ = select.select([sys.stdin], [], [], 0.0)
                    if rlist:
                        sys.stdin.readline()
                        user_pressed_enter = True
                except Exception:
                    pass

            now = time.time()
            cb_triggered = session and getattr(session, 'callback_received', False)
            if user_pressed_enter or cb_triggered or (now - last_poll >= poll_interval):
                last_poll = now
                if user_pressed_enter:
                    print(colorize("🔄 استعلام فوری موجودی به درخواست کاربر...", Colors.CYAN))
                elif cb_triggered:
                    print(colorize("📥 بازگشت از درگاه شاپرک دریافت شد! در حال استعلام موجودی جدید...", Colors.CYAN + Colors.BOLD))
                    session.callback_received = False

                try:
                    page = client.get_reservation_page()
                    info = parse_student_info(page)
                    raw_cred = info.get("restaurant_credit" if credit_type == "restaurant" else "self_credit", "0")
                    updated_credit = parse_rial_amount(raw_cred)
                    elapsed = int(now - start_time)

                    if updated_credit >= food_price:
                        print(colorize(f"\n🎉 افزایش اعتبار تایید شد! موجودی جدید: {updated_credit:,} ریال (زمان سپری‌شده: {elapsed} ثانیه)", Colors.GREEN + Colors.BOLD))
                        return True, updated_credit
                    elif cb_triggered:
                        print(colorize(f"   ⚠️ تراکنش در سامانه ثبت شد اما موجودی تغییر نکرد (احتمالاً انصراف از پرداخت یا تراکنش ناموفق). موجودی فعلی: {updated_credit:,} ریال", Colors.YELLOW))
                    else:
                        print(colorize(f"   ⏳ [{elapsed}s] موجودی فعلی: {updated_credit:,} ریال (نیاز به: {food_price:,} ریال)...", Colors.DIM))
                except Exception:
                    pass

            time.sleep(1.0)
    except KeyboardInterrupt:
        print(colorize("\n🛑 عملیات انتظار توسط کاربر لغو گردید.", Colors.YELLOW))
        return False, updated_credit
    finally:
        if session:
            session.stop()

    print(colorize(f"\n❌ مهلت انتظار ({max_wait} ثانیه) به پایان رسید و موجودی افزایش نیافت.", Colors.RED))
    return False, updated_credit


def do_manual_topup(
    client: SFDClient,
    amount: int,
    credit_type: str = "self",
    payment_settings: Optional[Dict[str, Any]] = None
) -> bool:
    """
    افزایش دستی و دلخواه اعتبار سلف عادی یا رستوران آزاد
    ایجاد نشست احراز هویت شده، درج مبلغ در درگاه و استعلام تایید پرداخت
    """
    if payment_settings is None:
        payment_settings = {}

    page = client.get_reservation_page()
    info = parse_student_info(page)
    init_self_cred = parse_rial_amount(info.get("self_credit", "0"))
    init_rest_cred = parse_rial_amount(info.get("restaurant_credit", "0"))
    init_credit = init_rest_cred if credit_type == "restaurant" else init_self_cred

    target_type_label = "رستوران آزاد" if credit_type == "restaurant" else "سلف عادی"
    direct_payment_url = f"{client.base_url}/ePay/ePay_Payment.aspx"
    auto_open = payment_settings.get("auto_open_browser", True)

    print("\n" + "=" * 80)
    print(colorize(f" 💳 ماژول افزایش دستی اعتبار حساب {target_type_label} ", Colors.HEADER + Colors.BOLD).center(80))
    print("=" * 80)
    print(colorize(f" 👤 دانشجو: {info['name']} ({info['student_id']})", Colors.BOLD))
    print(colorize(f" 💰 موجودی فعلی {target_type_label}: {init_credit:,} ریال", Colors.YELLOW + Colors.BOLD))
    print(colorize(f" ➕ مبلغ درخواستی جهت افزایش: {amount:,} ریال", Colors.GREEN + Colors.BOLD))
    print(colorize(f" 🎯 موجودی هدف پس از شارژ: {init_credit + amount:,} ریال", Colors.CYAN + Colors.BOLD))
    print("=" * 80 + "\n")

    session, target_url = launch_authenticated_payment_session(
        client,
        topup_amount=amount,
        page_type="self",
        initial_path="/ePay/ePay_Payment.aspx",
        auto_open_browser=auto_open
    )

    if isinstance(session, ChromeCDPSession):
        print(colorize(" 🔒 نشست احراز هویت فعال: مرورگر کروم مستقیماً با نشست معتبر و درج خودکار مبلغ باز شد.", Colors.GREEN + Colors.BOLD))
        print(colorize(f" 🔗 لینک پرداخت فعال:\n    {target_url}", Colors.BOLD + Colors.BLUE))
    elif "127.0.0.1" in target_url:
        print(colorize(" 🔒 نشست احراز هویت فعال: مرورگر شما بدون نیاز به لاگین مجدد مستقیماً با مبلغ درج‌شده باز خواهد شد.", Colors.GREEN))
        print(colorize(f" 🔗 لینک پرداخت فعال (لاگین خودکار):\n    {target_url}", Colors.BOLD + Colors.BLUE))
    else:
        print(colorize(f" 🔗 لینک درگاه پرداخت:\n    {direct_payment_url}", Colors.BOLD + Colors.BLUE))

    poll_interval = payment_settings.get("poll_interval_seconds", 5)
    max_wait = payment_settings.get("max_poll_wait_seconds", 300)

    print(colorize("\n⏳ در انتظار تکمیل پرداخت توسط شما در درگاه شاپرک...", Colors.YELLOW + Colors.BOLD))
    print(colorize("💡 پس از پرداخت در درگاه بانک، کلید [Enter] را در این ترمینال فشار دهید (یا سیستم هر چند ثانیه خودکار استعلام می‌کند).", Colors.CYAN))
    print(colorize("   (برای انصراف کلید Ctrl+C را فشار دهید)\n", Colors.DIM))

    start_time = time.time()
    last_poll = 0
    try:
        while time.time() - start_time < max_wait:
            user_pressed_enter = False
            if sys.stdin.isatty():
                try:
                    rlist, _, _ = select.select([sys.stdin], [], [], 0.0)
                    if rlist:
                        sys.stdin.readline()
                        user_pressed_enter = True
                except Exception:
                    pass

            now = time.time()
            cb_triggered = session and getattr(session, 'callback_received', False)
            if user_pressed_enter or cb_triggered or (now - last_poll >= poll_interval):
                last_poll = now
                if user_pressed_enter:
                    print(colorize("🔄 استعلام فوری موجودی به درخواست کاربر...", Colors.CYAN))
                elif cb_triggered:
                    print(colorize("📥 بازگشت از درگاه شاپرک دریافت شد! در حال استعلام موجودی جدید...", Colors.CYAN + Colors.BOLD))
                    session.callback_received = False

                try:
                    p = client.get_reservation_page()
                    p_info = parse_student_info(p)
                    cur_cred = parse_rial_amount(p_info.get("restaurant_credit" if credit_type == "restaurant" else "self_credit", "0"))
                    elapsed = int(now - start_time)

                    if cur_cred > init_credit:
                        added = cur_cred - init_credit
                        print(colorize(f"\n🎉 افزایش اعتبار با موفقیت تایید شد!", Colors.GREEN + Colors.BOLD))
                        print(colorize(f"   ↳ مبلغ افزوده شده: {added:,} ریال", Colors.GREEN))
                        print(colorize(f"   ↳ موجودی جدید {target_type_label}: {cur_cred:,} ریال (زمان سپری‌شده: {elapsed} ثانیه)\n", Colors.GREEN + Colors.BOLD))
                        return True
                    elif cb_triggered:
                        print(colorize(f"   ⚠️ نتیجه تراکنش در سامانه دریافت شد اما مبلغی به موجودی افزوده نشد (انصراف یا ناموفق). موجودی فعلی: {cur_cred:,} ریال", Colors.YELLOW))
                    else:
                        remaining_time = max(0, int(max_wait - elapsed))
                        print(colorize(f"   ⏳ [{elapsed}s / باقی‌مانده: {remaining_time}s] موجودی فعلی: {cur_cred:,} ریال (در انتظار تایید پرداخت)...", Colors.DIM))
                except Exception:
                    pass

            time.sleep(1.0)
    except KeyboardInterrupt:
        print(colorize("\n🛑 عملیات انتظار توسط کاربر لغو شد.", Colors.YELLOW))
        return False
    finally:
        if session:
            session.stop()

    print(colorize(f"\n❌ مهلت انتظار ({max_wait} ثانیه) به پایان رسید و افزایش اعتباری ثبت نشد.", Colors.RED))
    return False


def parse_self_grid_items(res_html: str, meal_name: str) -> List[Dict[str, Any]]:
    """پارس کردن روزها، سلف‌ها، غذاها و وضعیت چک‌باکس‌های رزرو سلف عادی (Reservation.aspx)"""
    table_map = {
        "صبحانه": "cphMain_grdReservationBreakfast",
        "ناهار": "cphMain_grdReservationLunch",
        "شام": "cphMain_grdReservationDinner"
    }
    tbl_id = table_map.get(meal_name, "cphMain_grdReservationLunch")
    m = re.search(r'(<table[^>]*id=\"' + tbl_id + r'\".*?</table>)', res_html, re.DOTALL)
    if not m:
        return []

    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', m.group(1), re.DOTALL)
    items = []
    for r in rows[1:]:
        cols = re.findall(r'<td[^>]*>(.*?)</td>', r, re.DOTALL)
        if len(cols) < 5:
            continue

        day_date = re.sub(r'<[^>]+>', ' ', cols[1]).strip()
        day_date = ' '.join(day_date.split())

        # استخراج چک‌باکس
        chk_m = re.search(r'<input[^>]*name=\"([^\"]*chkReserve[^\"]*)\"[^>]*>', cols[0])
        if not chk_m:
            chk_m = re.search(r'<input[^>]*name=\"([^\"]*chkReserve[^\"]*)\"[^>]*>', r)
        chk_tag = chk_m.group(0) if chk_m else ""
        chk_name = chk_m.group(1) if chk_m else ""
        is_disabled = "disabled" in chk_tag.lower()
        is_checked = "checked" in chk_tag.lower()

        # دراپ‌داون سلف
        self_sel = re.search(r'<select[^>]*name=\"([^\"]*drpSelf[^\"]*)\"[^>]*>(.*?)</select>', cols[2], re.DOTALL)
        self_name_ctl = self_sel.group(1) if self_sel else ""
        self_opts = re.findall(r'<option[^>]*value=\"([^\"]*)\"[^>]*>([^<]*)</option>', self_sel.group(2)) if self_sel else []
        current_self_id = re.search(r'<option[^>]*value=\"([^\"]*)\"[^>]*selected=\"selected\"', self_sel.group(2)) if self_sel else None
        current_self = current_self_id.group(1) if current_self_id else "-1"

        # دراپ‌داون غذا
        food_sel = re.search(r'<select[^>]*name=\"([^\"]*drpFood[^\"]*)\"[^>]*>(.*?)</select>', cols[3], re.DOTALL)
        food_ctl_name = food_sel.group(1) if food_sel else ""
        food_opts = re.findall(r'<option[^>]*value=\"([^\"]*)\"[^>]*>([^<]*)</option>', food_sel.group(2)) if food_sel else []
        selected_food_match = re.search(r'<option[^>]*value=\"([^\"]*)\"[^>]*selected=\"selected\"[^>]*>([^<]*)</option>', food_sel.group(2)) if food_sel else None
        reserved_food_text = selected_food_match.group(2).strip() if selected_food_match else ""

        # دراپ‌داون تعداد
        count_sel = re.search(r'<select[^>]*name=\"([^\"]*drpFoodCount[^\"]*)\"[^>]*>(.*?)</select>', cols[4], re.DOTALL)
        count_ctl_name = count_sel.group(1) if count_sel else ""

        foods = []
        for val, f_text in food_opts:
            if val in ('-1', ''):
                continue
            clean_f = f_text.strip()
            p_match = re.search(r'([0-9,]+)\s*ریال', clean_f)
            price_int = int(p_match.group(1).replace(',', '')) if p_match else 0
            p_str = p_match.group(1) + " ریال" if p_match else "-"
            clean_name = re.sub(r'[0-9,]+\s*ریال', '', clean_f).strip()
            foods.append({
                "id": val,
                "name": clean_name,
                "full_name": clean_f,
                "price_int": price_int,
                "price_str": p_str,
                "is_available": True,
                "remaining": "نامحدود (سلف عادی)",
                "is_cafeteria": False
            })

        items.append({
            "day_date": day_date,
            "is_disabled": is_disabled,
            "is_reserved": is_checked or (reserved_food_text != "" and reserved_food_text != "-"),
            "reserved_food": reserved_food_text,
            "chk_name": chk_name,
            "self_ctl_name": self_name_ctl,
            "current_self": current_self,
            "food_ctl_name": food_ctl_name,
            "count_ctl_name": count_ctl_name,
            "self_options": self_opts,
            "foods": foods
        })
    return items


def reserve_self_service_food(client: SFDClient, current_res_html: str, meal_name: str, day_item: Dict[str, Any], food_item: Dict[str, Any], self_id: Optional[str] = None) -> Tuple[bool, str, str]:
    """ثبت رزرو غذا در سلف عادی (Reservation.aspx) با ارسال اطلاعات و کلیک روی ذخیره"""
    form_data = {}
    for m in re.finditer(r'<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"', current_res_html):
        form_data[m.group(1)] = m.group(2)

    for m in re.finditer(r'<select[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</select>', current_res_html, re.DOTALL):
        val = extract_selected_option_value(m.group(2))
        if val:
            form_data[m.group(1)] = val

    for k in list(form_data.keys()):
        if any(x in k for x in ['btnSave', 'imgbtn', 'btnOk', 'btnLogin']):
            del form_data[k]

    chk_name = day_item.get("chk_name")
    food_ctl = day_item.get("food_ctl_name")
    count_ctl = day_item.get("count_ctl_name")
    self_ctl = day_item.get("self_ctl_name")

    if chk_name:
        form_data[chk_name] = "on"
    if food_ctl:
        form_data[food_ctl] = food_item["id"]
    if count_ctl:
        form_data[count_ctl] = "1"
    if self_ctl and self_id:
        form_data[self_ctl] = self_id

    btn_map = {
        "صبحانه": ("ctl00$cphMain$btnSaveBreakfast", "ذخیره صبحانه"),
        "ناهار": ("ctl00$cphMain$btnSaveLunch", "ذخیره ناهار"),
        "شام": ("ctl00$cphMain$btnSaveDinner", "ذخیره شام")
    }
    btn_key, btn_val = btn_map.get(meal_name, ("ctl00$cphMain$btnSaveLunch", "ذخیره ناهار"))
    form_data[btn_key] = btn_val
    form_data["__EVENTTARGET"] = ""
    form_data["__EVENTARGUMENT"] = ""

    resp_html = client._request(
        "/Reservation/Reservation.aspx",
        data=form_data,
        referer=f"{client.base_url}/Reservation/Reservation.aspx"
    )

    err_match = re.search(r'toastify\([^\)]*\'([^\']*خطا[^\']*)\'', resp_html)
    if err_match:
        return False, err_match.group(1), resp_html

    if "اعتبار شما کافی نمی باشد" in resp_html or "کسری موجودی" in resp_html or "عدم موجودی" in resp_html:
        return False, "اعتبار سلف شما برای ذخیره این غذا کافی نیست و نیاز به افزایش موجودی دارد.", resp_html

    return True, "عملیات ذخیره با موفقیت در سامانه ثبت گردید.", resp_html


def navigate_reservation_week(client: SFDClient, current_res_html: str, direction: str = "next") -> Tuple[bool, str, str]:
    """جابجایی بین هفته‌ها (هفته بعد یا هفته جاری) در صفحه رزرو سلف"""
    form_data = {}
    for m in re.finditer(r'<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"', current_res_html):
        form_data[m.group(1)] = m.group(2)
    for m in re.finditer(r'<select[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</select>', current_res_html, re.DOTALL):
        val = extract_selected_option_value(m.group(2))
        if val:
            form_data[m.group(1)] = val
    for k in list(form_data.keys()):
        if any(x in k for x in ['btnSave', 'imgbtn', 'btnOk', 'btnLogin']):
            del form_data[k]

    btn_name = "ctl00$cphMain$imgbtnNextWeek" if direction == "next" else "ctl00$cphMain$imgbtnLastWeek"
    form_data[f"{btn_name}.x"] = "18"
    form_data[f"{btn_name}.y"] = "18"
    form_data["__EVENTTARGET"] = ""
    form_data["__EVENTARGUMENT"] = ""

    new_html = client._request(
        "/Reservation/Reservation.aspx",
        data=form_data,
        referer=f"{client.base_url}/Reservation/Reservation.aspx"
    )

    old_w = re.search(r'id=\"[^\"]*lblWeekDate[^\"]*\"[^>]*>([^<]+)<', current_res_html)
    new_w = re.search(r'id=\"[^\"]*lblWeekDate[^\"]*\"[^>]*>([^<]+)<', new_html)
    old_str = old_w.group(1).strip() if old_w else ""
    new_str = new_w.group(1).strip() if new_w else ""

    if new_str and new_str != old_str:
        return True, new_str, new_html
    return False, old_str, new_html


def auto_reserve_pipeline(
    client: SFDClient,
    config: Dict[str, Any],
    res_html: str,
    caf_html: str,
    meal_filter: str = "all",
    target_type: str = "all",
    force_reorder: bool = False,
    dry_run: bool = False
) -> None:
    """
    خط لوله کامل رزرو خودکار بر اساس اولویت‌بندی غذاها و سلیقه کاربر
    پشتیبانی همزمان از:
    ۱. رزرو غذای سلف عادی (Reservation.aspx) برای روزهای فعال هفته جاری یا هفته بعد
    ۲. رزرو غذای رستوران آزاد / سلف آزاد (CafeteriaReservation.aspx)
    """
    preferences_cfg = config.get("preferences", {})
    payment_settings = config.get("payment_settings", {})
    skip_if_no_match = preferences_cfg.get("skip_if_no_preference_match", True)

    user_info = parse_student_info(res_html)

    target_meals = []
    if meal_filter in ["lunch", "ناهار"]:
        target_meals = ["ناهار"]
    elif meal_filter in ["dinner", "شام"]:
        target_meals = ["شام"]
    elif meal_filter in ["breakfast", "صبحانه"]:
        target_meals = ["صبحانه"]
    else:
        target_meals = ["ناهار", "شام"]

    print("\n" + "=" * 80)
    print(colorize(" 🤖 اجرای ماژول رزرو خودکار هوشمند بر اساس اولویت‌های کاربر ", Colors.HEADER + Colors.BOLD).center(80))
    print("=" * 80)
    print(f" 👤 دانشجو: {user_info['name']} ({user_info['student_id']})")
    print(f" 💳 موجودی فعلی: اعتبار رستوران آزاد: {colorize(user_info['restaurant_credit'], Colors.YELLOW + Colors.BOLD)} | سلف عادی: {colorize(user_info['self_credit'], Colors.GREEN + Colors.BOLD)}")
    if dry_run:
        print(colorize(" 🧪 وضعیت: حالت شبیه‌سازی (--dry-run) فعال است؛ رزرو نهایی ثبت نخواهد شد.", Colors.CYAN + Colors.BOLD))
    print("-" * 80)

    # ----------------------------------------------------
    # بخش ۱: رزرو در سلف عادی (Reservation.aspx)
    # ----------------------------------------------------
    if target_type in ["self", "all"]:
        print(colorize("\n🏫 [۱/۲] بررسی و رزرو غذا در سلف‌های عادی دانشگاه:", Colors.HEADER + Colors.BOLD))

        # تلاش برای رفتن به هفته بعد
        nav_ok, week_str, working_res_html = navigate_reservation_week(client, res_html, "next")
        if nav_ok:
            print(colorize(f"   🗓️ سامانه به هفته بعد منتقل شد: {week_str}", Colors.GREEN + Colors.BOLD))
        else:
            week_str = user_info.get("week_range", "هفته جاری")
            working_res_html = res_html
            print(colorize(f"   🗓️ برنامه هفته جاری سامانه: {week_str}", Colors.CYAN))

        # فاز ۱: استخراج و تجمیع هوشمند تمام وعده‌های منتخب هفته جهت محاسبه بودجه و کسری کل
        planned_meals = []
        for meal_name in target_meals:
            day_items = parse_self_grid_items(working_res_html, meal_name)
            active_days = [d for d in day_items if not d.get("is_disabled")]
            for day_item in active_days:
                d_title = day_item["day_date"]
                if day_item.get("is_reserved") and not force_reorder:
                    continue
                available_foods = day_item.get("foods", [])
                if not available_foods:
                    continue
                matched, unranked, _ = PreferenceEngine.rank_food_options(available_foods, meal_name, preferences_cfg)
                selected_food = None
                if matched:
                    selected_food = matched[0]
                elif not skip_if_no_match and unranked:
                    selected_food = unranked[0]

                if selected_food:
                    self_def_name = config.get("default_lunch_self" if meal_name == "ناهار" else "default_dinner_self")
                    chosen_self_id = None
                    if self_def_name:
                        for s_id, s_name in day_item.get("self_options", []):
                            if self_def_name in s_name or s_name in self_def_name:
                                chosen_self_id = s_id
                                break
                    planned_meals.append({
                        "meal_name": meal_name,
                        "day_date": d_title,
                        "day_item": day_item,
                        "selected_food": selected_food,
                        "food_name": selected_food["name"],
                        "food_price": selected_food["price_int"],
                        "chosen_self_id": chosen_self_id,
                        "priority_term": selected_food.get("matched_priority")
                    })

        total_planned_cost = sum(p["food_price"] for p in planned_meals)
        current_credit = parse_rial_amount(user_info["self_credit"])
        total_deficit = total_planned_cost - current_credit

        if not planned_meals:
            print(colorize("   ℹ️ هیچ وعده جدیدی منطبق با اولویت‌های شما برای رزرو در سلف یافت نشد.", Colors.DIM))
        else:
            print(colorize(f"\n   📋 برنامه رزرو سلف برای این هفته ({len(planned_meals)} وعده منطبق):", Colors.BOLD + Colors.CYAN))
            for idx, p in enumerate(planned_meals, 1):
                term_txt = f" (منطبق با: «{p['priority_term']}»)" if p['priority_term'] else ""
                print(f"      {idx}. وعده {colorize(p['meal_name'], Colors.BOLD)} ({p['day_date']}): «{p['food_name']}» ➔ {p['food_price']:,} ریال{term_txt}")
            print("   " + "-" * 70)
            print(colorize(f"   💰 جمع کل هزینه غذاهای انتخابی: {total_planned_cost:,} ریال", Colors.BOLD))
            print(colorize(f"   💳 موجودی فعلی سلف عادی: {current_credit:,} ریال", Colors.BOLD + (Colors.GREEN if total_deficit <= 0 else Colors.YELLOW)))

            if total_deficit > 0:
                print(colorize(f"   ⚠️ کسری کل موجودی جهت رزرو تمام وعده‌ها: {total_deficit:,} ریال", Colors.YELLOW + Colors.BOLD))
                if dry_run:
                    topup_calc = calculate_smart_topup(total_deficit, payment_settings)
                    print(colorize(f"   🧪 [شبیه‌سازی] شارژ پیشنهادی یکجا جهت رزرو کامل تمام وعده‌ها: {topup_calc:,} ریال.", Colors.CYAN + Colors.BOLD))
                else:
                    print(colorize("   💡 پیشنهاد: برای راحتی شما، می‌توانید کسری کل را هم‌اکنون یکجا پرداخت کنید تا تمام وعده‌ها بدون وقفه رزرو شوند.", Colors.CYAN))
                    topup_ok, new_credit = prompt_and_wait_for_payment(client, total_planned_cost, current_credit, "self", payment_settings)
                    if topup_ok:
                        current_credit = new_credit
                        user_info["self_credit"] = f"{new_credit:,} ریال"
                    else:
                        print(colorize("   ⚠️ ادامه فرآیند رزرو بر اساس سقف موجودی فعلی...", Colors.YELLOW))
            else:
                print(colorize("   ✅ موجودی سلف برای رزرو تمام وعده‌های این هفته کافی است.", Colors.GREEN + Colors.BOLD))

            # فاز ۲: ثبت نهایی رزروها در سامانه با کسر و به‌روزرسانی آنی موجودی
            print(colorize("\n   🚀 آغاز ثبت رزروها در سامانه تغذیه:", Colors.HEADER + Colors.BOLD))
            for p in planned_meals:
                meal_name = p["meal_name"]
                d_title = p["day_date"]
                day_item = p["day_item"]
                selected_food = p["selected_food"]
                food_name = p["food_name"]
                food_price = p["food_price"]
                chosen_self_id = p["chosen_self_id"]

                print(colorize(f"\n      📅 روز: {d_title} (وعده {meal_name})", Colors.BOLD + Colors.YELLOW))
                print(colorize(f"         🎯 انتخاب: «{food_name}» (قیمت: {food_price:,} ریال)", Colors.BOLD))

                if current_credit < food_price:
                    deficit = food_price - current_credit
                    if dry_run:
                        topup_amt = calculate_smart_topup(deficit, payment_settings)
                        print(colorize(f"         ⚠️ [شبیه‌سازی] کسری موجودی: {deficit:,} ریال (موجودی: {current_credit:,} | قیمت: {food_price:,}). شارژ پیشنهادی: {topup_amt:,} ریال.", Colors.YELLOW))
                    else:
                        print(colorize(f"         ⚠️ کسری موجودی برای این وعده: {deficit:,} ریال (موجودی فعلی: {current_credit:,} ریال).", Colors.YELLOW))
                        success, new_credit = prompt_and_wait_for_payment(client, food_price, current_credit, "self", payment_settings)
                        if not success:
                            print(colorize(f"         ❌ فرآیند رزرو این وعده به دلیل عدم تأمین موجودی متوقف شد.", Colors.RED))
                            continue
                        current_credit = new_credit
                        user_info["self_credit"] = f"{new_credit:,} ریال"
                else:
                    print(colorize(f"         💳 موجودی کافی است ({current_credit:,} ریال).", Colors.GREEN))

                if dry_run:
                    current_credit = max(0, current_credit - food_price)
                    user_info["self_credit"] = f"{current_credit:,} ریال"
                    print(colorize(f"         🧪 [شبیه‌سازی]: غذای «{food_name}» تست شد. (موجودی تخمینی باقیمانده: {current_credit:,} ریال)", Colors.CYAN + Colors.BOLD))
                else:
                    print(colorize("         🚀 در حال ذخیره غذای سلف در سامانه...", Colors.CYAN))
                    res_ok, res_msg, working_res_html = reserve_self_service_food(
                        client, working_res_html, meal_name, day_item, selected_food, self_id=chosen_self_id
                    )
                    if res_ok:
                        updated_info = parse_student_info(working_res_html)
                        parsed_c = parse_rial_amount(updated_info.get("self_credit", "0"))
                        if parsed_c > 0 and parsed_c != current_credit:
                            current_credit = parsed_c
                        else:
                            current_credit = max(0, current_credit - food_price)
                        user_info["self_credit"] = f"{current_credit:,} ریال"

                        print(colorize(f"         🎉 تبریک! غذای «{food_name}» برای {d_title} در سلف ذخیره شد.", Colors.GREEN + Colors.BOLD))
                        print(colorize(f"            ↳ موجودی باقیمانده سلف: {current_credit:,} ریال", Colors.CYAN))
                    else:
                        print(colorize(f"         ❌ خطا در ثبت رزرو سلف: {res_msg}", Colors.RED + Colors.BOLD))
                        try:
                            fresh_p = client.get_reservation_page()
                            fresh_info = parse_student_info(fresh_p)
                            fresh_c = parse_rial_amount(fresh_info.get("self_credit", "0"))
                            if fresh_c != current_credit:
                                current_credit = fresh_c
                                user_info["self_credit"] = f"{fresh_c:,} ریال"
                                print(colorize(f"            ↳ موجودی واقعی سرور همگام‌سازی شد: {current_credit:,} ریال", Colors.DIM))
                        except Exception:
                            pass

    # ----------------------------------------------------
    # بخش ۲: رزرو در رستوران‌های آزاد / سلف آزاد (CafeteriaReservation.aspx)
    # ----------------------------------------------------
    if target_type in ["cafeteria", "all"]:
        print(colorize("\n🏪 [۲/۲] بررسی و رزرو غذا در رستوران‌های آزاد (یاس ۱ و ۲):", Colors.HEADER + Colors.BOLD))
        def_rid = str(config.get("default_restaurant_id", "14"))
        current_caf = caf_html
        if def_rid:
            curr_id = ""
            m_l = re.search(r'<select[^>]*drpSelfTitle_Lunch[^>]*>(.*?)</select>', current_caf, re.DOTALL)
            if m_l:
                curr_id = extract_selected_option_value(m_l.group(1))
            if curr_id != def_rid:
                current_caf = client.switch_cafeteria_restaurant(current_caf, def_rid)

        for meal_name in target_meals:
            print(colorize(f"\n   ▶️ بررسی وعده {meal_name} رستوران آزاد:", Colors.BOLD + Colors.CYAN))

            already_reserved, reserved_food = check_already_reserved(current_caf, res_html, meal_name)
            if already_reserved:
                if not force_reorder:
                    print(colorize(f"      ℹ️ برای وعده {meal_name} قبلاً رزرو ثبت شده است («{reserved_food}»).", Colors.YELLOW))
                    print(colorize("         ↳ از رزرو مجدد صرف‌نظر شد (برای لغو و تغییر از فلگ --force-reorder استفاده کنید).", Colors.DIM))
                    continue
                else:
                    print(colorize(f"      ⚠️ رزرو قبلی وجود دارد («{reserved_food}»)، اما با فلگ --force-reorder لغو و تغییر خواهد یافت.", Colors.YELLOW + Colors.BOLD))

            raw_items = parse_cafeteria_meal_items(current_caf, meal_name)
            available_items = [it for it in raw_items if it.get("is_available")]

            if not available_items:
                print(colorize(f"      ❌ متأسفانه در رستوران پیش‌فرض برای وعده {meal_name} هیچ غذایی با ظرفیت باقیمانده یافت نشد.", Colors.RED))
                continue

            matched, unranked, blacklisted = PreferenceEngine.rank_food_options(available_items, meal_name, preferences_cfg)

            if blacklisted:
                b_names = ", ".join([b["name"] for b in blacklisted[:3]])
                print(colorize(f"      🚫 {len(blacklisted)} غذا به دلیل قرار داشتن در لیست سیاه (Blacklist) کنار گذاشته شدند: {b_names}", Colors.DIM))

            selected_food = None
            if matched:
                selected_food = matched[0]
                print(colorize(f"      🎯 بهترین گزینه منطبق با اولویت‌ها: «{selected_food['name']}»", Colors.GREEN + Colors.BOLD))
                print(f"         ↳ رتبه اولویت: {selected_food['rank']} (عبارت: «{selected_food['matched_priority']}») | قیمت: {selected_food['price_str']} | ظرفیت: {selected_food['remaining']} پرس")
            else:
                if skip_if_no_match:
                    print(colorize(f"      ⚠️ هیچ‌کدام از غذاهای دارای ظرفیت در لیست اولویت‌های {meal_name} شما قرار ندارند.", Colors.YELLOW))
                    print(colorize(f"         ↳ طبق تنظیمات (skip_if_no_preference_match: true) از رزرو صرف‌نظر شد.", Colors.DIM))
                    continue
                elif unranked:
                    selected_food = unranked[0]
                    print(colorize(f"      ⚠️ بدون انطباق با اولویت‌ها؛ انتخاب اولین غذای دارای ظرفیت: «{selected_food['name']}»", Colors.YELLOW))
                else:
                    print(colorize("      ❌ گزینه مناسبی برای رزرو یافت نشد.", Colors.RED))
                    continue

            food_price = selected_food["price_int"]
            food_name = selected_food["name"]

            if dry_run:
                print(colorize(f"      🧪 [شبیه‌سازی --dry-run]: تیک باکس غذای «{food_name}» انتخاب و تعداد ۱ تنظیم گردید.", Colors.CYAN + Colors.BOLD))
                print(colorize(f"         ↳ پس از کلیک روی دکمه ذخیره، هدایت به صفحه پیش‌پرداخت (CafeteriaReservation_SelectBank.aspx) با مبلغ {food_price:,} ریال شبیه‌سازی شد.", Colors.DIM))
            else:
                print(colorize(f"      🚀 در حال انتخاب باکس «{food_name}»، تنظیم تعداد ۱ و کلیک روی ذخیره در سامانه...", Colors.CYAN))
                res_ok, res_msg, current_caf, prepay_url = reserve_cafeteria_food(
                    client, current_caf, meal_name, selected_food, restaurant_id=def_rid
                )
                if not res_ok:
                    print(colorize(f"      ❌ خطا در ثبت سفارش موقت رستوران: {res_msg}", Colors.RED + Colors.BOLD))
                    continue

                if prepay_url:
                    print(colorize(f"      ✅ سفارش موقت با موفقیت در سامانه ثبت گردید. در حال انتقال به درگاه پیش‌پرداخت...", Colors.GREEN))
                    pay_ok = prompt_and_wait_for_restaurant_payment(
                        client, food_name, food_price, prepay_url, meal_name, payment_settings, restaurant_id=def_rid
                    )
                    if pay_ok:
                        print(colorize(f"      🎉 تبریک! سفارش غذای «{food_name}» برای وعده {meal_name} در رستوران آزاد نهایی و با موفقیت پرداخت شد.", Colors.GREEN + Colors.BOLD))
                    else:
                        print(colorize(f"      ⚠️ پرداخت یا تایید رزرو وعده {meal_name} تکمیل نشد.", Colors.YELLOW))
                else:
                    print(colorize(f"      🎉 تبریک! غذای «{food_name}» با موفقیت برای وعده {meal_name} در رستوران آزاد رزرو شد.", Colors.GREEN + Colors.BOLD))

    print("\n" + "=" * 80 + "\n")


def setup_or_edit_food_preferences(config: Dict[str, Any]) -> Dict[str, Any]:
    """ویزارد تعاملی مدیریت اولویت‌های غذایی و لیست سیاه در ترمینال"""
    prefs = config.get("preferences", {})
    if not prefs:
        prefs = {
            "skip_if_no_preference_match": True,
            "lunch_priority": [
                "چلو کباب کوبیده",
                "زرشک پلو با مرغ",
                "چلو جوجه کباب",
                "چلو خورشت قورمه سبزی",
                "چلو خورشت قیمه"
            ],
            "dinner_priority": [
                "شنیسل مرغ",
                "پیتزا",
                "خوراک مرغ",
                "ساندویچ ماکارونی"
            ],
            "blacklist": [
                "خوراک لوبیا",
                "کوکو سبزی"
            ]
        }
        config["preferences"] = prefs

    while True:
        print("\n" + "=" * 60)
        print(colorize(" ⚙️  مدیریت اولویت‌های غذایی و لیست سیاه (Preferences) ", Colors.HEADER + Colors.BOLD).center(60))
        print("=" * 60)
        print(f" [۱] مشاهده اولویت‌های فعلی")
        print(f" [۲] ویرایش اولویت‌های ناهار (Lunch Priority)")
        print(f" [۳] ویرایش اولویت‌های شام (Dinner Priority)")
        print(f" [۴] ویرایش لیست سیاه (Blacklist)")
        skip_status = "فعال (عدم رزرو در صورت عدم تطابق)" if prefs.get("skip_if_no_preference_match", True) else "غیرفعال (رزرو جایگزین)"
        print(f" [۵] وضعیت عدم رزرو در صورت نبود اولویت: {colorize(skip_status, Colors.CYAN)}")
        print(f" [۰] ذخیره تنظیمات و خروج")

        ch = input(colorize("\n👉 گزینه مورد نظر را انتخاب کنید: ", Colors.YELLOW)).strip()
        if ch == "۱":
            print(colorize("\n📋 اولویت‌های ناهار:", Colors.BOLD + Colors.CYAN))
            for idx, p in enumerate(prefs.get("lunch_priority", []), 1):
                print(f"   {idx}. {p}")
            print(colorize("\n📋 اولویت‌های شام:", Colors.BOLD + Colors.CYAN))
            for idx, p in enumerate(prefs.get("dinner_priority", []), 1):
                print(f"   {idx}. {p}")
            print(colorize("\n🚫 لیست سیاه (عدم رزرو):", Colors.BOLD + Colors.RED))
            for idx, b in enumerate(prefs.get("blacklist", []), 1):
                print(f"   {idx}. {b}")
        elif ch == "۲":
            print(colorize("\n🍱 وارد کردن اولویت‌های ناهار (با کاما ',' جدا کنید):", Colors.YELLOW))
            curr = ", ".join(prefs.get("lunch_priority", []))
            print(f"فعلی: {curr}")
            inp = input("جدید (خالی برای عدم تغییر): ").strip()
            if inp:
                prefs["lunch_priority"] = [x.strip() for x in inp.split(",") if x.strip()]
                print(colorize("✅ اولویت‌های ناهار به‌روزرسانی شد.", Colors.GREEN))
        elif ch == "۳":
            print(colorize("\n🌙 وارد کردن اولویت‌های شام (با کاما ',' جدا کنید):", Colors.YELLOW))
            curr = ", ".join(prefs.get("dinner_priority", []))
            print(f"فعلی: {curr}")
            inp = input("جدید (خالی برای عدم تغییر): ").strip()
            if inp:
                prefs["dinner_priority"] = [x.strip() for x in inp.split(",") if x.strip()]
                print(colorize("✅ اولویت‌های شام به‌روزرسانی شد.", Colors.GREEN))
        elif ch == "۴":
            print(colorize("\n🚫 وارد کردن غذاهای لیست سیاه (با کاما ',' جدا کنید):", Colors.YELLOW))
            curr = ", ".join(prefs.get("blacklist", []))
            print(f"فعلی: {curr}")
            inp = input("جدید (خالی برای عدم تغییر): ").strip()
            if inp:
                prefs["blacklist"] = [x.strip() for x in inp.split(",") if x.strip()]
                print(colorize("✅ لیست سیاه به‌روزرسانی شد.", Colors.GREEN))
        elif ch == "۵":
            prefs["skip_if_no_preference_match"] = not prefs.get("skip_if_no_preference_match", True)
            print(colorize("✅ وضعیت تغییر کرد.", Colors.GREEN))
        elif ch == "۰":
            config["preferences"] = prefs
            save_config(config)
            print(colorize("💾 تنظیمات با موفقیت در config.json ذخیره شد.", Colors.GREEN + Colors.BOLD))
            break

    return config


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
    parser.add_argument("--auto-reserve", "-A", action="store_true", help="رزرو خودکار غذا بر اساس اولویت‌های تعریف‌شده و سلیقه کاربر")
    parser.add_argument("--target", "-t", choices=["self", "cafeteria", "all"], default="all", help="تعیین نوع محل رزرو: self (سلف عادی)، cafeteria (رستوران آزاد)، all (هردو)")
    parser.add_argument("--meal", "-m", choices=["lunch", "dinner", "breakfast", "all"], default="all", help="تعیین وعده جهت رزرو خودکار (lunch, dinner, breakfast, all)")
    parser.add_argument("--force-reorder", action="store_true", help="لغو و تغییر رزرو فعلی در صورت وجود و ثبت غذای اولویت‌دار جدید")
    parser.add_argument("--dry-run", action="store_true", help="شبیه‌سازی کامل مراحل تصمیم‌گیری و محاسبه کسری بدون ارسال درخواست نهایی")
    parser.add_argument("--set-preferences", action="store_true", help="ویزارد تعاملی تنظیم و ویرایش اولویت‌های ناهار، شام و لیست سیاه")
    parser.add_argument("--change-defaults", "-s", action="store_true", help="تغییر مکان‌های سلف یا رستوران پیش‌فرض")
    parser.add_argument("--all-restaurants", "-a", action="store_true", help="بررسی و مقایسه ظرفیت تمام رستوران‌های آزاد (یاس ۱ و ۲)")
    parser.add_argument("--restaurant", "-r", help="مشاهده ظرفیت یک رستوران خاص (نام یا شماره شناسه)")
    parser.add_argument("--show-all-foods", action="store_true", help="نمایش غذاهای تمام‌شده رستوران در کنار غذاهای موجود")
    parser.add_argument("--manual", action="store_true", help="ورود دستی کد امنیتی")
    parser.add_argument("--json", action="store_true", help="خروجی در قالب JSON")
    parser.add_argument("--topup", "--charge", nargs="?", const="ask", default=None, help="افزایش دستی اعتبار سلف یا رستوران با مبلغ دلخواه (مثال: --topup 1000000 یا --topup)")
    parser.add_argument("--topup-type", choices=["self", "restaurant"], default="self", help="نوع اعتبار جهت افزایش: self (سلف عادی) یا restaurant (رستوران آزاد)")
    
    args = parser.parse_args()
    config = load_config()

    if args.set_preferences:
        setup_or_edit_food_preferences(config)
        return
    
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

    # بررسی و اجرای افزایش دستی اعتبار در صورت درخواست کاربر
    if args.topup is not None:
        raw_val = str(args.topup).strip()
        amt = 0
        if raw_val in ["ask", "true", "True", ""]:
            print("\n" + colorize("=" * 60, Colors.CYAN))
            print(colorize(" 💳 افزایش دستی اعتبار حساب کاربری سامانه تغذیه ", Colors.HEADER + Colors.BOLD).center(60))
            print(colorize("=" * 60, Colors.CYAN))
            prompt_t = colorize("🔢 لطفاً مبلغ مورد نظر جهت افزایش اعتبار را به ریال وارد کنید (مثال: 500000 یا 1000000): ", Colors.YELLOW + Colors.BOLD)
            val_in = input(prompt_t).strip()
            amt = parse_rial_amount(val_in)
        else:
            amt = parse_rial_amount(raw_val)

        if amt <= 0:
            print(colorize("❌ مبلغ وارد شده نامعتبر است.", Colors.RED))
            sys.exit(1)

        do_manual_topup(client, amt, credit_type=args.topup_type, payment_settings=config.get("payment_settings", {}))
        return

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

    # ۴. اجرای ماژول رزرو خودکار در صورت درخواست کاربر
    if args.auto_reserve:
        auto_reserve_pipeline(
            client=client,
            config=config,
            res_html=res_html,
            caf_html=caf_html,
            meal_filter=args.meal,
            target_type=args.target,
            force_reorder=args.force_reorder,
            dry_run=args.dry_run
        )
        return
    
    # ۵. استخراج غذاهای سلف منطبق با سلف انتخابی کاربر
    meals = {
        "صبحانه": parse_meal_table(res_html, "cphMain_grdReservationBreakfast", default_self=config.get("default_breakfast_self", "-")),
        "ناهار": parse_meal_table(res_html, "cphMain_grdReservationLunch", default_self=config.get("default_lunch_self", "-")),
        "شام": parse_meal_table(res_html, "cphMain_grdReservationDinner", default_self=config.get("default_dinner_self", "-")),
    }
    
    # ۶. استخراج ظرفیت رستوران‌های آزاد (صبحانه، ناهار و شام)
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
        curr_id = ""
        m_l = re.search(r'<select[^>]*drpSelfTitle_Lunch[^>]*>(.*?)</select>', current_caf, re.DOTALL)
        if m_l:
            curr_id = extract_selected_option_value(m_l.group(1))
        if curr_id != rid:
            current_caf = client.switch_cafeteria_restaurant(current_caf, rid)
        restaurant_capacities[rname] = parse_restaurant_all_meals_capacity(current_caf, rname)

    # ۷. چاپ خروجی
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
            
        # نمایش اولویت‌های کاربر و راهنمای دستور رزرو خودکار
        prefs = config.get("preferences", {})
        l_pri = prefs.get("lunch_priority", [])
        d_pri = prefs.get("dinner_priority", [])
        b_list = prefs.get("blacklist", [])
        if l_pri or d_pri:
            print("\n" + "-" * 80)
            print(colorize(" ⭐️ اولویت‌های فعال شما برای رزرو هوشمند غذا:", Colors.HEADER + Colors.BOLD))
            if l_pri:
                print(f"   🍲 {colorize('ناهار:', Colors.BOLD)} {' > '.join(l_pri[:4])}{' > ...' if len(l_pri) > 4 else ''}")
            if d_pri:
                print(f"   🌙 {colorize('شام:', Colors.BOLD)} {' > '.join(d_pri[:4])}{' > ...' if len(d_pri) > 4 else ''}")
            if b_list:
                print(f"   🚫 {colorize('لیست سیاه (عدم رزرو):', Colors.BOLD)} {', '.join(b_list)}")
            print(colorize(" 💡 جهت رزرو خودکار غذا بر اساس اولویت‌ها:   python3 check_food.py --auto-reserve", Colors.GREEN + Colors.BOLD))
            print(colorize(" 💡 جهت شبیه‌سازی مراحل بدون رزرو نهایی:     python3 check_food.py --auto-reserve --dry-run", Colors.CYAN))
            print(colorize(" 💡 جهت تغییر و ویرایش اولویت‌های غذایی:     python3 check_food.py --set-preferences", Colors.DIM))

        if not args.all_restaurants and len(available_restaurants) > 1:
            print(colorize(f"\n💡 برای مشاهده و مقایسه ظرفیت تمام رستوران‌ها از دستور زیر استفاده کنید:", Colors.CYAN))
            print(colorize("   python3 check_food.py --all-restaurants", Colors.BOLD + Colors.YELLOW))


if __name__ == "__main__":
    main()
