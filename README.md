<div align="center">

<img src="https://i.ibb.co/hRqy6VRz/rx-logo.png" alt="RXFoxyVPN logo" width="140">

# RXFoxyVPN

**Your Freedom, Our Priority.**

A fast, clean and multilingual VPN client for **Android** and **Windows**.

<br>

[![Version](https://img.shields.io/badge/version-v1.0.3-FF6A00?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows](https://img.shields.io/badge/Windows-portable-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
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

## Download

Get the latest build from <a href="https://github.com/omidrx0/RxFoxyVPN/releases/latest"><b>GitHub Releases</b></a>, or pick the package that best matches your device.

<table>
  <thead>
    <tr>
      <th align="center">Platform</th>
      <th align="center">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Download&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td rowspan="5" align="center" valign="middle">
        <img src="https://img.shields.io/badge/-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android"><br>
        <sub>Android 8.0+</sub>
      </td>
      <td align="center" valign="middle">
        <a href="https://github.com/omidrx0/RxFoxyVPN/releases/download/v1.0.3/app-universal-releaseV1.0.3.apk"><img src="https://img.shields.io/badge/Universal-APK-16A34A?style=for-the-badge&labelColor=14532D" alt="Universal APK"></a><br>
        <b>Best choice if you're unsure.</b> ⭐<br><sub>Works on all devices and architectures.</sub>
      </td>
    </tr>
    <tr>
      <td align="center" valign="middle">
        <a href="https://github.com/omidrx0/RxFoxyVPN/releases/download/v1.0.3/app-arm64-v8a-releaseV1.0.3.apk"><img src="https://img.shields.io/badge/ARM64-APK-22C55E?style=for-the-badge&labelColor=166534" alt="ARM64 APK"></a><br>
        Modern phones and tablets.<br><sub>64-bit ARM processors.</sub>
      </td>
    </tr>
    <tr>
      <td align="center" valign="middle">
        <a href="https://github.com/omidrx0/RxFoxyVPN/releases/download/v1.0.3/app-armeabi-v7a-releaseV1.0.3.apk"><img src="https://img.shields.io/badge/ARMv7-APK-4ADE80?style=for-the-badge&labelColor=15803D" alt="ARMv7 APK"></a><br>
        Older Android devices.<br><sub>32-bit ARM processors.</sub>
      </td>
    </tr>
    <tr>
      <td align="center" valign="middle">
        <a href="https://github.com/omidrx0/RxFoxyVPN/releases/download/v1.0.3/app-x86_64-releaseV1.0.3.apk"><img src="https://img.shields.io/badge/x86__64-APK-10B981?style=for-the-badge&labelColor=065F46" alt="x86_64 APK"></a><br>
        Intel/AMD devices and emulators.<br><sub>64-bit.</sub>
      </td>
    </tr>
    <tr>
      <td align="center" valign="middle">
        <a href="https://github.com/omidrx0/RxFoxyVPN/releases/download/v1.0.3/app-x86-releaseV1.0.3.apk"><img src="https://img.shields.io/badge/x86-APK-34D399?style=for-the-badge&labelColor=047857" alt="x86 APK"></a><br>
        Intel/AMD devices and emulators.<br><sub>32-bit.</sub>
      </td>
    </tr>
    <tr>
      <td rowspan="2" align="center" valign="middle">
        <img src="https://img.shields.io/badge/-Windows-0078D4?style=flat-square&logo=windows11&logoColor=white" alt="Windows"><br>
        <sub>Windows 10+</sub>
      </td>
      <td align="center" valign="middle">
        <a href="https://github.com/omidrx0/RxFoxyVPN/releases/download/v1.0.3/RXFoxyVPN-Setup.exe"><img src="https://img.shields.io/badge/Installer-x64-1D4ED8?style=for-the-badge&labelColor=1E3A8A" alt="Windows Installer x64"></a><br>
        <b>Standard installer for 64-bit PCs.</b> ⭐<br><sub>Adds shortcuts and easy uninstall.</sub>
      </td>
    </tr>
    <tr>
      <td align="center" valign="middle">
        <a href="https://github.com/omidrx0/RxFoxyVPN/releases/download/v1.0.3/RXFOXYVPN-Windows.zip"><img src="https://img.shields.io/badge/Portable-ZIP-3B82F6?style=for-the-badge&labelColor=1E40AF" alt="Windows Portable ZIP"></a><br>
        No installation needed.<br><sub>Extract the ZIP and run.</sub>
      </td>
    </tr>
  </tbody>
</table>

> [!TIP]
> Not sure which Android architecture you need? Choose **Universal**. If Android asks, allow installs from your browser or file manager.

> [!NOTE]
> If the repository or its releases are not visible to you, access may be restricted. Ask the maintainer for access.

## Build from source

<details>
<summary>Show build instructions</summary>

<br>

Clone the repository:

```bash
git clone https://github.com/omidrx0/RxFoxyVPN.git
cd RxFoxyVPN
```

- **Android**: open the `Android/` folder in Android Studio, let Gradle sync (check the Gradle files for the required JDK and Android SDK), then run the app or use *Build → Build APK(s)*.
- **Windows**: the Windows application is now written in Go. See the Windows source and build instructions in the repository, then follow the project's Go and frontend build requirements.

Build steps may vary with your environment. The configuration files in the repository are the source of truth.

</details>

## Project structure

```text
Android/    Android application (Kotlin / Gradle)
Windows/    Windows application (Go / web UI)
docs/       Translated README files
```

## Contributing

Suggestions, bug reports and pull requests are welcome. Please open an issue first for larger changes so we can discuss the approach.

## License

Usage and redistribution terms are defined by the repository's `LICENSE` file. If none is present, all rights are reserved by the author.

---

<div align="center">

<sub>**RXFoxyVPN** · Android & Windows · [GitHub repository](https://github.com/omidrx0/RxFoxyVPN)</sub>

<sub><i>Your Freedom, Our Priority.</i></sub>

</div>
