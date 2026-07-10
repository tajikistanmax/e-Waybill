'use client';

import { useCallback, useEffect, useState } from 'react';
import { authHeaders, STATUS_LABELS, TYPE_LABELS } from '@/lib/api';

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

  return (
    <>
      <h1>Отчёты (ҳисоботҳо)</h1>
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
          <div className="card">
            <h2>Итоги за период</h2>
            <dl className="kv">
              <dt>Путевых листов</dt><dd><b>{summary.totals.waybills}</b> (завершено {summary.totals.completed}, аннулировано {summary.totals.cancelled}, активно {summary.totals.active})</dd>
              <dt>Пробег</dt><dd>{summary.totals.distanceKm.toLocaleString('ru-RU')} км</dd>
              <dt>Топливо выдано</dt><dd>{summary.totals.fuelGivenLiters.toLocaleString('ru-RU')} л</dd>
              <dt>Выручка</dt><dd>{summary.totals.revenue.toLocaleString('ru-RU')} сомони</dd>
            </dl>
          </div>
          <div className="card">
            <h2>По статусам</h2>
            <table><tbody>
              {Object.entries(summary.byStatus).map(([k, v]) => (
                <tr key={k}><td>{STATUS_LABELS[k]?.label ?? k}</td><td style={{ textAlign: 'right' }}><b>{v}</b></td></tr>
              ))}
            </tbody></table>
          </div>
          <div className="card">
            <h2>По типам</h2>
            <table><tbody>
              {Object.entries(summary.byType).map(([k, v]) => (
                <tr key={k}><td>{TYPE_LABELS[k] ?? k}</td><td style={{ textAlign: 'right' }}><b>{v}</b></td></tr>
              ))}
            </tbody></table>
          </div>
        </>
      )}

      {tab === 'journal' && (
        <div className="card">
          <h2>Журнал диспетчера (дафтари қайди танзимгар) за {journalDate}</h2>
          <table>
            <thead><tr><th>Номер</th><th>Тип</th><th>ТС</th><th>Водитель</th><th>Статус</th><th>Одометр выезд</th><th>Одометр возврат</th></tr></thead>
            <tbody>
              {journal.map((r, i) => (
                <tr key={i}>
                  <td className="number">{r.number ?? '—'}</td>
                  <td>{TYPE_LABELS[r.waybillType] ?? r.waybillType}</td>
                  <td>{r.vehicleRegNumber}</td>
                  <td>{r.driverName}</td>
                  <td>{STATUS_LABELS[r.status]?.label ?? r.status}</td>
                  <td>{r.odometerExit ?? '—'}</td>
                  <td>{r.odometerEntry ?? '—'}</td>
                </tr>
              ))}
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
                  <td>{tab === 'driver' ? `${r.fullName ?? ''} (${r.driverRma ?? ''})` : r.vehicleRegNumber}</td>
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
                <tr key={i}><td>{r.fuelName ?? FUEL_NAMES[r.fuelType] ?? r.fuelType}</td><td>{r.given.toLocaleString('ru-RU')}</td><td>{r.remainEnd.toLocaleString('ru-RU')}</td></tr>
              ))}
              {fuel.length === 0 && <tr><td colSpan={3} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Записей нет</td></tr>}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
