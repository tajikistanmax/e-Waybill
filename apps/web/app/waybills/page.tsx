'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS, TYPE_LABELS } from '@/lib/api';
import { Icon, P } from '../icons';
import { useT } from '@/lib/i18n';

const PER_PAGE = 10;

export default function WaybillsPage() {
  const [items, setItems] = useState<Waybill[]>([]);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [type, setType] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const router = useRouter();
  const { t, tType, tStatus } = useT();

  useEffect(() => { wb.list().then(setItems).catch(e => setError(e.message)); }, []);

  const filtered = useMemo(() => items.filter(w => {
    if (status && w.status !== status) return false;
    if (type && w.waybillType !== type) return false;
    if (q) {
      const s = q.toLowerCase();
      const hay = `${w.number ?? ''} ${w.vehicleRegNumber} ${String(w.driverSnapshot?.fullName ?? w.driverRma)} ${String(w.organizationSnapshot?.name ?? '')}`.toLowerCase();
      if (!hay.includes(s)) return false;
    }
    return true;
  }), [items, status, type, q]);

  const pages = Math.max(1, Math.ceil(filtered.length / PER_PAGE));
  const view = filtered.slice((page - 1) * PER_PAGE, page * PER_PAGE);
  const reset = () => { setStatus(''); setType(''); setQ(''); setPage(1); };
  const fmt = (d: string | null) => d ? new Date(d).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

  return (
    <>
      <div className="toolbar">
        <h1>{t('nav.waybill.registry')}</h1>
        <span className="spacer" />
        <Link className="btn" href="/waybills/new"><Icon d={P.doc} cls="" style={{ width: 16, height: 16 }} /> {t('nav.waybill.new')}</Link>
        <button className="btn secondary" onClick={() => window.print()}><Icon d={P.mail} cls="" style={{ width: 16, height: 16 }} /> {t('wb.print')}</button>
      </div>

      {error && <div className="error">{error}</div>}

      {/* Фильтры */}
      <div className="card">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1.4fr auto', gap: 14, alignItems: 'end' }}>
          <div>
            <label>{t('col.status')}</label>
            <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}>
              <option value="">{t('wb.f.allstatuses')}</option>
              {Object.entries(STATUS_LABELS).map(([v]) => <option key={v} value={v}>{tStatus(v)}</option>)}
            </select>
          </div>
          <div>
            <label>{t('col.wbtype')}</label>
            <select value={type} onChange={e => { setType(e.target.value); setPage(1); }}>
              <option value="">{t('wb.f.alltypes')}</option>
              {Object.entries(TYPE_LABELS).map(([v]) => <option key={v} value={v}>{tType(v)}</option>)}
            </select>
          </div>
          <div>
            <label>{t('wb.f.search')}</label>
            <input value={q} onChange={e => { setQ(e.target.value); setPage(1); }} placeholder={t('wb.search.ph')} />
          </div>
          <button className="btn secondary" onClick={reset}>{t('wb.resetfilters')}</button>
        </div>
      </div>

      {/* Таблица */}
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <table>
          <thead>
            <tr>
              <th>{t('col.wbnum')}</th><th>{t('col.type')}</th><th>{t('col.company')}</th><th>{t('col.transport')}</th><th>{t('col.driver')}</th>
              <th>{t('wb.col.start')}</th><th>{t('wb.col.med')}</th><th>{t('wb.col.tech')}</th><th>{t('col.status')}</th><th></th>
            </tr>
          </thead>
          <tbody>
            {view.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? t('viol.draft')}</span></td>
                  <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                  <td>{String(w.vehicleSnapshot?.brand ?? '')} {w.vehicleRegNumber}</td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                  <td>{fmt(w.validFrom)}</td>
                  <td><span className={`badge ${w.medPassed ? 'green' : 'gray'}`}>{w.medPassed ? t('st.passeddone') : '—'}</span></td>
                  <td><span className={`badge ${w.techPassed ? 'green' : 'gray'}`}>{w.techPassed ? t('st.passeddone') : '—'}</span></td>
                  <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                  <td onClick={e => e.stopPropagation()}>
                    <Link href={`/waybills/${w.id}`} className="tb-icon" style={{ width: 32, height: 32, display: 'inline-grid' }} title={t('btn.open')}>
                      <Icon d={P.eye} cls="" style={{ width: 17, height: 17, color: 'var(--muted)' }} />
                    </Link>
                  </td>
                </tr>
              );
            })}
            {view.length === 0 && (
              <tr><td colSpan={10} style={{ textAlign: 'center', color: 'var(--muted)', padding: 34 }}>
                {t('wb.empty')}
              </td></tr>
            )}
          </tbody>
        </table>

        {/* Пагинация */}
        <div style={{ display: 'flex', alignItems: 'center', padding: '14px 16px', borderTop: '1px solid var(--line)', fontSize: 13, color: 'var(--muted)' }}>
          <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{filtered.length}</b></span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
          <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>
      </div>
    </>
  );
}
