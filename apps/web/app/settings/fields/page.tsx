'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { md, TYPE_LABELS, type FieldDefinition } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

const WB_TYPES = Object.keys(TYPE_LABELS);
const DATA_TYPES = ['STRING', 'NUMBER', 'DATE', 'BOOLEAN', 'ENUM'] as const;
// Ключ — имя в документе (typeData.custom.<ключ>): латиница, цифры, «_», с буквы (как на сервере).
const KEY_RE = /^[A-Za-z][A-Za-z0-9_]{1,39}$/;

type FormState = {
  fieldKey: string; labelRu: string; labelTj: string; dataType: string;
  required: boolean; options: string; sortOrder: string; active: boolean;
};
const EMPTY: FormState = { fieldKey: '', labelRu: '', labelTj: '', dataType: 'STRING', required: false, options: '', sortOrder: '', active: true };
const small = { padding: '4px 9px', fontSize: 12 } as const;

/**
 * Конструктор дополнительных полей путевого листа (по виду ПЛ). Поле появляется в мастере
 * создания ПЛ (шаг «Маршрут и параметры»), значение сохраняется в документе, показывается в
 * карточке и печатается на бланке (на официальных — в «Особых отметках»).
 */
export default function FieldsSettingsPage() {
  const { t, tType } = useT();
  const { roles } = useAuth();
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [wt, setWt] = useState<string>(WB_TYPES[0]);
  const [rows, setRows] = useState<FieldDefinition[]>([]);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY);
  // Правка существующего поля: id и черновик (ключ и тип данных после создания не меняются —
  // иначе уже заполненные листы получили бы значения чужого типа).
  const [editId, setEditId] = useState<string | null>(null);
  const [edit, setEdit] = useState<FormState>(EMPTY);

  const reload = useCallback(async () => {
    try { setRows(await md.fieldDefinitions(wt, true)); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, [wt]);

  useEffect(() => { setForm(EMPTY); setEditId(null); setInfo(''); reload(); }, [reload]);

  const nextOrder = rows.length ? Math.min(999, Math.max(...rows.map(r => r.sortOrder ?? 0)) + 10) : 10;

  const body = (f: FormState, key: string, dataType: string) => ({
    waybillType: wt, fieldKey: key, labelRu: f.labelRu.trim(), labelTj: f.labelTj.trim() || null,
    dataType, required: f.required, options: dataType === 'ENUM' ? f.options.trim() || null : null,
    sortOrder: f.sortOrder.trim() === '' ? undefined : Number(f.sortOrder), active: f.active,
  });

  const formError = (f: FormState, key: string, dataType: string) => {
    if (!KEY_RE.test(key)) return t('fld.err.key');
    if (!f.labelRu.trim()) return t('fld.err.label');
    if (dataType === 'ENUM' && !f.options.split(',').some(o => o.trim())) return t('fld.err.options');
    if (f.sortOrder.trim() !== '' && !/^\d{1,3}$/.test(f.sortOrder.trim())) return t('fld.err.order');
    return '';
  };

  async function add() {
    const key = form.fieldKey.trim();
    const err = formError(form, key, form.dataType);
    if (err) { setError(err); return; }
    if (rows.some(r => r.fieldKey === key)) { setError(t('fld.err.exists')); return; }
    setBusy(true); setError(''); setInfo('');
    try {
      await md.saveFieldDefinition(body({ ...form, sortOrder: form.sortOrder || String(nextOrder) }, key, form.dataType));
      setForm(EMPTY); setInfo(t('fld.saved')); await reload();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  function startEdit(r: FieldDefinition) {
    setEditId(r.id); setError(''); setInfo('');
    setEdit({
      fieldKey: r.fieldKey, labelRu: r.labelRu, labelTj: r.labelTj ?? '', dataType: r.dataType,
      required: r.required, options: r.options ?? '', sortOrder: String(r.sortOrder ?? 0), active: r.active,
    });
  }

  async function saveEdit(r: FieldDefinition) {
    const err = formError(edit, r.fieldKey, r.dataType);
    if (err) { setError(err); return; }
    setBusy(true); setError(''); setInfo('');
    try { await md.saveFieldDefinition(body(edit, r.fieldKey, r.dataType)); setEditId(null); setInfo(t('fld.saved')); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  async function toggleActive(r: FieldDefinition) {
    setBusy(true); setError(''); setInfo('');
    try {
      await md.saveFieldDefinition({
        waybillType: wt, fieldKey: r.fieldKey, labelRu: r.labelRu, labelTj: r.labelTj, dataType: r.dataType,
        required: r.required, options: r.options, sortOrder: r.sortOrder, active: !r.active,
      });
      await reload();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  async function remove(r: FieldDefinition) {
    if (!confirm(`${t('fld.remove.confirm')} «${r.labelRu}» (${r.fieldKey})?\n${t('fld.remove.hint')}`)) return;
    setBusy(true); setError(''); setInfo('');
    try { await md.deleteFieldDefinition(r.id); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  const dtLabel = (dt: string) => { const k = `fld.dt.${dt}`; const tr = t(k); return tr === k ? dt : tr; };
  const inp = (w: number) => ({ width: w, padding: '5px 8px', fontSize: 12.5 });

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
      {info && !error && <div className="sys-ok" style={{ marginBottom: 14 }}>{info}</div>}

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('fld.type')}:</span>
        <select value={wt} onChange={e => setWt(e.target.value)} style={{ width: 'auto', minWidth: 260 }} aria-label={t('fld.type')}>
          {WB_TYPES.map(c => <option key={c} value={c}>{tType(c)}</option>)}
        </select>
      </div>

      <div className="card">
        <div style={{ overflowX: 'auto' }}>
        <table className="dense">
          <thead>
            <tr>
              <th style={{ width: 70 }}>{t('fld.col.order')}</th>
              <th style={{ width: 140 }}>{t('fld.col.key')}</th>
              <th>{t('fld.col.label')}</th>
              <th>{t('fld.col.labeltj')}</th>
              <th style={{ width: 150 }}>{t('fld.col.datatype')}</th>
              <th style={{ width: 100 }}>{t('fld.col.required')}</th>
              <th style={{ width: 110 }}>{t('fld.col.status')}</th>
              <th style={{ width: 1 }} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={8} style={{ color: 'var(--muted)' }}>{t('fld.empty')}</td></tr>}
            {rows.map(r => editId === r.id ? (
              <tr key={r.id} style={{ background: 'var(--blue-050)' }}>
                <td><input value={edit.sortOrder} onChange={e => setEdit({ ...edit, sortOrder: e.target.value })} style={inp(56)} aria-label={t('fld.col.order')} /></td>
                <td><span className="number">{r.fieldKey}</span></td>
                <td><input value={edit.labelRu} onChange={e => setEdit({ ...edit, labelRu: e.target.value })} style={inp(180)} aria-label={t('fld.col.label')} /></td>
                <td><input value={edit.labelTj} onChange={e => setEdit({ ...edit, labelTj: e.target.value })} style={inp(180)} aria-label={t('fld.col.labeltj')} /></td>
                <td>
                  {dtLabel(r.dataType)}
                  {r.dataType === 'ENUM' && (
                    <input value={edit.options} onChange={e => setEdit({ ...edit, options: e.target.value })} style={{ ...inp(150), marginTop: 4 }}
                      aria-label={t('fld.options')} placeholder="A, B, C" />
                  )}
                </td>
                <td><input type="checkbox" checked={edit.required} onChange={e => setEdit({ ...edit, required: e.target.checked })} aria-label={t('fld.col.required')} /></td>
                <td>
                  <label style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12.5 }}>
                    <input type="checkbox" checked={edit.active} onChange={e => setEdit({ ...edit, active: e.target.checked })} /> {t('fld.active')}
                  </label>
                </td>
                <td style={{ whiteSpace: 'nowrap' }}>
                  <button className="btn" style={small} disabled={busy} onClick={() => saveEdit(r)}>{t('btn.save')}</button>{' '}
                  <button className="btn secondary" style={small} onClick={() => setEditId(null)}>{t('btn.cancel')}</button>
                </td>
              </tr>
            ) : (
              <tr key={r.id} style={{ opacity: r.active ? 1 : 0.55 }}>
                <td>{r.sortOrder ?? 0}</td>
                <td><span className="number">{r.fieldKey}</span></td>
                <td style={{ fontWeight: 600 }}>{r.labelRu}</td>
                <td>{r.labelTj ?? <span style={{ color: 'var(--muted)' }}>—</span>}</td>
                <td>{dtLabel(r.dataType)}{r.dataType === 'ENUM' && r.options ? <div style={{ color: 'var(--muted)', fontSize: 12 }}>{r.options}</div> : null}</td>
                <td><span className={`badge ${r.required ? 'amber' : 'gray'}`}>{r.required ? t('fld.yes') : t('fld.no')}</span></td>
                <td>{r.active ? <span className="badge green">{t('fld.shown')}</span> : <span className="badge">{t('fld.hidden')}</span>}</td>
                <td style={{ whiteSpace: 'nowrap' }}>
                  {isSysAdmin && (
                    <>
                      <button className="btn secondary" style={small} disabled={busy} onClick={() => startEdit(r)}>{t('fld.edit')}</button>{' '}
                      <button className="btn secondary" style={small} disabled={busy} onClick={() => toggleActive(r)}>{r.active ? t('fld.hide') : t('fld.show')}</button>{' '}
                      <button className="btn danger" style={small} disabled={busy} onClick={() => remove(r)} title={t('pol.remove')}>
                        <Icon d={P.trash} cls="" style={{ width: 14, height: 14 }} />
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>

        {isSysAdmin && (
          <div style={{ marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
            <div style={{ fontWeight: 700, fontSize: 13.5, marginBottom: 10 }}>{t('fld.new.h')}</div>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end' }}>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                {t('fld.col.key')}
                <input value={form.fieldKey} onChange={e => setForm({ ...form, fieldKey: e.target.value })} placeholder="cargoWeight" style={{ width: 150 }} aria-label={t('fld.col.key')}
                  title={t('fld.err.key')} />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                {t('fld.col.label')}
                <input value={form.labelRu} onChange={e => setForm({ ...form, labelRu: e.target.value })} style={{ width: 190 }} aria-label={t('fld.col.label')} />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                {t('fld.col.labeltj')}
                <input value={form.labelTj} onChange={e => setForm({ ...form, labelTj: e.target.value })} style={{ width: 190 }} aria-label={t('fld.col.labeltj')} />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                {t('fld.col.datatype')}
                <select value={form.dataType} onChange={e => setForm({ ...form, dataType: e.target.value })} style={{ width: 130 }} aria-label={t('fld.col.datatype')}>
                  {DATA_TYPES.map(d => <option key={d} value={d}>{dtLabel(d)}</option>)}
                </select>
              </label>
              {form.dataType === 'ENUM' && (
                <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                  {t('fld.options')}
                  <input value={form.options} onChange={e => setForm({ ...form, options: e.target.value })} placeholder="A, B, C" style={{ width: 180 }} aria-label={t('fld.options')} />
                </label>
              )}
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                {t('fld.col.order')}
                <input value={form.sortOrder} onChange={e => setForm({ ...form, sortOrder: e.target.value })} placeholder={String(nextOrder)} style={{ width: 70 }} aria-label={t('fld.col.order')} />
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, paddingBottom: 8 }}>
                <input type="checkbox" checked={form.required} onChange={e => setForm({ ...form, required: e.target.checked })} /> {t('fld.col.required')}
              </label>
              <button className="btn" disabled={busy || !form.fieldKey.trim() || !form.labelRu.trim()} onClick={add}>
                <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('fld.add')}
              </button>
            </div>
            <div className="hint" style={{ marginTop: 10 }}>{t('fld.new.hint')}</div>
          </div>
        )}
      </div>
    </>
  );
}
