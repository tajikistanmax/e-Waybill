// Персональные кабинеты по ролям: стартовая страница и доступные пункты меню.

export type NavKey = 'dashboard' | 'waybills' | 'dispatcher' | 'med' | 'tech' | 'fuel' | 'driver' | 'inspector' | 'company' | 'fleet' | 'monitoring' | 'registry' | 'violations' | 'reports' | 'dictionaries' | 'settings' | 'access' | 'consignments';

/** Внешние роли накладных (MIGRATION.md 1.1/3.11): грузоотправитель, экспедитор, таможенник. */
export const EXTERNAL_CONSIGNMENT_ROLES = ['CLIENT_SENDER', 'CLIENT_FORWARDER', 'CUSTOMS_OFFICER'];

/** Куда попадает пользователь после входа — в свой кабинет. */
export function roleHome(roles: string[]): string {
  if (roles.includes('SYSTEM_ADMIN') || roles.includes('COMPANY_ADMIN') || roles.includes('BRANCH_ADMIN')) return '/dashboard';
  if (roles.includes('MINTRANS_ANALYST')) return '/dashboard';
  if (roles.includes('DOCTOR')) return '/med';
  if (roles.includes('MECHANIC')) return '/tech';
  if (roles.includes('DRIVER')) return '/driver';
  if (roles.includes('DISPATCHER')) return '/dispatcher';
  if (roles.includes('INSPECTOR')) return '/inspector';
  if (roles.includes('FUEL_STATION')) return '/fuel';
  if (roles.includes('ACCOUNTANT')) return '/reports/summary';
  if (EXTERNAL_CONSIGNMENT_ROLES.some(r => roles.includes(r))) return '/consignments';
  return '/dashboard';
}

/** Пункты бокового меню, доступные роли. */
export function visibleNav(roles: string[]): Set<NavKey> {
  const s = new Set<NavKey>();
  const add = (...keys: NavKey[]) => keys.forEach(k => s.add(k));

  // Админ надзирает и настраивает — осмотры НЕ проводит (Т2/Т3 подписывают врач/механик
  // своими РМА в /med и /tech). Поэтому у админов нет АРМ врача/механика в меню.
  // 'access' — обязательно: учётные записи заводит в том числе администратор платформы
  // (первый администратор новой компании-перевозчика выдаётся только им). Без этого ключа
  // пункта «Доступы» в меню не было вовсе, и завести пользователя было нельзя (находка 23.09.2026).
  if (roles.includes('SYSTEM_ADMIN')) add('dashboard', 'waybills', 'company', 'access', 'monitoring', 'registry', 'violations', 'reports', 'dictionaries', 'settings');
  // Админ компании-перевозчика ведёт свою организацию и все её филиалы: парк и персонал (fleet),
  // доступы сотрудников (access), свои ПЛ/отчёты/нарушения/GPS, реестр и справочники расчёта —
  // на просмотр. НЕ платформенные Настройки §29 и нац. справочники.
  // Раздел «Компания» (профиль «Моя компания») убран: реквизиты/парк/персонал ведутся в /fleet,
  // дублирующая сводка не нужна. /company остаётся только у SYSTEM_ADMIN (реестр всех организаций).
  if (roles.includes('COMPANY_ADMIN')) add('dashboard', 'waybills', 'fleet', 'access', 'monitoring', 'violations', 'reports', 'registry', 'dictionaries');
  // Админ филиала — то же, но только в рамках своего филиала (область по токену).
  if (roles.includes('BRANCH_ADMIN')) add('dashboard', 'waybills', 'fleet', 'access', 'monitoring', 'violations', 'reports');
  if (roles.includes('DISPATCHER')) add('dispatcher', 'dashboard', 'waybills', 'fleet', 'monitoring');
  // Врач/механик (аутсорс-пункт) работают в своём АРМ — осмотр очереди, а НЕ управление парком перевозчика.
  if (roles.includes('DOCTOR')) add('med');
  if (roles.includes('MECHANIC')) add('tech');
  if (roles.includes('DRIVER')) add('driver');
  // Пункт выдачи топлива — учёт фактической выдачи по листам своей организации.
  if (roles.includes('FUEL_STATION')) add('fuel');
  // Бухгалтер — финансовая функция (подтверждение оплаты + отчёты), оперативный дашборд ему не нужен.
  if (roles.includes('ACCOUNTANT')) add('reports', 'waybills');
  // Инспектор — контроль (проверка ПЛ + нарушения + GPS) и надзорная отчётность:
  // сводка за период и журналы предрейсового контроля (проводились ли осмотры реально).
  // Экономика перевозчика (топливо, зарплата) и сводные Минтранса ему закрыты.
  // 'waybills' — обязательно: карточка ПЛ (титулы, история, блокировка/акт осмотра) уже
  // разрешена инспектору на бэкенде (@PreAuthorize), а без этого ключа гвард в shell.tsx
  // мгновенно возвращал его на /inspector при любой попытке открыть карточку — «Открыть
  // карточку путевого листа», клик по строке проблемных листов и блок НЕРУ вели в никуда
  // (находка приёмки 2026-09-04).
  if (roles.includes('INSPECTOR')) add('inspector', 'waybills', 'violations', 'monitoring', 'reports');
  // Аналитик Минтранса — надзор/аналитика по всем организациям (только чтение).
  if (roles.includes('MINTRANS_ANALYST')) add('dashboard', 'reports', 'registry', 'violations', 'monitoring');
  // Внешние пользователи накладных — только свой кабинет «Накладные» (legacy client_sender/forwarder/customs_officer).
  if (EXTERNAL_CONSIGNMENT_ROLES.some(r => roles.includes(r))) add('consignments');

  if (s.size === 0) s.add('dashboard');
  return s;
}

// --------------------------------------------------------------------------
// Права на действия. Зеркалят @PreAuthorize на бэкенде — держим в одном месте,
// чтобы UI не предлагал того, что сервер отклонит с 403.
// --------------------------------------------------------------------------

/** Выписать путевой лист: POST /api/v1/waybills — только диспетчер (и админ платформы для поддержки). */
export function canCreateWaybill(roles: string[]): boolean {
  return roles.includes('DISPATCHER') || roles.includes('SYSTEM_ADMIN');
}

/** Реестр борхатов 2-Б (GET /api/v1/consignment-notes) — те же роли, что в @PreAuthorize контроллера. */
export function canSeeConsignmentNotes(roles: string[]): boolean {
  return ['SYSTEM_ADMIN', 'MINTRANS_ANALYST', 'DISPATCHER', 'COMPANY_ADMIN', 'BRANCH_ADMIN', 'ACCOUNTANT',
    'CLIENT_SENDER', 'CLIENT_FORWARDER'].some(r => roles.includes(r));
}

/** Сводные отчёты Минтранса (/reports/regional*, /waybill-norm*) — надзор и админ платформы. */
export function canSeeMintransReports(roles: string[]): boolean {
  return roles.includes('SYSTEM_ADMIN') || roles.includes('MINTRANS_ANALYST');
}

/** Подтвердить оплату ПЛ: POST /{id}/confirm-payment. */
export function canConfirmPayment(roles: string[]): boolean {
  return ['ACCOUNTANT', 'COMPANY_ADMIN', 'SYSTEM_ADMIN'].some(r => roles.includes(r));
}

/**
 * Экономические разрезы перевозчика (расход топлива, зарплата, разрезы по водителям/ТС,
 * справки). Инспектору дорожного контроля они закрыты — это внутренняя экономика компании,
 * а не предмет надзора: ему доступны сводка и журналы предрейсового контроля.
 */
export function canSeeCarrierEconomics(roles: string[]): boolean {
  return !roles.includes('INSPECTOR')
    || ['SYSTEM_ADMIN', 'MINTRANS_ANALYST', 'COMPANY_ADMIN', 'BRANCH_ADMIN', 'ACCOUNTANT', 'DISPATCHER'].some(r => roles.includes(r));
}

/**
 * Нац. справочники (нормы расхода / коэффициенты / тарифы) — единые для платформы,
 * правит только SYSTEM_ADMIN (POST /api/v1/dictionaries/{fuel-norms,coefficients,tariffs}
 * = @PreAuthorize hasRole('SYSTEM_ADMIN')). Маршруты/клиенты сюда не входят — их правит
 * COMPANY_ADMIN своей организации (см. DictionaryController.upsertRoute/upsertClient).
 */
export function canEditNationalDictionaries(roles: string[]): boolean {
  return roles.includes('SYSTEM_ADMIN');
}
