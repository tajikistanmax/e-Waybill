'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { md, type PlatformUser, type PlatformUserPage } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

type Row = Record<string, unknown>;

// Те же списки, что в PlatformUserController: платформенные роли — без организации,
// роли перевозчика — с обязательной организацией.
const PLATFORM_ROLES = ['SYSTEM_ADMIN', 'MINTRANS_ANALYST', 'INSPECTOR', 'CUSTOMS_OFFICER'];
const ORGANIZATION_ROLES = ['COMPANY_ADMIN', 'BRANCH_ADMIN', 'DISPATCHER', 'DOCTOR', 'MECHANIC', 'DRIVER', 'ACCOUNTANT', 'FUEL_STATION'];
const ASSIGNABLE = [...PLATFORM_ROLES, ...ORGANIZATION_ROLES];
// В фильтре — ещё кабинеты контрагентов и служебные учётки (их выдают не здесь).
const FILTER_ROLES = [...ASSIGNABLE, 'CLIENT_SENDER', 'CLIENT_FORWARDER', 'API_INTEGRATOR'];
const PAGE_SIZE = 50;
const NO_ORG = '__none__';
const dim = { color: 'var(--muted)' } as const;
const smallBtn = { padding: '5px 10px', fontSize: 12, whiteSpace: 'nowrap' } as const;
const fmt = (iso: string | null) => (iso
  ? new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
  : null);

/**
 * «Пользователи» — все учётные записи платформы (замена legacy /admin/user). Только администратор
 * платформы. «Доступы» остаются рабочим местом по одной организации; здесь — поиск по всей
 * платформе и учётки без организации (администраторы, аналитики, инспекторы, таможня).
 */
export default function PlatformUsersPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const isAdmin = roles.includes('SYSTEM_ADMIN');

  const [orgs, setOrgs] = useState<Row[]>([]);
  const [data, setData] = useState<PlatformUserPage | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [freshPassword, setFreshPassword] = useState<{ username: string; password: string } | null>(null);

  // Фильтры. Поиск по тексту — с задержкой, чтобы не дёргать сервер на каждую букву.
  const [q, setQ] = useState('');
  const [qApplied, setQApplied] = useState('');
  const [role, setRole] = useState('');
  const [org, setOrg] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => {
    const h = setTimeout(() => { setQApplied(q.trim()); setPage(0); }, 350);
    return () => clearTimeout(h);
  }, [q]);

  // Форма новой учётки.
  const [fUsername, setFUsername] = useState('');
  const [fName, setFName] = useState('');
  const [fRole, setFRole] = useState('MINTRANS_ANALYST');
  const [fOrg, setFOrg] = useState('');
  const [fOrgQuery, setFOrgQuery] = useState('');

  // Смена роли: учётка, которую правим, и черновик роли/организации.
  const [editId, setEditId] = useState<string | null>(null);
  const [eRole, setERole] = useState('');
  const [eOrg, setEOrg] = useState('');

  const sortedOrgs = useMemo(
    () => [...orgs].sort((a, b) => String(a.name ?? '').localeCompare(String(b.name ?? ''), 'ru')),
    [orgs]);
  const orgName = useCallback((rma: string | null) => {
    if (!rma) return null;
    const o = orgs.find(x => String(x.rma) === rma);
    return o ? String(o.name) : rma;
  }, [orgs]);
  const orgLabel = (o: Row) => `${String(o.name ?? o.rma)} · ${String(o.rma)}${o.parentRma ? t('access.f.branch.suffix') : ''}`;
  // Организация в форме: сотни организаций — отбор по названию/РМА; выбранная остаётся в списке.
  const formOrgs = useMemo(() => {
    const s = fOrgQuery.trim().toLowerCase();
    if (!s) return sortedOrgs;
    const hit = sortedOrgs.filter(o => String(o.name ?? '').toLowerCase().includes(s) || String(o.rma ?? '').includes(s));
    const cur = sortedOrgs.find(o => String(o.rma) === fOrg);
    return cur && !hit.includes(cur) ? [cur, ...hit] : hit;
  }, [sortedOrgs, fOrgQuery, fOrg]);

  useEffect(() => {
    if (!isAdmin) return;
    md.organizations().then(setOrgs).catch(e => setErr(e instanceof Error ? e.message : String(e)));
  }, [isAdmin]);

  const load = useCallback(async () => {
    if (!isAdmin) return;
    setErr('');
    try {
      setData(await md.platformUsers.list({
        q: qApplied || undefined,
        role: role || undefined,
        organizationRma: org && org !== NO_ORG ? org : undefined,
        withoutOrganization: org === NO_ORG,
        status: status || undefined,
        page,
        size: PAGE_SIZE,
      }));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }, [isAdmin, qApplied, role, org, status, page]);

  useEffect(() => { load(); }, [load]);

  const act = async (fn: () => Promise<unknown>) => {
    setErr('');
    try { await fn(); await load(); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  };

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr(''); setFreshPassword(null);
    try {
      const parts = fName.trim().split(/\s+/).filter(Boolean);
      const created = await md.platformUsers.create({
        username: fUsername.trim(),
        lastName: parts[0] ?? '',
        firstName: parts.slice(1).join(' '),
        role: fRole,
        organizationRma: fOrg || undefined,
      });
      if (created.temporaryPassword) setFreshPassword({ username: created.username, password: created.temporaryPassword });
      setFUsername(''); setFName('');
      await load();
    } catch (e2) {
      setErr(e2 instanceof Error ? e2.message : String(e2));
    } finally {
      setBusy(false);
    }
  }

  const roleOf = (u: PlatformUser) => u.roles.find(r => FILTER_ROLES.includes(r)) ?? (u.roles[0] ?? '');
  const actionable = (u: PlatformUser) => !u.self && !u.service;

  function startEdit(u: PlatformUser) {
    setEditId(u.id);
    const r = roleOf(u);
    setERole(ASSIGNABLE.includes(r) ? r : 'DISPATCHER');
    setEOrg(u.organizationRma ?? '');
  }

  async function saveRole(u: PlatformUser) {
    if (ORGANIZATION_ROLES.includes(eRole) && !eOrg) { setErr(t('pu.new.orghint')); return; }
    const target = [t('role.' + eRole), orgName(eOrg || null)].filter(Boolean).join(' · ');
    if (!confirm(`${t('pu.role.confirm')} «${u.username}» → ${target}?`)) return;
    await act(async () => { await md.platformUsers.setRole(u.id, eRole, eOrg || undefined); setEditId(null); });
  }

  async function resetPassword(u: PlatformUser) {
    setErr('');
    try {
      const r = await md.orgUsers.resetPassword(u.id);
      if (r.temporaryPassword) setFreshPassword({ username: u.username, password: r.temporaryPassword });
      await load();
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  }

  function runAction(u: PlatformUser, action: string) {
    switch (action) {
      case 'role': startEdit(u); break;
      case 'toggle': act(() => md.orgUsers.setEnabled(u.id, !u.enabled)); break;
      case 'password': resetPassword(u); break;
      case '2fa': act(() => md.orgUsers.setSecondFactorRequired(u.id, !u.secondFactorRequired)); break;
      case '2fa-reset':
        if (confirm(`«${u.username}»: ${t('acc.2fa.reset.confirm')}`)) act(() => md.orgUsers.resetSecondFactor(u.id));
        break;
      case 'delete':
        if (confirm(`${t('access.remove.confirm')} «${u.username}»${t('access.remove.confirm.suffix')}`)) act(() => md.orgUsers.remove(u.id));
        break;
    }
  }

  if (!isAdmin) {
    return <main className="page"><h1>{t('pu.h')}</h1><p style={dim}>{t('pu.forbidden')}</p></main>;
  }

  const rows = data?.content ?? [];
  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const orgNeeded = ORGANIZATION_ROLES.includes(fRole);

  return (
    <main className="page">
      <h1>{t('pu.h')}</h1>
      <p style={{ ...dim, maxWidth: 760 }}>
        {t('pu.intro')} <Link href="/company/access">{t('nav.access')}</Link>
      </p>

      {err && <div className="error">{err}</div>}

      {freshPassword && (
        <div className="card" style={{ borderColor: 'var(--green)', background: 'var(--green-050)' }}>
          <b>{t('access.temppass')} «{freshPassword.username}»:</b>{' '}
          <span className="number" style={{ fontSize: '1.15em', letterSpacing: '.06em' }}>{freshPassword.password}</span>
          <div style={{ ...dim, marginTop: 4 }}>{t('access.temppass.hint')}</div>
          <button className="btn secondary" style={{ ...smallBtn, marginTop: 8 }} onClick={() => setFreshPassword(null)}>{t('access.hide')}</button>
        </div>
      )}

      <div className="card">
        <h2>{t('pu.new.h')}</h2>
        <form className="grid" onSubmit={create}>
          <div>
            <label htmlFor="pu-username">{t('access.f.username')}</label>
            <input id="pu-username" required value={fUsername} onChange={e => setFUsername(e.target.value)} placeholder="+992900000000" />
          </div>
          <div>
            <label htmlFor="pu-fullname">{t('access.f.fullname')}</label>
            <input id="pu-fullname" value={fName} onChange={e => setFName(e.target.value)} placeholder="Каримов Алишер" />
          </div>
          <div>
            <label htmlFor="pu-role">{t('access.f.role')}</label>
            <select id="pu-role" value={fRole} onChange={e => setFRole(e.target.value)} required>
              <optgroup label={t('pu.f.noorg')}>
                {PLATFORM_ROLES.map(r => <option key={r} value={r}>{t('role.' + r)}</option>)}
              </optgroup>
              <optgroup label={t('access.f.org')}>
                {ORGANIZATION_ROLES.map(r => <option key={r} value={r}>{t('role.' + r)}</option>)}
              </optgroup>
            </select>
          </div>
          <div>
            <label htmlFor="pu-org">{t('access.f.org')}{orgNeeded ? ' *' : ''}</label>
            <div style={{ display: 'flex', gap: 6 }}>
              <input value={fOrgQuery} onChange={e => setFOrgQuery(e.target.value)} placeholder={t('pu.find')}
                aria-label={t('access.org.search')} title={t('access.org.search')} style={{ width: 120 }} />
              <select id="pu-org" value={fOrg} onChange={e => setFOrg(e.target.value)} required={orgNeeded} style={{ flex: 1, minWidth: 0 }}>
                <option value="">{orgNeeded ? '—' : t('pu.f.noorg')}</option>
                {formOrgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{orgLabel(o)}</option>)}
              </select>
            </div>
          </div>
          <div className="full hint">
            {t('pu.new.orghint')} {['SYSTEM_ADMIN', 'MINTRANS_ANALYST', 'INSPECTOR'].includes(fRole) ? t('pu.new.2fa') : ''}
          </div>
          <div className="full">
            <button className="btn" disabled={busy || !fUsername.trim() || (orgNeeded && !fOrg)}>
              {busy ? t('access.grant.busy') : t('pu.new.btn')}
            </button>
          </div>
        </form>
      </div>

      <div className="card">
        <h2>{t('pu.total')}: <span className="number">{total}</span></h2>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 8, marginBottom: 12 }}>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('pu.f.search')} aria-label={t('pu.f.search')} />
          <select aria-label={t('access.col.role')} value={role} onChange={e => { setRole(e.target.value); setPage(0); }}>
            <option value="">{t('pu.f.allroles')}</option>
            {FILTER_ROLES.map(r => <option key={r} value={r}>{t('role.' + r)}</option>)}
          </select>
          <select aria-label={t('access.col.org')} value={org} onChange={e => { setOrg(e.target.value); setPage(0); }}>
            <option value="">{t('pu.f.allorgs')}</option>
            <option value={NO_ORG}>{t('pu.f.noorg')}</option>
            {sortedOrgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{orgLabel(o)}</option>)}
          </select>
          <select aria-label={t('access.col.status')} value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}>
            <option value="">{t('pu.f.allstatus')}</option>
            {['ACTIVE', 'BLOCKED', 'LOCKED'].map(s => <option key={s} value={s}>{t('pu.st.' + s)}</option>)}
          </select>
        </div>

        <div style={{ overflowX: 'auto' }}>
          <table className="dense">
            <thead>
              <tr>
                <th>{t('access.col.login')}</th><th>{t('access.col.fullname')}</th><th>{t('access.col.org')}</th>
                <th>{t('access.col.role')}</th><th>{t('access.col.status')}</th>
                <th>{t('pu.col.lastlogin')}</th><th>{' '}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(u => (
                <tr key={u.id} style={{ opacity: u.enabled ? 1 : 0.55 }}>
                  <td>
                    <span className="number">{u.username}</span>
                    {u.self && <> <span className="badge">{t('pu.badge.self')}</span></>}
                    {u.service && <> <span className="badge">{t('pu.badge.service')}</span></>}
                  </td>
                  <td>{[u.lastName, u.firstName].filter(Boolean).join(' ') || '—'}</td>
                  <td>{orgName(u.organizationRma) ?? <span style={dim}>{t('pu.platform')}</span>}</td>
                  <td>
                    {editId === u.id ? (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 220 }}>
                        <select aria-label={t('pu.role.edit')} value={eRole} onChange={e => setERole(e.target.value)} style={{ padding: '3px 6px', fontSize: 12.5 }}>
                          {ASSIGNABLE.map(r => <option key={r} value={r}>{t('role.' + r)}</option>)}
                        </select>
                        <select aria-label={t('access.col.org')} value={eOrg} onChange={e => setEOrg(e.target.value)} style={{ padding: '3px 6px', fontSize: 12.5 }}>
                          <option value="">{t('pu.f.noorg')}</option>
                          {sortedOrgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{orgLabel(o)}</option>)}
                        </select>
                        <div style={{ display: 'flex', gap: 4 }}>
                          <button className="btn" style={smallBtn} onClick={() => saveRole(u)}>{t('pu.role.save')}</button>
                          <button className="btn secondary" style={smallBtn} onClick={() => setEditId(null)}>{t('btn.cancel')}</button>
                        </div>
                      </div>
                    ) : (u.roles.map(r => t('role.' + r)).join(', ') || '—')}
                  </td>
                  <td>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                      {u.enabled ? <span className="badge green">{t('access.status.enabled')}</span> : <span className="badge">{t('access.status.disabled')}</span>}
                      {u.locked && <span className="badge red">{t('pu.badge.locked')}</span>}
                      {u.mustChangePassword && <span className="badge amber">{t('pu.badge.temppwd')}</span>}
                      {/* Второй фактор — в той же колонке (отдельная не помещалась на ноутбуке). */}
                      {u.secondFactorEnrolled ? <span className="badge green">{t('acc.2fa')}: {t('acc.2fa.on')}</span>
                        : u.secondFactorRequired ? <span className="badge amber" title={t('acc.2fa.pending')}>{t('acc.2fa')}: {t('pu.2fa.pending')}</span>
                        : null}
                    </div>
                  </td>
                  <td>{fmt(u.lastLoginAt) ?? <span style={dim}>{t('pu.never')}</span>}</td>
                  <td style={{ textAlign: 'right' }}>
                    {/* Действия — одним списком: шесть кнопок в строке растягивали таблицу в высоту. */}
                    {actionable(u) && editId !== u.id && (
                      <select aria-label={t('pu.action')} value="" onChange={e => runAction(u, e.target.value)}
                        style={{ padding: '4px 8px', fontSize: 12.5, width: 'auto' }}>
                        <option value="">{t('pu.action')}…</option>
                        <option value="role">{t('pu.action.role')}</option>
                        <option value="toggle">{u.enabled ? t('access.btn.disable') : t('access.btn.enable')}</option>
                        <option value="password">{t('access.btn.resetpwd')}</option>
                        <option value="2fa">{u.secondFactorRequired ? t('acc.2fa.unrequire') : t('acc.2fa.require')}</option>
                        {u.secondFactorEnrolled && <option value="2fa-reset">{t('acc.2fa.reset')}</option>}
                        <option value="delete">{t('access.btn.remove')}</option>
                      </select>
                    )}
                  </td>
                </tr>
              ))}
              {data && rows.length === 0 && (
                <tr><td colSpan={7} style={{ textAlign: 'center', ...dim, padding: 22 }}>{t('pu.empty')}</td></tr>
              )}
              {!data && (
                <tr><td colSpan={7} style={{ textAlign: 'center', ...dim, padding: 22 }}>{t('common.loading')}</td></tr>
              )}
            </tbody>
          </table>
        </div>

        {pages > 1 && (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', justifyContent: 'flex-end', marginTop: 12 }}>
            <button className="btn secondary" style={smallBtn} disabled={page === 0} onClick={() => setPage(p => p - 1)}>{t('pu.prev')}</button>
            <span style={dim}>{page + 1} / {pages}</span>
            <button className="btn secondary" style={smallBtn} disabled={page + 1 >= pages} onClick={() => setPage(p => p + 1)}>{t('pu.next')}</button>
          </div>
        )}
      </div>
    </main>
  );
}
