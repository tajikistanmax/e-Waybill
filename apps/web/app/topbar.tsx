'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Icon, P } from './icons';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { wb, md, type PlatformSetting } from '@/lib/api';

const TITLES: Record<string, { t: string; c: string }> = {
  '/dashboard': { t: 'nav.dashboard', c: 'dash.lead' },
  '/waybills': { t: 'nav.waybill.registry', c: 'nav.waybills' },
  '/waybills/new': { t: 'nav.waybill.new', c: 'nav.waybills' },
  '/dispatcher': { t: 'nav.dispatcher', c: 'nav.group.workplaces' },
  '/med': { t: 'nav.med', c: 'nav.group.workplaces' },
  '/tech': { t: 'nav.tech', c: 'nav.group.workplaces' },
  '/driver': { t: 'nav.driver', c: 'nav.group.workplaces' },
  '/inspector': { t: 'nav.inspector', c: 'nav.group.workplaces' },
  '/company': { t: 'nav.company', c: 'nav.group.management' },
  '/registry': { t: 'nav.registry', c: 'nav.group.management' },
  '/violations': { t: 'nav.violations', c: 'nav.group.management' },
  '/reports': { t: 'nav.reports', c: 'nav.group.management' },
  '/dictionaries': { t: 'nav.dictionaries', c: 'nav.group.management' },
  '/settings': { t: 'nav.settings', c: 'nav.group.management' },
};

export function Topbar() {
  const pathname = usePathname();
  const { username, roles, logout, authenticated } = useAuth();
  const { t, lang, setLang } = useT();
  const [unread, setUnread] = useState(0);
  const [helpOpen, setHelpOpen] = useState(false);
  const [contacts, setContacts] = useState<Record<string, string>>({});

  // Контакты поддержки — из публичных настроек платформы (§29), для попапа «Помощь».
  useEffect(() => {
    md.publicSettings()
      .then((rows: PlatformSetting[]) => setContacts(Object.fromEntries(
        rows.filter(r => r.category === 'general').map(r => [r.settingKey, r.settingValue ?? '']))))
      .catch(() => { /* нет связи — попап покажет только заголовок */ });
  }, []);

  // Счётчик непрочитанных: при входе, при смене страницы и по событию notif-changed
  // (страница уведомлений шлёт его после прочтения — счётчик обновляется сразу).
  useEffect(() => {
    if (!authenticated) { setUnread(0); return; }
    let alive = true;
    const refetch = () => wb.unreadCount().then(r => { if (alive) setUnread(r.count); }).catch(() => {});
    refetch();
    window.addEventListener('notif-changed', refetch);
    return () => { alive = false; window.removeEventListener('notif-changed', refetch); };
  }, [authenticated, pathname]);
  const meta = TITLES[pathname]
    ?? (pathname.startsWith('/waybills/') ? { t: 'nav.waybills', c: 'nav.waybills' }
      : pathname.startsWith('/settings/') ? { t: 'nav.settings', c: 'nav.group.management' }
      : { t: 'app.title', c: '' });
  const roleKey = roles.find(r => ['SYSTEM_ADMIN', 'COMPANY_ADMIN', 'MINTRANS_ANALYST', 'DISPATCHER', 'DOCTOR', 'MECHANIC', 'ACCOUNTANT', 'INSPECTOR', 'DRIVER', 'API_INTEGRATOR'].includes(r));
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
      <Link href="/notifications" className="tb-icon" aria-label={t('notif.title')} style={{ position: 'relative' }}>
        <Icon d={P.bell} />
        {unread > 0 && <span className="tb-badge">{unread > 99 ? '99+' : unread}</span>}
      </Link>
      <div style={{ position: 'relative', display: 'inline-flex' }}>
        <button type="button" className="tb-icon" aria-label={t('help.title')} onClick={() => setHelpOpen(o => !o)}>
          <Icon d={P.help} />
        </button>
        {helpOpen && (
          <>
            <div onClick={() => setHelpOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 40 }} />
            <div style={{ position: 'absolute', right: 0, top: 'calc(100% + 8px)', width: 268, background: '#fff', border: '1px solid var(--line)', borderRadius: 12, boxShadow: '0 12px 34px rgba(15,32,60,.16)', padding: 14, zIndex: 41 }}>
              <div style={{ fontWeight: 700, fontSize: 13.5 }}>{t('help.title')}</div>
              <div style={{ fontSize: 12, color: 'var(--muted)', margin: '4px 0 10px' }}>{t('help.lead')}</div>
              {contacts.support_phone && (
                <a href={`tel:${contacts.support_phone.replace(/[^\d+]/g, '')}`} style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '5px 0', fontSize: 13, color: 'var(--ink)', textDecoration: 'none' }}>
                  <Icon d={P.route} cls="" style={{ width: 15, height: 15, color: 'var(--blue-600)' }} /> {contacts.support_phone}
                </a>
              )}
              {contacts.support_email && (
                <a href={`mailto:${contacts.support_email}`} style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '5px 0', fontSize: 13, color: 'var(--ink)', textDecoration: 'none' }}>
                  <Icon d={P.mail} cls="" style={{ width: 15, height: 15, color: 'var(--blue-600)' }} /> {contacts.support_email}
                </a>
              )}
              {contacts.support_website && (
                <a href={contacts.support_website} target="_blank" rel="noreferrer" style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '5px 0', fontSize: 13, color: 'var(--blue-600)', textDecoration: 'none' }}>
                  <Icon d={P.globe} cls="" style={{ width: 15, height: 15 }} /> {contacts.support_website}
                </a>
              )}
              {contacts.support_hours && <div style={{ fontSize: 11.5, color: 'var(--muted)', marginTop: 8 }}>{contacts.support_hours}</div>}
            </div>
          </>
        )}
      </div>
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
