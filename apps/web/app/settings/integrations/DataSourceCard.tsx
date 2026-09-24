'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { md } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { DATASOURCE_CATEGORY, FORM_FIELDS, MODULE_FIELDS, fieldLabel } from '@/lib/formFields';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';
import { ConfirmDialog, type ChangeLine } from '../../ConfirmDialog';

const FORMS = ['organization', 'driver', 'vehicle', 'employee'] as const;
type Mode = 'MANUAL' | 'UNIFIED';

/**
 * «Кто ведёт справочники» (решение владельца 24.09.2026): компании, водители, ТС и сотрудники —
 * вручную в платформе (как сейчас) или в единой платформе транспорта e-Transport. Переключается
 * в день подключения, без выпуска новой версии. Правило на сервере — MasterDataSourcePolicy.
 */
export function DataSourceCard() {
  const { t } = useT();
  const { roles } = useAuth();
  const canEdit = roles.includes('SYSTEM_ADMIN');
  const [saved, setSaved] = useState<Record<string, Mode>>({});
  const [draft, setDraft] = useState<Record<string, Mode>>({});
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);

  const load = useCallback(async () => {
    try {
      const list = await md.settings(DATASOURCE_CATEGORY);
      const m: Record<string, Mode> = {};
      for (const f of FORMS) {
        const v = (list.find(s => s.settingKey === f)?.settingValue ?? 'MANUAL').toUpperCase();
        m[f] = v === 'UNIFIED' ? 'UNIFIED' : 'MANUAL';
      }
      setSaved(m); setDraft(m); setError('');
    } catch (e) { setError((e as Error).message); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const vLabel = (v: Mode | undefined) => t(`ds.v.${v ?? 'MANUAL'}`);
  const changes: ChangeLine[] = useMemo(() => FORMS
    .filter(f => saved[f] !== draft[f])
    .map(f => ({ label: t(`ds.form.${f}`), from: vLabel(saved[f]), to: vLabel(draft[f]) })),
  // eslint-disable-next-line react-hooks/exhaustive-deps
  [saved, draft, t]);

  const consequences = useMemo(() => {
    const out: string[] = [];
    if (FORMS.some(f => saved[f] !== draft[f] && draft[f] === 'UNIFIED')) {
      out.push(t('ds.cons.unified'), t('ds.cons.locked'), t('ds.cons.old'));
    }
    if (FORMS.some(f => saved[f] !== draft[f] && draft[f] === 'MANUAL')) out.push(t('ds.cons.manual'));
    return out;
  }, [saved, draft, t]);

  async function save() {
    setBusy(true); setError('');
    try {
      for (const f of FORMS) {
        if (saved[f] !== draft[f]) await md.saveSetting(DATASOURCE_CATEGORY, f, draft[f]);
      }
      setConfirm(false); setOk(t('ds.saved'));
      window.setTimeout(() => setOk(''), 3000);
      await load();
    } catch (e) { setError((e as Error).message); setConfirm(false); }
    finally { setBusy(false); }
  }

  const moduleFieldsText = (form: string) => (MODULE_FIELDS[form] ?? [])
    .map(k => FORM_FIELDS[form]?.find(f => f.key === k))
    .filter(Boolean)
    .map(f => fieldLabel(t, f!, false))
    .join(', ');

  return (
    <div className="card" style={{ padding: 16, marginBottom: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <span className="k-ic ic-blue"><Icon d={P.globe} cls="" /></span>
        <div style={{ fontWeight: 700, fontSize: 14 }}>{t('ds.h')}</div>
      </div>
      <div className="hint" style={{ marginBottom: 12 }}>{t('ds.lead')}</div>
      {!canEdit && <div className="hint" style={{ marginBottom: 12 }}>{t('set.readonly')}</div>}
      {error && <div className="error" style={{ marginBottom: 10 }}>{error}</div>}
      {ok && <div className="success" style={{ marginBottom: 10 }}>{ok}</div>}
      <table>
        <thead>
          <tr><th>{t('ds.col.what')}</th><th style={{ width: 260 }}>{t('ds.col.who')}</th><th>{t('ds.col.module')}</th></tr>
        </thead>
        <tbody>
          {FORMS.map(f => (
            <tr key={f}>
              <td style={{ fontWeight: 600 }}>{t(`ds.form.${f}`)}</td>
              <td>
                <select aria-label={t(`ds.form.${f}`)} value={draft[f] ?? 'MANUAL'} disabled={!canEdit || busy}
                  onChange={e => { setOk(''); setDraft({ ...draft, [f]: e.target.value as Mode }); }}>
                  <option value="MANUAL">{t('ds.v.MANUAL')}</option>
                  <option value="UNIFIED">{t('ds.v.UNIFIED')}</option>
                </select>
              </td>
              <td style={{ fontSize: 12.5, color: 'var(--muted)' }}>{moduleFieldsText(f)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {canEdit && (
        <div style={{ display: 'flex', gap: 10, marginTop: 12 }}>
          <button className="btn" disabled={busy || changes.length === 0} onClick={() => setConfirm(true)}>{t('btn.save')}</button>
          <button className="btn secondary" disabled={busy || changes.length === 0} onClick={() => setDraft(saved)}>{t('cfm.discard')}</button>
        </div>
      )}
      {confirm && (
        <ConfirmDialog title={t('ds.confirm.title')} changes={changes} consequences={consequences}
          busy={busy} onConfirm={save} onCancel={() => setConfirm(false)} />
      )}
    </div>
  );
}
