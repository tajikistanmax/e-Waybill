# План миграции боевых данных: legacy «Роҳхат» (MySQL) → e-Waybill (PostgreSQL)

> Дата: 2026-09-14. Источник: legacy Laravel `rohkhat.tj ralavel`, MySQL 8 `rohkhat`
> (docker `rohkhattjralavel-db-1`, порт **33061**, user `rohkhat`/`secret`).
> Цель: наши Postgres-БД `masterdata` (справочники/реестры) и `waybill` (ПЛ/планы), docker `epd-prod-postgres`.

## 1. Инвентаризация источника (реальные объёмы)

| Категория | Таблица (legacy) | Строк | Куда (наше) |
|---|---|---:|---|
| **Путевые листы** | waybill3cs (легковой/такси) | 1 361 489 | waybill (WB_CAR/WB_TAXI) |
| | waybill1as (микроавтобус) | 1 240 932 | waybill (WB_MINIBUS) |
| | waybill1ads (авто/троллейбус) | 645 330 | waybill (WB_BUS/WB_TROLLEYBUS) |
| | waybill2bs (грузовой 2-Б) | 370 081 | waybill (WB_TRUCK) |
| | cargo_waybills (борхаты) | 268 168 | waybill consignment |
| | waybill5bbms (5Б-БМ) | 19 877 | waybill (WB_TRUCK_INTL) |
| | waybill_work_days | 1 312 012 | work_day |
| | malumotnomas / rmalumotnomas | 51 542 / 107 517 | malumotnoma |
| **Реестры** | drivers | 132 633 | masterdata.driver |
| | parkings (ТС) | 105 925 | masterdata.vehicle |
| | parking_driver (назначения) | 91 378 | назначение ТС↔водитель |
| | phone_infos (устройства) | 61 857 | masterdata.mobile_device |
| | companies | 676 | masterdata.organization |
| | employees | 484 | masterdata.employee |
| | clients | 539 | masterdata.client |
| | routes | 854 | masterdata.route |
| **Справочники** | external_cities | 19 077 | masterdata.external_city (у нас сейчас 103!) |
| | external_countries | 218 | classifier COUNTRY (у нас 218 ✓) |
| | brands | 491 | masterdata.brand |
| | routemalumotnomas | 674 | masterdata.malumotnoma_route |
| | directions | 103 | masterdata.direction |
| | cities | 69 | masterdata.city |
| | regions | 7 | masterdata.region |
| | cargos | 139 | masterdata.cargo |
| | fuel_winter_coef/city_coef/mountain_coef/used_coef | 13/4/4/2 | коэффициенты |
| | tariffs / route_types / drive_classes / fuels / transport_type / bill_types | 5/4/3/3/6/7 | соотв. справочники |
| **Auth** | users / roles / permissions / *_has_* | 1094/12/18/… | Keycloak (отдельно) |
| **НЕ мигрировать** | backup_drivers (105 831), backup_parkings (92 882), log_ips, revisions, failed_jobs, migrations, dummies | — | бэкапы/системное |

Итого «полезных» строк: ~**5,6 млн ПЛ** + ~**1,3 млн** рабочих дней + ~**0,4 млн** реестров/устройств + ~**20k** справочников.

## 2. Стратегия ключей (RMA-центричная)

Legacy — авто-инкрементные `id` + `company_id` FK. Наше — натуральные ключи по РМА, id = UUID.
- `companies.rma` → `organization.rma` (уникальный ключ). Строим карту `company_id → rma`.
- `drivers.rma` → `driver.rma`; `drivers.company_id` → org rma по карте.
- `parkings.registration_number` → `vehicle.registration_number`; `parkings.company_id` → org rma.
- `parking_driver` (legacy id-связь) → пересобрать по (registration_number, driver rma).
- Legacy `id` НЕ переносим (генерим свои UUID). Ссылки resolvим по натуральным ключам.
- `deleted_at IS NOT NULL` (soft-delete) — **пропускаем** (или грузим как `active=false`).
- Справочные FK (`city_id`, `region_id`, `brand_id`, `direction_id`) — remap по коду/имени.

## 3. Фазы (по зависимостям)

**Ф0 — Подготовка.** Снять консистентный снапшот legacy (`mysqldump --single-transaction` только нужных таблиц). Настроить доступ Postgres→MySQL: рекомендую **`mysql_fdw`** (foreign data wrapper) в epd-prod-postgres — читать legacy напрямую и `INSERT … SELECT` с трансформацией; либо промежуточный ETL-скрипт (Python). Сверить, что часть справочников у нас уже засеяна (страны 218, регионы 7, route_types) — грузить идемпотентно (`ON CONFLICT DO NOTHING`), НЕ дублировать.

**Ф1 — Справочники** (мин. объём, зависимостей мало): transport_type, regions(reconcile), cities, brands, directions, cargos, external_countries(reconcile), **external_cities (полные 19k — заменяем наши 103)**, drive_classes, route_types(reconcile), fuels, коэффициенты (winter/city/mountain/used), tariffs, routemalumotnomas.

**Ф2 — Организации:** companies (676) → organization. Поля мапятся 1:1 (см. §маппинг). `source='MIGRATED'`. city_id/region_id remap.

**Ф3 — Реестры:** parkings (106k)→vehicle, drivers (132k)→driver, employees→employee, clients→client, routes→route, parking_driver→назначения. Батчами (COPY/партиями по 10-50k).

**Ф4 — Устройства:** phone_infos (62k) → mobile_device.

**Ф5 — Историч. путевые листы (~5,6 млн) — ТРЕБУЕТ РЕШЕНИЯ (см. §5).**

**Ф6 — Справки/планы:** malumotnomas (160k) — по решению; waybill_plans (пусто).

## 4. Маппинг ключевых сущностей (поле→поле)

**companies → organization:** rma→rma, name→name, kpp→kpp, name_head→name_head, bank→bank, address→address, phone→phone, email→email, region_id→region_id, license_number→carrier_license_number, license_activity_from/to→license_from/to, percent_income→percent_income, cat_1/2/3→cat_1/2/3, ownership_id→ownership, type_company_id→type_company, plan_pass_volume→plan_pass_volume, plan_pass_traffic→plan_pass_traffic, latitude/longitude→latitude/longitude, registration_certificate→registration_cert_number, iktibos→extract_number, aai→vat_cert_number, status_lock→blocked.

**drivers → driver:** full_name→full_name, number→tab_number, rma→rma, license→license_number, category→license_categories, degree→degree, med_cert_number→med_cert_number, med_cert_valid_date→med_cert_valid_to, phone→phone, company_id→organization(rma). (attach-поля — файлы, §6.)

**parkings → vehicle:** registration_number→registration_number, number→parking_number, brand_id→brand(name), capacity→capacity, carrying→carrying, year_manufacture→year_manufacture, vincode→vincode, tech_inspection_date_to→tech_inspection_valid_to, transport_type_id→transport_type, company_id→organization(rma), imei→(устройство). ydak_*(прицепы)→trailers (JSON/отдельно).

## 5. РЕШЕНИЕ по историческим ПЛ (5,6 млн) — главный вопрос

Наша схема ПЛ ≠ legacy: у нас workflow Т1–Т6 + снапшоты орг/ТС/водителя + ЭЦП + JSON typeData + статусы. Залить 5,6 млн ЗАКРЫТЫХ ПЛ с полной точностью — очень тяжело и, вероятно, избыточно. Варианты:
- **(A) Не мигрировать** (рекомендуется как база): старую БД держать как архив для справок; наша система стартует с чистыми ПЛ + перенесёнными реестрами/справочниками. Быстро, безопасно.
- **(B) Архивные записи**: залить ПЛ как упрощённые read-only записи (без re-run workflow, статус ARCHIVED, снапшоты из текущих реестров) — окном (напр. последние 1–2 года ≈ сотни тысяч) или полностью. Тяжёлый ETL, но даёт историю/отчёты в новой системе.
- **(C) Полная миграция всех 5,6 млн** — максимальная точность, максимальная стоимость/время/риск.

**Рекомендация:** Ф1–Ф4 (справочники+реестры+устройства) — перенести **полностью** (это даёт рабочую боевую систему с реальными орг/ТС/водителями). Историч. ПЛ — вариант **A** (не грузить) или **B окном** — по твоему решению.

## 6. Риски и нюансы
- **Объём/время:** ПЛ (5,6M) — часами; реестры (240k) — минутами; справочники — секунды. Нужны батчи + `COPY`, а не построчный insert.
- **ПДн:** 132k водителей (паспорт/мед) — персональные данные. На dev-стенд заливать осознанно; мед-показатели у нас шифруются (`MedicalDataCrypto`) — при переносе шифровать.
- **Кодировка:** MySQL utf8mb4 → PG UTF-8, таджикская кириллица — проверить.
- **Вложения (`*_attach`):** файлы (сканы лицензий/паспортов/печатей) лежат в legacy storage, не в БД — отдельный перенос в MinIO (наш файловый слой), если нужен.
- **Учётки входа:** миграция данных ≠ создание Keycloak-учёток. Вход компаний/водителей — отдельная задача провижининга (Keycloak users + realm-роли), либо через «единую платформу».
- **Дубли справочников:** у нас уже засеяны страны(218)/регионы(7)/route_types(4)/external_cities(103) — грузить идемпотентно, не плодить дубли.
- **Единая платформа:** наши орг/ТС/водители штатно приходят из налоговой/ГАИ (source=UNIFIED). Миграция — разовый seed source='MIGRATED'; последующая синхронизация не должна их затирать.

## 7. Проверка (после каждой фазы)
- Сверка `COUNT(*)` source vs target по каждой таблице (с учётом отброшенных soft-delete/бэкапов).
- Выборочная сверка 10–20 записей (орг/ТС/водитель) поле-в-поле.
- Наши отчёты/дашборд должны показать боевые числа (≈107k ТС, ≈132k водителей, 676 орг, 854 маршрута).

## 8. Инструмент (рекомендация)
- **Справочники+реестры (Ф1–Ф4):** `mysql_fdw` в epd-prod-postgres → `INSERT … SELECT` с remap, идемпотентно. Быстро, транзакционно, воспроизводимо (SQL-скрипты в `scripts/migration/`).
- **ПЛ (Ф5, если делаем):** отдельный батч-скрипт (Python или Java-runner) с `COPY` и resolvом снапшотов.
- Всё — **в отдельной схеме/скриптах**, идемпотентно, с логом и возможностью повторного прогона по фазам.

---

## 9. Ф5 — РЕАЛИЗОВАНО (2026-09-21): исторические ПЛ, вариант B (архив)

**Решение пользователя:** грузить **всю историю** (вариант B, окно = всё время). Записи read-only:
`status=ARCHIVED`, `source='MIGRATED'` (в модели `Waybill.source` — обычный String, не enum).

**Скрипты** (`scripts/migration/`): `14_staging_phase5_ddl.sql` (обобщённый staging `stg_wb5`,
22 text-колонки под все типы), `15_phase5_waybills.sql` (трансформация в `waybill`,
идемпотентно `ON CONFLICT (number) DO NOTHING`), оркестратор `run_phase5.ps1`
(параметр `-Window`, целевая БД `waybill`).

**Маппинг источник → тип:**
- `waybill3cs`: `type_service=1`(taxi)→`WB_TAXI`, `2`(route)/`3`(hourly)→`WB_CAR`;
- `waybill1as`→`WB_MINIBUS`; `waybill1ads`: `type='ebus'`→`WB_TROLLEYBUS`, иначе `WB_BUS`;
- `waybill2bs`→`WB_TRUCK` (маршрут из `directions.title`, нет schedule);
- `waybill5bbms`→`WB_TRUCK_INTL` (водители напрямую `first/second_driver_id`, номер = `bba_number`).

**Ключевые решения:**
- **Натуральные ключи резолвятся в SQL-запросе выгрузки на стороне MySQL** (все таблицы
  в одной БД — кросс-БД join не нужен). Водитель для 3cs/1as/1ads/2bs — через pivot
  `parking_driver` (в среднем 1.01 водителя на ТС, `MAX(driver_id)` детерминированно;
  best-effort: исторический водитель конкретного рейса в legacy не хранится).
- **Снапшоты самодостаточны** (org `{rma,name}`, vehicle `{registrationNumber,brand}`,
  driver `{rma,fullName}`) — рендер не зависит от live-реестра; workflow для ARCHIVED не идёт.
- **Номер** `'MG'||src_code||legacy_id` (уникальный, явно мигрированный; нац. формат
  RR-YY-… присваивается только в READY). Оригинал legacy-№ — в `type_data.legacyNumber`.
- `med_passed=tech_passed=true`; `work_day`/`fuel_record` НЕ переносятся (в legacy —
  сериализованный TEXT; для упрощённого архива достаточно шапки).

**Политика отсева (сироты, консистентно с Ф1–Ф4):** грузятся только ПЛ с разрешимыми
org+ТС+водитель. Главная причина отсева пассажирских — **~10 002 legacy-водителя с
`rma='NULL'` (мусор)**; они не в реестре (Ф3 их так же отбросила) → их ПЛ пропускаются.
Плюс ~36% ПЛ ссылаются на company с пустым `rma` (не мигрированы). Итог: грузится ~половина
исторических ПЛ (та, что имеет полный набор мигрированных мастер-данных).

**Урок (баг конвейера):** COPY падал `literal carriage return found in data` — в legacy-тексте
за 4 млн строк встречается «голый» CR (`\r`), который mysql batch не экранирует. Фикс:
вывод mysql проходит через `tr -d '\015\000'` (убрать CR и NUL; LF-разделители целы).
