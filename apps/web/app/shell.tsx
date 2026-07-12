'use client';

import { usePathname, useRouter } from 'next/navigation';
import { useEffect } from 'react';
import { useAuth } from '@/lib/auth';
import { useRoleAccess } from '@/lib/roleaccess';
import { type NavKey } from '@/lib/roles';
import { Sidebar } from './sidebar';
import { Topbar } from './topbar';

const isPublic = (path: string) => path === '/login' || path.startsWith('/verify/');

/** Первый сегмент пути → раздел меню (для проверки доступа по роли). */
const ROUTE_NAV: Record<string, NavKey> = {
  dashboard: 'dashboard', waybills: 'waybills', dispatcher: 'dispatcher',
  med: 'med', tech: 'tech', driver: 'driver', inspector: 'inspector',
  company: 'company', registry: 'registry', violations: 'violations',
  reports: 'reports', dictionaries: 'dictionaries', settings: 'settings',
};

/** Каркас приложения: публичные страницы (вход, проверка QR) — на весь экран;
 *  остальные — за авторизацией и за проверкой доступа роли к разделу. */
export function Shell({ children }: { children: React.ReactNode }) {
  const { ready, authenticated, roles } = useAuth();
  const { navFor, homeFor } = useRoleAccess();
  const pathname = usePathname();
  const router = useRouter();

  // Раздел, к которому относится путь, разрешён ролям пользователя? Неизвестные пути — разрешены.
  const seg = pathname.split('/')[1] ?? '';
  const routeKey = ROUTE_NAV[seg];
  const allowed = routeKey ? navFor(roles).has(routeKey) : true;

  useEffect(() => {
    if (!ready || isPublic(pathname)) return;
    if (!authenticated) { router.replace('/login'); return; }
    // Доступ к разделу не разрешён роли — уводим в её кабинет.
    if (!allowed) router.replace(homeFor(roles));
  }, [ready, authenticated, roles, pathname, allowed, router, homeFor]);

  if (isPublic(pathname)) return <>{children}</>;
  if (!ready) return <div className="boot">Загрузка системы…</div>;
  if (!authenticated) return <div className="boot">Переход к странице входа…</div>;

  return (
    <div className="app">
      <Sidebar />
      <div className="content">
        <Topbar />
        <main className="page">{allowed ? children : <div className="boot">Переход…</div>}</main>
      </div>
    </div>
  );
}
