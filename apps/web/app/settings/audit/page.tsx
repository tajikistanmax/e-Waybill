'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { md, type AuditEntry } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

const ACTION_BADGE: Record<string, string> = { CREATE: 'green', UPDATE: 'blue', DELETE: 'red' };

export default function AuditSettingsPage() {
  const { t } = useT();
  const [rows, setRows] = useState<AuditEntry[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    setLoading(true);
    try { setRows(await md.audit(undefined, 200)); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const fmt = (iso: string) => {
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleString('ru-RU', { dateStyle: 'short', timeStyle: 'short' });
  };
  const entityName = (type: string) => {
    const k = `aud.ent.${type}`;
    const tr = t(k);
    return tr === k ? type : tr;
  };

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.audit')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('aud.lead')}</div>
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 10 }}>
          <button className="btn secondary" onClick={reload} disabled={loading}>
            <Icon d={P.route} cls="" style={{ width: 15, height: 15 }} /> {t('aud.refresh')}
          </button>
          <Link href="/settings" className="btn secondary" style={{ textDecoration: 'none' }}>
            <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
          </Link>
        </div>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('aud.note')}</div>
      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}

      <div className="card">
        <table>
          <thead>
            <tr>
              <th style={{ width: 150 }}>{t('aud.col.time')}</th>
              <th style={{ width: 130 }}>{t('aud.col.actor')}</th>
              <th style={{ width: 120 }}>{t('aud.col.action')}</th>
              <th>{t('aud.col.entity')}</th>
              <th>{t('aud.col.change')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && !loading && (
              <tr><td colSpan={5} style={{ color: 'var(--muted)' }}>{t('aud.empty')}</td></tr>
            )}
            {rows.map(r => (
              <tr key={r.id}>
                <td style={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>{fmt(r.occurredAt)}</td>
                <td>{r.actor ?? '—'}</td>
                <td><span className={`badge ${ACTION_BADGE[r.action] ?? 'gray'}`}>{t(`aud.act.${r.action}`)}</span></td>
                <td>
                  <span className="badge gray" style={{ marginRight: 6 }}>{entityName(r.entityType)}</span>
                  <span style={{ fontFamily: 'var(--mono)', fontSize: 12.5 }}>{r.entityKey}</span>
                </td>
                <td style={{ fontSize: 12.5 }}>
                  <span style={{ color: 'var(--muted)', textDecoration: r.oldValue != null ? 'line-through' : 'none' }}>
                    {r.oldValue ?? '∅'}
                  </span>
                  {' → '}
                  <span style={{ fontWeight: 600 }}>{r.newValue ?? '∅'}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
