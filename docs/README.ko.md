<div align="center">

<img src="https://i.ibb.co/hRqy6VRz/rx-logo.png" alt="RXFoxyVPN 로고" width="140">

# RXFoxyVPN

**당신의 자유가 우리의 우선순위입니다.**

빠르고 깔끔하며 다국어를 지원하는 **Android** 및 **Windows**용 VPN 클라이언트입니다.

<br>

[![Version](https://img.shields.io/badge/version-v1.0.0-FF6A00?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows](https://img.shields.io/badge/Windows-portable-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Languages](https://img.shields.io/badge/languages-6-8A2BE2?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN)
[![Source](https://img.shields.io/badge/source-GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN)

<br>

[**다운로드**](https://github.com/omidrx0/RxFoxyVPN/releases/latest) · [소스 코드](https://github.com/omidrx0/RxFoxyVPN)

[English](../README.md) · [فارسی](README.fa.md) · [Русский](README.ru.md) · [简体中文](README.zh.md) · **한국어** · [العربية](README.ar.md)

</div>

---

## 소개

RXFoxyVPN은 **Android**와 **Windows**를 위한 크로스 플랫폼 프로젝트로, 사용자의 언어를 지원하는 간결하고 일관된 인터페이스를 제공합니다. 이 저장소에는 소스 코드와 문서가 있으며, 바로 사용할 수 있는 빌드는 [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest) 페이지에 게시됩니다.

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
  <table width="560">
    <tr>
      <td align="center" width="50%"><img src="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg" alt="Android 홈 화면" width="100%"><br><sub><b>홈</b></sub></td>
      <td align="center" width="50%"><img src="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg" alt="Android 서버 목록" width="100%"><br><sub><b>서버 목록</b></sub></td>
    </tr>
    <tr>
      <td align="center" colspan="2"><img src="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg" alt="Android 설정 화면" width="50%"><br><sub><b>설정</b></sub></td>
    </tr>
  </table>
</div>

## 다운로드

최신 빌드는 **[GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest)**에서 받을 수 있습니다.

| 플랫폼 | 파일 | 참고 |
| --- | --- | --- |
| Android | `.apk` | Android에서 요청하면 브라우저 또는 파일 관리자의 설치를 허용하세요. |
| Windows | 포터블 `.exe` 또는 `.zip` | 설치가 필요 없습니다. ZIP이라면 압축을 풀고 실행하세요. |

> [!NOTE]
> 저장소나 릴리스가 보이지 않는다면 접근이 제한되어 있을 수 있습니다. 관리자에게 접근 권한을 요청하세요.

## 소스에서 빌드

<details>
<summary>빌드 방법 보기</summary>

<br>

저장소를 클론합니다:

```bash
git clone https://github.com/omidrx0/RxFoxyVPN.git
cd RxFoxyVPN
```

- **Android**: Android Studio에서 `Android/` 폴더를 열고 Gradle 동기화가 끝나길 기다린 뒤(필요한 JDK 및 Android SDK 버전은 Gradle 설정 확인) 앱을 실행하거나 *Build → Build APK(s)*를 사용하세요.
- **Windows**: 소스 코드와 패키징 스크립트는 `Windows/`에 있습니다. 프로젝트가 안내하는 의존성을 설치한 뒤 앱을 실행하거나 패키징하세요.

빌드 절차는 환경에 따라 다를 수 있으며, 저장소의 설정 파일이 기준입니다.

</details>

## 프로젝트 구조

```text
Android/    Android 앱 (Kotlin / Gradle)
Windows/    Windows 앱 (Python / 웹 UI)
docs/       번역된 README 파일
```

## 기여하기

제안, 버그 신고, Pull Request를 환영합니다. 큰 변경은 먼저 Issue를 열어 방향을 논의해 주세요.

## 라이선스

사용 및 재배포 조건은 저장소의 `LICENSE` 파일에 따릅니다. 해당 파일이 없으면 모든 권리는 저작자에게 있습니다.

---

<div align="center">

<sub>**RXFoxyVPN** · Android 및 Windows · [GitHub 저장소](https://github.com/omidrx0/RxFoxyVPN)</sub>

<sub><i>당신의 자유가 우리의 우선순위입니다.</i></sub>

</div>
