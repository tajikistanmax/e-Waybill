'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useAuth } from '@/lib/auth';

/* Минималистичные линейные иконки (наследуют currentColor). */
const I = {
  dash: 'M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z',
  doc: 'M6 2h9l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zm8 1v5h5',
  med: 'M12 3v18M3 12h18',
  wrench: 'M14 6a4 4 0 0 0-5 5l-6 6 3 3 6-6a4 4 0 0 0 5-5l-3 3-2-2 2-2z',
  building: 'M3 21h18M5 21V5a1 1 0 0 1 1-1h8a1 1 0 0 1 1 1v16M9 8h1M9 12h1M12 8h1M12 12h1M19 21V11a1 1 0 0 0-1-1h-3',
  chart: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
  book: 'M4 5a2 2 0 0 1 2-2h12v16H6a2 2 0 0 0-2 2V5zM8 7h7M8 11h7',
  plus: 'M12 5v14M5 12h14',
};

function Icon({ d }: { d: string }) {
  return (
    <svg className="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

const NAV = [
  { href: '/dashboard', label: 'Обзор', icon: I.dash, group: null },
  { href: '/waybills', label: 'Путевые листы', icon: I.doc, group: 'Документы' },
  { href: '/med', label: 'АРМ врача', icon: I.med, group: 'Рабочие места' },
  { href: '/tech', label: 'АРМ механика', icon: I.wrench, group: null },
  { href: '/company', label: 'Компания', icon: I.building, group: 'Управление' },
  { href: '/reports', label: 'Отчёты', icon: I.chart, group: null },
  { href: '/dictionaries', label: 'Справочники', icon: I.book, group: null },
];

const ROLE_RU: Record<string, string> = {
  SYSTEM_ADMIN: 'Администратор', DISPATCHER: 'Диспетчер', DOCTOR: 'Врач',
  MECHANIC: 'Механик', ACCOUNTANT: 'Бухгалтер', COMPANY_ADMIN: 'Админ компании', INSPECTOR: 'Инспектор',
};

export function Sidebar() {
  const pathname = usePathname();
  const { username, roles, logout } = useAuth();

  const isActive = (href: string) =>
    href === '/waybills'
      ? pathname === '/waybills' || (pathname.startsWith('/waybills/') && pathname !== '/waybills/new')
      : pathname === href || pathname.startsWith(href + '/');

  const roleLabel = roles.map(r => ROLE_RU[r]).find(Boolean) ?? '';
  const initials = (username || 'ЭП').slice(0, 2).toUpperCase();

  return (
    <aside className="sidebar no-print">
      <div className="side-brand">
        <span className="emblem">Р</span>
        <div>
          <div className="brand-name">Роҳхат</div>
          <div className="brand-sub">Путевые листы · Минтранс РТ</div>
        </div>
      </div>

      <nav className="side-nav">
        {NAV.map(item => (
          <span key={item.href} style={{ display: 'contents' }}>
            {item.group && <span className="group-label">{item.group}</span>}
            <Link href={item.href} className={isActive(item.href) ? 'active' : ''}>
              <Icon d={item.icon} />
              {item.label}
            </Link>
          </span>
        ))}
        <Link href="/waybills/new" className="cta">
          <Icon d={I.plus} /> Новый лист
        </Link>
      </nav>

      {username && (
        <div className="side-user">
          <span className="avatar">{initials}</span>
          <span className="who">
            <span className="name">{username}</span>
            <span className="role">{roleLabel}</span>
          </span>
          <button onClick={logout}>Выход</button>
        </div>
      )}
    </aside>
  );
}
