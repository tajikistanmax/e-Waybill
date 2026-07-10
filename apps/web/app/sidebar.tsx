'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import { Icon, P } from './icons';
import { useAuth } from '@/lib/auth';

export function Sidebar() {
  const pathname = usePathname();
  const { logout } = useAuth();
  const [open, setOpen] = useState(pathname.startsWith('/waybills'));

  const active = (h: string) => pathname === h || pathname.startsWith(h + '/');
  const wbActive = pathname === '/waybills' || (pathname.startsWith('/waybills/') && pathname !== '/waybills/new');

  return (
    <aside className="sidebar no-print">
      <div className="side-brand">
        <span className="mark"><Icon d={P.docActive} cls="" /></span>
        <div>
          <div className="bt">DTS</div>
          <div className="bs">Электронный путевой лист</div>
        </div>
      </div>

      <nav className="side-nav">
        <Link href="/dashboard" className={`snav ${active('/dashboard') ? 'active' : ''}`}>
          <Icon d={P.home} /> Главная панель
        </Link>

        <button className={`snav ${wbActive || pathname === '/waybills/new' ? '' : ''} ${open ? 'open' : ''}`} onClick={() => setOpen(o => !o)}>
          <Icon d={P.doc} /> Путевые листы
          <Icon d={P.chevron} cls="chev" />
        </button>
        {open && (
          <div className="subnav">
            <Link href="/waybills/new" className={pathname === '/waybills/new' ? 'active' : ''}>Создать путевой лист</Link>
            <Link href="/waybills" className={wbActive ? 'active' : ''}>Реестр путевых листов</Link>
          </div>
        )}

        <div style={{ padding: '10px 12px 4px', fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: '#5f6f92', fontWeight: 600 }}>Рабочие места</div>
        <Link href="/med" className={`snav ${active('/med') ? 'active' : ''}`}><Icon d={P.med} /> АРМ врача</Link>
        <Link href="/tech" className={`snav ${active('/tech') ? 'active' : ''}`}><Icon d={P.wrench} /> АРМ механика</Link>

        <div style={{ padding: '10px 12px 4px', fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: '#5f6f92', fontWeight: 600 }}>Управление</div>
        <Link href="/company" className={`snav ${active('/company') ? 'active' : ''}`}><Icon d={P.building} /> Компания</Link>
        <Link href="/reports" className={`snav ${active('/reports') ? 'active' : ''}`}><Icon d={P.chart} /> Отчёты и аналитика</Link>
        <Link href="/dictionaries" className={`snav ${active('/dictionaries') ? 'active' : ''}`}><Icon d={P.book} /> Справочники</Link>
      </nav>

      <div className="side-foot">
        <button onClick={logout}><Icon d={P.login} cls="ic" /> Выход из системы</button>
      </div>
    </aside>
  );
}
