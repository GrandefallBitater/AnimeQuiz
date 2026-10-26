# AnimeQuiz

Веб-приложение для аниме-квизов: каталог тайтлов с постерами, импорт списков
просмотра из Shikimori, MyAnimeList и YummyAnime, профиль пользователя,
админ-панель и заготовка игры на WebSocket.

Приложение написано на Spring Boot 3.2 (Java 17), использует PostgreSQL,
Thymeleaf и локальное файловое хранилище для постеров и аватаров.

## Содержание

- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Настройка](#настройка)
- [Сборка и запуск](#сборка-и-запуск)
- [Первый вход](#первый-вход)
- [Хранилище файлов](#хранилище-файлов)
- [Импорт каталога](#импорт-каталога)
- [Резервное копирование](#резервное-копирование)
- [Структура проекта](#структура-проекта)
- [Диагностика](#диагностика)
- [Безопасность](#безопасность)

## Требования

| Компонент | Версия |
|---|---|
| JDK | 17 (проект собран под Java 17) |
| Maven | 3.8+ |
| PostgreSQL | 13+ (рекомендуется 16–18) |
| ОС | Windows 10/11 (x64) |

Приложение и скрипты рассчитаны только на Windows. Для сборки и запуска
нужны `cmd.exe` или Windows PowerShell 5.1 (или PowerShell 7), а также
`pg_dump`/`pg_restore` из состава PostgreSQL. Архивация хранилища использует
встроенный `tar.exe` (Windows 10 1803+).

## Быстрый старт

### 1. Создайте базу данных

Проект по умолчанию подключается к PostgreSQL на `localhost:5433` (не 5432)
к базе `AnimeQuiz`:

```sql
CREATE DATABASE "AnimeQuiz";
```

Схему `main`, таблицы и историю миграций создаст Flyway при первом запуске.
Отдельно выполнять SQL-миграции не нужно.

### 2. Проверьте подключение

Параметры находятся в `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5433/AnimeQuiz?currentSchema=main
spring.datasource.username=postgres
spring.datasource.password=postgres
```

Если у вас другой порт, пользователь или пароль — измените файл или задайте
переменные окружения (см. [Настройка](#настройка)).

### 3. Запустите приложение

```powershell
mvn spring-boot:run
```

Приложение будет доступно по адресу <http://localhost:8081>.

## Настройка

Основные параметры в `application.properties`:

| Параметр | Назначение | По умолчанию |
|---|---|---|
| `server.port` | HTTP-порт | `8081` |
| `server.address` | Интерфейс прослушивания | `0.0.0.0` |
| `spring.datasource.url` | JDBC-URL PostgreSQL | `localhost:5433/AnimeQuiz` |
| `app.storage.location` | Каталог постеров и аватаров | `%USERPROFILE%\.animequiz\storage` |
| `app.yummy.app-token` | Токен YummyAnime | пусто |
| `app.mal.client-id` | Client ID MyAnimeList | пусто |

Секреты не обязательно хранить в файле: Spring Boot читает переменные
окружения.

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5433/AnimeQuiz?currentSchema=main"
$env:SPRING_DATASOURCE_USERNAME = "animequiz"
$env:SPRING_DATASOURCE_PASSWORD = "change-me"
$env:YUMMY_APP_TOKEN = "..."
$env:MAL_CLIENT_ID = "..."
```

Переменные окружения имеют приоритет над `application.properties`.

## Сборка и запуск

### Обычный запуск

```powershell
mvn spring-boot:run
```

### Сборка jar

```powershell
mvn package
java -jar target/AnimeQuiz-*.jar
```

> **Важно.** В `pom.xml` plugin `exec-maven-plugin` привязан к фазе `package`
> и запускает `jpackage`. В результате `mvn package` дополнительно создаёт
> app-image в `%USERPROFILE%\Desktop\AnimeQuiz` и требует файл
> `anime-logo.ico` в корне проекта и JDK с утилитой `jpackage`.
> Если нужен только исполняемый jar, пропустите этот шаг:
>
> ```powershell
> mvn package -Dexec.skip=true
> ```

При сборке app-image:

1. Старая папка `%USERPROFILE%\Desktop\AnimeQuiz` удаляется — иначе jpackage
   отказывается работать («Application destination directory already exists»).
   Поэтому перед сборкой закройте запущенное приложение.
2. jpackage собирает образ из содержимого `target`.
3. Каталог `scripts` копируется рядом с `AnimeQuiz.exe`, то есть в
   `%USERPROFILE%\Desktop\AnimeQuiz\scripts`.

Скрипты не зависят от расположения: пути они вычисляют от собственного
каталога. Но задачи планировщика запоминают абсолютный путь, поэтому после
переноса папки скрипты нужно зарегистрировать заново (см. ниже).

### Запуск с другим профилем или портом

```powershell
java -jar target/AnimeQuiz-*.jar --server.port=9090
```

## Первый вход

После старта доступны страницы:

- `/` — главная;
- `/login` — вход;
- `/register` — регистрация;
- `/profile` — профиль (после входа);
- `/admin` — админ-панель (нужна роль `ADMIN`);
- `/lobby` — демонстрационная WebSocket-страница.

Миграция `V2__add_test_admin.sql` создаёт демонстрационные учётные записи
`admin` и `user` с предустановленным хешем пароля. Для собственного
развёртывания создайте нового пользователя через `/register`, а роль
администратора выдайте в базе:

```sql
INSERT INTO main.authorities (username, authority)
VALUES ('your_username', 'ROLE_ADMIN');
```

Перед публикацией приложения в интернет удалите или отключите демонстрационные
учётные записи.

## Хранилище файлов

Постеры и аватары хранятся не в базе, а на диске:

```text
%USERPROFILE%\.animequiz\storage\
├── avatars\
├── posters\
├── site_titles\
└── titles\
```

Каталог создаётся автоматически. Постеры адресуются по SHA-256 и
скачиваются фоновым обработчиком. При переносе приложения на другую машину
каталог нужно скопировать отдельно — в дамп базы он не входит.

Путь можно переопределить:

```properties
app.storage.location=D:/animequiz/storage
```

В `application.properties` обратный слэш является экранирующим символом,
поэтому путь записан через прямой: `D:/animequiz/storage` для Windows
равнозначен `D:\animequiz\storage`.

## Импорт каталога

Импорт запускается из админ-панели `/admin`.

- **Shikimori** — работает без дополнительных настроек.
- **MyAnimeList** — требуется `MAL_CLIENT_ID`.
- **YummyAnime** — требуется `YUMMY_APP_TOKEN`.

Глобальный импорт каталога и обогащение постеров выполняются в фоне. Прогресс
виден в админ-панели. Во время импорта не рекомендуется запускать резервное
копирование: нагрузка на базу и диск возрастает.

## Резервное копирование

В проекте есть готовые скрипты для Windows PowerShell. Они делают логический
дамп PostgreSQL и zip-архив файлового хранилища.

### Что копируется

| Данные | Формат | Расписание по умолчанию | Хранение |
|---|---|---|---|
| База `AnimeQuiz` | `pg_dump --format=custom` | ежедневно в 03:00 | 30 дней |
| Постеры и аватары | ZIP | воскресенье в 03:30 | 4 архива |
| Журналы скриптов | текст | при каждом запуске | не удаляются |

По умолчанию всё складывается в `%USERPROFILE%\Backups\AnimeQuiz`:

```text
%USERPROFILE%\Backups\AnimeQuiz\
├── db\
├── storage\
└── logs\
```

Путь можно изменить параметром `-BackupRoot` или переменной окружения
`ANIMEQUIZ_BACKUP_ROOT`.

### Настройка пароля PostgreSQL

Скрипты не запрашивают пароль интерактивно и используют стандартный файл
`pgpass`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup-pgpass.ps1
```

Скрипт создаст `%APPDATA%\postgresql\pgpass.conf`, ограничит права доступа
текущим пользователем и проверит подключение. Пароль хранится в открытом
виде — это штатное поведение libpq; защита строится на правах к файлу.

### Ручной запуск

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup-db.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backup-storage.ps1
```

Параметры:

```powershell
# другой каталог и срок хранения
... backup-db.ps1 -BackupRoot D:\Backups\AnimeQuiz -KeepDays 60

# другое число недельных архивов
... backup-storage.ps1 -KeepArchives 8
```

### Автоматический запуск

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\register-backup-tasks.ps1
```

Создаются задачи `AnimeQuiz DB Backup` и `AnimeQuiz Storage Backup` с
параметром «выполнять при пропущенном запуске». Если скрипт запущен без прав
администратора, задачи создаются в режиме `Interactive` — они выполняются,
когда пользователь вошёл в Windows. Для работы без входа в систему запустите
скрипт от имени администратора.

Задачи ссылаются на тот каталог, из которого запущен скрипт. Если запустить
`register-backup-tasks.ps1` из `%USERPROFILE%\Desktop\AnimeQuiz\scripts`,
планировщик будет брать скрипты оттуда, а не из репозитория.

Проверка:

```powershell
Get-ScheduledTask -TaskName "AnimeQuiz DB Backup", "AnimeQuiz Storage Backup"
Get-ScheduledTaskInfo -TaskName "AnimeQuiz DB Backup"
```

`LastTaskResult = 0` означает успех.

### Восстановление базы

Безопасный вариант — в отдельную базу `AnimeQuiz_restore`, рабочая база не
затрагивается:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-db.ps1
```

Скрипт возьмёт самый свежий дамп, проверит контрольную сумму, создаст базу и
покажет количество строк и историю Flyway.

Восстановление поверх рабочей базы требует `-Force` и остановленного
приложения:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-db.ps1 `
    -TargetDatabase AnimeQuiz -Force
```

Дамп содержит историю Flyway. Восстанавливать его нужно вместе с совместимой
версией приложения: откат базы — это всегда дамп + версия кода, которая
работала на момент дампа.

### Восстановление файлов

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\restore-storage.ps1
```

Текущий каталог storage будет переименован в `storage.bak-<дата>`, архив
распакован, при ошибке выполнится откат. Старую папку можно удалить после
проверки или сразу передать `-RemoveOldOnSuccess`.

### Ограничения

- Бэкапы на том же диске защищают от ошибок и неудачных миграций, но не от
  отказа диска. Для надёжности используйте второй диск или внешнее хранилище.
- В дампе хранятся хеши паролей и пользовательские данные. Не публикуйте
  дампы.
- Перед восстановлением рабочей базы остановите приложение.
- Проверяйте бэкапы восстановлением: дамп, который ни разу не разворачивали,
  нельзя считать надёжным.

## Структура проекта

```text
src/main/java/com/example/animequiz/
├── controller/     HTTP-контроллеры
├── entity/         JPA-сущности
├── repository/     Spring Data репозитории
├── service/        бизнес-логика, импорт, хранение файлов
└── securityConfig/ Spring Security и сессии

src/main/resources/
├── db/migration/   Flyway-миграции
├── static/         CSS, JavaScript, изображения
├── templates/      Thymeleaf-шаблоны
└── application.properties

scripts/            резервное копирование и восстановление (Windows)
```

## Диагностика

| Проблема | Решение |
|---|---|
| `Connection refused` к PostgreSQL | проверьте, что сервер запущен и слушает порт `5433` |
| `database "AnimeQuiz" does not exist` | создайте базу: `CREATE DATABASE "AnimeQuiz";` |
| Flyway: `Detected applied migration not resolved locally` | версия приложения старше базы; используйте совместимый jar или дамп |
| `mvn package` падает на `jpackage` | добавьте `anime-logo.ico` в корень или запустите `mvn package -Dexec.skip=true` |
| `pg_dump not found` | установите PostgreSQL client tools или задайте `ANIMEQUIZ_PG_BIN` |
| Задача планировщика не выполняется | проверьте `Get-ScheduledTaskInfo`; режим `Interactive` требует входа в Windows |
| Кириллица в логах выглядит битой | откройте файл в редакторе с UTF-8, смените кодировку консоли на UTF-8 |

## Безопасность

Проект предназначен для локального или домашнего использования. Перед
публикацией в интернет:

1. Смените пароль PostgreSQL и пароль приложения по умолчанию.
2. Удалите демонстрационные учётные записи `admin` и `user`.
3. Ограничьте `server.address` локальным интерфейсом или поставьте reverse
   proxy с HTTPS.
4. Проверьте права на эндпоинты создания и редактирования пользователей.
5. Не храните `pgpass.conf`, `.env` и дампы в репозитории.

## Лицензия

Проект не содержит файла лицензии. Уточните условия использования у автора.
