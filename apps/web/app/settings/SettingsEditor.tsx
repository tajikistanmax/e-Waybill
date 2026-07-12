'use client';

import { useCallback, useEffect, useState } from 'react';
import { md, type PlatformSetting } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';

/**
 * Обобщённый редактор настроек платформы одной категории. Настройки самоописываемы
 * (тип + метка приходят с бэкенда) → рендер формы универсален. Изменение — только
 * SYSTEM_ADMIN (POST /settings, аудит); остальным поля заблокированы.
 */
export function SettingsEditor({ category }: { category: string }) {
  const { t, lang } = useT();
  const { roles } = useAuth();
  const canEdit = roles.includes('SYSTEM_ADMIN');
  const [items, setItems] = useState<PlatformSetting[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    md.settings(category).then(setItems).catch(e => setError((e as Error).message)).finally(() => setLoading(false));
  }, [category]);
  useEffect(() => { load(); }, [load]);

  const save = async (key: string, value: string) => {
    setError('');
    try {
      const updated = await md.saveSetting(category, key, value);
      setItems(prev => prev.map(x => (x.settingKey === key ? updated : x)));
      setSaved(key);
      window.setTimeout(() => setSaved(s => (s === key ? null : s)), 1800);
    } catch (e) { setError((e as Error).message); load(); }
  };

  const label = (s: PlatformSetting) => (lang === 'tj' && s.nameTj ? s.nameTj : s.nameRu);

  if (loading) return <div className="card" style={{ color: 'var(--muted)' }}>{t('mon.loading')}</div>;

  return (
    <div className="card">
      {error && <div className="error" style={{ marginBottom: 12 }}>{error}</div>}
      {!canEdit && <div className="hint" style={{ marginBottom: 14 }}>{t('set.readonly')}</div>}
      <div style={{ display: 'grid', gap: 4 }}>
        {items.map((s, i) => (
          <div key={s.settingKey} style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '12px 0', borderTop: i === 0 ? 'none' : '1px solid var(--line-soft)' }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontWeight: 600, fontSize: 14 }}>{label(s)}</div>
              <div style={{ fontSize: 11.5, color: 'var(--muted)', fontFamily: 'var(--mono)', marginTop: 2 }}>{s.settingKey}</div>
            </div>
            <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 10 }}>
              {saved === s.settingKey && <span style={{ color: 'var(--green)', fontSize: 12.5, fontWeight: 600 }}>✓ {t('set.saved')}</span>}
              <SettingInput s={s} canEdit={canEdit} onSave={v => save(s.settingKey, v)} onLabel={t('pol.on')} offLabel={t('pol.off')} setLabel={t('pol.set')} />
            </div>
          </div>
        ))}
        {items.length === 0 && <div style={{ color: 'var(--muted)', fontSize: 13, padding: 8 }}>{t('common.norecords')}</div>}
      </div>
    </div>
  );
}

function SettingInput({ s, canEdit, onSave, onLabel, offLabel, setLabel }:
  { s: PlatformSetting; canEdit: boolean; onSave: (v: string) => void; onLabel: string; offLabel: string; setLabel: string }) {
  const [val, setVal] = useState(s.settingValue ?? '');
  useEffect(() => { setVal(s.settingValue ?? ''); }, [s.settingValue]);

  if (s.valueType === 'BOOLEAN') {
    const on = val === 'true';
    return (
      <button type="button" className={`badge ${on ? 'green' : 'gray'}`}
        style={{ cursor: canEdit ? 'pointer' : 'default', border: 'none', opacity: canEdit ? 1 : 0.7 }}
        disabled={!canEdit} onClick={() => { const nv = on ? 'false' : 'true'; setVal(nv); onSave(nv); }} aria-pressed={on}>
        {on ? onLabel : offLabel}
      </button>
    );
  }
  if (s.valueType === 'ENUM') {
    const opts = (s.options ?? '').split(',').map(x => x.trim()).filter(Boolean);
    return (
      <select disabled={!canEdit} value={val} onChange={e => { setVal(e.target.value); onSave(e.target.value); }} style={{ minWidth: 120 }}>
        {opts.map(o => <option key={o} value={o}>{o}</option>)}
      </select>
    );
  }
  const dirty = val.trim() !== (s.settingValue ?? '');
  return (
    <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
      <input type={s.valueType === 'NUMBER' ? 'number' : 'text'} value={val} disabled={!canEdit}
        onChange={e => setVal(e.target.value)} style={{ minWidth: 240 }} />
      {canEdit && dirty && (
        <button type="button" className="badge blue" style={{ cursor: 'pointer', border: 'none' }}
          onClick={() => onSave(val.trim())}>{setLabel}</button>
      )}
    </span>
  );
}
