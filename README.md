# Courier Mobile (Android)

Нативный Android-клиент для существующего Laravel API Courier Desktop. Kotlin + Jetpack Compose + OkHttp + osmdroid/OpenStreetMap.

## Что реализовано в первой сборке

- Авторизация через `POST /api/desktop/auth/login`, опциональный Tenant ID/slug.
- Bearer token хранится в EncryptedSharedPreferences (Android Keystore-backed master key).
- Проверка сессии через `/auth/me`, выход через `/auth/logout`.
- Dashboard и статистика.
- Доступные, активные и завершённые заказы; принятие, смена статуса, подтверждение доставки.
- Редактирование расстояния и стоимости у доступного заказа.
- Чат заказа: чтение/отправка сообщений.
- Список заведений, выбор заведений и сохранение через API.
- Автоперезагрузка при открытом приложении, настройки интервала и звука.
- Самостоятельный калькулятор доставки: выбор заведения, координаты начала/конца, выбор любой точки нажатием на карту, расчёт строго через Laravel `DeliveryCalculationController`, тарифы из настроек Tenant.
- Генерация платёжной ссылки и QR-кода.
- Звуковой сигнал при появлении новых доступных заказов (когда приложение открыто).
- Поиск адресов через Nominatim и выбор точек касанием карты.
- Информация и журнал запросов/ошибок.
- Карта OpenStreetMap через нативный osmdroid MapView.

## Важно: API contract

Базовый URL вводится на экране входа, только домен с `https://`, без `/api`.
Клиент использует `/api/desktop/...`, как Python-приложение.
Нужны те же Laravel routes и контроллеры, которые лежат в `../courier_desktop/laravel` в комплекте Python-проекта. Android-клиент не заменяет серверные контроллеры.

## Сборка без Android Studio

### 1. Установить JDK 17 или 21

Установить Temurin JDK и задать `JAVA_HOME`, например:

```cmd
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17..."
```

Открыть новое окно CMD после `setx`.

### 2. Установить Android SDK Command-line Tools

Скачать **Command line tools only** с https://developer.android.com/studio#command-tools .
Распаковать так, чтобы существовал файл:

`D:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat`

Задать переменные:

```cmd
setx ANDROID_SDK_ROOT "D:\Android\Sdk"
setx PATH "%PATH%;D:\Android\Sdk\platform-tools"
```

Открыть новое CMD и выполнить `INSTALL_TOOLS.bat`. Он установит platform-tools, Android 35 и Build Tools 35.

### 3. Собрать APK

Дважды нажать `BUILD_APK.bat`. Скрипт сам скачает Gradle 8.9 (если его ещё нет), выполнит `clean assembleDebug` и положит APK в:

`dist\CourierMobile-debug.apk`

Первый запуск сборки требует интернет для загрузки Gradle и Maven-зависимостей.

### 4. Установка на телефон

Включить USB debugging и выполнить:

```cmd
adb devices
adb install -r dist\CourierMobile-debug.apk
```

Или скопировать APK на телефон и установить вручную (разрешить установку из этого источника).

## Release APK

Debug APK предназначен для тестирования. Для публикации/распространения подготовить собственный keystore и отдельный release signing config. Не хранить keystore и пароли в Git.

## Ограничения первой версии

- Автообновление работает, пока приложение открыто. Для надёжных фоновых push-уведомлений нужно отдельно подключить Firebase Cloud Messaging и серверную отправку FCM; одних polling-таймеров Android в фоне недостаточно.
- Звуковой сигнал и QR-код работают в открытом приложении. Для фоновых уведомлений потребуется FCM.
- Поиск адресов использует Nominatim; точки можно также выбрать касанием карты. Маршрутизация по дорогам на карте не рисуется: расстояние и тариф рассчитывает Laravel GEOService.
- Автоматическая GPS-отправка координат курьера пока не включена. API endpoint `/location` предусмотрен; для полноценного трекинга потребуется разрешительный flow и foreground location service.
- Структура order payload соответствует текущему `DesktopDeliverymanController::orderPayload`. Если серверная версия API отличается, понадобится адаптация.

## Безопасность

- Токен хранится через AndroidX Security EncryptedSharedPreferences.
- Разрешены только HTTPS URL в форме входа.
- Не отключать проверку SSL-сертификатов.
- Не коммитить `local.properties`, keystore или секреты.

## Backend reference files

Папка `backend_reference` содержит актуальные на момент создания Android-клиента PHP-контроллеры и пример routes. Это справочные копии для сверки API; не перезаписывай ими серверные файлы вслепую.
