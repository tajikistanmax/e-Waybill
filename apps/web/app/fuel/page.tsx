'use client';

import { useCallback, useEffect, useState } from 'react';
import { wb, STATUS_LABELS, type FuelStationWaybill, type FuelLine } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

const FUEL_TYPES: Record<number, string> = {
  1: 'Бензин', 2: 'Дизель', 3: 'Газ сжиженный', 4: 'Газ природный', 5: 'Электро',
};
const PER_PAGE = 10;

/**
 * Кабинет пункта выдачи топлива (роль FUEL_STATION). Топливник видит путевые листы
 * своей организации «на заправке» (выданные / на линии) и заносит фактическую выдачу
 * по колонке. Сам путевой лист (маршрут, водитель, показания) он не правит.
 */
export default function FuelStationCabinet() {
  const { t, tStatus } = useT();
  const [rows, setRows] = useState<FuelStationWaybill[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [sel, setSel] = useState<FuelStationWaybill | null>(null);
  const [lines, setLines] = useState<FuelLine[]>([]);
  const [form, setForm] = useState({ fuelType: '2', fuelGiven: '', remainBeforeExit: '', remainEntry: '', additionalGiven: '', returned: '', coefBelow0: '', beGiven: '' });
  const [busy, setBusy] = useState(false);
  const [page, setPage] = useState(1);
  // Подсказка «из предыдущего ПЛ этого ТС» (legacy parking_fuel_left/parking_fuel_give, MIGRATION.md §4.9).
  const [hint, setHint] = useState('');

  // При выборе листа и смене вида топлива подставляем остаток до выезда и норму к выдаче
  // из предыдущего ПЛ того же ТС (как в legacy-форме топливника).
  useEffect(() => {
    if (!sel) { setHint(''); return; }
    let cancelled = false;
    wb.fuelPrefill(sel.id, Number(form.fuelType)).then(p => {
      if (cancelled) return;
      setForm(f => ({
        ...f,
        remainBeforeExit: p.remainBeforeExit != null ? String(p.remainBeforeExit) : '',
      }));
      setHint(p.found ? `${t('wbd.fuelprefill.from')}${p.sourceWaybillNumber ? ` (${p.sourceWaybillNumber})` : ''}` : t('wbd.fuelprefill.none'));
    }).catch(() => { if (!cancelled) setHint(''); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sel?.id, form.fuelType]);

  const load = useCallback(() => {
    wb.fuelStation.list().then(setRows).catch(e => setError(e.message));
  }, []);
  useEffect(() => { load(); }, [load]);

  const openWaybill = useCallback((w: FuelStationWaybill) => {
    setSel(w); setOk(''); setError('');
    wb.fuelStation.fuel(w.id).then(setLines).catch(e => setError(e.message));
  }, []);

  async function record() {
    if (!sel) return;
    setBusy(true); setError(''); setOk('');
    try {
      await wb.fuelStation.record(sel.id, {
        fuelType: Number(form.fuelType),
        fuelGiven: Number(form.fuelGiven || 0),
        remainBeforeExit: form.remainBeforeExit ? Number(form.remainBeforeExit) : null,
        remainEntry: form.remainEntry ? Number(form.remainEntry) : null,
        additionalGiven: form.additionalGiven ? Number(form.additionalGiven) : null,
        returned: form.returned ? Number(form.returned) : null,
        coefBelow0: form.coefBelow0 ? Number(form.coefBelow0) : null,
      });
      setOk(t('fuel.recorded'));
      setForm(f => ({ ...f, fuelGiven: '', remainBeforeExit: '', remainEntry: '', additionalGiven: '', returned: '', coefBelow0: '', beGiven: '' }));
      wb.fuelStation.fuel(sel.id).then(setLines);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const kpis = [
    { label: t('fuel.kpi.sheets'), value: rows.length, icon: P.doc, cls: 'ic-blue' },
    { label: t('fuel.kpi.nofuel'), value: rows.filter(r => r.fuelRecords === 0).length, icon: P.alert, cls: 'ic-amber' },
    { label: t('fuel.kpi.totalgiven'), value: Math.round(rows.reduce((s, r) => s + r.totalGiven, 0)), icon: P.route, cls: 'ic-green' },
  ];

  const pages = Math.max(1, Math.ceil(rows.length / PER_PAGE));
  const view = rows.slice((page - 1) * PER_PAGE, page * PER_PAGE);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.fuel')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('fuel.lead')}</div>
        </div>
        <button className="btn secondary" style={{ marginLeft: 'auto' }} onClick={load}><Icon d={P.route} cls="" style={{ width: 15, height: 15 }} /> {t('fuel.refresh')}</button>
      </div>

      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
        {kpis.map(k => (
          <div className="kpi" key={k.label}>
            <div className="k-top"><span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span></div>
            <div className="k-label">{k.label}</div>
            <div className="k-value">{k.value.toLocaleString('ru-RU')}</div>
          </div>
        ))}
      </div>

      {/* Список листов шире панели выдачи: при равных колонках столбец «Выдано, л» уезжал за край
          карточки (горизонтальная прокрутка) и заправщик не видел, сколько уже выдано. */}
      <div className="grid-2" style={{ gridTemplateColumns: 'minmax(0, 1.4fr) minmax(0, 1fr)' }}>
        <div className="card" style={{ overflowX: 'auto' }}>
          <h2>{t('fuel.h.sheets')}</h2>
          <table>
            <thead><tr><th>№</th><th>{t('rj.vehicle')}</th><th>{t('rj.driver')}</th><th>{t('col.status')}</th><th>{t('fuel.col.given')}</th></tr></thead>
            <tbody>
              {view.map(r => {
                const s = STATUS_LABELS[r.status] ?? { label: r.status, color: 'gray' };
                return (
                  <tr key={r.id} className="clickable" onClick={() => openWaybill(r)} style={sel?.id === r.id ? { background: 'var(--blue-050)' } : undefined}>
                    <td><span className="number">{r.number ?? '—'}</span></td>
                    <td>{r.vehicleBrand} {r.vehicleRegNumber}</td>
                    <td>{r.driver}</td>
                    <td><span className={`badge ${s.color}`}>{tStatus(r.status)}</span></td>
                    <td>{r.totalGiven}{r.fuelRecords === 0 && <span className="badge amber" style={{ marginLeft: 6 }}>{t('fuel.badge.no')}</span>}</td>
                  </tr>
                );
              })}
              {rows.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('fuel.empty.sheets')}</td></tr>}
            </tbody>
          </table>
          {/* Пагинация */}
          <div style={{ display: 'flex', alignItems: 'center', marginTop: 14, fontSize: 12.5, color: 'var(--muted)' }}>
            <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{rows.length}</b></span>
            <span style={{ flex: 1 }} />
            <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
            <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
            <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
          </div>
        </div>

        <div className="card">
          <h2>{sel ? `${t('fuel.h.fuel')}: ${sel.number ?? sel.vehicleRegNumber}` : t('fuel.h.select')}</h2>
          {sel && (
            <>
              <table>
                <thead><tr><th>{t('fuel.col.kind')}</th><th>{t('fuel.col.given')}</th><th>{t('fuel.col.remainbefore')}</th><th>{t('fuel.col.remainentry')}</th><th>{t('fuel.col.additional')}</th><th>{t('fuel.col.returned')}</th><th>{t('wbd.fuelcoef0')}</th><th>{t('fuel.col.time')}</th></tr></thead>
                <tbody>
                  {lines.map(l => (
                    <tr key={l.id}>
                      <td>{l.fuelName}</td>
                      <td>{l.fuelGiven ?? '—'}</td>
                      <td>{l.remainBeforeExit ?? '—'}</td>
                      <td>{l.remainEntry ?? '—'}</td>
                      <td>{l.additionalGiven ?? '—'}</td>
                      <td>{l.returned ?? '—'}</td>
                      <td>{l.coefBelow0 ?? '—'}</td>
                      <td>{l.at ? new Date(l.at).toLocaleString('ru-RU') : '—'}</td>
                    </tr>
                  ))}
                  {lines.length === 0 && <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 14 }}>{t('fuel.empty.records')}</td></tr>}
                </tbody>
              </table>

              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 12 }}>
                <div>
                  <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('fuel.f.type')}</label>
                  <select value={form.fuelType} onChange={e => setForm(f => ({ ...f, fuelType: e.target.value }))} style={{ width: 150 }}>
                    {Object.keys(FUEL_TYPES).map(v => <option key={v} value={v}>{t('fuel.type.' + v)}</option>)}
                  </select>
                </div>
                <div><label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('fuel.col.given')}</label>
                  <input type="number" step="0.1" style={{ width: 110 }} value={form.fuelGiven} onChange={e => setForm(f => ({ ...f, fuelGiven: e.target.value }))} /></div>
                <div><label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('fuel.col.remainbefore')}</label>
                  <input type="number" step="0.1" style={{ width: 130 }} value={form.remainBeforeExit} onChange={e => setForm(f => ({ ...f, remainBeforeExit: e.target.value }))} /></div>
                <div><label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('fuel.col.remainentry')}</label>
                  <input type="number" step="0.1" style={{ width: 130 }} value={form.remainEntry} onChange={e => setForm(f => ({ ...f, remainEntry: e.target.value }))} /></div>
                <div><label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('wbd.fueladd')}</label>
                  <input type="number" step="0.1" style={{ width: 120 }} value={form.additionalGiven} onChange={e => setForm(f => ({ ...f, additionalGiven: e.target.value }))} /></div>
                <div><label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('wbd.fuelreturn')}</label>
                  <input type="number" step="0.1" style={{ width: 120 }} value={form.returned} onChange={e => setForm(f => ({ ...f, returned: e.target.value }))} /></div>
                <div><label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('wbd.fuelcoef0')}</label>
                  <input type="number" step="0.1" min="0" style={{ width: 120 }} value={form.coefBelow0} onChange={e => setForm(f => ({ ...f, coefBelow0: e.target.value }))} /></div>
                {/* «Норма к выдаче» (Дода шавад) убрана (24.09.2026): в старой платформе 0 из 118 536
                    строк топлива; не печатается и в расчёт не идёт. */}
                <button className="btn" onClick={record} disabled={busy || !form.fuelGiven}>{busy ? '…' : t('fuel.btn.record')}</button>
              </div>
              {hint && <div style={{ marginTop: 8, fontSize: 12, color: 'var(--muted)' }}>{hint}</div>}
            </>
          )}
        </div>
      </div>
    </>
  );
}
