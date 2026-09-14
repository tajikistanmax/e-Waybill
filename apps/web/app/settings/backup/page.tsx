'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { md, type MdOps } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { Icon, P } from '../../icons';

const fmtSize = (b: number) =>
  b >= 1073741824 ? `${(b / 1073741824).toFixed(1)} ГБ` : `${Math.max(1, Math.round(b / 1048576))} МБ`;

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
  const { roles } = useAuth();
  const isAdmin = roles.includes('SYSTEM_ADMIN');
  // Реальные размеры баз данных (ops/overview master-data) — что именно подлежит копированию.
  const [dbs, setDbs] = useState<MdOps['databases'] | null>(null);
  const [opsErr, setOpsErr] = useState('');

  useEffect(() => {
    if (!isAdmin) return;
    md.ops().then(o => setDbs(o.databases)).catch(e => setOpsErr((e as Error).message));
  }, [isAdmin]);

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

      {isAdmin && (
        <div className="card" style={{ padding: 16, marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span className="k-ic ic-blue"><Icon d={P.book} cls="" /></span>
            <div style={{ fontWeight: 700, fontSize: 14 }}>{t('setbak.live.h')}</div>
          </div>
          {opsErr && <div className="error" style={{ marginBottom: 10 }}>{opsErr}</div>}
          {dbs && (
            <table style={{ width: '100%' }}>
              <thead><tr><th>{t('setbak.live.db')}</th><th style={{ textAlign: 'right' }}>{t('setbak.live.size')}</th></tr></thead>
              <tbody>
                {dbs.map(d => (
                  <tr key={d.name}>
                    <td style={{ fontFamily: 'var(--mono)' }}>{d.name}</td>
                    <td style={{ textAlign: 'right' }}>{fmtSize(Number(d.size_bytes))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

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
