# Playbook для модели Fable — поиск и исправление ошибок платформы ЭПД РТ

> **Кому:** модель Fable (используется для самых важных и трудных задач: поиск багов,
> ревью бизнес-логики, безопасность, исправление дефектов).
> **Задача:** находить реальные ошибки/баги, чинить их безопасно, проверять, что ничего
> не сломалось. Работать по циклу **база → охота → фикс → проверка → коммит**.
> **Язык общения и коммитов:** русский. **Не push’ить в GitHub без явного разрешения пользователя.**

---

## 0. Как пользоваться этим документом

1. Прочитай разделы 1–4 (что это, где что лежит, как собрать/запустить, зелёная база).
2. **Сначала подними стек и убедись, что все 4 щита зелёные** (раздел 4) — это точка отсчёта.
   Если щит красный ДО твоих правок — это уже баг, начни с него.
3. Выбери зону охоты из раздела 6 (приоритет сверху вниз). Ищи **реальные** дефекты, а не
   косметику. Для каждого кандидата — докажи сценарий (вход → неверный выход), потом чини.
4. После каждого фикса — раздел 7 (как чинить) и раздел 8 (как проверять). Щиты обязаны
   остаться зелёными. Один коммит на один осмысленный фикс.
5. Не трогай внешне-заблокированное (раздел 9). Не переоткрывай уже сделанное (раздел 5).

---

## 1. Что это за платформа

Госплатформа **«Электронные перевозочные документы РТ» (ЭПД РТ / е-Роҳхат)** для Министерства
транспорта Таджикистана. Этап 1 — электронные путевые листы (роҳхат) всех типов; этап 2 (позже) —
э-ТТН (борхат). Репозиторий: `D:\Projects\e-Waybill`.

Ключевые доменные идеи:
- **Титульная модель Т1–Т6**: диспетчер (Т1) → медосмотр (Т2) → техконтроль (Т3) → одометр на
  выезде (Т4) → возврат (Т5) → послерейсовый медосмотр (Т6). Водитель НЕ подписывает — предъявляет QR.
- **Статусная машина ПЛ**: DRAFT → CREATED → (AWAITING_PAYMENT → PAID) → READY → ISSUED → ACTIVE →
  RETURNED → COMPLETED → ARCHIVED; ветки MED_REJECTED / TECH_REJECTED / BLOCKED / CANCELLED / EXPIRED.
- **QR офлайн-проверки** (JWS ES256): инспектор проверяет подпись без сети и логина.
- **Мультиарендность (tenant)**: перевозчик видит/меняет только свои ПЛ и справочники; платформенные
  роли (SYSTEM_ADMIN, API_INTEGRATOR, INSPECTOR, MINTRANS_ANALYST) — шире.
- **Мастер-данные** (организации/водители/ТС/сотрудники) — реплика из единой платформы Минтранса
  (dev — stub), в документе фиксируются **снимки** на момент выдачи (иммутабельность).
- **Движок политик (feature-flags 3 уровня)**: NATIONAL < ORGANIZATION < VEHICLE_TYPE. Правила:
  require_med_pre, require_tech_check, require_med_post, max_validity_days, min_rest_hours, require_gps(засеян).

**Источник требований:** `spec\ru\` (ТЗ, разделы 00–19 + приложения), `spec\ТЗ-ЕДИНОЕ-DTS.md` (сводное
ТЗ + пробелы §27), `spec\data\*.yaml` (типы ПЛ, статусы, роли, проверки, интеграции), `spec\notes\`
(конспекты legacy rohkhat.tj и контекст РТ). QA-план заказчика: `docs\Test1.docx` → отчёт
`spec\QA-статус-Test1.md`.

---

## 2. Архитектура и где что лежит

### Backend — `apps\backend\` (Gradle-монорепо, Java 21 + Spring Boot 3)

Модули (settings.gradle.kts): **master-data-service** (:8081), **waybill-service** (:8082).
`crypto-service-mock` — автономная dev-утилита (не в Gradle-сборке, запускается `java CryptoServiceMock`).

```
waybill-service\src\main\java\tj\mintrans\epd\waybill\
  service\   WaybillService.java (837 стр — ЯДРО: статусная машина, блок-проверки, титулы),
             AggregatorService, FuelCalculationService, ExpenseService, WorkDayService,
             ReportService, QrTokenService, WaybillNumberGenerator, LifecycleScheduler, NotificationService
  domain\    Waybill, WaybillStatus, WaybillTitle, WaybillPayment, Expense, WorkDay, GpsPing, Notification…
  web\       WaybillController, AggregatorController, PaymentWebhookController, VerifyController,
             NeruController, GpsController, ReportController, ExpenseController, NotificationController…
  config\    SecurityConfig (OAuth2 resource server), CurrentUser (JWT→роли/rma), OpenApiConfig
  signing\   TitleSigner (интерфейс) / StubTitleSigner (sha256 dev) / CadesTitleSigner (прод, УЦ РТ)
  event\     KafkaEventBridge, NotificationEventListener, WaybillStatusChanged (@TransactionalEventListener)
  client\    MasterDataClient, ServiceTokenProvider (client-credentials межсервисные вызовы)
  resources\ application.yml, db\migration\V1..V8

master-data-service\src\main\java\tj\mintrans\epd\masterdata\
  domain\    Organization, Driver, Vehicle, Employee, Policy, Classifier, FieldDefinition,
             PlatformSetting, AuditLog, Dictionary(fuel_norm/coefficient/tariff/route/client)…
  web\       OrganizationController, DriverController, VehicleController, EmployeeController,
             PolicyController, ClassifierController, FieldDefinitionController, SettingsController,
             DictionaryController, AuditController, SyncController, DocumentExpiryController
  service\   AuditService, (клиент единой платформы)
  client\    UnifiedPlatformClient (stub|http)
  resources\ application.yml, db\migration\V1..V13
```

Снимки в ПЛ хранят историю ⇒ FK между БД нет (database-per-service: `masterdata`, `waybill`).

### Web — `apps\web\` (Next.js 15 App Router, React 19, TypeScript)

```
app\        39 страниц: login, dashboard, waybills(+[id],[id]/print,new), dispatcher, med, tech,
            driver, inspector, monitoring, company, fleet, registry, violations, reports,
            dictionaries, notifications, verify\[jws], settings\(23 подстраницы)
  layout.tsx → LangProvider → AuthProvider → Shell (гейт публичных/приватных, доступ по роли)
lib\        api.ts (клиент REST: md.*, wb.*), auth.tsx (Keycloak grant_type=password),
            i18n.tsx (словарь RU/TJ/EN ~680 ключей, useT/t/tType/tStatus), roles.ts (RBAC на фронте)
next.config.mjs  standalone + прокси /md-api→8081 /wb-api→8082 + experimental.cpus=1
```

### Инфраструктура — `infra\`

`docker-compose.yml` (dev: Postgres :5442, Redis, Kafka, Keycloak :8180, MinIO),
`docker-compose.prod.yml`, `keycloak\epd-realm.json` (realm epd + демо-юзеры),
`postgres-init\01-databases.sql` (создаёт БД masterdata, waybill).

### Скрипты-щиты — `scripts\`
`smoke-test.ps1`, `security-matrix.ps1`, `blocking-checks-test.ps1`, `concurrency-test.ps1`,
`seed-demo-*.ps1`, `seed-live-gps.ps1`, `run-cades-test.ps1`.

---

## 3. Как собрать и запустить (рецепты — критично, не очевидно!)

### ⚠️ Backend собирать ТОЛЬКО на JDK 21
Системный `JAVA_HOME` пользователя = **JDK 25**, а Gradle 8.14.3 его НЕ поддерживает (ошибка «25.0.3»).
Перед каждым вызовом `gradlew` (env не переживает между PowerShell-командами):
```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
cd D:\Projects\e-Waybill\apps\backend
.\gradlew.bat compileJava test          # компиляция + тесты
.\gradlew.bat :master-data-service:bootRun   # :8081
.\gradlew.bat :waybill-service:bootRun       # :8082
```
Прод не затронут: Dockerfile использует `gradle:8.14.3-jdk21`.

### ⚠️ Web: сборка с cpus=1, запуск ТОЛЬКО через standalone server.js
```powershell
cd D:\Projects\e-Waybill\apps\web
npx next build        # уже стабильно (next.config: experimental.cpus=1) — 39/39 страниц
# Прод-запуск НЕ через `next start` (даёт 500 при output:standalone), а:
Copy-Item .next\static .next\standalone\.next\static -Recurse -Force
if (Test-Path public) { Copy-Item public .next\standalone\public -Recurse -Force }
cd .next\standalone; $env:PORT="3000"; $env:MD_API_URL="http://127.0.0.1:8081"; $env:WB_API_URL="http://127.0.0.1:8082"
node server.js
# Для разработки достаточно: npx next dev -p 3000
```

### Инфраструктура и порты
```powershell
docker compose -f infra/docker-compose.yml up -d      # Postgres/Kafka/Keycloak/Redis/MinIO
docker ps                                              # проверить healthy
```
Порты: web 3000, master-data 8081, waybill 8082, Keycloak 8180, Postgres **5442** (не 5432!).
Освободить порт под свежий запуск: найти PID `Get-NetTCPConnection -LocalPort 8081 -State Listen`,
`Stop-Process -Id <pid> -Force`.

### Демо-логины (realm epd, логин = пароль)
`admin` (SYSTEM_ADMIN), `company` (COMPANY_ADMIN, орг 025680800), `dispatcher`, `doctor`,
`mechanic`, `accountant`, `driver`, `inspector`.
Токен:
```powershell
$t = (Invoke-RestMethod -Method Post "http://localhost:8180/realms/epd/protocol/openid-connect/token" `
  -Body "client_id=epd-web&grant_type=password&username=admin&password=admin" `
  -ContentType "application/x-www-form-urlencoded").access_token
```

---

## 4. Зелёная база — 4 ЩИТА (запускать ДО и ПОСЛЕ любой правки backend)

Требуют поднятых сервисов + Keycloak. Запуск из `D:\Projects\e-Waybill`:

| Щит | Скрипт | Эталон | Что покрывает |
|-----|--------|--------|----------------|
| Smoke | `& scripts\smoke-test.ps1` | **34/34 PASS** | весь жизненный цикл ПЛ, RBAC 401/403/409/422, QR, топливо, отчёты, политики, классификаторы |
| Безопасность | `& scripts\security-matrix.ps1` | **26/26 PASS** | границы RBAC + мультиарендность (кросс-тенант →404/403) |
| Блок-проверки | `& scripts\blocking-checks-test.ps1` | **5/5 PASS** | ВУ↔тип ТС, одометр, лицензия/страховка/техосмотр перед выдачей |
| Конкурентность | `& scripts\concurrency-test.ps1` | **2/2 PASS** | инвариант «1 активный ПЛ на ТС/водителя» под 8-way гонкой |

**Правило:** любой фикс, после которого хоть один щит краснеет, — сломан. Либо чини иначе, либо
исправляй регресс. Если добавляешь новую защиту — добавь и негатив-проверку в соответствующий щит.

⚠️ `security-matrix.ps1` — в кодировке **UTF-8 с BOM** (иначе PS 5.1 читает cp1251 и байт «—» ломает
парсер). Функция называется `Hdr`, не `H` (h — алиас Get-History). Не меняй эти инварианты файла.

---

## 5. Что уже сделано (НЕ переоткрывать)

Платформа зрелая (7+ итераций + 6 раундов ultracode-аудита ~130 агентов, 41 находка закрыта). Готово:
жизненный цикл Т1–Т6, блок-проверки, нумерация (Луна), QR-JWS+/verify, мультиарендность,
движок политик (5 правил), журнал аудита (полное покрытие + **IP/User-Agent §21**), уведомления
(in-app + колокольчик), классификаторы (страны/ADR/дозволы), Neru-витрина, GPS (приём + карточка +
радар /monitoring), монитор истечения документов, конструктор полей, оплата (PAID) + scoped-токены
агрегаторов, расходы рейса (§12), мин.отдых водителя (§5), страховка ТС (§13), «КОПИЯ»/альбом-печать
(§17), двуязычность RU/TJ (+EN на входе). Сервисные токены закрыли анонимный GET master-data.
TOCTOU-гонка «1 активный ПЛ» закрыта V7-индексами. Антифрод агрегатора (BLOCKED/CANCELLED) закрыт.

Миграции: **master-data до V13, waybill до V8**. История — в памяти проекта и git log.

---

## 6. Где искать баги — зоны охоты (приоритет сверху вниз)

Для каждого кандидата: **докажи сценарий** (конкретный вход → неверный выход/креш/утечка), только
потом чини. Предпочитай реальные дефекты домена, а не стилистику.

### A. Статусная машина и переходы — `WaybillService.java` (ядро)
- Переход из «неправильного» статуса не отклонён (`requireStatus`/`if(status!=…)`).
- Флаги `medPassed`/`techPassed` не сброшены при отклонении/замене (защёлкивание → обход осмотра).
- Замена водителя/ТС (CORRECTION): не перепроверены категория ВУ, сроки, «1 активный ПЛ», тенант.
- Гонки/идемпотентность: двойное подтверждение оплаты, двойная выдача, повторный вебхук.
- Терминальные статусы: аннулирование/закрытие из недопустимого состояния; BLOCKED в обход.
- Одометр: непрерывность (выезд < последнего пробега), возврат < выезда — антифрод.

### B. Безопасность, RBAC и мультиарендность — `SecurityConfig`, `CurrentUser`, `*Controller`, `WaybillService.get()`
- Эндпоинт без `@PreAuthorize`/`authenticated()` (утечка ПДн, обход роли).
- Tenant-scoped пользователь читает/меняет чужую организацию (должно быть 404, не 403 — не раскрывать
  существование). Все мутации ПЛ идут через `get()` — проверь, что это так.
- Перебор идентификаторов (госномер/ИНН/id) как канал утечки (Neru, GPS, expiry, effective-policies).
- Платформенные роли не должны попадать под tenant-фильтр (isPlatformAdmin: SYSTEM_ADMIN, API_INTEGRATOR,
  INSPECTOR, MINTRANS_ANALYST). Обратно — субъект без org-claim не должен видеть все организации.
- Прод-секреты с дефолтом без fail-fast (webhook-secret, QR-ключ при mode=cades).

### C. Валидация входа — `validateTypeData`, `runBlockingChecks`, `*Controller`
- Обязательные поля по типу ПЛ (2-Б прицепы, 5Б-БМ/4М-БМ виза/дозвол, 3-С услуга, ADR класс) — обходимы
  API-клиентом мимо фронта? (серверная проверка обязательна).
- Диапазоны (transportType 1–6, regionId 1–7, ADR 1–9, месяцы 1–12), позитивность сумм/норм.
- Канонизация госномера (trim+upper) при записи И в поиске.
- E-PERMIT: страна дозвола ∈ страны рейса; срок визы/дозвола.
- `dateOrNull(o.toString())` падает на дате-времени (ждёт ISO-дату) — проверь формат из мастер-данных.

### D. Схема БД и миграции — `db\migration\`, `ddl-auto: validate`
- Сущность (@Entity) ↔ миграция рассинхронены → сервис не стартует (validate). Новую колонку/таблицу
  ВСЕГДА добавляй миграцией + полем; проверяй стартом.
- Номер миграции: следующий свободный (master-data → V14, waybill → V9). Flyway читает как UTF-8.
- UNIQUE/частичные индексы для инвариантов (org-scope, «1 активный ПЛ»).

### E. Конкурентность/транзакции — `@Transactional`, события
- Check-then-insert без БД-констрейнта = TOCTOU. Пессимистичные блокировки где нужна сериализация.
- События Kafka/уведомления — `@TransactionalEventListener` после commit, best-effort (не рушат transition).
  Проверь, что сбой шины/уведомления не откатывает бизнес-операцию.

### F. QR / крипто — `QrTokenService`, `VerifyController`, `TitleSigner`
- exp/nbf QR (просроченный отклоняется), «действителен» = ISO 18013-5 статус (ISSUED/ACTIVE/RETURNED),
  а не READY/терминальные. Согласованность /verify и /inspector.
- Стабильный ключ QR в проде (JWKS не должен расходиться между репликами).

### G. Frontend — `app\`, `lib\`
- Гейт доступа по роли (Shell): по прямому URL нельзя открыть чужой кабинет.
- Фейковые/захардкоженные индикаторы (реальные данные вместо «зелёных» плашек до осмотра).
- Гонки загрузки в `useEffect` (флаг отмены), потеря ошибок валидации, `type=number`+`Number.isFinite`.
- i18n: нет «сырых» ключей на экране; префиксы не конфликтуют (напр. `exp.*` = document-expiry,
  расходы — `wexp.*`). tType/tStatus для типов/статусов.
- logout — серверный отзыв сессии (RP-initiated), не только очистка localStorage.

### H. Отчёты/агрегатор/legacy — `ReportService`, `AggregatorService`, `NeruController`
- Агрегатор (ЧУРА/НЕРУ): закрывает только СВОИ (source=AGGREGATOR) ПЛ; реальный статус в ответе
  (не «Активный» для BLOCKED/CANCELLED); формат дат `yyyy-MM-dd HH:mm`, правило 7 дней.

---

## 7. Как чинить безопасно

1. **Минимальная точечная правка** в стиле окружающего кода (та же плотность комментариев, именование,
   идиомы). Комментарии/сообщения — по-русски, как в проекте.
2. **Миграции только additive** (новые колонки nullable/с дефолтом, новые индексы). Не редактируй уже
   применённые миграции (Flyway-чексумма). Следующий свободный номер.
3. **Не ломай общие файлы бездумно**: `i18n.tsx`, `api.ts`, `SecurityConfig`, номера миграций, скрипты-щиты.
   Если правишь — проверь, что не задел смежное.
4. **Backend-правку — со стартом сервиса** (validate + Flyway) и прогоном затронутого щита.
5. **Один коммит = один осмысленный фикс.** Сообщение: `тип(scope): суть` + короткое «почему» и «как
   проверено». Типы: fix/feat/refactor/docs/chore.
6. **Проверяй `git status`/`git diff` перед коммитом** — не тащи чужие правки, бинарники (`docs/`,
   `photo/`), артефакты. Не push’ь без явного разрешения пользователя.

---

## 8. Как проверять фикс

1. **Backend**: пересобрать (JDK 21), перезапустить затронутый сервис, дождаться `health: UP`
   (валидатор Flyway+Hibernate прошёл).
2. **Прогнать релевантный щит** (раздел 4) — и smoke как минимум. Все должны остаться зелёными.
3. **Целевой негатив-тест** воспроизведённого сценария: тот же вход теперь даёт правильный
   результат/код (401/403/404/409/422). Curl/Invoke-RestMethod с нужным токеном.
4. **Web**: `npx next build` (39/39) или dev; проверить страницу в браузере/через standalone.
5. При новой защите — добавить постоянную проверку в щит (чтобы регресс ловился впредь).

---

## 9. Грабли и запреты (проверено на практике)

**PowerShell (Windows PowerShell 5.1):**
- Нет `&&`/`||`/тернарника. `del`/`rm`/`DEL` — алиасы Remove-Item (для HTTP DELETE именуй функцию иначе).
- `h` — алиас Get-History (не имя функции). Не редиректь stderr нативных exe (`2>&1`) — ломает `$?`.
- `[System.IO.File]::ReadAllBytes` использует cwd процесса, не `cd` — давай абсолютные пути.
- `-ExecutionPolicy Bypass` заблокирован политикой — запускай скрипты через `& scripts\x.ps1`.
- `Invoke-RestMethod` нестабильно парсит большой список ПЛ с кириллицей → бери id из ответа `create`
  (один объект), не `@(list)[0]`.

**Среда:**
- master-data при долгой работе залипает на браузерных keep-alive → браузерные запросы виснут
  («Загрузка…»), а health из PS = UP. Лечится перезапуском jar/сервиса.
- `licenseCategories` — токены через `[,;\s]+`: «B,C,D», НЕ слитно «BCD» (иначе проверка ВУ↔тип ТС даёт 422).
- git push на этом репо медленный (~85 МБ pack с docs/) — пускай `run_in_background`.
- В БД остаётся безвредный dev-мусор от тестов (драйверы/ТС/сотрудники) — DELETE-эндпоинт есть не у всех.

**НЕ трогать (внешне-заблокировано или отложено пользователем):**
- Реальная квалифицированная ЭЦП (нужен УЦ РТ / HSM / TSP) — сейчас Stub sha256 / скелет CAdES. Готово к
  включению `SIGNING_MODE=cades` без правок кода.
- Внешние каналы уведомлений (SMS/push/Telegram) — нужны провайдеры; интерфейс `NotificationChannel` готов.
- K8s/Helm для госЦОД, пробный запуск docker-compose.prod. Мобильные Flutter — В САМОМ КОНЦЕ (решение пользователя).
- Интеграция «получение данных из единой платформы» — позже (сейчас stub); субъекты/объекты вести внутри платформы.
- Бинарники `docs/`, `photo/` — не коммитить как часть код-фиксов.

---

## 10. Бэклог — кандидаты на работу (после охоты за багами)

Оставшиеся пробелы QA-списка (`spec\QA-статус-Test1.md`, §27 сводного ТЗ), по возрастанию риска:
1. **transitCountries мультиселект** (сейчас текст через запятую) в `/waybills/new` — мелко, фронт.
2. **Автосохранение черновика ПЛ (§7)** — фронт `/waybills/new` (localStorage draft) + аккуратно.
3. **Rate-limit (§22)** — защита от перебора (Neru/токен-эндпоинты); фильтр/бакет на backend, средний объём.
4. **Config-driven переходы статусов** — высокий риск (правка автомата), только отдельной осторожной итерацией.
5. Прод-инфра: нагрузка/бэкап/мониторинг/откат/UAT — этап, не код.

При старте новой фичи — читать `spec\ru\` и `spec\data\` как источник требований; не предлагать
Word/переводы; не изучать заново `docs\` (конспекты уже в `spec\notes\`).

---

## 11. Рабочий цикл (резюме)

```
1. Поднять инфра + оба сервиса (JDK 21) + web.  Прогнать 4 щита → должны быть зелёными.
2. Выбрать зону охоты (раздел 6, сверху вниз).  Найти реальный дефект → доказать сценарием.
3. Точечно починить (раздел 7).
4. Перезапустить сервис, прогнать щиты + целевой негатив-тест (раздел 8) → зелёные.
5. Один коммит с ясным сообщением.  НЕ push без разрешения.
6. Повторять.  Крупные находки/решения — фиксировать в памяти проекта.
```

**Зелёная база на момент передачи (12.07.2026):** smoke 34/34, security-matrix 26/26,
blocking-checks 5/5, concurrency 2/2; оба сервиса UP; web-сборка 39/39; аудит §21 и фикс сборки — в git.
