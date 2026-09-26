'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';
import { md } from '@/lib/api';
import { Icon, P } from '../icons';
import { useT } from '@/lib/i18n';

type CountKey = 'organizations' | 'vehicles' | 'drivers' | 'employees' | 'devices';

const TABS: { href: string; labelKey: string; icon: string; key: CountKey }[] = [
  // Организации — реестр перевозчиков для надзора (сверка 25.09, F10), только просмотр.
  { href: '/registry/organizations', labelKey: 'regorg.h', icon: P.building, key: 'organizations' },
  { href: '/registry/vehicles', labelKey: 'col.transport', icon: P.car, key: 'vehicles' },
  { href: '/registry/drivers', labelKey: 'col.drivers', icon: P.users, key: 'drivers' },
  { href: '/registry/employees', labelKey: 'col.employees', icon: P.building, key: 'employees' },
  { href: '/registry/devices', labelKey: 'nav.devices', icon: P.phone, key: 'devices' },
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

  // Счётчики на карточках разделов: ТС/водители/сотрудники — одним агрегатным запросом
  // (organizations/counts, без N+1), устройства — списком (их немного). Скоуп — по токену:
  // тенант видит свои организации, платформенная роль — все.
  const [counts, setCounts] = useState<Partial<Record<CountKey, number>>>({});
  useEffect(() => {
    let alive = true;
    md.organizationCounts()
      .then(rows => {
        if (!alive) return;
        const sum = (f: 'vehicles' | 'drivers' | 'employees') => rows.reduce((acc, r) => acc + Number(r[f] ?? 0), 0);
        setCounts(c => ({ ...c, organizations: rows.length, vehicles: sum('vehicles'), drivers: sum('drivers'), employees: sum('employees') }));
      })
      .catch(() => { /* счётчик не критичен — карточка просто без числа */ });
    md.mobileDevices()
      .then(rows => { if (alive) setCounts(c => ({ ...c, devices: rows.length })); })
      .catch(() => { /* см. выше */ });
    return () => { alive = false; };
  }, []);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.registry')} — {t(active.labelKey)}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('reg.lead')}</div>
        </div>
      </div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))' }}>
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
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ fontWeight: 700, fontSize: 14, color: sel ? 'var(--blue-700)' : 'var(--ink)' }}>{t(s.labelKey)}</div>
                  {/* Количество записей раздела — под названием (решение владельца 22.09). */}
                  <div style={{ fontSize: 20, fontWeight: 800, color: 'var(--ink)', lineHeight: 1.2, fontVariantNumeric: 'tabular-nums' }}>
                    {counts[s.key] === undefined ? '—' : counts[s.key]!.toLocaleString('ru-RU')}
                  </div>
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
