<div align="center" dir="rtl">

<img src="https://i.ibb.co/hRqy6VRz/rx-logo.png" alt="شعار RXFoxyVPN" width="140">

# RXFoxyVPN

**حريتك، أولويتنا.**

عميل VPN سريع وأنيق ومتعدد اللغات لنظامي **Android** و**Windows**.

<br>

[![Version](https://img.shields.io/badge/version-v1.0.0-FF6A00?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows](https://img.shields.io/badge/Windows-portable-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Languages](https://img.shields.io/badge/languages-6-8A2BE2?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN)
[![Source](https://img.shields.io/badge/source-GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN)

<br>

[**التنزيل**](https://github.com/omidrx0/RxFoxyVPN/releases/latest) · [الشيفرة المصدرية](https://github.com/omidrx0/RxFoxyVPN)

[English](../README.md) · [فارسی](README.fa.md) · [Русский](README.ru.md) · [简体中文](README.zh.md) · [한국어](README.ko.md) · **العربية**

</div>

---

<div dir="rtl">

## نظرة عامة

RXFoxyVPN مشروع متعدد المنصات لنظامي **Android** و**Windows**، بواجهة بسيطة وموحدة تتحدث لغتك. يضم هذا المستودع الشيفرة المصدرية والوثائق، وتُنشر النسخ الجاهزة للاستخدام على صفحة [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest).

## الميزات

| Android | Windows |
| --- | --- |
| خدمة VPN أصلية لإدارة الاتصال | واجهة سطح مكتب مع تكامل علبة النظام |
| شاشات الرئيسية وقائمة الخوادم والإعدادات | واجهة قائمة على الويب داخل غلاف سطح المكتب |
| يدعم Android 8.0 وما فوق | وضع TUN على مستوى النظام ودعم وكيل النظام |

**على المنصتين:** واجهة بالإنجليزية والفارسية والروسية والصينية المبسطة والكورية والعربية.

## لقطات الشاشة

### Windows

<div align="center">
  <img src="https://i.ibb.co/bgP17rKC/Rx-Foxy-Vpn-windows.jpg" alt="RXFoxyVPN على Windows" width="88%">
</div>

### Android

الحد الأدنى للإصدار المدعوم: **Android 8.0**.

<div align="center">
  <table width="100%">
    <tr>
      <td align="center" width="33%"><a href="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg"><img src="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg" alt="الشاشة الرئيسية في Android" width="100%"></a></td>
      <td align="center" width="33%"><a href="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg"><img src="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg" alt="قائمة الخوادم في Android" width="100%"></a></td>
      <td align="center" width="33%"><a href="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg"><img src="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg" alt="إعدادات Android" width="100%"></a></td>
    </tr>
    <tr>
      <td align="center"><sub><b>الرئيسية</b></sub></td>
      <td align="center"><sub><b>قائمة الخوادم</b></sub></td>
      <td align="center"><sub><b>الإعدادات</b></sub></td>
    </tr>
  </table>
</div>

## التنزيل

احصل على أحدث إصدار من **[GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest)**.

| المنصة | الملف | ملاحظات |
| --- | --- | --- |
| Android | `.apk` | اسمح بالتثبيت من المتصفح أو مدير الملفات إذا طلب Android ذلك. |
| Windows | ملف `.exe` محمول أو `.zip` | لا يحتاج إلى تثبيت. فك ضغط ملف ZIP (إن وُجد) ثم شغّل التطبيق. |

> [!NOTE]
> إذا لم يظهر لك المستودع أو الإصدارات فقد يكون الوصول مقيّدًا؛ اطلب الصلاحية من مسؤول المشروع.

## البناء من المصدر

<details>
<summary>عرض خطوات البناء</summary>

<br>

انسخ المستودع:

<div dir="ltr">

```bash
git clone https://github.com/omidrx0/RxFoxyVPN.git
cd RxFoxyVPN
```

</div>

- **Android**: افتح المجلد `Android/` في Android Studio وانتظر اكتمال مزامنة Gradle (راجع ملفات Gradle لمعرفة إصدارات JDK وAndroid SDK المطلوبة)، ثم شغّل التطبيق أو استخدم *Build → Build APK(s)*.
- **Windows**: توجد الشيفرة المصدرية وسكربتات التغليف في `Windows/`؛ ثبّت الاعتماديات المذكورة في المشروع ثم شغّل التطبيق أو قم بتغليفه.

قد تختلف خطوات البناء حسب بيئتك، وتبقى ملفات الإعداد في المستودع هي المرجع.

</details>

## هيكل المشروع

<div dir="ltr">

```text
Android/    تطبيق Android (Kotlin / Gradle)
Windows/    تطبيق Windows (Python / واجهة ويب)
docs/       ملفات README المترجمة
```

</div>

## المساهمة

نرحب بالاقتراحات وتقارير الأخطاء وطلبات الدمج (Pull Requests). للتغييرات الكبيرة، يُرجى فتح Issue أولًا لمناقشة الفكرة.

## الترخيص

تُحدَّد شروط الاستخدام وإعادة التوزيع في ملف `LICENSE` بالمستودع. وإن لم يوجد، فجميع الحقوق محفوظة للمؤلف.

</div>

---

<div align="center" dir="rtl">

<sub>**RXFoxyVPN** · Android وWindows · [مستودع GitHub](https://github.com/omidrx0/RxFoxyVPN)</sub>

<sub><i>حريتك، أولويتنا.</i></sub>

</div>
