-- Скрыть в карточках поля, которые по боевой базе старой платформы не используются
-- (spec/АНАЛИЗ-полей-по-боевой-базе-2026-09-24.md; решение владельца 24.09.2026 «поля, которые не
-- используются, логично убрать»). Поле только СКРЫВАЕТСЯ в формах: столбцы и уже внесённые значения
-- остаются, вернуть — Настройки → Поля … → «Вернуть по умолчанию».
-- Применяется только там, где администратор ещё не настраивал форму сам (значение пустое).
--   компания: КПП (1,8 % у активных компаний), «отметка на карте» (2 %, дублирует широту/долготу);
--   транспорт: мощность (нет в старой базе, не участвует ни в расчётах, ни в проверках, ни в печати),
--              прицеп 2 — 4 поля (≈1 %);
--   водитель: медограничения (нет в старой базе, не участвуют ни в проверках, ни в печати).
update platform_setting set setting_value = '{"kpp":"hidden","mapPoints":"hidden"}'
 where category = 'forms' and setting_key = 'organization' and coalesce(setting_value, '') = '';
update platform_setting set setting_value = '{"enginePower":"hidden","trailer2Number":"hidden","trailer2Brand":"hidden","trailer2Carrying":"hidden","trailer2Weight":"hidden"}'
 where category = 'forms' and setting_key = 'vehicle' and coalesce(setting_value, '') = '';
update platform_setting set setting_value = '{"medRestrictions":"hidden"}'
 where category = 'forms' and setting_key = 'driver' and coalesce(setting_value, '') = '';
