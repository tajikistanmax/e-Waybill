'use client';

import { usePathname, useRouter } from 'next/navigation';
import { useEffect } from 'react';
import { useAuth } from '@/lib/auth';
import { useRoleAccess } from '@/lib/roleaccess';
import { type NavKey } from '@/lib/roles';
import { appProfile, rolesInProfile, PROFILE_META, profileUrl } from '@/lib/profile';
import { Sidebar } from './sidebar';
import { Topbar } from './topbar';
import { MaintenanceBanner } from './MaintenanceBanner';
import { LicenseBanner } from './LicenseBanner';

const isPublic = (path: string) => path === '/login' || path.startsWith('/verify/');

/** Первый сегмент пути → раздел меню (для проверки доступа по роли). */
const ROUTE_NAV: Record<string, NavKey> = {
  dashboard: 'dashboard', waybills: 'waybills', dispatcher: 'dispatcher',
  med: 'med', tech: 'tech', driver: 'driver', inspector: 'inspector',
  company: 'company', registry: 'registry', violations: 'violations',
  reports: 'reports', dictionaries: 'dictionaries', settings: 'settings', consignments: 'consignments',
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
  // Карточка ПЛ (/waybills/{id}, /{id}/print, /journal) достижима deep-link'ом из кабинетов
  // водителя/инспектора/аналитика, у которых нет пункта «waybills» в меню. Доступ к самой
  // карточке ограничен ролями ВНУТРИ страницы + tenant/IDOR на бэкенде, поэтому гейтим по
  // меню только список /waybills, а его детальные подпути пускаем.
  const isWaybillDetail = seg === 'waybills' && (pathname.split('/')[2] ?? '') !== '';
  const allowed = !routeKey || isWaybillDetail || navFor(roles).has(routeKey);

  useEffect(() => {
    if (!ready || isPublic(pathname)) return;
    if (!authenticated) { router.replace('/login'); return; }
    // Доступ к разделу не разрешён роли — уводим в её кабинет.
    if (!allowed) router.replace(homeFor(roles));
  }, [ready, authenticated, roles, pathname, allowed, router, homeFor]);

  if (isPublic(pathname)) return <><MaintenanceBanner />{children}</>;
  if (!ready) return <div className="boot">Загрузка системы…</div>;
  if (!authenticated) return <div className="boot">Переход к странице входа…</div>;
  // Граница профиля: в профильной сборке (waybill/oversight) пускаем только «свои» роли.
  // В профиле 'all' (монолит) rolesInProfile всегда true — поведение не меняется.
  if (!rolesInProfile(roles)) return <WrongCabinet />;

  return (
    <>
      <MaintenanceBanner />
      <div className="app">
        <Sidebar />
        <div className="content">
          <Topbar />
          <LicenseBanner />
          <main className="page">{allowed ? children : <div className="boot">Переход…</div>}</main>
        </div>
      </div>
    </>
  );
}

/** Пользователь вошёл не в своё приложение (профильная сборка): предлагаем перейти в
 *  правильный кабинет (если его адрес задан) или выйти. */
function WrongCabinet() {
  const { logout } = useAuth();
  const meta = PROFILE_META[appProfile()];
  const other = meta.other ? { meta: PROFILE_META[meta.other], url: profileUrl(meta.other) } : null;
  return (
    <div className="boot" style={{ display: 'flex', flexDirection: 'column', gap: 14, textAlign: 'center', padding: 24 }}>
      <div style={{ fontSize: 56 }}>🚫</div>
      <h1 style={{ margin: 0 }}>Это не ваш кабинет</h1>
      <p style={{ color: 'var(--muted)', maxWidth: 460 }}>
        Вы вошли в «{meta.title}», но у вашей учётной записи нет к нему доступа.
        {other ? ` Похоже, вам нужен кабинет «${other.meta.title}».` : ''}
      </p>
      <div style={{ display: 'flex', gap: 10 }}>
        {other?.url && <a className="btn primary" href={other.url} style={{ textDecoration: 'none' }}>Перейти в «{other.meta.title}»</a>}
        <button className="btn secondary" onClick={logout}>Выйти</button>
      </div>
    </div>
  );
}
