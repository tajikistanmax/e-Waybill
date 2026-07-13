// Персональные кабинеты по ролям: стартовая страница и доступные пункты меню.

export type NavKey = 'dashboard' | 'waybills' | 'dispatcher' | 'med' | 'tech' | 'driver' | 'inspector' | 'company' | 'fleet' | 'monitoring' | 'registry' | 'violations' | 'reports' | 'dictionaries' | 'settings';

/** Куда попадает пользователь после входа — в свой кабинет. */
export function roleHome(roles: string[]): string {
  if (roles.includes('SYSTEM_ADMIN') || roles.includes('COMPANY_ADMIN')) return '/dashboard';
  if (roles.includes('MINTRANS_ANALYST')) return '/dashboard';
  if (roles.includes('DOCTOR')) return '/med';
  if (roles.includes('MECHANIC')) return '/tech';
  if (roles.includes('DRIVER')) return '/driver';
  if (roles.includes('DISPATCHER')) return '/dispatcher';
  if (roles.includes('INSPECTOR')) return '/inspector';
  if (roles.includes('ACCOUNTANT')) return '/reports';
  return '/dashboard';
}

/** Пункты бокового меню, доступные роли. */
export function visibleNav(roles: string[]): Set<NavKey> {
  const s = new Set<NavKey>();
  const add = (...keys: NavKey[]) => keys.forEach(k => s.add(k));

  // Админ надзирает и настраивает — осмотры НЕ проводит (Т2/Т3 подписывают врач/механик
  // своими РМА в /med и /tech). Поэтому у админов нет АРМ врача/механика в меню.
  if (roles.includes('SYSTEM_ADMIN')) add('dashboard', 'waybills', 'company', 'monitoring', 'registry', 'violations', 'reports', 'dictionaries', 'settings');
  // Админ компании-перевозчика ведёт ТОЛЬКО свою организацию: профиль (company), парк и
  // персонал (fleet), свои ПЛ/отчёты/нарушения/GPS. НЕ платформенные разделы (Настройки §29,
  // нац. Справочники, глобальные Реестры — это администратор платформы/Минтранс).
  if (roles.includes('COMPANY_ADMIN')) add('dashboard', 'waybills', 'company', 'fleet', 'monitoring', 'violations', 'reports');
  if (roles.includes('DISPATCHER')) add('dispatcher', 'dashboard', 'waybills', 'fleet', 'monitoring');
  if (roles.includes('DOCTOR')) add('med', 'fleet');
  if (roles.includes('MECHANIC')) add('tech', 'fleet');
  if (roles.includes('DRIVER')) add('driver');
  if (roles.includes('ACCOUNTANT')) add('dashboard', 'reports', 'waybills');
  if (roles.includes('INSPECTOR')) add('inspector', 'dashboard', 'violations', 'monitoring');
  // Аналитик Минтранса — надзор/аналитика по всем организациям (только чтение).
  if (roles.includes('MINTRANS_ANALYST')) add('dashboard', 'reports', 'registry', 'violations', 'monitoring');

  if (s.size === 0) s.add('dashboard');
  return s;
}
