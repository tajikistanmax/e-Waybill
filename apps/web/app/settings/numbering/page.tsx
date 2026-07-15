'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { wb, type WbOps } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { Icon, P } from '../../icons';

/** Компоненты формата номера ПЛ: RR-YY-TT-NNNNNNN-K (справочно). */
const PARTS: { code: string; key: string; ic: string; icon: keyof typeof P }[] = [
  { code: 'RR', key: 'rr', ic: 'ic-blue', icon: 'globe' },
  { code: 'YY', key: 'yy', ic: 'ic-cyan', icon: 'doc' },
  { code: 'TT', key: 'tt', ic: 'ic-purple', icon: 'route' },
  { code: 'NNNNNNN', key: 'nnn', ic: 'ic-green', icon: 'book' },
  { code: 'K', key: 'k', ic: 'ic-amber', icon: 'shield' },
];

export default function NumberingSettingsPage() {
  const { t, tType } = useT();
  const { roles } = useAuth();
  const isAdmin = roles.includes('SYSTEM_ADMIN');
  // Живые счётчики нумерации по типам (ops/overview waybill-service) — только SYSTEM_ADMIN.
  const [counters, setCounters] = useState<WbOps['numbering'] | null>(null);
  const [opsErr, setOpsErr] = useState('');

  useEffect(() => {
    if (!isAdmin) return;
    wb.ops().then(o => setCounters(o.numbering)).catch(e => setOpsErr((e as Error).message));
  }, [isAdmin]);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.numbering')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setnum.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setnum.note')}</div>

      {isAdmin && (
        <div className="card" style={{ padding: 16, marginBottom: 18 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span className="k-ic ic-green"><Icon d={P.chart} cls="" /></span>
            <div style={{ fontWeight: 700, fontSize: 14 }}>{t('setnum.live.h')}</div>
          </div>
          {opsErr && <div className="error" style={{ marginBottom: 10 }}>{opsErr}</div>}
          {counters && counters.length === 0 && <div className="hint" style={{ margin: 0 }}>{t('setnum.live.empty')}</div>}
          {counters && counters.length > 0 && (
            <table style={{ width: '100%' }}>
              <thead><tr><th>{t('col.type')}</th><th>{t('setnum.live.total')}</th><th>{t('setnum.live.numbered')}</th><th>{t('setnum.live.last')}</th></tr></thead>
              <tbody>
                {counters.map(c => (
                  <tr key={c.type}>
                    <td>{tType(c.type)}</td>
                    <td>{c.total}</td>
                    <td>{c.numbered}</td>
                    <td style={{ fontFamily: 'var(--mono)' }}>{c.last_number ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      <div className="card" style={{ padding: 24, marginBottom: 18, textAlign: 'center' }}>
        <div style={{ fontSize: 12.5, color: 'var(--muted)', marginBottom: 12, letterSpacing: '0.04em', textTransform: 'uppercase' }}>{t('setnum.format')}</div>
        <div style={{ fontFamily: 'var(--mono)', fontSize: 34, fontWeight: 700, color: 'var(--blue-700)', letterSpacing: '0.02em' }}>
          RR-YY-TT-NNNNNNN-K
        </div>
      </div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {PARTS.map(part => (
          <div key={part.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
              <span className={`k-ic ${part.ic}`}><Icon d={P[part.icon]} cls="" /></span>
              <span style={{ fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 15, color: 'var(--blue-700)' }}>{part.code}</span>
              <span style={{ fontWeight: 700, fontSize: 14, marginLeft: 4 }}>{t(`setnum.${part.key}.t`)}</span>
            </div>
            <div style={{ fontSize: 12.5, color: 'var(--muted)', lineHeight: 1.5 }}>{t(`setnum.${part.key}.d`)}</div>
          </div>
        ))}

        <div className="card" style={{ padding: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <span className="k-ic ic-red"><Icon d={P.eye} cls="" /></span>
            <span style={{ fontWeight: 700, fontSize: 14 }}>{t('setnum.example.t')}</span>
          </div>
          <div style={{ fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 18, color: 'var(--blue-600)', marginBottom: 10 }}>
            01-26-03-0000042-7
          </div>
          <div style={{ fontSize: 12.5, color: 'var(--muted)', lineHeight: 1.5 }}>{t('setnum.example.d')}</div>
        </div>
      </div>
    </>
  );
}
