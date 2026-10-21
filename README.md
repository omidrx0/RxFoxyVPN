<div align="center">

<img src="https://i.ibb.co/hRqy6VRz/rx-logo.png" alt="RXFoxyVPN logo" width="140">

# RXFoxyVPN

**Your Freedom, Our Priority.**

A fast, clean and multilingual VPN client for **Android** and **Windows**.

<br>

[![Version](https://img.shields.io/badge/version-v1.0.0-FF6A00?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows](https://img.shields.io/badge/Windows-portable-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Languages](https://img.shields.io/badge/languages-6-8A2BE2?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN)
[![Source](https://img.shields.io/badge/source-GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN)

<br>

[**Download**](https://github.com/omidrx0/RxFoxyVPN/releases/latest) · [Source](https://github.com/omidrx0/RxFoxyVPN)

**English** · [فارسی](docs/README.fa.md) · [Русский](docs/README.ru.md) · [简体中文](docs/README.zh.md) · [한국어](docs/README.ko.md) · [العربية](docs/README.ar.md)

</div>

---

## Overview

RXFoxyVPN is a cross-platform project for **Android** and **Windows**, built around a simple, consistent interface that speaks your language. This repository contains the source code and documentation; ready-to-use builds are published on the [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest) page.

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
  <table>
    <tr>
      <td align="center"><img src="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg" alt="Android home screen" width="220"></td>
      <td align="center"><img src="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg" alt="Android server list" width="220"></td>
      <td align="center"><img src="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg" alt="Android settings screen" width="220"></td>
    </tr>
    <tr>
      <td align="center"><sub>Home</sub></td>
      <td align="center"><sub>Server list</sub></td>
      <td align="center"><sub>Settings</sub></td>
    </tr>
  </table>
</div>

## Download

Get the latest build from **[GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest)**.

| Platform | File | Notes |
| --- | --- | --- |
| Android | `.apk` | Allow installs from your browser/file manager if Android asks. |
| Windows | Portable `.exe` or `.zip` | No installer needed. Extract the ZIP (if used) and run. |

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
- **Windows**: see `Windows/` for the source and packaging scripts, install the dependencies the project lists, then run or package the app.

Build steps may vary with your environment. The configuration files in the repository are the source of truth.

</details>

## Project structure

```text
Android/    Android application (Kotlin / Gradle)
Windows/    Windows application (Python / web UI)
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
