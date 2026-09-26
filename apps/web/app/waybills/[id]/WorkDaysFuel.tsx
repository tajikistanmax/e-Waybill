'use client';

import { useEffect, useState } from 'react';
import { wb, type Waybill, type WorkDaysResponse, type WorkDayRow, type FuelRecordRow } from '@/lib/api';
import { useT } from '@/lib/i18n';

/**
 * Рабочие дни, строки топлива и нормирование топлива листа (вкладка «Топливо» карточки).
 *
 * Как в legacy-формах 1-А/3-С/2-Б/1-АД: дни и строки топлива добавляются, исправляются и удаляются до
 * закрытия листа; «Бақияи пас аз даромад» (остаток после возврата) не вводится — его считает сервер
 * тем же расчётом, что бланк и отчёты (FuelBalanceService). Норма — POST /calculation (расчётный
 * движок по марке и маршруту), а не упрощённая оценка по демо-нормам.
 */
type Props = {
  id: string;
  w: Waybill;
  data: WorkDaysResponse | null;
  overdue: boolean;
  canDispatch: boolean;
  canFuel: boolean;
  hasConditioner: boolean;
  act: (label: string, fn: () => Promise<unknown>) => Promise<void>;
  onError: (message: string) => void;
};

const EMPTY_DAY = { workDate: '', exitTime: '06:00', entryTime: '', odometerExit: '', odometerEntry: '', laps: '', revenue: '',
  clientTime: '', conditionerHours: '', beginPathA: '', beginPathB: '', specialWorkTime: '' };
const EMPTY_FUEL = { fuelType: '', fuelGiven: '', remainBeforeExit: '', additionalGiven: '', returned: '', coefBelow0: '', workDayId: '' };

const num = (v: string) => (v.trim() === '' ? null : Number(v));
const fmt = (v: number | null | undefined) => (v == null ? '—' : (Math.round(Number(v) * 100) / 100).toLocaleString('ru-RU'));

export default function WorkDaysFuel({ id, w, data, overdue, canDispatch, canFuel, hasConditioner, act, onError }: Props) {
  const { t } = useT();
  const days: (WorkDayRow & { fuel: FuelRecordRow[] })[] = (data?.workDays ?? []).map(v => ({ ...v.workDay, fuel: v.fuel }));
  const allFuel: FuelRecordRow[] = [...days.flatMap(d => d.fuel), ...(data?.waybillFuel ?? [])]
    .sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1));
  const td = (w.typeData ?? {}) as Record<string, unknown>;
  const isTaxi = w.waybillType === 'WB_CAR' || w.waybillType === 'WB_TAXI';
  // «Гашти ибтидоӣ» есть у маршрутных пассажирских форм: 1-АД, 1-А, 3-С «маршрутное такси».
  const routeForm = ['WB_BUS', 'WB_TROLLEYBUS', 'WB_MINIBUS'].includes(w.waybillType) || (isTaxi && td.serviceKind === 'ROUTE');
  // Время работы спецоборудования за день — грузовые формы (legacy 2-Б / 5Б-БМ work_time; сверка 25.09, B4).
  const cargoForm = ['WB_TRUCK', 'WB_TRUCK_INTL', 'WB_SPECIAL', 'WB_DANGEROUS'].includes(w.waybillType);
  const vehicleFuel = Number((w.vehicleSnapshot as Record<string, unknown> | undefined)?.fuelType ?? 0);
  const defaultFuel = w.waybillType === 'WB_TROLLEYBUS' ? '5' : vehicleFuel >= 1 && vehicleFuel <= 5 ? String(vehicleFuel) : '2';

  const daysEditable = canDispatch && (w.status === 'ACTIVE' || w.status === 'RETURNED' || overdue);
  const fuelEditable = canFuel && (!['COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED'].includes(w.status) || overdue);

  const [dayForm, setDayForm] = useState(EMPTY_DAY);
  const [editDay, setEditDay] = useState<string | null>(null);
  const [fuelForm, setFuelForm] = useState({ ...EMPTY_FUEL, fuelType: defaultFuel });
  const [editFuel, setEditFuel] = useState<string | null>(null);
  const [fuelHint, setFuelHint] = useState('');
  const [calc, setCalc] = useState<Record<string, unknown> | null>(null);

  // Остаток до выезда — из предыдущего ПЛ этого ТС по виду топлива (legacy parking_fuel_left).
  useEffect(() => {
    if (!fuelEditable || editFuel || !fuelForm.fuelType) return;
    let cancelled = false;
    wb.fuelPrefill(id, Number(fuelForm.fuelType)).then(p => {
      if (cancelled) return;
      setFuelForm(f => ({ ...f, remainBeforeExit: p.remainBeforeExit != null ? String(p.remainBeforeExit) : '' }));
      setFuelHint(p.found ? `${t('wbd.fuelprefill.from')}${p.sourceWaybillNumber ? ` (${p.sourceWaybillNumber})` : ''}` : t('wbd.fuelprefill.none'));
    }).catch(() => { if (!cancelled) setFuelHint(''); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, fuelForm.fuelType, fuelEditable, editFuel]);

  const fuelName = (type: number) => ({ 1: t('wb.fuel.petrol'), 2: t('wb.fuel.diesel'), 3: t('wb.fuel.lpg'), 4: t('wb.fuel.cng'), 5: t('wb.fuel.electro') } as Record<number, string>)[type] ?? String(type);
  const pathLabel = (v: string | null) => (v === 'begin_path_a' ? 'А' : v === 'begin_path_b' ? 'Б' : '—');

  function startEditDay(d: WorkDayRow) {
    setEditDay(d.id);
    setDayForm({
      workDate: d.workDate, exitTime: d.exitTime?.slice(0, 5) ?? '', entryTime: d.entryTime?.slice(0, 5) ?? '',
      odometerExit: d.odometerExit != null ? String(d.odometerExit) : '', odometerEntry: d.odometerEntry != null ? String(d.odometerEntry) : '',
      laps: d.laps != null ? String(d.laps) : '', revenue: d.revenue != null ? String(d.revenue) : '',
      clientTime: d.clientTime?.slice(0, 5) ?? '', conditionerHours: d.conditionerHours != null ? String(d.conditionerHours) : '',
      beginPathA: d.beginPathA ?? '', beginPathB: d.beginPathB ?? '',
      specialWorkTime: d.specialWorkTime?.slice(0, 5) ?? '',
    });
  }

  function newDayDefaults() {
    // Одометр выезда нового дня = возврат предыдущего дня (legacy mbus/taxi edit.blade), иначе выезд листа.
    const last = days[days.length - 1];
    const odo = last?.odometerEntry ?? w.odometerExit;
    return { ...EMPTY_DAY, odometerExit: odo != null ? String(odo) : '', beginPathA: routeForm ? 'begin_path_a' : '' };
  }

  async function submitDay(e: React.FormEvent) {
    e.preventDefault();
    const body = {
      workDate: dayForm.workDate,
      exitTime: dayForm.exitTime || null, entryTime: dayForm.entryTime || null,
      odometerExit: num(dayForm.odometerExit), odometerEntry: num(dayForm.odometerEntry),
      laps: num(dayForm.laps), revenue: num(dayForm.revenue),
      clientTime: isTaxi && dayForm.clientTime ? dayForm.clientTime : null,
      conditionerHours: hasConditioner ? num(dayForm.conditionerHours) : null,
      beginPathA: routeForm ? dayForm.beginPathA || null : null,
      beginPathB: routeForm ? dayForm.beginPathB || null : null,
      specialWorkTime: cargoForm && dayForm.specialWorkTime ? dayForm.specialWorkTime : null,
    };
    if (editDay) {
      await act(t('wb.act.daysaved'), () => wb.put(`/${id}/work-days/${editDay}`, body));
    } else {
      await act(t('wb.act.dayadded'), () => wb.post(`/${id}/work-days`, body));
    }
    setEditDay(null);
    setDayForm(newDayDefaults());
  }

  function startEditFuel(f: FuelRecordRow) {
    setEditFuel(f.id);
    setFuelHint('');
    setFuelForm({
      fuelType: String(f.fuelType), fuelGiven: f.fuelGiven != null ? String(f.fuelGiven) : '',
      remainBeforeExit: f.remainBeforeExit != null ? String(f.remainBeforeExit) : '',
      additionalGiven: f.additionalGiven != null ? String(f.additionalGiven) : '',
      returned: f.returned != null ? String(f.returned) : '', coefBelow0: f.coefBelow0 != null ? String(f.coefBelow0) : '',
      workDayId: f.workDayId ?? '',
    });
  }

  async function submitFuel(e: React.FormEvent) {
    e.preventDefault();
    const body = {
      fuelType: Number(fuelForm.fuelType), fuelGiven: num(fuelForm.fuelGiven), remainBeforeExit: num(fuelForm.remainBeforeExit),
      additionalGiven: num(fuelForm.additionalGiven), returned: num(fuelForm.returned), coefBelow0: num(fuelForm.coefBelow0),
      workDayId: fuelForm.workDayId || null,
    };
    if (editFuel) {
      await act(t('wb.act.fuelsaved'), () => wb.put(`/${id}/fuel/${editFuel}`, body));
    } else {
      await act(t('wb.act.fuelrecorded'), () => wb.post(`/${id}/fuel`, body));
    }
    setEditFuel(null);
    setFuelForm({ ...EMPTY_FUEL, fuelType: fuelForm.fuelType });
    setCalc(null);
  }

  async function runCalc() {
    try {
      setCalc(await wb.post<Record<string, unknown>>(`/${id}/calculation`, {}));
    } catch (e) {
      onError((e as Error).message);
    }
  }

  const showDays = days.length > 0 || daysEditable;
  const calcBranch = (calc?.passenger ?? calc?.cargo) as Record<string, unknown> | undefined;
  const calcFuels = (Array.isArray(calcBranch?.fuels) ? calcBranch!.fuels : []) as Record<string, number>[];
  const coef = calcBranch?.coefficients as Record<string, number> | undefined;

  return (
    <>
      {showDays && (
        <div className="card" data-testid="wb-workdays">
          <h2>{t('wb.workdays.h')}</h2>
          {days.length > 0 && (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ marginBottom: 12 }}>
                <thead><tr>
                  <th>{t('col.date')}</th><th>{t('wb.th.exit')}</th><th>{t('wb.th.entry')}</th><th>{t('col.odometer')}</th>
                  <th>{t('wb.th.laps')}</th><th>{t('rep.revenue')}</th>
                  {isTaxi && <th>{t('wb.f.clienttime')}</th>}
                  {routeForm && <th>{t('wb.f.beginpath')}</th>}
                  {cargoForm && <th>{t('wb.f.specialtime')}</th>}
                  <th>{t('col.fuel')}</th>
                  {daysEditable && <th />}
                </tr></thead>
                <tbody>
                  {days.map(d => (
                    <tr key={d.id}>
                      <td>{d.workDate}</td>
                      <td>{d.exitTime?.slice(0, 5) ?? '—'}</td>
                      <td>{d.entryTime?.slice(0, 5) ?? '—'}</td>
                      <td>{d.odometerExit ?? '—'} → {d.odometerEntry ?? '—'}</td>
                      <td>{d.laps ?? '—'}</td>
                      <td>{fmt(d.revenue)}</td>
                      {isTaxi && <td>{d.clientTime?.slice(0, 5) ?? '—'}</td>}
                      {routeForm && <td>{pathLabel(d.beginPathA)} + {pathLabel(d.beginPathB)}</td>}
                      {cargoForm && <td>{d.specialWorkTime?.slice(0, 5) ?? '—'}</td>}
                      <td>{d.fuel.length ? d.fuel.map(f => `${fuelName(f.fuelType)} ${fmt(f.fuelGiven)} л`).join('; ') : '—'}</td>
                      {daysEditable && (
                        <td style={{ whiteSpace: 'nowrap' }}>
                          <button type="button" className="btn secondary" style={{ padding: '3px 8px' }} title={t('btn.edit')} onClick={() => startEditDay(d)}>✎</button>{' '}
                          <button type="button" className="btn secondary" style={{ padding: '3px 8px' }} title={t('btn.delete')}
                            onClick={() => { if (window.confirm(t('wb.day.delconfirm'))) void act(t('wb.act.daydeleted'), () => wb.del(`/${id}/work-days/${d.id}`)); }}>×</button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {daysEditable && (
            <form className="grid" onSubmit={submitDay}>
              <div><label>{t('col.date')}</label><input type="date" required value={dayForm.workDate} onChange={e => setDayForm({ ...dayForm, workDate: e.target.value })} /></div>
              <div><label>{t('wb.f.exitentry')}</label>
                <span style={{ display: 'flex', gap: 6 }}>
                  <input type="time" value={dayForm.exitTime} onChange={e => setDayForm({ ...dayForm, exitTime: e.target.value })} />
                  <input type="time" value={dayForm.entryTime} onChange={e => setDayForm({ ...dayForm, entryTime: e.target.value })} />
                </span>
              </div>
              <div><label>{t('wb.f.odoexitentry')}</label>
                <span style={{ display: 'flex', gap: 6 }}>
                  <input type="number" min={0} value={dayForm.odometerExit} onChange={e => setDayForm({ ...dayForm, odometerExit: e.target.value })} />
                  <input type="number" min={0} value={dayForm.odometerEntry} onChange={e => setDayForm({ ...dayForm, odometerEntry: e.target.value })} />
                </span>
              </div>
              <div><label>{t('wb.f.lapsrevenue')}</label>
                <span style={{ display: 'flex', gap: 6 }}>
                  <input type="number" min={0} max={99} value={dayForm.laps} onChange={e => setDayForm({ ...dayForm, laps: e.target.value })} />
                  <input type="number" min={0} step="0.01" value={dayForm.revenue} onChange={e => setDayForm({ ...dayForm, revenue: e.target.value })} />
                </span>
              </div>
              {isTaxi && (
                <div><label>{t('wb.f.clienttime')}</label><input type="time" value={dayForm.clientTime} onChange={e => setDayForm({ ...dayForm, clientTime: e.target.value })} /></div>
              )}
              {cargoForm && (
                <div><label>{t('wb.f.specialtime')}</label><input type="time" value={dayForm.specialWorkTime} data-testid="wd-special" onChange={e => setDayForm({ ...dayForm, specialWorkTime: e.target.value })} /></div>
              )}
              {hasConditioner && (
                <div><label>{t('wb.f.condhours')}</label><input type="number" min={0} step="0.1" value={dayForm.conditionerHours} onChange={e => setDayForm({ ...dayForm, conditionerHours: e.target.value })} /></div>
              )}
              {routeForm && (
                <div><label>{t('wb.f.beginpath')}</label>
                  <span style={{ display: 'flex', gap: 6 }}>
                    <select value={dayForm.beginPathA} onChange={e => setDayForm({ ...dayForm, beginPathA: e.target.value })} title={t('wb.f.beginpath.start')}>
                      <option value="">—</option><option value="begin_path_a">А</option><option value="begin_path_b">Б</option>
                    </select>
                    <select value={dayForm.beginPathB} onChange={e => setDayForm({ ...dayForm, beginPathB: e.target.value })} title={t('wb.f.beginpath.end')}>
                      <option value="">—</option><option value="begin_path_a">А</option><option value="begin_path_b">Б</option>
                    </select>
                  </span>
                </div>
              )}
              <div className="full" style={{ display: 'flex', gap: 8 }}>
                <button className="btn secondary" type="submit">{editDay ? t('btn.save') : t('wb.btn.addday')}</button>
                {editDay && <button type="button" className="btn secondary" onClick={() => { setEditDay(null); setDayForm(newDayDefaults()); }}>{t('btn.cancel')}</button>}
                {!editDay && days.length === 0 && dayForm.odometerExit === '' && (
                  <button type="button" className="btn secondary" onClick={() => setDayForm(newDayDefaults())}>{t('wb.day.prefill')}</button>
                )}
              </div>
            </form>
          )}
        </div>
      )}

      {(allFuel.length > 0 || fuelEditable) && (
        <div className="card" data-testid="wb-fuel">
          <h2>{t('wb.fuel.h')}</h2>
          {allFuel.length > 0 && (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ marginBottom: 12 }}>
                <thead><tr>
                  <th>{t('rep.col.fueltype')}</th><th>{t('wb.f.workday')}</th><th>{t('rep.col.given')}</th>
                  <th>{t('wb.f.remainbefore')}</th><th>{t('wbd.fueladd')}</th><th>{t('wbd.fuelreturn')}</th>
                  <th>{t('wbd.fuelcoef0')}</th><th>{t('wb.fuel.remainentry')}</th>
                  {fuelEditable && <th />}
                </tr></thead>
                <tbody>
                  {allFuel.map(f => (
                    <tr key={f.id}>
                      <td>{fuelName(f.fuelType)}</td>
                      <td>{f.workDayId ? (days.find(d => d.id === f.workDayId)?.workDate ?? '—') : t('wb.f.workday.whole')}</td>
                      <td>{fmt(f.fuelGiven)}</td><td>{fmt(f.remainBeforeExit)}</td><td>{fmt(f.additionalGiven)}</td>
                      <td>{fmt(f.returned)}</td><td>{fmt(f.coefBelow0)}</td><td><b>{fmt(f.remainEntry)}</b></td>
                      {fuelEditable && (
                        <td style={{ whiteSpace: 'nowrap' }}>
                          <button type="button" className="btn secondary" style={{ padding: '3px 8px' }} title={t('btn.edit')} onClick={() => startEditFuel(f)}>✎</button>{' '}
                          <button type="button" className="btn secondary" style={{ padding: '3px 8px' }} title={t('btn.delete')}
                            onClick={() => { if (window.confirm(t('wb.fuel.delconfirm'))) void act(t('wb.act.fueldeleted'), () => wb.del(`/${id}/fuel/${f.id}`)); }}>×</button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
              <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 10 }}>{t('wb.fuel.remainhint')}</div>
            </div>
          )}
          {fuelEditable && (
            <form className="grid" onSubmit={submitFuel}>
              {days.length > 0 && (
                <div><label>{t('wb.f.workday')}</label>
                  <select value={fuelForm.workDayId} onChange={e => setFuelForm({ ...fuelForm, workDayId: e.target.value })}>
                    <option value="">{t('wb.f.workday.whole')}</option>
                    {days.map(d => <option key={d.id} value={d.id}>{d.workDate}</option>)}
                  </select>
                </div>
              )}
              <div><label>{t('rep.col.fueltype')}</label>
                <select value={fuelForm.fuelType} disabled={!!editFuel} onChange={e => setFuelForm({ ...fuelForm, fuelType: e.target.value })}>
                  {w.waybillType === 'WB_TROLLEYBUS'
                    ? <option value="5">{t('wb.fuel.electro')}</option>
                    : (<><option value="1">{t('wb.fuel.petrol')}</option><option value="2">{t('wb.fuel.diesel')}</option>
                      <option value="3">{t('wb.fuel.lpg')}</option><option value="4">{t('wb.fuel.cng')}</option></>)}
                </select>
              </div>
              <div><label>{t('rep.col.given')}</label><input type="number" step="0.1" min={0} required value={fuelForm.fuelGiven} onChange={e => setFuelForm({ ...fuelForm, fuelGiven: e.target.value })} /></div>
              <div><label>{t('wb.f.remainbefore')}</label><input type="number" step="0.1" min={0} value={fuelForm.remainBeforeExit} onChange={e => setFuelForm({ ...fuelForm, remainBeforeExit: e.target.value })} /></div>
              <div><label>{t('wbd.fueladd')}</label><input type="number" step="0.1" min={0} value={fuelForm.additionalGiven} onChange={e => setFuelForm({ ...fuelForm, additionalGiven: e.target.value })} /></div>
              <div><label>{t('wbd.fuelreturn')}</label><input type="number" step="0.1" min={0} value={fuelForm.returned} onChange={e => setFuelForm({ ...fuelForm, returned: e.target.value })} /></div>
              <div><label>{t('wbd.fuelcoef0')}</label><input type="number" step="0.1" min={0} value={fuelForm.coefBelow0} onChange={e => setFuelForm({ ...fuelForm, coefBelow0: e.target.value })} /></div>
              {fuelHint && <div className="full" style={{ fontSize: 12, color: 'var(--muted)' }}>{fuelHint}</div>}
              <div className="full" style={{ display: 'flex', gap: 8 }}>
                <button className="btn secondary" type="submit">{editFuel ? t('btn.save') : t('wb.btn.recordfuel')}</button>
                {editFuel && <button type="button" className="btn secondary" onClick={() => { setEditFuel(null); setFuelForm({ ...EMPTY_FUEL, fuelType: defaultFuel }); }}>{t('btn.cancel')}</button>}
              </div>
            </form>
          )}
        </div>
      )}

      {/* Норма — тем же движком, что бланк и отчёты (марка, маршрут, коэффициенты legacy). */}
      {['RETURNED', 'COMPLETED', 'ARCHIVED'].includes(w.status) && w.waybillType !== 'WB_TROLLEYBUS' && (
        <div className="card">
          <h2>{t('wb.fuelnorm.h')}</h2>
          {!calc ? (
            <button className="btn secondary" onClick={runCalc}>{t('wb.btn.calcnorm')}</button>
          ) : (
            <>
              <dl className="kv">
                <dt>{t('col.mileage')}</dt><dd>{String(calcBranch?.distanceKm ?? '—')} {t('unit.km')}</dd>
                <dt>{t('dict.sec.coefficients')}</dt>
                <dd>{coef && coef.k ? `K = ${coef.k} (×${coef.multiplier})` : t('wb.notapplied')}</dd>
                <dt>{t('wb.norm')}</dt><dd><b>{fmt(Number(calcBranch?.totalNormLiters ?? 0))} л</b></dd>
              </dl>
              {calcFuels.length > 0 && (
                <table style={{ marginTop: 10 }}>
                  <thead><tr><th>{t('rep.col.fueltype')}</th><th>{t('rep.col.given')}</th><th>{t('wbd.fueladd')}</th><th>{t('wb.norm')}</th><th>{t('wb.f.remainbefore')}</th><th>{t('wb.fuel.remainentry')}</th><th>{t('wb.deviation')}</th></tr></thead>
                  <tbody>
                    {calcFuels.map(f => (
                      <tr key={f.fuelId}>
                        <td>{fuelName(f.fuelId)}</td><td>{fmt(f.given)}</td><td>{fmt(f.additional)}</td><td><b>{fmt(f.normLiters)}</b></td>
                        <td>{fmt(f.remainBeforeExit)}</td><td>{fmt(f.remainEntry)}</td>
                        <td style={{ color: f.normLiters - f.given < 0 ? 'var(--red)' : 'var(--green)' }}>{fmt(f.normLiters - f.given)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {Array.isArray(calc.notes) && (calc.notes as string[]).length > 0 && (
                <ul style={{ fontSize: 12, color: 'var(--muted)', marginTop: 10 }}>{(calc.notes as string[]).map((n, i) => <li key={i}>{n}</li>)}</ul>
              )}
            </>
          )}
        </div>
      )}
    </>
  );
}
