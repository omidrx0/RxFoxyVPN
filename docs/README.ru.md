<div align="center">

<img src="https://i.ibb.co/hRqy6VRz/rx-logo.png" alt="Логотип RXFoxyVPN" width="140">

# RXFoxyVPN

**Ваша свобода — наш приоритет.**

Быстрый, аккуратный и многоязычный VPN-клиент для **Android** и **Windows**.

<br>

[![Version](https://img.shields.io/badge/version-v1.0.0-FF6A00?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Windows](https://img.shields.io/badge/Windows-portable-0078D4?style=flat-square&logo=windows&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN/releases/latest)
[![Languages](https://img.shields.io/badge/languages-6-8A2BE2?style=flat-square)](https://github.com/omidrx0/RxFoxyVPN)
[![Source](https://img.shields.io/badge/source-GitHub-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/omidrx0/RxFoxyVPN)

<br>

[**Загрузки**](https://github.com/omidrx0/RxFoxyVPN/releases/latest) · [Исходный код](https://github.com/omidrx0/RxFoxyVPN)

[English](../README.md) · [فارسی](README.fa.md) · **Русский** · [简体中文](README.zh.md) · [한국어](README.ko.md) · [العربية](README.ar.md)

</div>

---

## Обзор

RXFoxyVPN — кроссплатформенный проект для **Android** и **Windows** с простым и единым интерфейсом на вашем языке. В репозитории находятся исходный код и документация; готовые сборки публикуются на странице [GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest).

## Возможности

| Android | Windows |
| --- | --- |
| Нативная VPN-служба для управления подключением | Настольный интерфейс с интеграцией в системный трей |
| Экраны: главный, список серверов и настройки | Веб-интерфейс в настольной оболочке |
| Поддержка Android 8.0 и выше | Системный режим TUN и поддержка системного прокси |

**Обе платформы:** интерфейс на английском, персидском, русском, упрощённом китайском, корейском и арабском языках.

## Скриншоты

### Windows

<div align="center">
  <img src="https://i.ibb.co/bgP17rKC/Rx-Foxy-Vpn-windows.jpg" alt="RXFoxyVPN для Windows" width="88%">
</div>

### Android

Минимальная поддерживаемая версия: **Android 8.0**.

<div align="center">
  <table>
    <tr>
      <td align="center"><img src="https://i.ibb.co/S1L6KL9/RXFOXYVPN-home-android.jpg" alt="Главный экран Android" width="220"></td>
      <td align="center"><img src="https://i.ibb.co/YTTfCL82/RXFOXYVPN-list-android.jpg" alt="Список серверов Android" width="220"></td>
      <td align="center"><img src="https://i.ibb.co/XHYyQR3/RXFOXYVPN-settings-android.jpg" alt="Настройки Android" width="220"></td>
    </tr>
    <tr>
      <td align="center"><sub>Главный экран</sub></td>
      <td align="center"><sub>Список серверов</sub></td>
      <td align="center"><sub>Настройки</sub></td>
    </tr>
  </table>
</div>

## Загрузки

Последнюю сборку можно скачать на **[GitHub Releases](https://github.com/omidrx0/RxFoxyVPN/releases/latest)**.

| Платформа | Файл | Примечания |
| --- | --- | --- |
| Android | `.apk` | Если Android попросит, разрешите установку из браузера или файлового менеджера. |
| Windows | Портативный `.exe` или `.zip` | Установка не требуется. Распакуйте ZIP (если он используется) и запустите. |

> [!NOTE]
> Если репозиторий или релизы вам не видны, доступ может быть ограничен — запросите его у владельца проекта.

## Сборка из исходников

<details>
<summary>Показать инструкцию по сборке</summary>

<br>

Клонируйте репозиторий:

```bash
git clone https://github.com/omidrx0/RxFoxyVPN.git
cd RxFoxyVPN
```

- **Android**: откройте папку `Android/` в Android Studio, дождитесь синхронизации Gradle (нужные версии JDK и Android SDK указаны в файлах Gradle), затем запустите приложение или выберите *Build → Build APK(s)*.
- **Windows**: исходный код и скрипты упаковки находятся в `Windows/`; установите перечисленные в проекте зависимости, затем запустите или упакуйте приложение.

Шаги сборки могут отличаться в зависимости от окружения; главным источником служат конфигурационные файлы репозитория.

</details>

## Структура проекта

```text
Android/    Приложение Android (Kotlin / Gradle)
Windows/    Приложение Windows (Python / веб-интерфейс)
docs/       Переведённые файлы README
```

## Участие в проекте

Предложения, отчёты об ошибках и pull request’ы приветствуются. Для крупных изменений сначала создайте issue, чтобы обсудить подход.

## Лицензия

Условия использования и распространения определены файлом `LICENSE` в репозитории. Если файла нет, все права принадлежат автору.

---

<div align="center">

<sub>**RXFoxyVPN** · Android и Windows · [Репозиторий GitHub](https://github.com/omidrx0/RxFoxyVPN)</sub>

<sub><i>Ваша свобода — наш приоритет.</i></sub>

</div>
