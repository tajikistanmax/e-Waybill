# 2026-09-21 — Отчёты и журналы: выборка ПЛ потоком по периоду вместо `findAll()` (OutOfMemoryError после Ф5)

## Что изменилось
Все отчётные сервисы waybill-service перестали загружать таблицу `waybill` целиком в память:
- новый `WaybillPeriodScan` — проход по ПЛ периода `[from 00:00, to 24:00)` (по `created_at`, как раньше)
  JPA-потоком (fetch size 500) с очисткой persistence-context каждые 500 строк и **предохранителем**:
  если в периоде больше `epd.reports.max-rows` ПЛ (по умолчанию 100 000) — HTTP 422 «Слишком большой объём
  для построчного отчёта … сузьте период или выберите организацию»;
- `ReportService` (сводка главной панели, журнал диспетчера, по водителям, по ТС, топливо): проход потоком;
  суммы выданного топлива и выручки и разрез по видам топлива считает БД (`FuelRecordRepository`/
  `WorkDayRepository` — агрегатные JPQL по периоду и области);
- `WaybillReportService` (14 типовых разрезов пассажирских/грузовых), `InspectionJournalService`
  (журналы механика/врача), `RegionalReportService` (норматив выдачи, сводный перевозок текущий/прошлый год,
  «Количество ПЛ» 19 счётчиков, тренд пассажирооборота) — те же проходы потоком по периоду;
- `FuelStationController.list` — ПЛ организации «на заправке» выбираются по статусам в БД, а не все ПЛ
  организации в память;
- миграция `V25__waybill_period_indexes.sql` — индексы `waybill(created_at)`, `(organization_rma, created_at)`,
  `(status, created_at)`: периодные выборки шли полным сканом 2,3 млн строк;
- **вторая находка live (429 → 500):** отчёты дёргали master-data по каждому ПЛ (`findRoute` →
  `GET /dictionaries/routes`) и по каждой из тысяч организаций (`listVehicles` в «Нормативе выдачи»), упираясь
  в rate-limit master-data (300 запросов/мин с одного IP). Сделано: `TtlCache` (60 с) для маршрутов и
  организаций в `MasterDataClient` (ключ — SHA-256 bearer вызывающего, области тенантов не смешиваются);
  новый агрегатный эндпоинт master-data `GET /api/v1/vehicles/count-by-organization?transportType=`
  (одна выборка вместо N+1) и его использование в `RegionalReportService.waybillNorm`; 429 и недоступность
  master-data теперь отдаются как 503 с `Retry-After`, а не 500 (`ApiErrors`).

## Как было ДО
Каждый из этих сервисов делал `waybills.findAll()` (а сводка — ещё и `fuelRecords.findAll()`,
`workDays.findAll()`) и фильтровал период/организацию в памяти. Пока ПЛ были сотни — работало. После
миграции Ф5 (21.09, ~2,28 млн архивных ПЛ legacy) любой отчёт платформенной роли — включая **главную панель**
администратора (сводка + тренд) — поднимал миллионы сущностей с JSONB-снимками и ронял сервис в
`java.lang.OutOfMemoryError: Java heap space` при `-Xmx384m` (потоки Acceptor/Poller Tomcat погибали,
сервис переставал отвечать без рестарта контейнера). Воспроизведено на локальном стенде 21.09 08:32.

## Как стало
Память ограничена независимо от объёма таблицы; время построчных отчётов пропорционально числу ПЛ в
периоде (за месяц по всей стране в архиве ~35–40 тыс. — допустимо); заведомо неподъёмные периоды
отклоняются с понятным текстом вместо падения сервиса. Семантика периодов/областей не менялась
(границы дня — зона сервера, как прежний `createdAt.toLocalDate()`).

## Почему
Без этого стенд с боевым объёмом неработоспособен для админа/аналитика (первая же загрузка главной панели —
OOM), а значит невозможны ни живые проверки Шага 2, ни демонстрация исторических отчётов, ради которых
владелец решил грузить всю историю (вариант B).

## Кто решил
Агент Claude — блокер живых проверок; подход (потоковая выборка + предохранитель, а не полный переход на
SQL-агрегацию) выбран как минимальный и безопасный. Полноценная SQL-агрегация отчётов на архиве —
отдельная задача (MIGRATION.md 8.10). Владельцу сообщено.

## Как проверено
- `WaybillPeriodScanTest` (6 кейсов: границы, стрим + clear, область/пустая область, предохранитель 422,
  невалидный период, count).
- `TtlCacheTest` (3). Полный `:waybill-service:test`: 239 (237 ✅ + 2 Testcontainers-окружение); `:master-data-service:test` 62/62 ✅.
- **Live (локальный стек, БД waybill 2,28 млн строк, admin = все организации), 14 вызовов подряд:**
  сводка Sep-2026 (643 ПЛ) 0,5 с · Aug-2026 (10 993) 0,6 с · весь 2026 (394 461 ПЛ) 9,4 с · по ТС Aug 1,1 с ·
  тренд 7 мес 1,4 с · типовой BY_VEHICLE Sep 4,4 с · BY_VEHICLE за год → **422** (предохранитель) ·
  журнал механика 1,3 с · сводный перевозок Aug 0,14 с · «Количество ПЛ» Aug (скан 20 мес ≈ 1,1 млн) 23 с ·
  норматив выдачи 0,7 с (до фикса — 429/500) · кабинет топливника 0,08 с. Память контейнера 498 → 604 МБ,
  health UP на всём протяжении. До фикса первая же сводка/тренд админа → OOM и мёртвый сервис.

## Затронутые файлы
- `.../service/WaybillPeriodScan.java` — новый; `.../repository/WaybillRepository.java` — count/stream по периоду,
  `findByOrganizationRmaAndStatusInOrderByCreatedAtDesc`; `FuelRecordRepository.java`, `WorkDayRepository.java` — агрегаты.
- `.../service/ReportService.java` (переписан), `WaybillReportService.java`, `InspectionJournalService.java`,
  `RegionalReportService.java`, `.../web/FuelStationController.java`.
- `.../test/.../service/WaybillPeriodScanTest.java` — новый.
- `.../resources/db/migration/V25__waybill_period_indexes.sql` — новая.
- `.../client/MasterDataClient.java` (кэш, `countVehiclesByOrganization`), `.../client/TtlCache.java` (новый),
  `.../test/.../client/TtlCacheTest.java` (новый), `.../web/error/ApiErrors.java` (429/недоступность → 503).
- master-data: `.../repository/VehicleRepository.java` (`countByOrganization*`), `.../web/VehicleController.java`
  (`GET /api/v1/vehicles/count-by-organization`).

## Коммиты (ветка `migration`)
- `b26e5d5` — perf(reports): выборка ПЛ потоком по периоду вместо findAll(), агрегаты в БД, индексы V25, кэш справочников, 429→503

## Как откатить
- `git revert <hash>` + пересборка `waybill`. Данных не затрагивает. Откат вернёт OOM на боевом объёме.
