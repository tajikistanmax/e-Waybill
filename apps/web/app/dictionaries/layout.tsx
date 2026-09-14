'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Icon, P } from '../icons';
import { useT } from '@/lib/i18n';

const SECTIONS: { href: string; labelKey: string; descKey: string; icon: string; cls: string }[] = [
  { href: '/dictionaries/routes', labelKey: 'dict.sec.routes', descKey: 'dict.sec.routes.d', icon: P.route, cls: 'ic-blue' },
  { href: '/dictionaries/clients', labelKey: 'dict.sec.clients', descKey: 'dict.sec.clients.d', icon: P.building, cls: 'ic-cyan' },
  { href: '/dictionaries/cargos', labelKey: 'dict.sec.cargos', descKey: 'dict.sec.cargos.d', icon: P.doc, cls: 'ic-green' },
  { href: '/dictionaries/fuel-norms', labelKey: 'dict.sec.fuelnorms', descKey: 'dict.sec.fuelnorms.d', icon: P.car, cls: 'ic-purple' },
  { href: '/dictionaries/coefficients', labelKey: 'dict.sec.coefficients', descKey: 'dict.sec.coefficients.d', icon: P.chart, cls: 'ic-amber' },
  { href: '/dictionaries/tariffs', labelKey: 'dict.sec.tariffs', descKey: 'dict.sec.tariffs.d', icon: P.doc, cls: 'ic-green' },
  // Справочники расчётного ядра (LegacyReferenceController, /api/v1/legacy-ref/*) — единые для
  // платформы, правит только SYSTEM_ADMIN (см. DictionariesView.NATIONAL_TABS).
  { href: '/dictionaries/brands', labelKey: 'dict.sec.brands', descKey: 'dict.sec.brands.d', icon: P.car, cls: 'ic-blue' },
  { href: '/dictionaries/winter-coefs', labelKey: 'dict.sec.wintercoefs', descKey: 'dict.sec.wintercoefs.d', icon: P.chart, cls: 'ic-cyan' },
  { href: '/dictionaries/mountain-coefs', labelKey: 'dict.sec.mountaincoefs', descKey: 'dict.sec.mountaincoefs.d', icon: P.globe, cls: 'ic-green' },
  { href: '/dictionaries/city-coefs', labelKey: 'dict.sec.citycoefs', descKey: 'dict.sec.citycoefs.d', icon: P.building, cls: 'ic-amber' },
  { href: '/dictionaries/used-coefs', labelKey: 'dict.sec.usedcoefs', descKey: 'dict.sec.usedcoefs.d', icon: P.wrench, cls: 'ic-red' },
  { href: '/dictionaries/drive-classes', labelKey: 'dict.sec.driveclasses', descKey: 'dict.sec.driveclasses.d', icon: P.users, cls: 'ic-purple' },
  { href: '/dictionaries/directions', labelKey: 'dict.sec.directions', descKey: 'dict.sec.directions.d', icon: P.route, cls: 'ic-blue' },
  { href: '/dictionaries/route-tariffs', labelKey: 'dict.sec.routetariffs', descKey: 'dict.sec.routetariffs.d', icon: P.doc, cls: 'ic-cyan' },
  // Справочник внешних (зарубежных) городов (ExternalCityController, /api/v1/external-cities) —
  // источник подсказок для города в международных ПЛ/СМР; правит только SYSTEM_ADMIN.
  { href: '/dictionaries/external-cities', labelKey: 'dict.sec.extcities', descKey: 'dict.sec.extcities.d', icon: P.globe, cls: 'ic-purple' },
  // Справочник типов маршрутов (RouteTypeController, /api/v1/route-types) — плоская классификация
  // маршрута по дальности сообщения; единый для платформы, правит только SYSTEM_ADMIN.
  { href: '/dictionaries/route-types', labelKey: 'dict.sec.routetypes', descKey: 'dict.sec.routetypes.d', icon: P.route, cls: 'ic-blue' },
];

/** Каркас справочников: заголовок + карточки-ссылки. У каждого справочника свой адрес. */
export default function DictionariesLayout({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.dictionaries')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('dict.lead')}</div>
        </div>
      </div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(5, 1fr)' }}>
        {SECTIONS.map(s => {
          const sel = path.startsWith(s.href);
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
                <span className={`k-ic ${s.cls}`}><Icon d={s.icon} cls="" /></span>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 700, fontSize: 13.5, color: sel ? 'var(--blue-700)' : 'var(--ink)' }}>{t(s.labelKey)}</div>
                  <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{t(s.descKey)}</div>
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
