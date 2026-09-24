-- Поля форм задаются в настройках (решение владельца 24.09.2026): администратор скрывает поле
-- или делает его обязательным без изменения кода. Значение — JSON {"поле":"hidden|required|show"};
-- пусто — все поля показаны и необязательны (кроме системных РМА/ИНН и названия).
-- Список полей и проверка — FormFieldPolicy (master-data), редактор — Настройки → Поля форм.
insert into platform_setting (id, category, setting_key, value_type, setting_value, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'forms', 'organization', 'STRING', '',
   'Форма «Организация»: скрытые и обязательные поля (JSON)',
   'Шакли «Ташкилот»: майдонҳои пинҳон ва ҳатмӣ (JSON)', 1);
