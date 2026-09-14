-- Реквизиты справочников, требуемые ТЗ, но отсутствовавшие в схеме.
--
-- driver (ТЗ §6.3 «ФИО, ИНН, дата рождения, … медограничения …»):
--   birth_date       — дата рождения. Нужна не «для красоты»: без неё нельзя проверить
--                      несовершеннолетнего водителя (QA-статус-Test1 §81 — проверка помечена
--                      как отсутствующая) и нельзя показать возраст в профиле врача.
--   experience_years — общий стаж вождения (лет). Есть в схеме обмена (XSD «Стаж», docs/часть 6.md),
--                      в таблице полей ПЛ (docs/часть 3.md, поле 17) и в бизнес-правиле
--                      «водитель должен иметь стаж не менее 3 лет» (docs/часть2.md §521,
--                      перевозка детей). Без поля правило проверить нечем.
--   med_restrictions — медограничения ИЗ ВОДИТЕЛЬСКОГО УДОСТОВЕРЕНИЯ (например «очки обязательны»),
--                      то, что врач обязан видеть при предрейсовом осмотре. Это отметка на ВУ,
--                      а НЕ диагноз: группа крови, аллергии и хронические заболевания —
--                      специальная категория ПДн, здесь они сознательно не хранятся
--                      (для таких данных в системе действует режим шифрования + аудит доступа,
--                      см. MedicalDataCrypto и WaybillService.decryptMedicalIndicators).
--
-- vehicle (ТЗ §6.4 «… двигатель, шасси, мощность, экологический класс, топливо …»):
--   fuel_type    — вид топлива ТС (1=Бензин, 2=Дизель, 3=Газ сжиженный, 4=Газ природный, 5=Электро;
--                  тот же классификатор, что fuel_type в V27 и FuelRecord.fuel_type). Нужен логике:
--                  тариф выбирается по паре (transport_type, fuel_type), а FuelCalculationService
--                  до сих пор брал вид топлива из первой заправки — то есть из введённого вручную
--                  документа, а при отсутствии заправок не мог определить его вовсе.
--   engine_power — мощность двигателя, л.с. Справочный реквизит карточки ТС по ТЗ §6.4.

ALTER TABLE driver
    ADD COLUMN birth_date       DATE,
    ADD COLUMN experience_years SMALLINT,
    ADD COLUMN med_restrictions VARCHAR(500);

ALTER TABLE driver
    ADD CONSTRAINT driver_experience_years_range
        CHECK (experience_years IS NULL OR (experience_years >= 0 AND experience_years <= 80));

ALTER TABLE vehicle
    ADD COLUMN fuel_type    SMALLINT,
    ADD COLUMN engine_power INTEGER;

ALTER TABLE vehicle
    ADD CONSTRAINT vehicle_fuel_type_range
        CHECK (fuel_type IS NULL OR (fuel_type >= 1 AND fuel_type <= 5));

ALTER TABLE vehicle
    ADD CONSTRAINT vehicle_engine_power_range
        CHECK (engine_power IS NULL OR (engine_power >= 0 AND engine_power <= 3000));

COMMENT ON COLUMN driver.birth_date IS 'Дата рождения (ТЗ §6.3); основа проверки несовершеннолетия и возраста в профиле';
COMMENT ON COLUMN driver.experience_years IS 'Общий стаж вождения, лет (XSD «Стаж»; правило «не менее 3 лет» для перевозки детей)';
COMMENT ON COLUMN driver.med_restrictions IS 'Медограничения из ВУ (например «очки обязательны»). НЕ диагноз: спецкатегория ПДн здесь не хранится';
COMMENT ON COLUMN vehicle.fuel_type IS 'Вид топлива ТС: 1=Бензин 2=Дизель 3=Газ сжиженный 4=Газ природный 5=Электро';
COMMENT ON COLUMN vehicle.engine_power IS 'Мощность двигателя, л.с. (ТЗ §6.4)';
