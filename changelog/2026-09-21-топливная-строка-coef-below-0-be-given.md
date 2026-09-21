# 2026-09-21 — Топливная строка 1-в-1: `coef_below_0` и `be_given` в модели, `additional`/`coef_below_0` в живом расчёте (MIGRATION.md §5.6)

## Что изменилось
В запись топлива путевого листа (`fuel_record`) добавлены два поля legacy «Роҳхат»: **`coef_below_0`**
(«Коефитсенти ҳарорати аз 0 поён» — надбавка при температуре ниже 0 °C, л) и **`be_given`** («Дода шавад» —
норма к выдаче, л). Сборщик входа расчёта `WaybillCalcAssembler` теперь передаёт в движок реальные
`coef_below_0` и `additional` (= `additional_given`, «довыдано в пути») вместо нулей. Поля доступны в API
(`POST /waybills/{id}/fuel`, `/fuel-station/waybills/{id}/fuel`, ответы списков), в форме топлива карточки ПЛ
и в кабинете пункта выдачи топлива (форма + таблица).

## Как было ДО
- `fuel_record`: fuel_given / remain_before_exit / remain_entry / additional_given / returned. Полей
  `coef_below_0` и `be_given` не было — оператор не мог их ввести.
- `WaybillCalcAssembler.fuelLines()` строил `CalcFuelLine(fuelType, fuelGiven, 0, 0, remainBeforeExit)`:
  надбавка «ниже 0» и довыдача в пути **всегда нули** в живом расчёте, хотя движок (`FuelNormCalculator.givenByFuel`,
  `WaybillCalcEngine`) их поддерживал с Фазы 1 и тесты на них были только на уровне движка.
- Итог: для ПЛ с довыдачей/зимней надбавкой «выдано» и «остаток при возврате» в `/calculation` и отчётах
  считались неверно (занижены на coef_below_0 + additional).

## Как стало
- Миграция `V24__fuel_record_coef_below_0_be_given.sql` (+2 колонки NUMERIC(8,2), nullable — обратная совместимость).
- `FuelRecord` +`coefBelow0`/`beGiven`; `WorkDayService.addFuel` — новая перегрузка на 10 аргументов (старые
  делегируют), валидация «не отрицательно» распространена на новые поля (legacy: `numeric|min:0`).
- `WorkDayController.FuelRequest`, `FuelStationController.RecordFuelRequest`/`FuelLine` — новые поля.
- `WaybillCalcAssembler.toCalcFuelLines(records)` (пакетно-видимый статический маппинг) — coef_below_0 и
  additional_given уходят в `CalcFuelLine`.
- Web: тип `FuelLine`, i18n `wbd.fuelcoef0`/`wbd.fuelbegiven`, поля в форме топлива карточки ПЛ и в `/fuel`
  (форма + 2 колонки таблицы).
- Формула, как в оригинале: `fuel_calc()` — выдано = fuel_given + coef_below_0; остаток при возврате =
  остаток до выезда + выдано(с надбавкой) + довыдано − норматив. `be_given` в формулах не участвует (хранимое).

## Почему
Перенос 1-в-1 (MIGRATION.md §5.6, найдено Шагом 1 механической сверки): в legacy эти поля есть в каждой
топливной строке всех форм ПЛ и участвуют в расчёте топлива и отчётах (тип 10 — колонки `additional`, `be_give`).

## Кто решил
Владелец: перенести всю логику 1-в-1, работать автономно. Реализовал агент Claude. Спорное: в legacy остаток
при возврате для 1-А/3-С считается по дням (MBusTrait) и без coef_below_0, а для 1-АД вводится вручную;
e-Waybill использует единую формулу движка (перенесена из rohkhat-v2 и ранее сверена с helpers.php) — формулу
не менял, только подал в неё реальные значения. Посуточная модель — по-прежнему за гейтом B10 (Вопрос 4).

## Как проверено
- Тест `WaybillCalcAssemblerFuelLinesTest` (маппинг записей → строки расчёта, null-safe).
- Тест `WaybillCalcEngineTest.coefBelow0AndAdditionalAffectGivenAndRemain` — эталон legacy `fuel_calc`:
  80 + 2 = 82 выдано; остаток 10 + 82 + 3 − 68.82 = 26.18 (с нулями было бы 21.18).
- Полный `:waybill-service:test`: 225 тестов, 223 ✅ + 2 `initializationError` (Testcontainers, известное
  ограничение Docker Desktop — не регрессия); `tsc --noEmit` ✅.
- **Live (локальный стек, 21.09):** ПЛ 01-26-04-0000130-0 (WB_BUS, ТС 0114TJ01 «Акиа», дизель):
  `POST /waybills/{id}/fuel {fuelGiven:80, remainBeforeExit:10, additionalGiven:3, coefBelow0:2, beGiven:85}` →
  сохранено и читается (`coefBelow0=2.00`, `beGiven=85.00`); `POST /calculation` → `fuel 2: given=82.0`
  (80 + 2, как legacy `fuel_calc`), `additional=3.0`, `remainEntry=95.0` (10 + 82 + 3 − 0; пробег 0 → норма 0);
  `coefBelow0=-1` → HTTP 422. Кабинет `/fuel` и карточка ПЛ показывают новые поля (tsc ✅, страницы 200).

## Затронутые файлы
- `apps/backend/waybill-service/src/main/resources/db/migration/V24__fuel_record_coef_below_0_be_given.sql` — новая.
- `.../domain/FuelRecord.java`, `.../service/WorkDayService.java`, `.../web/WorkDayController.java`,
  `.../web/FuelStationController.java`, `.../calc/WaybillCalcAssembler.java`.
- `.../test/.../calc/WaybillCalcAssemblerFuelLinesTest.java` (новый), `WaybillCalcEngineTest.java` (+1 тест).
- `packages/shared/lib/api.ts`, `packages/shared/lib/i18n.tsx`, `apps/web/app/waybills/[id]/page.tsx`,
  `apps/web/app/fuel/page.tsx`.

## Коммиты (ветка `migration`)
- (заполняется после коммита)

## Как откатить
- `git revert <hash>` + пересборка `waybill`/`web`. Колонки `coef_below_0`/`be_given` останутся в БД (nullable,
  безвредны); при желании — `ALTER TABLE fuel_record DROP COLUMN coef_below_0, DROP COLUMN be_given`.
