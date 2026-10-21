# RXFoxyVPN

[English](README.md) | [فارسی](docs/README.fa.md) | [Русский](docs/README.ru.md) | [简体中文](docs/README.zh.md) | [한국어](docs/README.ko.md) | [العربية](docs/README.ar.md)

> A multi-platform VPN client for Android and Windows, with a multilingual interface.

![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Windows-blue) ![Version](https://img.shields.io/badge/version-1.0.0-red)

## Features

Android and Windows applications; interface translations for English, Persian, Russian, Simplified Chinese, Korean and Arabic; Android VPN service; Windows desktop interface and tray integration.

## Project structure

```text
Android/    Android application (Kotlin / Gradle)
Windows/    Windows application (Python / web UI)
docs/       Translated README files
```

## Getting started

Clone the repository. Open `Android/` in Android Studio to build the Android app. For Windows, review the source and packaging scripts under `Windows/` and install the required Python dependencies before running `python -m rxvpn` from the appropriate application directory. Build requirements and third-party components may need additional setup.

```bash
git clone https://github.com/YOUR_USERNAME/RXFoxyVPN.git
```

## Downloads

Check GitHub Releases for published builds. Source code is not an installer.

## Security and licensing

Do not commit credentials, signing keys or private configurations. Review third-party component licenses before redistribution. A repository-wide license has not yet been selected.
