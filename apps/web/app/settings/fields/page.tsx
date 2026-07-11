'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { md, TYPE_LABELS, type FieldDefinition } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

const WB_TYPES = Object.keys(TYPE_LABELS);
const DATA_TYPES = ['STRING', 'NUMBER', 'DATE', 'BOOLEAN', 'ENUM'] as const;

type FormState = { fieldKey: string; labelRu: string; labelTj: string; dataType: string; required: boolean; options: string };
const EMPTY: FormState = { fieldKey: '', labelRu: '', labelTj: '', dataType: 'STRING', required: false, options: '' };

export default function FieldsSettingsPage() {
  const { t, tType } = useT();
  const { roles } = useAuth();
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [wt, setWt] = useState<string>(WB_TYPES[0]);
  const [rows, setRows] = useState<FieldDefinition[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY);

  const reload = useCallback(async () => {
    try { setRows(await md.fieldDefinitions(wt, true)); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, [wt]);

  useEffect(() => { setForm(EMPTY); reload(); }, [reload]);

  async function add() {
    setBusy(true); setError('');
    try {
      await md.saveFieldDefinition({
        waybillType: wt, fieldKey: form.fieldKey.trim(), labelRu: form.labelRu.trim(),
        labelTj: form.labelTj.trim() || null, dataType: form.dataType, required: form.required,
        options: form.dataType === 'ENUM' ? form.options.trim() || null : null,
      });
      setForm(EMPTY); await reload();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function remove(id: string) {
    setBusy(true); setError('');
    try { await md.deleteFieldDefinition(id); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  const dtLabel = (dt: string) => { const k = `fld.dt.${dt}`; const tr = t(k); return tr === k ? dt : tr; };

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.fields')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('fld.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('fld.note')}</div>
      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('fld.type')}:</span>
        <select value={wt} onChange={e => setWt(e.target.value)}>
          {WB_TYPES.map(c => <option key={c} value={c}>{tType(c)}</option>)}
        </select>
      </div>

      <div className="card">
        <table>
          <thead>
            <tr>
              <th style={{ width: 150 }}>{t('fld.col.key')}</th>
              <th>{t('fld.col.label')}</th>
              <th style={{ width: 120 }}>{t('fld.col.datatype')}</th>
              <th style={{ width: 110 }}>{t('fld.col.required')}</th>
              <th style={{ width: 60 }} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={5} style={{ color: 'var(--muted)' }}>{t('fld.empty')}</td></tr>}
            {rows.map(r => (
              <tr key={r.id} style={{ opacity: r.active ? 1 : 0.5 }}>
                <td><span className="number">{r.fieldKey}</span></td>
                <td style={{ fontWeight: 600 }}>{r.labelRu}{r.labelTj ? <span style={{ color: 'var(--muted)', fontWeight: 400 }}> · {r.labelTj}</span> : null}</td>
                <td>{dtLabel(r.dataType)}{r.dataType === 'ENUM' && r.options ? <span style={{ color: 'var(--muted)', fontSize: 12 }}> ({r.options})</span> : null}</td>
                <td><span className={`badge ${r.required ? 'amber' : 'gray'}`}>{r.required ? t('fld.yes') : t('fld.no')}</span></td>
                <td>
                  {isSysAdmin && (
                    <button title={t('pol.remove')} disabled={busy} onClick={() => remove(r.id)}
                      style={{ background: 'none', border: '1px solid var(--line)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--red)' }}>
                      <Icon d={P.trash} cls="" style={{ width: 15, height: 15 }} />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        {isSysAdmin && (
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('fld.col.key')}
              <input value={form.fieldKey} onChange={e => setForm({ ...form, fieldKey: e.target.value })} placeholder="cargoWeight" style={{ width: 140 }} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('fld.col.label')}
              <input value={form.labelRu} onChange={e => setForm({ ...form, labelRu: e.target.value })} style={{ width: 190 }} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('fld.col.datatype')}
              <select value={form.dataType} onChange={e => setForm({ ...form, dataType: e.target.value })}>
                {DATA_TYPES.map(d => <option key={d} value={d}>{dtLabel(d)}</option>)}
              </select>
            </label>
            {form.dataType === 'ENUM' && (
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                {t('fld.options')}
                <input value={form.options} onChange={e => setForm({ ...form, options: e.target.value })} placeholder="A, B, C" style={{ width: 180 }} />
              </label>
            )}
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, paddingBottom: 6 }}>
              <input type="checkbox" checked={form.required} onChange={e => setForm({ ...form, required: e.target.checked })} /> {t('fld.col.required')}
            </label>
            <button className="btn" disabled={busy || !form.fieldKey.trim() || !form.labelRu.trim()} onClick={add}>
              <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('fld.add')}
            </button>
          </div>
        )}
      </div>
    </>
  );
}
