# Боевая платформа MinTransRT (rohkhat.tj, Laravel) — полная инвентаризация («атлас»)

> Источник анализа: код `D:\Projects\rohkhat.tj ralavel` (Laravel-приложение на базе **Backpack for Laravel** + **spatie/laravel-permission**).
> Read-only анализ кода (код полнее и надёжнее кликов по сайту). Боевой экземпляр — http://localhost:8000.
> Дата составления: 2026-09-12. Пометка **(уточнить)** — там, где по коду неоднозначно.

---

## 0. Технологический обзор

| Что | Значение |
|---|---|
| Framework | Laravel (legacy, `app/User.php` в корне `App\` namespace — стиль L7/L8) |
| Admin-панель | **Backpack for Laravel** (CRUD, PermissionManager, PageManager, MenuCRUD, Settings, Backup, Elfinder) |
| Роли/права | **spatie/laravel-permission** (`HasRoles`), обёрнута Backpack PermissionManager |
| Auth API (мобайл/компании) | **tymon/jwt-auth** (`JWTSubject` на `User`) |
| QR | `simplesoftwareio/simple-qrcode` (view `qr.show`) |
| БД | MySQL (дамп `dump-07.08.26.sql` в корне) |
| Прочее | Docker (`docker-compose.yml`), `gulpfile.js`, Clockwork/Debugbar |

**Важно:** в проекте много демо-остатков Backpack, НЕ относящихся к домену: `Monster`, `FluentMonster`, `Product`, `Icon`, `Dummy`, `Article/Category/Tag`, `Page`, `MenuItem`. Их CRUD/миграции присутствуют, но в боевом меню и роутинге домена не используются (кроме служебных). При сверке их игнорировать.

**Дубликаты-«копии» в репозитории** (рабочими не являются, мусор): `... copy.php`, `ApiDataController0.php`, `FileUploadController0.php`, `BusBaseCalc copy.php`, `Notification0.php`, `routes/backpack/custom copy.php`, `routes/api1.php` (старый вариант api.php) и т.п.

---

## 1. Роли и права

### 1.1. Механизм
- **spatie/laravel-permission** через трейт `HasRoles` в `App\User`.
- Проверки в коде: `@can('...')`, `@hasrole/@hasanyrole` в blade; `backpack_user()->hasRole(...)`, `->can(...)` в контроллерах; middleware `role:...` на некоторых контроллерах.
- Права назначаются в основном **напрямую пользователю** (`model_has_permissions`) и через роль (`model_has_roles`); таблица `role_has_permissions` в дампе очень скудная (роли `mechanic/doctor/customs_officer/gps` получают `list`, `customs_officer` — `cmr`, `superadmin` — `delete`). Основная бизнес-логика доступа — по ролям + прямым правам пользователя + **сессионный скоуп** (см. §1.4).
- Дефолтные сидеры (`PermissionManagerTablesSeeder`, `DatabaseSeeder`) — это **демо Backpack**, к боевым ролям отношения не имеют. Реальные роли/права — в БД (ниже из дампа).

### 1.2. Роли (таблица `roles`, из дампа) — 12 ролей
| id | name | Описание (тадж.) |
|---|---|---|
| 1 | `superadmin` | суперадмин |
| 2 | `admin` | админ |
| 4 | `company` | Корхона (предприятие-перевозчик) |
| 5 | `region` | Минтақа (региональное управление) |
| 6 | `client_forwarder` | Мизоҷи интиқолдиҳанда (экспедитор) |
| 7 | `client_sender` | Мизоҷи борфиристонанда (грузоотправитель) |
| 8 | `mechanic` | Механик |
| 9 | `doctor` | Духтур (мед. работник) |
| 10 | `fuel_employee` | Корманди нуқтаи сӯзишворӣ (сотрудник топл. пункта) |
| 11 | `employee_kassa` | Корманди хазина (кассир) |
| 12 | `customs_officer` | Корманди гумрук (таможня) |
| 13 | `gps` | gps (доступ к GPS-событиям) |

> id 3 (`member`) из демо-сидера в боевом дампе отсутствует.

### 1.3. Права (таблица `permissions`, из дампа) — 18 прав
| id | name | Назначение |
|---|---|---|
| 10 | `list` | Просмотр |
| 11 | `create` | Создать |
| 12 | `update` | Обновить |
| 13 | `delete` | Удалить |
| 14 | `bus` | Роҳхати 1-АД Автобус |
| 15 | `ebus` | Роҳхати 1-АД Троллейбус |
| 16 | `mbus` | Роҳхати 1-А Микроавтобус |
| 17 | `taxi` | Роҳхати 3-С Сабукрав |
| 18 | `cargo2b` | Роҳхати Шакли 2-Б |
| 19 | `cargo5bbm` | Роҳхати Шакли 5Б-БМ |
| 20 | `cargowaybill1_attachment` | Борхати замимаи 1 |
| 21 | `cargowaybill2_attachment` | Борхати замимаи 2 |
| 22 | `cmr` | СМР (международная) |
| 23 | `malumotnoma` | Маълумотнома (справка) |
| 24 | `ewaybill` | е-роҳхат (электронный путевой лист) |
| 25 | `debt` | Қарз (долг/задолженность) |
| 26 | `sectoral_company` | Корхонаи нақлиёти соҳавӣ (ведомственный транспорт) |
| 27 | `public_company` | Корхонаи нақлиёти истифодаи умум (общего пользования) |

`list/create/update/delete` — общие Backpack-права (используются в `RoleTrait::denyAccess()` для показа/скрытия кнопок). Права `bus/ebus/mbus/taxi/cargo2b/...` гейтят пункты меню «Роҳхатҳо» и доступ к соответствующим формам.

### 1.4. Привязка пользователя к скоупу (пивот-таблицы `App\User`)
| relation | таблица | смысл |
|---|---|---|
| `companies()` | `company_has_user` | предприятия пользователя (роль `company`) |
| `regions()` | `region_has_user` | регионы пользователя (роль `region`) |
| `clients()` | `user_clients` | клиенты (роли `client_sender/forwarder`) |
| `employees()` | `employee_has_user` | сотрудник (диспетчер) |
| `bills()` | `bill_has_user` | типы путевых листов пользователя |

Статические хелперы: `User::company_to_user()`, `region_to_user()`, `bill_to_user()`, `client_to_user()`, `employee_to_user()`.

**Скоуп кладётся в сессию при логине** — `vendor/backpack/crud/src/app/Library/Auth/AuthenticatesUsers.php::authenticated()` (вендор пропатчен!):
- роль `company` → `session('has_companies')` (+ `dispatcher_id`, `waybill3c30` если Душанбе, `license_has_expired`, `debt`, `today_fifteenth`);
- роль `region` → `session('has_regions')` (+ `sectoral_company`/`public_company` если есть право);
- роль `client_sender` → `session('has_client_senders')`; `client_forwarder` → `session('has_client_forwarders')`;
- пользователи id **137, 480** → `session('waybill3c30')` (спец-суперадмины).

**Применение скоупа** — `app/Traits/RoleTrait.php`:
- `hasRole()` — на списках добавляет `whereIn('region_id', has_regions)` (для Company) или `whereHas('company.city.region', allow(has_regions))`; для роли `company` — `whereHas('company', allow(has_companies))`; на `show/edit/delete` — `abort(403)` при выходе за скоуп. Дополнительно фильтр по `type_company_id` (1=истифодаи умум/public, 2=соҳавӣ/sectoral) если стоит соответствующий сессионный флаг.
- `cargoWaybillHasRole()` — скоуп для борхатов по `client_sender/forwarder`.
- `denyAccess()` — скрывает list/create/update/delete по правам; всегда прячет delete; включает `ewaybill`, если у юзера нет права `ewaybill` (инверсная логика).

### 1.5. Хардкод по конкретным user id (техдолг, важно!)
В `sidebar_content.blade.php` и контроллерах доступ/пункты меню зашиты на конкретные id:
- **137, 480** — полный «суперадминский» набор (Массивы: корхона, минтака, шаҳру ноҳия, тамға, самт, самт для маълумотнома, давлатҳо/шаҳрҳои хориҷа, нақша, СУЗИШВОРИ (fuel + коэффициенты), хатсайр, мизоҷ, бор, нархнома, намуди хатсайр, дараҷаҳо), GPS, блок «Аутентификатсия» (user/role/permission), `waybill3c30`.
- **330** (habib oil) — доп. пункты `client`, `cargo`.
- **177** — `routemalumotnoma`, доступ к телефонам.
- **1, 137, 480, 205, 490, 177, 1056** — пункт `phone` (Телефонҳо).
- **388, 650, 1156** (муовин/замы) — доступ к отчётам «Мусофирбарӣ/Боркашонӣ».
- **1, 137, 480, 490, 177, 210** — редактирование `status_lock` (блокировка) в Company; `1,137,480,490,177` — редактирование muhr/подписи полей.
- Список городов Душанбе — хелпер `getCompaniesDushanbe()` (в `app/helpers.php`) включает `waybill3c30`.

> Это ключевой «антипаттерн» легаси: права размазаны между spatie, сессией и хардкод-списками id. В e-Waybill это нужно свести в нормальную матрицу ролей.

---

## 2. Кабинеты по ролям (что видит каждая роль)

Стартовая страница у всех — **Dashboard** (`/admin/dashboard`, стандартный Backpack; есть кастомный `dashboard/ebus`). Меню (`sidebar_content.blade.php`) строится по правам/ролям:

**Верхнеуровневые разделы меню:** Dashboard → **Роҳхатҳо** → **Массивҳо** (+ вложенный **Сузишвори**) → **Ҳисобот** → **GPS** → **Аутентификатсия**.

### Роль `company` (предприятие)
- **Роҳхатҳо**: пункты по правам (`ebus`→Тролейбус, `bus`→Автобус [или `waybill1adfe` если роль `fuel_employee`], `mbus`→Микроавтобус, `taxi`→Сабукрав [+`waybill3c30` если Душанбе], `cargo2b`→2-Б, `cargowaybill1/2_attachment`→Борхати замимаи 1/2, `cargo5bbm`→5Б-БМ, `cmr`→СМР, `malumotnoma`→Маълумотнома).
- **Массивҳо**: Автомобилҳо (parking), Ронандаҳо (driver), Кормандон (employee); (для id 330 доп. client, cargo).
- **Ҳисобот**: Мусофирбарӣ (report), Боркашонӣ (reportwaybillcargo); Маълумотнома если есть право.
- Списки автоматически скоупятся по `has_companies`. При `status_lock=1` предприятия — блок-уведомление и редирект (middleware `Notification`).

### Роль `region` (региональное управление)
- **Роҳхатҳо**: как у company (по правам).
- **Массивҳо**: parking, driver, employee + **Корхонаҳо** (company), Хатсайрҳо (route), Тамғаҳо (brand), Мизоҷ (client), Самтҳо (directions), Бор (cargo), Шаҳрҳои хориҷа (external_cities).
- **Ҳисобот**: Мусофирбарӣ, Боркашонӣ, **Умумӣ** (reportwaybillgeneral).
- Скоуп по `has_regions`; доп. фильтр public/sectoral company по правам.

### Роль `superadmin` / `admin`
- Всё видит; admin в меню Массивҳо видит хотя бы «Корхонаҳо». `superadmin` — доступ к отчёту «Умумӣ», всем фильтрам, PermissionManager (через id 137/480).
- В отчётах superadmin/admin видят все регионы.

### Роль `gps`
- Только раздел **GPS** → gpsevent (список GPS-событий).

### Роли `mechanic` / `doctor` / `fuel_employee` / `employee_kassa` / `customs_officer`
- Это в основном **справочные сущности `Employee`** (type 1..5) + учётки, участвующие в подтверждении путевых листов. `customs_officer` имеет право `cmr`. `fuel_employee` при роли company переключает пункт «Автобус» на `waybill1adfe` (топливная форма). (Точный набор экранов для этих ролей в меню минимален — **уточнить** по конкретным пользователям.)

### Роли `client_sender` / `client_forwarder`
- Доступ к борхатам (cargo waybill attachments), скоуп по `has_client_senders/forwarders` (`RoleTrait::cargoWaybillHasRole`).

---

## 3. Все маршруты / страницы

### 3.1. `routes/web.php`
| Метод | URI | Контроллер@метод | Назначение |
|---|---|---|---|
| GET | `/` | `Backpack AdminController@redirect` | редирект на админку |
| GET | `qrcode/{type}/{id}` | `QrCodeController@show` | публичная страница проверки путевого/борхата/справки по QR (type 1..8) |
| GET | `qrcode_last/{id}` | `Mobile\QrCodeController@show` | QR последнего путевого (мобайл) |
| GET | `get_waybill/{driver_id}` | `Mobile\WaybillController@get` | получить путевой водителя |

### 3.2. Admin CRUD (`routes/backpack/custom.php`, префикс `admin`, middleware `web, admin, password_lock, notification`)
42 `Route::crud(...)`. Каждый разворачивается в стандартный набор Backpack (list/create/store/show/edit/update/delete/bulk-delete + fetch/inline). Сгруппировано по смыслу:

**Роҳхатҳо (путевые листы):**
| URI | Controller | Форма/назначение |
|---|---|---|
| `waybill1ad` | Waybill1adCrudController | 1-АД: Автобус + Троллейбус (enum type bus/ebus) |
| `waybill1adfe` | Waybill1adFuelEmplCrudController | 1-АД для сотрудника топл. пункта |
| `waybill1adeBus` | Waybill1adeBusCrudController | 1-АД Троллейбус (отдельный) |
| `waybill1a` | Waybill1aCrudController | 1-А: Микроавтобус |
| `waybill3c` | Waybill3cCrudController | 3-С: Сабукрав (такси/маршрут/почасовой) |
| `waybill3c30` | Waybill3c30CrudController | 3-С «30» (вариант Душанбе) |
| `waybill2b` | Waybill2bCrudController | 2-Б: грузовой |
| `waybill4mbm` | Waybill4mbmCrudController | 4-МБМ (грузовой, **уточнить** назначение) |
| `waybill5bbm` | Waybill5bbmCrudController | 5Б-БМ: грузовой |
| `cmr` | Cargo5bbmCrudController | СМР (международная) |
| `cargowaybill1attachment` | CargoWaybillAttachment1CrudController | Борхати замимаи 1 |
| `cargowaybill2attachment` | CargoWaybillAttachment2CrudController | Борхати замимаи 2 |
| `malumotnoma` | MalumotnomaCrudController | Маълумотнома (справка о перевозке) |
| `waybill` | WaybillCrudController | реестр бланков путевых (учёт выданных бланков) |

**Массивҳо (справочники):** `company`, `region`, `city`, `parking` (автомобили), `car`, `brand`, `driver`, `route`, `directions`, `client`, `phone`, `employee`, `cargo`, `tariff`, `routetype`, `billtypes`, `numberdriver`, `external_countries`, `external_cities`, `routemalumotnoma`, `driverclass`, `waybill_plan`.

**Сузишвори (топливо/коэффициенты):** `fuel`, `wintercoef`, `citycoef`, `mountaincoef`, `usedcoef`.

**GPS:** `gpsevent` (GpsdataCrudController; middleware `role:superadmin|gps`).

**Custom admin routes (не-CRUD):**
| Метод | URI | Контроллер | Назначение |
|---|---|---|---|
| GET | `charts/users` | Charts\LatestUsersChartController | виджет |
| GET | `charts/new-entries` | Charts\NewEntriesChartController | виджет |
| GET | `charts/ebus-bill-counts` | Charts\Ebus\BillCountsController | виджет троллейбусов |
| GET | `charts/ebus-pass-vol` | Charts\Ebus\PassengerVolumeController | виджет пассажиропотока |
| GET | `dashboard/ebus` | DashboardController@ebus | дашборд троллейбусов |
| GET/POST | `report` | Report1CrudController @index/@adt | отчёт Мусофирбарӣ |
| GET | `report_details` | ReportDetailsCrudController@adt | детализация отчёта |
| GET/POST | `reportwaybillcargo` | ReportWaybillCargoController @index/@adt | отчёт Боркашонӣ |
| GET/POST | `reportwaybillgeneral` | ReportWaybillCargoGeneralController @index/@adt | отчёт Умумӣ |
| GET/POST | `reportmalumotnoma` | ReportMalumotnomaController @index/@adt | отчёт Маълумотнома |
| GET | `waybill/{waybill_type}/{id}` | WaybillController@getWaybill | открыть путевой для обработки |
| — | (операции на путевых) | Operations\WaybillOperationTrait | `{id}/print`, `{id}/inspection`, `{id}/pay` |

Отчётные роуты обёрнуты в middleware `lock_report_notification`.

### 3.3. Filter-API для админки (`routes/backpack/custom.php`, префикс `api`, middleware `web, admin`)
| Метод | URI | Метод контроллера |
|---|---|---|
| GET | `filter/region` | FilterController@region |
| GET | `filter/city` | FilterController@city |
| GET | `filter/company` | FilterController@company |
| GET | `filter/client` | FilterController@client |
| GET | `parking_indication_counter/{id}` | @parkingIndicationCounter |
| GET | `parking_fuel_left/{parking}/{fuel}/{table}/{id}` | @parkingFuelLeft |
| GET | `parking_fuel_give/{parking}/{fuel}/{table}/{id?}` | @parkingFuelGive |
| GET | `parking_fuel_give_multi_days/{parking}/{fuel}/{table}/{id?}` | @parkingFuelGiveMultiDays |
| POST | `reportwaybillgeneral` | GeneralReport\ApiReportWaybillGeneral@adt |
| POST | `filter/regions` | FilterController@regions |

### 3.4. PermissionManager (`routes/backpack/permissionmanager.php`)
`Route::crud('permission'/'role'/'user')` — управление правами/ролями/пользователями (в меню доступно только id 137/480). В `production` delete/bulk-delete отключены.

### 3.5. Публичное API — `routes/api.php` (боевой)
**CMR:** `POST /api/cmr` → ApiCmrController@cmr.

**Мобильное приложение водителя** (middleware `throttle:60,1`, `set_driver_conf`):
| Метод | URI | Метод |
|---|---|---|
| POST | `auth/login` | AuthJwtController@login |
| POST | `auth/logout` | @logout |
| POST | `auth/refresh` | @refresh |
| POST | `auth/getuser` | @get_user |
| POST | `get_profile` | ApiController@profile |
| POST | `get_license_attach` | ApiController@license_attach |
| POST | `get_seals/{company_id}` | ApiController@get_seals |
| POST | `get_waybill` | ApiWaybillController@get |
| POST | `update_geolocation/{distance}` | ApiGeolocationController@update |

**API для интеграции компаний / smart-city** (префикс `ref`, middleware `set_company_conf`, JWT `company.jwt`):
- `ref/auth/{login,logout,refresh,getuser}` (AuthJwtCompanyController)
- `ref/{companies,employees,transports,drivers,waybills}` (ApiDataController) — справочные выгрузки
- `route`, `direction`, `client`, `remain_fuel` (DataController)
- `files/upload`, `files/delete` (FileUploadController)
- `apiResource`: `waybill1ade`, `waybill2b`, `waybill1ad`, `waybill5bbm`, `waybill3c`, `waybill1a` (CompanyApi\Waybill*Controller)
- `waybill_neru` (POST/GET) — Waybill3cController (**уточнить**: «неру» = спец. режим)
- `organization`, `transports`, `driver`, `employees` (POST/GET) — CRUD организаций/ТС/водителей/сотрудников (kvd)
- `POST waybill/confirm` — WaybillConfirmController@confirm (подтверждение врачом/механиком по RMA)
- **Smart city GPS** (префикс `gps`, `company.jwt:2`): `POST gps/gps_data` → GpsDataController@store

> `routes/api1.php` — устаревшая копия того же (не подключён к боевому Kernel — **уточнить** в `RouteServiceProvider`).

---

## 4. Справочники (Массивҳо / Сузишвори) с полями

> Поля — из миграций `database/migrations` и `$fillable`/`$fields` моделей. Многие поля-документы хранятся как `binary`/пути к файлам. `create_user_id`/`update_user_id` проставляются автоматически (см. `User::boot`).

### 4.1. `regions` — Минтақаҳо (регионы)
`id, code (smallint), name, timestamps`. Связь: `cities.region_id`, `companies.region_id`, `directions.region_id`. Используется как верхний уровень геоскоупа.

### 4.2. `cities` — Шаҳру ноҳияҳо (города/районы)
`id, name(100), code(5), region_id, timestamps`.

### 4.3. `companies` — Корхонаҳо (предприятия-перевозчики)
Из миграции + модели (`$fillable`, `$fields`):
`id, number (рамз), name, city_id, region_id, ownership_id (Намуди моликият), type_company_id (1=истифодаи умум,2=соҳавӣ), registration_certificate(+attach), iktibos(+attach), rma(+attach), aai (ААИ 18%,+attach), license_activity_from/to(+attach), license_number, bank, address, phone, name_head (руководитель), email, points (координаты), latitude, longitude, kpp, percent_income (% с дохода), cat_1/cat_2 (ставки разрядов), seal_attach (муҳр), plan_pass_volume, plan_pass_traffic (плановые объём/оборот), give_fuel (флаг топлива), status, status_lock (блок), softDeletes`.
Связи: `city`, `region`, `ownership`, `type_company`, `related_companies` (M2M `company_has_relatedcompany` — предприятия для прохождения осмотра). Скоуп-метод `scopeAllow`.

### 4.4. `parkings` — Автомобилҳо (ТС предприятия)
`id, number, registration_number(госномер), brand_id, transport_type_id, capacity (мест), carrying (грузопод.), number_ydak/brand_ydak/carrying_ydak (прицеп), tech_id_number(+attach), tech_inspection_date_from/to(+attach) (техосмотр), expire_checklist_date_from/to(+attach), year_manufacture, vincode, company_id, timestamps`.
Связи: `company`, `brand`, `transport_type`. (Доп. поля добавлены миграциями `add_columns_to_parkings`.)

### 4.5. `brands` — Тамғаҳо (марки ТС)
`id, number, name, model, capacity, carrying, fuel_consumption (норма расхода), fuel_id, fuel, fuel_type, cost_services, coe_sea (коэф. места), coe_conditioner (коэф. кондиционера), timestamps`. Доп. поля в `add_cols_to_brands`, есть `brand_types` (BrandType).

### 4.6. `drivers` — Ронандаҳо (водители)
`id, number (табельный), full_name, license(+attach) (удостоверение), category, passport(+attach), degree (классность), patent_valid_date, duration_lessons_20_hours(+attach) (талон 20-часовых занятий), med_cert_valid_date(+attach) (медсправка), contract_number, duration_contract_number, rma(+attach), power_attorney(+attach) (доверенность), visa_valid_date(+attach), address, phone, email, photo, company_id, last_waybill, latitude/longitude/location_updated_at (геопозиция), softDeletes`.
Наблюдатель `DriverObserver`.

### 4.7. `routes` — Хатсайрҳо (маршруты)
`id, number, type_id (route_type), name_a, name_b (пункты А/Б), distance_a, distance_b, time_one_lap_a/b (время круга), begin_path_a/b, planned_lap (план кругов), coe_use_capacity (коэф. использования вместимости), average_length_pass_seat, valid_cert (действие сертификата), latitude, longitude, timestamps`. (+ `region_id`/`transport_type_id`/`company_id` через доп. миграции — **уточнить** полный набор.)

### 4.8. `route_types` — Намуди хатсайр
`id, number, name, timestamps`.

### 4.9. `directions` — Самтҳо (направления, для расчёта топлива)
`id, region_id, number, winter_coef_id, mountain_coef_id, in_city_coef_id, check, softDeletes, timestamps`. Связывает направление с набором топливных коэффициентов.

### 4.10. `clients` — Мизоҷ (клиенты/контрагенты)
`id, number, name, address, phone, type (доб. миграцией), timestamps`. Роли `client_sender/forwarder` привязываются через `user_clients`.

### 4.11. `cargos` — Бор (грузы)
`id, number, name, type, unit (ед. изм.), price, class (класс груза), timestamps, softDeletes`.

### 4.12. `tariffs` — Нархномаҳо (тарифы)
`id, number, type_auto, route_id, fuel_id, price_per_1_mkm (цена за 1 маш-км), adv_coe, price_one_time (разовая), timestamps`.

### 4.13. `employees` — Кормандон (сотрудники: врач/механик/диспетчер/топл./касса)
`id, type (1=Духтур,2=Механик,3=Танзимгар,4=Корманди нуқтаи сӯзишворӣ,5=Корманди хазина), number, name, company_id, address, phone, seal (муҳр), signature (имзо), softDeletes, timestamps`. Привязка к юзеру — `employee_has_user`.

### 4.14. `fuels_table` — Сузишвори (виды топлива)
`id, number, name, timestamps`. (Модель `Fuel`; есть `ParkingFuelLeft` — остатки топлива по ТС.)

### 4.15. Топливные коэффициенты
| Таблица | Модель | Поля |
|---|---|---|
| `fuel_winter_coef` | FuelWinterCoef | `name, coef, period_from, period_to` (Зимистона) |
| `city_coef` | InCityCoef | `name, coef` (Дохили шаҳр) |
| `mountain_coef` | MountainCoef | `name, coef` (Баландкуҳ) |
| `used_coef` | UsedCoef | `id + guarded` (Истифодабари, коэф. износа/пробега) |

> Миграций `create` для этих таблиц в `database/migrations` нет — таблицы созданы напрямую (есть в дампе). **(уточнить полный DDL по дампу.)**

### 4.16. `drive_classes` — Дараҷаҳо (классы/разряды водителей)
Модель `DriverClass` (таблица `drive_classes`), поля через `guarded=['id']` — **(уточнить по дампу)**.

### 4.17. `external_countries` / `external_cities` — Давлатҳо/Шаҳрҳои хориҷа
`external_countries`: `id, code(32), title(256), softDeletes`. `external_cities`: `id, country_id, code(32), title(256), softDeletes`. Для международных перевозок (СМР).

### 4.18. `malumotnomas` — Маълумотнома (справка)
`id, fio, transport_type_id, create_user_id, update_user_id, softDeletes` (доб. `age`). Связь с маршрутами через `rmalumotnomas` (Rmalumotnoma) и `routemalumotnomas`.

### 4.19. `routemalumotnomas` — Самт барои маълумотнома
`id, name, distance, car_price, mbus_price, bus_price, timestamps`. Тариф по типам ТС для справки.

### 4.20. `bill_types` — Намуди роҳхат
`id, number, name(100), timestamps`. Привязка к юзеру через `bill_has_user`.

### 4.21. `waybill_plans` — Нақша (планы)
`id, type (1..4: Мусофирбарӣ/Сабукрав/2Б/5Б-БМ), company_id, date, capacity (объём), rotation (оборот), timestamps`.

### 4.22. `phone_infos` — Телефонҳо (устройства мобайла)
`id, user_id, type (1=android,2=ios), model, manufacturer, brand, phone_unique_id, unique_id, company_id, softDeletes`. Привязка/учёт устройств водителей.

### 4.23. `waybills` — реестр бланков
`id, company_id, type_id, number, qty, valid_date, receipt_date, responsible_person, attorney_number` — учёт выданных бланков путевых.

### 4.24. Прочие домен-модели
`NumberDriver` (numberdriver — нумерация?), `Car`, `Trailer`, `TransportType`, `TypeCompany`, `Ownership`, `Address`, `PostalBox`, `Icon`. `WaybillWorkDay` (рабочие дни путевого — JSON `work_days` в mbus/taxi).

---

## 5. Настройки платформы

- Пакет **Backpack Settings** подключён (`SettingsTableSeeder`), но таблица `settings` содержит **только демо-ключи**: `contact_email`, `contact_cc`, `contact_bcc`, `motto`. **Реального экрана «Настройки платформы» с бизнес-параметрами нет.**
- Backup-панель (`config/backup.php`), Elfinder (файловый менеджер), логи — стандартные Backpack-инструменты (доступны superadmin).
- Все бизнес-«настройки» вынесены в **конфиг-файлы** (не в UI):
  - `config/trans.php` — типы отчётов, модели, коэффициенты планов, соответствия форм/типов ТС (см. §7-8);
  - `config/mobile_waybill.php`, `config/mobile_cargowaybill.php` — конфиг мобильных путевых;
  - `config/jwt.php` — JWT.

---

## 6. Списки и ФИЛЬТРЫ (пользователь особо подчёркивает)

Backpack-фильтры: `select2`, `select2_ajax`, `date_range`, `simple`, `text`. Ниже — по каждому важному списку. **Отмечены пробелы/кривизна.**

### 6.1. `parking` (Автомобилҳо) — `ParkingCrudController`
Колонки+поиск: number (поиск `like number%`), company_id (без поиска), registration_number (поиск `like %..%`), year_manufacture, brand_id, vincode, tech_inspection_date_to, tech_id_number.
Фильтры:
- **Минтақа** (select2, только superadmin) — `whereHas company.city.region`.
- **Шаҳр** (select2, только superadmin) — `whereHas company.city`. ⚠️ Список городов не зависит от выбранного региона (все города).
- **Корхона** (select2_ajax; superadmin и region) — `whereHas company`.
- **3C фаъол / 3C ғайрифаъол** (date_range) — были/не были путевые 3С за период.
- **2B фаъол / 2B ғайрифаъол** (date_range).
- **Соли барориш** (date_range по `year_manufacture` — ⚠️ date_range применён к году, семантика странная).
- **4-роҳхат(3с)** (date_range) — ТС ровно с 4 путевыми 3С за период (`havingRaw COUNT=4`).
- **2-роҳхат(2b)** (date_range) — ТС ровно с 2 путевыми 2Б.

### 6.2. `driver` (Ронандаҳо) — `DriverCrudController`
Колонки+поиск: number (`like num%`), company, full_name, category, parking number, brand, phone, address, rma, талон 20ч, срок 20ч.
Фильтры:
- **Минтақа** (select2, superadmin).
- **Шаҳр** (select2, superadmin+region).
- **Корхона** (select2_ajax, superadmin+region).
- **Ронандагони фаъол, 3c/2b/5bbm/1a/1ad/1ade** (6× date_range) — активные водители по каждому типу путевого.
- **Ронандагони ғайрифаъол** (date_range).
- **rma_number** (text) — поиск по РМА.

### 6.3. `company` (Корхонаҳо) — `CompanyCrudController`
Колонки — из `Company::crudFields()` (все show-поля).
Фильтры (`addCustomCrudFilters`):
- **Минтака** (select2_ajax) — `where region_id`.
- **Шаҳр** (select2_ajax) — ⚠️ **БАГ**: в замыкании `addClause('where','region_id',$value)` вместо `city_id` (фильтр по городу фактически фильтрует по региону).
- **Корхона** (select2_ajax) — `where id`.
Скоуп: `middleware role:superadmin|region|admin` + `RoleTrait::hasRole()`.

### 6.4. `employee` (Кормандон) — `EmployeeCrudController`
Колонки: number(`like num%`), name(`like %..%`), type (select_from_array 1..5), rma, address, phone.
Фильтр: только **Корхона** (select2_ajax). ⚠️ Нет фильтра по типу сотрудника (врач/механик/…), хотя type — ключевое поле.

### 6.5. Путевые листы (`waybill1ad/1a/2b/3c/5bbm/...`) — общий `FilterTrait::setFilters()`
- Роль **company** видит: **Автомобил** (parking_id, select2 по `has_companies`), **Рақами табел** (timesheet_id/driver, select2), **Санаи роҳхат** (date_range), + для 3c/1a/2b — **«Роҳхатҳое, ки муҳлаташон ба итмом расидаанд ва коркард нашудаанд»** (simple, по `exit_date <= now-{5/8/16}дн` и `entry_date=null`); для 5bbm — **«Роҳхатҳои коркарднашуда»** (simple).
- Роль **admin/region/superadmin** видит: **Корхона** (select2_ajax), **Санаи роҳхат** (date_range), фильтры необработанных путевых, **е-роҳхат** (simple: `doctor_id != null AND mechanic_id != null` — полностью подтверждённые).
- `waybill3c`/`waybill3c30` доп.: **Намуди хизматрасонӣ** (select2: 1=такси,2=хатсайр,3=соатбай).
- Колонка «Рамз» (number) кликабельна на обработку путевого только если `doctor_id` и `mechanic_id` заполнены.

### 6.6. `gpsevent` (GPS) — `GpsdataCrudController`
Колонки: parking (госномер, поиск `like`), driver (full_name), company (через путевой). Фильтр: company_id (select2_ajax). Update/delete запрещены.

### 6.7. `malumotnoma` — фильтр **Санаи маълумотнома** (date_range).
### 6.8. `cmr`/`Cargo5bbm` — фильтры **Мизоҷ** (client_id select2_ajax) и 2× date_range (from_to).

### Общие замечания по фильтрам
- ⚠️ **Нет свободного текстового поиска-«всё»** на большинстве списков — поиск только по 1-2 колонкам (searchLogic), у многих колонок `searchLogic=false`.
- ⚠️ **Каскад регион→город не реализован** в фильтрах parking/driver (грузятся все города/регионы).
- ⚠️ **Баг фильтра «Шаҳр» в Company** (фильтрует по региону).
- ⚠️ Фильтры «активные/неактивные» построены на выборке всех id за период в память (`->get()->pluck()`) — потенциально тяжёлые (есть `set_time_limit(0)`).
- ⚠️ У справочников `region/city/brand/fuel/tariff/routetype/coef*` фильтров практически нет (мелкие таблицы — допустимо).

---

## 7. Отчёты

Все отчётные экраны обёрнуты middleware `lock_report_notification`. Форма выбора: тип листа (report_bill), тип отчёта (report_type), период (date_range), предприятие (company). Права проверяются `RoleTrait::hasPermission($report_bill)` + `hasAccessCompany`.

### 7.1. Мусофирбарӣ / Боркашонӣ (`Report1CrudController`, `ReportWaybillCargoController`)
Конфиг `config/trans.php → report` и `reportwaybillcargo`.
**Пассажирские (report_bill: bus/ebus/mbus/taxi)** — типы отчёта:
1 Автомобил, 8 Табел, 2 Хатсайр, 3 Тамға, 4 Авто, 5 Музди меҳнати ронандагон (зарплата водителей), 6 Малумот оиди гашт, 7 Дафтари қайди в/н (журнал путевых), 9 Сузишвори, 10 Хисоботи сузишвори, 11 сузишвории ронанда, 12 сузишвории автомобил, 13 Дафтари қайди механик, 14 Дафтари қайди духтӯр.
Расчёт: `BusCalc`/`MBusCalc`/`TaxiCalc` (методы `typeN`).
**Грузовые (report_bill: cargo2b/cargo5bbm)** — типы: 1 Автомобил, 6 Табел, 7 Тамға, 8 Самт, 9 Малумот оиди гашт, 10 Дафтари қайди в/н, 11 Авто, 12 Хисоботи сузишвори, 13 механик, 14 духтӯр. Расчёт: `Waybill2b\Report\Cargo2bCalc`, `Waybill5bbm\Report\Waybill5bbmCalc`.

### 7.2. Умумӣ (сводный) — `ReportWaybillCargoGeneralController` (+ `Api\GeneralReport\ApiReportWaybillGeneral`)
Только superadmin/admin/region. По регионам (скоуп `has_regions`).
report_bill: passenger, waybill3c, waybill1a, waybill2b, waybill5bbm, cargo_waybill (борхаты 1 и 2).
report_type: transportation / transportation_industry / transportation_general (соҳавӣ/истифодаи умум), count_waybills (+ industry/general), count_waybills_type2, count_cargowaybills_industry_general.
Нормативы «должно быть» (`must_give`): waybill3c=4, waybill1a=6, waybill2b=2, waybill5bbm=0. Сервисы — `app/Services/WaybillCargoGeneralReport/*`.
Показатели: объём (тыс. пасс/тн) и оборот (млн пасс-км/т-км).

### 7.3. Маълумотнома — `ReportMalumotnomaController`. 
### 7.4. Прогнозы (`config/trans.php → forecast`) — типы Хатсайр/Минтақа/Шаҳру ноҳия/Корхона; в UI-меню явного пункта нет (**уточнить**, возможно через ReportMain — закомментирован).

Печатные формы отчётов — `resources/views/report/**` (1a, 1ada, 1adt, bus, mbus, custom, main, details, waybillcargo, waybillcargogeneral).

---

## 8. Жизненный цикл путевого листа

Единого поля `status` нет — состояние выводится из заполненности полей:

1. **Создание («навиштан»)** — company/диспетчер создаёт путевой: company_id, parking_id, route_id, timesheet_id (водитель), client_id, indication_counter_exit (одометр на выезде), schedule (реҷа), **exit_date** (время выезда), fuel (repeatable: fuel_id, fuel_given, remain_fuel_before_exit, coef). Номер генерится `BillNumberTrait`.
2. **Подтверждение врачом/механиком** — устанавливаются `doctor_id` (type=1) и `mechanic_id` (type=2). Каналы: `POST /api/ref/waybill/confirm` (`WaybillConfirmController`, по RMA организации+сотрудника; повторное подтверждение → 409) и/или мобайл-инспекция (`WaybillOperationTrait::inspection` → `InspectionService::goInspection`). Тип листа → класс: 1/2=Waybill1ad, 3=Waybill1a, 4=Waybill3c, 5=Waybill2b, 6=Waybill5bbm.
3. **«е-роҳхат»** — когда `doctor_id != null && mechanic_id != null`, лист считается электронно-подтверждённым (фильтр `e_waybill`); только тогда «Рамз» кликабелен для обработки.
4. **Обработка/закрытие («коркард»)** — по возвращении: number_lap (круги), work_time, entry_date (время возврата), indication_counter_entry (одометр возврата), remain_fuel_entry, earning (виручка), kassa (касса). Пока `entry_date=null` и срок вышел → лист «муҳлаташон ба итмом расида, коркард нашуда».
5. **Печать / QR** — `{id}/print` (view `print.*`), публичная проверка `qrcode/{type}/{id}` (`QrCodeController`, type 1..8), `qrcode_last/{id}` (мобайл).
6. **Оплата** — `{id}/pay` (WaybillOperationTrait; связано с `debt`/`percent_income`/касса — **уточнить** механику оплаты).

Роли в цикле: **company/диспетчер** — создание и обработка; **doctor/mechanic** — подтверждение (мобайл/API); **fuel_employee** — топливная форма 1adfe; **employee_kassa** — касса; **region/superadmin** — контроль/отчёты.

---

## 9. Ключевые бизнес-фичи

- **Расчёт топлива**: `app/Services/Calc/*` (BusCalc, BusBaseCalc, MBusCalc, TaxiCalc, Calc), `Waybill2b\Fuel\*` и `Waybill5bbm\Fuel\*` (CargoFuelBase, LabadorFuel, SelfUnloadFuel, SpecialFuel, SpecialMoverFuel), `Traits\Utils\FuelSumUtils`. Норма расхода `brands.fuel_consumption` корректируется коэффициентами: зимний (`fuel_winter_coef` по периоду), город (`city_coef`), горы (`mountain_coef`), износ (`used_coef`), кондиционер/место (`brands.coe_*`), «коэф. температуры ниже 0» в путевом. Остатки — `ParkingFuelLeft` + API `parking_fuel_left/give/give_multi_days`.
- **Тарифы**: `tariffs` (цена за маш-км/разовая по маршруту+топливу), `routemalumotnomas` (цена по типам ТС для справки), `companies.percent_income/cat_1/cat_2` (ставки/процент). Расчёт заработка — earning/kassa в путевом + Calc-сервисы.
- **ЭЦП/подпись/печать**: подпись/печать — как **изображения** (`employees.seal/signature`, `companies.seal_attach`), не криптографическая ЭЦП. Подтверждение = проставление doctor_id/mechanic_id по RMA. Полноценной крипто-ЭЦП/PKI в коде **не обнаружено** (уточнить — вероятно, это gap для e-Waybill).
- **QR-коды**: `QrCodeController` (публичная проверка по зашифрованному id, `decrypt`), `Mobile\QrCodeController`, пакет simple-qrcode.
- **GPS**: приём координат от ТС (`gps/gps_data`, `GpsDataController`), мобильная геолокация водителя (`update_geolocation`, обновляет `drivers.latitude/longitude/location_updated_at`), просмотр GPS-событий (`gpsevent`, роль gps), `points/latitude/longitude` у company/route.
- **Мобильное приложение водителя**: JWT-логин, профиль, лицензия/печати компании, получить путевой, геолокация; учёт устройств `phone_infos`.
- **Интеграция «компания/smart-city»**: `ref/*` API (выгрузка справочников и приём путевых `waybill1ade/1ad/1a/2b/3c/5bbm`), организации/ТС/водители/сотрудники (модели `App\Models\kvd\*`), подтверждение путевых, загрузка файлов, GPS smart-city.
- **СМР / международные**: `cmr` (Cargo5bbm), `external_countries/cities`, борхаты замимаи 1/2, роль `customs_officer`.
- **Блокировки/уведомления**: `companies.status_lock` + middleware `Notification` (блок при неоплате), `license_has_expired` (окончание лицензии), `UserLock`/`password_lock` (`users.password_locked`, `login_attempts`, `is_blocked`).
- **Аудит**: `create_user_id/update_user_id` (авто в `User::boot`), пакет revisions, `AuditTrait`.
- **Долг/оплата**: право `debt` → `session('debt')`; `{id}/pay`.

---

## 10. Итоги и что важно/пропущено у типичной реализации

**Количественно:**
- **Роли:** 12 (боевой дамп) — superadmin, admin, company, region, client_forwarder, client_sender, mechanic, doctor, fuel_employee, employee_kassa, customs_officer, gps.
- **Права:** 18 (list/create/update/delete + bus/ebus/mbus/taxi/cargo2b/cargo5bbm/cargowaybill1_attachment/cargowaybill2_attachment/cmr/malumotnoma/ewaybill/debt/sectoral_company/public_company).
- **CRUD-сущности:** 42 (custom.php) + 3 (permission/role/user) = 45 админ-CRUD.
- **Справочники (Массивҳо+Сузишвори):** ~28 (region, city, company, parking, car, brand, driver, route, routetype, directions, client, cargo, tariff, employee, fuel, 4 коэф., driverclass, external_countries/cities, malumotnoma, routemalumotnoma, bill_types, numberdriver, waybill_plan, phone, waybills-реестр).
- **Формы путевых/борхатов:** 1-АД (bus/ebus, +fe, +eBus), 1-А (mbus), 3-С (+3c30), 2-Б, 4-МБМ, 5Б-БМ, СМР, борхаты замимаи 1/2, маълумотнома.
- **Отчёты:** 3 группы экранов (Мусофирбарӣ/Боркашонӣ до 14 типов каждый; Умумӣ ~8 типов; Маълумотнома) + прогнозы (в конфиге).
- **API:** мобайл водителя (9 маршрутов), интеграция компаний `ref/*` (~25 маршрутов, apiResource на 6 форм), GPS smart-city, CMR.

**Наиболее важное / болевые точки для сверки в e-Waybill:**
1. **Права размазаны** между spatie (roles/permissions), сессионным скоупом (has_regions/has_companies/public_company/sectoral_company) и **хардкод-списками user id** (137/480/330/177/205/490/1056/388/650/1156/210). Обязательно нормализовать в матрицу.
2. **Скоуп по регионам/предприятиям** реализован через пивоты `region_has_user`/`company_has_user` + сессию, а не декларативно (нет policies — папка `app/Policies` фактически пуста). В e-Waybill это должно стать явными политиками/scope.
3. **Нет единого статуса путевого** — состояние вычисляется из полей (exit_date/entry_date/doctor_id/mechanic_id). Стоит ввести явный статус-машину.
4. **ЭЦП — только картинки печати/подписи**, крипто-ЭЦП/PKI отсутствует (вероятный gap стандарта).
5. **Фильтры**: местами баги (Company «Шаҳр» → region_id), нет каскада регион→город, нет типового «поиска по всему», тяжёлые in-memory фильтры активности; фильтр по типу сотрудника отсутствует.
6. **Настроек в UI нет** — все параметры в конфиг-файлах; таблица settings = демо.
7. **Топливные коэффициенты** — 4 отдельных справочника, связываются через `directions`; логика расчёта разбросана по множеству Calc/Fuel-сервисов.
8. **Демо-мусор Backpack** и дубликаты `*copy*`/`*0.php` — при переносе не тащить.
9. Патч в **vendor** (`AuthenticatesUsers`) — критичная бизнес-логика (сессионный скоуп) живёт в вендоре; легко потерять при обновлении.

> Файлы-ориентиры: роуты — `routes/backpack/custom.php`, `routes/api.php`; меню — `resources/views/vendor/backpack/base/inc/sidebar_content.blade.php`; скоуп — `app/Traits/RoleTrait.php` + `vendor/.../AuthenticatesUsers.php`; фильтры — `app/Traits/FilterTrait.php` и `*CrudController`; отчёты — `config/trans.php` + `app/Services/*Report*`, `app/Services/Calc/*`.
