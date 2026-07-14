'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, md, Waybill, STATUS_LABELS, authHeaders, TYPE_LABELS, type WaybillRequest } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';
import QRCode from 'qrcode';

/** Статус заявки на путевой лист → ключ i18n + цвет бейджа. */
const REQ_STATUS: Record<string, { k: string; color: string }> = {
  PENDING: { k: 'req.st.pending', color: 'amber' },
  APPROVED: { k: 'req.st.approved', color: 'green' },
  REJECTED: { k: 'req.st.rejected', color: 'red' },
  CANCELLED: { k: 'req.st.cancelled', color: 'gray' },
};

function fmtDate(iso: string | null) {
  return iso ? new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' }) : '—';
}
function fmtDateTime(iso: string | null) {
  return iso
    ? new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
    : '—';
}
/** Пробег рейса = одометр возврата − одометр выезда (если оба заполнены). */
function mileage(w: Waybill): number | null {
  if (w.odometerEntry != null && w.odometerExit != null && w.odometerEntry >= w.odometerExit) {
    return w.odometerEntry - w.odometerExit;
  }
  return null;
}

/**
 * Кабинет водителя — водитель видит свои путевые листы: активный ПЛ с QR-кодом
 * для предъявления инспектору и историю завершённых рейсов. Водитель ничего
 * не создаёт и не подписывает — только просматривает и предъявляет.
 */
export default function DriverCabinet() {
  const { t, tType, tStatus } = useT();
  const [items, setItems] = useState<Waybill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [qr, setQr] = useState('');
  const router = useRouter();
  // Заявка на путевой лист (водитель подаёт сам; личность — из входа, госномер вписывает вручную).
  const [reqs, setReqs] = useState<WaybillRequest[]>([]);
  const [showReqForm, setShowReqForm] = useState(false);
  const [reqForm, setReqForm] = useState({ vehicleRegNumber: '', waybillType: 'WB_BUS', requestedFrom: '', odometer: '', route: '', notes: '' });
  const [reqBusy, setReqBusy] = useState(false);
  const [reqMsg, setReqMsg] = useState('');
  const [reqErr, setReqErr] = useState('');
  const [orgName, setOrgName] = useState('');
  const [orgRma, setOrgRma] = useState('');
  // Автоподсказка ТС по госномеру: список машин своей компании (появляется при почти полном вводе).
  const [plateOpts, setPlateOpts] = useState<{ reg: string; brand: string }[]>([]);
  const [plateOpen, setPlateOpen] = useState(false);
  const pickedRef = useRef(false); // подавляет повторное открытие списка сразу после выбора

  useEffect(() => {
    wb.list().then(setItems).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }, []);

  // Текущий ПЛ: сначала активный, затем выданный, затем готовый к выдаче.
  const current = useMemo(() => {
    const pick = (st: string) => items.find(w => w.status === st);
    return pick('ACTIVE') ?? pick('ISSUED') ?? pick('READY') ?? null;
  }, [items]);

  // Остальные ПЛ — в историю рейсов (новые сверху).
  const history = useMemo(
    () => items.filter(w => w.id !== current?.id).sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [items, current],
  );

  const stats = useMemo(() => {
    const total = items.length;
    const active = items.filter(w => ['ACTIVE', 'ISSUED', 'RETURNED'].includes(w.status)).length;
    const completed = items.filter(w => w.status === 'COMPLETED').length;
    const km = items
      .filter(w => w.status === 'COMPLETED')
      .reduce((sum, w) => sum + (mileage(w) ?? 0), 0);
    return { total, active, completed, km };
  }, [items]);

  // QR текущего ПЛ: получаем jws-подпись и кодируем в неё URL страницы проверки,
  // чтобы камера телефона инспектора открывала её напрямую.
  useEffect(() => {
    setQr('');
    if (!current) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch(`/wb-api/api/v1/waybills/${current.id}/qr`, { headers: authHeaders() });
        if (!res.ok) return;
        const { jws } = (await res.json()) as { jws: string };
        const dataUrl = await QRCode.toDataURL(`${window.location.origin}/verify/${jws}`, { width: 220, margin: 1 });
        if (!cancelled) setQr(dataUrl);
      } catch {
        /* QR доступен только с готового путевого листа */
      }
    })();
    return () => { cancelled = true; };
  }, [current]);

  function loadReqs() { wb.requests.mine().then(r => { setReqs(r); setReqErr(''); }).catch((e: unknown) => setReqErr(e instanceof Error ? e.message : 'Не удалось загрузить заявки')); }
  useEffect(() => {
    loadReqs();
    // Организация, к которой привязан водитель (тенант-скоуп → своя). Фолбэк — из снимка ПЛ ниже.
    md.organizations().then(l => {
      setOrgName(String(l[0]?.name ?? ''));
      setOrgRma(String(l[0]?.rma ?? ''));
    }).catch(() => {});
  }, []);

  // Заявка «Ожидает» уже подана → новую подать нельзя (правило «одна заявка в работе»).
  const hasPending = useMemo(() => reqs.some(r => r.status === 'PENDING'), [reqs]);
  // Сегодня в локальной дате (YYYY-MM-DD) — нижняя граница «Даты выхода»: нельзя задним числом.
  const todayStr = new Date().toLocaleDateString('en-CA');

  // Автоподсказка ТС: ищем машины своей компании по подстроке госномера. Подсказываем уже с
  // 3 символов (номера бывают короткие, напр. 451TJ01). Выбор подставляет госномер.
  useEffect(() => {
    if (pickedRef.current) { pickedRef.current = false; setPlateOpen(false); return; }
    const q = reqForm.vehicleRegNumber.trim();
    if (!showReqForm || q.length < 3) { setPlateOpts([]); setPlateOpen(false); return; }
    let cancelled = false;
    const timer = setTimeout(() => {
      md.searchVehicles(orgRma, q, 8)
        .then(list => {
          if (cancelled) return;
          const opts = list.map(v => ({ reg: String(v.registrationNumber ?? ''), brand: String(v.brand ?? '') }));
          setPlateOpts(opts);
          setPlateOpen(opts.length > 0);
        })
        .catch(() => { if (!cancelled) { setPlateOpts([]); setPlateOpen(false); } });
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [reqForm.vehicleRegNumber, showReqForm, orgRma]);

  function pickPlate(reg: string) {
    pickedRef.current = true;
    setReqForm(f => ({ ...f, vehicleRegNumber: reg }));
    setPlateOpen(false);
  }

  async function submitRequest(e: React.FormEvent) {
    e.preventDefault();
    setReqBusy(true); setReqErr(''); setReqMsg('');
    try {
      await wb.requests.create({
        waybillType: reqForm.waybillType,
        vehicleRegNumber: reqForm.vehicleRegNumber.trim(),
        requestedFrom: reqForm.requestedFrom || null,
        odometer: reqForm.odometer ? Number(reqForm.odometer) : null,
        route: reqForm.route || null,
        notes: reqForm.notes || null,
      });
      setReqMsg(t('drvreq.sent'));
      setReqForm({ vehicleRegNumber: '', waybillType: 'WB_BUS', requestedFrom: '', odometer: '', route: '', notes: '' });
      setShowReqForm(false);
      loadReqs();
    } catch (err) { setReqErr((err as Error).message); }
    finally { setReqBusy(false); }
  }
  async function cancelReq(id: string) {
    setReqErr('');
    try { await wb.requests.cancel(id); loadReqs(); } catch (err) { setReqErr((err as Error).message); }
  }

  const kpis = [
    { label: t('drv.kpi.total'), value: stats.total, icon: P.doc, cls: 'ic-blue' },
    { label: t('drv.kpi.active'), value: stats.active, icon: P.car, cls: 'ic-cyan' },
    { label: t('kpi.done'), value: stats.completed, icon: P.check, cls: 'ic-green' },
    { label: t('drv.kpi.km'), value: stats.km, icon: P.route, cls: 'ic-purple' },
  ];

  const cs = current ? STATUS_LABELS[current.status] ?? { label: current.status, color: 'gray' } : null;
  const company = orgName || String(current?.organizationSnapshot?.name ?? '');

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('drv.h')}</h1>
          <div className="page-lead" style={{ margin: 0, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <span>{t('drv.lead')}</span>
            {company && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: 'var(--blue-700)', fontWeight: 600, background: 'var(--blue-050)', padding: '3px 10px', borderRadius: 999 }}>
                <Icon d={P.building} cls="" style={{ width: 14, height: 14 }} /> {t('drv.company')}: {company}
              </span>
            )}
          </div>
        </div>
        <Link href="/driver/waybills" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.doc} cls="" style={{ width: 16, height: 16 }} /> {t('drv.mywaybills')}
        </Link>
        <button className="btn" disabled={hasPending} title={hasPending ? t('drvreq.onlyone') : undefined}
          onClick={() => { setShowReqForm(v => !v); setReqErr(''); setReqMsg(''); }}>
          <Icon d={P.doc} cls="" style={{ width: 16, height: 16 }} /> {showReqForm ? t('drvreq.hide') : t('drvreq.new')}
        </button>
      </div>

      {error && <div className="error">{error}</div>}
      {reqErr && <div className="error">{reqErr}</div>}
      {reqMsg && <div className="success">{reqMsg}</div>}
      {hasPending && <div className="hint">{t('drvreq.onlyone')}</div>}

      {/* Заявка на путевой лист (водитель подаёт сам) */}
      {showReqForm && (
        <div className="card" style={{ borderColor: 'var(--blue-500)' }}>
          <h2 style={{ marginTop: 0 }}>{t('drvreq.new')}</h2>
          <p className="hint">{t('drvreq.hint')}</p>
          <form onSubmit={submitRequest} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 12 }}>
            <div style={{ position: 'relative' }}>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.plate')} *</label>
              <input required placeholder="0101TJ01" value={reqForm.vehicleRegNumber} autoComplete="off"
                onChange={e => setReqForm({ ...reqForm, vehicleRegNumber: e.target.value })}
                onFocus={() => { if (plateOpts.length) setPlateOpen(true); }}
                onBlur={() => setTimeout(() => setPlateOpen(false), 120)}
                style={{ width: '100%', marginTop: 4 }} />
              {plateOpen && plateOpts.length > 0 && (
                <div style={{
                  position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 40, marginTop: 4,
                  background: 'var(--surface)', border: '1px solid var(--line)', borderRadius: 8,
                  boxShadow: '0 10px 30px rgba(15,23,42,.14)', maxHeight: 240, overflowY: 'auto',
                }}>
                  {plateOpts.map(o => (
                    <button type="button" key={o.reg} onMouseDown={e => { e.preventDefault(); pickPlate(o.reg); }}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 10, width: '100%', textAlign: 'left',
                        padding: '9px 12px', background: 'none', border: 'none', cursor: 'pointer',
                        fontFamily: 'inherit', borderBottom: '1px solid var(--line-soft)',
                      }}>
                      <span className="number" style={{ fontWeight: 700 }}>{o.reg}</span>
                      {o.brand && <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>{o.brand}</span>}
                    </button>
                  ))}
                </div>
              )}
              <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 3 }}>{t('drvreq.f.plate.hint')}</div>
            </div>
            <div>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.type')} *</label>
              <select value={reqForm.waybillType} onChange={e => setReqForm({ ...reqForm, waybillType: e.target.value })} style={{ width: '100%', marginTop: 4 }}>
                {Object.keys(TYPE_LABELS).map(v => <option key={v} value={v}>{tType(v)}</option>)}
              </select>
            </div>
            <div>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.from')}</label>
              <input type="date" min={todayStr} value={reqForm.requestedFrom} onChange={e => setReqForm({ ...reqForm, requestedFrom: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.odometer')}</label>
              <input type="number" min={0} placeholder={t('drvreq.f.odometer.ph')} value={reqForm.odometer}
                onChange={e => setReqForm({ ...reqForm, odometer: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.route')}</label>
              <input value={reqForm.route} onChange={e => setReqForm({ ...reqForm, route: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.notes')}</label>
              <input value={reqForm.notes} onChange={e => setReqForm({ ...reqForm, notes: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div style={{ gridColumn: '1 / -1', display: 'flex', gap: 10, marginTop: 4 }}>
              <button className="btn primary" type="submit" disabled={reqBusy || hasPending || !reqForm.vehicleRegNumber.trim()}>{reqBusy ? '…' : t('drvreq.send')}</button>
              <button type="button" className="btn secondary" onClick={() => setShowReqForm(false)}>{t('fleet.cancel')}</button>
            </div>
          </form>
          <div className="hint" style={{ marginTop: 8 }}>{t('drvreq.privacy')}</div>
        </div>
      )}

      {reqs.length > 0 && (
        <div className="card">
          <div className="card-h"><h2>{t('drvreq.mine.h')}</h2></div>
          <table>
            <thead><tr><th>{t('col.type')}</th><th>{t('col.transport')}</th><th>{t('drvreq.f.from')}</th><th>{t('col.odometer')}</th><th>{t('col.status')}</th><th></th></tr></thead>
            <tbody>
              {reqs.map(r => {
                const rs = REQ_STATUS[r.status] ?? { k: r.status, color: 'gray' };
                return (
                  <tr key={r.id}>
                    <td>{tType(r.waybillType).replace(/\s*\(.*\)/, '')}</td>
                    <td><span className="number">{r.vehicleRegNumber}</span></td>
                    <td>{r.requestedFrom ?? '—'}</td>
                    <td>{r.odometer != null ? `${r.odometer.toLocaleString('ru-RU')} км` : '—'}</td>
                    <td>
                      <span className={`badge ${rs.color}`}>{t(rs.k)}</span>
                      {r.status === 'REJECTED' && r.rejectReason ? <div style={{ fontSize: 11.5, color: 'var(--red)', marginTop: 2 }}>{r.rejectReason}</div> : null}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {r.status === 'PENDING' && <button className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => cancelReq(r.id)}>{t('drvreq.cancel')}</button>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Текущий путевой лист */}
      {current ? (
        <div className="card">
          <div className="card-h">
            <h2>{t('drv.current.h')}</h2>
            {cs && <span className={`badge ${cs.color}`} style={{ marginLeft: 'auto' }}>{tStatus(current.status)}</span>}
          </div>
          <div style={{ display: 'flex', gap: 28, flexWrap: 'wrap', alignItems: 'flex-start' }}>
            {/* Сведения */}
            <div style={{ flex: 1, minWidth: 300 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', marginBottom: 18 }}>
                <span
                  style={{
                    fontFamily: 'var(--mono)', fontWeight: 800, fontSize: 30, letterSpacing: '-.02em',
                    color: 'var(--blue-700)', background: 'var(--blue-050)', padding: '6px 16px', borderRadius: 10,
                  }}
                >
                  {current.number ?? t('common.draft')}
                </span>
                <span className={`badge ${current.medPassed ? 'green' : 'gray'}`}>{t('drv.t2')} {current.medPassed ? '✓' : '…'}</span>
                <span className={`badge ${current.techPassed ? 'green' : 'gray'}`}>{t('drv.t3')} {current.techPassed ? '✓' : '…'}</span>
              </div>
              <dl className="kv">
                <dt>{t('col.type')}</dt><dd>{tType(current.waybillType)}</dd>
                <dt>{t('drv.routeschedule')}</dt><dd>{current.route ?? '—'} / {current.schedule ?? '—'}</dd>
                <dt>{t('col.vehiclefull')}</dt><dd>{String(current.vehicleSnapshot?.brand ?? '')} {current.vehicleRegNumber}</dd>
                <dt>{t('drv.validity')}</dt><dd>{fmtDateTime(current.validFrom)} → {fmtDateTime(current.validTo)}</dd>
              </dl>
            </div>

            {/* QR-код */}
            <div
              style={{
                flex: 'none', width: 240, textAlign: 'center', display: 'flex', flexDirection: 'column',
                alignItems: 'center', gap: 12, background: 'var(--blue-050)', borderRadius: 14, padding: 20,
              }}
            >
              {qr ? (
                <>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={qr}
                    alt={t('drv.qr.alt')}
                    width={200}
                    height={200}
                    style={{ background: '#fff', borderRadius: 12, padding: 10 }}
                  />
                  <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink)' }}>{t('drv.qr.show')}</div>
                  <div style={{ fontSize: 12, color: 'var(--muted)' }}>{t('drv.qr.valid')}</div>
                </>
              ) : (
                <div
                  style={{
                    width: 200, height: 200, display: 'grid', placeItems: 'center', background: '#fff',
                    borderRadius: 12, color: 'var(--muted)', fontSize: 12.5, textAlign: 'center', padding: 16,
                  }}
                >
                  {t('drv.qr.pending')}
                </div>
              )}
            </div>
          </div>
        </div>
      ) : (
        <div className="card" style={{ textAlign: 'center', padding: '38px 20px' }}>
          <span className="k-ic ic-blue" style={{ display: 'inline-grid', marginBottom: 12 }}><Icon d={P.doc} cls="" /></span>
          <h2 style={{ marginBottom: 6 }}>{t('drv.empty.h')}</h2>
          <div style={{ color: 'var(--muted)', fontSize: 13.5 }}>
            {t('drv.empty.note')}
          </div>
        </div>
      )}

      {/* KPI */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        {kpis.map(k => (
          <div className="kpi" key={k.label}>
            <div className="k-top">
              <span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span>
            </div>
            <div className="k-label">{k.label}</div>
            <div className="k-value">{loading ? '—' : k.value.toLocaleString('ru-RU')}</div>
          </div>
        ))}
      </div>

      {/* История рейсов */}
      <div className="card">
        <div className="card-h">
          <h2>{t('drv.hist.h')}</h2>
          <Link className="link" href="/driver/waybills">{t('drv.allwb')}</Link>
        </div>
        <table>
          <thead>
            <tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.route')}</th><th>{t('col.date')}</th><th>{t('col.mileage')}</th><th>{t('col.status')}</th></tr>
          </thead>
          <tbody>
            {history.slice(0, 5).map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              const km = mileage(w);
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                  <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{w.route ?? '—'}</td>
                  <td>{fmtDate(w.createdAt)}</td>
                  <td>{km != null ? `${km.toLocaleString('ru-RU')} км` : '—'}</td>
                  <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                </tr>
              );
            })}
            {history.length === 0 && !loading && (
              <tr>
                <td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>{t('drv.empty.trips')}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
