'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** Меры производительности и лимитов платформы (справочно). */
const CARDS: { key: string; icon: keyof typeof P; ic: string; status: 'on' | 'planned' }[] = [
  { key: 'scale', icon: 'chart', ic: 'ic-blue', status: 'on' },
  { key: 'async', icon: 'bell', ic: 'ic-cyan', status: 'on' },
  { key: 'timeout', icon: 'settings', ic: 'ic-green', status: 'on' },
  { key: 'metrics', icon: 'eye', ic: 'ic-amber', status: 'on' },
  { key: 'rate', icon: 'shield', ic: 'ic-red', status: 'planned' },
  { key: 'cache', icon: 'book', ic: 'ic-purple', status: 'planned' },
];

export default function PerformanceSettingsPage() {
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.performance')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setperf.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setperf.note')}</div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {CARDS.map(card => (
          <div key={card.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
              <span className={`k-ic ${card.ic}`}><Icon d={P[card.icon]} cls="" /></span>
              <div style={{ fontWeight: 700, fontSize: 14 }}>{t(`setperf.${card.key}.t`)}</div>
              <span className={`badge ${card.status === 'on' ? 'green' : 'gray'}`} style={{ marginLeft: 'auto' }}>
                {t(card.status === 'on' ? 'setperf.on' : 'setperf.planned')}
              </span>
            </div>
            <div style={{ color: 'var(--muted)', fontSize: 13, lineHeight: 1.5 }}>{t(`setperf.${card.key}.d`)}</div>
          </div>
        ))}
      </div>
    </>
  );
}
