'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { md, wb, type MdOps, type WbOps, type OpsRuntime } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { Icon, P } from '../../icons';

function fmtUptime(ms: number): string {
  const m = Math.floor(ms / 60000);
  return m < 60 ? `${m} мин` : m < 60 * 24 ? `${Math.floor(m / 60)} ч ${m % 60} мин` : `${Math.floor(m / 1440)} дн ${Math.floor((m % 1440) / 60)} ч`;
}
const mb = (b: number) => `${Math.round(b / 1048576)} МБ`;

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
  const { roles } = useAuth();
  const isAdmin = roles.includes('SYSTEM_ADMIN');
  const [mdOps, setMdOps] = useState<MdOps | null>(null);
  const [wbOps, setWbOps] = useState<WbOps | null>(null);
  const [opsErr, setOpsErr] = useState('');

  useEffect(() => {
    if (!isAdmin) return;
    md.ops().then(setMdOps).catch(e => setOpsErr((e as Error).message));
    wb.ops().then(setWbOps).catch(e => setOpsErr((e as Error).message));
  }, [isAdmin]);

  const totalWb = wbOps ? wbOps.numbering.reduce((acc, n) => acc + Number(n.total), 0) : null;

  const rtRow = (name: string, rt: OpsRuntime | undefined) => rt && (
    <tr><td>{name}</td>
      <td>{fmtUptime(rt.uptimeMs)}</td>
      <td>{mb(rt.heapUsedBytes)} / {mb(rt.heapMaxBytes)}</td>
      <td>{rt.processors}</td></tr>
  );

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

      {isAdmin && (
        <div className="card" style={{ padding: 16, marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span className="k-ic ic-green"><Icon d={P.chart} cls="" /></span>
            <div style={{ fontWeight: 700, fontSize: 14 }}>{t('setperf.live.h')}</div>
            {totalWb != null && <span className="badge blue" style={{ marginLeft: 'auto' }}>{t('setperf.live.total')}: {totalWb}</span>}
          </div>
          {opsErr && <div className="error" style={{ marginBottom: 10 }}>{opsErr}</div>}
          <table style={{ width: '100%' }}>
            <thead><tr><th>{t('setperf.live.svc')}</th><th>{t('setperf.live.uptime')}</th><th>{t('setperf.live.heap')}</th><th>CPU</th></tr></thead>
            <tbody>
              {rtRow('master-data-service', mdOps?.runtime)}
              {rtRow('waybill-service', wbOps?.runtime)}
            </tbody>
          </table>
        </div>
      )}

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
