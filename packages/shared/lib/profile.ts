// Профиль приложения. Один код консоли собирается/запускается в двух профилях:
//   waybill   — кабинет перевозчика (диспетчер/водитель/механик/врач/бухгалтер/топливо/админ компании)
//   oversight — платформа надзора (сис-админ платформы, аналитик Минтранса, инспектор)
// Профиль задаётся build-arg NEXT_PUBLIC_APP_PROFILE. По умолчанию 'all' = единый монолит
// (все роли), т.е. поведение до разделения — обратная совместимость и зелёная регрессия.

export type AppProfile = 'waybill' | 'oversight' | 'all';

/** Роли, «принадлежащие» профилю (кому разрешён вход в это приложение). */
export const PROFILE_ROLES: Record<Exclude<AppProfile, 'all'>, string[]> = {
  waybill: ['COMPANY_ADMIN', 'BRANCH_ADMIN', 'DISPATCHER', 'DRIVER', 'MECHANIC', 'DOCTOR', 'ACCOUNTANT', 'FUEL_STATION'],
  oversight: ['SYSTEM_ADMIN', 'MINTRANS_ANALYST', 'INSPECTOR'],
};

/** Метаданные профиля: заголовок и указатель на «соседний» кабинет для экрана «не тот кабинет». */
export const PROFILE_META: Record<AppProfile, { title: string; other?: Exclude<AppProfile, 'all'> }> = {
  waybill: { title: 'Путевой лист', other: 'oversight' },
  oversight: { title: 'Платформа надзора', other: 'waybill' },
  all: { title: 'е-Роҳхат' },
};

/** URL «соседнего» приложения (для ссылки на экране «не тот кабинет»), если задан в env. */
export function profileUrl(p: Exclude<AppProfile, 'all'>): string | null {
  const v = p === 'waybill'
    ? process.env.NEXT_PUBLIC_WAYBILL_URL
    : process.env.NEXT_PUBLIC_OVERSIGHT_URL;
  return v || null;
}

/** Текущий профиль приложения из env. */
export function appProfile(): AppProfile {
  const p = process.env.NEXT_PUBLIC_APP_PROFILE;
  return p === 'waybill' || p === 'oversight' ? p : 'all';
}

/**
 * Разрешён ли вход пользователю с этими ролями в текущий профиль.
 * В профиле 'all' (монолит) — всегда да.
 */
export function rolesInProfile(roles: string[], profile: AppProfile = appProfile()): boolean {
  if (profile === 'all') return true;
  const allowed = PROFILE_ROLES[profile];
  return roles.some(r => allowed.includes(r));
}
