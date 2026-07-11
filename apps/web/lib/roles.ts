// Персональные кабинеты по ролям: стартовая страница и доступные пункты меню.

export type NavKey = 'dashboard' | 'waybills' | 'dispatcher' | 'med' | 'tech' | 'driver' | 'inspector' | 'company' | 'registry' | 'violations' | 'reports' | 'dictionaries' | 'settings';

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

  if (roles.includes('SYSTEM_ADMIN')) add('dashboard', 'waybills', 'med', 'tech', 'company', 'registry', 'violations', 'reports', 'dictionaries', 'settings');
  if (roles.includes('COMPANY_ADMIN')) add('dashboard', 'waybills', 'med', 'tech', 'company', 'registry', 'violations', 'reports', 'dictionaries', 'settings');
  if (roles.includes('DISPATCHER')) add('dispatcher', 'dashboard', 'waybills');
  if (roles.includes('DOCTOR')) add('med');
  if (roles.includes('MECHANIC')) add('tech');
  if (roles.includes('DRIVER')) add('driver');
  if (roles.includes('ACCOUNTANT')) add('dashboard', 'reports', 'waybills');
  if (roles.includes('INSPECTOR')) add('inspector', 'dashboard', 'violations');
  // Аналитик Минтранса — надзор/аналитика по всем организациям (только чтение).
  if (roles.includes('MINTRANS_ANALYST')) add('dashboard', 'reports', 'registry', 'violations');

  if (s.size === 0) s.add('dashboard');
  return s;
}
