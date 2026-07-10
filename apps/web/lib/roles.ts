// Персональные кабинеты по ролям: стартовая страница и доступные пункты меню.

export type NavKey = 'dashboard' | 'waybills' | 'med' | 'tech' | 'driver' | 'company' | 'registry' | 'violations' | 'reports' | 'dictionaries';

/** Куда попадает пользователь после входа — в свой кабинет. */
export function roleHome(roles: string[]): string {
  if (roles.includes('SYSTEM_ADMIN') || roles.includes('COMPANY_ADMIN')) return '/dashboard';
  if (roles.includes('DOCTOR')) return '/med';
  if (roles.includes('MECHANIC')) return '/tech';
  if (roles.includes('DRIVER')) return '/driver';
  if (roles.includes('DISPATCHER')) return '/waybills';
  if (roles.includes('ACCOUNTANT')) return '/reports';
  return '/dashboard';
}

/** Пункты бокового меню, доступные роли. */
export function visibleNav(roles: string[]): Set<NavKey> {
  const s = new Set<NavKey>();
  const add = (...keys: NavKey[]) => keys.forEach(k => s.add(k));

  if (roles.includes('SYSTEM_ADMIN')) add('dashboard', 'waybills', 'med', 'tech', 'company', 'registry', 'violations', 'reports', 'dictionaries');
  if (roles.includes('COMPANY_ADMIN')) add('dashboard', 'waybills', 'med', 'tech', 'company', 'registry', 'violations', 'reports', 'dictionaries');
  if (roles.includes('DISPATCHER')) add('dashboard', 'waybills');
  if (roles.includes('DOCTOR')) add('med');
  if (roles.includes('MECHANIC')) add('tech');
  if (roles.includes('DRIVER')) add('driver');
  if (roles.includes('ACCOUNTANT')) add('dashboard', 'reports', 'waybills');
  if (roles.includes('INSPECTOR')) add('dashboard', 'violations');

  if (s.size === 0) s.add('dashboard');
  return s;
}
