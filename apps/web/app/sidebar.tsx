'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Icon, P } from './icons';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { useBrand, BrandLogo } from '@/lib/brand';
import { useRoleAccess } from '@/lib/roleaccess';
import { canCreateWaybill, canSeeConsignmentNotes } from '@/lib/roles';
import { carrierReportsFor, generalReportsFor, isGeneralPath, isLinkActive, type ReportLink } from './reports/nav';

/**
 * Раскрывающаяся группа меню. В свёрнутом меню (только значки) подпункты скрыты,
 * поэтому заголовок группы становится ссылкой на первый её отчёт.
 */
function NavGroup({ label, icon, links, open, setOpen, collapsed, pathname, t }: {
  label: string; icon: string; links: ReportLink[]; open: boolean; setOpen: (f: (o: boolean) => boolean) => void;
  collapsed: boolean; pathname: string; t: (k: string) => string;
}) {
  if (links.length === 0) return null;
  const anyActive = links.some(l => isLinkActive(l, pathname));
  if (collapsed) {
    return <Link href={links[0].href} className={`snav ${anyActive ? 'active' : ''}`} title={label}><Icon d={icon} /> {label}</Link>;
  }
  return (
    <>
      <button className={`snav ${open ? 'open' : ''}`} onClick={() => setOpen(o => !o)} aria-expanded={open}>
        <Icon d={icon} /> {label}
        <Icon d={P.chevron} cls="chev" />
      </button>
      {open && (
        <div className="subnav">
          {links.map(l => (
            <Link key={l.href} href={l.href} className={isLinkActive(l, pathname) ? 'active' : ''}>{t(l.label)}</Link>
          ))}
        </div>
      )}
    </>
  );
}

export function Sidebar() {
  const pathname = usePathname();
  const { logout, roles } = useAuth();
  const { t } = useT();
  const brand = useBrand();
  const { navFor } = useRoleAccess();
  const [open, setOpen] = useState(pathname.startsWith('/waybills') || pathname.startsWith('/consignment-notes'));
  // Сворачивание бокового меню (только значки) — выбор пользователя запоминается в браузере.
  // Чтение только после монтирования: при SSR localStorage нет, иначе разъезжается разметка.
  const [collapsed, setCollapsed] = useState(false);
  useEffect(() => {
    try { setCollapsed(localStorage.getItem('epd.sidebar.collapsed') === '1'); } catch { /* приватный режим */ }
  }, []);
  const toggleCollapsed = () => {
    setCollapsed(v => {
      const next = !v;
      try { localStorage.setItem('epd.sidebar.collapsed', next ? '1' : '0'); } catch { /* приватный режим */ }
      return next;
    });
  };
  const nav = navFor(roles);

  const active = (h: string) => pathname === h || pathname.startsWith(h + '/');
  const wbActive = pathname === '/waybills' || (pathname.startsWith('/waybills/') && pathname !== '/waybills/new');
  const showWorkplaces = nav.has('med') || nav.has('tech') || nav.has('fuel') || nav.has('driver') || nav.has('inspector') || nav.has('fleet') || nav.has('consignments');
  // Мониторинг переехал в «Управление» — но у ролей без управленческих разделов (инспектор)
  // он единственный, поэтому заголовок группы показываем и ради него.
  // «Транспорт и водители» ролево: врач осматривает водителей → «Водители», механик ТС → «Транспорт».
  const manages = roles.includes('DISPATCHER') || roles.includes('COMPANY_ADMIN') || roles.includes('BRANCH_ADMIN') || roles.includes('SYSTEM_ADMIN');
  const fleetLabel = !manages && roles.includes('DOCTOR') ? t('fleet.tab.drivers')
    : !manages && roles.includes('MECHANIC') ? t('fleet.tab.vehicles') : t('nav.fleet');
  const fleetIcon = !manages && roles.includes('DOCTOR') ? P.user : P.car;
  const showManagement = nav.has('company') || nav.has('access') || nav.has('registry') || nav.has('violations') || nav.has('reports') || nav.has('dictionaries') || nav.has('monitoring') || nav.has('settings');
  const canCreate = canCreateWaybill(roles);
  const canSeeNotes = canSeeConsignmentNotes(roles);
  const carrierLinks = carrierReportsFor(roles);
  const generalLinks = generalReportsFor(roles);
  const inReports = pathname === '/reports' || pathname.startsWith('/reports/');
  const [repOpen, setRepOpen] = useState(inReports && !isGeneralPath(pathname));
  const [genOpen, setGenOpen] = useState(isGeneralPath(pathname));
  // Переход по ссылке извне меню (дашборд, закладка) раскрывает группу нужного отчёта.
  useEffect(() => {
    if (isGeneralPath(pathname)) setGenOpen(true);
    else if (inReports) setRepOpen(true);
  }, [pathname, inReports]);

  return (
    <aside className={`sidebar no-print${collapsed ? ' collapsed' : ''}`}>
      <div className="side-brand">
        <span className="mark"><BrandLogo /></span>
        <div>
          <div className="bt">{brand.name}</div>
          <div className="bs">{brand.subtitle || t('brand.sub')}</div>
        </div>
        <button
          type="button"
          className="side-toggle"
          onClick={toggleCollapsed}
          title={collapsed ? t('nav.expand') : t('nav.collapse')}
          aria-label={collapsed ? t('nav.expand') : t('nav.collapse')}
          aria-expanded={!collapsed}
        >
          <Icon d={P.menu} cls="" />
        </button>
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
                {/* Выписывает ПЛ только диспетчер: бухгалтеру/аналитику пункт не показываем —
                    сервер всё равно вернёт 403 на создании (см. canCreateWaybill). */}
                {canCreate && <Link href="/waybills/new" className={pathname === '/waybills/new' ? 'active' : ''}>{t('nav.waybill.new')}</Link>}
                <Link href="/waybills" className={wbActive ? 'active' : ''}>{t('nav.waybill.registry')}</Link>
                {/* Реестр борхатов 2-Б — роли, которым сервер его отдаёт (ConsignmentNoteRegistryController). */}
                {canSeeNotes && <Link href="/consignment-notes" className={active('/consignment-notes') ? 'active' : ''}>{t('nav.notes')}</Link>}
              </div>
            )}
          </>
        )}

        {showWorkplaces && <div className="group-label">{t('nav.group.workplaces')}</div>}
        {nav.has('med') && <Link href="/med" className={`snav ${pathname === '/med' ? 'active' : ''}`}><Icon d={P.med} /> {t('nav.med')}</Link>}
        {nav.has('med') && <Link href="/med/journal" className={`snav ${active('/med/journal') ? 'active' : ''}`}><Icon d={P.book} /> {t('med.history')}</Link>}
        {nav.has('tech') && <Link href="/tech" className={`snav ${pathname === '/tech' ? 'active' : ''}`}><Icon d={P.wrench} /> {t('nav.tech')}</Link>}
        {nav.has('tech') && <Link href="/tech/journal" className={`snav ${active('/tech/journal') ? 'active' : ''}`}><Icon d={P.book} /> {t('tech.journal.link')}</Link>}
        {nav.has('fuel') && <Link href="/fuel" className={`snav ${active('/fuel') ? 'active' : ''}`}><Icon d={P.route} /> {t('nav.fuel')}</Link>}
        {nav.has('driver') && (
          <>
            <Link href="/driver" className={`snav ${pathname === '/driver' ? 'active' : ''}`}><Icon d={P.car} /> {t('nav.driver')}</Link>
            <Link href="/driver/waybills" className={`snav ${active('/driver/waybills') ? 'active' : ''}`}><Icon d={P.doc} /> {t('drv.mywaybills')}</Link>
          </>
        )}
        {nav.has('inspector') && <Link href="/inspector" className={`snav ${active('/inspector') ? 'active' : ''}`}><Icon d={P.shield} /> {t('nav.inspector')}</Link>}
        {nav.has('fleet') && <Link href="/fleet/vehicles" className={`snav ${active('/fleet') ? 'active' : ''}`}><Icon d={fleetIcon} /> {fleetLabel}</Link>}
        {nav.has('consignments') && <Link href="/consignments" className={`snav ${active('/consignments') ? 'active' : ''}`}><Icon d={P.doc} /> {t('nav.consignments')}</Link>}
        {/* Аналитик Минтранса (без раздела «Путевые листы») и грузоотправитель/экспедитор — отдельным пунктом. */}
        {canSeeNotes && !nav.has('waybills') && (
          <Link href="/consignment-notes" className={`snav ${active('/consignment-notes') ? 'active' : ''}`}><Icon d={P.doc} /> {t('nav.notes')}</Link>
        )}

        {showManagement && <div className="group-label">{t('nav.group.management')}</div>}
        {nav.has('company') && <Link href="/company" className={`snav ${active('/company') && !active('/company/access') ? 'active' : ''}`}><Icon d={P.building} /> {t('nav.company')}</Link>}
        {nav.has('access') && <Link href="/company/access" className={`snav ${pathname === '/company/access' ? 'active' : ''}`}><Icon d={P.users} /> {t('nav.access')}</Link>}
        {/* Все учётные записи платформы (legacy /admin/user) — только администратору платформы. */}
        {nav.has('access') && roles.includes('SYSTEM_ADMIN') && <Link href="/company/access/users" className={`snav ${active('/company/access/users') ? 'active' : ''}`}><Icon d={P.user} /> {t('nav.users')}</Link>}
        {nav.has('registry') && <Link href="/registry/vehicles" className={`snav ${active('/registry') ? 'active' : ''}`}><Icon d={P.users} /> {t('nav.registry')}</Link>}
        {nav.has('violations') && <Link href="/violations" className={`snav ${active('/violations') ? 'active' : ''}`}><Icon d={P.shield} /> {t('nav.violations')}</Link>}
        {/* Отчёты — две группы, как меню «Ҳисобот» старой платформы: свои отчёты предприятия
            и отдельно «Умумӣ» — сводные Минтранса по всем организациям (см. reports/nav.ts). */}
        {nav.has('reports') && (
          <NavGroup
            label={t('nav.reports')} icon={P.chart} links={carrierLinks}
            open={repOpen} setOpen={setRepOpen} collapsed={collapsed} pathname={pathname} t={t}
          />
        )}
        {nav.has('reports') && generalLinks.length > 0 && (
          <NavGroup
            label={t('nav.general')} icon={P.globe} links={generalLinks}
            open={genOpen} setOpen={setGenOpen} collapsed={collapsed} pathname={pathname} t={t}
          />
        )}
        {nav.has('dictionaries') && <Link href="/dictionaries/routes" className={`snav ${active('/dictionaries') ? 'active' : ''}`}><Icon d={P.book} /> {t('nav.dictionaries')}</Link>}
        {/* GPS-мониторинг — надзорный раздел (наблюдение за парком на линии), а не рабочее
            место: место в «Управлении», между справочниками и настройками. */}
        {nav.has('monitoring') && <Link href="/monitoring" className={`snav ${active('/monitoring') ? 'active' : ''}`}><Icon d={P.route} /> {t('nav.monitoring')}</Link>}
        {nav.has('settings') && <Link href="/settings" className={`snav ${active('/settings') ? 'active' : ''}`}><Icon d={P.settings} /> {t('nav.settings')}</Link>}
      </nav>

      <div className="side-foot">
        <button onClick={logout}><Icon d={P.login} cls="ic" /> {t('nav.logout')}</button>
      </div>
    </aside>
  );
}
