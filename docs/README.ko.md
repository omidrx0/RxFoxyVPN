# RXFoxyVPN

[English](../README.md) | [فارسی](README.fa.md) | [Русский](README.ru.md) | [简体中文](README.zh.md) | [한국어](README.ko.md) | [العربية](README.ar.md)

> Android와 Windows를 위한 다국어 인터페이스 기반 멀티플랫폼 VPN 클라이언트입니다.

![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Windows-blue) ![Version](https://img.shields.io/badge/version-1.0.0-red)

## 기능

Android 및 Windows 앱; 영어, 페르시아어, 러시아어, 중국어 간체, 한국어, 아랍어 UI; Android VPN 서비스; Windows 데스크톱 UI 및 트레이 통합.

## 프로젝트 구조

```text
Android/    Android application (Kotlin / Gradle)
Windows/    Windows application (Python / web UI)
docs/       Translated README files
```

## 시작하기

저장소를 복제하세요. Android Studio에서 `Android/`를 여세요. Windows 버전은 `Windows/`의 소스 및 패키징 스크립트를 확인하고 필요한 Python 의존성을 설치한 뒤 적절한 앱 디렉터리에서 `python -m rxvpn`을 실행하세요. 추가 설정이 필요할 수 있습니다.

```bash
git clone https://github.com/YOUR_USERNAME/RXFoxyVPN.git
```

## 다운로드

배포된 빌드는 GitHub Releases에서 확인하세요. 소스 코드는 설치 파일이 아닙니다.

## 보안 및 라이선스

비밀번호, 서명 키 또는 개인 설정을 커밋하지 마세요. 재배포 전 타사 구성 요소의 라이선스를 확인하세요. 저장소 전체 라이선스는 아직 선택되지 않았습니다.
