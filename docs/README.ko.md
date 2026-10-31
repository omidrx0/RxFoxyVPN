# RXFoxyVPN

**여러분의 자유가 우리의 최우선입니다.**

Android와 Windows를 위한 빠른 다국어 VPN 클라이언트입니다.

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

[**다운로드**](https://github.com/omidrx0/RxFoxyVPN/releases/latest) · [소스 코드](https://github.com/omidrx0/RxFoxyVPN)

[English](../README.md) · [فارسی](README.fa.md) · [Русский](README.ru.md) · [简体中文](README.zh.md) · **한국어** · [العربية](README.ar.md)

</div>

---

## 소개

RXFoxyVPN은 **Android**와 **Windows**를 위한 크로스 플랫폼 프로젝트로, 사용자의 언어를 지원하는 간결하고 일관된 인터페이스를 제공합니다. 이 저장소에는 소스 코드와 문서가 있으며, 바로 사용할 수 있는 빌드는 [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest) 페이지에 게시됩니다.

### v1.0.3 변경 사항

이번 버전에서는 Windows의 Firefox Accounts 로그인 흐름을 개선했습니다. 인증, HTTP 호환성, Cookie 및 Challenge 처리, 계정 확인 기능을 업데이트했으며, 이를 통해 `406 Not Acceptable` 오류를 일으킬 수 있는 문제를 해결하고자 합니다.

**테스트 상태:** HAWK, PBKDF2/HKDF 기준 테스트와 `gofmt` 검사를 통과했습니다. Windows 전체 빌드와 실제 계정 로그인 테스트는 아직 완료되지 않아 406 오류의 완전한 해결 여부는 확인되지 않았습니다.

## 주요 기능

| Android | Windows |
| --- | --- |
| 연결을 관리하는 네이티브 VPN 서비스 | 시스템 트레이 통합을 지원하는 데스크톱 UI |
| 홈, 서버 목록, 설정 화면 | 데스크톱 셸 안에서 동작하는 웹 기반 UI |
| Android 8.0 이상 지원 | 시스템 전체 TUN 모드 및 시스템 프록시 지원 |

**두 플랫폼 공통:** 영어, 페르시아어, 러시아어, 중국어 간체, 한국어, 아랍어 인터페이스를 지원합니다.

## 스크린샷

### Windows

<div align="center">
  <img src="https://i.ibb.co/bgP17rKC/Rx-Foxy-Vpn-windows.jpg" alt="Windows용 RXFoxyVPN" width="88%">
</div>

### Android

최소 지원 버전: **Android 8.0**.

<div align="center">
  <table width="100%">
    <tr>
      <td align="center" width="33%"><a href="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg"><img src="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg" alt="Android 홈 화면" width="100%"></a></td>
      <td align="center" width="33%"><a href="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg"><img src="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg" alt="Android 서버 목록" width="100%"></a></td>
      <td align="center" width="33%"><a href="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg"><img src="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg" alt="Android 설정 화면" width="100%"></a></td>
    </tr>
    <tr>
      <td align="center"><sub><b>홈</b></sub></td>
      <td align="center"><sub><b>서버 목록</b></sub></td>
      <td align="center"><sub><b>설정</b></sub></td>
    </tr>
  </table>
</div>
>>>>>>> f1e9d32795a84dc67a8e2ca87f18df9e1df5723a

## 다운로드

최신 버전은 [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest)에서 받을 수 있습니다.

| 플랫폼 | 파일 | 설명 |
|---|---|---|
| Android | `.apk` | Android 8.0 이상 지원. |
| Windows | Portable `.exe` 또는 `.zip` | EXE를 바로 실행하거나 ZIP을 압축 해제하세요. |
| Windows | Setup `.exe` | 설치 프로그램을 실행하고 안내를 따르세요. |

## 버전 1.0.3 업데이트

Windows Firefox 계정 로그인, 인증, HTTP 호환성, 쿠키 처리 및 계정 확인 흐름을 개선하여 `406 Not Acceptable` 문제를 해결하도록 했습니다. HAWK 및 PBKDF2/HKDF 참조 테스트와 `gofmt` 검사는 통과했으며, Windows 전체 빌드와 실제 계정 로그인 테스트는 아직 남아 있습니다.
