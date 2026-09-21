# 07. Механическая инвентаризация legacy «Роҳхат» → e-Waybill (Шаг 1 промпта переноса)

Дата: 2026-09-21. Источник: `D:\Projects\rohkhat.tj ralavel` (только чтение), снято скриптом
(`routes/*`, все `*CrudController`, `app/Http/Requests/**`, `app/Services/**`, `app/Traits/**`,
`app/Models/**`, `resources/views/**`, `database/migrations/**`, `config/trans.php`, sidebar-меню)
и сверено с e-Waybill по коду (`*Controller.java` обоих сервисов, entity-поля, web-страницы).
Итог по каждой строке уходит в `MIGRATION.md` (там — статус и «как проверено»); здесь — полная карта,
чтобы ни один элемент старой платформы не остался без строки.

Легенда статуса: **✓** есть 1-в-1 или эквивалент · **~** частично · **✗** нет · **†** мёртвый код /
демо фреймворка / костыль — не переносить (с доказательством) · **⏸** отложено владельцем (мобильное).

---

## A. Точки входа UI (sidebar_content.blade.php) — что реально видит пользователь

| Legacy пункт меню | Кому | e-Waybill | Ст. |
|---|---|---|---|
| Роҳхатҳо → Тролейбус (`waybill1adeBus`) | can:ebus | `/waybills` тип WB_TROLLEYBUS | ✓ |
| Роҳхатҳо → Автобус (`waybill1ad`, для fuel_employee — `waybill1adfe`) | can:bus | WB_BUS; кабинет `/fuel` | ✓ |
| Роҳхатҳо → Микроавтобус (`waybill1a`) | can:mbus | WB_MINIBUS | ✓ |
| Роҳхатҳо → Сабукрав (`waybill3c`; `waybill3c30` — Душанбе, по сессии) | can:taxi | WB_TAXI/WB_CAR; «30 дней» — policy | ✓ |
| Роҳхатҳо → Шакли 2-Б | can:cargo2b | WB_TRUCK | ✓ |
| Роҳхатҳо → Борхати замимаи 1 / 2 | can:cargowaybill1/2_attachment | consignment + `print-attachment.pdf` | ~ (нет отдельного реестра борхатов с фильтрами; кабинеты отправителя/экспедитора — Вопрос 2) |
| Роҳхатҳо → Шакли 5Б-БМ | can:cargo5bbm | WB_TRUCK_INTL | ✓ |
| Роҳхатҳо → СМР (`cmr`) | can:cmr | `print-cmr.pdf` | ~ (нет реестра СМР с фильтрами клиент/период) |
| Роҳхатҳо → Маълумотнома | can:malumotnoma | `/reports/malumotnoma` | ✓ |
| Массивҳо → Автомобилҳо / Ронандаҳо / Кормандон | superadmin/admin/region/company | `/fleet`, `/registry` | ✓ |
| Массивҳо → Мизоҷ, Бор (только user 330 — хардкод) | — | `/dictionaries/clients`, cargos | ✓ |
| Массивҳо (region) → Корхонаҳо, Хатсайрҳо, Тамғаҳо, Мизоҷ, Самтҳо, Бор, Шаҳрҳои хориҷа | region | `/company`, `/dictionaries/*`, legacy-ref | ✓ (регион-пишущий — Вопрос 3) |
| Телефонҳо (`phone`, 7 user id) | хардкод | `/registry/devices` | ✓ |
| Самт барои маълумотнома (`routemalumotnoma`) | user 177/137/480 | маршруты справок в `/reports/malumotnoma` | ✓ |
| Корхонаҳо, Минтақаҳо, Шаҳру ноҳияҳо, Тамғаҳо, Самтҳо, Давлатҳои/Шаҳрҳои хориҷа, Нақша | user 137/480 | company/regions/cities/brands/directions/countries/external-cities/plans | ✓ |
| Сузишвори → Сузишвори, Коефитсентҳо (зимистона/дохили шаҳр/баландкуҳ/истифодабари) | user 137/480 | legacy-ref coefs (UI в `/dictionaries`) | ✓ (виды топлива — enum, ⚙~) |
| Хатсайрҳо, Мизоҷ, Бор, Нархномаҳо, Намуди хатсайр, Дараҷаҳо | user 137/480 | routes, clients, cargos, tariffs, route-types, drive-classes | ✓ |
| Ҳисобот → Мусофирбарӣ (`report`), Боркашонӣ (`reportwaybillcargo`) | superadmin/company/region (+3 user id) | `/reports/sections` (passenger/cargo, 14 типов) | ✓ |
| Ҳисобот → Умумӣ (`reportwaybillgeneral`) | superadmin/admin/region | `/reports/regional` (transportation/count/norm/trend) | ✓ |
| Ҳисобот → Маълумотнома (`reportmalumotnoma`) | can:malumotnoma | `/reports/malumotnoma` отчёт по кассирам | ✓ |
| GPS → Событии (`gpsevent`) | role gps + 137/480 | `/monitoring` (живые точки) | ~ (журнал СОБЫТИЙ заезд/выезд — см. C.4) |
| Аутентификатсия → user/role/permission | 137/480 | `/company/access` (org-users) + `/settings/roles` | ✓ |
| Dashboard (+ `dashboard/ebus`) | все | `/dashboard` (+ тренд пассажирооборота) | ~ (счётчик ПЛ троллейбус по месяцам) |

Не в меню, но есть роутами (тупики/мусор): `car` (дубль parking с полем price — не в меню, †), `waybill`
(реестр бланков — сознательно не тащим), `numberdriver` (†), `billtypes` (enum ✓), `waybill4mbm`
(нет в меню; в e-Waybill бланк есть), `fuel` (в меню; ✓ enum), Backpack-демо `monster/dummy/product/icon/
fluent-monster`, charts `users/new-entries`, Lines/Pies (†).

## B. Admin CRUD: поля / колонки / фильтры → e-Waybill

| CRUD | Поля формы (legacy) | Фильтры (legacy) | e-Waybill | Ст. / гэп |
|---|---|---|---|---|
| `parking` (ТС) | number, transport_type_id, company_id, registration_number, brand_id, capacity, carrying, ydak×2 (number/brand/carrying/weight), indication_counter, expire_checklist_number/date_to/attach, certificate_number, year_manufacture, vincode, air_conditioner, tech_id_number(+attach), tech_inspection_number/date_to(+attach), expire_checklist_itl_number/date_to, timesheet (водители) | region→city→company, **active_trans/inactive_trans** (ТС с/без 3-С за период), **active2b/inactive2b** (2-Б), **period_trans** (год выпуска от–до), **two_waybills** «4-роҳхат(3с)» (ровно 4 ПЛ 3-С за период), **four_waybills** «2-роҳхат(2b)» (ровно 2 ПЛ 2-Б) | `Vehicle` (все поля ✓, документы — subject documents) | ~: фильтры активности/кол-ва ПЛ за период и год выпуска ✗; экспорт ✗ |
| `driver` | number(таб.), full_name, company, category, address, phone, email, license(+attach), passport(+attach), lessons_20h number/duration(+attach), med_cert number/valid(+attach), rma(+attach), power_attorney(+attach), visa_valid(+attach), photo, signature_attach, bill_block, status, contracts, debt, duration_contract_number, degree | region→city→company, **inactive_drivers** (без 3-С за период), **active_drivers{тип}** (с ПЛ типа за период), **rma_number** | `Driver` (ядро ✓; suspended=status; bill_block=инвариант «1 ПЛ») | ~: patent_valid_date/photo/signature/debt ✗ (2.6); фильтры активности ✗; авто-номер (DriverObserver) ✗; экспорт ✗ |
| `employee` | number, type (1 врач/2 механик/3 диспетчер/4 топливо/5 касса), company, name, rma, address, phone, seal, signature (картинки) | company | `Employee` (type 1..3, certNumber) | ~: типы 4/5 ✗ (роли FUEL_STATION/ACCOUNTANT есть, тип сотрудника нет); seal/signature-картинки ✗ (заменены ЭП — Вопрос 8) |
| `company` | status_lock (+ через FetchTrait все поля 2.3) | region→city, company | `Organization` (blocked/blockReason) | ✓ (2.3 гэпы number/give_fuel/seal_attach/points → Вопрос 8) |
| `route` | number, desc, type_id, transport_type_id, region_id, city, company_id, name_a/b, distance_a/b, **time_one_lap_a/b**, begin_path_a/b, planned_lap, coe_use_capacity, average_length_pass_seat, **valid_cert**, in_city/winter/mountain coef, station_coef, road_quality, additional_fuel(_100), cond_fuel, heating_fuel, **latitude/longitude**, excluding_coef, **week_days_earnings** | — | `Route` (V28) | ~: time_one_lap_a/b, valid_cert, desc, city, lat/long ✗; week_days_earnings — в расчётах/отчётах legacy НЕ используется (только модель+форма) → Вопрос 14 |
| `brand` | (см. 2.5) | — | `Brand` | ~ (2.5) |
| `client` | number, **type** (намуди мизоҷ), name, address, phone, **riam, rma, account, correspondence_account, mfo, bank_name** | — | `Client` (number/name/address/phone) | ~: type + 6 банковских реквизитов ✗ (нужны для борхата/СМР сторон) |
| `cargo` | **number**, name, type, unit, price, class | — | `Cargo` (name/type/unit/price/cargoClass) | ~: number ✗ |
| `tariff` | number, type_auto, route, fuel, price_per_1_mkm, **adv_coe**, price_one_time | — | `RouteTariff` | ~: adv_coe ✗ |
| `directions` | number, title, winter/mountain/in_city_coef_id, check | — | `Direction` | ✓ |
| `wintercoef` name/coef/period_from/to · `citycoef` name/coef · `mountaincoef` name/coef · `usedcoef` year/km/coef · `driverclass` class/coef · `routetype` · `region` code/name · `city` code/name/region | — | legacy-ref / regions / cities / route-types | ✓ |
| `external_countries`, `external_cities` | code/title(/country) | — | classifier COUNTRY, external_city | ✓ |
| `fuel` | number, name | — | enum 1..5 | ✓ (⚙~) |
| `phone` (PhoneInfo) | user, driver, company, model, manufacturer, brand, phone_unique_id | company | `MobileDevice` | ✓ (авто-регистрация с телефона ⏸) |
| `gpsevent` (GpsData) | parking, driver, company, route, direction, state, time, distance | company | `GpsPing` (lat/lon/speed) | ✗ событийная модель (C.4) |
| `malumotnoma` | id, fio, age, transport_type_id, routes[] (route_id, round_trip), created_at | from_to | `Malumotnoma` | ✓ |
| `routemalumotnoma` | name, distance, car/mbus/bus price | — | `MalumotnomaRoute` | ✓ |
| `waybill_plan` | type (1 пасс/2 такси/3 2Б/**4 5Б-БМ**), company, date(год), capacity, rotation | — | `WaybillPlan` (PASSENGER/TAXI/CARGO, +month) | ~: вид «5Б-БМ» ✗ |
| `waybill1ad` / `waybill1adeBus` / `waybill1adfe` | type, number, company, parking, route, timesheet, created_at, indication_counter_exit/entry, schedule, exit_date, begin_path_a/b, number_lap, work_time, conditioner_time, entry_date, client_id, client_time, earning, kassa, special_mark, fuels[] (fuel_id, fuel_given, remain_before_exit, remain_entry, **be_given, additional, coef_below_0**) | company, from_to, valid_date (просрочен и не обработан), e_waybill; company-роль: parking_id, timesheet_id, from_to | Waybill + WorkDay + FuelRecord | ~: be_given/additional/coef_below_0 ✗ (в расчёт идут нулями — C.5); фильтры ТС/водитель-select, type_service ✗ (C.6) |
| `waybill1a` (1-А) | + work_days[] ≤4 (date, exit/entry_time, begin_path_a/b, laps, odometers, client, client_time, conditioner_time, fuels[]), kassa, max_counter_value=750 | те же | ✓ WorkDay | ~ (лимит суточного пробега ✗ — D) |
| `waybill3c` / `waybill3c30` | + type_service 1/2/3, regions_id[] (1..7), work_days[] (без лимита), kassa, **employee_kassa_id + действие `pay`** (кассир отмечает сдачу выручки), max_counter_value=650 | + type_service | ✓ | ~: `pay`/employee_kassa ✗ (C.7); type_service-фильтр ✗ |
| `waybill2b` | type_of_shipment 1/2, direction, client, trailers[] ≤2, work_days[] 1..15 (…work_time, fuels ≤1), special_mark, борхаты 1/2 | те же (+valid_date −16 дн.) | ✓ (+consignment) | ✓ |
| `cargowaybill1attachment` / `2attachment` (борхат прил. 1/2) | client, sender, receiver, forwarder, cargo, amount, capacity, distance, invoice_number, waybill_number, date | (CWControllerTrait) client, from_to | consignment в ПЛ 2-Б + `print-attachment.pdf` | ~ (нет отдельного реестра/фильтров; кабинеты — Вопрос 2) |
| `waybill5bbm` | parking(+iadak), first/second driver, client, load/unload country+city, visa (country/expire), transit_countries[], cargo, bba_number, cargo_capacity/distance, fuels ≤1, arrival_time, special_mark | + valid_date (не обработан) | WB_TRUCK_INTL | ✓ (arrival_time — проверить в typeData) |
| `cmr` (Cargo5bbm) | waybill5bbm, sender, receiver, load/unload country+city, cargo, amount, distance, reis_amount, capacity, entry_time, **customs_officer_id** | client_id, from_to | `print-cmr.pdf` | ~: реестр СМР ✗; таможенник — Вопрос 2 |
| `waybill4mbm` | company, parking, timesheet_first/second, odometers, route, fuel_given/remain×2, entry_date, start/end_route_name, number_passengers, route_distance | — | WB_PAX_INTL + `waybill4mbm.html` | ✓ (сверить typeData на 4 поля маршрута/пассажиров) |
| `report` / `report_details` / `reportwaybillcargo` / `reportwaybillgeneral` / `reportmalumotnoma` | форма: report_bill, report_type, dates, company (region выбирает через ajax), regions[] | — | `/reports/*` | ✓ типы; ~ `report_details` (тип 10 по ОДНОМУ ТС — параметр parking_id) ✗ |
| `waybill/{type}/{id}` (PC-версия мобильного вида с подписями) | — | — | карточка ПЛ + PDF | ✓ |
| `user` / `role` / `permission` (PermissionManager) | — | — | org-users + role-access | ✓ |

## C. API и интеграции — маршрут за маршрутом

### C.1 Публичные (web.php)
| Legacy | e-Waybill | Ст. |
|---|---|---|
| `GET qrcode/{type 1..8}/{enc id}` — публичная страница проверки (1 1-АД, 2 1-А, 3 3-С, 4 2-Б, 5 5Б-БМ, 6 борхат, 7 СМР, 8 маълумотнома) | `GET /api/v1/verify/{jws}` + публичный портал; QR есть на всех 11 шаблонах печати | ✓ (проверить, что QR борхата/СМР ведёт на verify) |
| `GET qrcode_last/{id}` — QR последнего ПЛ водителя | mobile `/waybills/{id}/qr` | ✓ |
| `GET get_waybill/{driver_id}` — HTML последнего ПЛ водителя | mobile `/waybills/current` (JSON) + PDF | ✓ (дизайн) |
| `POST /api/cmr` (cmr_number + захардкоженный токен → number/company/rma/link на QR) | verify/{jws} | ✓ (дизайн; † хардкод-токен не переносить) |

### C.2 Мобильное API водителя (api.php, `set_driver_conf`, throttle 60/мин)
| Legacy | e-Waybill | Ст. |
|---|---|---|
| `auth/login|logout|refresh|getuser` (JWT, привязка телефона: 1 телефон/водитель, смена через 15 дней — `AuthService.phoneChanged`) | Keycloak; `MobileDevice` реестр вручную | ~ (правило «1 устройство / 15 дней» ⏸) |
| `POST get_profile` (name/phone/email/photo) | `GET /mobile/me` | ✓ (photo ✗ — 2.6) |
| `POST get_license_attach` (скан ВУ) | subject documents (driver) — мобильного эндпоинта нет | ~ |
| `POST get_seals/{company_id}` (картинки подписей врача/механика/диспетчера/водителя, печать компании) | ЭП + отпечаток + QR | ✓ (дизайн; Вопрос 8 про печать) |
| `POST get_waybill` (v=2.5 гейт версии; 404 если `location_updated_at` ≥5 мин и нет ПЛ; HTML-вид) | `/mobile/waybills/current` (JSON: `allowedActions`, qrJws) | ✓ (присутствие ⏸) |
| `POST update_geolocation/{distance}` — присутствие на предприятии: distance <300 м (Душанбе 3-С <2000, компания 152 <600, город 23 <500 — хардкод), пишет `location_updated_at`/`last_distance`; барьер для подтверждения врача/механика (`InspectionService`: ≥5 мин → отказ) | нет | ⏸ (владелец: мобильное позже) |
| `waybill1ad*.blade` мобильные HTML (26 шаблонов), `config/mobile_waybill.php`, `mobile_cargowaybill.php` | JSON DTO + PDF | ✓ (дизайн) |

### C.3 API перевозчиков `ref/*` (`set_company_conf`, `company.jwt:1`) — «КВД»
| Legacy | e-Waybill | Ст. |
|---|---|---|
| `ref/auth/login|logout|refresh|getuser` (company_for_api: логин/пароль интегратора) | Keycloak client-credentials (`epd-aggregator`, роль API_INTEGRATOR) | ✓ |
| `GET companies/employees/transports/drivers/waybills` (списки) | `GET /organizations`, `/employees`, `/vehicles`, `/drivers`, `/waybills` (API_INTEGRATOR = платформенное чтение) | ✓ (сверить поля ответа при подключении реального интегратора) |
| `GET route|direction|client?search=` (подсказки) | `/dictionaries/routes`, `/legacy-ref/directions`, `/dictionaries/clients` | ✓ |
| `GET remain_fuel?transport_registration_number&waybill_type&fuel_id` — остаток топлива ТС из последнего ПЛ | нет | ✗ (C.5) |
| `POST files/upload`, `files/delete` (+ `CheckFilesTrait` проверяет, что файл существует перед сохранением сущности) | multipart в documents (org/vehicle/driver/waybill) | ✓ (дизайн) |
| `apiResource waybill1ade|1ad|1a|3c|2b|5bbm` (index/store/show/update/destroy — создание/закрытие ПЛ всех 6 форм системой перевозчика; правила — `Requests/kvd/*`) | только `POST /aggregator/waybills` (такси) | ✗ → Вопрос 11 |
| `POST waybill_neru`, `GET waybill_neru/{id}` (агрегатор такси: exit/entry/distance) | `AggregatorController` | ✓ |
| `POST/GET organization`, `transports`, `driver`, `employees` (регистрация субъектов интегратором, `Requests/kvd/Store*`) | `POST /sync/organization|vehicle|driver|employee` | ✓ |
| `POST waybill/confirm` (organization_rma, waybill_type 1..6, waybill_id, employee_rma → doctor_id/mechanic_id; 409 если уже) | `confirm-med`/`confirm-tech` только под логином DOCTOR/MECHANIC | ✗ → Вопрос 11 |

### C.4 Smart-city GPS (`auth_company`, `gps/gps_data`, `company.jwt:2`)
Legacy — **событийная** модель: `state ∈ {enter_into_route, exit_from_route, enter_into_company, exit_from_company}`,
`direction` обязателен для route-состояний, `distance` обязателен для enter_into_company, `registration_number`,
`time`; **повтор того же state — не чаще 1 раза в 20 минут**; журнал `gps_data` (parking/driver/company/route/
waybill/direction/state/time/distance) с фильтром по компании. e-Waybill — сырые точки (`POST /gps`: lat/lon/speed/
waybillId) + live/track. **✗ событий и 20-мин дедупликации** → Вопрос 12 (актуальна ли интеграция smart-city).

### C.5 Служебное API админки (`api/*`, middleware admin)
| Legacy | e-Waybill | Ст. |
|---|---|---|
| `filter/region|city|company|client?q=` (ajax-подсказки), `filter/regions` (регионы пользователя) | поиск в реестрах/`SearchSelect` | ✓ |
| `parking_indication_counter/{id}` — последний одометр ТС для автоподстановки при создании ПЛ | `Vehicle.odometer` + `PATCH /vehicles/{id}/odometer`; подстановка в форму активации — проверить | ~ |
| `parking_fuel_left/{parking}/{fuel}/{table}/{id}` — `remain_fuel_entry` из ПРЕДЫДУЩЕГО ПЛ того же ТС по виду топлива → «Бақияи пеш аз баромад» | нет | ✗ |
| `parking_fuel_give/…`, `parking_fuel_give_multi_days/…` — `be_given` из предыдущего ПЛ | нет | ✗ |
| `POST reportwaybillgeneral` (ajax сводного) | `/reports/regional*` | ✓ |

## D. Правила валидации (`app/Http/Requests/**`) → e-Waybill

| Правило legacy | Где | e-Waybill | Ст. |
|---|---|---|---|
| RMA/ИНН = 9–10 цифр (`size:9,10|regex:^[0-9]+$`) | все kvd-запросы, org/driver/employee | `@Pattern("\\d{9,10}")` везде | ✓ |
| Госномер: kvd `^[A-Z0-9]+$` max 15; админ-форма «4 цифры + 2 лат. буквы + 2 цифры, напр. 1234AB01» | StoreTransport, ParkingRequest | `[A-Za-zА-Яа-я0-9]{4,20}` | ~ (формат 1234AB01 не проверяется — Вопрос 13: какой формат боевой?) |
| `year_manufacture` — 4 цифры, ≥1900, ≤ текущий | StoreTransport | без ограничения | ✗ |
| `transport_type_id ∈ 1..6`, `type ∈ 1,2,3` (сотрудник), `degree ∈ 1,2,3`, `region_id ∈ 1..7`, `type_company_id ∈ 1,2` | kvd | `@Min/@Max` 1..6 / 1..3 / 1..7 / 1..2 | ✓ (degree 1..3 — проверить) |
| `parkings.number` уникален в компании | ParkingRequest | `parkingNumber` 4 цифры; уникальность — проверить | ~ |
| Обязательные при создании ПЛ: company, parking, route (кроме 2-Б/5Б-БМ), timesheet(≠0), schedule, exit_date, indication_counter_exit ≥0 | Waybill*Request | `@NotNull/@NotBlank` в CreateRequest; odometer ≥0 | ✓ |
| «Роҳхати қаблии ронанда коркард нашудааст» — нельзя выписать новый ПЛ, пока предыдущий не обработан | 1-А/3-С Store | инвариант «1 действующий ПЛ» (V7) + preflight | ✓ |
| 1-А: `work_days ≤ 4`; 2-Б: `work_days 1..15`; 5Б-БМ/2-Б: `fuels ≤ 1`; 1-АД/1-А/3-С: `fuels ≤ 2` | kvd | без лимитов | ✗ (лимиты дней/видов топлива на ПЛ) |
| 1-АД `fuels.*.additional ∈ 0..5` (харҷи иловагӣ) | kvd | поля нет | ✗ (C.5/§5.6) |
| Лимит суточного пробега: `max_counter_value` 650 (3-С, 1-А update) / 750 (1-А create) — «Максимальное значение спидометра» | формы + kvd | нет | ✗ |
| `fuels.*.fuel_id ∈ 1,2,3` (API) | kvd | 1..5 | ✓ (шире) |
| `type_service ∈ 1,2,3`, `regions_id.* ∈ 1..7` (3-С), `type_of_shipment ∈ 1,2` (2-Б) | kvd | serviceKind enum; регион работы 1..7; shipmentKind | ✓ (проверить регионы) |
| `begin_path_a/b ∈ {begin_path_a, begin_path_b}` | kvd | typeData | ✓ |
| Времена `H:i`, даты `Y-m-d H:i(:s)` | kvd | LocalTime/OffsetDateTime | ✓ |
| `entry_date.valid_date_range` (3-С update: возврат не раньше выезда) | kvd | проверить в `returnTrip` | ~ |
| GPS: state ∈ 4 значений, direction/distance по состоянию, cooldown 20 мин | StoreGpsData | нет | ✗ (C.4) |
| Маълумотнома: fio, age, transport_type_id обязательны | MalumotnomaRequest | ✓ | ✓ |
| Cargo: number/name/type/unit/price обязательны; Client: number/name/address/phone; Direction: number/title(/region) | *Request | `@NotBlank` частично | ~ (number у cargo нет) |
| Waybill5bbm: exit_date, company, client, parking, first+second driver, load/unload country+city, visa_country, cargo, bba_number, visa_expire_date, transit_countries — обязательны | Waybill5bbmRequest | проверить обязательность в форме WB_TRUCK_INTL | ~ |
| Фото водителя `image|mimes:jpg,png,jpeg` (update) | DriverUpdateRequest | поля нет | ✗ (2.6) |

## E. Сервисы / трейты / модели → e-Waybill

| Legacy | Назначение | e-Waybill | Ст. |
|---|---|---|---|
| `Services/Calc/{Calc,BusBaseCalc,MBusCalc,BusCalc,TaxiCalc}`, `Bus/BusCalc` | показатели/топливо/зарплата пассажирских, типы отчётов 1–14 | `calc/*` (WaybillCalcEngine, MultiDayPassengerCalc, WaybillMath, CoefficientCalculator, FuelNormCalculator, SequentialFuel) | ✓ (§5; B10 загейчен) |
| `Waybill2b/Fuel/*`, `Waybill5bbm/Fuel/*` (Labador/SelfUnload/Special/SpecialMover) | грузовое топливо по кузову | `WaybillCalcEngine.cargo` | ✓ (спот-чек веток — §5.2) |
| `Waybill2b/Report/*`, `Waybill5bbm/Report/*` | грузовые отчёты | `WaybillReportService` cargo | ✓ |
| `WaybillGeneralReport/*`, `WaybillCargoGeneralReport/*` (Trans/Count/Type2/CargoAttach/PessengerEmpty) | сводные Минтранса | `RegionalReportService` | ✓ |
| `Malumotnoma/MalumotnomaCalc` | цена справки | `MalumotnomaService` | ✓ |
| `Mobile/{AuthService,InspectionService,WaybillService,CargoWaybillService}` | телефон, присутствие, мобильный DTO | `MobileController` | ⏸ (присутствие/телефон) |
| `TrailerService.syncAndGetIdsTrailers` | прицепы 2-Б как сущности | trailer1/2 в Vehicle + typeData | ✓ |
| `Traits/BillNumberTrait, EBusTrait, MBusTrait, kvd/Bill*Trait` (boot: номер, bill_block водителя, calcFuel при сохранении) | нумерация + блокировка водителя + расчёт при сохранении | номер при READY, инвариант, `/calculation` | ✓ |
| `Traits/FilterTrait, CargoWaybills/CWControllerTrait` | фильтры списков | см. B | ~ |
| `Traits/RoleTrait` (hasAccessCompany/Region, hasBills, cargoWaybillHasRole…) | скоуп ролей | `TenantScope` | ✓ |
| `Traits/kvd/CheckFilesTrait` | файл существует до сохранения | multipart | ✓ (дизайн) |
| `Traits/Utils/{FuelSumUtils,TimeUtils}` | суммы топлива/времени | `CalcUtils` | ✓ |
| `Traits/Reports/General/{Base,Type1,Type2}` | report/main (роут закомментирован) | — | † |
| `Observers/DriverObserver` | авто-`number` водителя = max+1 по компании | tabNumber свободный | ✗ (авто-присвоение) |
| `Models/*` (77): Company related_companies (геозона), Driver.password/waybill_id/waybill_type/last_distance, Parking phone/imei, ParkingFuelLeft, Ticket1a/1ad/2b, Waybill3cRiport, Waybill1ade, Trailer, kvd/{Organization,Transport,Driver,DoctorMechanic,WaybillConfirm,WaybillWorkDay}, CompanyForApi, UserHasCompany, PhoneInfo, Rmalumotnoma, BrandType, TypeCompany, Ownership, TransportType, Report, Monster/Dummy/Product/Icon/Address/PostalBox | — | эквиваленты в §2 / ⏸ мобильные / † демо | см. §2 |

## F. Console / middleware / прочее

| Legacy | Что делает | e-Waybill | Ст. |
|---|---|---|---|
| `db:backup` ежедневно 00:00 + `find … -mtime +10 -delete` еженедельно (Kernel) | бэкап БД с ротацией 10 дней | `scripts/backup-postgres.ps1` + `/settings/backup` (вручную) | ~ (расписание/ротация — инфра) |
| `UpdateCompaniesFromJson`, `UpdateDriversFromJson` | разовый импорт из JSON | ETL `scripts/migration/*` + sync API | ✓ |
| `RefreshDb`, `CrudGenerator`, `Inspire`, `LockUserCommand` (на деле — разовая alter-миграция clients) | dev/разовое | — | † |
| middleware `Notification`: company + `status_lock` → баннер «оплатите услуги» и редирект (кроме маълумотнома); `license_has_expired` true → блок, число → предупреждение «через N дней» | блокировка кабинета/предупреждение | `Organization.blocked`+reason → 403 на выписку; `licenseTo` истёк → отказ при создании; expiry-монитор/уведомления | ~ (баннер-обратный отсчёт до истечения лицензии в кабинете — проверить) |
| `LockReportNotification`: отчёты недоступны 08–11 и 17–20 (кроме 11 user id) | костыль нагрузки | — | † → Вопрос 10 |
| `UserLock` (`password_locked` → принудительная смена) | смена пароля | Keycloak required action + временный пароль | ✓ |
| `CheckBills` (`exit` для company) | заглушка | — | † |
| `CheckIfAdmin`, `JWTMiddleware`, `CompanyJWTMiddleware`, `SmartCity`, `CompanyConf/DriverConf` | auth-плюмбинг | Keycloak/Spring Security | ✓ |
| Backpack `revisions`, `settings`, `permission` | аудит/настройки/права | audit_log, platform_settings, role_access | ✓ |
| Charts: `ebus-bill-counts` (кол-во ПЛ троллейбусов по месяцам, 6 мес), `ebus-pass-vol` (capacity×coe_use×avg_dist×laps по месяцам), `dashboard/ebus` | дашборд троллейбусов | `passenger-volume-trend` (bus/trolleybus) | ~ (счётчик ПЛ по месяцам) |
| Charts `users`, `new-entries`, Lines/Pies | демо Backpack | — | † |
| blades `report/main*`, `report/min`, `report/1a|1ada|1adt`, `report/type_N` (root) | нет роутов | — | † (проверено: view() только на custom/details/waybillcargo/waybillcargogeneral/malumotnoma) |
| `config/trans.php`: month, report.type/models/bill_type/report_type/report_main/type_calc/calc_pass/taxi_service_type, fuel.table_key (подписи столбцов топлива), bill_type_name, reportwaybillcargo.*, reportwaybillgeneral.* (must_give, transport_type_id, bill_type_plan 1..4, report_type ×8, титулы), auto_type, forecast.* | справочники-константы | i18n, ReportType, policy must_give, WaybillType map, RegionalReport | ✓ кроме bill_type_plan «5Б-БМ» (~) и forecast (Вопрос 5); подписи fuel.table_key — ⚙– |
