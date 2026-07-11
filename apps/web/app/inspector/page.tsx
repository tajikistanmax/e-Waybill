'use client';

import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

function fmtDateTime(iso: string | null) {
  return iso
    ? new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
    : '—';
}

/** Итог проверки путевого листа инспектором — цвет/текст баннера. title/note — ключи i18n. */
function verdict(w: Waybill): { tone: 'ok' | 'warn' | 'stop'; title: string; note: string } {
  if (w.status === 'BLOCKED') return { tone: 'stop', title: 'insp.v.blocked.t', note: 'insp.v.blocked.n' };
  if (w.status === 'EXPIRED') return { tone: 'stop', title: 'insp.v.expired.t', note: 'insp.v.expired.n' };
  if (w.status === 'CANCELLED') return { tone: 'stop', title: 'insp.v.cancelled.t', note: 'insp.v.cancelled.n' };
  if (!w.medPassed || !w.techPassed) return { tone: 'warn', title: 'insp.v.noexam.t', note: 'insp.v.noexam.n' };
  if (['ISSUED', 'ACTIVE', 'RETURNED'].includes(w.status)) return { tone: 'ok', title: 'insp.v.ok.t', note: 'insp.v.ok.n' };
  return { tone: 'warn', title: 'insp.v.offline.t', note: 'insp.v.offline.n' };
}

const TONE_BG: Record<string, string> = { ok: 'var(--green-050)', warn: 'var(--amber-050)', stop: 'var(--red-050)' };
const TONE_FG: Record<string, string> = { ok: 'var(--green)', warn: '#a9700a', stop: 'var(--red)' };

/**
 * Кабинет инспектора — дорожный контроль: проверка путевого листа по госномеру
 * или номеру ПЛ, вердикт о допуске, и перечень проблемных листов (заблокированные,
 * просроченные). Быстрая проверка на дороге — сканированием QR (страница /verify).
 */
export default function InspectorCabinet() {
  const { t, tType, tStatus } = useT();
  const [items, setItems] = useState<Waybill[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [checked, setChecked] = useState<Waybill | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState('');
  const router = useRouter();

  useEffect(() => {
    wb.list().then(setItems).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }, []);

  function runCheck(e: React.FormEvent) {
    e.preventDefault();
    const q = query.trim().toLowerCase();
    setNotFound(false); setChecked(null);
    if (!q) return;
    // Совпадение по номеру ПЛ или госномеру ТС; приоритет — активному/выданному листу.
    const matches = items
      .filter(w => (w.number ?? '').toLowerCase().includes(q) || (w.vehicleRegNumber ?? '').toLowerCase().includes(q))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    const pick = matches.find(w => ['ACTIVE', 'ISSUED', 'RETURNED'].includes(w.status)) ?? matches[0];
    if (pick) setChecked(pick); else setNotFound(true);
  }

  // Проблемные листы — заблокированные и просроченные (новые сверху).
  const problems = useMemo(
    () => items
      .filter(w => ['BLOCKED', 'EXPIRED'].includes(w.status))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [items],
  );

  const stats = useMemo(() => ({
    onLine: items.filter(w => ['ISSUED', 'ACTIVE', 'RETURNED'].includes(w.status)).length,
    blocked: items.filter(w => w.status === 'BLOCKED').length,
    expired: items.filter(w => w.status === 'EXPIRED').length,
  }), [items]);

  const v = checked ? verdict(checked) : null;

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.inspector')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('insp.lead')}</div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {/* Консоль проверки */}
      <div className="card">
        <div className="card-h"><h2>{t('insp.check.h')}</h2></div>
        <form onSubmit={runCheck} style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div style={{ flex: 1, minWidth: 260 }}>
            <label>{t('insp.f.query')}</label>
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder={t('insp.f.query.ph')}
              style={{ width: '100%' }}
            />
          </div>
          <button className="btn" type="submit"><Icon d={P.eye} cls="" /> {t('btn.check')}</button>
        </form>
        <div className="hint" style={{ marginTop: 14, marginBottom: 0 }}>
          {t('insp.hint')}
        </div>

        {/* Результат */}
        {v && checked && (
          <div style={{ marginTop: 18 }}>
            <div
              style={{
                display: 'flex', alignItems: 'center', gap: 12, padding: '14px 18px', borderRadius: 12,
                background: TONE_BG[v.tone], color: TONE_FG[v.tone], fontWeight: 700, fontSize: 16, marginBottom: 16,
              }}
            >
              <Icon d={v.tone === 'ok' ? P.check : v.tone === 'stop' ? P.shield : P.alert} cls="" />
              {t(v.title)}
              <span style={{ fontWeight: 500, fontSize: 13, color: 'var(--ink-soft)', marginLeft: 6 }}>{t(v.note)}</span>
            </div>
            <dl className="kv">
              <dt>{t('insp.wbnum')}</dt><dd><span className="number">{checked.number ?? '— черновик —'}</span></dd>
              <dt>{t('col.type')}</dt><dd>{tType(checked.waybillType)}</dd>
              <dt>{t('col.transport')}</dt><dd>{String(checked.vehicleSnapshot?.brand ?? '')} {checked.vehicleRegNumber}</dd>
              <dt>{t('col.driver')}</dt><dd>{String(checked.driverSnapshot?.fullName ?? checked.driverRma ?? '—')}</dd>
              <dt>{t('drv.validity')}</dt><dd>{fmtDateTime(checked.validFrom)} → {fmtDateTime(checked.validTo)}</dd>
              <dt>{t('insp.medtech')}</dt>
              <dd>
                <span className={`badge ${checked.medPassed ? 'green' : 'gray'}`}>Т2 {checked.medPassed ? '✓' : '…'}</span>{' '}
                <span className={`badge ${checked.techPassed ? 'green' : 'gray'}`}>Т3 {checked.techPassed ? '✓' : '…'}</span>
              </dd>
            </dl>
            <button className="btn secondary" style={{ marginTop: 14 }} onClick={() => router.push(`/waybills/${checked.id}`)}>
              {t('insp.opencard')}
            </button>
          </div>
        )}
        {notFound && (
          <div style={{ marginTop: 16, color: 'var(--muted)', fontSize: 13.5 }}>
            {t('insp.notfound.pre')} «{query}» {t('insp.notfound.post')}
          </div>
        )}
      </div>

      {/* KPI */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
        <div className="kpi"><div className="k-top"><span className="k-ic ic-cyan"><Icon d={P.car} cls="" /></span></div><div className="k-label">{t('kpi.online')}</div><div className="k-value">{loading ? '—' : stats.onLine}</div></div>
        <div className="kpi"><div className="k-top"><span className="k-ic ic-red"><Icon d={P.shield} cls="" /></span></div><div className="k-label">{t('insp.kpi.blocked')}</div><div className="k-value">{loading ? '—' : stats.blocked}</div></div>
        <div className="kpi"><div className="k-top"><span className="k-ic ic-amber"><Icon d={P.alert} cls="" /></span></div><div className="k-label">{t('insp.kpi.expired')}</div><div className="k-value">{loading ? '—' : stats.expired}</div></div>
      </div>

      {/* Проблемные листы */}
      <div className="card">
        <div className="card-h"><h2>{t('insp.problems.h')}</h2></div>
        <table>
          <thead>
            <tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.transport')}</th><th>{t('col.driver')}</th><th>{t('col.created')}</th><th>{t('col.status')}</th></tr>
          </thead>
          <tbody>
            {problems.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? '— черновик —'}</span></td>
                  <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{w.vehicleRegNumber || '—'}</td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma ?? '—')}</td>
                  <td>{fmtDateTime(w.createdAt)}</td>
                  <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                </tr>
              );
            })}
            {problems.length === 0 && !loading && (
              <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>{t('insp.problems.empty')}</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
