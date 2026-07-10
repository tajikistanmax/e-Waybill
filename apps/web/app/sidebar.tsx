'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import { Icon, P } from './icons';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

export function Sidebar() {
  const pathname = usePathname();
  const { logout } = useAuth();
  const { t } = useT();
  const [open, setOpen] = useState(pathname.startsWith('/waybills'));

  const active = (h: string) => pathname === h || pathname.startsWith(h + '/');
  const wbActive = pathname === '/waybills' || (pathname.startsWith('/waybills/') && pathname !== '/waybills/new');

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
        <Link href="/dashboard" className={`snav ${active('/dashboard') ? 'active' : ''}`}>
          <Icon d={P.home} /> {t('nav.dashboard')}
        </Link>

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

        <div className="group-label">{t('nav.group.workplaces')}</div>
        <Link href="/med" className={`snav ${active('/med') ? 'active' : ''}`}><Icon d={P.med} /> {t('nav.med')}</Link>
        <Link href="/tech" className={`snav ${active('/tech') ? 'active' : ''}`}><Icon d={P.wrench} /> {t('nav.tech')}</Link>

        <div className="group-label">{t('nav.group.management')}</div>
        <Link href="/company" className={`snav ${active('/company') ? 'active' : ''}`}><Icon d={P.building} /> {t('nav.company')}</Link>
        <Link href="/reports" className={`snav ${active('/reports') ? 'active' : ''}`}><Icon d={P.chart} /> {t('nav.reports')}</Link>
        <Link href="/dictionaries" className={`snav ${active('/dictionaries') ? 'active' : ''}`}><Icon d={P.book} /> {t('nav.dictionaries')}</Link>
      </nav>

      <div className="side-foot">
        <button onClick={logout}><Icon d={P.login} cls="ic" /> {t('nav.logout')}</button>
      </div>
    </aside>
  );
}
