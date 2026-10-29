'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { md } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { Icon, P } from '../icons';
import { useT } from '@/lib/i18n';

type CountKey = 'organizations' | 'vehicles' | 'drivers' | 'employees';

const TABS: { href: string; labelKey: string; icon: string; key: CountKey }[] = [
  // Организации — реестр перевозчиков для надзора (сверка 25.09, F10), только просмотр.
  // SYSTEM_ADMIN эту вкладку не видит (отфильтровано ниже): у него тот же список организаций
  // уже есть в «Компания» (/company), с полноценным CRUD — показывать его же вторым, read-only,
  // было дублированием (замечено пользователем 26.10, расхождение счётчиков 592/472 — тот же баг).
  { href: '/registry/organizations', labelKey: 'regorg.h', icon: P.building, key: 'organizations' },
  { href: '/registry/vehicles', labelKey: 'col.transport', icon: P.car, key: 'vehicles' },
  { href: '/registry/drivers', labelKey: 'col.drivers', icon: P.users, key: 'drivers' },
  { href: '/registry/employees', labelKey: 'col.employees', icon: P.building, key: 'employees' },
];

/**
 * Общий каркас реестров: заголовок + переключатель разделов (Транспорт / Водители /
 * Сотрудники). У каждого раздела свой адрес (/registry/vehicles и т.д.) — ссылки
 * разделяемые, кнопка «назад» и F5 работают корректно.
 */
export default function RegistryLayout({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const router = useRouter();
  const { t } = useT();
  const { roles } = useAuth();
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');
  const tabs = isSysAdmin ? TABS.filter(s => s.key !== 'organizations') : TABS;
  const active = tabs.find(s => path.startsWith(s.href)) ?? tabs[0];

  // Прямой заход по ссылке/закладке на убранную для SYSTEM_ADMIN вкладку — туда же, где у него
  // этот список уже есть, с правами на редактирование.
  useEffect(() => {
    if (isSysAdmin && path.startsWith('/registry/organizations')) router.replace('/company');
  }, [isSysAdmin, path, router]);

  // Счётчики на карточках разделов: ТС/водители/сотрудники — одним агрегатным запросом
  // (organizations/counts, без N+1). Скоуп — по токену: тенант видит свои организации,
  // платформенная роль — все.
  const [counts, setCounts] = useState<Partial<Record<CountKey, number>>>({});
  useEffect(() => {
    let alive = true;
    md.organizationCounts()
      .then(rows => {
        if (!alive) return;
        const sum = (f: 'vehicles' | 'drivers' | 'employees') => rows.reduce((acc, r) => acc + Number(r[f] ?? 0), 0);
        // rows здесь — список организаций, у которых есть хотя бы один ТС/водитель/сотрудник
        // (так считает /organizations/counts): он короче полного реестра, поэтому для карточки
        // «Организации» число берём отдельным вызовом, а не rows.length (расхождение 592/472,
        // замечено пользователем 26.10).
        setCounts(c => ({ ...c, vehicles: sum('vehicles'), drivers: sum('drivers'), employees: sum('employees') }));
      })
      .catch(() => { /* счётчик не критичен — карточка просто без числа */ });
    md.organizations()
      .then(rows => { if (alive) setCounts(c => ({ ...c, organizations: rows.length })); })
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
        {tabs.map(s => {
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
