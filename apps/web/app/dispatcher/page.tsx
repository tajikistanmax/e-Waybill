'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS, TYPE_LABELS, type WaybillRequest, type LivePosition, type NotificationItem } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { canCreateWaybill } from '@/lib/roles';
import { Icon, P } from '../icons';
import { ExpiryAlert } from '../ExpiryAlert';

/** Статус заявки на путевой лист → ключ i18n + цвет бейджа. */
const REQ_STATUS: Record<string, { k: string; color: string }> = {
  PENDING: { k: 'req.st.pending', color: 'amber' },
  APPROVED: { k: 'req.st.approved', color: 'green' },
  REJECTED: { k: 'req.st.rejected', color: 'red' },
  CANCELLED: { k: 'req.st.cancelled', color: 'gray' },
};

/** Цвет бейджа уведомления по его виду (совпадает со страницей /notifications). */
const NOTIF_BADGE: Record<string, string> = {
  MED_REJECTED: 'red', TECH_REJECTED: 'red', BLOCKED: 'red', EXPIRED: 'amber', READY: 'green', CREATED: 'blue', DRIVER_ISSUE: 'amber',
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
function agoSec(iso: string | null): number | null {
  if (!iso) return null;
  return Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
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

const PER_PAGE = 10;

/**
 * Кабинет диспетчера — оперативный обзор смены: живой мониторинг рейсов на линии, заявки
 * водителей, путевые листы, требующие действия, инструменты и уведомления.
 * Диспетчер оформляет и ведёт путевые листы своей организации.
 */
export default function DispatcherCabinet() {
  const { t, tType, tStatus } = useT();
  const { roles } = useAuth();
  const canCreate = canCreateWaybill(roles);
  const [items, setItems] = useState<Waybill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const router = useRouter();
  // Живой мониторинг: ТС «на линии» (реальные GPS-позиции) и уведомления диспетчера.
  const [live, setLive] = useState<LivePosition[]>([]);
  const [notifs, setNotifs] = useState<NotificationItem[]>([]);
  const [tripQ, setTripQ] = useState('');
  // Заявки на путевые листы от водителей: очередь + панель проверки/одобрения/отклонения.
  const [reqs, setReqs] = useState<WaybillRequest[]>([]);
  const [sel, setSel] = useState<WaybillRequest | null>(null);
  const [rev, setRev] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [reqBusy, setReqBusy] = useState(false);
  const [reqErr, setReqErr] = useState('');
  const [reqMsg, setReqMsg] = useState('');
  // Пагинация таблицы «Требуют внимания».
  const [attnPage, setAttnPage] = useState(1);

  useEffect(() => {
    wb.list().then(setItems).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }, []);
  // Живые позиции обновляем периодически — как на странице мониторинга.
  useEffect(() => {
    const load = () => wb.gpsLive().then(setLive).catch(() => {});
    load();
    const h = window.setInterval(load, 15000);
    return () => window.clearInterval(h);
  }, []);
  useEffect(() => { wb.notifications().then(setNotifs).catch(() => {}); }, []);

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

  // Требуют внимания диспетчера — новые сверху.
  const attention = useMemo(
    () => items
      .filter(w => ACTION_HINT[w.status])
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [items],
  );
  const attnPages = Math.max(1, Math.ceil(attention.length / PER_PAGE));
  const attnView = attention.slice((attnPage - 1) * PER_PAGE, attnPage * PER_PAGE);

  const stats = useMemo(() => {
    const active = items.filter(w => ['ISSUED', 'ACTIVE', 'RETURNED'].includes(w.status)).length;
    const online = live.length;
    const driversOnShift = new Set(live.map(r => r.driver).filter(Boolean)).size;
    return { active, online, driversOnShift, attention: attention.length };
  }, [items, live, attention.length]);

  const recent = useMemo(
    () => [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 8),
    [items],
  );

  // Рейсы для блока мониторинга — с фильтром по номеру/ТС/водителю.
  const trips = useMemo(() => {
    const s = tripQ.trim().toLowerCase();
    const list = s
      ? live.filter(r => [r.vehicleRegNumber, r.number, r.driver].map(x => String(x ?? '').toLowerCase()).join(' ').includes(s))
      : live;
    return list.slice(0, 6);
  }, [live, tripQ]);

  const topNotifs = useMemo(() => notifs.slice(0, 5), [notifs]);

  const ago = (sec: number | null) => {
    if (sec == null) return t('mon.nosignal');
    if (sec < 60) return `${sec} ${t('mon.sec')}`;
    if (sec < 3600) return `${Math.floor(sec / 60)} ${t('mon.min')}`;
    return `${Math.floor(sec / 3600)} ${t('mon.hour')}`;
  };
  const sigColor = (sec: number | null) => sec == null ? 'gray' : sec < 120 ? 'green' : sec < 900 ? 'amber' : 'red';
  const notifTitle = (n: NotificationItem) => {
    const k = `notif.k.${n.kind}`;
    const tr = t(k);
    return tr === k ? n.title : tr;
  };

  const kpis = [
    { label: t('disp.kpi.active'), value: stats.active, icon: P.doc, cls: 'ic-blue' },
    { label: t('disp.kpi.online'), value: stats.online, icon: P.car, cls: 'ic-cyan' },
    { label: t('disp.kpi.drivers'), value: stats.driversOnShift, icon: P.users, cls: 'ic-green' },
    { label: t('disp.kpi.pending'), value: stats.attention, icon: P.alert, cls: 'ic-amber' },
  ];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.dispatcher')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('disp.lead')}</div>
        </div>
        {canCreate && (
          <Link href="/waybills/new" className="btn" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
            <Icon d={P.doc} cls="" /> {t('nav.waybill.new')}
          </Link>
        )}
      </div>

      {error && <div className="error">{error}</div>}

      <div style={{ marginBottom: 16 }}><ExpiryAlert days={30} compact /></div>

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

      {/* Основная сетка: слева — мониторинг рейсов и «требуют внимания», справа — инструменты и уведомления */}
      <div className="disp-grid">
        <div className="disp-main">
          {/* Мониторинг активных рейсов */}
          <div className="card">
            <div className="card-h">
              <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                <Icon d={P.route} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} />
                {t('disp.mon.h')}
              </h2>
              <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 10 }}>
                <input value={tripQ} onChange={e => setTripQ(e.target.value)} placeholder={t('disp.mon.search')} style={{ maxWidth: 200, padding: '7px 12px' }} />
                <Link className="link" href="/monitoring" style={{ whiteSpace: 'nowrap' }}>{t('disp.mon.map')}</Link>
              </div>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              {trips.map((r, i) => {
                const sec = agoSec(r.recordedAt);
                const sc = sigColor(sec);
                return (
                  <Link
                    key={r.vehicleRegNumber + i}
                    href="/monitoring"
                    className="trip-card"
                  >
                    <span className="trip-ic"><Icon d={P.car} cls="" style={{ width: 22, height: 22 }} /></span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <span className="number" style={{ fontSize: 13 }}>{r.vehicleRegNumber}</span>
                        <span className={`badge ${STATUS_LABELS[r.status]?.color ?? 'blue'}`}>{tStatus(r.status)}</span>
                      </div>
                      <div style={{ fontSize: 12.5, color: 'var(--muted)', marginTop: 3 }}>
                        {r.driver || '—'}{r.number ? ` · ${r.number}` : ''}
                      </div>
                    </div>
                    <div style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--ink)' }}>
                        {r.speedKmh != null ? `${r.speedKmh} ${t('mon.kmh')}` : '—'}
                      </div>
                      <div style={{ marginTop: 4 }}><span className={`badge ${sc}`}>{ago(sec)}</span></div>
                    </div>
                    <Icon d={P.chevron} cls="" style={{ width: 18, height: 18, color: 'var(--faint)', flex: 'none' }} />
                  </Link>
                );
              })}
              {trips.length === 0 && (
                <div style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>
                  {tripQ.trim() ? t('disp.mon.searchempty') : t('disp.mon.empty')}
                </div>
              )}
            </div>
          </div>

          {/* Требуют внимания */}
          <div className="card">
            <div className="card-h">
              <h2>{t('disp.attention.h')}</h2>
              <Link className="link" href="/waybills">{t('disp.allregistry')}</Link>
            </div>
            {/* Прокрутка вместо обрезки: при узкой колонке статус («Ожидает осмотров») срезался краем карточки. */}
            <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.transport')}</th><th>{t('col.driver')}</th><th>{t('col.todo')}</th><th>{t('col.status')}</th></tr>
              </thead>
              <tbody>
                {attnView.map(w => {
                  const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
                  return (
                    <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                      <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                      <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                      <td>{w.vehicleRegNumber || '—'}</td>
                      <td>{String(w.driverSnapshot?.fullName ?? w.driverRma ?? '—')}</td>
                      <td style={{ color: 'var(--ink-soft)' }}>{t(ACTION_HINT[w.status])}</td>
                      <td style={{ whiteSpace: 'nowrap' }}><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
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
            {/* Пагинация */}
            <div style={{ display: 'flex', alignItems: 'center', marginTop: 14, fontSize: 12.5, color: 'var(--muted)' }}>
              <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{attention.length}</b></span>
              <span style={{ flex: 1 }} />
              <button className="btn secondary" disabled={attnPage <= 1} onClick={() => setAttnPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
              <span style={{ margin: '0 12px' }}>{attnPage} / {attnPages}</span>
              <button className="btn secondary" disabled={attnPage >= attnPages} onClick={() => setAttnPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
            </div>
          </div>
        </div>

        <div className="disp-side">
          {/* Инструменты */}
          <div className="card">
            <h2>{t('disp.tools.h')}</h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {canCreate && (
                <Link href="/waybills/new" className="btn" style={{ justifyContent: 'flex-start', textDecoration: 'none' }}>
                  <Icon d={P.plus} cls="" style={{ width: 16, height: 16 }} /> {t('nav.waybill.new')}
                </Link>
              )}
              <Link href="/monitoring" className="btn secondary" style={{ justifyContent: 'flex-start', textDecoration: 'none' }}>
                <Icon d={P.route} cls="" style={{ width: 16, height: 16 }} /> {t('disp.tools.map')}
              </Link>
              <Link href="/waybills" className="btn secondary" style={{ justifyContent: 'flex-start', textDecoration: 'none' }}>
                <Icon d={P.doc} cls="" style={{ width: 16, height: 16 }} /> {t('disp.tools.registry')}
              </Link>
              <Link href="/fleet/vehicles" className="btn secondary" style={{ justifyContent: 'flex-start', textDecoration: 'none' }}>
                <Icon d={P.car} cls="" style={{ width: 16, height: 16 }} /> {t('disp.tools.fleet')}
              </Link>
            </div>
          </div>

          {/* Уведомления */}
          <div className="card">
            <div className="card-h">
              <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                <Icon d={P.bell} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} />
                {t('notif.title')}
              </h2>
              <Link className="link" href="/notifications">{t('disp.notif.all')}</Link>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              {topNotifs.map(n => (
                <Link
                  key={n.id}
                  href={n.waybillId ? `/waybills/${n.waybillId}` : '/notifications'}
                  style={{
                    display: 'flex', alignItems: 'flex-start', gap: 10, padding: '11px 0',
                    borderBottom: '1px solid var(--line-soft)', textDecoration: 'none',
                  }}
                >
                  <span style={{
                    width: 8, height: 8, borderRadius: '50%', marginTop: 6, flex: 'none',
                    background: n.readAt ? 'var(--line)' : 'var(--blue-600)',
                  }} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <span className={`badge ${NOTIF_BADGE[n.kind] ?? 'gray'}`}>{notifTitle(n)}</span>
                      <span style={{ fontSize: 11.5, color: 'var(--faint)' }}>{fmtDateTime(n.createdAt)}</span>
                    </div>
                    {n.body && <div style={{ fontSize: 12.5, color: 'var(--ink-soft)', marginTop: 3 }}>{n.body}</div>}
                  </div>
                </Link>
              ))}
              {topNotifs.length === 0 && (
                <div style={{ color: 'var(--muted)', fontSize: 13, padding: '8px 0' }}>{t('notif.empty')}</div>
              )}
            </div>
          </div>
        </div>
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
