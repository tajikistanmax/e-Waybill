'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import dynamic from 'next/dynamic';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, md, Waybill, STATUS_LABELS, authHeaders, TYPE_LABELS, type WaybillRequest, type LivePosition, type GpsPing } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { verifyLink } from '@/lib/verify';
import { Icon, P } from '../icons';
import QRCode from 'qrcode';

// Карта рейса (Leaflet) — только на клиенте (обращается к window), поэтому ssr:false. Общий
// компонент с монитором диспетчера — с компактной высотой через проп height.
const LeafletMap = dynamic(() => import('../monitoring/LeafletMap'), {
  ssr: false,
  loading: () => <div style={{ height: 240, display: 'grid', placeItems: 'center', color: 'var(--muted)', border: '1px solid var(--line)', borderRadius: 14 }}>Загрузка карты…</div>,
});

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
  const [reqForm, setReqForm] = useState({ vehicleRegNumber: '', waybillType: 'WB_BUS', requestedFrom: '', odometer: '', communicationType: 'URBAN', route: '', schedule: '', notes: '' });
  const [reqBusy, setReqBusy] = useState(false);
  const [reqMsg, setReqMsg] = useState('');
  const [reqErr, setReqErr] = useState('');
  const [orgName, setOrgName] = useState('');
  const [orgRma, setOrgRma] = useState('');
  // Автоподсказка ТС по госномеру: список машин своей компании (появляется при почти полном вводе).
  const [plateOpts, setPlateOpts] = useState<{ reg: string; brand: string }[]>([]);
  const [plateOpen, setPlateOpen] = useState(false);
  const pickedRef = useRef(false); // подавляет повторное открытие списка сразу после выбора
  // Живая позиция ТС текущего рейса (водителю доступен /gps/last по своему ТС).
  const [pos, setPos] = useState<GpsPing | null>(null);
  // Реквизиты закреплённого ТС (сроки техосмотра/страховки) — из справочника парка.
  const [vehInfo, setVehInfo] = useState<Record<string, unknown> | null>(null);
  // «Сообщить о проблеме» — сообщение диспетчеру.
  const [reportOpen, setReportOpen] = useState(false);
  const [reportForm, setReportForm] = useState({ issueType: '', message: '' });
  const [reportBusy, setReportBusy] = useState(false);
  const [reportMsg, setReportMsg] = useState('');
  const [reportErr, setReportErr] = useState('');

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
        const dataUrl = await QRCode.toDataURL(verifyLink(jws), { width: 220, margin: 1 });
        if (!cancelled) setQr(dataUrl);
      } catch {
        /* QR доступен только с готового путевого листа */
      }
    })();
    return () => { cancelled = true; };
  }, [current]);

  // Живая позиция ТС текущего рейса — только своё ТС (обновление 15 с).
  useEffect(() => {
    if (!current) { setPos(null); return; }
    let alive = true;
    const load = () => wb.gpsLast(current.vehicleRegNumber).then(p => { if (alive) setPos(p); }).catch(() => {});
    load();
    const h = window.setInterval(load, 15000);
    return () => { alive = false; window.clearInterval(h); };
  }, [current]);

  // Реквизиты закреплённого ТС (сроки техосмотра/страховки) из справочника парка.
  useEffect(() => {
    if (!current) { setVehInfo(null); return; }
    let alive = true;
    md.searchVehicles(orgRma, current.vehicleRegNumber, 3)
      .then(list => {
        if (!alive) return;
        const reg = current.vehicleRegNumber.trim().toUpperCase();
        setVehInfo(list.find(v => String(v.registrationNumber ?? '').toUpperCase() === reg) ?? list[0] ?? null);
      })
      .catch(() => { if (alive) setVehInfo(null); });
    return () => { alive = false; };
  }, [current, orgRma]);

  async function submitReport() {
    if (!reportForm.message.trim()) return;
    setReportBusy(true); setReportErr('');
    try {
      await wb.reportIssue({
        issueType: reportForm.issueType || t('drv.report.t.other'),
        message: reportForm.message.trim(),
        waybillId: current?.id ?? null,
      });
      setReportMsg(t('drv.report.sent'));
      setReportOpen(false);
      setReportForm({ issueType: '', message: '' });
    } catch (err) { setReportErr((err as Error).message); }
    finally { setReportBusy(false); }
  }

  function loadReqs() { wb.requests.mine().then(setReqs).catch(() => {}); }
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
        communicationType: reqForm.communicationType || null,
        route: reqForm.route || null,
        schedule: reqForm.schedule || null,
        notes: reqForm.notes || null,
      });
      setReqMsg(t('drvreq.sent'));
      setReqForm({ vehicleRegNumber: '', waybillType: 'WB_BUS', requestedFrom: '', odometer: '', communicationType: 'URBAN', route: '', schedule: '', notes: '' });
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

  // Строка для карты рейса (единый компонент с монитором диспетчера).
  const mapRow: LivePosition | null = current ? {
    vehicleRegNumber: current.vehicleRegNumber,
    number: current.number,
    driver: String(current.driverSnapshot?.fullName ?? current.driverRma),
    status: current.status,
    waybillType: current.waybillType,
    organizationRma: current.organizationRma,
    organizationName: String(current.organizationSnapshot?.name ?? ''),
    lat: pos?.lat ?? null, lon: pos?.lon ?? null,
    speedKmh: pos?.speedKmh ?? null, recordedAt: pos?.recordedAt ?? null,
  } : null;
  const vehBrand = String(current?.vehicleSnapshot?.brand ?? vehInfo?.brand ?? '');
  const techTo = vehInfo?.techInspectionValidTo ? String(vehInfo.techInspectionValidTo) : null;
  const insTo = vehInfo?.insuranceValidTo ? String(vehInfo.insuranceValidTo) : null;
  const validColor = (d: string | null) => {
    if (!d) return 'gray';
    const days = (new Date(d).getTime() - Date.now()) / 86400000;
    return isNaN(days) ? 'gray' : days < 0 ? 'red' : days <= 30 ? 'amber' : 'green';
  };

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
      {reportMsg && <div className="success">{reportMsg}</div>}
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
              {/* Одометр обязателен: диспетчер выпускает лист от текущих показаний (Т4),
                  непрерывность пробега — антифрод-инвариант. Сервер тоже это проверяет. */}
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.odometer')} *</label>
              <input type="number" min={0} required placeholder={t('drvreq.f.odometer.ph')} value={reqForm.odometer}
                onChange={e => setReqForm({ ...reqForm, odometer: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.comm')}</label>
              <select value={reqForm.communicationType} onChange={e => setReqForm({ ...reqForm, communicationType: e.target.value })} style={{ width: '100%', marginTop: 4 }}>
                <option value="URBAN">{t('comm.URBAN')}</option>
                <option value="SUBURBAN">{t('comm.SUBURBAN')}</option>
                <option value="INTERCITY">{t('comm.INTERCITY')}</option>
                <option value="INTERNATIONAL">{t('comm.INTERNATIONAL')}</option>
              </select>
            </div>
            <div>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.route')}</label>
              <input value={reqForm.route} onChange={e => setReqForm({ ...reqForm, route: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.schedule')}</label>
              <input value={reqForm.schedule} placeholder={t('drvreq.f.schedule.ph')}
                onChange={e => setReqForm({ ...reqForm, schedule: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div style={{ gridColumn: '1 / -1' }}>
              <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('drvreq.f.notes')}</label>
              <input value={reqForm.notes} onChange={e => setReqForm({ ...reqForm, notes: e.target.value })} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div style={{ gridColumn: '1 / -1', display: 'flex', gap: 10, marginTop: 4 }}>
              <button className="btn primary" type="submit" disabled={reqBusy || hasPending || !reqForm.vehicleRegNumber.trim() || !reqForm.odometer.trim()}>{reqBusy ? '…' : t('drvreq.send')}</button>
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

          {/* Положение ТС на карте — живой GPS текущего рейса */}
          <div style={{ marginTop: 18 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
              <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--ink-soft)', display: 'inline-flex', alignItems: 'center', gap: 7 }}>
                <Icon d={P.route} cls="" style={{ width: 16, height: 16, color: 'var(--blue-600)' }} /> {t('drv.trip.map')}
              </span>
              {pos && (
                <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--muted)' }}>
                  {pos.speedKmh != null ? `${pos.speedKmh} ${t('mon.kmh')} · ` : ''}{t('drv.trip.updated')} {fmtDateTime(pos.recordedAt)}
                </span>
              )}
            </div>
            {pos && mapRow ? (
              <LeafletMap rows={[mapRow]} t={t} tStatus={tStatus} height={240} />
            ) : (
              <div style={{ height: 160, display: 'grid', placeItems: 'center', textAlign: 'center', color: 'var(--muted)', fontSize: 13, background: 'var(--line-soft)', borderRadius: 14, border: '1px solid var(--line)' }}>
                {t('drv.trip.nosignal')}
              </div>
            )}
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

      {/* Закреплённый транспорт + быстрые действия */}
      <div className="grid-2" style={{ alignItems: 'start' }}>
        <div className="card" style={{ marginBottom: 0 }}>
          <div className="card-h">
            <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
              <Icon d={P.car} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} /> {t('drv.vehicle.h')}
            </h2>
          </div>
          {current ? (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 16 }}>
                <span className="k-ic ic-blue" style={{ width: 48, height: 48 }}><Icon d={P.car} cls="" /></span>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 12, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.03em', fontWeight: 600 }}>{vehBrand || '—'}</div>
                  <div className="number" style={{ fontSize: 18 }}>{current.vehicleRegNumber}</div>
                </div>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div>
                  <div style={{ fontSize: 11.5, color: 'var(--muted)', marginBottom: 4 }}>{t('drv.vehicle.tech')}</div>
                  <span className={`badge ${validColor(techTo)}`}>{techTo ?? '—'}</span>
                </div>
                <div>
                  <div style={{ fontSize: 11.5, color: 'var(--muted)', marginBottom: 4 }}>{t('drv.vehicle.ins')}</div>
                  <span className={`badge ${validColor(insTo)}`}>{insTo ?? '—'}</span>
                </div>
              </div>
              <Link href={`/waybills/${current.id}`} className="btn secondary" style={{ marginTop: 16, textDecoration: 'none' }}>
                <Icon d={P.eye} cls="" style={{ width: 15, height: 15 }} /> {t('btn.open')}
              </Link>
            </>
          ) : (
            <p style={{ color: 'var(--muted)', fontSize: 13, margin: 0 }}>{t('drv.vehicle.none')}</p>
          )}
        </div>

        <div className="card" style={{ marginBottom: 0 }}>
          <div className="card-h"><h2>{t('drv.quick.h')}</h2></div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <Link href="/driver/waybills" className="quick-tile">
              <span className="k-ic ic-blue"><Icon d={P.doc} cls="" /></span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 13.5, color: 'var(--ink)' }}>{t('drv.mywaybills')}</div>
                <div style={{ fontSize: 12, color: 'var(--muted)' }}>{t('drv.quick.waybills.s')}</div>
              </div>
              <Icon d={P.chevron} cls="" style={{ width: 16, height: 16, color: 'var(--faint)' }} />
            </Link>
            <Link href="/notifications" className="quick-tile">
              <span className="k-ic ic-amber"><Icon d={P.bell} cls="" /></span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 13.5, color: 'var(--ink)' }}>{t('drv.quick.notif')}</div>
                <div style={{ fontSize: 12, color: 'var(--muted)' }}>{t('drv.quick.notif.s')}</div>
              </div>
              <Icon d={P.chevron} cls="" style={{ width: 16, height: 16, color: 'var(--faint)' }} />
            </Link>
            <button type="button" className="quick-tile alert" onClick={() => { setReportOpen(true); setReportErr(''); setReportMsg(''); }}>
              <span className="k-ic ic-red"><Icon d={P.alert} cls="" /></span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 13.5, color: 'var(--red)' }}>{t('drv.report.btn')}</div>
                <div style={{ fontSize: 12, color: 'var(--muted)' }}>{t('drv.report.sub')}</div>
              </div>
              <Icon d={P.chevron} cls="" style={{ width: 16, height: 16, color: 'var(--faint)' }} />
            </button>
          </div>
        </div>
      </div>

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

      {/* Модалка «Сообщить о проблеме» */}
      {reportOpen && (
        <div onClick={() => setReportOpen(false)} style={{ position: 'fixed', inset: 0, background: 'rgba(15,27,52,.45)', display: 'grid', placeItems: 'center', zIndex: 60, padding: 20 }}>
          <div className="card" onClick={e => e.stopPropagation()} style={{ width: 460, maxWidth: '100%', margin: 0, borderColor: 'var(--red)', boxShadow: 'var(--shadow-lg)' }}>
            <div className="card-h">
              <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, color: 'var(--red)', margin: 0 }}>
                <Icon d={P.alert} cls="" style={{ width: 18, height: 18 }} /> {t('drv.report.h')}
              </h2>
              <button onClick={() => setReportOpen(false)} aria-label={t('btn.close')} style={{ marginLeft: 'auto', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--muted)', fontSize: 16 }}>✕</button>
            </div>
            <p className="hint">{t('drv.report.note')}</p>
            {reportErr && <div className="error">{reportErr}</div>}
            <div style={{ marginBottom: 12 }}>
              <label>{t('drv.report.type')}</label>
              <select value={reportForm.issueType} onChange={e => setReportForm({ ...reportForm, issueType: e.target.value })}>
                <option value="">—</option>
                <option value={t('drv.report.t.breakdown')}>{t('drv.report.t.breakdown')}</option>
                <option value={t('drv.report.t.road')}>{t('drv.report.t.road')}</option>
                <option value={t('drv.report.t.waybill')}>{t('drv.report.t.waybill')}</option>
                <option value={t('drv.report.t.delay')}>{t('drv.report.t.delay')}</option>
                <option value={t('drv.report.t.other')}>{t('drv.report.t.other')}</option>
              </select>
            </div>
            <div style={{ marginBottom: 14 }}>
              <label>{t('drv.report.msg')}</label>
              <textarea value={reportForm.message} onChange={e => setReportForm({ ...reportForm, message: e.target.value })} placeholder={t('drv.report.msg.ph')} maxLength={500}
                style={{ width: '100%', minHeight: 110, padding: '10px 13px', border: '1px solid var(--line)', borderRadius: 'var(--radius-sm)', fontSize: 13.5, fontFamily: 'var(--sans)', color: 'var(--ink)', resize: 'vertical' }} />
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button className="btn secondary" onClick={() => setReportOpen(false)}>{t('fleet.cancel')}</button>
              <button className="btn danger" disabled={reportBusy || !reportForm.message.trim()} onClick={submitReport}>{reportBusy ? '…' : t('drv.report.send')}</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
