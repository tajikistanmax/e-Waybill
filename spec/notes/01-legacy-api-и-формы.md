# Рабочий конспект: legacy-платформа rohkhat.tj — API, формы, бизнес-правила

> Источник: извлечённые тексты .docx из `docs\` (тз.docx, api.docx, ТЗ-get waybills, бизнес-логика-бо-агенти, Waybill Neru, ТЗ по формам, ТЗ Справочники updated, Массиви, Шаклҳои роххату борхат, Юридическое лицо...). Конспект составлен агентом-исследователем 10.07.2026. Ничего не домыслено.

## 1. Соответствие форм, типов ТС и сроков действия

| Эндпоинт | Форма | ТС | waybill_type | Срок действия |
|---|---|---|---|---|
| `waybill1ad` | Т(1-АД) | Автобус | 1 | 1 день |
| `waybill1ade` (1adt) | Т(1-АД) | Троллейбус | 2 | 1 день |
| `waybill1a` | 1-А | Микроавтобус/автобус | 3 | 3–4/7 дней |
| `waybill3c` | 3-С | Сабукрав (легковой) | 4 | 7 дней; такси Душанбе — 1 месяц |
| `waybill2b` | 2-Б | Грузовой (боркаш) | 5 | 15 дней |
| `waybill5bbm` | 5Б-БМ | Грузовой международный | 6 | 1 гардиш (1 рейс) |

Дополнительно в перечне форм: **4М-БМ** — международный пассажирский (1 гардиш), отдельного API-описания нет. Накладные («борхат»): борхати замимаи 1, замимаи 2 (разовые перевозки груза), международная накладная **CMR** (1 гардиш).

## 2. Идентификация

- **RMA** (рамзи мушаххаси андозсупоранда — ИНН налогоплательщика, 9–10 цифр, только цифры) — ключ организации, водителя, сотрудника. Upsert по rma.
- **registration_number** (госномер) — ключ ТС. Upsert по registration_number.
- **KPP** — опциональный доп. код организации.
- Старый черновик (tz_waybills) использовал ИНН/tin — устарело, не брать.

## 3. Аутентификация и API-инфраструктура legacy

- Laravel 8 / PHP 7.4 / MySQL. JWT: `POST /api/ref/auth/login|refresh|logout`, токен на 43200 с (12 ч), `Authorization: Bearer`.
- Справочные выгрузки с пагинацией: `GET /api/ref/companies|transports|drivers|employees|waybills` (`?page&updated_after=dd.mm.yyyy`), meta: current_page/last_page/per_page/total.
- Ограничение окна: «api аз соати 05:00 то 23:00 ғайрифаъол мегардад» — API для массовых выгрузок активен только 23:00–05:00.
- Файлы: `POST /api/files/upload` (form-data: file, file_type) **до** создания сущности; `POST /api/files/delete`. Папки `/uploads/<сущность>/<поле>/`.
- Автодополнения: `GET /api/brand|city|external_city|route|client|direction|remain_fuel` (`?search=`, LIKE 'x%', ≤10 результатов).
- Логирование всех запросов в БД: request_type (1–6 путёвки, 7 организация, 8 сотрудник, 9 водитель, 10 транспорт), operation_type (1 добавление, 2 изменение, 3 confirm), datetime, success.

## 4. Сущности и их поля (актуальная RMA-версия)

### 4.1. Организация (`/api/organization`)
`name`, `city_name` (строкой — backend ищет город), `region_name`/`region_id` (1–7), `registration_certificate`(+attach), `iktibos`(+attach — иқтибос/выписка), `kpp`, `rma`(+attach, req), `aai`(+attach — шаҳодатномаи ААИ 18%/НДС), `license_activity_from/to`(+attach — обязательны только при type_company_id=1), `bank`, `address`, `phone`, `name_head`, `email`, `type_company_id` (1=Истифодаи умум/общего пользования, 2=Соҳавӣ/ведомственный), `number` (рамзи корхона).

### 4.2. Водитель (`/api/driver`)
`number` (табельный), `full_name`, `license`(+attach), `category` (A,B,C,D…), `degree` (класс: 1=Якум, 2=Дуюм, 3=Сеюм), `passport`(+attach), `number_lessons_20_hours`+`duration_lessons_20_hours`(+attach — талони 20-часовых занятий), `med_cert_number`+`med_cert_valid_date`(+attach — медсправка), `contract_number`+`duration_contract_number`, `rma`(+attach), `power_attorney`(+attach — ваколатнома/доверенность), `address`, `phone`, `email`, `photo`, `signature_attach`, `company_id`.

### 4.3. Транспорт (`/api/transports`)
`number` (рақами таваққуфгоҳ — номер стоянки, 4 цифры: 1-я = колонна, 3–4-я = бригада), `transport_type_id` (1=Автобус, 2=Троллейбус, 3=Микроавтобус, 4=Сабукрав, 5=Боркашони 2Б, 6=Боркашони 5ББМ), `registration_number` (госномер), `brand_id/brand_name` (тамға), `capacity` (ғунҷоиш — вместимость), `carrying` (грузоподъёмность), прицепы ядак 1/2: `number_ydak, brand_ydak, carrying_ydak, weight_ydak` (+`_2`), `indication_counter` (одометр; **для автобуса диспетчер не может менять**), `expire_checklist_number`+`date_to`(+attach — варақаи назоратӣ/контрольная карточка; обязательна при type_company_id=1), `certificate_number`, `year_manufacture` (1900–next), `vincode`, `air_conditioner`, `tech_id_number`(+attach — техпаспорт), `tech_inspection_number`+`date_to`(+attach — техосмотр), `timesheet[]` (закреплённые водители), `company_id`.

### 4.4. Сотрудник (`/api/employees`)
`number`, `name`, `type` (1=Духтур/врач, 2=Механик, 3=Танзимгар/диспетчер), `rma`, `address`, `phone`, `signature` (имзо), `seal` (муҳр — печать; у механика и врача), `company_id`.

## 5. Поля форм путевых листов

### 5.1. Т(1-АД) Автобус — waybill1ad
Шапка: organization_rma, transport_registration_number, driver_rma (timesheet_id), employee_rma (диспетчер), company_id, parking_id, route_id (req), created_at (дата ПЛ), indication_counter_exit (req, disabled, авто), schedule (реҷа/график, req), exit_date (время выезда, req), begin_path_a/b (гашти ибтидои А/Б, max 2, a — req), client_id (мизоҷ), special_mark (қайдҳои махсус), number_lap (миқдори гардиш/кругов), work_time, conditioner_time, entry_date, client_time, earning (маблағ/выручка), indication_counter_entry (один раз).
Топливо fuels[] (max 2, добавляется 1 раз, не изменяется): fuel_id (1=бензин, 2=солярка, 3=сжиж. газ; в макетах ещё 4=гази табиӣ/природный), fuel_given (дода шуд), remain_fuel_before_exit (авто из предыдущего ПЛ), additional (0–5, харҷи иловагӣ), be_given (дода шавад), coef_below_0.
Ответ: {id, number, fuels{fuel_id, remain_fuel_entry}}.

### 5.2. Т(1-АД) Троллейбус — waybill1ade
Как автобус, но **без топлива**, без client_id/client_time/conditioner_time.

### 5.3. 1-А Микроавтобус — waybill1a
Шапка: как выше + route_id (req), schedule (req), kassa (хазина), special_mark.
**work_days[] 1–4 дня** (макс одометр 650; в тз.txt — «до 4 дней, макс 1600» — расхождение): date, exit_time, entry_time, begin_path_a (req)/b, laps, indication_counter_exit/entry, client_id, client_time, conditioner_time, fuels[] (max 2).
Ответ включает doctor_id, mechanic_id, workdays.

### 5.4. 3-С Сабукрав (легковой) — waybill3c
Шапка + `type_service` (req): 1=такси, 2=хатсайр/маршрут, 3=соатбай/почасовой. route_id и schedule активны только при type_service=2; `regions_id[]` (req, 1–7) — пассивно при type_service=2.
**work_days[] 1–7 дней** (макс одометр 2800), поля как 1-А.

### 5.5. 2-Б Грузовой — waybill2b
organization_rma/kpp, transport_registration_number, driver_rma, employee_rma (req), `type_of_shipment` (1=Корбайъ/сдельно, 2=Соатбайъ/почасово), `direction_id` (самт, req), client_id (req), exit_date/entry_date, indication_counter_exit/entry, special_mark.
**trailers[]** (max 2): registration_number (req), brand (req), carrying, weight.
**work_days[] до 15 дней**: date (req), exit_time/entry_time (req), indication_counter_exit/entry (req), work_time (время работы спецоборудования), begin_path_a/b, laps, client_id, client_time, conditioner_time, fuels[] (max 1/день).
PUT: exit_date не меняется; work_days не передан → все дни удаляются.

### 5.6. 5Б-БМ Грузовой международный — waybill5bbm
company_id, parking_id, route_id (req), **два водителя**: first_driver_id (req), second_driver_id, exit_date (req), arrival_time, indication_counter_exit/entry, client_id (req),
**виза**: visa_expire_date (req), visa_country_id (req),
**маршрут**: load_country_id/load_city_id, unload_country_id/unload_city_id, transit_countries_id[] (все req),
**груз**: cargo_id (req, номгӯи бор), cargo_capacity (ҳаҷм), cargo_distance (масофа бо бор),
`bba_number` (req — рақами китобчаи ББА, книжка ББА/TIR?), special_mark, fuels[] (max 1).

## 6. Подтверждение врачом/механиком

`POST /api/waybill/confirm`: waybill_type (1–6), waybill_id, employee_id, employee_type (1=Духтур, 2=Механик, 3=Танзимгар). Ошибки: 409 «Доктор/Механик не подтвердил путёвку», 409 «This employee has already confirmed this waybill», 404 not found, 422 валидация.

## 7. Схема работы с агрегаторами (ЧУРА/Jura, НЕРУ/Neru)

`POST /api/waybill_neru`: organization_rma (req), transport_registration_number (req), driver_rma (req), employee_rma, exit_date (req), entry_date, distance (req, км).
- При новом запросе **предыдущий ПЛ закрывается**, создаётся новый со статусом «Ожидает».
- Пока врач и механик не подтвердят — любой повторный запрос возвращает «нужно пройти медосмотр/техосмотр».
- После подтверждений: 201 → объект ПЛ (id, number, exit_date, entry_date, indication_counter_exit, company{id,rma,name,kpp}, parking{id,registration_number,vincode}, timesheet{id,full_name,rma}) + status «Активный».
- `indication_counter_entry = indication_counter_exit + distance`.
- `GET /api/waybill_neru/{id}` — отдаёт только подтверждённый ПЛ.
- Ошибки: 404 (организация/ТС/водитель/ПЛ не найдены), 409 (врач/механик не подтвердил), 422 (обязательность; «дата въезда > даты выезда»; **«не более 7 дней от даты выезда»**).

## 8. Справочники-константы

- Типы ТС: 1=Автобус, 2=Троллейбус, 3=Микроавтобус, 4=Сабукрав, 5=Боркашони 2Б, 6=Боркашони 5ББМ
- Топливо: 1=Бензин, 2=Солярка, 3=Гази моеъ (сжиженный), 4=Гази табиӣ (природный — в макетах)
- type_company_id: 1=Истифодаи умум, 2=Соҳавӣ
- Регионы (1–7): 1=Душанбе, 2=ВМКБ (ГБАО), 3=Суғд, 4=РРП Рашт, 5=Хатлон-Бохтар, 6=Хатлон-Кӯлоб, 7=РРП Ҳисор
- Класс водителя: 1=Якум, 2=Дуюм, 3=Сеюм
- type_service (3-С): 1=такси, 2=хатсайр, 3=соатбай
- type_of_shipment (2-Б): 1=Корбайъ, 2=Соатбайъ
- Вид сообщения (намуди ҳамлу нақл): 0=шаҳрӣ (городское), 1=наздишаҳрӣ (пригородное), 2=байнишаҳрӣ (междугородное), 3=байналмилалӣ (международное)
- Тип сотрудника: 1=Духтур, 2=Механик, 3=Танзимгар
- Автодополнения из rohkhat.tj: brand, city, external_city, route {id, number, desc, transport_type_id}, client {id, name}, direction {id, title}, visa_country, load/unload_country/city, transit_countries, cargo

## 9. Бизнес-правила и валидации

- Порядок: заявка → медосмотр (врач) → техосмотр (механик) → выдача. Без обоих подтверждений ПЛ не выдаётся.
- Один активный ПЛ: новый запрос закрывает предыдущий (логика агрегаторов).
- Топливный остаток: remain_fuel_before_exit = remain_fuel_entry предыдущего ПЛ того же ТС/вида топлива.
- Одометр: indication_counter_exit disabled, из транспорта/предыдущего ПЛ.
- Сроки: см. таблицу §1. Правило 7 дней для агрегаторов.
- Условная обязательность: лицензия и контрольная карточка — только для type_company_id=1 (общего пользования).
- Маски ввода (из бумажных макетов): номер ПЛ — 7 цифр; номер стоянки — 4 цифры; номер водителя — 4 цифры; время — HH:mm; маршрут ≤3 цифр; график ≤2 цифр; клиент — 6 цифр; одометр — 6 цифр; топливо ≤3 цифр; круги ≤2 цифр; выручка ≤5 цифр (3 сомони + 2 дирама).
- HTTP-коды: 400 текст ошибки, 404 не найдено, 409 конфликт подтверждений, 422 валидация под полями, 500 сервер.

## 10. Концепция нового процесса (из «Юридическое лицо...»)

- Заявитель — юрлицо с правом транспортной деятельности; данные заполняются один раз, проверяются автоматически через налоговый орган (без загрузки учредительных документов при наличии API).
- ТС: госномер, марка/модель, тип, год, VIN, **форма владения (собственность/аренда/лизинг)** — проверка через ГАИ/реестр ТС.
- Водитель: из зарегистрированных; проверка ВУ через ГАИ, статус сотрудника — через налоговую.
- Рейс: дата/время начала и окончания, маршрут, цель, тип перевозки.
- Три подтверждающих роли с ЭП: диспетчер (формирует), механик (техисправность), медработник (допуск).
- После подтверждений ПЛ = «Активен»; бумажный ПЛ не требуется.

## 11. Противоречия исходников (решить в новом ТЗ)

1. Идентификация ИНН vs RMA → брать RMA.
2. 1-А: макс одометр 650 vs 1600 → уточнить у заказчика.
3. Нумерация типа троллейбуса (1adt/1ade) → зафиксировать единый код.
4. transport_type_id в примерах непоследователен → нормализовать справочник.
5. 4М-БМ упомянут без API → в новом ТЗ описать полноценно.
