# 2026-09-22 — GPS-события Smart-city: заезд/выезд на маршрут и в предприятие, журнал по компании (MIGRATION.md §9.7, §8.9, §12.12)

## Что изменилось
- **waybill-service** — событийная модель legacy `gps_data` (`GpsDataController::store`, `StoreGpsDataRequest`,
  `GpsdataCrudController`): таблица `gps_event` (V28), `GpsEvent`/`GpsEventState`
  (`ENTER_INTO_ROUTE | EXIT_FROM_ROUTE | ENTER_INTO_COMPANY | EXIT_FROM_COMPANY`, legacy-коды 1..4),
  `GpsEventRules` (правила), `GpsEventService` (регистрация + журнал).
- **API**: `POST /api/v1/gps/events` (API_INTEGRATOR / SYSTEM_ADMIN — как legacy `company.jwt:2`, один сервисный
  аккаунт): `vehicleRegNumber` (принимается и `registration_number`), `state` (snake_case / enum / код),
  `direction` A|B (и кириллица А/Б), `distanceKm` (и `distance`), `eventTime` (по умолчанию — сейчас).
  Правила 1-в-1: направление обязательно для маршрутных состояний, дистанция — для въезда в предприятие;
  ПЛ дня по госномеру (виды `epd.gps.event-waybill-types`, по умолчанию Т 1-АД: `WB_BUS,WB_TROLLEYBUS`) — иначе
  422 «Путевой лист для данного транспортного средства не найден.»; повтор того же состояния (для маршрутных —
  того же направления) в тот же день не раньше `epd.gps.event-cooldown-minutes` (20) — 422 с legacy-текстами;
  выезд с маршрута — не раньше 20 мин после въезда на него. Ответ выезда из предприятия — как legacy:
  `routeNumber`, `schedule`, `exitTime` ПЛ.
  `GET /api/v1/gps/events` — журнал (DISPATCHER/COMPANY_ADMIN/SYSTEM_ADMIN/INSPECTOR/MINTRANS_ANALYST): фильтры
  организация (платформенные роли; тенант — своя область), госномер, состояние, период, поиск; серверная пагинация.
- **Web** `/monitoring` — третий вид «События»: таблица как в legacy (госномер, водитель, компания, маршрут,
  направление, событие, время фиксации, дистанция, дата записи), фильтр компании/состояния/периода/поиск, страницы.
- **Конфиг**: `GPS_EVENT_COOLDOWN_MINUTES` (20), `GPS_EVENT_WAYBILL_TYPES` (`WB_BUS,WB_TROLLEYBUS`).

## Как было ДО
В e-Waybill — только сырые GPS-пинги трекеров (`POST /api/v1/gps`, live/track). Событий камер/постов Smart-city,
правил интервалов и журнала по компании не было (строки 9.7/8.9/12.12 — Вопрос 12).

## Как стало
Паритет с legacy: интеграция шлёт события по госномеру, платформа привязывает их к ПЛ дня, отбивает повторы и
ведёт журнал с фильтром по компании; поля legacy-API принимаются как есть.

## Почему
MIGRATION.md 9.7/8.9/12.12 — решение владельца 22.09 «делать все пять» (Вопрос 12).

## Кто решил
Владелец (22.09). Не перенесён баг legacy: интервалы там считались по времени суток `H:i:s` — событие после
полуночи давало отрицательную разницу и ложный отказ; у нас — полные метки времени (зафиксировано в
`GpsEventRules`, тест `exitFromRouteMustFollowEnterByCooldown`). Виды ПЛ и порог вынесены в настройки.

## Как проверено
- `GpsEventRulesTest` (4), `GpsEventServiceTest` (3: нет ПЛ дня → 422; повтор с учётом направления; ответ
  выезда из предприятия), `GpsEventControllerSecurityTest` (16: регистрация — только интеграция/админ, журнал —
  роли, неверное состояние → 422). Suite waybill **324** (322 ✅ + 2 `initializationError` Testcontainers —
  окружение), `tsc --noEmit` ✅. Flyway waybill v28.
- **Live** (`scratchpad/live-97.ps1`, локальный стек): создан ПЛ Т 1-АД дня для 0114TJ01 (маршрут 3, график 1);
  DRIVER → 403; неизвестное ТС / маршрут без направления / въезд без дистанции / состояние `teleport` → 422 с
  legacy-текстами; `exit_from_company` в legacy-именах полей → 201 с `routeNumber=3, schedule=1, exitTime`;
  повтор → 422 «через 20 минут»; `enter_into_route A` → 201, повтор A → 422, B → 201 (другое направление);
  `exit_from_route A` через минуту после въезда → 422; `enter_into_company distance 12.5` → 201. Журнал
  диспетчера: 4 события с ФИО водителя и названием компании; фильтр по состоянию — только въезды на маршрут;
  чужая организация у админа → 0; поиск по водителю → 4; DRIVER → 403.

## Затронутые файлы
- waybill-service: `db/migration/V28__gps_event.sql`, `domain/GpsEvent.java`, `domain/GpsEventState.java`,
  `repository/GpsEventRepository.java`, `repository/WaybillRepository.java` (ПЛ дня по госномеру),
  `service/GpsEventRules.java`, `service/GpsEventService.java`, `web/GpsController.java`, `application.yml`;
  тесты `GpsEventRulesTest`, `GpsEventServiceTest`, `GpsEventControllerSecurityTest`.
- web: `app/monitoring/page.tsx`, `packages/shared/lib/api.ts` (`wb.gpsEvents`, типы), `packages/shared/lib/i18n.tsx`.
- `MIGRATION.md` — 9.7, 8.9, 12.12 → [x]; Вопрос 12 закрыт.

## Коммиты (ветка `migration`)
- (заполняется после коммита)

## Как откатить
- `git revert <hash>` + пересборка `waybill`, `web`. Таблица `gps_event` остаётся (без кода не используется).
