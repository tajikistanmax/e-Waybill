# 2026-09-26 — Ответы каналов НЕРУ (агрегатор) и GPS Smart City — как в legacy

## Что изменилось
**waybill-service:**
- `web/AggregatorController.java`:
  - тела ответов и ошибок — как `Waybill3cController::waybill_neru` и `StoreWaybill3cNeruRequest`;
  - поля проверяются вручную с legacy-сообщениями; все ошибки возвращаются разом в `errors`.
- `service/AggregatorService.java` — новый `submit(...)` с legacy-семантикой повторного запроса. `create(...)` оставлен, его вызывает интеграционный тест.
- `repository/WaybillRepository.java` — поиск последнего открытого листа агрегатора по организации, ТС и водителю.
- `web/GpsController.java` (`POST /api/v1/gps/events`) — ответы как `GpsDataController::store`.
- `service/GpsEventService.java`, `GpsEventRules.java` — ошибки с полем запроса.
- `web/error/ApiErrors.java` — `FieldException`: 422 с именем поля. Для остальных клиентов — обычная 422.

**Скрипты** (`smoke-test.ps1`, `concurrency-test.ps1`, `seed-demo-waybills.ps1`) — в заявке агрегатора добавлен `entry_date`.

**Тесты:**
- `AggregatorControllerLegacyTest` (4);
- `AggregatorServiceSubmitTest` (4);
- `GpsEventControllerSecurityTest` — +1 и успех теперь 200.

## Как было ДО
Агрегатор (`/api/v1/aggregator/waybills`):
- **Каждый** `POST` аннулировал открытые заявки агрегатора на это ТС и водителя и создавал новую. НЕРУ опрашивает тем же запросом, пока врач и механик не подтвердят, поэтому заявка пересоздавалась при каждом опросе и не доживала до осмотров.
- Новая заявка: `201 {id, status, message}`, без `statusCode` и `waybill_id`.
- Ошибки в формате RFC 7807 (`{type, title, status, detail}`) вместо `{statusCode, error}` и `{statusCode, message, errors}`.
- `entry_date` необязателен, формат только `yyyy-MM-dd HH:mm` (с секундами — 400). Текст ошибки «не более чем на 7 дней» при реальном пределе 30.

GPS (`/api/v1/gps/events`):
- Успех: `201` с объектом события в camelCase.
- Ошибки в формате RFC 7807. Smart City ждёт `{success, route_number, schedule, exit_time}` и `{success:false, errors:{поле:[…]}}`.

## Как стало
Агрегатор — как legacy `waybill_neru`:

| Ситуация | Ответ |
|---|---|
| Есть открытый лист этой организации, ТС и водителя, и сегодня между днём выезда и днём въезда | лист **возвращается**, а не пересоздаётся |
| — врач ещё не подтвердил | 409 `{statusCode:409, error:"Доктор не подтвердил путёвку"}` |
| — механик ещё не подтвердил | 409 `{statusCode:409, error:"Механик не подтвердил путёвку"}` |
| — оба подтвердили | 200 `{statusCode:200, id, number, exit_date, entry_date, indication_counter_exit, company, parking{…, transport_type_id}, timesheet, status}` |
| Нет действующего, дата выезда не сегодня | 422 `errors.exit_date` «Дата выезда должна быть сегодняшней» |
| Нет действующего, дата выезда сегодня | прежние заявки агрегатора закрываются, новая: 201 `{statusCode:201, message, waybill_id}` (+ `id`, `status`) |
| Организация, ТС или водитель не найдены | 404 `{statusCode:404, error:"… not found"}` |
| Ошибки полей | 422 `{statusCode:422, message:"The given data was invalid.", errors:{поле:[…]}}` |

Правила полей:
- `entry_date` обязателен;
- даты в формате `Y-m-d H:i` или `Y-m-d H:i:s`;
- дата въезда не раньше сегодня и не позже выезда + 30 дней;
- `organization_rma` — 9 или 10 цифр.

`GET /{id}` — 200 с тем же объектом или 409/404 в том же виде.

GPS:
- Успех — 200 `{success:true}`, при `exit_from_company` ещё `route_number`, `schedule`, `exit_time`.
- Ошибка — 422 `{success:false, message:"Ошибка валидации", errors:{поле:[…]}}`. Поля: `registration_number`, `state`, `direction`, `distance`, `waybill`, `transport`.

Для клиентов платформы в ошибках оставлен `detail`, в ответах добавлены `event_id` и `status`.

## Почему
Пункт G3 сверки 25.09: внешние системы НЕРУ и Smart City написаны под ответы legacy. При переключении на платформу они не поняли бы ответы. Кроме того, опрос НЕРУ бесконечно пересоздавал бы заявку.

## Кто решил
- Задача — владелец (полная сверка).
- Контракт и семантика взяты из кода legacy: `Waybill3cController::waybill_neru`, `waybill_neru_get`, `StoreWaybill3cNeruRequest`, `GpsDataController::store`, `StoreGpsDataRequest`.
- Решения исполнителя:
  - «Лист в силе» — только открытые (не аннулированные и не закрытые) листы агрегатора. В legacy статусов не было; лист, снятый инспектором, мы не отдаём как действующий.
  - Ошибка «ТС не найдено» у GPS приходит как `errors.waybill`: без обращения к справочнику ТС на каждое событие мы не отличаем «нет ТС» от «нет листа дня».
  - Предел 30 дней оставлен (как в legacy-реквесте, решение от 22.09); исправлен только текст ошибки.

## Как проверено
- waybill-service: 515 тестов. Не прошли только 2 известных Testcontainers-теста (окружение); новые и изменённые — зелёные.
- Стенд, `scratchpad/g3-live.ps1`, токен агрегатора:
  - `POST` → 201 `{"statusCode":201,…,"waybill_id":…}`;
  - повторный `POST` → 409 «Доктор не подтвердил путёвку», заявка **та же**;
  - `GET` → 409;
  - неверные поля → 422 с `errors` по шести полям;
  - неизвестная организация → 404 `{"statusCode":404,"error":"Organization not found"}`;
  - GPS: неизвестное ТС → 422 `errors.waybill`, без `direction` → 422 `errors.direction`;
  - заявка отменена после проверки.
- `scripts/smoke-test.ps1` — 35/35 ✅.

## Коммиты
- `<hash>` — feat(интеграции): ответы НЕРУ и GPS Smart City как в legacy (G3)

## Как откатить
`git revert <hash>` + пересборка `waybill`.

Внешние системы, уже настроенные на новый формат, после отката снова получат ответы RFC 7807.
