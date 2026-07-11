'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import { Icon, P } from './icons';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { visibleNav } from '@/lib/roles';

export function Sidebar() {
  const pathname = usePathname();
  const { logout, roles } = useAuth();
  const { t } = useT();
  const [open, setOpen] = useState(pathname.startsWith('/waybills'));
  const nav = visibleNav(roles);

  const active = (h: string) => pathname === h || pathname.startsWith(h + '/');
  const wbActive = pathname === '/waybills' || (pathname.startsWith('/waybills/') && pathname !== '/waybills/new');
  const showWorkplaces = nav.has('med') || nav.has('tech') || nav.has('driver') || nav.has('inspector');
  const showManagement = nav.has('company') || nav.has('registry') || nav.has('violations') || nav.has('reports') || nav.has('dictionaries') || nav.has('settings');

  return (
    <aside className="sidebar no-print">
      <div className="side-brand">
        <span className="mark"><Icon d={P.docActive} cls="" /></span>
        <div>
          <div className="bt">DTS</div>
          <div className="bs">{t('brand.sub')}</div>
        </div>
      </div>

      <nav className="side-nav">
        {nav.has('dispatcher') && (
          <Link href="/dispatcher" className={`snav ${active('/dispatcher') ? 'active' : ''}`}>
            <Icon d={P.route} /> {t('nav.dispatcher')}
          </Link>
        )}

        {nav.has('dashboard') && (
          <Link href="/dashboard" className={`snav ${active('/dashboard') ? 'active' : ''}`}>
            <Icon d={P.home} /> {t('nav.dashboard')}
          </Link>
        )}

        {nav.has('waybills') && (
          <>
            <button className={`snav ${open ? 'open' : ''}`} onClick={() => setOpen(o => !o)}>
              <Icon d={P.doc} /> {t('nav.waybills')}
              <Icon d={P.chevron} cls="chev" />
            </button>
            {open && (
              <div className="subnav">
                <Link href="/waybills/new" className={pathname === '/waybills/new' ? 'active' : ''}>{t('nav.waybill.new')}</Link>
                <Link href="/waybills" className={wbActive ? 'active' : ''}>{t('nav.waybill.registry')}</Link>
              </div>
            )}
          </>
        )}

        {showWorkplaces && <div className="group-label">{t('nav.group.workplaces')}</div>}
        {nav.has('med') && <Link href="/med" className={`snav ${active('/med') ? 'active' : ''}`}><Icon d={P.med} /> {t('nav.med')}</Link>}
        {nav.has('tech') && <Link href="/tech" className={`snav ${active('/tech') ? 'active' : ''}`}><Icon d={P.wrench} /> {t('nav.tech')}</Link>}
        {nav.has('driver') && <Link href="/driver" className={`snav ${active('/driver') ? 'active' : ''}`}><Icon d={P.car} /> {t('nav.driver')}</Link>}
        {nav.has('inspector') && <Link href="/inspector" className={`snav ${active('/inspector') ? 'active' : ''}`}><Icon d={P.shield} /> {t('nav.inspector')}</Link>}

        {showManagement && <div className="group-label">{t('nav.group.management')}</div>}
        {nav.has('company') && <Link href="/company" className={`snav ${active('/company') ? 'active' : ''}`}><Icon d={P.building} /> {t('nav.company')}</Link>}
        {nav.has('registry') && <Link href="/registry" className={`snav ${active('/registry') ? 'active' : ''}`}><Icon d={P.users} /> {t('nav.registry')}</Link>}
        {nav.has('violations') && <Link href="/violations" className={`snav ${active('/violations') ? 'active' : ''}`}><Icon d={P.shield} /> {t('nav.violations')}</Link>}
        {nav.has('reports') && <Link href="/reports" className={`snav ${active('/reports') ? 'active' : ''}`}><Icon d={P.chart} /> {t('nav.reports')}</Link>}
        {nav.has('dictionaries') && <Link href="/dictionaries" className={`snav ${active('/dictionaries') ? 'active' : ''}`}><Icon d={P.book} /> {t('nav.dictionaries')}</Link>}
        {nav.has('settings') && <Link href="/settings" className={`snav ${active('/settings') ? 'active' : ''}`}><Icon d={P.settings} /> {t('nav.settings')}</Link>}
      </nav>

      <div className="side-foot">
        <button onClick={logout}><Icon d={P.login} cls="ic" /> {t('nav.logout')}</button>
      </div>
    </aside>
  );
}
