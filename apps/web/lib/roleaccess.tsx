'use client';

import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { md, type RoleAccess } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { visibleNav as defaultNav, roleHome as defaultHome, type NavKey } from '@/lib/roles';

/**
 * Доступ ролей к разделам меню — из бэкенда (редактирует админ на /settings/roles), с фолбэком
 * на зашитый lib/roles.ts, если конфиг ещё не загружен или роль в нём отсутствует. Это только
 * навигация (какие пункты меню видны), а НЕ безопасность — реальные права проверяет бэкенд.
 */

/** Приоритет выбора стартовой страницы при нескольких ролях (зеркалит порядок roleHome). */
const HOME_PRIORITY = ['SYSTEM_ADMIN', 'COMPANY_ADMIN', 'MINTRANS_ANALYST', 'DOCTOR', 'MECHANIC', 'DRIVER', 'DISPATCHER', 'INSPECTOR', 'ACCOUNTANT'];

type RoleAccessCtx = {
  config: Record<string, RoleAccess> | null;
  loaded: boolean;
  navFor: (roles: string[]) => Set<NavKey>;
  homeFor: (roles: string[]) => string;
  reload: () => void;
};

const Ctx = createContext<RoleAccessCtx>({
  config: null, loaded: false, navFor: defaultNav, homeFor: defaultHome, reload: () => {},
});

export const useRoleAccess = () => useContext(Ctx);

export function RoleAccessProvider({ children }: { children: React.ReactNode }) {
  const { ready, authenticated } = useAuth();
  const [config, setConfig] = useState<Record<string, RoleAccess> | null>(null);

  const reload = useCallback(() => {
    md.roleAccess()
      .then(list => setConfig(Object.fromEntries(list.map(r => [r.role, r]))))
      .catch(() => { /* нет связи — остаётся фолбэк на зашитый lib/roles */ });
  }, []);

  useEffect(() => { if (ready && authenticated) reload(); }, [ready, authenticated, reload]);

  const navFor = useCallback((roles: string[]): Set<NavKey> => {
    if (!config) return defaultNav(roles);
    const s = new Set<NavKey>();
    let matched = false;
    for (const role of roles) {
      const ra = config[role];
      if (ra) { matched = true; ra.navKeys.forEach(k => s.add(k as NavKey)); }
    }
    if (!matched) return defaultNav(roles); // роли пользователя нет в конфиге → фолбэк
    if (s.size === 0) s.add('dashboard');
    return s;
  }, [config]);

  const homeFor = useCallback((roles: string[]): string => {
    if (!config) return defaultHome(roles);
    for (const role of HOME_PRIORITY) {
      if (roles.includes(role) && config[role]) return '/' + config[role].homeKey;
    }
    return defaultHome(roles);
  }, [config]);

  return (
    <Ctx.Provider value={{ config, loaded: config != null, navFor, homeFor, reload }}>
      {children}
    </Ctx.Provider>
  );
}
