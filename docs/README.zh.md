# RXFoxyVPN

**您的自由，我们的首要任务。**

适用于 Android 和 Windows 的快速多语言 VPN 客户端。

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

[**下载**](https://github.com/omidrx0/RxFoxyVPN/releases/latest) · [源代码](https://github.com/omidrx0/RxFoxyVPN)

[English](../README.md) · [فارسی](README.fa.md) · [Русский](README.ru.md) · **简体中文** · [한국어](README.ko.md) · [العربية](README.ar.md)

</div>

---

## 简介

RXFoxyVPN 是面向 **Android** 和 **Windows** 的跨平台项目，拥有简洁统一、支持你所用语言的界面。本仓库包含源代码和文档；可直接使用的构建版本发布在 [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest) 页面。

### v1.0.3 更新内容

此版本改进了 Windows 版 Firefox Accounts 登录流程，并更新了身份验证、HTTP 兼容性、Cookie 与 Challenge 处理以及账户验证机制。这些调整旨在解决可能导致 `406 Not Acceptable` 错误的问题。

**测试状态：**HAWK、PBKDF2/HKDF 参考测试和 `gofmt` 检查均已通过。Windows 完整构建和实际账户登录测试尚未完成，因此还不能确认 406 错误已彻底解决。

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
>>>>>>> f1e9d32795a84dc67a8e2ca87f18df9e1df5723a

## 下载

请从 [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest) 获取最新版本。

| 平台 | 文件 | 说明 |
|---|---|---|
| Android | `.apk` | 支持 Android 8.0 及更高版本。 |
| Windows | Portable `.exe` 或 `.zip` | 直接运行 EXE，或先解压 ZIP。 |
| Windows | Setup `.exe` | 运行安装程序并按照提示操作。 |

## 1.0.3 版本更新

改进了 Windows Firefox 账户登录、身份验证、HTTP 兼容性、Cookie 处理和账户验证，以处理 `406 Not Acceptable` 问题。HAWK、PBKDF2/HKDF 参考测试和 `gofmt` 检查已通过；完整 Windows 构建和真实账户登录测试仍待完成。
