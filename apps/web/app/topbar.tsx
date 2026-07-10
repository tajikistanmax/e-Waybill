'use client';

import { usePathname } from 'next/navigation';
import { Icon, P } from './icons';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

const TITLES: Record<string, { t: string; c: string }> = {
  '/dashboard': { t: 'nav.dashboard', c: 'dash.lead' },
  '/waybills': { t: 'nav.waybill.registry', c: 'nav.waybills' },
  '/waybills/new': { t: 'nav.waybill.new', c: 'nav.waybills' },
  '/med': { t: 'nav.med', c: 'nav.group.workplaces' },
  '/tech': { t: 'nav.tech', c: 'nav.group.workplaces' },
  '/company': { t: 'nav.company', c: 'nav.group.management' },
  '/reports': { t: 'nav.reports', c: 'nav.group.management' },
  '/dictionaries': { t: 'nav.dictionaries', c: 'nav.group.management' },
};

export function Topbar() {
  const pathname = usePathname();
  const { username, roles, logout } = useAuth();
  const { t, lang, setLang } = useT();
  const meta = TITLES[pathname] ?? (pathname.startsWith('/waybills/') ? { t: 'nav.waybills', c: 'nav.waybills' } : { t: 'app.title', c: '' });
  const roleKey = roles.find(r => ['SYSTEM_ADMIN', 'DISPATCHER', 'DOCTOR', 'MECHANIC', 'ACCOUNTANT', 'COMPANY_ADMIN', 'INSPECTOR'].includes(r));
  const roleLabel = roleKey ? t('role.' + roleKey) : '';
  const initials = (username || 'ЭП').slice(0, 2).toUpperCase();
  const now = new Date().toLocaleDateString(lang === 'tj' ? 'tg-TJ' : 'ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });

  return (
    <header className="topbar no-print">
      <div>
        <div className="tb-title">{t(meta.t)}</div>
        <div className="tb-crumb">{t(meta.c)}</div>
      </div>
      <div className="sp" />
      <span className="lang-switch">
        <button className={lang === 'ru' ? 'on' : ''} onClick={() => setLang('ru')}>RU</button>
        <button className={lang === 'tj' ? 'on' : ''} onClick={() => setLang('tj')}>TJ</button>
      </span>
      <span style={{ fontSize: 12.5, color: 'var(--muted)', margin: '0 4px' }}>{now}</span>
      <span className="tb-icon"><Icon d={P.bell} /><span className="tb-badge">3</span></span>
      <span className="tb-icon"><Icon d={P.mail} /></span>
      <span className="tb-icon"><Icon d={P.help} /></span>
      <div className="tb-user">
        <span className="av">{initials}</span>
        <div>
          <div className="u-name">{username}</div>
          <div className="u-role">{roleLabel}</div>
        </div>
        <button className="u-logout" onClick={logout}>{lang === 'tj' ? 'Баромад' : 'Выход'}</button>
      </div>
    </header>
  );
}
