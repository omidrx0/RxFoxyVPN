# RXFoxyVPN

[English](../README.md) | [فارسی](README.fa.md) | [Русский](README.ru.md) | [简体中文](README.zh.md) | [한국어](README.ko.md) | [العربية](README.ar.md)

> Мультиплатформенный VPN-клиент для Android и Windows с многоязычным интерфейсом.

![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Windows-blue) ![Version](https://img.shields.io/badge/version-1.0.0-red)

## Возможности

Приложения для Android и Windows; интерфейс на английском, персидском, русском, упрощённом китайском, корейском и арабском; VPN-служба Android; настольный интерфейс и значок в трее Windows.

## Структура проекта

```text
Android/    Android application (Kotlin / Gradle)
Windows/    Windows application (Python / web UI)
docs/       Translated README files
```

## Начало работы

Клонируйте репозиторий. Откройте `Android/` в Android Studio. Для Windows изучите исходники и скрипты сборки в `Windows/`, установите необходимые зависимости Python и выполните `python -m rxvpn` из соответствующего каталога приложения. Может потребоваться дополнительная настройка.

```bash
git clone https://github.com/YOUR_USERNAME/RXFoxyVPN.git
```

## Загрузки

Опубликованные сборки ищите в GitHub Releases. Исходный код не является установщиком.

## Безопасность и лицензии

Не публикуйте пароли, ключи подписи и приватные конфигурации. Проверьте лицензии сторонних компонентов перед распространением. Общая лицензия репозитория пока не выбрана.
