'use client';

import { createContext, useContext, useEffect, useState } from 'react';

export type Lang = 'ru' | 'tj';

/** Словарь интерфейса: русский · тоҷикӣ. Ключи — по смыслу. */
const DICT: Record<string, { ru: string; tj: string }> = {
  // Бренд / общее
  'app.title': { ru: 'Электронный путевой лист', tj: 'Роҳхати электронӣ' },
  'app.subtitle': { ru: 'Цифровое управление транспортом и путевыми листами', tj: 'Идоракунии рақамии нақлиёт ва роҳхатҳо' },
  'brand.sub': { ru: 'Электронный путевой лист', tj: 'Роҳхати электронӣ' },

  // Навигация
  'nav.dashboard': { ru: 'Главная панель', tj: 'Лавҳаи асосӣ' },
  'nav.waybills': { ru: 'Путевые листы', tj: 'Роҳхатҳо' },
  'nav.waybill.new': { ru: 'Создать путевой лист', tj: 'Эҷоди роҳхат' },
  'nav.waybill.registry': { ru: 'Реестр путевых листов', tj: 'Феҳристи роҳхатҳо' },
  'nav.group.workplaces': { ru: 'Рабочие места', tj: 'Ҷойҳои корӣ' },
  'nav.group.management': { ru: 'Управление', tj: 'Идоракунӣ' },
  'nav.med': { ru: 'АРМ врача', tj: 'Ҷои кории духтур' },
  'nav.tech': { ru: 'АРМ механика', tj: 'Ҷои кории механик' },
  'nav.company': { ru: 'Компания', tj: 'Корхона' },
  'nav.reports': { ru: 'Отчёты и аналитика', tj: 'Ҳисобот ва таҳлил' },
  'nav.dictionaries': { ru: 'Справочники', tj: 'Маълумотномаҳо' },
  'nav.logout': { ru: 'Выход из системы', tj: 'Баромадан аз система' },

  // Роли
  'role.SYSTEM_ADMIN': { ru: 'Администратор', tj: 'Маъмур' },
  'role.DISPATCHER': { ru: 'Диспетчер', tj: 'Танзимгар' },
  'role.DOCTOR': { ru: 'Медработник', tj: 'Корманди тиббӣ' },
  'role.MECHANIC': { ru: 'Механик', tj: 'Механик' },
  'role.ACCOUNTANT': { ru: 'Бухгалтер', tj: 'Муҳосиб' },
  'role.COMPANY_ADMIN': { ru: 'Администратор', tj: 'Маъмур' },
  'role.INSPECTOR': { ru: 'Инспектор', tj: 'Нозир' },

  // Вход
  'login.h': { ru: 'Электронный путевой лист', tj: 'Роҳхати электронӣ' },
  'login.user': { ru: 'Телефон или ИНН', tj: 'Телефон ё РМА' },
  'login.user.ph': { ru: 'Введите телефон или ИНН', tj: 'Телефон ё РМА-ро ворид кунед' },
  'login.pass': { ru: 'Пароль', tj: 'Рамз' },
  'login.pass.ph': { ru: 'Введите пароль', tj: 'Рамзро ворид кунед' },
  'login.remember': { ru: 'Запомнить меня', tj: 'Маро дар хотир нигоҳ доред' },
  'login.forgot': { ru: 'Забыли пароль?', tj: 'Рамзро фаромӯш кардед?' },
  'login.submit': { ru: 'Войти', tj: 'Ворид шудан' },
  'login.busy': { ru: 'Вход…', tj: 'Воридшавӣ…' },
  'login.or': { ru: 'или', tj: 'ё' },
  'login.sso': { ru: 'Войти через DTS SSO', tj: 'Ворид тавассути DTS SSO' },
  'login.secure': { ru: 'Ваши данные защищены в соответствии с требованиями безопасности Республики Таджикистан', tj: 'Маълумоти шумо мутобиқи талаботи амнияти Ҷумҳурии Тоҷикистон ҳифз мешавад' },
  'login.err': { ru: 'Неверный логин или пароль', tj: 'Логин ё рамз нодуруст аст' },
  'login.support': { ru: 'Поддержка', tj: 'Дастгирӣ' },
  'login.caption': { ru: 'Цифровая платформа для эффективного и безопасного управления транспортом', tj: 'Платформаи рақамӣ барои идоракунии самаранок ва бехатари нақлиёт' },

  // Дашборд
  'dash.h': { ru: 'Главная панель', tj: 'Лавҳаи асосӣ' },
  'dash.lead': { ru: 'Обзор состояния системы и ключевых показателей', tj: 'Шарҳи ҳолати система ва нишондиҳандаҳои асосӣ' },
  'dash.new': { ru: 'Новый путевой лист', tj: 'Роҳхати нав' },
  'kpi.total': { ru: 'Путевых листов', tj: 'Роҳхатҳо' },
  'kpi.today': { ru: 'Оформлено сегодня', tj: 'Имрӯз тартиб дода шуд' },
  'kpi.online': { ru: 'На линии', tj: 'Дар хат' },
  'kpi.done': { ru: 'Завершено', tj: 'Анҷом ёфт' },
  'kpi.cancel': { ru: 'Аннулировано', tj: 'Бекор шуд' },
  'dash.dynamics': { ru: 'Динамика путевых листов', tj: 'Динамикаи роҳхатҳо' },
  'dash.bytype': { ru: 'Путевые листы по типам', tj: 'Роҳхатҳо аз рӯи навъҳо' },
  'dash.total': { ru: 'Всего', tj: 'Ҳамагӣ' },
  'dash.sysstate': { ru: 'Состояние системы', tj: 'Ҳолати система' },
  'dash.sysok': { ru: 'Все системы работают стабильно', tj: 'Ҳамаи системаҳо устувор кор мекунанд' },
  'dash.recent': { ru: 'Последние путевые листы', tj: 'Роҳхатҳои охирин' },
  'dash.all': { ru: 'Все документы', tj: 'Ҳамаи ҳуҷҷатҳо' },
  'dash.quick': { ru: 'Быстрая статистика', tj: 'Омори тез' },
  'dash.activity': { ru: 'Последняя активность', tj: 'Фаъолияти охирин' },
  'sys.online': { ru: 'Онлайн', tj: 'Онлайн' },

  // Таблицы
  'col.number': { ru: 'Номер', tj: 'Рақам' },
  'col.type': { ru: 'Тип', tj: 'Навъ' },
  'col.vehicle': { ru: 'ТС', tj: 'НВ' },
  'col.driver': { ru: 'Водитель', tj: 'Ронанда' },
  'col.route': { ru: 'Маршрут', tj: 'Хатсайр' },
  'col.status': { ru: 'Статус', tj: 'Вазъият' },
  'col.created': { ru: 'Создан', tj: 'Сохта шуд' },

  'common.online': { ru: 'Онлайн', tj: 'Онлайн' },
};

const LangContext = createContext<{ lang: Lang; setLang: (l: Lang) => void; t: (k: string) => string }>({
  lang: 'ru', setLang: () => {}, t: (k) => k,
});

export const useT = () => useContext(LangContext);

export function LangProvider({ children }: { children: React.ReactNode }) {
  const [lang, setLangState] = useState<Lang>('ru');
  useEffect(() => {
    try { const s = localStorage.getItem('dts_lang'); if (s === 'ru' || s === 'tj') setLangState(s); } catch { /* ignore */ }
  }, []);
  const setLang = (l: Lang) => { setLangState(l); try { localStorage.setItem('dts_lang', l); } catch { /* ignore */ } };
  const t = (k: string) => DICT[k]?.[lang] ?? k;
  return <LangContext.Provider value={{ lang, setLang, t }}>{children}</LangContext.Provider>;
}
