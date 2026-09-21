# MIGRATION.md — Перенос платформы 1-в-1: legacy «Роҳхат» → e-Waybill

> **Старое:** `D:\Projects\rohkhat.tj ralavel` (PHP/Laravel + Backpack), боевой экземпляр http://localhost:8000. Только для чтения.
> **Новое:** e-Waybill (Java/Spring Boot `master-data`:8081 / `waybill`:8082 + Next.js), стенд `epd-prod-*`.
> **Цель:** перенести ВСЮ логику 1-в-1 (поля, расчёты, отчёты, проверки). **Новое требование:** шаблоны/отчёты/настройки — конфигурируемые через UI, не хардкод.
> **Источники сверки:** `spec/notes/legacy-full-inventory.md` (атлас старого), `spec/notes/gap-plan.md`, `spec/notes/our-platform-inventory.md`, **`spec/notes/07-механическая-инвентаризация-legacy.md`** (Шаг 1: механическая карта маршрут/поле/фильтр/правило → аналог, 21.09), прямая проверка кода.
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
| 1.5 | `LockReportNotification` — отчёты недоступны 08:00–11:00 и 17:00–20:00 (кроме 11 захардкоженных user id) | — | [x] | — | **Не переносить**: костыль нагрузки, не бизнес-правило (при необходимости — настройка «окно недоступности»). Вопрос 10. |
| 1.6 | `UserLock`/`password_locked` (принудительная смена пароля), `users.is_blocked` | Keycloak required action + временный пароль 1 раз; org-users `enabled=false` | [x] | ⚙+ | Проверено при провижининге (Фаза 2). |

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
| 2.24 | `clients.type` (намуди мизоҷ) + банковские реквизиты `riam, rma, account, correspondence_account, mfo, bank_name` | `Client`: только number/name/address/phone | [~] | ⚙+ | **Гэп полей (Шаг 1):** нужны сторонам борхата/СМР (отправитель/получатель/экспедитор). Доперенести 7 полей + форма + печать. |
| 2.25 | `cargos.number` (рақами бор) | `Cargo` без number | [~] | ⚙+ | Доперенести. |
| 2.26 | `tariffs.adv_coe` (коэффициенти иловагӣ) | `RouteTariff` без adv_coe | [~] | ⚙+ | Доперенести; сверить, где legacy его применяет (`TariffMath`). |
| 2.27 | `employees.type` 1..5 (врач/механик/диспетчер/**топливо**/**касса**); `seal`, `signature` (картинки) | `Employee.type` 1..3; картинок нет | [~] | ⚙+ | Типы 4/5 добавить (кабинеты FUEL_STATION/касса есть, тип сотрудника — нет). Картинки печати/подписи → Вопрос 8 (у нас ЭП+QR). |
| 2.28 | `routes`: `time_one_lap_a/b`, `valid_cert`, `desc`, `city`, `latitude/longitude`, `week_days_earnings` | `Route` (V28) без них | [~] | ⚙+ | time_one_lap/valid_cert/desc/city/lat-long — доперенести. `week_days_earnings` в расчётах и отчётах legacy НЕ используется (только модель+форма) → Вопрос 14. |
| 2.29 | `waybill_plans.type` = 4 «Шакли 5Б-БМ» | planKind PASSENGER/TAXI/CARGO | [~] | ⚙+ | Добавить вид плана для 5Б-БМ (и грузовой сводный по нему). |
| 2.30 | `parkings.timesheet` (pivot: несколько водителей на ТС) | `Driver.assignedVehicleId` | [x] | ⚙+ | Эквивалентно (много водителей → одно ТС). |
| 2.31 | `company_has_relatedcompany` (геозона «родственных» предприятий для присутствия), `user_clients`, `company_for_api`, `employee_has_user` | ⏸ / Вопрос 2 / Keycloak clients / org-users | [x] | — | related — вместе с мобильным присутствием (9.6); остальное — эквиваленты. |

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
| 3.13 | Скрытые поля форм `tbl`, `t_type`, `exit_date_h`, `d_fuel_rows`, `max_counter_value` | — | [x] | — | Плюмбинг Backpack-формы — не переносить; `max_counter_value` (650/750) = лимит суточного пробега → §12.5. |
| 3.14 | 4-МБМ: `timesheet_second`, `start/end_route_name`, `number_passengers`, `route_distance` | `waybill4mbm.html` + typeData | [~] | ⚙~ | Проверить, что 4 поля есть в форме WB_PAX_INTL и печатаются. |
| 3.15 | 5Б-БМ `arrival_time` (прибытие, отдельно от возврата); СМР `reis_amount`, `customs_officer_id` | typeData / `print-cmr.pdf` | [~] | ⚙~ | arrival_time/reis_amount — проверить; таможенник — Вопрос 2. |
| 3.16 | Кнопка «import» в списках 1-АД | — | [x] | — | В legacy закомментирована (`Waybill1adCrudController:1103`) — мёртвая. |

## 4. Жизненный цикл ПЛ

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 4.1 | Статус вычисляется из полей (exit/entry_date, doctor_id, mechanic_id) | Явная статус-машина Т1–Т6 (`WaybillStatus`) | [x] | Улучшение сверх legacy (осознанно). |
| 4.2 | Создание (company/диспетчер): одометр, schedule, exit_date, fuel repeatable | `WaybillService.create` + typeData | [x] | Свериться по полям создания. |
| 4.3 | Подтверждение врач/механик (RMA / мобайл-инспекция) | DOCTOR/MECHANIC кабинеты + инспекция | [x] | |
| 4.4 | «е-роҳхат» (doctor_id && mechanic_id) | статус READY/ISSUED | [x] | |
| 4.5 | Закрытие: круги, work_time, entry_date, одометр возврата, earning, kassa | close-flow | [x] | Свериться по полям закрытия. |
| 4.6 | Печать/QR/оплата | print + QR verify + payment | [x] | |
| 4.7 | 3-С: действие `pay` — кассир (роль `employee_kassa`) отмечает сдачу выручки (`employee_kassa_id`, колонка списка «Пардохти маблағ») | нет | [ ] | Признак «выручка сдана в кассу» (кто/когда) на ПЛ; роль — ACCOUNTANT или тип сотрудника «касса» (2.27). Вопрос 15. |
| 4.8 | Автоподстановка одометра выезда из `parkings.indication_counter` (`api/parking_indication_counter`) | `WaybillService.activate`: при пустом odometerExit берётся `vehicleSnapshot.odometer` (последний пробег ТС); явное значение меньше последнего → 422 (антифрод); ТС обновляется `PATCH /vehicles/{id}/odometer` | [x] | **Сверено 21.09 по коду** (`WaybillService.activate:1120–1144`): паритет legacy + строже (непрерывность одометра). |
| 4.9 | Автоподстановка «Бақияи пеш аз баромад» = `remain_fuel_entry` ПРЕДЫДУЩЕГО ПЛ того же ТС по виду топлива (`parking_fuel_left`, `ref/remain_fuel`) и «Дода шавад» (`be_given`) из предыдущего ПЛ | `GET /waybills/{id}/fuel-prefill?fuelType=` (`FuelPrefillService`, `FuelRecordRepository.findLatestForVehicle`); формы топлива карточки ПЛ и `/fuel` подставляют значения при выборе вида топлива + подсказка «из ПЛ №…» | [x] | **ЗАКРЫТО 21.09.** Как проверено: `FuelPrefillServiceTest` (3 кейса), `:waybill-service:test`, `tsc` ✅; **live** ТС 0114TJ01: после записи 26.18/85 в ПЛ 0000128-4 префилл для следующих ПЛ того же ТС вернул remainBeforeExit=26.18, beGiven=85, source=предыдущий №; для другого вида топлива — found=false; собственные записи листа исключаются. Отличие: «предыдущий» = самая свежая запись по госномеру (legacy — по id/parking_id). Changelog `2026-09-21-автоподстановка-остатка-топлива-из-предыдущего-ПЛ.md`. |
| 4.10 | Барьер присутствия водителя (`location_updated_at` < 5 мин) перед подтверждением врача/механика (`InspectionService`) | нет | ⏸ | Мобильное — отложено владельцем. |
| 4.11 | «Просрочен и не обработан»: 1-А 5 дн., 3-С 8 дн., 2-Б 16 дн., 5Б-БМ — без срока (фильтр `valid_date`) | `LifecycleScheduler` → EXPIRED по `validTo` (policy `max_validity_days` по типу) + грейс 24 ч | [x] | ⚙+ Сверить значения политики по типам с 5/8/16/∞. |

## 5. Расчёты (топливо / показатели / зарплата / тариф) — КРИТИЧНО 1-в-1

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 5.1 | BusCalc/MBusCalc/TaxiCalc + Bus/BaseCalc | `calc/` пакет (пассажир.) | [x] | **Сверено + ИСПРАВЛЕНО 21.09.** bus/микроавтобус/маршрут — **1-в-1** (route_distance=(a+b)/2, gardishi=capacity·coe_use·dist·laps, miqdori=gardishi/avg_seat, gasht_musofir=dist·laps, gasht_hamagi=l_pass+begin_a+begin_b, спец bus+регион1→одометр). **Такси/почасовой — БЫЛ гэп (нули), исправлено:** `WaybillCalcAssembler` теперь для WB_TAXI/WB_CAR METER(1)/HOURLY(3) считает показатели спец-формулой (`MultiDayPassengerCalc.forTaxi`), одн​одневный ПЛ — синтез дня из шапки; `taxiServiceType` читает и `serviceKind`, и числовой `typeService` (мигрированные). B10-независимо (MULTIDAY_PASSENGER_ENABLED=false, топливо/маршрут/микроавтобус не тронуты). **Проверено вживую** (MG3C352: gashti_umumi=199, gasht_musofir=149.25). Тест `MultiDayPassengerCalcTest.meterSingleDayFromHeader`. ⚠️ Мигрированные ПЛ: `capacity` нет в снапшоте → miqdori/gardishi=0 (для боевых считается); опц. бэкфилл capacity в снапшоты. |
| 5.2 | Waybill2b/5bbm Fuel-сервисы (Labador/SelfUnload/Special/SpecialMover) | грузовой calc | [x] | **ЗАКРЫТО 21.09.** Коэффициент `K = winter + mountain(lookup) + city(lookup) + used` (`CargoFuelBase.php:163` ↔ `cargoCoefficient`) — 1-в-1. Ветки по кузову (1/2/4 бортовой, 3 самосвал, 5 спец, 8 спецтягач; 5-й символ кода > 0 → прицеп) ↔ `WaybillCalcEngine.cargo`/`FuelNormCalculator` — 1-в-1 (залочено `WaybillCalcEngineCargoTest`). **Найден и закрыт гэп входов:** без тела `Supplement` направление (`typeData.directionId` → Direction.winter/mountain/inCity coef id, как `$waybill2b->direction`) и прицеп (`vehicleSnapshot.trailer1Weight/trailer1Carrying/trailer2Weight`, как `parkings.weight_ydak/carrying_ydak`) до движка не доходили → K = только износ, надбавка за прицеп 0. Теперь берутся из ПЛ, `Supplement` переопределяет. Как проверено: `WaybillCalcAssemblerCargoTest` (3), `:waybill-service:test` 242 (240 ✅ + 2 Testcontainers-окружение); **live** ПЛ 5555TT01 (направление 3: горы 20 + город 5, зима вне сезона) → K=25 ×1.25, норма 99.25 л (до правки 79.4); ПЛ 5556TT01 (марка 10001 + прицеп 4 т из снимка ТС) → hasTrailer, норма 102.15 = 91.75 + 10.4. Changelog `2026-09-21-грузовой-расчёт-направление-и-прицеп-из-ПЛ.md`. **Расхождения зафиксированы, не перенесены:** неизвестный код кузова — legacy не считает, у нас бортовая формула + предупреждение (Вопрос 18); P/Z в legacy собираются из строк борхата, у нас вводятся при возврате (автосбор — с 3.11); посуточная цепочка остатка 2-Б — как B10 (Вопрос 4). |
| 5.3 | Коэффициенты: зимний(период)/город/горы/износ/кондиционер/место/темп<0 | `CoefficientCalculator` | [x] | **Сверено 21.09 (дословно `helpers.php::fuel_calc_100`):** пассажир `K=(winter+mountain+station+city+used)−road_quality`, `множ=1+0.01·K`; mountain/city = ЗНАЧЕНИЯ маршрута (не id, квирк сохранён); износ инлайн 8л/150000км→10, 5л/100000км→5; грузовой K=winter+mountain+city+used (без station/road). Есть `passengerCoefficientLegacy` с точным legacy-условием зимы для историч. сверки. **ЗАКРЫТО 21.09 (спот-чек ветки Душанбе):** ветка А `excluding_coef` — 1-в-1 (`fuel_100_dushanbe`, `0.01·(fuel + additional_fuel_100 + additional_fuel)·L + cond_fuel + heating_fuel`, без K; тест `WaybillCalcEngineTest.excludingCoefBranch` = 64 л = ручной расчёт по `helpers.php:318`). Ветка Б — `0.01·(fuel + additional_fuel_100)·L·(1+0.01K)` 1-в-1 (`normal` = 68.82). **Найдено и исправлено расхождение чисел:** отопление салона (`brands.fuel_interior_heating × часы`) в legacy закомментировано и = 0 всегда, в e-Waybill применялось круглый год (4 боевые марки: МАЗ 103, ЛиАЗ 5292, Акиа Granbird, id 16 — +16…24 л за смену даже летом). Теперь режим `epd.calc.interior-heating` = OFF (паритет) / WINTER / ALWAYS — Вопрос 19. Как проверено: 4 теста режимов, suite 246 (244 ✅ + 2 окружение), **live** один ПЛ 0114TJ01 «Акиа» 200 км/8 ч: до 88.0 л → после 68.0 л (= legacy). Changelog `2026-09-21-отопление-салона-режим-по-умолчанию-как-legacy.md`. **Кондиционер (Вопрос 9, уточнено):** в legacy НЕ 0, а `cond_h/work_h` — безразмерная доля ≤ 1 «л» (баг приоритета `?:`, `helpers.php:379`); e-Waybill применяет корректную формулу из закомментированной строки 378 — баг не переносится, legacy-вариант сохранён как `conditionerFuelLegacy`. |
| 5.4 | Многодневные mbus/taxi (посуточно, work_days JSON) | B10 (загейчен `MULTIDAY_PASSENGER_ENABLED=false`) | [~] | **Гэп точности** — по одобрению включить/доделать (per-day топливо). **Заметка 21.09 (при спот-чеке 5.3):** посуточные ветки legacy (`MBusTrait::calcFuel`, `kvd/Bill3c1aTrait::calcFuel`) выбирают норматив НЕ по `route.excluding_coef`, а по `company.region_id == 1` (→ `fuel_100_dushanbe`), коэффициент = `excluding_coef ? 1 : getCoef(...)` (в kvd ещё и при `route == null`), без `additional_fuel_100`/кондиционера/отопления — т.е. отличается от однодневного `fuel_calc_100`. При включении B10 воспроизводить именно это (или согласовать с владельцем, что верно). |
| 5.5 | Тарифы/заработок (price_per_1_mkm, percent_income, cat_1/2) | tariff calc | [x] | **Сверено 21.09: зарплата 1-в-1** (`MBusCalc.type5` ↔ `WaybillMath.driverSalary`): `((earning/4)·3)·percent_income + cat_{degree}`, порядок операций сохранён, degree→cat_1/2/3. **Тариф сверен 21.09:** в legacy `tariffs.price_per_1_mkm/price_one_time` живут только в админ-CRUD (`TariffCrudController`) — ни расчёта, ни отчёта, ни blade их не используют (grep по `app/` и `resources/`); `brands.cost_services` — тоже только поле. e-Waybill: справочник есть (`legacy-ref/route-tariffs`, в данных 0 строк) + `TariffMath` (расширение, в Javadoc помечено как расхождение). Переносить нечего — паритет по факту; расширение безвредно (при пустом тарифе — 0). |
| 5.6 | Топливная строка: `be_given` (норма к выдаче), `additional` (харҷи иловагӣ 0..5 л), `coef_below_0` (коэф. темп. ниже 0) — входят в `fuel_calc` | `fuel_record` +`coef_below_0`/`be_given` (V24); `WaybillCalcAssembler.toCalcFuelLines` передаёт coef_below_0 и additional_given в движок; поля в API/формах карточки ПЛ и `/fuel` | [x] | **ЗАКРЫТО 21.09.** Как проверено: `WaybillCalcAssemblerFuelLinesTest` (маппинг), `WaybillCalcEngineTest.coefBelow0AndAdditionalAffectGivenAndRemain` (эталон legacy `fuel_calc`: 80+2=82; остаток 10+82+3−68.82=26.18), `:waybill-service:test` 225 (223 ✅ + 2 Testcontainers-окружение), `tsc` ✅; **live** ПЛ 01-26-04-0000130-0: запись coefBelow0=2/additionalGiven=3/beGiven=85 → `/calculation` given=82, additional=3, remainEntry=95 (пробег 0 → норма 0); отрицательное → 422. Changelog `2026-09-21-топливная-строка-coef-below-0-be-given.md`. |
| 5.7 | 3-С «30» Душанбе (`waybill3c30`, `getCompaniesDushanbe()` — 20 захардкоженных id компаний) | policy по организации | [x] | Хардкод заменён политикой (3.6). |

## 6. Отчёты (3 группы, до 14 типов)

| # | Legacy | e-Waybill | Покр. | ⚙ | Прим. |
|---|---|---|---|---|---|
| 6.1 | Мусофирбарӣ (14 типов) | `ReportType` (13) + журналы механика/врача | [x] | ⚙– | **Сверено 21.09 (config/trans.php): все 14 типов покрыты** (1→BY_VEHICLE,2→BY_ROUTE,3→BY_BRAND,4→COMPANY_SUMMARY,5→DRIVER_SALARY,6→TRIP_INFO,7→REGISTRY_JOURNAL,8→BY_DRIVER,9→FUEL_GENERAL,10→FUEL_BY_WAYBILL,11→FUEL_BY_DRIVER,12→FUEL_BY_VEHICLE,13→journal/mechanic,14→journal/doctor). XLSX сверх эталона. Формат — захардкожен (⚙–, §7/§10). |
| 6.2 | Боркашонӣ (грузовые типы) | те же ReportType /cargo | [x] | ⚙– | **Сверено 21.09: все активные типы (1,6,7,8,9,10,11,12,13,14) покрыты.** Тип 8 «Самт» = `BY_ROUTE` (у грузовых `wb.route`=направление). Типы 2–5 в legacy закомментированы (не активны). |
| 6.3 | Умумӣ (сводный, регион, нормативы must_give 4/6/2/0) | `/regional`, `/waybill-norm`, trend | [x] | ⚙~ | Структурно покрыто (regional/count/norm/trend). Осталось: точная сверка transportation_industry/general по колонкам. |
| 6.4 | Маълумотнома | `MalumotnomaController` | [x] | ⚙~ | Контроллер есть (сверено 21.09). |
| 6.5 | Раздвоение топл. свода (тип 9/10) | FUEL_GENERAL(9) + **FUEL_BY_WAYBILL(10)** | [x] | ⚙– | **ЗАКРЫТО:** тип 10 — отдельный `FUEL_BY_WAYBILL` (построчно по ПЛ) рядом с FUEL_GENERAL (свод). A4 закрыт. |
| 6.6 | Прогнозы (Хатсайр/Минтақа/…) | — | [ ] | — | В legacy не выведены в UI. Нужность — §Вопросы. |
| 6.7 | `report_details` — детализация типа 10 (топливо) по **одному ТС** (`parking_id`) | типовые отчёты без фильтра по ТС/водителю | [~] | ⚙– | Добавить параметры `vehicleRegNumber`/`driverRma` в `/reports/passenger|cargo` (+UI). |
| 6.8 | Дашборд троллейбусов (`dashboard/ebus`): кол-во ПЛ по месяцам (6 мес) + пассажирооборот | `passenger-volume-trend` (bus/trolleybus) | [~] | ⚙~ | Добавить счётчик ПЛ по месяцам (по типу) в тренд/дашборд. |
| 6.9 | Форма отчёта: bill (bus/ebus/mbus/taxi), тип, период, компания (обязательна; регион выбирает ajax), regions[] для сводного | `/reports/sections`, `/reports/regional` | [x] | ⚙– | — |
| 6.10 | Blades `report/main*`, `report/min`, `report/1a|1ada|1adt`, корневые `report/type_N` | — | [x] | — | Мёртвые: нет роутов (`view()` только custom/details/waybillcargo/waybillcargogeneral/malumotnoma). Не переносить. |

## 7. Шаблоны / печать — ГЛАВНЫЙ фокус конфигурируемости

| # | Legacy | e-Waybill | Покр. | ⚙ | Прим. |
|---|---|---|---|---|---|
| 7.1 | Печатные бланки — blade-шаблоны в коде (`resources/views/print/*`) | Thymeleaf `templates/print/*.html` | [x] | **⚙–** | Шаблоны в коде. **По новому требованию — сделать редактируемыми через админку** (хранить в БД/настройках). Крупный пункт. |
| 7.2 | Водяной знак / «Сформировано» | print-настройки (V61 show_watermark) | [x] | ⚙+ | Уже гейтится настройкой. |
| 7.3 | Названия полей/заголовки в бланках | захардкожены в шаблонах | [~] | ⚙– | Вынести подписи полей в настройки (i18n/конфиг полей). |
| 7.4 | Форматы отчётов | views | [~] | ⚙– | См. 6.1. |
| 7.5 | 26 мобильных HTML-бланков (`mobile/*`, `back_list`, `pcversion`) + `config/mobile_*` | JSON DTO + серверный PDF | [x] | — | Дизайн-решение: мобильный клиент рисует сам / открывает PDF. |
| 7.6 | Подписи столбцов топлива `trans.fuel.table_key` (Дода шуд / Баромад / Меъер / Даромад / Дода шавад) | захардкожены в шаблонах | [~] | ⚙– | Вместе с 7.3 — вынести в настройки. |

## 8. Списки / фильтры (пользователь особо подчёркивал)

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 8.1 | Списки ПЛ (все формы): компания (ajax), период `from_to` (created_at), «просрочен и не обработан» (`valid_date`), «е-роҳхат» (врач+механик), 3-С `type_service`; company-роль: `parking_id` (select), `timesheet_id` (select), период; поиск по «Рамз» | `/waybills`: статус, тип, компания, период, текстовый поиск (№/ТС/водитель/орг) | [~] | **Сверено 21.09 (механически).** Гэпы: **фильтр по виду обслуживания 3-С**; **select-фильтры ТС и водитель** (сейчас только текст). «Просрочен» = статус EXPIRED ✓; «е-роҳхат» = статус ≥ READY ✓. В legacy есть баги (Company «Шаҳр»→region_id) — НЕ переносить. |
| 8.2 | Каскад регион→город→компания | `RegistryView`: region/city/org — независимые | [~] | Сделать каскад (в legacy тоже не каскад; паритет намерения). |
| 8.3 | Фильтр по типу сотрудника | `posFilter` в реестре сотрудников | [x] | Есть. |
| 8.4 | Серверная пагинация реестра ПЛ | архив вне дефолта + кэп 1000 (21.09); реестры ТС/водителей — клиентская по 20 | [~] | Полноценная серверная пагинация — позже. |
| 8.5 | Реестр ТС: `active_trans/inactive_trans` (ТС с/без ПЛ 3-С за период), `active2b/inactive2b` (2-Б), `period_trans` (год выпуска от–до), «4-роҳхат(3с)» / «2-роҳхат(2b)» (ТС ровно с 4 / 2 ПЛ за период — контроль норматива выдачи) | нет | [ ] | **Гэп (Шаг 1).** Фильтры реестра ТС; бэкенд — агрегат по waybill за период (waybill-service) + год выпуска (master-data). |
| 8.6 | Реестр водителей: `inactive_drivers` (без 3-С за период), `active_drivers{тип}` (с ПЛ типа за период), поиск по RMA | RMA — через `q` ✓ | [~] | Фильтры активности за период ✗. |
| 8.7 | Экспорт списков (`enableExportButtons`: Excel/CSV/PDF/print): ТС, водители, сотрудники, направления, маршруты справок, планы, ВСЕ списки ПЛ | CSV только на `/waybills`; отчёты — CSV/XLSX | [ ] | **Гэп (Шаг 1).** Экспорт CSV+XLSX в реестры ТС/водителей/сотрудников/организаций и в справочники. |
| 8.8 | Реестры СМР (`cmr`: клиент, период) и борхатов прил. 1/2 (клиент, период) как отдельные списки | нет — СМР/борхат печатаются из ПЛ | [~] | Список ПЛ «с накладной/СМР» по клиенту и периоду (вкладка/фильтр). |
| 8.9 | GPS-события: список по компании | `/monitoring` (точки) | [~] | Зависит от 9.7. |
| 8.10 | Отчёты на боевом объёме (Ф5: 2,28 млн архивных ПЛ, ~55 тыс./мес.) | **21.09:** все отчётные сервисы переведены с `findAll()` на потоковую выборку по периоду (`WaybillPeriodScan`: fetch 500 + очистка контекста; счётные — без лимита, с расчётом по ПЛ — предохранитель `epd.reports.max-rows`=100 000 → 422; сводный/тренд — только COMPLETED в SQL); суммы топлива/выручки — агрегаты в БД | [~] | Найдено live: главная панель админа роняла сервис в OOM (`-Xmx384m`). Вторая находка: отчёты упирались в rate-limit master-data (300/мин по IP → 429 → 500) из-за `findRoute` по каждому ПЛ и `listVehicles` по каждой организации → кэш 60 с в `MasterDataClient` (ключ — токен вызывающего), агрегатный `GET /vehicles/count-by-organization` в master-data, 429/недоступность → 503. Индексы V25 (`created_at`, `org+created_at`, `status+created_at`). Осталось: SQL-агрегация для «Количество ПЛ» (сканирует ~20 мес. ≈ 1,1 млн строк, ~20 с) и типовых отчётов на архиве; лимитер master-data по IP не различает межсервисный трафик (Вопрос 17). Changelog `2026-09-21-отчёты-без-findAll-OOM-после-Ф5.md`. |

## 9. API / интеграции

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 9.1 | Мобайл водителя (9 маршрутов, JWT) | `MobileController` | [~] | Свериться по эндпоинтам (профиль/лицензия/печати/геолокация). |
| 9.2 | `ref/*` интеграция компаний (~25, apiResource 6 форм) | приём ПЛ/агрегаторы | [~] | Свериться; часть — stub (единая платформа). |
| 9.3 | GPS smart-city (`gps/gps_data`) | GPS приём (`GpsController`) | [x] | |
| 9.4 | CMR API | СМР | [x] | |
| 9.5 | Служебное API админки: `filter/region|city|company|client` (подсказки), `filter/regions`, `parking_indication_counter`, `parking_fuel_left|give|give_multi_days`, `POST reportwaybillgeneral` | поиск ✓; сводный ✓; одометр/топливо — 4.8/4.9 | [~] | — |
| 9.6 | Мобильное: привязка телефона (1 устройство/водитель, смена через 15 дн.), присутствие `update_geolocation/{distance}` (пороги 300/500/600/2000 м — хардкод по компаниям/городу), `get_seals` (картинки подписей), `get_license_attach`, гейт версии приложения v=2.5 | `MobileDevice` (вручную); `/mobile/*` JSON; ЭП вместо картинок | ⏸ | Отложено владельцем. При возобновлении: пороги — в настройки, не хардкод. |
| 9.7 | Smart-city GPS (`gps/gps_data`): **события** `enter_into_route/exit_from_route/enter_into_company/exit_from_company` (+direction/distance по состоянию), cooldown 20 мин на состояние, журнал `gps_data` с фильтром по компании | сырые точки `POST /gps` (lat/lon/speed/waybillId) + live/track | [ ] | Вопрос 12 (актуальна ли интеграция). Если да — событийная модель + журнал + фильтр 8.9. |
| 9.8 | `ref/*` (КВД-канал перевозчиков): `apiResource waybill1ade|1ad|1a|3c|2b|5bbm` (создание/закрытие ПЛ всех 6 форм системой перевозчика, правила `Requests/kvd/*`), `waybill/confirm` (врач/механик по RMA через API, 409 если уже), `remain_fuel`, `files/upload` | только `POST /aggregator/waybills` (такси); confirm — под логином DOCTOR/MECHANIC; sync субъектов ✓; списки ✓ (API_INTEGRATOR) | [ ] | Вопрос 11 (нужен ли B2B-канал создания ПЛ в модели «модуль e-Transport»). `remain_fuel` — 4.9. |
| 9.9 | `POST /api/cmr` (проверка СМР по номеру, захардкоженный токен) и `qrcode/{1..8}` (публичная проверка 8 видов документов) | `verify/{jws}`; QR на всех 11 шаблонах | [x] | Хардкод-токен не переносить. Проверить, что QR борхата/СМР ведут на verify (типы 6/7). |

## 10. Настройки / конфигурируемость (новое требование — сквозной пункт)

| # | Что в legacy захардкожено | e-Waybill | ⚙ | Прим. |
|---|---|---|---|---|
| 10.1 | `config/trans.php` (типы отчётов, коэф. планов, соответствия форм) | SettingsEditor по категориям | ⚙~ | Проверить, что все ключи trans.php вынесены в настройки. |
| 10.2 | settings-таблица = демо (реальных настроек нет) | `platform_settings` (V10) + SettingsEditor | ⚙+ | Инфраструктура есть. |
| 10.3 | Поля форм захардкожены | конструктор полей (V8 field_definitions) | ⚙+ | Работает end-to-end. **Проверить покрытие всех форм.** |
| 10.4 | Шаблоны печати в коде | Thymeleaf в коде | ⚙– | **7.1 — ключевой гэп конфигурируемости.** |
| 10.5 | Форматы отчётов в коде | views/сервисы | ⚙– | 6.1. |
| 10.6 | Нормативы (must_give), нумерация, статусы-названия | policy / numbering / classifier WAYBILL_STATUS | ⚙+ | Уже редактируемо. |
| 10.7 | `config/mobile_waybill.php`, `mobile_cargowaybill.php` (поля мобильных бланков) | — | ⏸ | Мобильное. |
| 10.8 | Хардкод в коде legacy: `getCompaniesDushanbe()` (20 id), пороги присутствия по компаниям, whitelist user id в меню/отчётах | policy по организации; остальное ⏸/† | ⚙+ | Не переносить хардкод; при возобновлении мобильного — настройки. |
| 10.9 | `trans.php`: `bill_type_plan` 1..4 (вкл. «5Б-БМ»), `fuel.table_key`, `forecast.*` | planKind ×3 (2.29), подписи в шаблонах (7.6), прогнозы (6.6) | ⚙~ | См. соответствующие строки. |

## 11. Прочие фичи

| # | Legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 11.1 | ЭЦП = картинки печати/подписи (крипто нет) | StubTitleSigner → CAdES (внешне-заблок.) | [x] | Наше сильнее; боевая ЭЦП ждёт УЦ. |
| 11.2 | QR публичная проверка (type 1..8) | QR verify + public portal | [x] | |
| 11.3 | Блокировки/уведомления (status_lock, license_expired, password_lock) | maintenance/blocked + expiry monitor | [~] | Свериться по триггерам. |
| 11.4 | Аудит (create/update_user_id, revisions) | audit_log (V6) | [x] | |
| 11.5 | Долг/оплата (debt, {id}/pay) | payment flow | [~] | Свериться по механике оплаты/долга. `pay` 3-С = сдача выручки кассиру (4.7), не платёж за услугу. |
| 11.6 | `Notification` middleware: `status_lock` → баннер «оплатите услуги» + редирект (весь кабинет, кроме маълумотнома); лицензия истекла → блок; за N дней — предупреждение «через N дней» | `Organization.blocked`+reason → 403 на выписку; `licenseTo` истёк → отказ при создании; expiry-монитор + уведомления | [~] | Проверить: баннер с обратным отсчётом до истечения лицензии в кабинете перевозчика. Блокировка = только выписка ПЛ (не весь кабинет) — осознанно мягче, зафиксировать. |
| 11.7 | `DriverObserver`: табельный `number` = max+1 по компании при создании/обновлении без номера | `tabNumber` вручную (и из sync) | [ ] | Авто-присвоение при пустом tabNumber. |
| 11.8 | `db:backup` ежедневно 00:00 + ротация >10 дней еженедельно (Kernel) | `scripts/backup-postgres.ps1` + `/settings/backup` вручную | [~] | Расписание/ротация — инфра стенда (cron / Task Scheduler), не код. |
| 11.9 | `revisions` (история изменений сущностей), `create/update_user_id` | audit_log (hash chain, V44) | [x] | — |
| 11.10 | Мёртвый/демо-код legacy: Backpack Monster/Dummy/Product/Icon/FluentMonster, Charts users/new-entries/Lines/Pies, `CheckBills` (пустой exit), `LockUserCommand` (разовая alter), `car` (дубль parkings, нет в меню), `numberdriver`, Ticket1a/1ad/2b, `Waybill3cRiport`, `Waybill1ade`, `report/main|min|1a|1ada|1adt` | — | [x] | Не переносить (доказательство: нет в меню/роутах или пустая реализация — см. 07-инвентаризация §A, §F). |

## 12. Валидации и проверки (`app/Http/Requests/**` → e-Waybill) — добавлено Шагом 1

| # | Правило legacy | e-Waybill | Покр. | Прим. |
|---|---|---|---|---|
| 12.1 | RMA/ИНН = 9–10 цифр во всех запросах | `@Pattern("\\d{9,10}")` везде (org/driver/employee/waybill) | [x] | — |
| 12.2 | Госномер: админ-форма «4 цифры + 2 лат. буквы + 2 цифры» (`1234AB01`); API `^[A-Z0-9]+$` ≤15 | `[A-Za-zА-Яа-я0-9]{4,20}` | [~] | Вопрос 13 — какой формат боевой (прицепы, кириллица?). |
| 12.3 | `year_manufacture` — 4 цифры, 1900..текущий год | без ограничения | [ ] | Добавить `@Min(1900)` + ≤ текущий. |
| 12.4 | Лимиты на ПЛ: 1-А `work_days ≤ 4`; 2-Б `work_days 1..15`; `fuels ≤ 1` (2-Б/5Б-БМ) / `≤ 2` (1-АД/1-А/3-С) | без лимитов | [ ] | Правила в `WorkDayService`/policy по типу ПЛ. |
| 12.5 | Лимит суточного пробега `max_counter_value`: 650 км (3-С; 1-А при правке) / 750 км (1-А при создании) — «Максимальное значение спидометра» | нет | [ ] | Policy по типу ПЛ (⚙+) + проверка в `addWorkDay`/`returnTrip`. |
| 12.6 | «Роҳхати қаблии ронанда коркард нашудааст» — новый ПЛ невозможен, пока предыдущий не обработан | инвариант «1 действующий ПЛ» (V7) + preflight | [x] | — |
| 12.7 | Диапазоны кодов: transport_type 1..6, employee type 1..3(5), degree 1..3, region 1..7, type_company 1..2, type_service 1..3, shipment 1..2, fuel_id 1..3 | `@Min/@Max` 1..6 / 1..3 / 1..7 / 1..2; enum; fuel 1..5 | [~] | employee type 1..5 (2.27); degree 1..3 — проверить; регионы 3-С 1..7 — проверить. |
| 12.8 | 1-АД `fuels.*.additional ∈ 0..5` | поля нет | [ ] | Вместе с 5.6. |
| 12.9 | Возврат не раньше выезда (`entry_date.valid_date_range`, 3-С) | проверить `returnTrip` | [~] | — |
| 12.10 | Обязательные поля 5Б-БМ: клиент, 2 водителя, страны/города погрузки-разгрузки, страна визы, груз, ББА, срок визы, транзитные страны | проверить форму WB_TRUCK_INTL | [~] | — |
| 12.11 | Фото водителя `image|mimes:jpg,png,jpeg` | поля нет | [ ] | Вместе с 2.6 (photo). |
| 12.12 | GPS: state ∈ 4 значений, direction/distance по состоянию, повтор не чаще 20 мин | нет | [ ] | Вместе с 9.7. |
| 12.13 | `parkings.number` уникален в компании | `parkingNumber` 4 цифры; уникальность — проверить | [~] | — |
| 12.14 | Обязательные при создании ПЛ: компания, ТС, маршрут (кроме 2-Б/5Б-БМ), водитель (≠0), график, дата выезда, одометр ≥0 | `@NotNull/@NotBlank`, `@PositiveOrZero` | [x] | — |
| 12.15 | Cargo: number/name/type/unit/price обязательны; Client: number/name/address/phone; Direction: number/title | частично `@NotBlank` | [~] | number у cargo — 2.25. |

## 13. Полная механическая карта (Шаг 1 промпта)

Каждый маршрут, CRUD (поля/колонки/фильтры), правило валидации, сервис, трейт, модель, blade, console-команда и
middleware старой платформы сопоставлены с e-Waybill в **`spec/notes/07-механическая-инвентаризация-legacy.md`**
(разделы A меню · B CRUD · C API · D валидации · E сервисы/модели · F служебное). Здесь в MIGRATION.md — только
строки со статусом; при закрытии пункта обновлять обе стороны не нужно — статус ведётся тут.

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
8. **Валидации/проверки (§12)** — лимиты дней/топлива/пробега, диапазоны, обязательность; **служебное (§11.7–11.8)**.

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
9. ~~**Марка `coe_conditioner`/`coe_sea` (2.5)**~~ — **СНЯТ 21.09, уточнено при 5.3:** в `helpers.php:379` из-за приоритета `?:` кондиционерная надбавка legacy = `cond_h / work_h` (безразмерная доля, ≤ 1 «л»), а не 0 и не формула с `parkings.air_conditioner` — баг. e-Waybill применяет корректную формулу (закомментированная строка 378: `(cond_h/work_h)·air_conditioner%·0.01·fuel·L`); баг не переносится, legacy-вариант оставлен как `conditionerFuelLegacy` для сверки истории. Влияет только на ПЛ с часами кондиционера и `% кондиционера` у ТС. `coe_conditioner`/`coe_sea` марки в топливе не участвуют — не переносить.
10. **Окно недоступности отчётов (1.5):** legacy отключает отчёты 08–11 и 17–20 (кроме 11 user id) — это костыль от нагрузки. Предлагаю НЕ переносить. Подтвердить.
11. **B2B-канал `ref/*` (9.8):** в legacy перевозчик мог своей системой создавать/закрывать ПЛ всех 6 форм и подтверждать врача/механика по RMA через API. В модели «e-Waybill = модуль e-Transport» нужен ли такой канал и кому (кроме такси-агрегатора)? Пока безопасно: не делаем (есть агрегатор такси + sync субъектов).
12. **Smart-city GPS (9.7):** интеграция с камерами/постами через события заезд/выезд (маршрут/предприятие, cooldown 20 мин) — жива ли она? Если да — нужна событийная модель и журнал; сейчас у нас только сырые GPS-точки трекеров.
13. **Формат госномера (12.2):** legacy требует строго `1234AB01` (4 цифры, 2 лат. буквы, 2 цифры); e-Waybill принимает буквы/цифры 4–20. Ужесточать до боевого формата (учесть прицепы, кириллицу, дипломатические)?
14. **`routes.week_days_earnings` (2.28):** план выручки по дням недели есть в форме маршрута, но нигде в расчётах/отчётах legacy не используется. Переносить как поле или считать мёртвым? Безопасно: не переносить.
15. **Касса 3-С (4.7):** отметка «выручка сдана» кассиром (`employee_kassa_id`) — нужна ли в новой платформе и кто её ставит (бухгалтер / тип сотрудника «касса»)? Безопасно: перенести как отметку на ПЛ с ролью ACCOUNTANT.
16. **Архив в сводных отчётах (8.10):** мигрированные исторические ПЛ имеют статус `ARCHIVED` (и без `work_day`/`fuel_record`), а сводный перевозок и тренд считают только `COMPLETED` — значит история legacy в них НЕ попадает (в «Количество ПЛ» и типовые разрезы — попадает). Считать ARCHIVED как завершённые для истории? Тогда показатели по ним будут из снимков без топлива/дней. Безопасно: пока не учитывать, зафиксировать.
17. **Rate-limit master-data и межсервисный трафик (8.10):** `RateLimitFilter` считает 300 запросов/мин по IP до аутентификации — waybill-service с одного внутреннего IP при отчётах легко упирается в лимит (кэш и агрегаты сняли остроту, но не причину). Исключать сервисные вызовы (client-credentials/внутренняя сеть) из лимита или считать по субъекту после аутентификации? Безопасно: пока кэш + 503 с Retry-After.
18. **Неизвестный код кузова в грузовом расчёте (5.2):** legacy при первой цифре кода марки вне {1,2,3,4,5,8} (и 9→0) расчёт 2-Б не выполняет вовсе (норма не считается), e-Waybill считает по бортовой формуле и пишет предупреждение в `notes`. Оставить мягкое поведение (норма есть, но с пометкой) или повторить legacy (норма 0 + явная ошибка «тип кузова не определён»)? Безопасно: оставлено как есть, ПЛ с таким кодом видны по предупреждению.
19. **Отопление салона в пассажирской норме (5.3):** в legacy надбавка `brands.fuel_interior_heating × часы` закомментирована (= 0 всегда), в e-Waybill применялась круглый год для 4 марок (МАЗ 103, ЛиАЗ 5292, Акиа Granbird, id 16). Сделан режим `epd.calc.interior-heating` (`CALC_INTERIOR_HEATING`): `OFF` (по умолчанию, числа legacy) / `WINTER` (как задумано в оригинале — только в зимний период маршрута) / `ALWAYS` (прежнее поведение e-Waybill). Какой режим ставить на боевом стенде? Безопасно: `OFF`.

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
- **2026-09-21** — **Шаг 0 промпта (санитария git) — ВЫПОЛНЕН.** Ветка `migration` от `main` 29b25e8; рабочее дерево 21.09 разложено на 9 атомарных коммитов + docs `73341d8`: `bdeb0a8` артефакты сборки вне индекса · `b509a65`/`d62f901` восстановление сборки после слияния 14.09 (бэкенд/web) · `47e229f` гейт B10 · `78d1076` показатели такси · `4a819f4` ETL Ф5 · `315c2a5` реестр ПЛ (архив вне дефолта + кэп) · `112ca7e` пагинация реестров web · `561230d` web на :3000. **Базовая линия тестов:** master-data **62/62 ✅**; waybill **222: 220 ✅ + 2 `initializationError`** (`WaybillLifecycleIntegrationTest`, `WaybillConcurrencyIntegrationTest` — Testcontainers не стартует под Docker Desktop for Windows (docker-java через проксированный сокет), известное ограничение окружения, не регрессия; прогонять в Linux CI); web `tsc --noEmit` ✅. Стенды: legacy :8000 UP (боевая MySQL), e-Waybill :8081/:8082/:3000 UP, rohkhat-v2 :8080 UP. Диск C: 5 ГБ свободно — build-cache Docker очищен (21 ГБ), следить.
- **2026-09-21** — **Шаг 1 промпта (переинвентаризация старой платформы) — ВЫПОЛНЕН.** Механически (скриптом по исходникам, не по памяти) сняты: 4 файла маршрутов, 60 admin-контроллеров (поля/колонки/фильтры/кнопки), 74 класса валидации, 50 сервисов, 31 трейт, 77 моделей, 81 миграция, 139+26+10 blade, `config/trans.php`, sidebar-меню, console/middleware/observers; e-Waybill — 275 эндпоинтов обоих сервисов + поля entity + web-фильтры. Результат: **`spec/notes/07-механическая-инвентаризация-legacy.md`** + **41 новая строка** здесь (1.5–1.6, 2.24–2.31, 3.13–3.16, 4.7–4.11, 5.6–5.7, 6.7–6.10, 7.5–7.6, 8.1–8.9 переписаны, 9.5–9.9, 10.7–10.9, 11.6–11.10, новый §12 валидации ×15) и вопросы 10–15. **Ключевые новые гэпы 1-в-1:** 5.6 (additional/coef_below_0 в расчёт идут нулями), 4.9 (автоподстановка остатка топлива/нормы из предыдущего ПЛ), 4.7 (касса 3-С), 8.5/8.6/8.7 (фильтры активности ТС/водителей, экспорт реестров), 2.24 (реквизиты клиента), 2.27 (типы сотрудников 4/5), 12.3–12.5 (лимиты дней/топлива/пробега, год выпуска), 11.7 (авто-табельный номер), 9.7 (GPS-события — Вопрос 12), 9.8 (B2B-канал — Вопрос 11). Мёртвый код доказан и помечен (11.10, 6.10, 3.16).
- **2026-09-21** — **§6 отчёты — сверка ПОЛНАЯ (config/trans.php ↔ ReportType/ReportController).** Все отчёты старой платформы перенесены: Мусофирбарӣ 14/14, Боркашонӣ все активные (тип 8 «Самт»=BY_ROUTE), Умумӣ (regional/count/norm/trend), журналы механик/врач, Маълумотнома. Прогнозы — в legacy не в UI, не гэп. Осталось: (а) опц. отдельный ярлык `BY_DIRECTION` для грузового «Самт»; (б) поколоночная сверка Умумӣ (industry/general); (в) конфигурируемость формата (§7/§10). Отчёты как ТИПЫ — 100% покрыты.
- **2026-09-21** — **§5.6 топливная строка — ЗАКРЫТО** (`coef_below_0`/`be_given` V24, `additional`+`coef_below_0` идут в `fuel_calc`-эквивалент; тесты + live; коммит `1a5204e`). **§4.9 автоподстановка остатка топлива/нормы из предыдущего ПЛ — ЗАКРЫТО** (`FuelPrefillService` + `GET /waybills/{id}/fuel-prefill`, подсказка в формах; коммит `1e5343c`).
- **2026-09-21** — **Стенд (не legacy-гэпы, найдено live):** compose не пробрасывал `MEDDATA_ENCRYPTION_KEY`/Redis-хост, локальный override портов (`4dcfaa6`); **8.10** отчёты на 2,28 млн ПЛ роняли сервис в OOM + 429 от rate-limit master-data → `WaybillPeriodScan`, агрегаты в БД, индексы V25, кэш справочников, 429→503 (`b26e5d5`); 14 отчётных вызовов проверены live. ⚠️ Для боевого 10.10.29.70 перед деплоем в `infra/.env` нужен `MEDDATA_ENCRYPTION_KEY` (тот же, что сейчас в контейнере — не генерировать новый).
- **2026-09-21** — **§5.2 грузовой расчёт 2-Б — ЗАКРЫТО.** Ветки по кузову 1-в-1; найден и закрыт гэп входов (направление из `typeData.directionId`, прицеп из снимка ТС — без тела `Supplement` не применялись). `WaybillCalcAssemblerCargoTest` (3), suite 242 (240 ✅ + 2 окружение), live 5555TT01 → K=25/99.25 л, 5556TT01 (прицеп) → 102.15 л. Расхождения (неизвестный кузов → Вопрос 18; P/Z из борхата → 3.11; посуточно → B10) зафиксированы в строке.
- **2026-09-21** — **§5.3 спот-чек Душанбе — ЗАКРЫТО; §5.5 тариф — ЗАКРЫТО.** Ветка А (`excluding_coef`) и ветка Б — 1-в-1 (тесты = ручной расчёт по `helpers.php`). Найдено и исправлено расхождение чисел: отопление салона применялось круглый год (legacy — никогда) → режим `epd.calc.interior-heating`, по умолчанию OFF (Вопрос 19); live тот же ПЛ: 88.0 → 68.0 л. Вопрос 9 уточнён (кондиционер legacy = `cond_h/work_h`, баг; корректная формула у нас). Тариф: в legacy только CRUD, расчёта нет — `TariffMath` безвредное расширение. Заметка в 5.4: посуточные ветки legacy выбирают норматив по `company.region_id==1`, а не по `excluding_coef`. **§5 расчёты: 5.1/5.2/5.3/5.5/5.6/5.7 [x], 5.4 — B10 (Вопрос 4).**
