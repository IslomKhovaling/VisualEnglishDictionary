# Как получить APK Visual English Dictionary

В проект добавлен GitHub Actions workflow: `.github/workflows/android-apk.yml`.

## Вариант 1 — Android Studio на Windows

1. Распакуйте ZIP.
2. Откройте Android Studio → **Open** → выберите папку `VisualEnglishDictionary`.
3. Дождитесь Gradle Sync. При запросе установите Android SDK Platform 35 и Build Tools.
4. Выберите **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. APK появится по адресу `app/build/outputs/apk/debug/app-debug.apk`.

## Вариант 2 — автоматическая сборка на GitHub

1. Создайте пустой репозиторий GitHub и загрузите в него содержимое этой папки.
2. Откройте вкладку **Actions**.
3. Выберите workflow **Build Android APK** и нажмите **Run workflow** (или сделайте push в main/master).
4. После завершения откройте запуск workflow и скачайте artifact `VisualEnglishDictionary-debug-apk`.
5. Распакуйте artifact — внутри будет `app-debug.apk`.

## Важно

- В этой среде отсутствуют Android SDK, Gradle и доступ к интернету для их установки, поэтому APK здесь не был собран и не прошёл запуск на устройстве.
- `app-debug.apk` — отладочная сборка для тестирования. Для публикации в Google Play потребуется отдельная подписанная release-сборка.
- Минимальная версия Android в проекте: API 24 (Android 7.0).
