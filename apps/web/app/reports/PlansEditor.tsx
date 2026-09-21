'use client';

import { useCallback, useEffect, useState } from 'react';
import { wb, md, type WaybillPlan } from '@/lib/api';
import { useT } from '@/lib/i18n';

// Виды плана = legacy config/trans.php bill_type_plan: 1 Мусофирбарӣ, 2 Автомобили сабукрав (такси),
// 3 Шакли 2Б (грузовые), 4 Шакли 5Б-БМ (грузовые международные) — MIGRATION.md 2.29.
const KINDS = [
  { v: 'PASSENGER', k: 'rp.kind.passenger' },
  { v: 'CARGO', k: 'rp.kind.cargo' },
  { v: 'TAXI', k: 'rp.kind.taxi' },
  { v: 'CARGO_INTL', k: 'rp.kind.cargointl' },
];

/** Редактор плановых показателей перевозок (для сводного отчёта Минтранса). SYSTEM_ADMIN / MINTRANS_ANALYST. */
export default function PlansEditor() {
  const { t } = useT();
  const [kind, setKind] = useState('PASSENGER');
  const [rows, setRows] = useState<WaybillPlan[]>([]);
  const [orgs, setOrgs] = useState<{ rma: string; name: string }[]>([]);
  const [error, setError] = useState('');
  const [form, setForm] = useState({ organizationRma: '', regionId: '', planYear: String(new Date().getFullYear()), planMonth: '', volumeThousand: '', rotationMillion: '', note: '' });

  const load = useCallback(() => {
    wb.plans.list(kind).then(setRows).catch(e => setError(e.message));
  }, [kind]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    md.organizations().then(list => setOrgs(list.map(o => ({ rma: String(o.rma), name: String(o.name) })))).catch(() => { /* без списка */ });
  }, []);

  async function save() {
    setError('');
    try {
      await wb.plans.upsert({
        organizationRma: form.organizationRma || null,
        regionId: form.regionId ? Number(form.regionId) : null,
        planYear: Number(form.planYear),
        planMonth: form.planMonth ? Number(form.planMonth) : null,
        planKind: kind,
        volumeThousand: Number(form.volumeThousand || 0),
        rotationMillion: Number(form.rotationMillion || 0),
        note: form.note || null,
      });
      setForm(f => ({ ...f, planMonth: '', volumeThousand: '', rotationMillion: '', note: '' }));
      load();
    } catch (e) { setError((e as Error).message); }
  }

  async function remove(id: string) {
    if (!confirm(t('rp.confirm.delete'))) return;
    try { await wb.plans.remove(id); load(); } catch (e) { setError((e as Error).message); }
  }

  return (
    <div className="card" style={{ marginTop: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 10 }}>
        <h2 style={{ margin: 0 }}>{t('rp.h2')}</h2>
        <select value={kind} onChange={e => setKind(e.target.value)} style={{ width: 320 }}>
          {KINDS.map(k => <option key={k.v} value={k.v}>{t(k.k)}</option>)}
        </select>
      </div>
      {error && <div className="error">{error}</div>}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 10 }}>
        <select value={form.organizationRma} onChange={e => setForm(f => ({ ...f, organizationRma: e.target.value }))} style={{ width: 240 }}>
          <option value="">{t('rp.national')}</option>
          {orgs.map(o => <option key={o.rma} value={o.rma}>{o.name} ({o.rma})</option>)}
        </select>
        <input style={{ width: 90 }} type="number" placeholder={t('col.region')} value={form.regionId} onChange={e => setForm(f => ({ ...f, regionId: e.target.value }))} />
        <input style={{ width: 90 }} type="number" placeholder={t('rp.year')} value={form.planYear} onChange={e => setForm(f => ({ ...f, planYear: e.target.value }))} />
        <input style={{ width: 130 }} type="number" min={1} max={12} placeholder={t('rp.monthopt')} title={t('rp.monthhint')} value={form.planMonth} onChange={e => setForm(f => ({ ...f, planMonth: e.target.value }))} />
        <input style={{ width: 130 }} type="number" step="0.1" placeholder={t('rp.volume')} value={form.volumeThousand} onChange={e => setForm(f => ({ ...f, volumeThousand: e.target.value }))} />
        <input style={{ width: 130 }} type="number" step="0.1" placeholder={t('rp.rotation')} value={form.rotationMillion} onChange={e => setForm(f => ({ ...f, rotationMillion: e.target.value }))} />
        <input style={{ width: 160 }} placeholder={t('rp.note')} value={form.note} onChange={e => setForm(f => ({ ...f, note: e.target.value }))} />
        <button className="btn" onClick={save} disabled={!form.planYear}>{t('rp.save')}</button>
      </div>

      <table>
        <thead><tr><th>{t('rp.year')}</th><th>{t('rp.month')}</th><th>{t('col.org')}</th><th>{t('col.region')}</th><th>{t('rp.volume')}</th><th>{t('rp.rotation')}</th><th>{t('rp.note')}</th><th></th></tr></thead>
        <tbody>
          {rows.map(p => (
            <tr key={p.id}>
              <td>{p.planYear}</td>
              <td>{p.planMonth ?? t('rp.wholeyear')}</td>
              <td>{p.organizationRma ?? t('rp.nationalshort')}</td>
              <td>{p.regionId ?? '—'}</td>
              <td>{p.volumeThousand.toLocaleString('ru-RU')}</td>
              <td>{p.rotationMillion.toLocaleString('ru-RU')}</td>
              <td>{p.note ?? ''}</td>
              <td><button className="btn secondary" onClick={() => remove(p.id)}>×</button></td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 14 }}>{t('rp.empty')}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
