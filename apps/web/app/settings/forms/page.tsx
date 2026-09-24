'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { md } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import {
  FORM_FIELDS, FORMS_CATEGORY, defaultFieldModes, parseFieldModes, serializeFieldModes, type FieldMode,
} from '@/lib/formFields';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';
import { ConfirmDialog, type ChangeLine } from '../../ConfirmDialog';

const FORMS = Object.keys(FORM_FIELDS);
const MODES: FieldMode[] = ['show', 'required', 'hidden'];

/**
 * Настройки → Поля компании / водителя / транспорта / сотрудника (решение владельца 24.09.2026):
 * администратор платформы скрывает поле формы или делает его обязательным без изменения кода.
 * Системные поля каждой формы закреплены. Обязательность проверяет и сервер (FormFieldPolicy).
 */
export default function FormFieldsSettingsPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const canEdit = roles.includes('SYSTEM_ADMIN');

  const [form, setForm] = useState<string>(FORMS[0]);
  // Плитки хаба ведут сюда с ?form=driver|vehicle|employee — открываем нужную форму.
  // (useSearchParams в Next 15 требует Suspense на статической странице — читаем адрес напрямую.)
  useEffect(() => {
    const q = new URLSearchParams(window.location.search).get('form');
    if (q && FORMS.includes(q)) setForm(q);
  }, []);
  const [saved, setSaved] = useState<Record<string, FieldMode>>(() => defaultFieldModes(FORMS[0]));
  const [draft, setDraft] = useState<Record<string, FieldMode>>(() => defaultFieldModes(FORMS[0]));
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);

  const reload = useCallback(async () => {
    try {
      const list = await md.settings(FORMS_CATEGORY);
      const modes = parseFieldModes(form, list.find(s => s.settingKey === form)?.settingValue);
      setSaved(modes); setDraft(modes); setError('');
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, [form]);

  useEffect(() => { setNotice(''); reload(); }, [reload]);

  const modeLabel = (m: FieldMode) => t(`formset.mode.${m}`);
  const fields = FORM_FIELDS[form] ?? [];

  const changes: ChangeLine[] = useMemo(() => fields
    .filter(f => !f.locked && saved[f.key] !== draft[f.key])
    .map(f => ({ label: t(f.labelKey).replace(/\s*\*$/, ''), from: modeLabel(saved[f.key]), to: modeLabel(draft[f.key]) })),
  // eslint-disable-next-line react-hooks/exhaustive-deps
  [fields, saved, draft, t]);

  const consequences = useMemo(() => {
    const out: string[] = [];
    if (changes.some(c => c.to === modeLabel('hidden'))) out.push(t('formset.cons.hidden'));
    if (changes.some(c => c.to === modeLabel('required'))) out.push(t('formset.cons.required'));
    out.push(t('formset.cons.sync'));
    return out;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [changes, t]);

  async function save() {
    setBusy(true); setError('');
    try {
      await md.saveSetting(FORMS_CATEGORY, form, serializeFieldModes(form, draft));
      setConfirm(false); setNotice(t('formset.saved'));
      await reload();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); setConfirm(false); }
    finally { setBusy(false); }
  }

  const counts = {
    hidden: fields.filter(f => draft[f.key] === 'hidden').length,
    required: fields.filter(f => draft[f.key] === 'required').length,
  };

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t(`formset.title.${form}`)}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('formset.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('formset.note')}</div>
      {!canEdit && <div className="hint" style={{ marginBottom: 14 }}>{t('formset.readonly')}</div>}
      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}
      {notice && <div className="hint" style={{ marginBottom: 14, borderColor: 'var(--green)' }}>{notice}</div>}

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('formset.form')}:</span>
        <select value={form} onChange={e => setForm(e.target.value)}>
          {FORMS.map(f => <option key={f} value={f}>{t(`formset.form.${f}`)}</option>)}
        </select>
        <span style={{ fontSize: 12.5, color: 'var(--muted)', marginLeft: 8 }}>
          {t('formset.count.required')}: {counts.required} · {t('formset.count.hidden')}: {counts.hidden}
        </span>
      </div>

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>{t('formset.col.field')}</th>
              <th style={{ width: 260 }}>{t('formset.col.mode')}</th>
            </tr>
          </thead>
          <tbody>
            {fields.map(f => (
              <tr key={f.key} style={{ opacity: draft[f.key] === 'hidden' ? 0.55 : 1 }}>
                <td style={{ fontWeight: 600 }}>{t(f.labelKey).replace(/\s*\*$/, '')}</td>
                <td>
                  {f.locked
                    ? <span className="badge amber">{t('formset.locked')}</span>
                    : (
                      <select value={draft[f.key]} disabled={!canEdit || busy}
                        onChange={e => { setNotice(''); setDraft({ ...draft, [f.key]: e.target.value as FieldMode }); }}>
                        {MODES.filter(m => m !== 'required' || !f.noRequired).map(m => <option key={m} value={m}>{modeLabel(m)}</option>)}
                      </select>
                    )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        {canEdit && (
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
            <button className="btn" disabled={busy || changes.length === 0} onClick={() => setConfirm(true)}>
              {changes.length === 0 ? t('formset.nochanges') : `${t('formset.save')} (${changes.length})`}
            </button>
            <button className="btn secondary" disabled={busy || changes.length === 0} onClick={() => setDraft(saved)}>
              {t('formset.discard')}
            </button>
            <button className="btn secondary" style={{ marginLeft: 'auto' }} disabled={busy}
              onClick={() => { setNotice(''); setDraft(defaultFieldModes(form)); }}>
              {t('formset.reset')}
            </button>
          </div>
        )}
      </div>

      {confirm && (
        <ConfirmDialog
          title={t('formset.confirm.title')}
          changes={changes}
          consequences={consequences}
          busy={busy}
          onConfirm={save}
          onCancel={() => setConfirm(false)}
        />
      )}
    </>
  );
}
