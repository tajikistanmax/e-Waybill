'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** Базовые справочные сведения о платформе (параметр → значение). */
const PARAMS: { key: string; icon: keyof typeof P; ic: string }[] = [
  { key: 'platform', icon: 'building', ic: 'ic-blue' },
  { key: 'customer', icon: 'shield', ic: 'ic-cyan' },
  { key: 'stage', icon: 'doc', ic: 'ic-purple' },
  { key: 'lang', icon: 'globe', ic: 'ic-green' },
  { key: 'legal', icon: 'shield', ic: 'ic-amber' },
  { key: 'host', icon: 'settings', ic: 'ic-red' },
];

export default function GeneralSettingsPage() {
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.general')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setgen.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setgen.note')}</div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {PARAMS.map(p => (
          <div key={p.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
              <span className={`k-ic ${p.ic}`}><Icon d={P[p.icon]} cls="" /></span>
              <div style={{ fontWeight: 700, fontSize: 14 }}>{t(`setgen.${p.key}.t`)}</div>
            </div>
            <div style={{ fontSize: 15, fontWeight: 600, lineHeight: 1.45, color: 'var(--blue-700)' }}>
              {t(`setgen.${p.key}.d`)}
            </div>
            {p.key === 'platform' && (
              <div style={{ marginTop: 6, fontSize: 13, color: 'var(--muted)' }}>{t('setgen.subsystem.d')}</div>
            )}
          </div>
        ))}
      </div>
    </>
  );
}
