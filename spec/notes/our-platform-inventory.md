# Инвентаризация платформы e-Waybill (е-Роҳхат) — по текущему коду

> Составлено 2026-09-12 прямым чтением кода `D:\Projects\e-Waybill`. Цель — полная опись
> НАШЕЙ платформы в той же структуре, что для боевого MinTransRT/rohkhat, чтобы можно было
> сравнивать один-в-один. Источники: `apps/web/app/**`, `packages/shared/lib/{roles,i18n,profile}.ts`,
> `apps/web/app/shell.tsx`, `apps/backend/{master-data-service,waybill-service}/**`,
> `infra/keycloak/epd-realm.json`, миграции `resources/db/migration/*.sql`.
>
> Соотношение с готовыми документами: `spec/notes/04-gap-анализ…` и `spec/QA-статус-Test1.md`
> прочитаны. В ряде мест они **устарели** относительно кода — отмечено «(было в 04: …)».
> Самое заметное: справочники `Cargo`, `Direction`, `City` теперь существуют как полноценные
> сущности+CRUD (в 04 значились ❌/через classifier).
>
> Легенда точности: где не удалось проверить по коду — помечено «(уточнить)».

---

## 0. Архитектура в двух словах

- **Фронт**: Next.js (App Router), общий код в `@epd/shared` (`packages/shared`). Один код
  консоли собирается в 3 профиля (`NEXT_PUBLIC_APP_PROFILE`): `waybill` (кабинет перевозчика),
  `oversight` (платформа надзора), `all` (монолит, дефолт). Плюс отдельное публичное
  приложение `apps/public` (порт 3002) только для проверки QR без авторизации.
- **Бэкенд**: 2 микросервиса Spring Boot (JDK 21):
  - `master-data-service` — субъекты/объекты и справочники (организации, водители, ТС,
    сотрудники, маршруты, коэффициенты, тарифы, классификаторы, политики, настройки, аудит).
  - `waybill-service` — путевые листы (жизненный цикл, расчёт, печать, оплата, QR, GPS,
    отчёты, справки-маълумотнома, инспекции, уведомления, агрегатор).
  - `crypto-service-mock` — заглушка криптосервиса.
- **Auth**: Keycloak (realm `epd-realm.json`), роли в JWT, `@PreAuthorize` на бэкенде +
  зеркальная проверка во фронте (`lib/roles.ts`, `shell.tsx`).
- **Мультиарендность**: `TenantScope`/`CurrentUser` — тенант видит свою организацию (админ
  компании — и филиалы), платформенные роли — все.

---

## 1. Роли и права

### 1.1. Список ролей (Keycloak realm `epd-realm.json`) — 12 realm-ролей

| Роль | Назначение | Тип |
|---|---|---|
| `SYSTEM_ADMIN` | Администратор системы (ГУП ЦЦТО) — надзор+настройка платформы | человек, платформа |
| `MINTRANS_ANALYST` | Аналитик Минтранса — надзор по всем организациям (только чтение, агрегаты по стране) | человек, платформа |
| `INSPECTOR` | Инспектор дорожного контроля — проверка ПЛ, нарушения, GPS, надзорная отчётность | человек, платформа |
| `COMPANY_ADMIN` | Администратор перевозчика — своя компания и все её филиалы | человек, тенант |
| `BRANCH_ADMIN` | Администратор филиала — только свой филиал | человек, тенант |
| `DISPATCHER` | Диспетчер (Танзимгар) — выписка ПЛ, парк, мониторинг | человек, тенант |
| `DOCTOR` | Медработник (Духтур) — предрейсовый/послерейсовый медосмотр (АРМ /med) | человек, тенант |
| `MECHANIC` | Механик — предрейсовый техконтроль (АРМ /tech) | человек, тенант |
| `DRIVER` | Водитель (Ронанда) — свои ПЛ + предъявление QR | человек, тенант |
| `ACCOUNTANT` | Бухгалтер (Муҳосиб) — подтверждение оплаты + отчёты | человек, тенант |
| `FUEL_STATION` | Пункт выдачи топлива — учёт фактической выдачи | человек, тенант |
| `API_INTEGRATOR` | Агрегатор/интегратор (ЧУРА/НЕРУ, GPS-трекеры) — scoped API, client-credentials | сервис |

Роли, встречающиеся в комментариях/спеке как «этап 2 / расширение», но **отсутствующие в
realm**: `SELF_EMPLOYED`, `MED_ORG_ADMIN`, `TECH_ORG_ADMIN`, `CALL_OPERATOR`,
`CONSIGNOR`/`CONSIGNEE`, `customs_officer`, `employee_kassa`, `gps` — сознательно не заведены
(см. §6 gap-анализа 04). (уточнить, нужны ли они в этапе 1)

### 1.2. Стартовые страницы (`roleHome`, `roles.ts`)

| Роль(-и) | Кабинет по входу |
|---|---|
| SYSTEM_ADMIN, COMPANY_ADMIN, BRANCH_ADMIN, MINTRANS_ANALYST | `/dashboard` |
| DOCTOR | `/med` |
| MECHANIC | `/tech` |
| DRIVER | `/driver` |
| DISPATCHER | `/dispatcher` |
| INSPECTOR | `/inspector` |
| FUEL_STATION | `/fuel` |
| ACCOUNTANT | `/reports/summary` |

### 1.3. Матрица доступа к разделам (`visibleNav`, `roles.ts`)

Разделы (`NavKey`): dashboard, waybills, dispatcher, med, tech, fuel, driver, inspector,
company, fleet, monitoring, registry, violations, reports, dictionaries, settings, access.

| Раздел \ Роль | SYS_ADMIN | COMP_ADMIN | BRANCH_ADMIN | DISPATCHER | DOCTOR | MECHANIC | DRIVER | FUEL | ACCOUNTANT | INSPECTOR | ANALYST |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| dashboard | ✓ | ✓ | ✓ | ✓ | | | | | | | ✓ |
| dispatcher | | | | ✓ | | | | | | | |
| waybills | ✓ | ✓ | ✓ | ✓ | | | ✓* | | ✓ | ✓ | |
| med | | | | | ✓ | | | | | | |
| tech | | | | | | ✓ | | | | | |
| fuel | | | | | | | | ✓ | | | |
| driver | | | | | | | ✓ | | | | |
| inspector | | | | | | | | | | ✓ | |
| fleet | | ✓ | ✓ | ✓ | | | | | | | |
| monitoring | ✓ | ✓ | | ✓ | | | | | | ✓ | ✓ |
| company | ✓ | | | | | | | | | | |
| access | | ✓ | ✓ | | | | | | | | |
| registry | ✓ | ✓ | | | | | | | | | ✓ |
| violations | ✓ | ✓ | ✓ | | | | | | | ✓ | ✓ |
| reports | ✓ | ✓ | ✓ | | | | | | ✓ | ✓ | ✓ |
| dictionaries | ✓ | ✓ | | | | | | | | | |
| settings | ✓ | | | | | | | | | | |

\* `driver` для DRIVER — свой кабинет; `waybills` открыт водителю только для его карточек ПЛ
(список фильтруется своими рейсами на бэке). ✓ у DRIVER-waybills = доступ к карточке.

Примечания к матрице (из комментариев кода):
- **Админы платформы/компании НЕ имеют АРМ врача/механика** — осмотры подписывают врач/механик
  в /med и /tech своими РМА.
- **INSPECTOR**: `waybills` включён специально (карточка ПЛ + блокировка/акт осмотра),
  экономика перевозчика ему закрыта (`canSeeCarrierEconomics`).
- **COMPANY_ADMIN**: раздел `/company` (реестр всех организаций) убран — остался только у
  SYSTEM_ADMIN; реквизиты/парк/персонал компании ведутся в `/fleet`.

### 1.4. Динамическое переопределение доступа

Матрица `visibleNav` в `roles.ts` — это **код-дефолт**. Поверх есть БД-слой:
таблица `role_access` (миграция master-data `V15__role_access.sql`), сущность `RoleAccess`,
`RoleAccessController`, экран `/settings/roles` (только SYSTEM_ADMIN) — редактируемая матрица
«роль → стартовый раздел + видимые разделы», применяется на лету (`useRoleAccess`).
Защита от само-локаута: у SYSTEM_ADMIN нельзя снять `settings`.

### 1.5. Права на действия (зеркалят `@PreAuthorize`)

| Функция (`roles.ts`) | Кому разрешено |
|---|---|
| `canCreateWaybill` — выписать ПЛ | DISPATCHER, SYSTEM_ADMIN |
| `canConfirmPayment` — подтвердить оплату | ACCOUNTANT, COMPANY_ADMIN, SYSTEM_ADMIN |
| `canSeeMintransReports` — сводные Минтранса | SYSTEM_ADMIN, MINTRANS_ANALYST |
| `canSeeCarrierEconomics` — экономика перевозчика | все, кроме «чистого» INSPECTOR |
| `canEditNationalDictionaries` — нац. справочники | SYSTEM_ADMIN |

---

## 2. Кабинеты по ролям (меню)

Меню строит `sidebar.tsx` по `visibleNav`; группы: «Рабочие места» и «Управление».

- **SYSTEM_ADMIN** — Главная, Путевые листы (реестр + создать), Компания (реестр всех орг),
  Мониторинг, Реестры, Нарушения, Отчёты, Справочники, Настройки.
- **COMPANY_ADMIN** — Главная, Путевые листы, Транспорт и водители (fleet), Доступы,
  Мониторинг, Нарушения, Отчёты, Реестры, Справочники.
- **BRANCH_ADMIN** — Главная, Путевые листы, Транспорт и водители, Доступы, Мониторинг,
  Нарушения, Отчёты (в пределах филиала).
- **DISPATCHER** — Кабинет диспетчера, Главная, Путевые листы (+ создать), Транспорт и
  водители, Мониторинг.
- **DOCTOR** — Кабинет врача (/med) + Журнал медосмотров (/med/journal). В меню «Транспорт»
  показывается как «Водители».
- **MECHANIC** — Кабинет механика (/tech) + Журнал техконтроля (/tech/journal). «Транспорт».
- **DRIVER** — Кабинет водителя (/driver) + Мои путевые листы (/driver/waybills).
- **FUEL_STATION** — Пункт выдачи топлива (/fuel).
- **ACCOUNTANT** — Отчёты, Путевые листы.
- **INSPECTOR** — Кабинет инспектора (/inspector), Путевые листы (карточка), Нарушения,
  Мониторинг, Отчёты (сводка + журналы контроля).
- **MINTRANS_ANALYST** — Главная, Отчёты, Реестры, Нарушения, Мониторинг.

---

## 3. Все страницы/маршруты фронта (`apps/web/app/**/page.tsx`) — 76 файлов

### Публичные / служебные
| Маршрут | Назначение |
|---|---|
| `/login` | Вход (Keycloak direct-grant; кнопка SSO — заглушка) |
| `/auth/callback` | OIDC-callback |
| `/verify/[jws]` | Публичная проверка ПЛ/справки по QR (JWS) |
| `/` | Корень — редирект в кабинет роли |

### Путевые листы
| Маршрут | Назначение |
|---|---|
| `/waybills` | Реестр ПЛ (фильтры, счётчики, CSV-экспорт) |
| `/waybills/new` | Мастер создания ПЛ (степпер, автосохранение в localStorage, «на основании») |
| `/waybills/[id]` | Карточка ПЛ (титулы, история, расчёт, оплата, накладная, инспекции, расходы) |
| `/waybills/[id]/print` | Печатная форма (обёртка серверного PDF-бланка) |
| `/waybills/journal` | Журнал ПЛ за период (печатная форма) |

### Рабочие места
| Маршрут | Назначение |
|---|---|
| `/dispatcher` | Кабинет диспетчера (оперативная сводка/очереди) |
| `/driver` | Кабинет водителя (текущий ПЛ, QR) |
| `/driver/waybills` | Мои путевые листы |
| `/med` | АРМ врача — очередь предрейсового медосмотра |
| `/med/journal` | Журнал медосмотров |
| `/tech` | АРМ механика — предрейсовый техконтроль (+ фотофиксация неисправностей) |
| `/tech/journal` | Журнал техконтроля |
| `/fuel` | Пункт выдачи топлива — учёт фактической выдачи |
| `/inspector` | Кабинет инспектора (проверка ПЛ по номеру/QR, блок НЕРУ) |

### Управление / надзор
| Маршрут | Назначение |
|---|---|
| `/dashboard` | Главная панель (KPI, тренд пассажирооборота) |
| `/company` | Реестр всех организаций (SYSTEM_ADMIN): фильтры + создание по ИНН/вручную |
| `/company/access` | Доступы сотрудников организации (OrgUser) |
| `/fleet`, `/fleet/vehicles`, `/fleet/drivers`, `/fleet/employees` | Парк и персонал перевозчика (CRUD, sync по ИНН) |
| `/registry`, `/registry/vehicles`, `/registry/drivers`, `/registry/employees` | Реестры субъектов/объектов (надзорный просмотр) |
| `/monitoring` | GPS-мониторинг (живые позиции ТС на линии) |
| `/violations` | Нарушения (акты дорожного контроля) |
| `/notifications` | Уведомления пользователя |

### Отчёты (9 страниц-обёрток над `ReportsView`)
`/reports` (→ summary), `/reports/summary`, `/reports/journal`, `/reports/by-driver`,
`/reports/by-vehicle`, `/reports/fuel`, `/reports/sections`, `/reports/regional`,
`/reports/malumotnoma`, `/reports/journals`. См. §7.

### Справочники (14 разделов + индекс)
`/dictionaries` (→ routes), routes, clients, cargos, fuel-norms, coefficients, tariffs,
brands, winter-coefs, mountain-coefs, city-coefs, used-coefs, drive-classes, directions,
route-tariffs. См. §4.

### Настройки (18 подстраниц + индекс)
`/settings` + general, branding, types, fields, numbering, statuses, roles, notifications,
integrations, security, print, classifiers, expiry, audit, backup, performance, interface,
policies. См. §5.

---

## 4. Справочники

### 4.1. Раздел `/dictionaries` — 14 CRUD-экранов (`dictionaries/layout.tsx`)

**Перевозчик (COMPANY_ADMIN/SYSTEM_ADMIN, org-scoped) — `DictionaryController`, `/api/v1/dictionaries`:**

| Экран | Сущность | Поля | Данные/сиды |
|---|---|---|---|
| Маршруты `/routes` | `Route` | number, name, transportType, regionId, + коэф. поля (winterCoefId, mountainCoefValue, inCityCoefValue, stationCoef, roadQuality, excludingCoef, additionalFuel100/…, distanceA/B, beginPathA/B, plannedLap, coeUseCapacity, averageLengthPassSeat — V28) | org-scoped, без сидов |
| Клиенты `/clients` | `Client` | number, name, address, phone | org-scoped |
| Грузы `/cargos` | `Cargo` | name, type, unit, price, cargoClass | ~135 сидов (V55, перенос legacy) — **(было в 04: ❌ нет сущности)** |

**Национальные (SYSTEM_ADMIN, единые для платформы):**

`DictionaryController` (`/api/v1/dictionaries`):
| Экран | Сущность | Поля | Сиды (V2) |
|---|---|---|---|
| Нормы расхода `/fuel-norms` | `FuelNorm` | transportType, brand(NULL=все), baseNorm (л/100км) | 5 (авто/микроавт/легк/груз/груз-межд) |
| Коэффициенты `/coefficients` | `Coefficient` | kind(WINTER/CITY/HIGHLAND/USAGE), name, value, regionId, monthFrom/To | 3 (зимний 1.10, город 1.05, ГБАО 1.15) |
| Тарифы `/tariffs` | `Tariff` | transportType, fuelType(NULL=все), pricePerKm (сомони/км) | 3 |

`LegacyReferenceController` (`/api/v1/legacy-ref`, расчётное ядро):
| Экран | Сущность | Поля | Сиды |
|---|---|---|---|
| Марки ТС `/brands` | `Brand` | name, number, model, typeId, capacity, carrying, costServices, fuel100, fuel100Dushanbe, fuelHour, fuelInteriorHeating, tariffRate | ~488 (V58, перенос) + demo (V30) |
| Зимний коэф. `/winter-coefs` | `FuelWinterCoef` | name, periodFrom, periodTo, coef | (уточнить) |
| Горный коэф. `/mountain-coefs` | `MountainCoef` | name, coef | |
| Городской коэф. `/city-coefs` | `CityCoef` | name, coef | |
| Износ `/used-coefs` | `UsedCoef` | year, km, coef (порог возраст/пробег) | |
| Классы водителей `/drive-classes` | `DriveClass` | driveClass, coef | |
| Направления `/directions` | `Direction` | title, number, winterCoefId, mountainCoefId, inCityCoefId, checked | ~95 (V56) — **(было в 04: как classifier)** |
| Тарифы маршрута `/route-tariffs` | `RouteTariff` | routeId, fuelId, number, typeAuto, pricePer1Mkm, priceOneTime | |

### 4.2. Классификаторы `/settings/classifiers` — `Classifier` (общий key-value по `category`)

`ClassifierController`. Засеянные категории (миграции V7, V24):
| Категория | Кол-во | Примеры |
|---|---|---|
| COUNTRY | 14 | TJ, UZ, KZ, KG, AF, CN, RU, TR, IR, TM, PK, AZ, BY, GE |
| ADR_CLASS | 9 | классы опасных грузов 1–9 |
| PERMIT_TYPE | 4 | BILATERAL, TRANSIT, THIRD_COUNTRY, EKMT |
| WORK_TYPE | 8 | виды работ спецтехники (форма 09) |
| WAYBILL_TYPE | (уточнить) | имена типов ПЛ (V22) |

### 4.3. Справочник городов — `City` (V54)

69 записей (перенос из боевого /city), region_id 1..7, code, name. Используется как подсказки
для поля «Город» организации. `CityController`. **(было в 04: ❌ свободный текст)**

### 4.4. Что осталось «кодом/JSON», а не отдельным справочником

- `regions` (1..7), `type-company`, `transport-type` (1–6), `fuels` (1–5, захардкожено в
  `WaybillPrintService.FUEL_NAMES`), `trailers` (поля в `Waybill.typeData`) — сознательно.
- `external-cities` (~19 тыс. в боевой БД) — не заведён; свободный текст в международных ПЛ.
- `ownerships`, `route-types`, `brand-types`, `bill-types`, `number-driver`, `phones` — нет
  (по gap-анализу 04 — не нужны/статичные enum).

### 4.5. Реестр сущностей master-data (`domain/*.java`, 28 классов)

AuditLog, Brand, BrandingAsset, Cargo, City, CityCoef, Classifier, Client, Coefficient,
Direction, DriveClass, Driver, Employee, FieldDefinition, FuelNorm, FuelWinterCoef,
MountainCoef, Organization, OrganizationDocument, PlatformSetting, Policy, RoleAccess, Route,
RouteTariff, SubjectDocument, Tariff, UsedCoef, Vehicle.

---

## 5. Настройки (`/settings/*`)

Индекс `/settings` — плитки со статусом (done/partial/info). admin-плитки видны только
SYSTEM_ADMIN. Значения хранятся в `PlatformSetting` (по `category`/`setting_key`), правит
общий `SettingsEditor`.

| Экран | Что настраивает | Статус |
|---|---|---|
| `/settings/general` | Контакты поддержки (phone/email/website/hours), режим обслуживания (V10, V19) | done |
| `/settings/branding` | Брендинг платформы: тексты, логотипы (`BrandingAsset`, V14) | done, admin |
| `/settings/types` | Типы путевых листов (справочно/включение) | done |
| `/settings/fields` | Конструктор доп. полей (`FieldDefinition`, V8) | done |
| `/settings/roles` | Матрица «роль → разделы/стартовая» (`RoleAccess`, V15) | done, admin |
| `/settings/notifications` | Флаги событий: notify_med_rejected/tech_rejected/ready/blocked/expired (V17) | done |
| `/settings/security` | idle_logout_minutes и др. (V16) | done |
| `/settings/print` | show_qr, show_stamp, paper_size (A4/A5), маълумотнома-приказ (V10, V40) | partial |
| `/settings/classifiers` | Классификаторы (см. §4.2) | done |
| `/settings/expiry` | Пороги «скоро истекает» для документов (`DocumentExpiry`) | done |
| `/settings/audit` | Журнал аудита (просмотр, `AuditLog` + hash-chain) | done, admin |
| `/settings/policies` | Движок бизнес-правил (`Policy`, см. §5.1) | done |
| `/settings/numbering` | Схема нумерации ПЛ (справочно) | info |
| `/settings/statuses` | Статусы и жизненный цикл (справочно) | info |
| `/settings/integrations` | Внешние интеграции (справочно/заглушки) | info |
| `/settings/backup` | Резервное копирование (справочно) | info |
| `/settings/performance` | Производительность и лимиты (справочно) | info |
| `/settings/interface` | Язык по умолчанию и пр. (V10) | partial |

### 5.1. Бизнес-правила (движок `Policy`, 3 уровня: NATIONAL < ORGANIZATION < VEHICLE_TYPE)

| rule_key | Дефолт | Где задан |
|---|---|---|
| require_med_pre | true | V5 |
| require_tech_check | true | V5 |
| require_med_post | false (true для пассажирских типов) | V5 |
| require_gps | false | V5 |
| min_rest_hours | (V12) — мин. отдых водителя между рейсами | V12 |
| min_driver_experience_years | 0 | V48 |
| block_minor_driver | false | V48 |

---

## 6. Списки и фильтры

| Экран | Поиск | Фильтры | Доп. |
|---|---|---|---|
| `/waybills` | по номеру/госномеру/водителю/компании | Компания, Статус (15), Тип ПЛ (10), Дата с/по | 4 счётчика-KPI, пагинация (10/стр), CSV-экспорт, «Сброс» |
| `/company` (реестр орг) | по названию/ИНН | Вид субъекта, Тип компании, Регион, Город | счётчик активных |
| `/fleet/{vehicles,drivers,employees}` | по строке (госномер/ФИО/ИНН) | вкладки-типы; org-выбор для платформы | CRUD, sync по ИНН |
| `/registry/{vehicles,drivers,employees}` | по строке (`reg.search`) | Тип/должность, Регион, Город, Организация | надзорный просмотр (`RegistryView`, вкладки-виды) |
| `/monitoring` | по строке | Организация, Тип ПЛ | живые GPS-позиции |
| `/violations` | по строке | (базовый) | акты контроля |
| `/reports/*` | — | Дата с/по, Организация (для платформы), тип разреза | XLSX-экспорт |

Замечание: фильтрация реестра ПЛ и большинства списков — **клиентская** (по загруженному
набору `useMemo`), серверная пагинация не везде (риск на больших объёмах — см. §23 QA-статуса).

---

## 7. Отчёты (`/reports/*`, `ReportController` `/api/v1/reports`)

### 7.1. Вкладки фронта (`reports/layout.tsx`), состав зависит от роли

| Вкладка | Назначение | Доступ |
|---|---|---|
| Сводка `/summary` | Сводный отчёт за период | все отчётные + INSPECTOR |
| Журнал диспетчера `/journal` | `dispatcher-journal` за дату | экономика |
| По водителям `/by-driver` | Разрез по водителям | экономика |
| По транспорту `/by-vehicle` | Разрез по ТС | экономика |
| Топливо `/fuel` | Топливный разрез | экономика |
| Разрезы Роҳхат `/sections` | Типовые разрезы движка (`ReportType`) | экономика |
| Сводный (Минтранс) `/regional` | Региональный план/факт, «кол-во ПЛ», норматив | SYSTEM_ADMIN/ANALYST |
| Справки `/malumotnoma` | Маълумотнома (справки) | экономика |
| Журналы контроля `/journals` | Журналы медосмотра/техконтроля | все + INSPECTOR |

### 7.2. Эндпоинты `ReportController` (19 методов)

`/summary`, `/dispatcher-journal`, `/by-driver`, `/by-vehicle`, `/fuel`,
`/passenger` (+`.xlsx`), `/cargo` (+`.xlsx`), `/regional` (+`.xlsx`), `/regional-count`
(+`.xlsx`), `/waybill-norm` (+`.xlsx`), `/passenger-volume-trend`,
`/journal/mechanic` (+`.xlsx`), `/journal/doctor` (+`.xlsx`), `/types`.
XLSX-экспорт (`ReportXlsxWriter`) — **сверх эталона** (в боевом только HTML-печать).

### 7.3. Типы разрезов (`ReportType`, 11) для `/passenger` и `/cargo`

BY_VEHICLE (Автомобил), BY_ROUTE (Хатсайр), BY_BRAND (Тамға), BY_DRIVER (Табел),
COMPANY_SUMMARY (Авто), DRIVER_SALARY (зарплата), TRIP_INFO (гашт), REGISTRY_JOURNAL
(Дафтари қайди в/н), FUEL_GENERAL, FUEL_BY_VEHICLE, FUEL_BY_DRIVER. Покрывают 14 legacy
`report_type` (см. §4 gap-анализа 04).

---

## 8. Жизненный цикл ПЛ

### 8.1. Статусы (`WaybillStatus`, 15)

DRAFT → CREATED (Т1 подписан) → [MED_REJECTED / TECH_REJECTED] → AWAITING_PAYMENT → PAID →
READY (номер + QR) → ISSUED (водитель получил) → ACTIVE (Т4, на линии) → RETURNED (Т5,
одометр возврата) → COMPLETED (Т6/закрыт). Плюс: CANCELLED, EXPIRED, BLOCKED, ARCHIVED.

- **OPEN_STATUSES** (блокируют новый ПЛ на то же ТС/водителя): CREATED, AWAITING_PAYMENT,
  PAID, READY, ISSUED, ACTIVE.
- **Терминальные**: COMPLETED, CANCELLED, EXPIRED, ARCHIVED.
- Отдельного «В рейсе» нет — покрыт ACTIVE.
- Медосмотр/техконтроль — параллельные флаги `medPassed`/`techPassed`, статус агрегирует.

### 8.2. Переходы и роли (`WaybillController`)

| Действие | Эндпоинт | Роль |
|---|---|---|
| Создать | `POST /waybills` | DISPATCHER, SYSTEM_ADMIN |
| Т1 (подпись диспетчера) | `POST /{id}/titles/t1` | DISPATCHER, SYSTEM_ADMIN |
| Т2 медосмотр | `POST /{id}/confirm-med` | DOCTOR, SYSTEM_ADMIN |
| Т3 техконтроль | `POST /{id}/confirm-tech` | MECHANIC, SYSTEM_ADMIN |
| Подтвердить оплату | `POST /{id}/confirm-payment` | ACCOUNTANT, COMPANY_ADMIN, SYSTEM_ADMIN |
| Возврат оплаты | `POST /{id}/refund` | ACCOUNTANT, COMPANY_ADMIN, SYSTEM_ADMIN |
| Выдать | `POST /{id}/issue` | DISPATCHER, SYSTEM_ADMIN |
| Активировать (Т4) | `POST /{id}/activate` | DISPATCHER, SYSTEM_ADMIN |
| Возврат (Т5) | `POST /{id}/return` | DISPATCHER, SYSTEM_ADMIN |
| Закрыть (Т6) | `POST /{id}/close` | DISPATCHER, SYSTEM_ADMIN |
| Аннулировать | `POST /{id}/cancel` (причина обязательна) | DISPATCHER, SYSTEM_ADMIN |
| Замена водителя/ТС | `POST /{id}/replace-driver` / `replace-vehicle` | DISPATCHER, SYSTEM_ADMIN |
| Блокировка (инспектор) | `POST /{id}/block` (основание из классификатора) | INSPECTOR, SYSTEM_ADMIN |
| Проверка без нарушений | `POST /{id}/inspection` | INSPECTOR, SYSTEM_ADMIN |
| Разблокировка | `POST /{id}/unblock` | SYSTEM_ADMIN |

Просрочка → EXPIRED автоматически (`LifecycleScheduler`). Preflight/available-types —
проверка пригодности до создания.

---

## 9. Ключевые фичи — что реализовано

| Фича | Статус | Где |
|---|---|---|
| **Расчёт топлива** | ✅ норма × коэф. WINTER/CITY/HIGHLAND, остатки, факт/перерасход | `FuelCalculationService`, пакет `calc/` (`FuelNormCalculator`, `CoefficientCalculator`, `WaybillCalcEngine`) |
| Посуточный расчёт (многодневные) | ⚠️ `SequentialFuel`/`MultiDayPassengerCalc` есть, но **не подключены** к живому пути (агрегат на весь ПЛ) | см. §2.2 gap-анализа 04 |
| **Тарифы/стоимость** | ✅ `Tariff`/`RouteTariff`, `TariffMath`, зарплата водителя | `calc/`, `/calculation` |
| **Оплата** | ✅ webhook шлюза (`X-Payment-Secret`, идемпотентность), ручное подтверждение, возврат | `PaymentWebhookController`, `confirm-payment`/`refund` |
| **ЭЦП** | ⛔ **заглушка** SHA-256 (`StubTitleSigner`); прод `CadesTitleSigner` (CAdES/УЦ РТ) есть, но не активен — единственный оставшийся P0 | `signing/` |
| **QR** | ✅ JWS ES256 (`QrTokenService`), офлайн-проверка по JWKS, `/verify/[jws]`; QR и для маълумотнома | `QrTokenService`, `VerifyController` |
| **GPS-мониторинг** | ✅ приём пингов (API_INTEGRATOR), `/live` (позиции на линии), `/track` | `GpsController`, `GpsPing` |
| **Аудит** | ✅ кто/когда/старое→новое, IP/UA, hash-chain, неизменяемость | `AuditLog` (V6, V13, V44/V45), `AuditController` |
| **Инспекции** | ✅ акт дорожного контроля, основания (`InspectionReason`), блок/разблок | `WaybillInspection`, `/violations` |
| **Уведомления** | ✅ адресация по ролям (`target_roles`) | `Notification`, `NotificationService` |
| **Расходы рейса** | ✅ типы/сумма/НДС/чек, подтверждение бухгалтером | `Expense`, `ExpenseController` |
| **Печатные бланки** | ✅ 6 форм ПЛ + CMR + приложение к 2-Б + маълумотнома (Thymeleaf→PDF), landscape, «Копия» | `templates/print/`, `PrintController` |
| **Агрегатор (ЧУРА/НЕРУ)** | ✅ абонемент, scoped API такси | `AggregatorController`, `NeruController` |
| **Справки (маълумотнома)** | ✅ + QR, настраиваемая ссылка на приказ-нархнома | `MalumotnomaController` |
| **Планы (план/факт)** | ✅ `WaybillPlan` | `WaybillPlanController` |
| **Мобильный канал** | ✅ `MobileController` (свои ПЛ водителя) | |

---

## 10. Итоговые счётчики

- **Роли**: 12 realm-ролей (11 «человеческих» + API_INTEGRATOR); +динамическая матрица `role_access`.
- **Страницы фронта**: 76 файлов `page.tsx` (вкл. индексы-редиректы и динамические маршруты).
- **Справочники**: 14 CRUD в `/dictionaries` + классификаторы (5 категорий) + города (69).
- **Настройки**: 18 подстраниц (+индекс); движок политик — 7 rule_key.
- **Отчёты**: 9 вкладок фронта / 19 эндпоинтов / 11 типов разрезов.
- **Типы ПЛ**: 10. **Статусы ПЛ**: 15.
- **Контроллеры**: master-data ~19, waybill-service ~19. **Сущности**: master-data 28, waybill 20.

---

## 11. Оценка: чего из типичной гос-платформы у нас нет или сделано слабо

**Блокеры (внешне заблокированы):**
1. **Квалифицированная ЭЦП** — только SHA-256-заглушка; боевой УЦ РТ не подключён (P0).
2. **Реальные интеграции** — налоговая, реестры ТС/водителей, SMS-шлюз — заглушки
   (`UnifiedPlatformClient` stub); банк/оплата — webhook есть, боевого шлюза нет.

**Функционально слабо/отсутствует:**
3. **Маршрут — свободный текст**, нет структурных точек, геозон, запрещённых участков,
   платных дорог, пунктов пропуска; плановый пробег не авторассчитывается (§10 QA).
4. **Оргструктура** не смоделирована: банк-реквизиты как объекты, автоколонны, медпункты,
   пункты ТО, подразделения — нет (§4 QA); филиалы есть на уровне поля/скоупа.
5. **Посуточный расчёт топлива** многодневных пассажирских ПЛ не подключён (движок есть,
   но живой путь считает агрегатом) — риск для точности 1-А/3-С.
6. **Международный справочник городов** (external-cities, ~19 тыс.) — свободный текст.
7. **Фотофиксация**: MinIO поднят; фото неисправностей при Т3 добавлено, но полноценного
   документо-хранилища/галереи нет.
8. **Списки — клиентская фильтрация/пагинация** на большинстве экранов; серверная не везде —
   риск на боевых объёмах (нагрузочное тестирование не проводилось, §23 QA).
9. **Локализация EN** частичная (RU/TJ полные, EN — только вход).
10. **Восстановление пароля/2FA**: 2FA включена для admin/analyst/inspector; «Забыли пароль»
    для перевозчиков — через админа орг (нет самообслуживания).

**Прод-готовность (не код):** нет нагрузочного/отказоустойчивости, бэкапа+восстановления,
боевого мониторинга, плана отката, UAT; секреты в репозитории дефолтные (см. §22 QA).

**Сильные стороны сверх эталона:** XLSX-экспорт отчётов, отметка «Копия»+landscape печать,
hash-chain аудит с IP/UA, движок политик (конфигурируемые бизнес-правила вместо хардкода),
атомарная нумерация (без гонки оригинала), исправленные баги расчёта оригинала (износ,
зимний период, кондиционер), разделение на профили waybill/oversight/public.
