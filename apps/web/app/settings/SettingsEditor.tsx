'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { md, type PlatformSetting } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { ConfirmDialog, type ChangeLine } from '../ConfirmDialog';

/**
 * Обобщённый редактор настроек платформы одной категории. Настройки самоописываемы
 * (тип + метка приходят с бэкенда) → рендер формы универсален. Изменение — только
 * SYSTEM_ADMIN (POST /settings, аудит); остальным поля заблокированы.
 *
 * Правки НЕ применяются сразу (замечание владельца 22.09: «можно случайно что-то отключить»):
 * значения копятся в черновике, внизу появляется панель «Сохранить изменения», а перед записью
 * показывается окно со списком правок «было → стало» и последствиями.
 */
export function SettingsEditor({ category }: { category: string }) {
  const { t, lang } = useT();
  const { roles } = useAuth();
  const canEdit = roles.includes('SYSTEM_ADMIN');
  const [items, setItems] = useState<PlatformSetting[]>([]);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    md.settings(category)
      .then(list => { setItems(list); setDraft(Object.fromEntries(list.map(s => [s.settingKey, s.settingValue ?? '']))); })
      .catch(e => setError((e as Error).message))
      .finally(() => setLoading(false));
  }, [category]);
  useEffect(() => { load(); }, [load]);

  const label = (s: PlatformSetting) => (lang === 'tj' && s.nameTj ? s.nameTj : s.nameRu);
  const shown = (s: PlatformSetting, v: string) =>
    s.valueType === 'BOOLEAN' ? (v === 'true' ? t('pol.on') : t('pol.off')) : v;

  const changed = useMemo(
    () => items.filter(s => (draft[s.settingKey] ?? '') !== (s.settingValue ?? '')),
    [items, draft],
  );

  const changeLines: ChangeLine[] = changed.map(s => ({
    label: label(s),
    from: shown(s, s.settingValue ?? ''),
    to: shown(s, draft[s.settingKey] ?? ''),
  }));

  // Последствия: общий текст + отдельная строка, если что-то выключается.
  const consequences = useMemo(() => {
    const out = [t('cfm.set.scope'), t('cfm.set.audit'), t('cfm.set.waybills')];
    if (changed.some(s => s.valueType === 'BOOLEAN' && (s.settingValue ?? '') === 'true' && draft[s.settingKey] === 'false')) {
      out.unshift(t('cfm.set.turnedoff'));
    }
    return out;
  }, [changed, draft, t]);

  async function applyChanges() {
    setBusy(true); setError('');
    try {
      for (const s of changed) {
        const updated = await md.saveSetting(category, s.settingKey, (draft[s.settingKey] ?? '').trim());
        setItems(prev => prev.map(x => (x.settingKey === s.settingKey ? updated : x)));
      }
      setConfirming(false);
      setOk(t('set.saved'));
      window.setTimeout(() => setOk(''), 2500);
      load();
    } catch (e) {
      setError((e as Error).message);
      setConfirming(false);
      load();
    } finally {
      setBusy(false);
    }
  }

  if (loading) return <div className="card" style={{ color: 'var(--muted)' }}>{t('mon.loading')}</div>;

  return (
    <div className="card">
      {error && <div className="error" style={{ marginBottom: 12 }}>{error}</div>}
      {ok && <div className="success" style={{ marginBottom: 12 }}>{ok}</div>}
      {!canEdit && <div className="hint" style={{ marginBottom: 14 }}>{t('set.readonly')}</div>}
      <div style={{ display: 'grid', gap: 4 }}>
        {items.map((s, i) => {
          const dirty = (draft[s.settingKey] ?? '') !== (s.settingValue ?? '');
          return (
            <div key={s.settingKey} style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '12px 0', borderTop: i === 0 ? 'none' : '1px solid var(--line-soft)' }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 14 }}>{label(s)}</div>
                <div style={{ fontSize: 11.5, color: 'var(--muted)', fontFamily: 'var(--mono)', marginTop: 2 }}>{s.settingKey}</div>
              </div>
              <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 10 }}>
                {dirty && <span className="badge amber" style={{ fontSize: 11 }}>{t('cfm.unsaved')}</span>}
                <SettingInput
                  s={s}
                  value={draft[s.settingKey] ?? ''}
                  canEdit={canEdit}
                  onChange={v => setDraft(d => ({ ...d, [s.settingKey]: v }))}
                  onLabel={t('pol.on')}
                  offLabel={t('pol.off')}
                />
              </div>
            </div>
          );
        })}
        {items.length === 0 && <div style={{ color: 'var(--muted)', fontSize: 13, padding: 8 }}>{t('common.norecords')}</div>}
      </div>

      {canEdit && changed.length > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 16, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
          <span style={{ fontSize: 13, color: 'var(--muted)' }}>{t('cfm.changedcount')}: <b style={{ color: 'var(--ink)' }}>{changed.length}</b></span>
          <span style={{ flex: 1 }} />
          <button type="button" className="btn secondary" onClick={() => setDraft(Object.fromEntries(items.map(s => [s.settingKey, s.settingValue ?? ''])))}>
            {t('cfm.discard')}
          </button>
          <button type="button" className="btn" onClick={() => setConfirming(true)}>{t('btn.save')}</button>
        </div>
      )}

      {confirming && (
        <ConfirmDialog
          title={t('cfm.set.title')}
          changes={changeLines}
          consequences={consequences}
          busy={busy}
          onConfirm={applyChanges}
          onCancel={() => setConfirming(false)}
          confirmLabel={t('cfm.saveapply')}
        />
      )}
    </div>
  );
}

function SettingInput({ s, value, canEdit, onChange, onLabel, offLabel }:
  { s: PlatformSetting; value: string; canEdit: boolean; onChange: (v: string) => void; onLabel: string; offLabel: string }) {
  if (s.valueType === 'BOOLEAN') {
    const on = value === 'true';
    return (
      <button type="button" className={`badge ${on ? 'green' : 'gray'}`}
        style={{ cursor: canEdit ? 'pointer' : 'default', border: 'none', opacity: canEdit ? 1 : 0.7 }}
        disabled={!canEdit} onClick={() => onChange(on ? 'false' : 'true')} aria-pressed={on}>
        {on ? onLabel : offLabel}
      </button>
    );
  }
  if (s.valueType === 'ENUM') {
    const opts = (s.options ?? '').split(',').map(x => x.trim()).filter(Boolean);
    return (
      <select disabled={!canEdit} value={value} onChange={e => onChange(e.target.value)} style={{ minWidth: 120 }}>
        {opts.map(o => <option key={o} value={o}>{o}</option>)}
      </select>
    );
  }
  return (
    <input type={s.valueType === 'NUMBER' ? 'number' : 'text'} value={value} disabled={!canEdit}
      onChange={e => onChange(e.target.value)} style={{ minWidth: 240 }} />
  );
}
