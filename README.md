# Платформа «Электронные перевозочные документы Республики Таджикистан» (ЭПД РТ)

Государственная платформа Министерства транспорта РТ:
**этап 1** — электронные путевые листы (роҳхати электронӣ) всех типов, включая международные;
**этап 2** — электронные товарно-транспортные накладные (борхати электронӣ, э-ТТН/eCMR).

## Структура репозитория

```
e-Waybill\
├── apps\
│   ├── backend\       # микросервисы Java/Spring: master-data-service (8081), waybill-service (8082)
│   ├── web\           # веб-консоль Next.js (профили: all|waybill|oversight)
│   └── public\        # публичный портал проверки ПЛ по QR (Next.js, :3002)
├── packages\
│   └── shared\        # общий код фронта @epd/shared: lib (auth/i18n/roles/verify/…) + UI
├── infra\             # docker-compose (dev / prod-подобный), Keycloak realm, .env
├── scripts\           # PowerShell: запуск фронта, сиды, регресс-щиты (smoke/security/…), бэкап
├── docs\              # исходные материалы заказчика (legacy rohkhat.tj, сканы форм) — архив, не редактируется
├── spec\
│   ├── ru\            # ТЗ (русский): 00-титульный-лист … 19-риски, 90-приложения
│   ├── data\          # единый источник структурированных данных (YAML):
│   │                  #   waybill-types, waybill-statuses, roles-matrix, checks, integrations, dictionaries
│   ├── diagrams\      # mermaid-диаграммы (.mmd) + png\ (рендер)
│   └── notes\         # рабочие конспекты фактуры (legacy API, справочники, контекст РТ)
└── README.md
```

ТЗ ведётся в markdown (`spec\ru\`) — это рабочий формат для дальнейшей разработки платформы.

## Ключевые решения

- **Титульная модель** документа Т1–Т6 (по образцу ГИС ЭПД РФ): диспетчер → медосмотр → техконтроль → одометр на выезде → возврат → послерейсовый медосмотр; водитель не подписывает — предъявляет QR.
- **QR с офлайн-проверкой** (модель ISO 18013-5 / E-Way Bill Индии): инспектор проверяет подпись без сети и без логина.
- **Мастер-данные** (организации, водители, ТС, лицензии) — из единой платформы Минтранса; в документе фиксируются снимки на момент выдачи.
- **Стек**: Java 21 + Spring Boot 3 (микросервисы), PostgreSQL 16, Kafka, Redis, MinIO, Keycloak, Camunda; Next.js + React + TypeScript (web); Flutter (мобильные: водитель, медик/механик, инспектор); Kubernetes в госЦОД РТ; ЭЦП по Закону РТ № 1965.
- **Legacy-совместимость**: типы ПЛ наследуют формы 1-А, 1-АД, 2-Б, 3-С, 5Б-БМ, 4М-БМ; сохраняется API-контракт агрегаторов (ЧУРА/НЕРУ).

## Запуск dev-окружения (Этап 1а, walking skeleton)

```powershell
# 1. Инфраструктура (PostgreSQL, Redis, Kafka, Keycloak, MinIO)
docker compose -f infra/docker-compose.yml up -d

# 2. Сборка backend
cd apps/backend
./gradlew.bat build

# 3. Запуск сервисов (в двух терминалах)
./gradlew.bat :master-data-service:bootRun   # порт 8081, Swagger: /swagger-ui.html
./gradlew.bat :waybill-service:bootRun       # порт 8082, Swagger: /swagger-ui.html
```

Жизненный цикл ПЛ (REST): `POST /api/v1/waybills` → `/titles/t1` (диспетчер) → `/confirm-med` (врач) → `/confirm-tech` (механик) → номер + `/qr` → `/issue` (водитель) → `/activate` (Т4) → `/return` (Т5) → `/close` (Т6). Проверка QR: `GET /api/v1/verify/{jws}`, ключи: `/.well-known/jwks.json`.

```powershell
# 4. Фронтенд (dev). Веб-консоль:
powershell -File scripts\run-web.ps1                      # монолит (все роли), :3000
powershell -File scripts\run-web.ps1 -Profile waybill     # кабинет перевозчика, :3000
powershell -File scripts\run-web.ps1 -Profile oversight   # платформа надзора,   :3001
# Публичный портал проверки ПЛ по QR (отдельное приложение):
powershell -File scripts\run-public.ps1                   # :3002
```

Прод-подобный стек одним узлом: `docker compose -f infra/docker-compose.prod.yml up -d --build`
(поднимает backend + монолит web :3000 + публичный портал :3002). Профильные сборки
консоли (кабинет перевозчика :3010 / платформа надзора :3011) — опционально:
`docker compose -f infra/docker-compose.prod.yml --profile split up -d --build`.

## Статус

- [x] Исследование мирового опыта (Россия ГИС ЭПД, Индия E-Way Bill, Казахстан ЕСУТД, Узбекистан, eCMR/eTIR, X-Road)
- [x] Конспекты legacy-систем (rohkhat.tj, программа НА ва ХЛ)
- [x] Структурированные данные (spec\data)
- [x] Диаграммы (spec\diagrams)
- [x] Разделы ТЗ: 00–19 + приложения (21 файл, ~600 КБ, 124 функциональных требования)
- [x] Финальная сверка полноты (типы ПЛ, статусы, роли, интеграции, legacy-совместимость)
- [x] **Этап 1а, итерация 1 (walking skeleton)**: инфраструктура (docker-compose), master-data-service (8081), waybill-service (8082) — полный жизненный цикл ПЛ с титулами Т1–Т6, национальным номером и QR проверен сквозным сценарием
- [x] **Итерация 2**: legacy-API агрегаторов (`/api/v1/aggregator/waybills`, семантика ЧУРА/НЕРУ: закрытие предыдущего ПЛ, правило 7 дней, формат дат `yyyy-MM-dd HH:mm`), многодневные ПЛ (work_days + топливо), **веб-кабинет диспетчера** (apps/web, Next.js 15, http://localhost:3000: реестр, создание, карточка с действиями по титулам, QR)
- [x] **Итерация 3**: Keycloak-авторизация с ролями (`@PreAuthorize`, мультиарендность по claim `organization_rma`), АРМ врача (`/med`) и механика (`/tech`) в вебе, нормирование топлива (`/fuel-calculation`, нормы + коэффициенты + нархнома), отчёты (`/reports`: сводка, журнал диспетчера, по водителям/ТС/топливу), справочники НСИ (`/dictionaries`), кабинет компании (`/company`), публичная страница проверки QR (`/verify/{jws}`)
- [x] **Итерация 4 (полнота статусной машины)**: замена водителя/ТС после недопуска корректирующим титулом CORRECTION (`/replace-driver`, `/replace-vehicle`: MED_REJECTED/TECH_REJECTED → CREATED), блокировка инспектором и разблокировка админом Минтранса (`/block`, `/unblock`: ACTIVE ↔ BLOCKED), автопереходы по расписанию (LifecycleScheduler: READY/ISSUED/ACTIVE → EXPIRED по сроку + грейс-период, COMPLETED → ARCHIVED по ретенции)
- [x] **Подготовка к продакшн-развёртыванию**: конфигурация через env (DB_URL, KEYCLOAK_ISSUER_URI, MASTER_DATA_URL, MD_API_URL/WB_API_URL, NEXT_PUBLIC_KEYCLOAK_URL), Dockerfile'ы (apps/backend — параметризованный ARG SERVICE; apps/web — standalone), infra/docker-compose.prod.yml (полный стек одного узла)
- [x] **Итерация 5 (закрытие контура этапа 1)**: оплата ПЛ (`AWAITING_PAYMENT → PAID → READY`: таблица waybill_payment, `GET/POST /{id}/payment|confirm-payment`, роль ACCOUNTANT, включается PAYMENT_ENABLED=true; агрегаторские ПЛ покрыты абонементом), scoped-доступ агрегаторов (AGGREGATOR_OPEN=false → требуется client-credentials токен клиента `epd-aggregator` с ролью API_INTEGRATOR, realm обновлён)
- [x] **Итерация 6 (единая платформа Минтранса)**: субъекты (физлицо/ИП/юрлицо) и объекты (ТС) больше НЕ регистрируются в ЭПД — добавляются по ИНН/госномеру через `POST /api/v1/sync/*`, данные приходят из единой платформы (налоговая, ГАИ, Минздрав) и хранятся как реплика (`source=UNIFIED`, `synced_at`); прямые upsert-эндпоинты — только push-канал единой платформы (роль API_INTEGRATOR) и сисадмин; клиент: `UNIFIED_PLATFORM_MODE=stub` (dev-имитация) / `http` (реальная платформа); редизайн веб-кабинета в госбренд РТ (лента флага, зелёный/золото, активная навигация, единая дизайн-система globals.css)
- [x] **Итерация 7 (интеграционный контур)**: статусные события ПЛ в Kafka (topic `epd.waybill.status`, публикация после commit через `@TransactionalEventListener`; читают единый кабинет/аналитика/антифрод; TODO prod: outbox), webhook платёжного шлюза (`POST /api/v1/payments/webhook`, заголовок `X-Payment-Secret`, AWAITING_PAYMENT → PAID → READY без бухгалтера), онлайн-валидация дозвола E-PERMIT при создании международных ПЛ (`GET /api/v1/sync/permit/{number}` через единую платформу; недействительный дозвол → 422)
- [x] **Итерация 8 (разделение контуров фронта)**: общий код консоли вынесен в workspace-пакет `@epd/shared` (`packages/shared`: auth/profile/roleaccess/orgscope/brand/verify/i18n + UI `VerifyView`); веб-консоль собирается в двух профилях `NEXT_PUBLIC_APP_PROFILE=waybill|oversight` (кабинет перевозчика / платформа надзора) — каждый в своём `distDir`, с границей профиля в `Shell` и экраном «Это не ваш кабинет»; по умолчанию профиль `all` (единый монолит) — поведение до разделения сохранено. Публичная проверка ПЛ по QR выделена в **отдельное минимальное приложение `apps/public`** (порт 3002, `epd-public`) без авторизации и служебного кода — минимальная поверхность атаки публичного контура; QR ведёт на него через `NEXT_PUBLIC_VERIFY_BASE_URL`. Оркестрация: `apps/web`/`apps/public` собираются из корня монорепо (нужен `@epd/shared`), в `infra/docker-compose.prod.yml` добавлен сервис `public` и профильные `web-waybill`/`web-oversight` (opt-in: `docker compose --profile split up`); dev-запуск — `scripts/run-web.ps1 -Profile ...` и `scripts/run-public.ps1`. Собрано и проверено: backend (JDK 21), web (all/waybill/oversight), public — все сборки зелёные
- [x] **Итерация 9 (закрытие внутренних функциональных пробелов)**: security-хардинг §22 (`@PreAuthorize` на удаление документов субъекта и просмотр дозвола `/sync/permit`); расходы рейса в печатном бланке §17 (только подтверждённые, общий фрагмент во всех формах); усиленная валидация заправок топлива §11 (неотрицательность, соответствие виду топлива ТС, дата не в будущем); фотофиксация неисправностей при техосмотре §9 (тип вложения `DEFECT_PHOTO` + загрузка в АРМ механика); уникальность VIN ТС §6 (частичный индекс `uq_vehicle_vincode`, V49); возврат оплаты §16 (`CONFIRMED→REFUNDED`, роль ACCOUNTANT, V22; движение денег — внешний хук); журнал проверок ПЛ по QR §18 (`verify_scan_log`, V23, неблокирующая запись + история для надзора); проверка срока сертификата врача при медосмотре §8 (поля на Employee, V50, мягкая блок-проверка); ввод/приём даты рождения и стажа водителя §5 (контракт синка; политики ценза остаются off by default); возвращена обязательная 2FA (CONFIGURE_TOTP) для admin/analyst/inspector. Регрессия зелёная после каждого шага (smoke 35/35, security-matrix 27/27, blocking 5/5, concurrency 4/4). Попутно исправлены реальные дефекты: NPE в `FuelCalculationService` (тернарник short/Short) и битый образ `epd/master-data`
- [ ] Далее: встраивание в единый личный кабинет платформы Минтранса (SSO уже общий; ПЛ, лицензии, сертификаты и дозволы в одном месте), мобильные приложения (Flutter: водитель, инспектор), квалифицированная ЭП (CAdES) титулов через Crypto Service, outbox-паттерн для событий, файлы в MinIO (акты, фото неисправностей), Kubernetes в госЦОД; **этап 2** — э-ТТН/eCMR отдельным микросервисом `consignment-service` в этой же платформе (общие мастер-данные, Keycloak, QR-инфраструктура)

## Следующий шаг после ТЗ

Разработка платформы в этом же репозитории: `apps\` (микросервисы Java/Spring, web Next.js, мобильные Flutter) — по разделу 16 ТЗ «Этапы и порядок разработки».
