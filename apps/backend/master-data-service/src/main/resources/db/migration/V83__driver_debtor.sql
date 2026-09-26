-- «Қарздор» (должник) — отметка перевозчика у своего водителя (legacy drivers.debt, право debt у роли company;
-- сверка 25.09, F6). Водитель-должник не выбирается при выписке путевого листа (legacy FetchTrait::fetchTimesheet:
-- debt = 0). Отдельно от suspended: отстранение ставит и снимает только Минтранс, отметку «Қарздор» — перевозчик.
ALTER TABLE driver ADD COLUMN debtor BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE driver ADD COLUMN debtor_note VARCHAR(300);
ALTER TABLE driver ADD COLUMN debtor_marked_at TIMESTAMPTZ;
ALTER TABLE driver ADD COLUMN debtor_marked_by VARCHAR(100);
