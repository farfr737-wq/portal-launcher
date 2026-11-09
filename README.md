# Portal Launcher

Лаунчер Minecraft Java Edition для Windows 10/11 (Kotlin + Compose Desktop, Gradle, SQLite).
Vanilla / Forge / NeoForge / Fabric / Quilt / OptiFine, Skin Manager с 3D-просмотром, соцсистема Portal (отдельный сервер).

## Разработка в VS Code, сборка в GitHub Actions

1. Установите JDK 17+ и VS Code (расширения предложит `.vscode/extensions.json`).
2. Создайте репозиторий на GitHub, загрузите в него содержимое этой папки (`git init`, `git add .`, `git push`).
3. Каждый push запускает **Actions → Build Portal Launcher**: тесты → сборка Windows → артефакты:
   - `PortalLauncher-portable` — zip с `PortalLauncher.exe` (запускается без установки);
   - `PortalLauncher-installer` — установщик `.exe`;
   - `portal-server` — серверная часть.
4. Релиз: `git tag v1.0.0 && git push --tags` — exe прикрепятся к GitHub Release автоматически.

Локально (по желанию): `./gradlew run` (или `scripts\run.bat`), `scripts\build-windows.bat` (нужен WiX 3 для установщика).

## Структура

```
src/main/kotlin/com/portal/
  core/        пути, ОС, HTTP, JSON, SQLite, файлы
  downloads/   многопоточный загрузчик (лимит потоков/скорости, SHA-1/256, повторы)
  cache/       кэш метаданных, очистка
  versions/    манифест Mojang, разбор inheritsFrom, ассеты/библиотеки, версии (portal.json)
  loaders/     Fabric, Quilt, Forge, NeoForge, OptiFine
  java/        поиск Java, проверка, загрузка Temurin (Adoptium API, SHA-256)
  launcher/    сборка команды запуска, natives, процесс игры
  accounts/    локальные профили, Microsoft OAuth (device code), токены через Windows DPAPI
  skins/       валидация PNG, 3D-модель, локальные скины
  social/      клиент API сервера Portal, кэш чужих скинов
  settings/ logging/ ui/
server/        бэкенд Portal (JDK HttpServer + SQLite), API: docs/API.md
```

Данные лаунчера: `%APPDATA%\PortalLauncher\` (`launcher/`, `versions/`, `libraries/`, `assets/`, `java/`, `profiles/`, `skins/local|cache`, `logs/`).
Каждая версия — своя папка `versions/<id>/` с `portal.json` (настройки, загрузчик, Java, моды); миры и моды — в `profiles/<id>/`.

## Как устанавливаются загрузчики

- **Fabric/Quilt** — профиль версии берётся с официальных meta-серверов (meta.fabricmc.net, meta.quiltmc.org), библиотеки качаются с проверкой хэшей.
- **Forge/NeoForge** — официальный установщик с maven.minecraftforge.net / maven.neoforged.net запускается в режиме `--installClient` (он сам скачивает библиотеки и выполняет процессоры).
- **OptiFine** — публичного API нет: список и файл берутся с optifine.net, затем запускается официальный установщик OptiFine (нужно нажать «Install»). Если автозагрузка не сработала, положите `OptiFine_*.jar` в папку `downloads` внутри папки Portal.

## Вход через Microsoft

Нужен собственный (бесплатный) Azure-клиент:
1. portal.azure.com → Microsoft Entra ID → App registrations → New registration.
2. Supported account types: «Personal Microsoft accounts only». Redirect URI не нужен.
3. Authentication → Allow public client flows: **Yes**.
4. Скопируйте Application (client) ID в Настройки → Сервисы.
5. Для работы с Minecraft Services приложение Azure должно быть одобрено Mojang/Microsoft (форма: aka.ms/mce-reviewappid). До одобрения запросы могут отклоняться — это ограничение Microsoft, а не лаунчера.

Refresh-токены шифруются Windows DPAPI. Локальный профиль — только для тестов и не является лицензией.

## Сервер Portal (соцсистема)

```
PORTAL_PORT=8080 PORTAL_ADMIN_KEY=секрет ./gradlew :server:run
```
Или скачайте артефакт `portal-server` из Actions и запустите `bin/server`. Адрес сервера укажите на странице «Сообщество».
Статусы (`verified`, `admin`) назначает только администратор: `PUT /api/admin/users/{id}/status` с заголовком `X-Admin-Key`. Для интернета поставьте сервер за HTTPS-прокси (Caddy/nginx).

## Ограничения

- Локальный скин виден в игре только там, где это поддерживает авторизация/сервер; официальный скин меняется на minecraft.net.
- Linux: код ОС-зависимых частей вынесен в `core/Os.kt`; сборка под Linux ещё не настроена.

## Контент, языки и оформление

- **Контент** — вкладка для установки модов, ресурспаков, шейдеров, датапаков и сборок из Modrinth (ключ не нужен) и CurseForge (бесплатный ключ с console.curseforge.com вставляется в Настройки → Сервисы). Обязательные зависимости модов ставятся автоматически. Свои файлы можно добавить кнопкой «Добавить файл с компьютера». Сборки (.mrpack и zip CurseForge) ставятся как отдельная версия со своей папкой игры.
- **Язык** — 12 языков (русский, English, 中文, हिन्दी, Español, Français, العربية, Português, বাংলা, Deutsch, 日本語, Bahasa Indonesia): Настройки → Язык.
- **Оформление** — цвета фона, панелей, кнопок, обводки, текста; готовые схемы; иконка лаунчера; фоновое фото (встроенные или своё).
