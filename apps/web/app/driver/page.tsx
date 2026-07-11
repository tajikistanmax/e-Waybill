'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS, authHeaders } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';
import QRCode from 'qrcode';

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
  const [qr, setQr] = useState('');
  const router = useRouter();

  useEffect(() => {
    wb.list().then(setItems).catch(() => {}).finally(() => setLoading(false));
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

  const kpis = [
    { label: t('drv.kpi.total'), value: stats.total, icon: P.doc, cls: 'ic-blue' },
    { label: t('drv.kpi.active'), value: stats.active, icon: P.car, cls: 'ic-cyan' },
    { label: t('kpi.done'), value: stats.completed, icon: P.check, cls: 'ic-green' },
    { label: t('drv.kpi.km'), value: stats.km, icon: P.route, cls: 'ic-purple' },
  ];

  const cs = current ? STATUS_LABELS[current.status] ?? { label: current.status, color: 'gray' } : null;

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('drv.h')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('drv.lead')}</div>
        </div>
      </div>

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
                  {current.number ?? '— черновик —'}
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
          <Link className="link" href="/waybills">{t('drv.allwb')}</Link>
        </div>
        <table>
          <thead>
            <tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.route')}</th><th>{t('col.date')}</th><th>{t('col.mileage')}</th><th>{t('col.status')}</th></tr>
          </thead>
          <tbody>
            {history.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              const km = mileage(w);
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? '— черновик —'}</span></td>
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
