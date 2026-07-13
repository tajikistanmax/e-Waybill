'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS, TYPE_LABELS, type WaybillRequest } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';
import { ExpiryAlert } from '../ExpiryAlert';

/** Статус заявки на путевой лист → ключ i18n + цвет бейджа. */
const REQ_STATUS: Record<string, { k: string; color: string }> = {
  PENDING: { k: 'req.st.pending', color: 'amber' },
  APPROVED: { k: 'req.st.approved', color: 'green' },
  REJECTED: { k: 'req.st.rejected', color: 'red' },
  CANCELLED: { k: 'req.st.cancelled', color: 'gray' },
};

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
  // Заявки на путевые листы от водителей: очередь + панель проверки/одобрения/отклонения.
  const [reqs, setReqs] = useState<WaybillRequest[]>([]);
  const [sel, setSel] = useState<WaybillRequest | null>(null);
  const [rev, setRev] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [reqBusy, setReqBusy] = useState(false);
  const [reqErr, setReqErr] = useState('');
  const [reqMsg, setReqMsg] = useState('');

  useEffect(() => {
    wb.list().then(setItems).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }, []);

  function loadReqs() { wb.requests.list().then(setReqs).catch(() => {}); }
  useEffect(() => { loadReqs(); }, []);
  const pendingReqs = useMemo(() => reqs.filter(r => r.status === 'PENDING'), [reqs]);

  function openReview(r: WaybillRequest) {
    setSel(r); setReason(''); setReqErr(''); setReqMsg('');
    setRev({
      vehicleRegNumber: r.vehicleRegNumber, waybillType: r.waybillType,
      requestedFrom: r.requestedFrom ?? '', odometer: r.odometer != null ? String(r.odometer) : '',
      route: r.route ?? '', shipmentKind: 'PIECEWORK', serviceKind: 'TAXI', adrClass: '',
    });
  }
  /** Мин. тип-специфичные данные (диспетчер дополняет то, что требует тип ПЛ при создании). */
  function typeDataFor(type: string): Record<string, unknown> | undefined {
    if (type === 'WB_TRUCK') return { shipmentKind: rev.shipmentKind || 'PIECEWORK' };
    if (type === 'WB_CAR' || type === 'WB_TAXI') return { serviceKind: rev.serviceKind || 'TAXI' };
    if (type === 'WB_DANGEROUS') return { adrClass: rev.adrClass };
    return undefined;
  }
  async function doApprove() {
    if (!sel) return;
    setReqBusy(true); setReqErr(''); setReqMsg('');
    try {
      await wb.requests.edit(sel.id, {
        waybillType: rev.waybillType, vehicleRegNumber: rev.vehicleRegNumber.trim(),
        requestedFrom: rev.requestedFrom || null, odometer: rev.odometer ? Number(rev.odometer) : null,
        route: rev.route || null,
      });
      await wb.requests.approve(sel.id, { typeData: typeDataFor(rev.waybillType) });
      setReqMsg(t('dreq.approved')); setSel(null); loadReqs();
      wb.list().then(setItems).catch(() => {});
    } catch (err) { setReqErr((err as Error).message); }
    finally { setReqBusy(false); }
  }
  async function doReject() {
    if (!sel || !reason.trim()) return;
    setReqBusy(true); setReqErr('');
    try {
      await wb.requests.reject(sel.id, reason.trim());
      setReqMsg(t('dreq.rejected')); setSel(null); loadReqs();
    } catch (err) { setReqErr((err as Error).message); }
    finally { setReqBusy(false); }
  }

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

      {/* Заявки на путевые листы от водителей */}
      <div className="card">
        <div className="card-h">
          <h2>{t('dreq.h')}</h2>
          {pendingReqs.length > 0 && <span className="badge amber" style={{ marginLeft: 'auto' }}>{pendingReqs.length}</span>}
        </div>
        {reqErr && <div className="error" style={{ marginBottom: 10 }}>{reqErr}</div>}
        {reqMsg && <div className="success" style={{ marginBottom: 10 }}>{reqMsg}</div>}
        {pendingReqs.length === 0 ? (
          <p style={{ color: 'var(--muted)', fontSize: 13, margin: 0 }}>{t('dreq.empty')}</p>
        ) : (
          <table>
            <thead><tr><th>{t('col.driver')}</th><th>{t('col.transport')}</th><th>{t('col.type')}</th><th>{t('drvreq.f.from')}</th><th>{t('col.odometer')}</th><th></th></tr></thead>
            <tbody>
              {pendingReqs.map(r => (
                <tr key={r.id}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{r.driverName ?? r.driverRma}</td>
                  <td><span className="number">{r.vehicleRegNumber}</span></td>
                  <td>{tType(r.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{r.requestedFrom ?? '—'}</td>
                  <td>{r.odometer != null ? `${r.odometer.toLocaleString('ru-RU')} км` : '—'}</td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn" style={{ padding: '4px 12px', fontSize: 12 }} onClick={() => openReview(r)}>{t('dreq.review')}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {sel && (
          <div className="card" style={{ borderColor: 'var(--blue-500)', marginTop: 14 }}>
            <h2 style={{ marginTop: 0 }}>{t('dreq.review.h')}: {sel.driverName ?? sel.driverRma}</h2>
            <p className="hint">{t('dreq.review.note')}</p>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 12 }}>
              <div>
                <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.plate')}</label>
                <input value={rev.vehicleRegNumber} onChange={e => setRev({ ...rev, vehicleRegNumber: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
              </div>
              <div>
                <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.type')}</label>
                <select value={rev.waybillType} onChange={e => setRev({ ...rev, waybillType: e.target.value })} style={{ width: '100%', marginTop: 4 }}>
                  {Object.keys(TYPE_LABELS).map(v => <option key={v} value={v}>{tType(v)}</option>)}
                </select>
              </div>
              <div>
                <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.from')}</label>
                <input type="date" value={rev.requestedFrom} onChange={e => setRev({ ...rev, requestedFrom: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
              </div>
              <div>
                <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.odometer')}</label>
                <input type="number" min={0} value={rev.odometer} onChange={e => setRev({ ...rev, odometer: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
              </div>
              <div style={{ gridColumn: '1 / -1' }}>
                <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.route')}</label>
                <input value={rev.route} onChange={e => setRev({ ...rev, route: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
              </div>
              {rev.waybillType === 'WB_TRUCK' && (
                <div>
                  <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('wb.f.shipmentkind')}</label>
                  <select value={rev.shipmentKind} onChange={e => setRev({ ...rev, shipmentKind: e.target.value })} style={{ width: '100%', marginTop: 4 }}>
                    <option value="PIECEWORK">{t('wb.ship.piecework')}</option>
                    <option value="HOURLY">{t('wb.ship.hourly')}</option>
                  </select>
                </div>
              )}
              {(rev.waybillType === 'WB_CAR' || rev.waybillType === 'WB_TAXI') && (
                <div>
                  <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('wb.f.servicekind')}</label>
                  <select value={rev.serviceKind} onChange={e => setRev({ ...rev, serviceKind: e.target.value })} style={{ width: '100%', marginTop: 4 }}>
                    <option value="TAXI">{t('wb.svc.taxi')}</option>
                    <option value="ROUTE">{t('wb.svc.route')}</option>
                    <option value="HOURLY">{t('wb.svc.hourly')}</option>
                  </select>
                </div>
              )}
              {rev.waybillType === 'WB_DANGEROUS' && (
                <div>
                  <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('wb.f.adrclass')}</label>
                  <input value={rev.adrClass} onChange={e => setRev({ ...rev, adrClass: e.target.value })} placeholder="1–9" style={{ width: '100%', marginTop: 4 }} />
                </div>
              )}
            </div>
            <div style={{ display: 'flex', gap: 10, marginTop: 14, flexWrap: 'wrap', alignItems: 'center' }}>
              <button className="btn primary" disabled={reqBusy || !rev.vehicleRegNumber.trim()} onClick={doApprove}>{reqBusy ? '…' : t('dreq.approve')}</button>
              <input placeholder={t('dreq.reason')} value={reason} onChange={e => setReason(e.target.value)} style={{ flex: 1, minWidth: 200 }} />
              <button className="btn danger" disabled={reqBusy || !reason.trim()} onClick={doReject}>{t('dreq.reject')}</button>
              <button className="btn secondary" onClick={() => setSel(null)}>{t('fleet.cancel')}</button>
            </div>
          </div>
        )}
      </div>

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
