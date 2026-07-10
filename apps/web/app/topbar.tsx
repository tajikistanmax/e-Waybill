'use client';

import { usePathname } from 'next/navigation';
import { Icon, P } from './icons';
import { useAuth } from '@/lib/auth';

const TITLES: Record<string, { t: string; c: string }> = {
  '/dashboard': { t: 'Главная панель', c: 'Обзор состояния системы и ключевых показателей' },
  '/waybills': { t: 'Реестр путевых листов', c: 'Главная панель / Путевые листы' },
  '/waybills/new': { t: 'Новый путевой лист', c: 'Главная панель / Путевые листы / Создание' },
  '/med': { t: 'АРМ медицинского работника', c: 'Главная панель / Медосмотры' },
  '/tech': { t: 'АРМ механика', c: 'Главная панель / Техосмотры' },
  '/company': { t: 'Кабинет компании', c: 'Главная панель / Управление' },
  '/reports': { t: 'Отчёты и аналитика', c: 'Главная панель / Отчёты' },
  '/dictionaries': { t: 'Справочники', c: 'Главная панель / Управление' },
};

const ROLE_RU: Record<string, string> = {
  SYSTEM_ADMIN: 'Суперадминистратор', DISPATCHER: 'Диспетчер', DOCTOR: 'Медработник',
  MECHANIC: 'Механик', ACCOUNTANT: 'Бухгалтер', COMPANY_ADMIN: 'Администратор', INSPECTOR: 'Инспектор',
};

export function Topbar() {
  const pathname = usePathname();
  const { username, roles, logout } = useAuth();
  const meta = TITLES[pathname] ?? (pathname.startsWith('/waybills/') ? { t: 'Путевой лист', c: 'Главная панель / Путевые листы' } : { t: 'DTS', c: '' });
  const roleLabel = roles.map(r => ROLE_RU[r]).find(Boolean) ?? '';
  const initials = (username || 'ЭП').slice(0, 2).toUpperCase();
  const now = new Date().toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });

  return (
    <header className="topbar no-print">
      <div>
        <div className="tb-title">{meta.t}</div>
        <div className="tb-crumb">{meta.c}</div>
      </div>
      <div className="sp" />
      <span style={{ fontSize: 12.5, color: 'var(--muted)', marginRight: 4 }}>{now}</span>
      <span className="tb-icon"><Icon d={P.bell} /><span className="tb-badge">3</span></span>
      <span className="tb-icon"><Icon d={P.mail} /></span>
      <span className="tb-icon"><Icon d={P.help} /></span>
      <div className="tb-user">
        <span className="av">{initials}</span>
        <div>
          <div className="u-name">{username}</div>
          <div className="u-role">{roleLabel}</div>
        </div>
        <button className="u-logout" onClick={logout}>Выход</button>
      </div>
    </header>
  );
}
