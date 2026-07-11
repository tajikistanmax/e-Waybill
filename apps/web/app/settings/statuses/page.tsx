'use client';

import Link from 'next/link';
import { STATUS_LABELS } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** Статусная модель ПЛ, сгруппированная по этапам жизненного цикла (справочно). */
const STAGES: { key: string; statuses: string[] }[] = [
  { key: 'create', statuses: ['DRAFT', 'CREATED'] },
  { key: 'exam', statuses: ['MED_REJECTED', 'TECH_REJECTED'] },
  { key: 'ready', statuses: ['AWAITING_PAYMENT', 'PAID', 'READY'] },
  { key: 'trip', statuses: ['ISSUED', 'ACTIVE', 'RETURNED'] },
  { key: 'done', statuses: ['COMPLETED', 'ARCHIVED'] },
  { key: 'exc', statuses: ['CANCELLED', 'EXPIRED', 'BLOCKED'] },
];

const OPEN = ['CREATED', 'AWAITING_PAYMENT', 'PAID', 'READY', 'ISSUED', 'ACTIVE'];
const TERMINAL = ['COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED'];

export default function StatusesSettingsPage() {
  const { t, tStatus } = useT();

  const nature = (s: string) =>
    OPEN.includes(s) ? { key: 'stat.n.open', cls: 'green' }
    : TERMINAL.includes(s) ? { key: 'stat.n.terminal', cls: 'gray' }
    : { key: 'stat.n.transient', cls: 'amber' };

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.statuses')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('stat.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('stat.note')}</div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {STAGES.map((stage, i) => (
          <div key={stage.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
              <span style={{ width: 24, height: 24, borderRadius: 7, background: 'var(--blue-050)', color: 'var(--blue-700)', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 12 }}>{i + 1}</span>
              <div style={{ fontWeight: 700, fontSize: 14 }}>{t(`stat.stage.${stage.key}`)}</div>
            </div>
            <table>
              <tbody>
                {stage.statuses.map(s => {
                  const color = STATUS_LABELS[s]?.color ?? 'gray';
                  const nat = nature(s);
                  return (
                    <tr key={s}>
                      <td><span className={`badge ${color}`}>{tStatus(s)}</span></td>
                      <td style={{ textAlign: 'right' }}><span className={`badge ${nat.cls}`} style={{ fontSize: 11 }}>{t(nat.key)}</span></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ))}
      </div>
    </>
  );
}
