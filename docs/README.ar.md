# RXFoxyVPN

[English](../README.md) | [فارسی](README.fa.md) | [Русский](README.ru.md) | [简体中文](README.zh.md) | [한국어](README.ko.md) | [العربية](README.ar.md)

> عميل VPN متعدد المنصات لنظامي Android وWindows بواجهة متعددة اللغات.

![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Windows-blue) ![Version](https://img.shields.io/badge/version-1.0.0-red)

## الميزات

تطبيقات Android وWindows؛ واجهة بالإنجليزية والفارسية والروسية والصينية المبسطة والكورية والعربية؛ خدمة VPN على Android؛ واجهة سطح مكتب وتكامل مع علبة النظام في Windows.

## هيكل المشروع

```text
Android/    Android application (Kotlin / Gradle)
Windows/    Windows application (Python / web UI)
docs/       Translated README files
```

## البدء

انسخ المستودع وافتح `Android/` في Android Studio. بالنسبة إلى Windows، راجع المصدر ونصوص الحزم داخل `Windows/` وثبّت اعتماديات Python المطلوبة، ثم شغّل `python -m rxvpn` من مجلد التطبيق المناسب. قد يلزم إعداد إضافي.

```bash
git clone https://github.com/YOUR_USERNAME/RXFoxyVPN.git
```

## التنزيلات

راجع GitHub Releases للحصول على الإصدارات المنشورة. الشيفرة المصدرية ليست ملف تثبيت.

## الأمان والتراخيص

لا ترفع كلمات المرور أو مفاتيح التوقيع أو الإعدادات الخاصة. راجع تراخيص المكونات الخارجية قبل إعادة التوزيع. لم يتم اختيار ترخيص شامل للمستودع بعد.
