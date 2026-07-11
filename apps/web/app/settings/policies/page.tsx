'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { md, type Policy } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** Известные правила движка политик (для тумблеров и выпадающих списков). */
const RULES = ['require_med_pre', 'require_tech_check', 'require_med_post', 'require_gps'] as const;

export default function PoliciesSettingsPage() {
  const { t, tType } = useT();
  const { roles } = useAuth();
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [rows, setRows] = useState<Policy[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<{ scopeKey: string; ruleKey: string; ruleValue: string }>(
    { scopeKey: '', ruleKey: RULES[0], ruleValue: 'true' });

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
    const k = `pol.r.${key}`;
    const translated = t(k);
    return translated === k ? key : translated;
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

      {/* Национальные умолчания — тумблеры */}
      <div className="card" style={{ marginBottom: 18 }}>
        <h3 style={{ margin: '4px 0 12px' }}>{t('pol.national')}</h3>
        <table>
          <thead><tr><th>{t('pol.rule')}</th><th style={{ width: 120 }}>{t('pol.value')}</th></tr></thead>
          <tbody>
            {RULES.map(rule => {
              const p = national(rule);
              const on = p ? p.ruleValue === 'true' : false;
              return (
                <tr key={rule}>
                  <td style={{ fontWeight: 600 }}>{ruleName(rule)}</td>
                  <td>
                    <Toggle value={on} disabled={!isSysAdmin}
                      onChange={v => save({ scopeLevel: 'NATIONAL', ruleKey: rule, ruleValue: String(v) })} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Переопределения по типу ПЛ */}
      <div className="card" style={{ marginBottom: 18 }}>
        <h3 style={{ margin: '4px 0 12px' }}>{t('pol.bytype')}</h3>
        <table>
          <thead><tr><th>{t('col.type')}</th><th>{t('pol.rule')}</th><th style={{ width: 110 }}>{t('pol.value')}</th><th style={{ width: 100 }} /></tr></thead>
          <tbody>
            {byType.length === 0 && <tr><td colSpan={4} style={{ color: 'var(--muted)' }}>{t('pol.empty')}</td></tr>}
            {byType.map(p => (
              <tr key={p.id}>
                <td style={{ fontWeight: 600 }}>{tType(p.scopeKey)}</td>
                <td>{ruleName(p.ruleKey)}</td>
                <td>
                  <Toggle value={p.ruleValue === 'true'} disabled={!isSysAdmin}
                    onChange={v => save({ scopeLevel: 'VEHICLE_TYPE', scopeKey: p.scopeKey, ruleKey: p.ruleKey, ruleValue: String(v) })} />
                </td>
                <td>
                  {isSysAdmin && (
                    <button title={t('pol.remove')} disabled={busy} onClick={() => remove(p.id)}
                      style={{ background: 'none', border: '1px solid var(--line)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--red)' }}>
                      <Icon d={P.trash} cls="" style={{ width: 15, height: 15 }} />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Переопределения по организации */}
      <div className="card">
        <h3 style={{ margin: '4px 0 12px' }}>{t('pol.byorg')}</h3>
        <table>
          <thead><tr><th>{t('pol.org')}</th><th>{t('pol.rule')}</th><th style={{ width: 110 }}>{t('pol.value')}</th><th style={{ width: 100 }} /></tr></thead>
          <tbody>
            {byOrg.length === 0 && <tr><td colSpan={4} style={{ color: 'var(--muted)' }}>{t('pol.empty')}</td></tr>}
            {byOrg.map(p => (
              <tr key={p.id}>
                <td><span className="number">{p.scopeKey}</span></td>
                <td>{ruleName(p.ruleKey)}</td>
                <td>
                  <Toggle value={p.ruleValue === 'true'}
                    onChange={v => save({ scopeLevel: 'ORGANIZATION', scopeKey: p.scopeKey, ruleKey: p.ruleKey, ruleValue: String(v) })} />
                </td>
                <td>
                  <button className="btn-icon" title={t('pol.remove')} disabled={busy} onClick={() => remove(p.id)}>
                    <Icon d={P.trash} cls="" style={{ width: 15, height: 15 }} />
                  </button>
                </td>
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
            <select value={form.ruleKey} onChange={e => setForm({ ...form, ruleKey: e.target.value })}>
              {RULES.map(r => <option key={r} value={r}>{ruleName(r)}</option>)}
            </select>
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('pol.value')}
            <select value={form.ruleValue} onChange={e => setForm({ ...form, ruleValue: e.target.value })}>
              <option value="true">{t('pol.on')}</option>
              <option value="false">{t('pol.off')}</option>
            </select>
          </label>
          <button className="btn" disabled={busy || !form.scopeKey.trim()}
            onClick={() => save({ scopeLevel: 'ORGANIZATION', scopeKey: form.scopeKey.trim(), ruleKey: form.ruleKey, ruleValue: form.ruleValue })}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('pol.add')}
          </button>
        </div>
      </div>
    </>
  );
}
