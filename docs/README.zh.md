# RXFoxyVPN

[English](../README.md) | [فارسی](README.fa.md) | [Русский](README.ru.md) | [简体中文](README.zh.md) | [한국어](README.ko.md) | [العربية](README.ar.md)

> 适用于 Android 和 Windows 的多平台 VPN 客户端，提供多语言界面。

![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Windows-blue) ![Version](https://img.shields.io/badge/version-1.0.0-red)

## 功能

Android 和 Windows 应用；支持英语、波斯语、俄语、简体中文、韩语和阿拉伯语界面；Android VPN 服务；Windows 桌面界面及系统托盘集成。

## 项目结构

```text
Android/    Android application (Kotlin / Gradle)
Windows/    Windows application (Python / web UI)
docs/       Translated README files
```

## 开始使用

克隆仓库。在 Android Studio 中打开 `Android/`。Windows 版本请查看 `Windows/` 中的源代码和打包脚本，安装所需 Python 依赖，然后在适当的应用目录运行 `python -m rxvpn`。可能还需要额外配置。

```bash
git clone https://github.com/YOUR_USERNAME/RXFoxyVPN.git
```

## 下载

请在 GitHub Releases 查看已发布的构建版本。源代码不等于安装程序。

## 安全与许可

请勿提交密码、签名密钥或私人配置。重新分发前请检查第三方组件许可证。仓库尚未选择统一许可证。
