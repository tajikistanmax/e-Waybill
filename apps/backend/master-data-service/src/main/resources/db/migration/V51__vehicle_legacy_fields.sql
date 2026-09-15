-- Паритет карточки ТС с боевой формой MinTransRT (parking/create): переносим недостающие
-- реквизиты автомобиля и прицепов. Копии документов (техпаспорт/техосмотр/контр.лист)
-- ведутся через существующую подсистему «документы ТС» (MinIO), отдельными полями здесь не хранятся.
ALTER TABLE vehicle
    ADD COLUMN tech_inspection_number     VARCHAR(50),   -- № муоинаи техникӣ (техосмотра)
    ADD COLUMN tech_passport_number       VARCHAR(50),   -- № шиносномаи техникӣ (техпаспорта)
    ADD COLUMN certificate_number         VARCHAR(50),   -- № сертификата
    ADD COLUMN air_conditioner            INTEGER,       -- кондиционер, %
    ADD COLUMN intl_control_card_number   VARCHAR(50),   -- № варақаи назоратӣ (междунар. контр.лист)
    ADD COLUMN intl_control_card_valid_to DATE,          -- междунар. контр.лист действ. до
    ADD COLUMN trailer1_number            VARCHAR(20),   -- прицеп 1: госномер (ядаки якум)
    ADD COLUMN trailer1_brand             VARCHAR(200),  -- прицеп 1: марка
    ADD COLUMN trailer1_carrying          NUMERIC(10, 2),-- прицеп 1: грузоподъёмность
    ADD COLUMN trailer1_weight            NUMERIC(10, 2),-- прицеп 1: вес, т
    ADD COLUMN trailer2_number            VARCHAR(20),   -- прицеп 2: госномер (ядаки дуюм)
    ADD COLUMN trailer2_brand             VARCHAR(200),  -- прицеп 2: марка
    ADD COLUMN trailer2_carrying          NUMERIC(10, 2),-- прицеп 2: грузоподъёмность
    ADD COLUMN trailer2_weight            NUMERIC(10, 2);-- прицеп 2: вес, т

ALTER TABLE vehicle
    ADD CONSTRAINT vehicle_air_conditioner_range
        CHECK (air_conditioner IS NULL OR (air_conditioner >= 0 AND air_conditioner <= 100));

COMMENT ON COLUMN vehicle.tech_inspection_number IS '№ техосмотра (муоинаи техникӣ) — из боевой parking/create';
COMMENT ON COLUMN vehicle.tech_passport_number IS '№ техпаспорта (шиносномаи техникӣ)';
COMMENT ON COLUMN vehicle.certificate_number IS '№ сертификата ТС';
COMMENT ON COLUMN vehicle.air_conditioner IS 'Кондиционер, % (боевое поле air_conditioner)';
COMMENT ON COLUMN vehicle.intl_control_card_number IS '№ контрольного листа для международной деятельности';
COMMENT ON COLUMN vehicle.intl_control_card_valid_to IS 'Срок междунар. контрольного листа';
