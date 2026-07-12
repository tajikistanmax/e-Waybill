'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { useRoleAccess } from '@/lib/roleaccess';
import { Icon, P } from '../../icons';
import { md, type RoleAccess } from '@/lib/api';
import { type NavKey } from '@/lib/roles';

/** Порядок разделов в матрице. */
const SECTIONS: NavKey[] = ['dashboard', 'dispatcher', 'waybills', 'med', 'tech', 'driver', 'inspector', 'fleet', 'monitoring', 'company', 'registry', 'violations', 'reports', 'dictionaries', 'settings'];

const NAV_KEY: Record<NavKey, string> = {
  dashboard: 'nav.dashboard', dispatcher: 'nav.dispatcher', waybills: 'nav.waybills', med: 'nav.med',
  tech: 'nav.tech', driver: 'nav.driver', inspector: 'nav.inspector', fleet: 'nav.fleet',
  monitoring: 'nav.monitoring', company: 'nav.company', registry: 'nav.registry',
  violations: 'nav.violations', reports: 'nav.reports', dictionaries: 'nav.dictionaries', settings: 'nav.settings',
};

type Draft = { homeKey: string; nav: Set<string> };

export default function RolesSettingsPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const { reload } = useRoleAccess();
  const canEdit = roles.includes('SYSTEM_ADMIN');

  const [list, setList] = useState<RoleAccess[] | null>(null);
  const [draft, setDraft] = useState<Record<string, Draft>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<Record<string, { ok: boolean; text: string }>>({});

  useEffect(() => {
    md.roleAccess()
      .then(rows => {
        setList(rows);
        setDraft(Object.fromEntries(rows.map(r => [r.role, { homeKey: r.homeKey, nav: new Set(r.navKeys) }])));
      })
      .catch(() => setList([]));
  }, []);

  function toggle(role: string, key: NavKey) {
    if (!canEdit) return;
    // Защита от само-локаута: у SYSTEM_ADMIN «Настройки» снять нельзя.
    if (role === 'SYSTEM_ADMIN' && key === 'settings') return;
    setDraft(d => {
      const cur = d[role]; if (!cur) return d;
      const nav = new Set(cur.nav);
      if (nav.has(key)) nav.delete(key); else nav.add(key);
      // Стартовый раздел должен оставаться доступным.
      let homeKey = cur.homeKey;
      if (!nav.has(homeKey)) homeKey = nav.values().next().value ?? '';
      return { ...d, [role]: { homeKey, nav } };
    });
    setMsg(m => ({ ...m, [role]: undefined! }));
  }

  function setHome(role: string, homeKey: string) {
    setDraft(d => ({ ...d, [role]: { ...d[role], homeKey } }));
  }

  async function save(role: string) {
    const cur = draft[role]; if (!cur) return;
    setBusy(role); setMsg(m => ({ ...m, [role]: undefined! }));
    try {
      await md.saveRoleAccess(role, cur.homeKey, [...cur.nav]);
      reload(); // обновить меню/навигацию во всём приложении
      setMsg(m => ({ ...m, [role]: { ok: true, text: t('common.saved') } }));
    } catch (err) {
      setMsg(m => ({ ...m, [role]: { ok: false, text: err instanceof Error ? err.message : String(err) } }));
    } finally {
      setBusy(null);
    }
  }

  const dirty = (r: RoleAccess) => {
    const d = draft[r.role]; if (!d) return false;
    return d.homeKey !== r.homeKey || d.nav.size !== r.navKeys.length || r.navKeys.some(k => !d.nav.has(k));
  };

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.roles')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setroles.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{canEdit ? t('setroles.note.edit') : t('set.readonly')}</div>

      {list === null ? (
        <div className="hint">{t('common.loading')}</div>
      ) : (
        <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(430px, 1fr))', gap: 14 }}>
          {list.map(r => {
            const d = draft[r.role]; if (!d) return null;
            const homeOptions = SECTIONS.filter(s => d.nav.has(s));
            const m = msg[r.role];
            return (
              <div className="card" key={r.role} style={{ padding: 16 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
                  <span className="k-ic ic-blue"><Icon d={P.user} cls="" /></span>
                  <div style={{ fontWeight: 700, fontSize: 14, flex: 1 }}>{t(`role.${r.role}`)}</div>
                  <span className="badge blue" style={{ fontFamily: 'var(--mono)', fontSize: 10.5 }}>{r.role}</span>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
                  <span style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('setroles.home')}:</span>
                  <select value={d.homeKey} onChange={e => setHome(r.role, e.target.value)} disabled={!canEdit} style={{ padding: '4px 8px', borderRadius: 7, border: '1px solid var(--line)', fontSize: 12.5 }}>
                    {homeOptions.map(s => <option key={s} value={s}>{t(NAV_KEY[s])}</option>)}
                  </select>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 12px' }}>
                  {SECTIONS.map(s => {
                    const locked = r.role === 'SYSTEM_ADMIN' && s === 'settings';
                    return (
                      <label key={s} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 12.5, cursor: canEdit && !locked ? 'pointer' : 'default', opacity: locked ? 0.7 : 1 }}>
                        <input type="checkbox" checked={d.nav.has(s)} disabled={!canEdit || locked} onChange={() => toggle(r.role, s)} />
                        {t(NAV_KEY[s])}
                      </label>
                    );
                  })}
                </div>

                {canEdit && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 14 }}>
                    <button className="btn" onClick={() => save(r.role)} disabled={busy === r.role || !dirty(r)}>
                      <Icon d={P.check} cls="" style={{ width: 15, height: 15 }} /> {busy === r.role ? '…' : t('btn.save')}
                    </button>
                    {m && <span style={{ fontSize: 12.5, color: m.ok ? 'var(--green)' : 'var(--red)' }}>{m.text}</span>}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
