'use client';

import { useMemo, useState } from 'react';
import { md, wb } from '@/lib/api';
import { downloadCsv } from '@/lib/csv';
import { useT } from '@/lib/i18n';

type Row = Record<string, unknown>;
type Hit = { key: string; waybills: number; organizationRma: string | null; name?: string };

// Формы ПЛ legacy → виды ПЛ e-Waybill (как фильтры активности реестра парка перевозчика, MIGRATION.md 8.5/8.6).
const FORMS: Record<string, string[]> = {
  '': [], '3c': ['WB_CAR', 'WB_TAXI'], '2b': ['WB_TRUCK'], '1ad': ['WB_BUS', 'WB_TROLLEYBUS'], '1a': ['WB_MINIBUS'], '5bbm': ['WB_TRUCK_INTL'],
};
const FORM_LABELS: [string, string][] = [['3c', '3-С'], ['2b', '2-Б'], ['1ad', '1-АД'], ['1a', '1-А'], ['5bbm', '5Б-БМ']];
const PER_PAGE = 50;

/**
 * Активность ТС / водителей за период для надзора по всем организациям (сверка 25.09, F5; legacy фильтры
 * active_trans / inactive_trans / active2b / «4 роҳхат» / active_drivers / inactive_drivers). У перевозчика
 * эти фильтры есть в «Транспорт и водители»; у регулятора — здесь. «Работали» и «ровно N ПЛ» — агрегат по
 * всем организациям (или выбранной); «без ПЛ» — только внутри выбранной организации (весь парк страны
 * — десятки тысяч записей — сравнивать в браузере не будем).
 */
export default function ActivityPanel({ kind, orgs, orgFilter }: { kind: 'vehicles' | 'drivers'; orgs: Row[]; orgFilter: string }) {
  const { t } = useT();
  const monthStart = () => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1, 12).toISOString().slice(0, 10); };
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const [form, setForm] = useState('');
  const [mode, setMode] = useState<'active' | 'exact' | 'inactive'>('active');
  const [n, setN] = useState('4');
  const [hits, setHits] = useState<Hit[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [page, setPage] = useState(1);
  const orgName = useMemo(() => new Map(orgs.map(o => [String(o.rma), String(o.name ?? o.rma)])), [orgs]);

  async function run() {
    setBusy(true); setError(''); setPage(1);
    try {
      const list = await wb.activity(kind === 'vehicles' ? 'VEHICLE' : 'DRIVER', from, to, FORMS[form], orgFilter || undefined);
      if (mode === 'inactive') {
        if (!orgFilter) { setError(t('act.inactive.needorg')); setHits(null); return; }
        const active = new Set(list.map(x => x.key.toUpperCase()));
        const all = kind === 'vehicles' ? await md.vehicles(orgFilter) : await md.drivers(orgFilter);
        setHits(all.map(r => ({
          key: String(kind === 'vehicles' ? r.registrationNumber : r.rma),
          name: kind === 'vehicles' ? String(r.brand ?? '') : String(r.fullName ?? ''),
          waybills: 0, organizationRma: orgFilter,
        })).filter(h => !active.has(h.key.toUpperCase())));
      } else {
        setHits(list.filter(x => mode === 'active' ? x.waybills > 0 : x.waybills === Number(n || 0))
          .sort((a, b) => b.waybills - a.waybills || a.key.localeCompare(b.key)));
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const rows = hits ?? [];
  const pages = Math.max(1, Math.ceil(rows.length / PER_PAGE));
  const shown = rows.slice((page - 1) * PER_PAGE, page * PER_PAGE);
  const keyLabel = kind === 'vehicles' ? t('col.regnum') : t('col.innrma');

  return (
    <div className="card" data-testid="activity-panel">
      <h2 style={{ marginTop: 0 }}>{t('act.h')}</h2>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 10, alignItems: 'end' }}>
        <div><label>{t('flt.datefrom')}</label><input type="date" aria-label={t('act.from')} value={from} onChange={e => setFrom(e.target.value)} /></div>
        <div><label>{t('flt.dateto')}</label><input type="date" aria-label={t('act.to')} value={to} onChange={e => setTo(e.target.value)} /></div>
        <div><label>{t('act.form')}</label>
          <select aria-label={t('act.form')} value={form} onChange={e => setForm(e.target.value)}>
            <option value="">{t('act.allforms')}</option>
            {FORM_LABELS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select></div>
        <div><label>{t('act.mode')}</label>
          <select aria-label={t('act.mode')} value={mode} onChange={e => setMode(e.target.value as typeof mode)}>
            <option value="active">{t('act.mode.active')}</option>
            <option value="exact">{t('act.mode.exact')}</option>
            <option value="inactive">{t('act.mode.inactive')}</option>
          </select></div>
        {mode === 'exact' && <div><label>N</label><input type="number" min={1} aria-label="N" value={n} onChange={e => setN(e.target.value)} /></div>}
        <div style={{ display: 'flex', gap: 8 }}>
          <button type="button" className="btn" disabled={busy || !from || !to} onClick={() => void run()}>{busy ? '…' : t('act.run')}</button>
          {hits && hits.length > 0 && (
            <button type="button" className="btn secondary" onClick={() => downloadCsv(`activity_${kind}_${from}_${to}.csv`,
              [[keyLabel, t('col.org'), t('act.count')], ...rows.map(h => [h.key, orgName.get(String(h.organizationRma)) ?? h.organizationRma ?? '', h.waybills])])}>⬇ CSV</button>
          )}
        </div>
      </div>
      <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 6 }}>{orgFilter ? `${t('col.org')}: ${orgName.get(orgFilter) ?? orgFilter}` : t('act.scope.all')}</div>
      {error && <div className="error" style={{ marginTop: 10 }}>{error}</div>}
      {hits && (
        <>
          <div style={{ overflowX: 'auto', marginTop: 10 }}>
            <table className="dense" data-testid="activity-table">
              <thead><tr><th>{keyLabel}</th>{mode === 'inactive' && <th>{kind === 'vehicles' ? t('col.brand') : t('col.fio')}</th>}<th>{t('col.org')}</th><th style={{ textAlign: 'right' }}>{t('act.count')}</th></tr></thead>
              <tbody>
                {shown.map(h => (
                  <tr key={h.key}>
                    <td><span className="number">{h.key}</span></td>
                    {mode === 'inactive' && <td>{h.name || '—'}</td>}
                    <td>{orgName.get(String(h.organizationRma)) ?? h.organizationRma ?? '—'}</td>
                    <td style={{ textAlign: 'right' }}>{h.waybills}</td>
                  </tr>
                ))}
                {rows.length === 0 && <tr><td colSpan={4} style={{ textAlign: 'center', color: 'var(--muted)', padding: 18 }}>{t('common.norecords')}</td></tr>}
              </tbody>
            </table>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingTop: 10, fontSize: 13, color: 'var(--muted)' }}>
            <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{rows.length.toLocaleString('ru-RU')}</b></span>
            <span style={{ flex: 1 }} />
            <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
            <span>{page} / {pages}</span>
            <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
          </div>
        </>
      )}
    </div>
  );
}
