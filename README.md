# RXFoxyVPN

**Your Freedom, Our Priority.**

A fast, clean and multilingual VPN client for Android and Windows.

<<<<<<< HEAD
[![Windows Portable](https://img.shields.io/badge/Windows-Portable-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows Setup](https://img.shields.io/badge/Windows-Setup-2EA44F?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
=======
<br>

[![Version](https://img.shields.io/badge/version-v1.0.3-FF6A00?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows Portable](https://img.shields.io/badge/Windows-Portable-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows Setup](https://img.shields.io/badge/Windows-Setup-2EA44F?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Languages](https://img.shields.io/badge/languages-6-8A2BE2?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN)
[![Source](https://img.shields.io/badge/source-GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN)

<br>

[**Download**](https://github.com/omidrx0/RxFoxyVPN/releases/latest) · [Source](https://github.com/omidrx0/RxFoxyVPN)

**English** · [فارسی](docs/README.fa.md) · [Русский](docs/README.ru.md) · [简体中文](docs/README.zh.md) · [한국어](docs/README.ko.md) · [العربية](docs/README.ar.md)

</div>

---

## Overview

RXFoxyVPN is a cross-platform project for **Android** and **Windows**, built around a simple, consistent interface that speaks your language. This repository contains the source code and documentation; ready-to-use builds are published on the [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest) page.

### What's new in v1.0.3

This release improves the Windows Firefox Accounts sign-in flow, with updates to authentication, HTTP compatibility, cookie and challenge handling, and account verification. These changes aim to address issues that may cause HTTP `406 Not Acceptable` responses.

**Testing:** HAWK, PBKDF2/HKDF reference tests and `gofmt` checks passed. A full Windows build and real-account sign-in test are still pending, so the 406 issue is not yet confirmed as resolved.

## Features

| Android | Windows |
| --- | --- |
| Native VPN service to manage your connection | Desktop interface with system-tray integration |
| Home, server list and settings screens | Web-based UI inside a desktop shell |
| Supports Android 8.0 and above | System-wide TUN mode and system-proxy support |

**Both platforms:** interface in English, Persian, Russian, Simplified Chinese, Korean and Arabic.

## Screenshots

### Windows

<div align="center">
  <img src="https://i.ibb.co/bgP17rKC/Rx-Foxy-Vpn-windows.jpg" alt="RXFoxyVPN for Windows" width="88%">
</div>

### Android

Minimum supported version: **Android 8.0**.

<div align="center">
  <table width="100%">
    <tr>
      <td align="center" width="33%"><a href="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg"><img src="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg" alt="Android home screen" width="100%"></a></td>
      <td align="center" width="33%"><a href="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg"><img src="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg" alt="Android server list" width="100%"></a></td>
      <td align="center" width="33%"><a href="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg"><img src="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg" alt="Android settings screen" width="100%"></a></td>
    </tr>
    <tr>
      <td align="center"><sub><b>Home</b></sub></td>
      <td align="center"><sub><b>Server list</b></sub></td>
      <td align="center"><sub><b>Settings</b></sub></td>
    </tr>
  </table>
</div>
>>>>>>> f1e9d32795a84dc67a8e2ca87f18df9e1df5723a

## Download

Get the latest build from [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest).

| Platform | File | Notes |
|---|---|---|
| Android | `.apk` | Android 8.0 and above. |
| Windows | Portable `.exe` or `.zip` | Run the executable directly, or extract the ZIP first. |
| Windows | Setup `.exe` | Run the installer and follow the setup steps. |

## What's new in v1.0.3

The Windows Firefox Accounts sign-in flow, authentication, HTTP compatibility, cookie handling and verification flow were updated to address HTTP `406 Not Acceptable` issues. HAWK and PBKDF2/HKDF reference tests and `gofmt` checks passed; full Windows build and real-account sign-in verification remain pending.
