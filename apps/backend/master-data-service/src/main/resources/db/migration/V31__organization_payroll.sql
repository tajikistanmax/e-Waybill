-- Параметры оплаты труда организации (перенос companies.percent_income, cat_1..cat_3
-- из ИС «Роҳхат», §2.6): доля дохода компании и надбавки за класс водителя (1/2/3).
-- Используются формулой заработка водителя:
--   salary = ((earning / 4) * 3) * percent_income + cat_{degree}

ALTER TABLE organization
    ADD COLUMN percent_income DOUBLE PRECISION,  -- доля (не проценты): 0.5 = 50 %
    ADD COLUMN cat_1          SMALLINT,          -- надбавка за 1-й класс
    ADD COLUMN cat_2          SMALLINT,
    ADD COLUMN cat_3          SMALLINT;

-- Демо-значения для стенда.
UPDATE organization SET percent_income = 0.50, cat_1 = 200, cat_2 = 120, cat_3 = 0
 WHERE rma IN ('100001000', '100002000', '100003000', '100004000');
