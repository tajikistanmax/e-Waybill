'use client';

import { useCallback, useEffect, useState } from 'react';
import { authHeaders, STATUS_LABELS, TYPE_LABELS } from '@/lib/api';
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

  const statusMax = summary ? Math.max(1, ...Object.values(summary.byStatus)) : 1;
  const typeMax = summary ? Math.max(1, ...Object.values(summary.byType)) : 1;

  const KPIS = summary ? [
    { label: 'Путевых листов', value: summary.totals.waybills, icon: P.doc, cls: 'ic-blue' },
    { label: 'Завершено', value: summary.totals.completed, icon: P.check, cls: 'ic-green' },
    { label: 'Активно', value: summary.totals.active, icon: P.car, cls: 'ic-cyan' },
    { label: 'Аннулировано', value: summary.totals.cancelled, icon: P.alert, cls: 'ic-red' },
  ] : [];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>Отчёты и аналитика</h1>
          <div className="page-lead" style={{ margin: 0 }}>Сводка, журнал диспетчера и отчёты по водителям, транспорту и топливу (ҳисоботҳо)</div>
        </div>
      </div>

      <div className="toolbar">
        <button className={`btn ${tab === 'summary' ? '' : 'secondary'}`} onClick={() => setTab('summary')}>Сводка</button>
        <button className={`btn ${tab === 'journal' ? '' : 'secondary'}`} onClick={() => setTab('journal')}>Журнал диспетчера</button>
        <button className={`btn ${tab === 'driver' ? '' : 'secondary'}`} onClick={() => setTab('driver')}>По водителям</button>
        <button className={`btn ${tab === 'vehicle' ? '' : 'secondary'}`} onClick={() => setTab('vehicle')}>По транспорту</button>
        <button className={`btn ${tab === 'fuel' ? '' : 'secondary'}`} onClick={() => setTab('fuel')}>Топливо</button>
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
              <h2>Путевые листы по статусам</h2>
              {Object.entries(summary.byStatus).length === 0
                ? <p style={{ color: 'var(--muted)', fontSize: 13 }}>Нет данных за период</p>
                : Object.entries(summary.byStatus)
                    .sort((a, b) => b[1] - a[1])
                    .map(([k, v]) => (
                      <BarRow key={k} label={STATUS_LABELS[k]?.label ?? k} value={v} max={statusMax} color={BAR_COLOR[STATUS_LABELS[k]?.color ?? 'blue'] ?? 'var(--blue-600)'} />
                    ))}
            </div>
            <div className="card">
              <h2>Путевые листы по типам</h2>
              {Object.entries(summary.byType).length === 0
                ? <p style={{ color: 'var(--muted)', fontSize: 13 }}>Нет данных за период</p>
                : Object.entries(summary.byType)
                    .sort((a, b) => b[1] - a[1])
                    .map(([k, v]) => (
                      <BarRow key={k} label={(TYPE_LABELS[k] ?? k).replace(/\s*\(.*\)/, '')} value={v} max={typeMax} color="var(--blue-600)" />
                    ))}
            </div>
          </div>

          {/* Показатели периода */}
          <div className="card">
            <h2>Показатели за период</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14 }}>
              <div style={{ background: 'var(--blue-050)', borderRadius: 11, padding: 16 }}>
                <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>Пробег</div>
                <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--blue-700)', marginTop: 4 }}>{summary.totals.distanceKm.toLocaleString('ru-RU')} <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--muted)' }}>км</span></div>
              </div>
              <div style={{ background: 'var(--cyan-050)', borderRadius: 11, padding: 16 }}>
                <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>Топливо выдано</div>
                <div style={{ fontSize: 22, fontWeight: 800, color: '#0b7f97', marginTop: 4 }}>{summary.totals.fuelGivenLiters.toLocaleString('ru-RU')} <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--muted)' }}>л</span></div>
              </div>
              <div style={{ background: 'var(--green-050)', borderRadius: 11, padding: 16 }}>
                <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>Выручка</div>
                <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--green)', marginTop: 4 }}>{summary.totals.revenue.toLocaleString('ru-RU')} <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--muted)' }}>сомони</span></div>
              </div>
            </div>
          </div>
        </>
      )}

      {tab === 'journal' && (
        <div className="card">
          <h2>Журнал диспетчера (дафтари қайди танзимгар) за {journalDate}</h2>
          <table>
            <thead><tr><th>Номер</th><th>Тип</th><th>ТС</th><th>Водитель</th><th>Статус</th><th>Одометр выезд</th><th>Одометр возврат</th></tr></thead>
            <tbody>
              {journal.map((r, i) => {
                const s = STATUS_LABELS[r.status] ?? { label: r.status, color: 'gray' };
                return (
                  <tr key={i}>
                    <td><span className="number">{r.number ?? '—'}</span></td>
                    <td>{(TYPE_LABELS[r.waybillType] ?? r.waybillType).replace(/\s*\(.*\)/, '')}</td>
                    <td>{r.vehicleRegNumber}</td>
                    <td>{r.driverName}</td>
                    <td><span className={`badge ${s.color}`}>{s.label}</span></td>
                    <td>{r.odometerExit ?? '—'}</td>
                    <td>{r.odometerEntry ?? '—'}</td>
                  </tr>
                );
              })}
              {journal.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Записей нет</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {(tab === 'driver' || tab === 'vehicle') && (
        <div className="card">
          <h2>{tab === 'driver' ? 'По водителям' : 'По транспортным средствам'}</h2>
          <table>
            <thead><tr><th>{tab === 'driver' ? 'Водитель' : 'ТС'}</th><th>ПЛ всего</th><th>Завершено</th><th>Пробег, км</th></tr></thead>
            <tbody>
              {(tab === 'driver' ? byDriver : byVehicle).map((r, i) => (
                <tr key={i}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{tab === 'driver' ? `${r.fullName ?? ''} (${r.driverRma ?? ''})` : r.vehicleRegNumber}</td>
                  <td>{r.waybills}</td><td>{r.completed}</td><td>{r.distanceKm.toLocaleString('ru-RU')}</td>
                </tr>
              ))}
              {(tab === 'driver' ? byDriver : byVehicle).length === 0 && <tr><td colSpan={4} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Записей нет</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'fuel' && (
        <div className="card">
          <h2>Отчёт по топливу (ҳисоботи сузишворӣ)</h2>
          <table>
            <thead><tr><th>Вид топлива</th><th>Выдано, л</th><th>Остаток на конец, л</th></tr></thead>
            <tbody>
              {fuel.map((r, i) => (
                <tr key={i}><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{r.fuelName ?? FUEL_NAMES[r.fuelType] ?? r.fuelType}</td><td>{r.given.toLocaleString('ru-RU')}</td><td>{r.remainEnd.toLocaleString('ru-RU')}</td></tr>
              ))}
              {fuel.length === 0 && <tr><td colSpan={3} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Записей нет</td></tr>}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
