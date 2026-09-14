'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Icon, P } from '../icons';
import { useT } from '@/lib/i18n';

const TABS: { href: string; labelKey: string; icon: string }[] = [
  { href: '/registry/vehicles', labelKey: 'col.transport', icon: P.car },
  { href: '/registry/drivers', labelKey: 'col.drivers', icon: P.users },
  { href: '/registry/employees', labelKey: 'col.employees', icon: P.building },
  { href: '/registry/devices', labelKey: 'nav.devices', icon: P.phone },
];

/**
 * Общий каркас реестров: заголовок + переключатель разделов (Транспорт / Водители /
 * Сотрудники). У каждого раздела свой адрес (/registry/vehicles и т.д.) — ссылки
 * разделяемые, кнопка «назад» и F5 работают корректно.
 */
export default function RegistryLayout({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const { t } = useT();
  const active = TABS.find(s => path.startsWith(s.href)) ?? TABS[0];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.registry')} — {t(active.labelKey)}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('reg.lead')}</div>
        </div>
      </div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        {TABS.map(s => {
          const sel = s.href === active.href;
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

      {children}
    </>
  );
}
