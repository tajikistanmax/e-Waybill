'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { md, type ExpiryItem } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { downloadCsv } from '@/lib/csv';
import { Icon, P } from '../../icons';

const WINDOWS = [30, 90, 365];

export default function ExpirySettingsPage() {
  const { t } = useT();
  const [days, setDays] = useState(90);
  const [rows, setRows] = useState<ExpiryItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    setLoading(true);
    try { setRows(await md.documentExpiry(days)); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }, [days]);

  useEffect(() => { reload(); }, [reload]);

  const label = (prefix: string, code: string, fallback: string) => {
    const k = `${prefix}.${code}`;
    const tr = t(k);
    return tr === k ? fallback : tr;
  };
  const fmt = (iso: string) => {
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleDateString('ru-RU');
  };

  /** Выгрузка истекающих документов в CSV (открывается в Excel) — для планирования замен. */
  function exportCsv() {
    const head = [t('aud.col.entity'), 'Ключ', t('cls.name.ru'), t('exp.col.doc'), t('exp.col.validto'), t('exp.col.left')];
    const data = rows.map(r => [
      label('aud.ent', r.entityType, r.entityType), r.key, r.name,
      label('exp.doc', r.docType, r.docType), fmt(r.validTo), r.daysLeft,
    ]);
    downloadCsv(`сроки-документов-${days}дн.csv`, [head, ...data]);
  }

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.expiry')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('exp.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('exp.note')}</div>
      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16 }}>
        <span style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('exp.window')}:</span>
        {WINDOWS.map(w => (
          <button key={w} onClick={() => setDays(w)}
            className={`badge ${w === days ? 'blue' : 'gray'}`}
            style={{ cursor: 'pointer', border: 'none', padding: '6px 12px' }}>{w}</button>
        ))}
        <button className="btn secondary" onClick={exportCsv} disabled={rows.length === 0} style={{ marginLeft: 'auto' }}>
          <Icon d={P.chart} cls="" style={{ width: 15, height: 15 }} /> {t('rep.export')}
        </button>
      </div>

      <div className="card">
        <table>
          <thead>
            <tr>
              <th style={{ width: 130 }}>{t('aud.col.entity')}</th>
              <th>{t('cls.name.ru')}</th>
              <th>{t('exp.col.doc')}</th>
              <th style={{ width: 120 }}>{t('exp.col.validto')}</th>
              <th style={{ width: 120 }}>{t('exp.col.left')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && !loading && <tr><td colSpan={5} style={{ color: 'var(--muted)' }}>{t('exp.empty')}</td></tr>}
            {rows.map((r, i) => {
              const overdue = r.daysLeft < 0;
              const soon = r.daysLeft >= 0 && r.daysLeft <= 14;
              return (
                <tr key={`${r.entityType}-${r.key}-${r.docType}-${i}`}>
                  <td>
                    <span className="badge gray" style={{ marginRight: 6 }}>{label('aud.ent', r.entityType, r.entityType)}</span>
                    <span style={{ fontFamily: 'var(--mono)', fontSize: 12 }}>{r.key}</span>
                  </td>
                  <td style={{ fontWeight: 600 }}>{r.name}</td>
                  <td>{label('exp.doc', r.docType, r.docType)}</td>
                  <td style={{ fontVariantNumeric: 'tabular-nums' }}>{fmt(r.validTo)}</td>
                  <td>
                    <span className={`badge ${overdue ? 'red' : soon ? 'amber' : 'green'}`}>
                      {overdue ? t('exp.expired') : `${r.daysLeft} ${t('exp.daysleft')}`}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}
