'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';
import { visibleNav, roleHome, type NavKey } from '@/lib/roles';

/** Все роли платформы (Keycloak realm epd). */
const ROLES = ['SYSTEM_ADMIN', 'COMPANY_ADMIN', 'DISPATCHER', 'DOCTOR', 'MECHANIC', 'DRIVER', 'INSPECTOR', 'ACCOUNTANT', 'API_INTEGRATOR'];

/** Порядок разделов для отображения доступа. */
const SECTIONS: NavKey[] = ['dashboard', 'dispatcher', 'waybills', 'med', 'tech', 'driver', 'inspector', 'company', 'registry', 'violations', 'reports', 'dictionaries', 'settings'];

const NAV_KEY: Record<NavKey, string> = {
  dashboard: 'nav.dashboard', dispatcher: 'nav.dispatcher', waybills: 'nav.waybills', med: 'nav.med',
  tech: 'nav.tech', driver: 'nav.driver', inspector: 'nav.inspector', company: 'nav.company',
  registry: 'nav.registry', violations: 'nav.violations', reports: 'nav.reports',
  dictionaries: 'nav.dictionaries', settings: 'nav.settings',
};

export default function RolesSettingsPage() {
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.roles')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setroles.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setroles.note')}</div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: 14 }}>
        {ROLES.map(role => {
          const nav = visibleNav([role]);
          const home = roleHome([role]);
          const homeKey = SECTIONS.find(s => `/${s}` === home) ?? null;
          return (
            <div className="card" key={role} style={{ padding: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                <span className="k-ic ic-blue"><Icon d={P.user} cls="" /></span>
                <div style={{ fontWeight: 700, fontSize: 14, flex: 1 }}>{t(`role.${role}`)}</div>
                <span className="badge blue" style={{ fontFamily: 'var(--mono)', fontSize: 10.5 }}>{role}</span>
              </div>
              <div style={{ fontSize: 12.5, color: 'var(--muted)', marginBottom: 10 }}>
                {t('setroles.home')}: <b style={{ color: 'var(--ink)' }}>{homeKey ? t(NAV_KEY[homeKey]) : home}</b>
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {SECTIONS.filter(s => nav.has(s)).map(s => (
                  <span key={s} className="badge green" style={{ fontSize: 11 }}>{t(NAV_KEY[s])}</span>
                ))}
                {nav.size === 0 && <span style={{ fontSize: 12, color: 'var(--muted)' }}>—</span>}
              </div>
            </div>
          );
        })}
      </div>
    </>
  );
}
