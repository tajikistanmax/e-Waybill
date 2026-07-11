'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** Настройки интерфейса приложения (справочно, read-only). */
const CARDS: { key: string; icon: string; ic: string; status: 'on' | 'planned' }[] = [
  { key: 'lang', icon: 'globe', ic: 'ic-blue', status: 'on' },
  { key: 'theme', icon: 'settings', ic: 'ic-cyan', status: 'on' },
  { key: 'density', icon: 'book', ic: 'ic-amber', status: 'planned' },
  { key: 'menu', icon: 'route', ic: 'ic-purple', status: 'planned' },
];

export default function InterfaceSettingsPage() {
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.interface')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setui.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setui.note')}</div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {CARDS.map(c => (
          <div key={c.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
              <span className={`k-ic ${c.ic}`}><Icon d={P[c.icon as keyof typeof P]} cls="" /></span>
              <div style={{ fontWeight: 700, fontSize: 14 }}>{t(`setui.${c.key}.t`)}</div>
              <span className={`badge ${c.status === 'on' ? 'green' : 'gray'}`} style={{ marginLeft: 'auto', fontSize: 11 }}>
                {c.status === 'on' ? t('setui.on') : t('setui.planned')}
              </span>
            </div>
            <div style={{ color: 'var(--muted)', fontSize: 13, lineHeight: 1.5 }}>{t(`setui.${c.key}.d`)}</div>
          </div>
        ))}
      </div>
    </>
  );
}
