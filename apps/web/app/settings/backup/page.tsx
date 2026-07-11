'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** Политика резервного копирования и восстановления (справочно, read-only).
 *  st: 'on' — уже действует, 'planned' — реализация на этапе продовой инфраструктуры. */
const CARDS: { key: string; icon: string; cls: string; st: 'on' | 'planned' }[] = [
  { key: 'scope', icon: P.shield, cls: 'ic-blue', st: 'planned' },
  { key: 'freq', icon: P.settings, cls: 'ic-cyan', st: 'planned' },
  { key: 'enc', icon: P.shield, cls: 'ic-amber', st: 'planned' },
  { key: 'retention', icon: P.doc, cls: 'ic-green', st: 'on' },
  { key: 'restore', icon: P.route, cls: 'ic-purple', st: 'planned' },
];

export default function BackupSettingsPage() {
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.backup')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setbak.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setbak.note')}</div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {CARDS.map(c => (
          <div key={c.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
              <span className={`k-ic ${c.cls}`}><Icon d={c.icon} cls="" /></span>
              <div style={{ fontWeight: 700, fontSize: 14, flex: 1 }}>{t(`setbak.${c.key}.t`)}</div>
            </div>
            <div style={{ fontSize: 12.5, color: 'var(--muted)', minHeight: 34, marginBottom: 10 }}>{t(`setbak.${c.key}.d`)}</div>
            {c.st === 'on'
              ? <span className="badge green">{t('setbak.on')}</span>
              : <span className="badge gray">{t('setbak.planned')}</span>}
          </div>
        ))}
      </div>
    </>
  );
}
