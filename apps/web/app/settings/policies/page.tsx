'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { md, type Policy } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** Каталог правил движка политик: тип значения (bool — тумблер, int — число). */
const RULE_TYPES: Record<string, 'bool' | 'int'> = {
  require_med_pre: 'bool',
  require_tech_check: 'bool',
  require_med_post: 'bool',
  require_gps: 'bool',
  max_validity_days: 'int',
};
const ALL_RULES = Object.keys(RULE_TYPES);

export default function PoliciesSettingsPage() {
  const { t, tType } = useT();
  const { roles } = useAuth();
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [rows, setRows] = useState<Policy[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<{ scopeKey: string; ruleKey: string; ruleValue: string }>(
    { scopeKey: '', ruleKey: ALL_RULES[0], ruleValue: 'true' });

  const reload = useCallback(async () => {
    try { setRows(await md.policies()); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const flash = (msg: string) => { setOk(msg); setTimeout(() => setOk(''), 2000); };

  async function save(body: Record<string, unknown>) {
    setBusy(true); setError('');
    try { await md.savePolicy(body); await reload(); flash(t('pol.saved')); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  async function remove(id: string) {
    setBusy(true); setError('');
    try { await md.deletePolicy(id); await reload(); flash(t('pol.saved')); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  const national = (rule: string) => rows.find(p => p.scopeLevel === 'NATIONAL' && p.ruleKey === rule);
  const byType = rows.filter(p => p.scopeLevel === 'VEHICLE_TYPE').sort((a, b) => a.ruleKey.localeCompare(b.ruleKey));
  const byOrg = rows.filter(p => p.scopeLevel === 'ORGANIZATION').sort((a, b) => a.scopeKey.localeCompare(b.scopeKey));

  const ruleName = (key: string) => {
    const translated = t(`pol.r.${key}`);
    return translated === `pol.r.${key}` ? key : translated;
  };

  const Toggle = ({ value, disabled, onChange }: { value: boolean; disabled?: boolean; onChange: (v: boolean) => void }) => (
    <button
      type="button"
      className={`badge ${value ? 'green' : 'gray'}`}
      style={{ cursor: disabled ? 'default' : 'pointer', border: 'none', opacity: disabled ? 0.7 : 1 }}
      disabled={disabled || busy}
      onClick={() => onChange(!value)}
    >
      {value ? t('pol.on') : t('pol.off')}
    </button>
  );

  /** Числовое значение с сохранением по кнопке (пусто = лимит типа). */
  const NumberValue = ({ current, disabled, onSave }: { current?: string; disabled?: boolean; onSave: (v: string) => void }) => {
    const [v, setV] = useState(current ?? '');
    useEffect(() => { setV(current ?? ''); }, [current]);
    const dirty = (v.trim() || '') !== (current ?? '');
    return (
      <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
        <input type="number" min={1} value={v} disabled={disabled || busy}
          onChange={e => setV(e.target.value)} placeholder="—" style={{ width: 68 }} />
        {dirty && !disabled && (
          <button type="button" className="badge blue" style={{ cursor: 'pointer', border: 'none' }}
            disabled={busy} onClick={() => onSave(v.trim())}>{t('pol.set')}</button>
        )}
      </span>
    );
  };

  const removeBtn = (id: string) => (
    <button title={t('pol.remove')} disabled={busy} onClick={() => remove(id)}
      style={{ background: 'none', border: '1px solid var(--line)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--red)' }}>
      <Icon d={P.trash} cls="" style={{ width: 15, height: 15 }} />
    </button>
  );

  /** Ячейка значения политики в таблицах переопределений (по типу значения правила). */
  const valueCell = (p: Policy, canEdit: boolean) =>
    RULE_TYPES[p.ruleKey] === 'int'
      ? <NumberValue current={p.ruleValue} disabled={!canEdit}
          onSave={v => save({ scopeLevel: p.scopeLevel, scopeKey: p.scopeKey, ruleKey: p.ruleKey, ruleValue: v })} />
      : <Toggle value={p.ruleValue === 'true'} disabled={!canEdit}
          onChange={v => save({ scopeLevel: p.scopeLevel, scopeKey: p.scopeKey, ruleKey: p.ruleKey, ruleValue: String(v) })} />;

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.rules')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('pol.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('pol.note')}</div>
      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}
      {ok && <div className="badge green" style={{ marginBottom: 14 }}>{ok}</div>}

      {/* Национальные умолчания */}
      <div className="card" style={{ marginBottom: 18 }}>
        <h3 style={{ margin: '4px 0 12px' }}>{t('pol.national')}</h3>
        <table>
          <thead><tr><th>{t('pol.rule')}</th><th style={{ width: 160 }}>{t('pol.value')}</th></tr></thead>
          <tbody>
            {ALL_RULES.map(rule => {
              const p = national(rule);
              return (
                <tr key={rule}>
                  <td style={{ fontWeight: 600 }}>{ruleName(rule)}</td>
                  <td>
                    {RULE_TYPES[rule] === 'int'
                      ? <NumberValue current={p?.ruleValue} disabled={!isSysAdmin}
                          onSave={v => save({ scopeLevel: 'NATIONAL', ruleKey: rule, ruleValue: v })} />
                      : <Toggle value={p ? p.ruleValue === 'true' : false} disabled={!isSysAdmin}
                          onChange={v => save({ scopeLevel: 'NATIONAL', ruleKey: rule, ruleValue: String(v) })} />}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <div className="hint" style={{ marginTop: 10 }}>{t('pol.numhint')}</div>
      </div>

      {/* Переопределения по типу ПЛ */}
      <div className="card" style={{ marginBottom: 18 }}>
        <h3 style={{ margin: '4px 0 12px' }}>{t('pol.bytype')}</h3>
        <table>
          <thead><tr><th>{t('col.type')}</th><th>{t('pol.rule')}</th><th style={{ width: 160 }}>{t('pol.value')}</th><th style={{ width: 80 }} /></tr></thead>
          <tbody>
            {byType.length === 0 && <tr><td colSpan={4} style={{ color: 'var(--muted)' }}>{t('pol.empty')}</td></tr>}
            {byType.map(p => (
              <tr key={p.id}>
                <td style={{ fontWeight: 600 }}>{tType(p.scopeKey)}</td>
                <td>{ruleName(p.ruleKey)}</td>
                <td>{valueCell(p, isSysAdmin)}</td>
                <td>{isSysAdmin && removeBtn(p.id)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Переопределения по организации */}
      <div className="card">
        <h3 style={{ margin: '4px 0 12px' }}>{t('pol.byorg')}</h3>
        <table>
          <thead><tr><th>{t('pol.org')}</th><th>{t('pol.rule')}</th><th style={{ width: 160 }}>{t('pol.value')}</th><th style={{ width: 80 }} /></tr></thead>
          <tbody>
            {byOrg.length === 0 && <tr><td colSpan={4} style={{ color: 'var(--muted)' }}>{t('pol.empty')}</td></tr>}
            {byOrg.map(p => (
              <tr key={p.id}>
                <td><span className="number">{p.scopeKey}</span></td>
                <td>{ruleName(p.ruleKey)}</td>
                <td>{valueCell(p, true)}</td>
                <td>{removeBtn(p.id)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        {/* Добавление переопределения организации */}
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('pol.org')}
            <input value={form.scopeKey} onChange={e => setForm({ ...form, scopeKey: e.target.value })}
              placeholder="025680800" style={{ width: 160 }} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('pol.rule')}
            <select value={form.ruleKey}
              onChange={e => setForm({ ...form, ruleKey: e.target.value, ruleValue: RULE_TYPES[e.target.value] === 'int' ? '1' : 'true' })}>
              {ALL_RULES.map(r => <option key={r} value={r}>{ruleName(r)}</option>)}
            </select>
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('pol.value')}
            {RULE_TYPES[form.ruleKey] === 'int'
              ? <input type="number" min={1} value={form.ruleValue}
                  onChange={e => setForm({ ...form, ruleValue: e.target.value })} style={{ width: 100 }} />
              : <select value={form.ruleValue} onChange={e => setForm({ ...form, ruleValue: e.target.value })}>
                  <option value="true">{t('pol.on')}</option>
                  <option value="false">{t('pol.off')}</option>
                </select>}
          </label>
          <button className="btn" disabled={busy || !form.scopeKey.trim() || !form.ruleValue.trim()}
            onClick={() => save({ scopeLevel: 'ORGANIZATION', scopeKey: form.scopeKey.trim(), ruleKey: form.ruleKey, ruleValue: form.ruleValue.trim() })}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('pol.add')}
          </button>
        </div>
      </div>
    </>
  );
}
