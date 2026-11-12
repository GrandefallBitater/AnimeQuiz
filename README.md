# AnimeQuiz

Веб-приложение для аниме-квизов: каталог тайтлов с постерами, импорт списков
просмотра из Shikimori, MyAnimeList и YummyAnime, профиль пользователя,
админ-панель и заготовка игры на WebSocket.

Приложение написано на Spring Boot 3.2 (Java 17), использует PostgreSQL,
Thymeleaf и локальное файловое хранилище для постеров и аватаров.
Развёртывание выполняется только через Docker Compose.

## Содержание

- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Настройка](#настройка)
- [Сборка и запуск](#сборка-и-запуск)
- [Первый вход](#первый-вход)
- [Хранилище файлов](#хранилище-файлов)
- [Импорт каталога](#импорт-каталога)
- [Резервное копирование](#резервное-копирование)
- [Мониторинг базы данных](#мониторинг-базы-данных)
- [Структура проекта](#структура-проекта)
- [Диагностика](#диагностика)
- [Безопасность](#безопасность)

## Требования

| Компонент | Версия |
|---|---|
| Docker Engine | 24+ |
| Docker Compose | v2 |
| ОС | любая, поддерживаемая Docker |

JDK, Maven и PostgreSQL на хосте не нужны: приложение собирается внутри
Docker-образа, а база данных запускается отдельным контейнером.

## Быстрый старт

### 1. Подготовьте окружение

```bash
cp .env.example .env
```

Откройте `.env` и задайте пароль PostgreSQL. Остальные значения можно
оставить пустыми.

### 2. Запустите приложение

```bash
docker compose up -d --build
```

Первый запуск собирает образ и может занять несколько минут. Приложение
будет доступно по адресу <http://localhost:8081>.

### 3. Проверьте состояние

```bash
docker compose ps
docker compose logs -f app
```

`db` должен быть `healthy`, `app` и `scheduler` — `running`.

## Настройка

Параметры читаются из `.env` и `src/main/resources/application.properties`.
Переменные окружения имеют приоритет.

| Переменная | Назначение | По умолчанию |
|---|---|---|
| `POSTGRES_DB` | имя базы | `AnimeQuiz` |
| `POSTGRES_USER` | пользователь PostgreSQL | `animequiz` |
| `POSTGRES_PASSWORD` | пароль PostgreSQL | обязателен |
| `YUMMY_APP_TOKEN` | токен YummyAnime | пусто |
| `MAL_CLIENT_ID` | Client ID MyAnimeList | пусто |
| `BACKUP_KEEP_DAYS` | срок хранения дампов БД | `30` |
| `BACKUP_KEEP_ARCHIVES` | число архивов storage | `4` |
| `TZ` | часовой пояс планировщика бэкапов | `UTC` |

`.env` добавлен в `.gitignore`. Не коммитьте его и не публикуйте дампы:
в них есть хеши паролей и пользовательские данные.

Параметры самого приложения:

| Параметр | Назначение | По умолчанию |
|---|---|---|
| `server.port` | HTTP-порт | `8081` |
| `spring.datasource.url` | JDBC-URL PostgreSQL | `db:5432/AnimeQuiz` |
| `app.storage.location` | каталог постеров и аватаров | `/app/storage` в Docker |
| `logging.file.name` | файл логов | `/app/storage/logs/app.log` в Docker |

## Сборка и запуск

```bash
# собрать образ и запустить всё
docker compose up -d --build

# пересобрать только приложение
docker compose build app
docker compose up -d app

# остановить, данные в томах сохранятся
docker compose down

# удалить контейнеры вместе с базой и storage
docker compose down -v
```

`docker compose down -v` удаляет оба именованных тома вместе с базой и
постерами. Правило: `down` — без `-v`, а `-v` — только сразу после свежего
бэкапа. То же относится к `docker system prune --volumes`: он удаляет тома всех
остановленных контейнеров, включая эту базу.

Логи приложения пишутся одновременно в stdout и в файл
`/app/storage/logs/app.log` внутри тома `animequiz_animequiz_storage`.

```bash
docker compose logs -f app
docker compose exec app tail -f /app/storage/logs/app.log
```

Для локальной разработки без Docker можно собрать jar:

```bash
mvn -DskipTests package
```

Но запускать приложение рекомендуется через Compose, чтобы не расходились
версии PostgreSQL и параметры окружения.

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
развёртывания создайте пользователя через `/register`, а роль администратора
выдайте в базе:

```bash
docker compose exec db psql -U animequiz -d AnimeQuiz -c \
  "INSERT INTO main.authorities (username, authority) VALUES ('your_username', 'ROLE_ADMIN');"
```

Перед публикацией приложения в интернет удалите или отключите демонстрационные
учётные записи.

## Хранилище файлов

Постеры, аватары и логи хранятся не в базе, а в именованном томе:

```text
animequiz_animequiz_storage -> /app/storage
├── avatars/
├── posters/
└── logs/
    └── app.log
```

Том создаётся автоматически. Постеры адресуются по SHA-256 и скачиваются
фоновым обработчиком. При переносе развёртывания на другую машину данные
переносятся вместе с томом или из резервной копии.

Посмотреть содержимое тома:

```bash
docker compose exec app ls -la /app/storage
docker compose exec app find /app/storage -type f | wc -l
```

## Импорт каталога

Импорт запускается из админ-панели `/admin`.

- **Shikimori** — работает без дополнительных настроек.
- **MyAnimeList** — требуется `MAL_CLIENT_ID`.
- **YummyAnime** — требуется `YUMMY_APP_TOKEN`.

Глобальный импорт каталога и обогащение постеров выполняются в фоне. Прогресс
виден в админ-панели. Во время импорта не рекомендуется запускать резервное
копирование: нагрузка на базу и диск возрастает.

## Резервное копирование

Резервное копирование выполняется внутри Docker. Постоянный контейнер
`scheduler` запускает задачи по расписанию, а сервис `backup` используется для
ручных запусков и восстановления. На хосте не нужны `pg_dump`, `tar`, `cron`
или другие утилиты — только Docker.

### Что копируется

| Данные | Формат | Расписание по умолчанию | Хранение |
|---|---|---|---|
| База `AnimeQuiz` | `pg_dump --format=custom` | ежедневно в 03:00 | 30 дней |
| Постеры и аватары | `tar.gz` | воскресенье в 03:30 | 4 архива |
| Журналы запусков | текст | при каждом запуске | не удаляются |

Каталог логов внутри storage (`logs/`) в архив не попадает.

По умолчанию всё складывается в `backups/` рядом с `compose.yaml`:

```text
backups/
├── db/
├── storage/
└── logs/
```

`backups/` добавлен в `.gitignore`. Храните копии вне репозитория и, желательно,
на другом диске или в удалённом хранилище.

### Ручной запуск

```bash
# дамп базы
docker compose --profile tools run --rm backup db

# архив постеров и аватаров
docker compose --profile tools run --rm backup storage

# оба действия подряд
docker compose --profile tools run --rm backup all

# список файлов
docker compose --profile tools run --rm backup list
```

Параметры хранения можно задать в `.env`:

```properties
BACKUP_KEEP_DAYS=30
BACKUP_KEEP_ARCHIVES=4
```

### Автоматический запуск

Расписание выполняет контейнер `scheduler` (Supercronic). Он запускается
вместе с остальными сервисами и постоянно работает в фоне, а внутри него по
расписанию вызывается тот же `scripts/backup.sh`. Cron на хосте не нужен.

Расписание лежит в `scripts/backup/crontab`:

```cron
# ежедневный дамп базы в 03:00
0 3 * * * /scripts/backup.sh db

# еженедельный парный бэкап по воскресеньям в 03:30
30 3 * * 0 /scripts/backup.sh all

# ежемесячная проверка восстановления первого числа в 04:00
0 4 1 * * /scripts/backup.sh restore-db --target AnimeQuiz_restore
```

Воскресное задание делает `all`, поэтому раз в неделю база и файлы снимаются
в одном окне и подходят друг к другу; ежедневные дампы остаются для меньшего
RPO. Ежемесячное задание восстанавливает последний дамп в отдельную базу
`AnimeQuiz_restore` и печатает счётчики строк и историю Flyway, то есть
проверяет, что бэкап действительно разворачивается. Тестовая база остаётся до
следующего прогона.

Время считается в часовом поясе контейнера. Задайте его в `.env`:

```properties
TZ=Europe/Kyiv
```

Если `TZ` не задан, используется `UTC`. После изменения расписания или
часового пояса перезапустите планировщик:

```bash
docker compose up -d scheduler
```

Проверка:

```bash
docker compose ps scheduler
docker compose logs -f scheduler
docker compose exec scheduler supercronic -test /scripts/crontab
```

Вывод задач идёт в `docker compose logs scheduler` и в
`backups/logs/backup-<год-месяц>.log`.

### Восстановление базы

Безопасный вариант — в отдельную базу `AnimeQuiz_restore`, рабочая база не
затрагивается:

```bash
docker compose --profile tools run --rm backup restore-db
```

Скрипт возьмёт самый свежий дамп, проверит SHA-256, создаст базу с кодировкой
`UTF8` и локалью `C.UTF-8`, восстановит данные и покажет количество строк и
историю Flyway.

Восстановление поверх рабочей базы требует `--force` и остановленного
приложения:

```bash
docker compose stop app
docker compose --profile tools run --rm backup restore-db \
  --file db/AnimeQuiz_2026-11-07_030000.dump \
  --target AnimeQuiz \
  --force
docker compose start app
```

Дамп содержит историю Flyway. Восстанавливать его нужно вместе с совместимой
версией приложения: откат базы — это всегда дамп плюс версия кода, которая
работала на момент дампа.

### Восстановление файлов

```bash
docker compose stop app
docker compose --profile tools run --rm backup restore-storage --force
docker compose start app
```

Текущее содержимое storage (кроме `logs/`) перемещается в
`/app/storage/.restore-<дата>`, архив распаковывается, при ошибке выполняется
откат. Старые файлы можно удалить после проверки или сразу передать
`--remove-old`. Если приложение ещё отвечает на `app:8081`, скрипт откажется
работать без `--force`.

### Ограничения

- Бэкапы на том же диске защищают от ошибок и неудачных миграций, но не от
  отказа диска. Для надёжности используйте второй диск или внешнее хранилище.
- В дампе хранятся хеши паролей и пользовательские данные. Не публикуйте
  дампы.
- Перед восстановлением рабочей базы и storage остановите приложение.
- Проверяйте бэкапы восстановлением: дамп, который ни разу не разворачивали,
  нельзя считать надёжным.

## Мониторинг базы данных

В `scripts/sql` лежат SQL-запросы только для чтения. Они помогают следить за
каталогом: считать тайтлы и заглушки, проверять постеры, ошибки обогащения и
распределение тайтлов по пользовательским спискам.

| Файл | Что показывает |
|---|---|
| `01_overview.sql` | Общая сводка по каталогу: заглушки, постеры, обогащение, источники ID, сироты. |
| `02_completeness_poster.sql` | Разбивка по `completeness` и `poster_status`. |
| `03_by_user.sql` | Количество тайтлов и заглушек в списке каждого пользователя. |
| `04_stubs.sql` | Список заглушек с ошибками обогащения и числом списков. |
| `05_top_titles.sql` | Топ-50 тайтлов по числу списков пользователей. |

Запуск через контейнер базы:

```bash
docker compose exec -T db psql -U animequiz -d AnimeQuiz < scripts/sql/01_overview.sql
```

Файлы также можно открыть в pgAdmin, DBeaver или Adminer и выполнить целиком.
Запросы начинаются с `SET search_path TO main;`; при другой схеме поправьте
эту строку. Подробное описание полей и того, что считать заглушкой, — в
`scripts/sql/README.md`.

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

scripts/backup/     резервное копирование, восстановление и расписание
scripts/sql/        SQL-запросы для мониторинга базы

Dockerfile            сборка образа приложения
Dockerfile.scheduler  образ планировщика бэкапов (Supercronic)
compose.yaml          PostgreSQL, приложение, планировщик и сервис backup
.env.example          шаблон локальных настроек
```

## Диагностика

| Проблема | Решение |
|---|---|
| `port is already allocated` | остановите процесс на порту 8081 или 5434 |
| `database "AnimeQuiz" does not exist` | проверьте `.env` и пересоздайте контейнеры: `docker compose down && docker compose up -d` |
| Приложение не стартует, в логах ошибка подключения к БД | `docker compose ps`, `docker compose logs db`; дождитесь статуса `healthy` |
| Flyway: `Detected applied migration not resolved locally` | версия приложения старше базы; используйте совместимый образ или дамп |
| Нужно понять, сколько в каталоге заглушек и ошибок | выполните `scripts/sql/01_overview.sql`, список заглушек — `04_stubs.sql` |
| Бэкап не запускается | `docker compose ps scheduler`, `docker compose logs scheduler`; расписание — `scripts/backup/crontab` |
| Кириллица в логах выглядит битой | откройте файл в редакторе с UTF-8; Docker и PostgreSQL используют UTF-8 |
| Нужно освободить место | `docker system df`, старые бэкапы в `backups/` |

Полезные команды:

```bash
docker compose ps
docker compose logs --tail 100 app
docker compose exec db psql -U animequiz -d AnimeQuiz
docker volume ls --filter name=animequiz
docker volume inspect animequiz_animequiz_pgdata
```

## Безопасность

Проект предназначен для локального или домашнего использования. Перед
публикацией в интернет:

1. Смените пароль PostgreSQL в `.env` и удалите демонстрационные учётные
   записи `admin` и `user`.
2. Не открывайте порт PostgreSQL (`5434`) наружу без необходимости.
3. Ограничьте `server.address` локальным интерфейсом или поставьте reverse
   proxy с HTTPS.
4. Проверьте права на эндпоинты создания и редактирования пользователей.
5. Не храните `.env`, `backups/` и дампы в репозитории.

## Лицензия

Проект не содержит файла лицензии. Уточните условия использования у автора.
