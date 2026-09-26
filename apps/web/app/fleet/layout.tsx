'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Icon, P } from '../icons';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';

/**
 * Общий каркас раздела «Транспорт и водители»: заголовок + переключатель разделов
 * (Транспорт / Водители / Сотрудники). У каждого свой адрес (/fleet/vehicles и т.д.) —
 * ссылки разделяемые, «назад» и F5 работают. Состав разделов зависит от роли:
 * механик осматривает ТС, врач — водителей, управляющие роли видят всё.
 */
export default function FleetLayout({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const { t } = useT();
  const { roles } = useAuth();

  const canManage = ['DISPATCHER', 'COMPANY_ADMIN', 'BRANCH_ADMIN', 'SYSTEM_ADMIN'].some(r => roles.includes(r));
  const tabs = [
    { href: '/fleet/vehicles', labelKey: 'fleet.tab.vehicles', icon: P.car, show: canManage || roles.includes('MECHANIC') },
    { href: '/fleet/drivers', labelKey: 'fleet.tab.drivers', icon: P.user, show: canManage || roles.includes('DOCTOR') },
    { href: '/fleet/employees', labelKey: 'fleet.tab.employees', icon: P.users, show: canManage },
    // Документы и печать своей организации — администратору перевозчика (сверка 25.09, F9).
    { href: '/fleet/organization', labelKey: 'fleet.tab.org', icon: P.building, show: roles.includes('COMPANY_ADMIN') },
  ].filter(s => s.show);

  const active = tabs.find(s => path.startsWith(s.href)) ?? tabs[0];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.fleet')}{active ? ` — ${t(active.labelKey)}` : ''}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('fleet.lead')}</div>
        </div>
      </div>

      {tabs.length > 1 && (
        <div className="kpi-row" style={{ gridTemplateColumns: `repeat(${tabs.length}, 1fr)` }}>
          {tabs.map(s => {
            const sel = active && s.href === active.href;
            return (
              <Link
                key={s.href}
                href={s.href}
                className="kpi"
                style={{
                  textDecoration: 'none', gap: 12,
                  border: sel ? '1.5px solid var(--blue-500)' : '1px solid var(--line)',
                  boxShadow: sel ? '0 6px 16px -6px rgba(37,99,235,.4)' : 'var(--shadow-sm)',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <span className="k-ic ic-blue"><Icon d={s.icon} cls="" /></span>
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontWeight: 700, fontSize: 14, color: sel ? 'var(--blue-700)' : 'var(--ink)' }}>{t(s.labelKey)}</div>
                    <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{sel ? t('reg.currentsection') : t('reg.openregistry')}</div>
                  </div>
                </div>
              </Link>
            );
          })}
        </div>
      )}

      {children}
    </>
  );
}
