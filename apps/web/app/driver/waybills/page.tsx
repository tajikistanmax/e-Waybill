'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

function fmt(iso: string | null) {
  return iso ? new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' }) : '—';
}
/** Пробег рейса = одометр возврата − одометр выезда (если оба заполнены). */
function mileage(w: Waybill): number | null {
  if (w.odometerEntry != null && w.odometerExit != null && w.odometerEntry >= w.odometerExit) {
    return w.odometerEntry - w.odometerExit;
  }
  return null;
}

const PER_PAGE = 10;

/**
 * «Мои путевые листы» — чистая страница водителя: полный список ЕГО путевых листов
 * (бэкенд отдаёт только свои для роли DRIVER). Клик — открыть документ.
 */
export default function DriverWaybillsPage() {
  const { t, tType, tStatus } = useT();
  const [items, setItems] = useState<Waybill[]>([]);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(1);
  const router = useRouter();

  useEffect(() => { wb.list().then(setItems).catch(e => setError(e.message)); }, []);

  // Фильтр по периоду — водителю нужен, чтобы посмотреть «что я отъездил за месяц»
  // (по дате выхода на линию, при её отсутствии — по дате создания листа).
  const list = useMemo(
    () => [...items]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .filter(w => !status || w.status === status)
      .filter(w => {
        const d = (w.validFrom ?? w.createdAt ?? '').slice(0, 10);
        if (from && d && d < from) return false;
        if (to && d && d > to) return false;
        return true;
      }),
    [items, status, from, to],
  );

  // Итог по выбранному периоду — рейсов и суммарный пробег.
  const totals = useMemo(() => ({
    trips: list.length,
    km: list.reduce((sum, w) => sum + (mileage(w) ?? 0), 0),
  }), [list]);

  const pages = Math.max(1, Math.ceil(list.length / PER_PAGE));
  const view = list.slice((page - 1) * PER_PAGE, page * PER_PAGE);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('drvwb.h')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('drvwb.lead')}</div>
        </div>
        <Link href="/driver" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('drvwb.back')}
        </Link>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="card">
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 14 }}>
          <div style={{ minWidth: 200 }}>
            <label>{t('col.status')}</label>
            <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}>
              <option value="">{t('wb.f.allstatuses')}</option>
              {Object.keys(STATUS_LABELS).map(v => <option key={v} value={v}>{tStatus(v)}</option>)}
            </select>
          </div>
          <div>
            <label>{t('insp.period.from')}</label>
            <input type="date" value={from} onChange={e => { setFrom(e.target.value); setPage(1); }} />
          </div>
          <div>
            <label>{t('insp.period.to')}</label>
            <input type="date" value={to} onChange={e => { setTo(e.target.value); setPage(1); }} />
          </div>
          <button className="btn secondary" onClick={() => { setStatus(''); setFrom(''); setTo(''); setPage(1); }}>
            {t('wb.resetfilters')}
          </button>
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--muted)' }}>
            {t('drvwb.totals')}: <b style={{ color: 'var(--ink)' }}>{totals.trips}</b>
            {totals.km > 0 && <> · <b style={{ color: 'var(--ink)' }}>{totals.km.toLocaleString('ru-RU')} км</b></>}
          </span>
        </div>
        <table>
          <thead>
            <tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.route')}</th><th>{t('drv.validity')}</th><th>{t('col.mileage')}</th><th>{t('col.status')}</th></tr>
          </thead>
          <tbody>
            {view.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              const km = mileage(w);
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                  <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{w.route ?? '—'}</td>
                  <td>{fmt(w.validFrom)} → {fmt(w.validTo)}</td>
                  <td>{km != null ? `${km.toLocaleString('ru-RU')} км` : '—'}</td>
                  <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                </tr>
              );
            })}
            {list.length === 0 && (
              <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 28 }}>{t('drv.empty.trips')}</td></tr>
            )}
          </tbody>
        </table>
        {/* Пагинация */}
        <div style={{ display: 'flex', alignItems: 'center', marginTop: 14, fontSize: 12.5, color: 'var(--muted)' }}>
          <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{list.length}</b></span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
          <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>
      </div>
    </>
  );
}
