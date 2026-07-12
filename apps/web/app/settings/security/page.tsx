'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';
import { SettingsEditor } from '../SettingsEditor';

/** Домены модели безопасности платформы (справочно, read-only). */
const DOMAINS: { key: string; icon: string; ic: string; on?: boolean }[] = [
  { key: 'auth', icon: P.shield, ic: 'ic-blue' },
  { key: 'rbac', icon: P.users, ic: 'ic-blue' },
  { key: 'tenant', icon: P.building, ic: 'ic-green', on: true },
  { key: 'session', icon: P.eye, ic: 'ic-cyan' },
  { key: 'prod', icon: P.settings, ic: 'ic-amber' },
  { key: 'qr', icon: P.doc, ic: 'ic-green', on: true },
  { key: 'title', icon: P.docActive, ic: 'ic-cyan' },
  { key: 'audit', icon: P.eye, ic: 'ic-amber' },
];

export default function SecuritySettingsPage() {
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.security')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setsec.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setsec.note')}</div>

      <h2 style={{ margin: '4px 0 6px' }}>{t('setsec.params.h')}</h2>
      <div className="page-lead" style={{ marginTop: 0, marginBottom: 14 }}>{t('setsec.params.lead')}</div>
      <SettingsEditor category="security" />

      <h2 style={{ margin: '28px 0 6px' }}>{t('setsec.domains.h')}</h2>
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {DOMAINS.map(d => (
          <div key={d.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
              <span className={`k-ic ${d.ic}`}><Icon d={d.icon} cls="" /></span>
              <div style={{ fontWeight: 700, fontSize: 14 }}>{t(`setsec.${d.key}.t`)}</div>
              {d.on && <span className="badge green" style={{ marginLeft: 'auto', fontSize: 11 }}>{t('setsec.on')}</span>}
            </div>
            <div style={{ color: 'var(--muted)', fontSize: 13, lineHeight: 1.5 }}>{t(`setsec.${d.key}.d`)}</div>
          </div>
        ))}
      </div>
    </>
  );
}
