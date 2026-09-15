'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { md, type AuditEntry } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

const ACTION_BADGE: Record<string, string> = {
  CREATE: 'green', UPDATE: 'blue', DELETE: 'red', ACCESS: 'teal',
  LOGIN: 'green', LOGIN_ERROR: 'red', LOGOUT: 'gray', LOGOUT_ERROR: 'amber',
};

function isToday(iso: string) {
  const d = new Date(iso), n = new Date();
  return d.toDateString() === n.toDateString();
}

export default function AuditSettingsPage() {
  const { t } = useT();
  const [rows, setRows] = useState<AuditEntry[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState('');
  const [action, setAction] = useState('');
  const [module_, setModule] = useState('');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');

  const reload = useCallback(async () => {
    setLoading(true);
    try { setRows(await md.audit(undefined, 500)); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const modules = useMemo(() => Array.from(new Set(rows.map(r => r.entityType))).sort(), [rows]);

  const day = (iso: string) => iso.slice(0, 10);
  const filtered = useMemo(() => rows.filter(r => {
    if (action && r.action !== action) return false;
    if (module_ && r.entityType !== module_) return false;
    if (dateFrom && day(r.occurredAt) < dateFrom) return false;
    if (dateTo && day(r.occurredAt) > dateTo) return false;
    if (q) {
      const s = q.toLowerCase();
      const hay = `${r.actor ?? ''} ${r.entityType} ${r.entityKey ?? ''} ${r.oldValue ?? ''} ${r.newValue ?? ''}`.toLowerCase();
      if (!hay.includes(s)) return false;
    }
    return true;
  }), [rows, action, module_, dateFrom, dateTo, q]);

  const kpis = useMemo(() => ({
    total: rows.length,
    today: rows.filter(r => isToday(r.occurredAt)).length,
    create: rows.filter(r => r.action === 'CREATE').length,
    update: rows.filter(r => r.action === 'UPDATE').length,
    delete: rows.filter(r => r.action === 'DELETE').length,
    auth: rows.filter(r => r.entityType === 'AUTH').length,
  }), [rows]);

  const resetFilters = () => { setQ(''); setAction(''); setModule(''); setDateFrom(''); setDateTo(''); };

  const fmt = (iso: string) => {
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleString('ru-RU', { dateStyle: 'short', timeStyle: 'short' });
  };
  const entityName = (type: string) => {
    const k = `aud.ent.${type}`;
    const tr = t(k);
    return tr === k ? type : tr;
  };
  // Краткая метка устройства из User-Agent: браузер + ОС (для читаемости журнала).
  const deviceLabel = (ua: string | null): string => {
    if (!ua) return '';
    const browser = /Edg\//.test(ua) ? 'Edge'
      : /OPR\/|Opera/.test(ua) ? 'Opera'
      : /Firefox\//.test(ua) ? 'Firefox'
      : /Chrome\//.test(ua) ? 'Chrome'
      : /Safari\//.test(ua) ? 'Safari' : '';
    const os = /Windows/.test(ua) ? 'Windows'
      : /Android/.test(ua) ? 'Android'
      : /iPhone|iPad|iOS/.test(ua) ? 'iOS'
      : /Mac OS X|Macintosh/.test(ua) ? 'macOS'
      : /Linux/.test(ua) ? 'Linux' : '';
    return [browser, os].filter(Boolean).join(' · ');
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

      {/* Сводка — заметно ли что-то необычное с одного взгляда, до чтения таблицы */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(6, 1fr)' }}>
        <div className="kpi">
          <div className="k-top"><span className="k-ic ic-blue"><Icon d={P.chart} cls="" /></span></div>
          <div className="k-label">{t('aud.kpi.total')}</div>
          <div className="k-value">{kpis.total}</div>
        </div>
        <div className="kpi">
          <div className="k-top"><span className="k-ic ic-cyan"><Icon d={P.doc} cls="" /></span></div>
          <div className="k-label">{t('aud.kpi.today')}</div>
          <div className="k-value">{kpis.today}</div>
        </div>
        <div className="kpi">
          <div className="k-top"><span className="k-ic ic-green"><Icon d={P.check} cls="" /></span></div>
          <div className="k-label">{t('aud.kpi.create')}</div>
          <div className="k-value">{kpis.create}</div>
        </div>
        <div className="kpi">
          <div className="k-top"><span className="k-ic ic-blue"><Icon d={P.route} cls="" /></span></div>
          <div className="k-label">{t('aud.kpi.update')}</div>
          <div className="k-value">{kpis.update}</div>
        </div>
        <div className="kpi">
          <div className="k-top"><span className="k-ic ic-red"><Icon d={P.trash} cls="" /></span></div>
          <div className="k-label">{t('aud.kpi.delete')}</div>
          <div className="k-value">{kpis.delete}</div>
        </div>
        <div className="kpi">
          <div className="k-top"><span className="k-ic ic-purple"><Icon d={P.login} cls="" /></span></div>
          <div className="k-label">{t('aud.kpi.auth')}</div>
          <div className="k-value">{kpis.auth}</div>
        </div>
      </div>

      {/* Фильтры — модуль (тип объекта), действие, диапазон дат, свободный поиск */}
      <div className="card">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14, alignItems: 'end' }}>
          <div>
            <label>{t('aud.col.entity')}</label>
            <select value={module_} onChange={e => setModule(e.target.value)}>
              <option value="">{t('aud.f.module')}</option>
              {modules.map(m => <option key={m} value={m}>{entityName(m)}</option>)}
            </select>
          </div>
          <div>
            <label>{t('aud.col.action')}</label>
            <select value={action} onChange={e => setAction(e.target.value)}>
              <option value="">{t('aud.f.action')}</option>
              {Object.keys(ACTION_BADGE).map(a => <option key={a} value={a}>{t(`aud.act.${a}`)}</option>)}
            </select>
          </div>
          <div>
            <label>{t('aud.f.from')}</label>
            <input type="date" value={dateFrom} onChange={e => setDateFrom(e.target.value)} />
          </div>
          <div>
            <label>{t('aud.f.to')}</label>
            <input type="date" value={dateTo} onChange={e => setDateTo(e.target.value)} />
          </div>
          <div style={{ gridColumn: '1 / -1', display: 'flex', gap: 10, alignItems: 'end' }}>
            <div style={{ flex: 1 }}>
              <label>{t('aud.f.search')}</label>
              <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('aud.f.search.ph')} />
            </div>
            <button className="btn secondary" onClick={resetFilters}>{t('aud.f.reset')}</button>
          </div>
        </div>
      </div>

      <div className="card">
        <div style={{ fontSize: 12.5, color: 'var(--muted)', marginBottom: 10 }}>
          {t('aud.shown')} <b style={{ color: 'var(--ink)' }}>{filtered.length}</b> / {rows.length}
        </div>
        <table>
          <thead>
            <tr>
              <th style={{ width: 150 }}>{t('aud.col.time')}</th>
              <th style={{ width: 130 }}>{t('aud.col.actor')}</th>
              <th style={{ width: 120 }}>{t('aud.col.action')}</th>
              <th>{t('aud.col.entity')}</th>
              <th>{t('aud.col.change')}</th>
              <th style={{ width: 160 }}>{t('aud.col.source')}</th>
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 && !loading && (
              <tr><td colSpan={6} style={{ color: 'var(--muted)' }}>{t('aud.empty')}</td></tr>
            )}
            {filtered.map(r => (
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
                <td style={{ fontSize: 12 }}>
                  {r.clientIp || r.userAgent ? (
                    <>
                      <div style={{ fontFamily: 'var(--mono)' }}>{r.clientIp ?? '—'}</div>
                      {r.userAgent && (
                        <div style={{ color: 'var(--muted)' }} title={r.userAgent}>{deviceLabel(r.userAgent) || '—'}</div>
                      )}
                    </>
                  ) : <span style={{ color: 'var(--muted)' }}>—</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
