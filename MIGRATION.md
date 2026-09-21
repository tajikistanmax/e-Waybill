# MIGRATION.md — Перенос платформы 1-в-1: legacy «Роҳхат» → e-Waybill

> **Старое:** `D:\Projects\rohkhat.tj ralavel` (PHP/Laravel + Backpack), боевой экземпляр http://localhost:8000. Только для чтения.
> **Новое:** e-Waybill (Java/Spring Boot `master-data`:8081 / `waybill`:8082 + Next.js), стенд `epd-prod-*`.
> **Цель:** перенести ВСЮ логику 1-в-1 (поля, расчёты, отчёты, проверки). **Новое требование:** шаблоны/отчёты/настройки — конфигурируемые через UI, не хардкод.
> **Источники сверки:** `spec/notes/legacy-full-inventory.md` (атлас старого), `spec/notes/gap-plan.md`, `spec/notes/our-platform-inventory.md`, прямая проверка кода.
> Дата: 2026-09-21.

## Легенда
Покрытие: **[x]** перенесено 1-в-1 · **[~]** частично · **[ ]** гэп.
Конфигурируемость (⚙): **⚙+** уже редактируется админом через UI · **⚙~** частично · **⚙–** захардкожено (требует вынести в настройки/БД по новому требованию).
Каждый закрытый пункт: отметить `[x]`, приписать «как проверено», запись в `changelog/`, (коммит — когда будет git).

> **Git:** portable MinGit `%LOCALAPPDATA%\Programs\PortableGit\cmd\git.exe` (системного git нет). Вся работа — ветка **`migration`**; прямой push в `main` блокируется классификатором → `git push origin migration`, слияние в `main` делает владелец через PR. Каждый закрытый пункт = changelog + атомарный коммит + push.

---

## 1. Роли и права (12 ролей, 18 прав)

| # | Legacy | e-Waybill | Покр. | ⚙ | Прим. |
|---|---|---|---|---|---|
| 1.1 | 12 ролей spatie (superadmin/admin/company/region/client_forwarder/client_sender/mechanic/doctor/fuel_employee/employee_kassa/customs_officer/gps) | Роли Keycloak (`roles.ts`, `epd-realm.json`) | [~] | ⚙~ | Нет кабинетов `client_sender`/`client_forwarder`/`customs_officer`; `region`-пишущий отсутствует (надзор = аналитик). См. §Вопросы. |
| 1.2 | 18 прав (list/create/update/delete + bus/ebus/mbus/taxi/cargo2b/cargo5bbm/cargowaybill1_2/cmr/malumotnoma/ewaybill/debt/sectoral/public) | Скоуп типов ПЛ на орг (`available-types/org`) + роль-матрица | [x] | ⚙+ | Права-на-форму = разрешённые типы ПЛ у орг (редактируется). |
| 1.3 | **Хардкод user id** (137/480/330/177/205/490/1056/388/650/1156/210) для меню/прав | Нормальная матрица ролей + nav по роли | [x] | ⚙+ | Ключевой антипаттерн legacy устранён. **Проверить**, что все точки хардкода покрыты декларативно. |
| 1.4 | Сессионный скоуп (has_regions/has_companies/public/sectoral) в пропатченном vendor | `TenantScope` (по токену), региональный скоуп аналитика | [~] | ⚙~ | public/sectoral фильтр — проверить паритет. |

## 2. Справочники (~28) — Массивҳо / Сузишвори

| # | Legacy | e-Waybill | Покр. | ⚙ | Прим. |
|---|---|---|---|---|---|
| 2.1 | `regions` (7) | `region` (V62, code 1..7) | [x] | ⚙~ | CRUD-страница есть; проверить полноту полей. |
| 2.2 | `cities` (69) | `City` (V54, `CityController`) | [x] | ⚙+ | |
| 2.3 | `companies` | `Organization` | [~] | ⚙+ | **Сверено 21.09** (колонки companies↔organization): 1-в-1 покрыто rma/kpp/name/city/name_head/bank/address/phone/email/license_from-to/carrier_license/ownership/type_company/percent_income/cat_1-3/plan_pass_volume-traffic/lat-long/registration_cert(←reg_cert)/extract(←iktibos)/vat_cert(←aai)/blocked(←status_lock). **Гэпы полей:** `number`(рамз — внутр. код орг), `give_fuel`(флаг выдачи топлива), `seal_attach`(печать орг), `status`(семантика vs blocked/source) — см. Вопрос 8. attach-файлы → подсистема документов (organization_document V34). |
| 2.4 | `parkings` (ТС) | `Vehicle` | [~] | ⚙+ | **Сверено 21.09:** 1-в-1 (number→parking_number, ydak/ydak_2→trailer1/2, tech_inspection/expire_checklist→tech_inspection/control_card + itl→intl_control_card, indication_counter→odometer, transport_type, air_conditioner, vincode, certificate). **Гэпы:** `fuel_left_1..5`(остаток топлива на ТС — у нас через fuel_records ПЛ), `phone`; `imei`→mobile_device(V64). Наш богаче (insurance/adr/engine_power/fuel_type). |
| 2.5 | `brands` (~488) | `Brand` (V58/V29) | [~] | ⚙+ | **Сверено 21.09:** ядро 1-в-1 (fuel_100/fuel_100_dushanbe/fuel_hour/fuel_interior_heating/tariff_rate/cost_services/net_weight/capacity/carrying/model). **Гэпы:** `fuel_consumption1`(vs fuel_100 — уточнить), `fuel_id1`(деф. топливо марки), **`coe_conditioner`/`coe_sea`** — ⚠️ потенциально влияют на расчёт (коэф. кондиционера/места) → Вопрос 9. |
| 2.6 | `drivers` | `Driver` | [~] | ⚙+ | **Сверено 21.09:** 1-в-1 (number→tab_number, license→license_number, category→license_categories, талон-20ч→safety_course_number/valid_to, med_cert/rma/power_attorney/visa→visa_valid_to/contract→contract_valid_to, degree/passport/address/phone/email). **Гэпы:** `patent_valid_date`, `photo`, `signature_attach`, `debt`(долг водителя, §11.5). geo(lat/long)→GPS-слой. Наш богаче (birth_date/experience/adr/med_restrictions). |
| 2.7 | `routes` | `Route` (+коэф. V28) | [~] | ⚙+ | Свободные точки А/Б; структурного маршрута нет (B6). Проверить time_one_lap, valid_cert. |
| 2.8 | `route_types` (Намуди хатсайр) | `route_type` (V63) + `/dictionaries/route-types` | [x] | ⚙+ | |
| 2.9 | `directions` (Самт, топл. коэф.) | `Direction` (V56) | [x] | ⚙+ | Проверить связку winter/mountain/in_city_coef_id. |
| 2.10 | `clients` | `Client` (org-scoped) | [x] | ⚙+ | |
| 2.11 | `cargos` (Бор) | `Cargo` (V55) | [x] | ⚙+ | Проверить class/unit/price. |
| 2.12 | `tariffs` (Нархнома) | `Tariff`+`RouteTariff` | [x] | ⚙+ | |
| 2.13 | `employees` (врач/механик/…5 типов) | `Employee` (type) | [x] | ⚙+ | seal/signature — изображения. |
| 2.14 | `fuels_table` (виды топлива) | enum 1..5 + `FuelNorm` | [x] | ⚙~ | Виды статичны (enum). |
| 2.15 | 4 топл. коэф. (winter/city/mountain/used) | `FuelWinterCoef/CityCoef/MountainCoef/UsedCoef` | [x] | ⚙+ | Проверить period_from/to у зимнего. |
| 2.16 | `drive_classes` (Дараҷа) | `DriveClass` | [x] | ⚙+ | |
| 2.17 | `external_countries` (218) | classifier COUNTRY (V59, 218) | [x] | ⚙+ | |
| 2.18 | `external_cities` (~19k) | `external_city` (V60) | [x] | ⚙+ | Мигрировано 18120 (Ф1). |
| 2.19 | `malumotnomas` + `routemalumotnomas` | `Malumotnoma`+`MalumotnomaRoute` | [x] | ⚙+ | Поля 1:1. |
| 2.20 | `bill_types` (Намуди роҳхат) | `WaybillType` enum + `/settings/types` | [x] | ⚙+ | Скоуп на орг. |
| 2.21 | `waybill_plans` (Нақша) | `WaybillPlan` | [x] | ⚙+ | |
| 2.22 | `phone_infos` (устройства) | `MobileDevice` (V64) + `/registry/devices` | [x] | ⚙+ | Авто-заполнение мобайлом — этап 2. |
| 2.23 | `numberdriver`, `waybills`-реестр, `car` | — (не нужны при 100% электронном) | [x] | — | Сознательно не тащим. |

## 3. Формы путевых листов / борхаты

| # | Legacy форма | e-Waybill тип/шаблон | Покр. | ⚙ | Прим. |
|---|---|---|---|---|---|
| 3.1 | 1-АД Автобус | `WB_BUS` / `print/waybill1ad` | [x] | ⚙~ | Шаблон конфигурируем? см. §7. |
| 3.2 | 1-АД Троллейбус (ebus) | `WB_TROLLEYBUS` | [x] | ⚙~ | |
| 3.3 | 1-АД fe (топл. сотрудник) | кабинет `FUEL_STATION` | [x] | ⚙+ | |
| 3.4 | 1-А Микроавтобус | `WB_MINIBUS` / `print/waybill1a` | [x] | ⚙~ | |
| 3.5 | 3-С Сабукрав (такси/маршрут/почас.) | `WB_CAR`+`WB_TAXI` / `print/waybill3c` | [x] | ⚙~ | type_service 1/2/3. |
| 3.6 | 3-С «30» Душанбе | норматив (policy) | [x] | ⚙+ | Не отдельная форма. |
| 3.7 | 2-Б грузовой | `WB_TRUCK` / `print/waybill2b` | [x] | ⚙~ | |
| 3.8 | 4-МБМ | `WB_PAX_INTL` / `print/waybill4mbm` | [x] | ⚙~ | Бланк заведён (A1). |
| 3.9 | 5Б-БМ межд. | `WB_TRUCK_INTL` / `print/waybill5bbm` | [x] | ⚙~ | |
| 3.10 | СМР | `print/cmr` (PDF к 5Б-БМ) | [x] | ⚙~ | |
| 3.11 | Борхати замимаи 1/2 | `print/waybill2b-attachment` + consignment | [~] | ⚙~ | Форма/печать есть; кабинетов грузоотправителя/экспедитора нет (B1). |
| 3.12 | Маълумотнома | `print/malumotnoma`+QR | [x] | ⚙~ | Свериться с форматом гос-письма (ссылка на приказ-нархнома). |

## 4. Жизненный цикл ПЛ

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 4.1 | Статус вычисляется из полей (exit/entry_date, doctor_id, mechanic_id) | Явная статус-машина Т1–Т6 (`WaybillStatus`) | [x] | Улучшение сверх legacy (осознанно). |
| 4.2 | Создание (company/диспетчер): одометр, schedule, exit_date, fuel repeatable | `WaybillService.create` + typeData | [x] | Свериться по полям создания. |
| 4.3 | Подтверждение врач/механик (RMA / мобайл-инспекция) | DOCTOR/MECHANIC кабинеты + инспекция | [x] | |
| 4.4 | «е-роҳхат» (doctor_id && mechanic_id) | статус READY/ISSUED | [x] | |
| 4.5 | Закрытие: круги, work_time, entry_date, одометр возврата, earning, kassa | close-flow | [x] | Свериться по полям закрытия. |
| 4.6 | Печать/QR/оплата | print + QR verify + payment | [x] | |

## 5. Расчёты (топливо / показатели / зарплата / тариф) — КРИТИЧНО 1-в-1

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 5.1 | BusCalc/MBusCalc/TaxiCalc + Bus/BaseCalc | `calc/` пакет (пассажир.) | [x] | **Сверено + ИСПРАВЛЕНО 21.09.** bus/микроавтобус/маршрут — **1-в-1** (route_distance=(a+b)/2, gardishi=capacity·coe_use·dist·laps, miqdori=gardishi/avg_seat, gasht_musofir=dist·laps, gasht_hamagi=l_pass+begin_a+begin_b, спец bus+регион1→одометр). **Такси/почасовой — БЫЛ гэп (нули), исправлено:** `WaybillCalcAssembler` теперь для WB_TAXI/WB_CAR METER(1)/HOURLY(3) считает показатели спец-формулой (`MultiDayPassengerCalc.forTaxi`), одн​одневный ПЛ — синтез дня из шапки; `taxiServiceType` читает и `serviceKind`, и числовой `typeService` (мигрированные). B10-независимо (MULTIDAY_PASSENGER_ENABLED=false, топливо/маршрут/микроавтобус не тронуты). **Проверено вживую** (MG3C352: gashti_umumi=199, gasht_musofir=149.25). Тест `MultiDayPassengerCalcTest.meterSingleDayFromHeader`. ⚠️ Мигрированные ПЛ: `capacity` нет в снапшоте → miqdori/gardishi=0 (для боевых считается); опц. бэкфилл capacity в снапшоты. |
| 5.2 | Waybill2b/5bbm Fuel-сервисы (Labador/SelfUnload/Special/SpecialMover) | грузовой calc | [~] | **Коэффициент сверен 21.09** (`CargoFuelBase.php:163` ↔ `cargoCoefficient`): `K = winter + mountain(lookup) + city(lookup) + used`, без station/road — 1-в-1 (в отличие от пассажирского — lookup по id направления, не значения). Осталось: ветки формулы по типу кузова (Labador/SelfUnload/Special/SpecialMover) — спот-чек `WaybillCalcEngine.cargo` (bodyName-диспетч) vs legacy Fuel-сервисы. |
| 5.3 | Коэффициенты: зимний(период)/город/горы/износ/кондиционер/место/темп<0 | `CoefficientCalculator` | [x] | **Сверено 21.09 (дословно `helpers.php::fuel_calc_100`):** пассажир `K=(winter+mountain+station+city+used)−road_quality`, `множ=1+0.01·K`; mountain/city = ЗНАЧЕНИЯ маршрута (не id, квирк сохранён); износ инлайн 8л/150000км→10, 5л/100000км→5; грузовой K=winter+mountain+city+used (без station/road). Есть `passengerCoefficientLegacy` с точным legacy-условием зимы для историч. сверки. **Кондиционер (Вопрос 9 СНЯТ):** в legacy `cond_fuel=0` (баг приоритета `?:` — фактически не применяется) → отсутствие в e-Waybill = верный паритет. Осталось спот-чек: ветка Душанбе `excluding_coef` (fuel_100_dushanbe + additional_fuel_100/additional_fuel + cond_fuel + heating_fuel). |
| 5.4 | Многодневные mbus/taxi (посуточно, work_days JSON) | B10 (загейчен `MULTIDAY_PASSENGER_ENABLED=false`) | [~] | **Гэп точности** — по одобрению включить/доделать (per-day топливо). |
| 5.5 | Тарифы/заработок (price_per_1_mkm, percent_income, cat_1/2) | tariff calc | [x] | **Сверено 21.09: зарплата 1-в-1** (`MBusCalc.type5` ↔ `WaybillMath.driverSalary`): `((earning/4)·3)·percent_income + cat_{degree}`, порядок операций сохранён, degree→cat_1/2/3. Тариф (price_per_1_mkm/one_time) — `TariffMath`, свериться отдельно. |

## 6. Отчёты (3 группы, до 14 типов)

| # | Legacy | e-Waybill | Покр. | ⚙ | Прим. |
|---|---|---|---|---|---|
| 6.1 | Мусофирбарӣ (14 типов) | `ReportType` (13) + журналы механика/врача | [x] | ⚙– | **Сверено 21.09 (config/trans.php): все 14 типов покрыты** (1→BY_VEHICLE,2→BY_ROUTE,3→BY_BRAND,4→COMPANY_SUMMARY,5→DRIVER_SALARY,6→TRIP_INFO,7→REGISTRY_JOURNAL,8→BY_DRIVER,9→FUEL_GENERAL,10→FUEL_BY_WAYBILL,11→FUEL_BY_DRIVER,12→FUEL_BY_VEHICLE,13→journal/mechanic,14→journal/doctor). XLSX сверх эталона. Формат — захардкожен (⚙–, §7/§10). |
| 6.2 | Боркашонӣ (грузовые типы) | те же ReportType /cargo | [x] | ⚙– | **Сверено 21.09: все активные типы (1,6,7,8,9,10,11,12,13,14) покрыты.** Тип 8 «Самт» = `BY_ROUTE` (у грузовых `wb.route`=направление). Типы 2–5 в legacy закомментированы (не активны). |
| 6.3 | Умумӣ (сводный, регион, нормативы must_give 4/6/2/0) | `/regional`, `/waybill-norm`, trend | [x] | ⚙~ | Структурно покрыто (regional/count/norm/trend). Осталось: точная сверка transportation_industry/general по колонкам. |
| 6.4 | Маълумотнома | `MalumotnomaController` | [x] | ⚙~ | Контроллер есть (сверено 21.09). |
| 6.5 | Раздвоение топл. свода (тип 9/10) | FUEL_GENERAL(9) + **FUEL_BY_WAYBILL(10)** | [x] | ⚙– | **ЗАКРЫТО:** тип 10 — отдельный `FUEL_BY_WAYBILL` (построчно по ПЛ) рядом с FUEL_GENERAL (свод). A4 закрыт. |
| 6.6 | Прогнозы (Хатсайр/Минтақа/…) | — | [ ] | — | В legacy не выведены в UI. Нужность — §Вопросы. |

## 7. Шаблоны / печать — ГЛАВНЫЙ фокус конфигурируемости

| # | Legacy | e-Waybill | Покр. | ⚙ | Прим. |
|---|---|---|---|---|---|
| 7.1 | Печатные бланки — blade-шаблоны в коде (`resources/views/print/*`) | Thymeleaf `templates/print/*.html` | [x] | **⚙–** | Шаблоны в коде. **По новому требованию — сделать редактируемыми через админку** (хранить в БД/настройках). Крупный пункт. |
| 7.2 | Водяной знак / «Сформировано» | print-настройки (V61 show_watermark) | [x] | ⚙+ | Уже гейтится настройкой. |
| 7.3 | Названия полей/заголовки в бланках | захардкожены в шаблонах | [~] | ⚙– | Вынести подписи полей в настройки (i18n/конфиг полей). |
| 7.4 | Форматы отчётов | views | [~] | ⚙– | См. 6.1. |

## 8. Списки / фильтры (пользователь особо подчёркивал)

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 8.1 | Фильтры parking/driver/company/waybill (регион/город/корхона/период/активность) | реестры с фильтрами | [~] | Свериться по каждому списку; в legacy есть баги (Company «Шаҳр»→region_id) — НЕ переносить баг. |
| 8.2 | Каскад регион→город | отсутствует в legacy | [ ] | Реализовать корректно (улучшение с явной причиной — паритет намерения). |
| 8.3 | Фильтр по типу сотрудника | отсутствует в legacy | [ ] | Добавить (в legacy — пробел). |
| 8.4 | Серверная пагинация реестра ПЛ | добавлена 21.09 (архив вне дефолта + кэп) | [~] | Полноценную пагинацию — позже. |

## 9. API / интеграции

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 9.1 | Мобайл водителя (9 маршрутов, JWT) | `MobileController` | [~] | Свериться по эндпоинтам (профиль/лицензия/печати/геолокация). |
| 9.2 | `ref/*` интеграция компаний (~25, apiResource 6 форм) | приём ПЛ/агрегаторы | [~] | Свериться; часть — stub (единая платформа). |
| 9.3 | GPS smart-city (`gps/gps_data`) | GPS приём (`GpsController`) | [x] | |
| 9.4 | CMR API | СМР | [x] | |

## 10. Настройки / конфигурируемость (новое требование — сквозной пункт)

| # | Что в legacy захардкожено | e-Waybill | ⚙ | Прим. |
|---|---|---|---|---|
| 10.1 | `config/trans.php` (типы отчётов, коэф. планов, соответствия форм) | SettingsEditor по категориям | ⚙~ | Проверить, что все ключи trans.php вынесены в настройки. |
| 10.2 | settings-таблица = демо (реальных настроек нет) | `platform_settings` (V10) + SettingsEditor | ⚙+ | Инфраструктура есть. |
| 10.3 | Поля форм захардкожены | конструктор полей (V8 field_definitions) | ⚙+ | Работает end-to-end. **Проверить покрытие всех форм.** |
| 10.4 | Шаблоны печати в коде | Thymeleaf в коде | ⚙– | **7.1 — ключевой гэп конфигурируемости.** |
| 10.5 | Форматы отчётов в коде | views/сервисы | ⚙– | 6.1. |
| 10.6 | Нормативы (must_give), нумерация, статусы-названия | policy / numbering / classifier WAYBILL_STATUS | ⚙+ | Уже редактируемо. |

## 11. Прочие фичи

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 11.1 | ЭЦП = картинки печати/подписи (крипто нет) | StubTitleSigner → CAdES (внешне-заблок.) | [x] | Наше сильнее; боевая ЭЦП ждёт УЦ. |
| 11.2 | QR публичная проверка (type 1..8) | QR verify + public portal | [x] | |
| 11.3 | Блокировки/уведомления (status_lock, license_expired, password_lock) | maintenance/blocked + expiry monitor | [~] | Свериться по триггерам. |
| 11.4 | Аудит (create/update_user_id, revisions) | audit_log (V6) | [x] | |
| 11.5 | Долг/оплата (debt, {id}/pay) | payment flow | [~] | Свериться по механике оплаты/долга. |

---

## Порядок работы (приоритет)
Большинство пунктов уже **[x]** (e-Waybill — зрелый порт). Реальная работа = (A) **верификация 1-в-1 тестами** там, где [x] стоит «по коду», и (B) закрытие **[~]/[ ]** и **⚙–**. Приоритет:
1. **Расчёты (§5)** — тесты старое-vs-новое (критично для точности): 5.1, 5.2, 5.3, 5.5.
2. **Конфигурируемость шаблонов (7.1/10.4)** — главный смысл новой версии.
3. **Отчёты (§6)** — паритет типов + конфигурируемость формата (6.1/6.5).
4. **Поля форм/справочников** — доперенос полей (2.3/2.4/2.6/2.7 «свериться»).
5. **Фильтры (§8)** — паритет + каскад/тип сотрудника (без переноса багов).
6. **Кабинеты/роли (§1, 3.11)** — client_sender/forwarder/customs (B1/B2) — продуктовое.
7. **API (§9)** — сверка мобайла/ref.

---

## Вопросы к Максу
1. **Конфигурируемость шаблонов печати (7.1):** делать полноценный редактор бланков в админке (WYSIWYG/HTML с плейсхолдерами) — большой объём. Подтвердить приоритет и уровень (полный редактор vs. параметры/подписи полей)?
2. **Кабинеты грузоотправителя/экспедитора/таможенника (1.1, 3.11):** заводить как в legacy (client_sender/forwarder/customs_officer) — это оргмодель с внешними пользователями. Нужны в Этапе 1?
3. **Регионально-пишущий админ (`region`):** в legacy регион ВЕДЁТ справочники своего региона; у нас надзор = аналитик (чтение). Децентрализовать ведение?
4. **B10 (посуточный расчёт многодневных ПЛ, 5.4):** включать/доделывать? Сейчас загейчен (расчёт как в одн​одневном приближении).
5. **Прогнозы (6.6):** в legacy не выведены в UI — нужны ли в новой?
6. **Раздвоение топл. свода 9/10 (6.5):** нужна ли вторая презентация отдельным типом?
7. ~~**git:** установить git в окружении~~ — **СНЯТ 21.09:** portable MinGit есть, ветка `migration` создана, коммиты и push идут. Осталось от владельца: слияние `migration` → `main` через PR (прямой push в main блокируется).
8. **Поля organization (2.3):** нужны ли на боевых бланках/в отчётах: `number`(внутр. рамз орг), `give_fuel`(флаг выдачи топлива компанией), `seal_attach`(изображение печати орг)? Если да — добавить в `Organization` (и в конструктор полей / print-настройки). Пока безопасно: не добавляю (рамз = rma; печать — на уровне сотрудника/бренда).
9. ~~**Марка `coe_conditioner`/`coe_sea` (2.5)**~~ — **СНЯТ 21.09:** проверено по `helpers.php::fuel_calc_100` — кондиционерный расход в legacy битый (`cond_fuel=0`, приоритет `?:`), т.е. `coe_conditioner` в топливе фактически НЕ применяется. Отсутствие в e-Waybill — верный паритет, доносить не нужно. `coe_sea` — аналогично проверить при спот-чеке показателей (в mBus не встречается).

---

## Прогресс верификации (лог)
- **2026-09-21** — Создан MIGRATION.md (шаг 1). Сверено **2.3 organization**: поля 1-в-1 покрыты (см. строку), найдены 4 гэпа-поля (number/give_fuel/seal_attach/status) → Вопрос 8. Оба стенда подняты (legacy :8000, e-Waybill :8082) — доступны как эталон/цель.
- **2026-09-21** — §5 расчёты, сверка формул (`app/Services/Calc/{MBusCalc,Calc}.php` ↔ `WaybillCalcEngine`/`WaybillMath`):
  - **§5.5 зарплата — 1-в-1 ✅** `((earning/4)·3)·percent_income + cat_{degree}`.
  - **§5.1 пассажир bus/микроавтобус/маршрут — 1-в-1 ✅** (gardishi/miqdori/gasht_musofir/gasht_hamagi/route_distance, спец-случай bus+регион1).
  - **§5.1 такси/почасовой — ГЭП ⚠️**: формула в `MultiDayPassengerCalc` (загейчена B10), в живом однодневном пути такси → нули. **Actionable** (вынести taxi/hourly-показатели независимо от B10). НЕ правлю сейчас — пересекается с held B10 (Вопрос 4), безопасный вариант = зафиксировать.
  - Подтверждённые формулы залочены существующими 119 расчётными тестами e-Waybill (зелёные). Отдельный старое-vs-новое тест для таксишного гэпа — при доработке.
- **2026-09-21** — Сверка полей реестров **2.4 ТС / 2.5 марка / 2.6 водитель** (колонки БД обоих стендов): ядро 1-в-1 подтверждено; гэпы-поля зафиксированы в строках (ТС: fuel_left/phone; водитель: patent/photo/signature/debt; марка: coe_conditioner/coe_sea/fuel_id1 → Вопрос 9). Все три → `[~]` (минорные гэпы, ядро перенесено).
- **2026-09-21** — **§5.1 такси/почасовой — ИСПРАВЛЕНО и задеплоено** (перенос 1-в-1, B10-независимо; тест + live-проверка MG3C352; changelog `2026-09-21-показатели-такси…`).
- **2026-09-21** — **§5.3 коэффициенты — 1-в-1 ✅** (`CoefficientCalculator` дословно = `helpers.php::fuel_calc_100`: K-сумма, износ инлайн, значения-не-id, legacy-вариант зимы). **Вопрос 9 СНЯТ** (кондиционер в legacy `cond_fuel=0`, не применяется). Спот-чек Душанбе-ветки (`excluding_coef`) — в очереди.
- **2026-09-21** — **§5.2 грузовой коэффициент — 1-в-1 ✅** (`CargoFuelBase.php:163` ↔ `cargoCoefficient`: winter+mountain(lookup)+city(lookup)+used, без station/road). Ветки по типу кузова — спот-чек в очереди.
- **2026-09-21** — Опц. бэкфилл `capacity` в снапшоты мигрированных ПЛ (для историч. таксишных показателей): масс-UPDATE 2.28М заблокирован классификатором у агента; SQL готов (export masterdata.vehicle → stg_veh_cap → UPDATE waybill), выполнить вручную при желании. Для боевых ПЛ таксишный фикс работает без этого.
- **2026-09-21** — **Шаг 0 промпта (санитария git) — ВЫПОЛНЕН.** Ветка `migration` от `main` 29b25e8; рабочее дерево 21.09 разложено на 9 атомарных коммитов: `5f20557` артефакты сборки вне индекса · `b509a65`/`d62f901` восстановление сборки после слияния 14.09 (бэкенд/web) · `47e229f` гейт B10 · `78d1076` показатели такси · `4a819f4` ETL Ф5 · `315c2a5` реестр ПЛ (архив вне дефолта + кэп) · `112ca7e` пагинация реестров web · `561230d` web на :3000. **Базовая линия тестов:** master-data **62/62 ✅**; waybill **222: 220 ✅ + 2 `initializationError`** (`WaybillLifecycleIntegrationTest`, `WaybillConcurrencyIntegrationTest` — Testcontainers не стартует под Docker Desktop for Windows (docker-java через проксированный сокет), известное ограничение окружения, не регрессия; прогонять в Linux CI); web `tsc --noEmit` ✅. Стенды: legacy :8000 UP (боевая MySQL), e-Waybill :8081/:8082/:3000 UP, rohkhat-v2 :8080 UP. Диск C: 5 ГБ свободно — build-cache Docker очищен (21 ГБ), следить.
- **2026-09-21** — **§6 отчёты — сверка ПОЛНАЯ (config/trans.php ↔ ReportType/ReportController).** Все отчёты старой платформы перенесены: Мусофирбарӣ 14/14, Боркашонӣ все активные (тип 8 «Самт»=BY_ROUTE), Умумӣ (regional/count/norm/trend), журналы механик/врач, Маълумотнома. Прогнозы — в legacy не в UI, не гэп. Осталось: (а) опц. отдельный ярлык `BY_DIRECTION` для грузового «Самт»; (б) поколоночная сверка Умумӣ (industry/general); (в) конфигурируемость формата (§7/§10). Отчёты как ТИПЫ — 100% покрыты.
