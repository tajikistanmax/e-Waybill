'use client';

import { useCallback, useEffect, useState } from 'react';
import { authHeaders } from '@/lib/api';
import { Icon, P } from '../icons';

type Row = Record<string, unknown>;
type Tab = 'routes' | 'clients' | 'fuel-norms' | 'coefficients' | 'tariffs';

const SECTIONS: { key: Tab; label: string; desc: string; icon: string; cls: string }[] = [
  { key: 'routes', label: 'Маршруты', desc: 'Хатсайрҳо', icon: P.route, cls: 'ic-blue' },
  { key: 'clients', label: 'Клиенты', desc: 'Мизоҷҳо', icon: P.building, cls: 'ic-cyan' },
  { key: 'fuel-norms', label: 'Нормы расхода', desc: 'л/100 км', icon: P.car, cls: 'ic-purple' },
  { key: 'coefficients', label: 'Коэффициенты', desc: 'Множители расхода', icon: P.chart, cls: 'ic-amber' },
  { key: 'tariffs', label: 'Нархнома', desc: 'Тарифы, сомони/км', icon: P.doc, cls: 'ic-green' },
];

const TT: Record<number, string> = { 1: 'Автобус', 2: 'Троллейбус', 3: 'Микроавтобус', 4: 'Легковой', 5: 'Грузовой', 6: 'Грузовой межд.' };
const KIND: Record<string, string> = { WINTER: 'Зимний', CITY: 'Внутригородской', HIGHLAND: 'Высокогорный', USAGE: 'Эксплуатационный' };

async function api<T>(path: string, body?: unknown): Promise<T> {
  const r = await fetch(`/md-api/api/v1/dictionaries/${path}`, body ? {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  } : { headers: authHeaders() });
  if (!r.ok) {
    const p = await r.json().catch(() => null);
    throw new Error(p?.detail ?? p?.title ?? `Ошибка ${r.status}`);
  }
  return r.json();
}

/** Справочники платформы (раздел 8.12 ТЗ) — наследие массивов программы НА ва ХЛ. */
export default function DictionariesPage() {
  const [tab, setTab] = useState<Tab>('routes');
  const [rows, setRows] = useState<Row[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [form, setForm] = useState<Record<string, string>>({});

  const reload = useCallback(async () => {
    setRows(await api<Row[]>(tab));
  }, [tab]);

  useEffect(() => { setForm({}); setOk(''); setError(''); reload().catch(e => setError(e.message)); }, [reload, tab]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setOk('');
    try {
      const body: Record<string, unknown> = { ...form };
      for (const k of ['transportType', 'regionId', 'fuelType', 'monthFrom', 'monthTo']) {
        if (k in body) body[k] = body[k] === '' ? null : Number(body[k]);
      }
      for (const k of ['baseNorm', 'value', 'pricePerKm']) {
        if (k in body) body[k] = Number(body[k]);
      }
      await api(tab, body);
      setOk('Сохранено');
      setForm({});
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  const f = (k: string) => ({ value: form[k] ?? '', onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setForm({ ...form, [k]: e.target.value }) });

  const ttSelect = (key: string) => (
    <select {...f(key)}>
      <option value="">— тип ТС —</option>
      {Object.entries(TT).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
    </select>
  );

  const active = SECTIONS.find(s => s.key === tab);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>Справочники</h1>
          <div className="page-lead" style={{ margin: 0 }}>Нормативно-справочная информация платформы (НСИ, маълумотномаҳо)</div>
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      {/* Разделы справочников — карточки-переключатели */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(5, 1fr)' }}>
        {SECTIONS.map(s => {
          const sel = s.key === tab;
          return (
            <button
              key={s.key}
              onClick={() => setTab(s.key)}
              className="kpi"
              style={{
                cursor: 'pointer', textAlign: 'left', gap: 12, fontFamily: 'inherit',
                border: sel ? '1.5px solid var(--blue-500)' : '1px solid var(--line)',
                boxShadow: sel ? '0 6px 16px -6px rgba(37,99,235,.4)' : 'var(--shadow-sm)',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <span className={`k-ic ${s.cls}`}><Icon d={s.icon} cls="" /></span>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 700, fontSize: 13.5, color: sel ? 'var(--blue-700)' : 'var(--ink)' }}>{s.label}</div>
                  <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{s.desc}</div>
                </div>
              </div>
            </button>
          );
        })}
      </div>

      <div className="card" style={{ borderColor: 'var(--blue-500)' }}>
        <h2>{active ? `${active.label}: добавить / обновить` : 'Добавить / обновить'}</h2>
        <form className="grid" onSubmit={submit}>
          {tab === 'routes' && <>
            <div><label>Номер</label><input required {...f('number')} placeholder="3" /></div>
            <div><label>Название</label><input required {...f('name')} placeholder="Вокзал — Аэропорт" /></div>
            <div><label>Тип ТС</label>{ttSelect('transportType')}</div>
            <div><label>Регион (1–7)</label><input type="number" min={1} max={7} {...f('regionId')} /></div>
          </>}
          {tab === 'clients' && <>
            <div><label>Номер</label><input required {...f('number')} placeholder="000123" /></div>
            <div><label>Название</label><input required {...f('name')} /></div>
            <div><label>Адрес</label><input {...f('address')} /></div>
            <div><label>Телефон</label><input {...f('phone')} /></div>
          </>}
          {tab === 'fuel-norms' && <>
            <div><label>Тип ТС</label>{ttSelect('transportType')}</div>
            <div><label>Марка (пусто = все)</label><input {...f('brand')} placeholder="Акиа" /></div>
            <div><label>Базовая норма, л/100км</label><input required type="number" step="0.1" {...f('baseNorm')} /></div>
          </>}
          {tab === 'coefficients' && <>
            <div><label>Вид</label>
              <select {...f('kind')} required>
                <option value="">—</option>
                {Object.entries(KIND).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            </div>
            <div><label>Название</label><input required {...f('name')} /></div>
            <div><label>Множитель (напр. 1.10)</label><input required type="number" step="0.001" {...f('value')} /></div>
            <div><label>Регион (для высокогорного)</label><input type="number" min={1} max={7} {...f('regionId')} /></div>
            <div><label>Месяц с (для зимнего)</label><input type="number" min={1} max={12} {...f('monthFrom')} /></div>
            <div><label>Месяц по</label><input type="number" min={1} max={12} {...f('monthTo')} /></div>
          </>}
          {tab === 'tariffs' && <>
            <div><label>Тип ТС</label>{ttSelect('transportType')}</div>
            <div><label>Вид топлива (пусто = любой)</label><input type="number" min={1} max={5} {...f('fuelType')} /></div>
            <div><label>Тариф, сомони/км</label><input required type="number" step="0.01" {...f('pricePerKm')} /></div>
          </>}
          <div className="full"><button className="btn" type="submit">Сохранить</button></div>
        </form>
      </div>

      <div className="card">
        <div className="card-h">
          <h2>{active ? active.label : 'Записи'}</h2>
          <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 12.5 }}>Всего записей: {rows.length}</span>
        </div>
        <table>
          <thead>
            {tab === 'routes' && <tr><th>Номер</th><th>Название</th><th>Тип ТС</th><th>Регион</th></tr>}
            {tab === 'clients' && <tr><th>Номер</th><th>Название</th><th>Адрес</th><th>Телефон</th></tr>}
            {tab === 'fuel-norms' && <tr><th>Тип ТС</th><th>Марка</th><th>Норма, л/100км</th></tr>}
            {tab === 'coefficients' && <tr><th>Вид</th><th>Название</th><th>Множитель</th><th>Регион</th><th>Месяцы</th></tr>}
            {tab === 'tariffs' && <tr><th>Тип ТС</th><th>Топливо</th><th>Сомони/км</th></tr>}
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={String(r.id ?? i)}>
                {tab === 'routes' && <><td><span className="number">{String(r.number)}</span></td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td>{TT[Number(r.transportType)] ?? '—'}</td><td>{String(r.regionId ?? '—')}</td></>}
                {tab === 'clients' && <><td><span className="number">{String(r.number ?? '—')}</span></td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td>{String(r.address ?? '—')}</td><td>{String(r.phone ?? '—')}</td></>}
                {tab === 'fuel-norms' && <><td>{TT[Number(r.transportType)] ?? r.transportType}</td><td>{String(r.brand ?? 'все')}</td><td><b>{String(r.baseNorm)}</b></td></>}
                {tab === 'coefficients' && <><td>{KIND[String(r.kind)] ?? String(r.kind)}</td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td><b>{String(r.value)}</b></td><td>{String(r.regionId ?? '—')}</td><td>{r.monthFrom ? `${r.monthFrom}–${r.monthTo}` : '—'}</td></>}
                {tab === 'tariffs' && <><td>{TT[Number(r.transportType)] ?? r.transportType}</td><td>{String(r.fuelType ?? 'любой')}</td><td><b>{String(r.pricePerKm)}</b></td></>}
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Записей нет</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
