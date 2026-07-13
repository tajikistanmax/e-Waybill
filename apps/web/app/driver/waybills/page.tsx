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

/**
 * «Мои путевые листы» — чистая страница водителя: полный список ЕГО путевых листов
 * (бэкенд отдаёт только свои для роли DRIVER). Клик — открыть документ.
 */
export default function DriverWaybillsPage() {
  const { t, tType, tStatus } = useT();
  const [items, setItems] = useState<Waybill[]>([]);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const router = useRouter();

  useEffect(() => { wb.list().then(setItems).catch(e => setError(e.message)); }, []);

  const list = useMemo(
    () => [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).filter(w => !status || w.status === status),
    [items, status],
  );

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
        <div style={{ marginBottom: 12, maxWidth: 280 }}>
          <label>{t('col.status')}</label>
          <select value={status} onChange={e => setStatus(e.target.value)}>
            <option value="">{t('wb.f.allstatuses')}</option>
            {Object.keys(STATUS_LABELS).map(v => <option key={v} value={v}>{tStatus(v)}</option>)}
          </select>
        </div>
        <table>
          <thead>
            <tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.route')}</th><th>{t('drv.validity')}</th><th>{t('col.mileage')}</th><th>{t('col.status')}</th></tr>
          </thead>
          <tbody>
            {list.map(w => {
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
      </div>
    </>
  );
}
