<div align="center" dir="rtl">

<img src="https://i.ibb.co/hRqy6VRz/rx-logo.png" alt="لوگوی RXFoxyVPN" width="140">

# RXFoxyVPN

**آزادی شما، اولویت ما.**

یک کلاینت VPN سریع، ساده و چندزبانه برای **اندروید** و **ویندوز**.

<br>

[![Version](https://img.shields.io/badge/version-v1.0.0-FF6A00?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows](https://img.shields.io/badge/Windows-portable-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Languages](https://img.shields.io/badge/languages-6-8A2BE2?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN)
[![Source](https://img.shields.io/badge/source-GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN)

<br>

[**دانلود**](https://github.com/omidrx0/RxFoxyVPN/releases/latest) · [سورس‌کد](https://github.com/omidrx0/RxFoxyVPN)

[English](../README.md) · **فارسی** · [Русский](README.ru.md) · [简体中文](README.zh.md) · [한국어](README.ko.md) · [العربية](README.ar.md)

</div>

---

<div dir="rtl">

## معرفی

RXFoxyVPN پروژه‌ای چندسکویی برای **اندروید** و **ویندوز** است که حول یک رابط کاربری ساده و یکپارچه، به زبان خودتان ساخته شده است. این مخزن شامل سورس‌کد و مستندات است؛ فایل‌های آمادهٔ نصب در صفحهٔ [Releases گیت‌هاب](https://github.com/omidrx0/RxFoxyVPN/releases/latest) منتشر می‌شوند.

## ویژگی‌ها

| اندروید | ویندوز |
| --- | --- |
| سرویس VPN بومی برای مدیریت اتصال | رابط دسکتاپ همراه با یکپارچگی با System Tray |
| صفحه‌های اصلی، فهرست سرورها و تنظیمات | رابط کاربری مبتنی بر وب در قالب برنامهٔ دسکتاپ |
| پشتیبانی از اندروید ۸٫۰ و بالاتر | حالت TUN سراسری و پشتیبانی از پروکسی سیستم |

**هر دو پلتفرم:** رابط کاربری به زبان‌های انگلیسی، فارسی، روسی، چینی ساده‌شده، کره‌ای و عربی.

## تصاویر برنامه

### ویندوز

<div align="center">
  <img src="https://i.ibb.co/bgP17rKC/Rx-Foxy-Vpn-windows.jpg" alt="RXFoxyVPN در ویندوز" width="88%">
</div>

### اندروید

حداقل نسخهٔ پشتیبانی‌شده: **اندروید ۸٫۰**.

<div align="center">
  <table width="560">
    <tr>
      <td align="center" width="50%"><img src="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg" alt="صفحهٔ اصلی اندروید" width="100%"><br><sub><b>صفحهٔ اصلی</b></sub></td>
      <td align="center" width="50%"><img src="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg" alt="فهرست سرورها در اندروید" width="100%"><br><sub><b>فهرست سرورها</b></sub></td>
    </tr>
    <tr>
      <td align="center" colspan="2"><img src="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg" alt="تنظیمات اندروید" width="50%"><br><sub><b>تنظیمات</b></sub></td>
    </tr>
  </table>
</div>

## دانلود

آخرین نسخه را از **[Releases گیت‌هاب](https://github.com/omidrx0/RxFoxyVPN/releases/latest)** دریافت کنید.

| پلتفرم | فایل | توضیحات |
| --- | --- | --- |
| اندروید | `.apk` | اگر اندروید درخواست کرد، اجازهٔ نصب از مرورگر یا مدیر فایل را فعال کنید. |
| ویندوز | فایل `.exe` قابل‌حمل یا `.zip` | نیازی به نصب نیست. در صورت ZIP بودن، آن را استخراج و اجرا کنید. |

> [!NOTE]
> اگر مخزن یا Releaseها را نمی‌بینید، ممکن است دسترسی محدود باشد؛ از مسئول پروژه درخواست دسترسی کنید.

## ساخت از روی سورس

<details>
<summary>نمایش مراحل ساخت</summary>

<br>

مخزن را کلون کنید:

<div dir="ltr">

```bash
git clone https://github.com/omidrx0/RxFoxyVPN.git
cd RxFoxyVPN
```

</div>

- **اندروید:** پوشهٔ `Android/` را در Android Studio باز کنید، صبر کنید Gradle همگام‌سازی شود (نسخه‌های موردنیاز JDK و Android SDK را در فایل‌های Gradle ببینید) و سپس برنامه را اجرا کنید یا از *Build → Build APK(s)* استفاده کنید.
- **ویندوز:** سورس‌کد و اسکریپت‌های بسته‌بندی در `Windows/` قرار دارند؛ وابستگی‌های معرفی‌شدهٔ پروژه را نصب کنید و سپس برنامه را اجرا یا بسته‌بندی کنید.

مراحل ساخت ممکن است بسته به محیط شما متفاوت باشد؛ فایل‌های پیکربندی مخزن مرجع اصلی هستند.

</details>

## ساختار پروژه

<div dir="ltr">

```text
Android/    برنامهٔ اندروید (Kotlin / Gradle)
Windows/    برنامهٔ ویندوز (Python / رابط وب)
docs/       فایل‌های README ترجمه‌شده
```

</div>

## مشارکت

پیشنهادها، گزارش باگ و Pull Requestها قابل‌قبول هستند. برای تغییرات بزرگ، لطفاً ابتدا یک Issue باز کنید تا دربارهٔ رویکرد گفتگو کنیم.

## مجوز

شرایط استفاده و بازتوزیع در فایل `LICENSE` مخزن مشخص شده است. در صورت نبود این فایل، همهٔ حقوق برای نویسنده محفوظ است.

</div>

---

<div align="center" dir="rtl">

<sub>**RXFoxyVPN** · اندروید و ویندوز · [مخزن گیت‌هاب](https://github.com/omidrx0/RxFoxyVPN)</sub>

<sub><i>آزادی شما، اولویت ما.</i></sub>

</div>
