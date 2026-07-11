'use client';

import { useCallback, useEffect, useState } from 'react';
import { authHeaders } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type Row = Record<string, unknown>;
type Tab = 'routes' | 'clients' | 'fuel-norms' | 'coefficients' | 'tariffs';

const SECTIONS: { key: Tab; labelKey: string; descKey: string; icon: string; cls: string }[] = [
  { key: 'routes', labelKey: 'dict.sec.routes', descKey: 'dict.sec.routes.d', icon: P.route, cls: 'ic-blue' },
  { key: 'clients', labelKey: 'dict.sec.clients', descKey: 'dict.sec.clients.d', icon: P.building, cls: 'ic-cyan' },
  { key: 'fuel-norms', labelKey: 'dict.sec.fuelnorms', descKey: 'dict.sec.fuelnorms.d', icon: P.car, cls: 'ic-purple' },
  { key: 'coefficients', labelKey: 'dict.sec.coefficients', descKey: 'dict.sec.coefficients.d', icon: P.chart, cls: 'ic-amber' },
  { key: 'tariffs', labelKey: 'dict.sec.tariffs', descKey: 'dict.sec.tariffs.d', icon: P.doc, cls: 'ic-green' },
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
  const { t } = useT();
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
      setOk(t('common.saved'));
      setForm({});
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  const f = (k: string) => ({ value: form[k] ?? '', onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setForm({ ...form, [k]: e.target.value }) });

  const ttSelect = (key: string) => (
    <select {...f(key)}>
      <option value="">{t('dict.opt.vehtype')}</option>
      {Object.entries(TT).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
    </select>
  );

  const active = SECTIONS.find(s => s.key === tab);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.dictionaries')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('dict.lead')}</div>
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
                  <div style={{ fontWeight: 700, fontSize: 13.5, color: sel ? 'var(--blue-700)' : 'var(--ink)' }}>{t(s.labelKey)}</div>
                  <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{t(s.descKey)}</div>
                </div>
              </div>
            </button>
          );
        })}
      </div>

      <div className="card" style={{ borderColor: 'var(--blue-500)' }}>
        <h2>{active ? `${t(active.labelKey)}: ${t('dict.addupdate')}` : t('dict.addupdate.cap')}</h2>
        <form className="grid" onSubmit={submit}>
          {tab === 'routes' && <>
            <div><label>{t('col.number')}</label><input required {...f('number')} placeholder="3" /></div>
            <div><label>{t('col.name')}</label><input required {...f('name')} placeholder="Вокзал — Аэропорт" /></div>
            <div><label>{t('f.vehtype')}</label>{ttSelect('transportType')}</div>
            <div><label>{t('f.region')}</label><input type="number" min={1} max={7} {...f('regionId')} /></div>
          </>}
          {tab === 'clients' && <>
            <div><label>{t('col.number')}</label><input required {...f('number')} placeholder="000123" /></div>
            <div><label>{t('col.name')}</label><input required {...f('name')} /></div>
            <div><label>{t('col.address')}</label><input {...f('address')} /></div>
            <div><label>{t('col.phone')}</label><input {...f('phone')} /></div>
          </>}
          {tab === 'fuel-norms' && <>
            <div><label>{t('f.vehtype')}</label>{ttSelect('transportType')}</div>
            <div><label>{t('dict.f.brandall')}</label><input {...f('brand')} placeholder="Акиа" /></div>
            <div><label>{t('dict.f.basenorm')}</label><input required type="number" step="0.1" {...f('baseNorm')} /></div>
          </>}
          {tab === 'coefficients' && <>
            <div><label>{t('dict.f.kind')}</label>
              <select {...f('kind')} required>
                <option value="">—</option>
                {Object.entries(KIND).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            </div>
            <div><label>{t('col.name')}</label><input required {...f('name')} /></div>
            <div><label>{t('dict.f.multiplier')}</label><input required type="number" step="0.001" {...f('value')} /></div>
            <div><label>{t('dict.f.regionhigh')}</label><input type="number" min={1} max={7} {...f('regionId')} /></div>
            <div><label>{t('dict.f.monthfrom')}</label><input type="number" min={1} max={12} {...f('monthFrom')} /></div>
            <div><label>{t('dict.f.monthto')}</label><input type="number" min={1} max={12} {...f('monthTo')} /></div>
          </>}
          {tab === 'tariffs' && <>
            <div><label>{t('f.vehtype')}</label>{ttSelect('transportType')}</div>
            <div><label>{t('dict.f.fuelany')}</label><input type="number" min={1} max={5} {...f('fuelType')} /></div>
            <div><label>{t('dict.f.tariff')}</label><input required type="number" step="0.01" {...f('pricePerKm')} /></div>
          </>}
          <div className="full"><button className="btn" type="submit">{t('btn.save')}</button></div>
        </form>
      </div>

      <div className="card">
        <div className="card-h">
          <h2>{active ? t(active.labelKey) : t('dict.records')}</h2>
          <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 12.5 }}>{t('dict.totalrecords')}: {rows.length}</span>
        </div>
        <table>
          <thead>
            {tab === 'routes' && <tr><th>{t('col.number')}</th><th>{t('col.name')}</th><th>{t('f.vehtype')}</th><th>{t('col.region')}</th></tr>}
            {tab === 'clients' && <tr><th>{t('col.number')}</th><th>{t('col.name')}</th><th>{t('col.address')}</th><th>{t('col.phone')}</th></tr>}
            {tab === 'fuel-norms' && <tr><th>{t('f.vehtype')}</th><th>{t('col.brand')}</th><th>{t('dict.col.norm')}</th></tr>}
            {tab === 'coefficients' && <tr><th>{t('dict.f.kind')}</th><th>{t('col.name')}</th><th>{t('dict.f.multiplier').replace(/\s*\(.*\)/, '')}</th><th>{t('col.region')}</th><th>{t('dict.col.months')}</th></tr>}
            {tab === 'tariffs' && <tr><th>{t('f.vehtype')}</th><th>{t('col.fuel')}</th><th>{t('dict.col.somonikm')}</th></tr>}
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={String(r.id ?? i)}>
                {tab === 'routes' && <><td><span className="number">{String(r.number)}</span></td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td>{TT[Number(r.transportType)] ?? '—'}</td><td>{String(r.regionId ?? '—')}</td></>}
                {tab === 'clients' && <><td><span className="number">{String(r.number ?? '—')}</span></td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td>{String(r.address ?? '—')}</td><td>{String(r.phone ?? '—')}</td></>}
                {tab === 'fuel-norms' && <><td>{TT[Number(r.transportType)] ?? r.transportType}</td><td>{r.brand ? String(r.brand) : t('dict.all')}</td><td><b>{String(r.baseNorm)}</b></td></>}
                {tab === 'coefficients' && <><td>{KIND[String(r.kind)] ?? String(r.kind)}</td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td><b>{String(r.value)}</b></td><td>{String(r.regionId ?? '—')}</td><td>{r.monthFrom ? `${r.monthFrom}–${r.monthTo}` : '—'}</td></>}
                {tab === 'tariffs' && <><td>{TT[Number(r.transportType)] ?? r.transportType}</td><td>{r.fuelType != null ? String(r.fuelType) : t('dict.any')}</td><td><b>{String(r.pricePerKm)}</b></td></>}
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
