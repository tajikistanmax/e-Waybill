'use client';

import { useCallback, useEffect, useState } from 'react';
import { authHeaders, STATUS_LABELS } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type Summary = {
  period: { from: string; to: string };
  totals: { waybills: number; completed: number; cancelled: number; active: number; distanceKm: number; fuelGivenLiters: number; revenue: number };
  byStatus: Record<string, number>;
  byType: Record<string, number>;
};

type JournalRow = {
  number: string | null; waybillType: string; vehicleRegNumber: string; driverName: string;
  status: string; validFrom: string | null; validTo: string | null;
  odometerExit: number | null; odometerEntry: number | null;
};

type GroupRow = {
  driverRma?: string; fullName?: string;
  vehicleRegNumber?: string;
  waybills: number; completed: number; distanceKm: number;
};
type FuelRow = { fuelType: number; fuelName?: string; given: number; remainEnd: number };

const FUEL_NAMES: Record<number, string> = { 1: 'Бензин', 2: 'Дизель', 3: 'Газ сжиженный', 4: 'Газ природный', 5: 'Электро' };

/** Цвет полосы для статус-бара — по палитре бейджей статусов. */
const BAR_COLOR: Record<string, string> = {
  gray: '#94a3b8', blue: 'var(--blue-600)', amber: 'var(--amber)',
  green: 'var(--green)', red: 'var(--red)', teal: 'var(--cyan)',
};

async function getJson<T>(url: string): Promise<T> {
  const r = await fetch(url, { headers: authHeaders() });
  if (!r.ok) {
    const p = await r.json().catch(() => null);
    throw new Error(p?.detail ?? p?.title ?? `Ошибка ${r.status}`);
  }
  return r.json();
}

/** Экранирование ячейки CSV (разделитель «;» — как ждёт Excel в RU-локали). */
function csvCell(v: unknown): string {
  const s = v == null ? '' : String(v);
  return /[";\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}

/** Скачать CSV с UTF-8 BOM — Excel открывает напрямую и корректно показывает кириллицу. */
function downloadCsv(filename: string, rows: unknown[][]) {
  const text = rows.map(r => r.map(csvCell).join(';')).join('\r\n');
  const blob = new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}

function today(offsetDays = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

/** Горизонтальный бар-ряд для распределений (по статусам / по типам). */
function BarRow({ label, value, max, color }: { label: string; value: number; max: number; color: string }) {
  const pct = max > 0 ? Math.round((value / max) * 100) : 0;
  return (
    <div style={{ marginBottom: 13 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}>
        <span style={{ color: 'var(--ink-soft)' }}>{label}</span>
        <b style={{ color: 'var(--ink)', fontVariantNumeric: 'tabular-nums' }}>{value.toLocaleString('ru-RU')}</b>
      </div>
      <div style={{ height: 8, background: 'var(--line-soft)', borderRadius: 999, overflow: 'hidden' }}>
        <div style={{ width: `${pct}%`, height: '100%', background: color, borderRadius: 999, transition: 'width .3s' }} />
      </div>
    </div>
  );
}

/** Отчёты (раздел 8.13 ТЗ): сводка, журнал диспетчера, по водителям/ТС/топливу. */
export default function ReportsPage() {
  const { t, tType, tStatus } = useT();
  const [from, setFrom] = useState(today(-30));
  const [to, setTo] = useState(today());
  const [journalDate, setJournalDate] = useState(today());
  const [summary, setSummary] = useState<Summary | null>(null);
  const [journal, setJournal] = useState<JournalRow[]>([]);
  const [byDriver, setByDriver] = useState<GroupRow[]>([]);
  const [byVehicle, setByVehicle] = useState<GroupRow[]>([]);
  const [fuel, setFuel] = useState<FuelRow[]>([]);
  const [error, setError] = useState('');
  const [tab, setTab] = useState<'summary' | 'journal' | 'driver' | 'vehicle' | 'fuel'>('summary');

  const load = useCallback(async () => {
    setError('');
    try {
      if (tab === 'summary') setSummary(await getJson(`/wb-api/api/v1/reports/summary?from=${from}&to=${to}`));
      if (tab === 'journal') setJournal(await getJson(`/wb-api/api/v1/reports/dispatcher-journal?date=${journalDate}`));
      if (tab === 'driver') setByDriver(await getJson(`/wb-api/api/v1/reports/by-driver?from=${from}&to=${to}`));
      if (tab === 'vehicle') setByVehicle(await getJson(`/wb-api/api/v1/reports/by-vehicle?from=${from}&to=${to}`));
      if (tab === 'fuel') setFuel(await getJson(`/wb-api/api/v1/reports/fuel?from=${from}&to=${to}`));
    } catch (e) {
      setError((e as Error).message);
    }
  }, [tab, from, to, journalDate]);

  useEffect(() => { load(); }, [load]);

  const hasData = tab === 'summary' ? !!summary
    : tab === 'journal' ? journal.length > 0
    : tab === 'driver' ? byDriver.length > 0
    : tab === 'vehicle' ? byVehicle.length > 0
    : fuel.length > 0;

  /** Экспорт текущей вкладки в CSV (открывается в Excel). */
  function exportCurrent() {
    const stripType = (x: string) => tType(x).replace(/\s*\(.*\)/, '');
    if (tab === 'summary' && summary) {
      const rows: unknown[][] = [['Показатель', 'Значение'],
        ['Путевых листов', summary.totals.waybills], ['Завершено', summary.totals.completed],
        ['Активных', summary.totals.active], ['Аннулировано', summary.totals.cancelled],
        ['Пробег, км', summary.totals.distanceKm], ['Топливо выдано, л', summary.totals.fuelGivenLiters],
        ['Выручка, сомони', summary.totals.revenue], [], ['По статусам', '']];
      Object.entries(summary.byStatus).forEach(([k, v]) => rows.push([tStatus(k), v]));
      rows.push([], ['По типам', '']);
      Object.entries(summary.byType).forEach(([k, v]) => rows.push([stripType(k), v]));
      downloadCsv(`отчёт-сводка-${from}_${to}.csv`, rows);
    } else if (tab === 'journal') {
      const rows: unknown[][] = [['Номер', 'Тип', 'ТС', 'Водитель', 'Статус', 'Одометр выезд', 'Одометр возврат']];
      journal.forEach(r => rows.push([r.number ?? '', stripType(r.waybillType), r.vehicleRegNumber, r.driverName, tStatus(r.status), r.odometerExit ?? '', r.odometerEntry ?? '']));
      downloadCsv(`отчёт-журнал-${journalDate}.csv`, rows);
    } else if (tab === 'driver') {
      const rows: unknown[][] = [['Водитель', 'РМА', 'Путевых листов', 'Завершено', 'Пробег, км']];
      byDriver.forEach(r => rows.push([r.fullName ?? '', r.driverRma ?? '', r.waybills, r.completed, r.distanceKm]));
      downloadCsv(`отчёт-по-водителям-${from}_${to}.csv`, rows);
    } else if (tab === 'vehicle') {
      const rows: unknown[][] = [['ТС / госномер', 'Путевых листов', 'Завершено', 'Пробег, км']];
      byVehicle.forEach(r => rows.push([r.vehicleRegNumber ?? '', r.waybills, r.completed, r.distanceKm]));
      downloadCsv(`отчёт-по-тс-${from}_${to}.csv`, rows);
    } else if (tab === 'fuel') {
      const rows: unknown[][] = [['Топливо', 'Выдано, л', 'Остаток, л']];
      fuel.forEach(r => rows.push([r.fuelName ?? FUEL_NAMES[r.fuelType] ?? r.fuelType, r.given, r.remainEnd]));
      downloadCsv(`отчёт-топливо-${from}_${to}.csv`, rows);
    }
  }

  const statusMax = summary ? Math.max(1, ...Object.values(summary.byStatus)) : 1;
  const typeMax = summary ? Math.max(1, ...Object.values(summary.byType)) : 1;

  const KPIS = summary ? [
    { label: t('kpi.total'), value: summary.totals.waybills, icon: P.doc, cls: 'ic-blue' },
    { label: t('kpi.done'), value: summary.totals.completed, icon: P.check, cls: 'ic-green' },
    { label: t('st.active'), value: summary.totals.active, icon: P.car, cls: 'ic-cyan' },
    { label: t('kpi.cancel'), value: summary.totals.cancelled, icon: P.alert, cls: 'ic-red' },
  ] : [];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.reports')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('rep.lead')}</div>
        </div>
      </div>

      <div className="toolbar">
        <button className={`btn ${tab === 'summary' ? '' : 'secondary'}`} onClick={() => setTab('summary')}>{t('rep.tab.summary')}</button>
        <button className={`btn ${tab === 'journal' ? '' : 'secondary'}`} onClick={() => setTab('journal')}>{t('rep.tab.journal')}</button>
        <button className={`btn ${tab === 'driver' ? '' : 'secondary'}`} onClick={() => setTab('driver')}>{t('rep.tab.driver')}</button>
        <button className={`btn ${tab === 'vehicle' ? '' : 'secondary'}`} onClick={() => setTab('vehicle')}>{t('rep.tab.vehicle')}</button>
        <button className={`btn ${tab === 'fuel' ? '' : 'secondary'}`} onClick={() => setTab('fuel')}>{t('rep.tab.fuel')}</button>
        <span className="spacer" />
        {tab === 'journal' ? (
          <input type="date" style={{ width: 170 }} value={journalDate} onChange={e => setJournalDate(e.target.value)} />
        ) : (
          <>
            <input type="date" style={{ width: 170 }} value={from} onChange={e => setFrom(e.target.value)} />
            <span>—</span>
            <input type="date" style={{ width: 170 }} value={to} onChange={e => setTo(e.target.value)} />
          </>
        )}
        <button className="btn secondary" onClick={exportCurrent} disabled={!hasData} title={t('rep.export.hint')}>
          <Icon d={P.chart} cls="" style={{ width: 15, height: 15 }} /> {t('rep.export')}
        </button>
      </div>
      {error && <div className="error">{error}</div>}

      {tab === 'summary' && summary && (
        <>
          {/* KPI */}
          <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
            {KPIS.map(k => (
              <div className="kpi" key={k.label}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                  <span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span>
                  <div style={{ minWidth: 0 }}>
                    <div className="k-label">{k.label}</div>
                    <div className="k-value" style={{ marginTop: 4 }}>{k.value.toLocaleString('ru-RU')}</div>
                  </div>
                </div>
              </div>
            ))}
          </div>

          {/* Распределения */}
          <div className="grid-2">
            <div className="card">
              <h2>{t('rep.bystatus')}</h2>
              {Object.entries(summary.byStatus).length === 0
                ? <p style={{ color: 'var(--muted)', fontSize: 13 }}>{t('rep.nodata')}</p>
                : Object.entries(summary.byStatus)
                    .sort((a, b) => b[1] - a[1])
                    .map(([k, v]) => (
                      <BarRow key={k} label={tStatus(k)} value={v} max={statusMax} color={BAR_COLOR[STATUS_LABELS[k]?.color ?? 'blue'] ?? 'var(--blue-600)'} />
                    ))}
            </div>
            <div className="card">
              <h2>{t('dash.bytype')}</h2>
              {Object.entries(summary.byType).length === 0
                ? <p style={{ color: 'var(--muted)', fontSize: 13 }}>{t('rep.nodata')}</p>
                : Object.entries(summary.byType)
                    .sort((a, b) => b[1] - a[1])
                    .map(([k, v]) => (
                      <BarRow key={k} label={tType(k).replace(/\s*\(.*\)/, '')} value={v} max={typeMax} color="var(--blue-600)" />
                    ))}
            </div>
          </div>

          {/* Показатели периода */}
          <div className="card">
            <h2>{t('rep.periodmetrics')}</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14 }}>
              <div style={{ background: 'var(--blue-050)', borderRadius: 11, padding: 16 }}>
                <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{t('col.mileage')}</div>
                <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--blue-700)', marginTop: 4 }}>{summary.totals.distanceKm.toLocaleString('ru-RU')} <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--muted)' }}>{t('unit.km')}</span></div>
              </div>
              <div style={{ background: 'var(--cyan-050)', borderRadius: 11, padding: 16 }}>
                <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{t('rep.fuelgiven')}</div>
                <div style={{ fontSize: 22, fontWeight: 800, color: '#0b7f97', marginTop: 4 }}>{summary.totals.fuelGivenLiters.toLocaleString('ru-RU')} <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--muted)' }}>{t('unit.l')}</span></div>
              </div>
              <div style={{ background: 'var(--green-050)', borderRadius: 11, padding: 16 }}>
                <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{t('rep.revenue')}</div>
                <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--green)', marginTop: 4 }}>{summary.totals.revenue.toLocaleString('ru-RU')} <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--muted)' }}>{t('unit.somoni')}</span></div>
              </div>
            </div>
          </div>
        </>
      )}

      {tab === 'journal' && (
        <div className="card">
          <h2>{t('rep.journal.title')} {journalDate}</h2>
          <table>
            <thead><tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.vehicle')}</th><th>{t('col.driver')}</th><th>{t('col.status')}</th><th>{t('rep.col.odoexit')}</th><th>{t('rep.col.odoentry')}</th></tr></thead>
            <tbody>
              {journal.map((r, i) => {
                const s = STATUS_LABELS[r.status] ?? { label: r.status, color: 'gray' };
                return (
                  <tr key={i}>
                    <td><span className="number">{r.number ?? '—'}</span></td>
                    <td>{tType(r.waybillType).replace(/\s*\(.*\)/, '')}</td>
                    <td>{r.vehicleRegNumber}</td>
                    <td>{r.driverName}</td>
                    <td><span className={`badge ${s.color}`}>{tStatus(r.status)}</span></td>
                    <td>{r.odometerExit ?? '—'}</td>
                    <td>{r.odometerEntry ?? '—'}</td>
                  </tr>
                );
              })}
              {journal.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {(tab === 'driver' || tab === 'vehicle') && (
        <div className="card">
          <h2>{tab === 'driver' ? t('rep.tab.driver') : t('rep.byvehicle')}</h2>
          <table>
            <thead><tr><th>{tab === 'driver' ? t('col.driver') : t('col.vehicle')}</th><th>{t('rep.col.wbtotal')}</th><th>{t('kpi.done')}</th><th>{t('drv.kpi.km')}</th></tr></thead>
            <tbody>
              {(tab === 'driver' ? byDriver : byVehicle).map((r, i) => (
                <tr key={i}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{tab === 'driver' ? `${r.fullName ?? ''} (${r.driverRma ?? ''})` : r.vehicleRegNumber}</td>
                  <td>{r.waybills}</td><td>{r.completed}</td><td>{r.distanceKm.toLocaleString('ru-RU')}</td>
                </tr>
              ))}
              {(tab === 'driver' ? byDriver : byVehicle).length === 0 && <tr><td colSpan={4} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'fuel' && (
        <div className="card">
          <h2>{t('rep.fuel.h')}</h2>
          <table>
            <thead><tr><th>{t('rep.col.fueltype')}</th><th>{t('rep.col.given')}</th><th>{t('rep.col.remain')}</th></tr></thead>
            <tbody>
              {fuel.map((r, i) => (
                <tr key={i}><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{r.fuelName ?? FUEL_NAMES[r.fuelType] ?? r.fuelType}</td><td>{r.given.toLocaleString('ru-RU')}</td><td>{r.remainEnd.toLocaleString('ru-RU')}</td></tr>
              ))}
              {fuel.length === 0 && <tr><td colSpan={3} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
