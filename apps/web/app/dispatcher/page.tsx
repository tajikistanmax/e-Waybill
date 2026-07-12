'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';
import { ExpiryAlert } from '../ExpiryAlert';

function fmtDateTime(iso: string | null) {
  return iso
    ? new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
    : '—';
}
function isToday(iso: string | null) {
  if (!iso) return false;
  const d = new Date(iso);
  const n = new Date();
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
}

/** Что нужно сделать по путевому листу в данном статусе (подсказка диспетчеру). Значения — ключи i18n. */
const ACTION_HINT: Record<string, string> = {
  DRAFT: 'disp.hint.draft',
  CREATED: 'disp.hint.created',
  MED_REJECTED: 'disp.hint.medrej',
  TECH_REJECTED: 'disp.hint.techrej',
  AWAITING_PAYMENT: 'disp.hint.await',
  PAID: 'disp.hint.paid',
  READY: 'disp.hint.ready',
  RETURNED: 'disp.hint.returned',
  BLOCKED: 'disp.hint.blocked',
};

/**
 * Кабинет диспетчера — оперативный обзор смены: сколько ПЛ в работе, какие требуют
 * действия (осмотры, оплата, выдача, закрытие) и последние оформленные листы.
 * Диспетчер оформляет и ведёт путевые листы своей организации.
 */
export default function DispatcherCabinet() {
  const { t, tType, tStatus } = useT();
  const [items, setItems] = useState<Waybill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const router = useRouter();

  useEffect(() => {
    wb.list().then(setItems).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }, []);

  const stats = useMemo(() => {
    const total = items.length;
    const active = items.filter(w => ['ISSUED', 'ACTIVE', 'RETURNED'].includes(w.status)).length;
    const pending = items.filter(w => ['DRAFT', 'CREATED', 'MED_REJECTED', 'TECH_REJECTED', 'AWAITING_PAYMENT', 'PAID', 'READY'].includes(w.status)).length;
    const today = items.filter(w => isToday(w.createdAt)).length;
    return { total, active, pending, today };
  }, [items]);

  // Требуют внимания диспетчера — новые сверху.
  const attention = useMemo(
    () => items
      .filter(w => ACTION_HINT[w.status])
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [items],
  );

  const recent = useMemo(
    () => [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 8),
    [items],
  );

  const kpis = [
    { label: t('disp.kpi.total'), value: stats.total, icon: P.doc, cls: 'ic-blue' },
    { label: t('disp.kpi.active'), value: stats.active, icon: P.car, cls: 'ic-cyan' },
    { label: t('disp.kpi.pending'), value: stats.pending, icon: P.alert, cls: 'ic-amber' },
    { label: t('kpi.today'), value: stats.today, icon: P.check, cls: 'ic-green' },
  ];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.dispatcher')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('disp.lead')}</div>
        </div>
        <Link href="/waybills/new" className="btn" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.doc} cls="" /> {t('nav.waybill.new')}
        </Link>
      </div>

      {error && <div className="error">{error}</div>}

      <div style={{ marginBottom: 16 }}><ExpiryAlert days={30} /></div>

      {/* KPI */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        {kpis.map(k => (
          <div className="kpi" key={k.label}>
            <div className="k-top"><span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span></div>
            <div className="k-label">{k.label}</div>
            <div className="k-value">{loading ? '—' : k.value.toLocaleString('ru-RU')}</div>
          </div>
        ))}
      </div>

      {/* Требуют внимания */}
      <div className="card">
        <div className="card-h">
          <h2>{t('disp.attention.h')}</h2>
          <Link className="link" href="/waybills">{t('disp.allregistry')}</Link>
        </div>
        <table>
          <thead>
            <tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.transport')}</th><th>{t('col.driver')}</th><th>{t('col.todo')}</th><th>{t('col.status')}</th></tr>
          </thead>
          <tbody>
            {attention.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                  <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{w.vehicleRegNumber || '—'}</td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma ?? '—')}</td>
                  <td style={{ color: 'var(--ink-soft)' }}>{t(ACTION_HINT[w.status])}</td>
                  <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                </tr>
              );
            })}
            {attention.length === 0 && !loading && (
              <tr>
                <td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>
                  {t('disp.attention.empty')}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Последние путевые листы */}
      <div className="card">
        <div className="card-h">
          <h2>{t('dash.recent')}</h2>
          <Link className="link" href="/waybills">{t('dash.all')} →</Link>
        </div>
        <table>
          <thead>
            <tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.transport')}</th><th>{t('col.created')}</th><th>{t('col.status')}</th></tr>
          </thead>
          <tbody>
            {recent.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                  <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{w.vehicleRegNumber || '—'}</td>
                  <td>{fmtDateTime(w.createdAt)}</td>
                  <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                </tr>
              );
            })}
            {recent.length === 0 && !loading && (
              <tr>
                <td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>{t('disp.empty.recent')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
