-- Номера документов для печатных бланков ПЛ (Замима 7 к Қоидаи нақлиёти автомобилӣ).
-- В бланках эти графы — НОМЕРА документов, а не даты: у ТС/водителя/организации уже есть
-- поля «действителен до», но не было самих номеров, из-за чего печать подставляла суррогаты.
ALTER TABLE vehicle
    ADD COLUMN control_card_number     VARCHAR(50),  -- № варақаи назоратӣ (контрольного листа ТС)
    ADD COLUMN intl_certificate_number VARCHAR(50);  -- № сертификата ТС для межд. перевозок (форма 5Б-БМ)

ALTER TABLE driver
    ADD COLUMN safety_course_number VARCHAR(50);     -- № талона курса 20 соатаи ҚҲР ва МО (БДД)

ALTER TABLE organization
    ADD COLUMN carrier_license_number VARCHAR(50);   -- № иҷозатномаи ҳамлу нақл (лицензии перевозчика)
