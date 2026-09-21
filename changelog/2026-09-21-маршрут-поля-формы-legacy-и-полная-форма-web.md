# 2026-09-21 — Маршрут: поля формы legacy (пункты А/Б, время рейса, свидетельство, город, координаты) и полная форма в web (MIGRATION.md §2.28)

## Что изменилось
- master-data, миграция `V67__route_form_fields.sql`: `route.name_a, name_b VARCHAR(100)`, `time_one_lap_a/b TIME`,
  `valid_cert DATE`, `city_name VARCHAR(200)`, `latitude/longitude NUMERIC(10,6)`.
- `Route` — поля/геттеры; `POST /api/v1/dictionaries/routes` принимает `nameA, nameB, timeOneLapA, timeOneLapB
  (HH:mm), validCert (yyyy-MM-dd), cityName, latitude (−90…90), longitude (−180…180)`; `GET` отдаёт их.
- Web `/dictionaries/routes`: форма теперь содержит **все** поля маршрута — новые (V67) и путевые
  показатели/коэффициенты V28 (`distanceA/B, beginPathA/B, plannedLap, coeUseCapacity, averageLengthPassSeat,
  stationCoef, roadQuality, mountainCoefValue, inCityCoefValue, winterCoefId, additionalFuel100/additionalFuel,
  condFuel, heatingFuel, excludingCoef`), которые раньше правились только через API; город — с подсказками из
  справочника `city`; в таблице — пункты А—Б, город, срок свидетельства. i18n ru/tj (`route.f.*`).
- Тест `RouteRequestValidationTest` (2): корректная форма / без координат — без нарушений; широта 91 и долгота −181 —
  нарушения диапазонов.

## Как было ДО
Legacy форма маршрута (`App\Models\Route::$fields`, `RouteCrudController`) содержала `name_a/name_b`
(«Номгӯи хатсайр A/B»), `time_one_lap_a/b` («Вақт дар як гардиш»), `valid_cert` («Муҳлати вобастакунии
шаҳодатнома»), `city` («Шаҳру ноҳия»), `latitude/longitude`, `week_days_earnings`. В e-Waybill этих полей не было;
кроме того, web-форма маршрута показывала только номер/название/тип ТС/регион/тип маршрута — коэффициентные и
путевые поля V28 можно было задать только API-вызовом.

## Как стало
Справочник маршрутов равен legacy по составу полей (кроме `week_days_earnings`) и полностью редактируется из web.
`desc` legacy («Номгӯи хатсайр») соответствует нашему `name`; печать 1-А/3-С (`number - name_a name_b` в legacy)
у нас выводит `number` + `name` — не менялась.

## Что намеренно НЕ перенесено
- `week_days_earnings` (план выручки по дням недели): в legacy нигде не используется, кроме модели и формы
  (grep по `app/` и `resources/views`) — Вопрос 14 MIGRATION.md; безопасно не переносить.

## Почему
MIGRATION.md 2.28 — гэп полей справочника (Шаг 1). Заодно закрыт UI-гэп: V28-поля без формы.

## Кто решил
Агент Claude по промпту Шага 2 (однозначный перенос полей формы). Подтверждено по коду legacy, что `time_one_lap`,
`valid_cert`, `latitude/longitude`, `city` маршрута не участвуют ни в расчётах, ни в отчётах, ни в печати.

## Как проверено
- `RouteRequestValidationTest` (2); `:master-data-service:test` **70/70 ✅**; web `tsc --noEmit` ✅;
  `/dictionaries/routes` на пересобранном web — 200.
- **Live (локальный стек, org 025680800):** V67 применилась («now at version v67»); `POST` маршрута с
  `nameA/nameB`, `timeOneLapA=00:45`, `timeOneLapB=00:50`, `validCert=2027-01-01`, `cityName`, координатами
  38.5598/68.787 и V28-полями (`distanceA=12.5`, `excludingCoef=true`) → HTTP 201, все поля в ответе;
  `latitude=91` → HTTP 400; `GET` возвращает город/время/срок; справочник городов для подсказок — 69 строк.

## Затронутые файлы
- `apps/backend/master-data-service/src/main/resources/db/migration/V67__route_form_fields.sql` — новая.
- `apps/backend/master-data-service/src/main/java/tj/mintrans/epd/masterdata/domain/Route.java`,
  `.../web/DictionaryController.java` (`RouteRequest`, `upsertRoute`).
- `apps/backend/master-data-service/src/test/java/tj/mintrans/epd/masterdata/web/RouteRequestValidationTest.java` — новый.
- `apps/web/app/dictionaries/DictionariesView.tsx`, `packages/shared/lib/i18n.tsx`.
- `MIGRATION.md` — 2.28 → [x].

## Коммиты (ветка `migration`)
- (заполняется после коммита)

## Как откатить
- `git revert <hash>` + пересборка `master-data`, `web`. Колонки в БД останутся (безвредны).
