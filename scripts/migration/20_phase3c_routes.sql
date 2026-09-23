-- ============================================================================
-- ФАЗА 3c — маршруты, которые фазы 3/3b не перенесли. Файл 3/3.
-- Предполагается, что stg_routes_3c загружен (run_phase1b.ps1 сделал COPY).
--
-- ПОЧЕМУ НУЖНА. Архивный путевой лист хранит маршрут текстом «name_a - name_b»
-- (Ф5), отчёт ищет его в справочнике по номеру или названию. Фазы 3/3b вставляли
-- маршруты с дедупом DISTINCT ON (организация, номер) и ключом uq_route_org_number:
-- если у организации в legacy ДВА маршрута с одним номером (17 групп, 35 строк),
-- оставался только маршрут с большим id, остальные молча терялись. Пример: у
-- «Автобуси 2» (040000796) номер «1» у маршрутов id 25 «кучаи Гагарин - терминали
-- Чануби» и id 209 «Терминали Дарвозаи Ҷанубӣ - Саристгоҳи Ҷанубӣ» — остался 209,
-- и 323 листа июля по маршруту 25 получили пассажирооборот 0. Так же пропускались
-- маршруты без номера.
--
-- ЧТО ДЕЛАЕТ. ТОЛЬКО INSERT маршрутов legacy, которых у организации нет ПО НАЗВАНИЮ
-- (lower(name) в пределах организации). Существующие маршруты не трогает.
--   * номер свободен в организации — ставится как есть;
--   * номер занят (дубль legacy) — «<номер>/<legacy id>» (например «1/25»), чтобы не
--     нарушить uq_route_org_number и не перезаписать чужой маршрут;
--   * номера нет — «б/н <legacy id>».
-- Путевые и коэффициентные поля — как в Ф3; дополнительно пункты А/Б (V67) и
-- коэффициенты legacy: горный и городской — ЗНАЧЕНИЯ (как складывает их getCoef),
-- зимний — ссылка на наш fuel_winter_coef по имени периода.
-- Маршруты без организации (company_id пуст/не перенесена) пропускаются.
-- Повторный прогон ничего не вставляет (маршрут уже найдётся по названию).
-- ============================================================================

SET client_encoding TO 'UTF8';

CREATE TEMP TABLE tmp_route_src AS
SELECT r.*,
       o.rma AS org_rma,
       left(COALESCE(
         NULLIF(btrim(concat_ws(' - ', NULLIF(btrim(r.name_a), ''), NULLIF(btrim(r.name_b), ''))), ''),
         'Хатсайр ' || COALESCE(NULLIF(btrim(r.number), ''), r.id)), 300) AS route_name
FROM stg_routes_3c r
JOIN organization o ON o.rma = btrim(r.company_rma)
WHERE btrim(COALESCE(r.company_rma, '')) <> '';

-- Один legacy-маршрут на (организация, название): при дубле названия — больший id.
-- num_rank > 1 — номер повторяется и среди самих вставляемых: такие тоже получают суффикс.
CREATE TEMP TABLE tmp_route_missing AS
SELECT d.*,
       row_number() OVER (PARTITION BY d.org_rma, left(btrim(COALESCE(d.number, '')), 10)
                          ORDER BY d.id::bigint DESC) AS num_rank
FROM (
  SELECT DISTINCT ON (s.org_rma, lower(s.route_name)) s.*
  FROM tmp_route_src s
  WHERE NOT EXISTS (SELECT 1 FROM route t
                     WHERE t.organization_rma = s.org_rma
                       AND lower(btrim(t.name)) = lower(btrim(s.route_name)))
  ORDER BY s.org_rma, lower(s.route_name), s.id::bigint DESC
) d;

SELECT count(*) AS routes_to_insert,
       count(*) FILTER (WHERE btrim(COALESCE(number, '')) = '') AS without_number,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM route t WHERE t.organization_rma = m.org_rma
                                        AND t.number = left(btrim(m.number), 10))) AS number_taken
FROM tmp_route_missing m;

INSERT INTO route(
  id, organization_rma, number, name, transport_type, region_id, route_type_code,
  winter_coef_id, mountain_coef_value, in_city_coef_value,
  station_coef, road_quality, excluding_coef, additional_fuel_100, additional_fuel,
  cond_fuel, heating_fuel, distance_a, distance_b, begin_path_a, begin_path_b,
  planned_lap, coe_use_capacity, average_length_pass_seat, name_a, name_b)
SELECT
  gen_random_uuid(),
  m.org_rma,
  CASE
    WHEN btrim(COALESCE(m.number, '')) = '' THEN left('б/н ' || m.id, 10)
    WHEN m.num_rank > 1
      OR EXISTS (SELECT 1 FROM route t WHERE t.organization_rma = m.org_rma
                   AND t.number = left(btrim(m.number), 10))
      THEN left(btrim(m.number), greatest(1, 10 - length('/' || m.id))) || '/' || m.id
    ELSE left(btrim(m.number), 10)
  END,
  m.route_name,
  CASE WHEN m.transport_type_id ~ '^[0-9]+$' THEN m.transport_type_id::smallint END,
  CASE WHEN m.region_id ~ '^[0-9]+$' THEN m.region_id::smallint END,
  CASE WHEN m.type_id ~ '^[0-9]+$'
            AND EXISTS (SELECT 1 FROM route_type rt WHERE rt.code = m.type_id::smallint)
       THEN m.type_id::smallint END,
  (SELECT w.id FROM fuel_winter_coef w
    WHERE lower(btrim(w.name)) = lower(btrim(m.winter_name)) ORDER BY w.id LIMIT 1),
  CASE WHEN m.mountain_coef_id ~ '^[0-9]+$' AND m.mountain_coef_id::int > 0 THEN m.mountain_coef_id::smallint END,
  CASE WHEN m.in_city_coef_id ~ '^[0-9]+$' AND m.in_city_coef_id::int > 0 THEN m.in_city_coef_id::smallint END,
  CASE WHEN m.station_coef ~ '^[0-9]+$' THEN m.station_coef::smallint END,
  CASE WHEN m.road_quality ~ '^[0-9]+$' THEN m.road_quality::smallint END,
  COALESCE(CASE WHEN m.excluding_coef ~ '^[0-9]+$' THEN m.excluding_coef::int <> 0 END, false),
  CASE WHEN m.additional_fuel_100 ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.additional_fuel_100::double precision END,
  CASE WHEN m.additional_fuel ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.additional_fuel::double precision END,
  CASE WHEN m.cond_fuel ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.cond_fuel::double precision END,
  CASE WHEN m.heating_fuel ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.heating_fuel::double precision END,
  CASE WHEN m.distance_a ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.distance_a::double precision END,
  CASE WHEN m.distance_b ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.distance_b::double precision END,
  CASE WHEN m.begin_path_a ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.begin_path_a::double precision END,
  CASE WHEN m.begin_path_b ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.begin_path_b::double precision END,
  CASE WHEN m.planned_lap ~ '^[0-9]+$' THEN m.planned_lap::smallint END,
  CASE WHEN m.coe_use_capacity ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.coe_use_capacity::double precision END,
  CASE WHEN m.average_length_pass_seat ~ '^-?[0-9]+(\.[0-9]+)?$' THEN m.average_length_pass_seat::double precision END,
  left(NULLIF(btrim(m.name_a), ''), 100),
  left(NULLIF(btrim(m.name_b), ''), 100)
FROM tmp_route_missing m
ON CONFLICT (organization_rma, number) DO NOTHING;

SELECT count(*) AS routes_total FROM route;

DROP TABLE tmp_route_missing;
DROP TABLE tmp_route_src;
