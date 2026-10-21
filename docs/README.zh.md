<div align="center">

<img src="https://i.ibb.co/hRqy6VRz/rx-logo.png" alt="RXFoxyVPN 标志" width="140">

# RXFoxyVPN

**你的自由，我们的优先。**

一款快速、简洁、支持多语言的 **Android** 与 **Windows** VPN 客户端。

<br>

[![Version](https://img.shields.io/badge/version-v1.0.0-FF6A00?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows](https://img.shields.io/badge/Windows-portable-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Languages](https://img.shields.io/badge/languages-6-8A2BE2?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN)
[![Source](https://img.shields.io/badge/source-GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN)

<br>

[**下载**](https://github.com/omidrx0/RxFoxyVPN/releases/latest) · [源代码](https://github.com/omidrx0/RxFoxyVPN)

[English](../README.md) · [فارسی](README.fa.md) · [Русский](README.ru.md) · **简体中文** · [한국어](README.ko.md) · [العربية](README.ar.md)

</div>

---

## 简介

RXFoxyVPN 是面向 **Android** 和 **Windows** 的跨平台项目，拥有简洁统一、支持你所用语言的界面。本仓库包含源代码和文档；可直接使用的构建版本发布在 [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest) 页面。

## 功能

| Android | Windows |
| --- | --- |
| 原生 VPN 服务，用于管理连接 | 桌面界面，集成系统托盘 |
| 主页、服务器列表和设置页面 | 桌面外壳中的 Web 界面 |
| 支持 Android 8.0 及以上版本 | 系统级 TUN 模式与系统代理支持 |

**双平台通用：** 界面支持英语、波斯语、俄语、简体中文、韩语和阿拉伯语。

## 应用截图

### Windows

<div align="center">
  <img src="https://i.ibb.co/bgP17rKC/Rx-Foxy-Vpn-windows.jpg" alt="Windows 版 RXFoxyVPN" width="88%">
</div>

### Android

最低支持版本：**Android 8.0**。

<div align="center">
  <table width="100%">
    <tr>
      <td align="center" width="33%"><a href="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg"><img src="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg" alt="Android 主页" width="100%"></a></td>
      <td align="center" width="33%"><a href="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg"><img src="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg" alt="Android 服务器列表" width="100%"></a></td>
      <td align="center" width="33%"><a href="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg"><img src="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg" alt="Android 设置页面" width="100%"></a></td>
    </tr>
    <tr>
      <td align="center"><sub><b>主页</b></sub></td>
      <td align="center"><sub><b>服务器列表</b></sub></td>
      <td align="center"><sub><b>设置</b></sub></td>
    </tr>
  </table>
</div>

## 下载

请从 **[GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest)** 获取最新构建。

| 平台 | 文件 | 说明 |
| --- | --- | --- |
| Android | `.apk` | 如 Android 提示，请允许从浏览器或文件管理器安装应用。 |
| Windows | 便携版 `.exe` 或 `.zip` | 无需安装。如为 ZIP，请先解压再运行。 |

> [!NOTE]
> 如果你看不到仓库或发行版，可能是访问受限，请联系维护者获取权限。

## 从源代码构建

<details>
<summary>展开构建说明</summary>

<br>

克隆仓库：

```bash
git clone https://github.com/omidrx0/RxFoxyVPN.git
cd RxFoxyVPN
```

- **Android**：在 Android Studio 中打开 `Android/` 目录，等待 Gradle 同步完成（所需 JDK 与 Android SDK 版本请查看 Gradle 配置），然后运行应用，或使用 *Build → Build APK(s)*。
- **Windows**：源代码和打包脚本位于 `Windows/`，请先安装项目列出的依赖，再运行或打包应用。

构建步骤可能因环境而异，请以仓库中的配置文件为准。

</details>

## 项目结构

```text
Android/    Android 应用（Kotlin / Gradle）
Windows/    Windows 应用（Python / Web 界面）
docs/       多语言 README 文件
```

## 参与贡献

欢迎提出建议、报告问题和提交 Pull Request。较大的改动请先创建 Issue，以便讨论方案。

## 许可

使用与再分发条款以仓库中的 `LICENSE` 文件为准。如无该文件，则作者保留所有权利。

---

<div align="center">

<sub>**RXFoxyVPN** · Android 与 Windows · [GitHub 仓库](https://github.com/omidrx0/RxFoxyVPN)</sub>

<sub><i>你的自由，我们的优先。</i></sub>

</div>
