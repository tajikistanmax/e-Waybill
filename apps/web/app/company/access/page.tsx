'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { md, type OrgUser } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

type Row = Record<string, unknown>;

const BASE_ROLES = ['DISPATCHER', 'DOCTOR', 'MECHANIC', 'DRIVER', 'ACCOUNTANT', 'FUEL_STATION'];
const dim = { color: 'var(--muted)' } as const;
const smallBtn = { padding: '5px 10px', fontSize: 12 } as const;

export default function AccessPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const canGrantBranchAdmin = roles.includes('SYSTEM_ADMIN') || roles.includes('COMPANY_ADMIN');
  // COMPANY_ADMIN — только SYSTEM_ADMIN (первый вход новой организации; см. normalizeRole
  // в OrgUserController — тот же принцип на бэкенде, здесь просто не показываем недоступный
  // пункт в списке, а не единственная граница).
  const canGrantCompanyAdmin = roles.includes('SYSTEM_ADMIN');
  const grantableRoles = [
    ...BASE_ROLES,
    ...(canGrantBranchAdmin ? ['BRANCH_ADMIN'] : []),
    ...(canGrantCompanyAdmin ? ['COMPANY_ADMIN'] : []),
  ];

  const [available, setAvailable] = useState<boolean | null>(null);
  const [orgs, setOrgs] = useState<Row[]>([]);
  const [users, setUsers] = useState<OrgUser[]>([]);
  const [people, setPeople] = useState<{ rma: string; name: string; kind: 'EMPLOYEE' | 'DRIVER'; phone: string }[]>([]);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [freshPassword, setFreshPassword] = useState<{ username: string; password: string } | null>(null);

  const [fPerson, setFPerson] = useState('');
  const [fUsername, setFUsername] = useState('');
  const [fName, setFName] = useState('');
  const [fOrg, setFOrg] = useState('');
  const [fRole, setFRole] = useState('DISPATCHER');

  const orgName = useCallback((rma: string | null) => {
    if (!rma) return '—';
    const o = orgs.find(x => String(x.rma) === rma);
    return o ? String(o.name) : rma;
  }, [orgs]);

  const load = useCallback(async () => {
    setErr('');
    try {
      const on = await md.orgUsers.provisioningEnabled();
      setAvailable(on);
      if (!on) return;
      const [ol, ul] = await Promise.all([md.organizations(), md.orgUsers.list()]);
      setOrgs(ol);
      setUsers(ul);
      setFOrg(prev => prev || String((ol.find(o => !o.parentRma) ?? ol[0])?.rma ?? ''));
      const [emps, drs] = await Promise.all([md.allEmployees(), md.allDrivers()]);
      const list: { rma: string; name: string; kind: 'EMPLOYEE' | 'DRIVER'; phone: string }[] = [];
      emps.forEach(e => list.push({ rma: String(e.rma), name: String(e.name ?? ''), kind: 'EMPLOYEE', phone: String(e.phone ?? '') }));
      drs.forEach(d => list.push({ rma: String(d.rma), name: String(d.fullName ?? ''), kind: 'DRIVER', phone: String(d.phone ?? '') }));
      setPeople(list);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  function pickPerson(rma: string) {
    setFPerson(rma);
    const p = people.find(x => x.rma === rma);
    if (p) {
      setFName(p.name);
      if (p.phone) setFUsername(p.phone.replace(/\s+/g, ''));
    }
  }

  async function grant(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setErr(''); setFreshPassword(null);
    try {
      const parts = fName.trim().split(/\s+/).filter(Boolean);
      const created = await md.orgUsers.create({
        username: fUsername.trim(),
        lastName: parts[0] ?? '',
        firstName: parts.slice(1).join(' '),
        personRma: fPerson || undefined,
        organizationRma: fOrg,
        role: fRole,
      });
      if (created.temporaryPassword) setFreshPassword({ username: created.username, password: created.temporaryPassword });
      setFPerson(''); setFUsername(''); setFName('');
      await load();
    } catch (e2) {
      setErr(e2 instanceof Error ? e2.message : String(e2));
    } finally {
      setBusy(false);
    }
  }

  async function toggle(u: OrgUser) {
    setErr('');
    try { await md.orgUsers.setEnabled(u.id, !u.enabled); await load(); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  }

  async function reset(u: OrgUser) {
    setErr('');
    try {
      const r = await md.orgUsers.resetPassword(u.id);
      if (r.temporaryPassword) setFreshPassword({ username: u.username, password: r.temporaryPassword });
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  }

  async function remove(u: OrgUser) {
    if (!confirm(`${t('access.remove.confirm')} «${u.username}»${t('access.remove.confirm.suffix')}`)) return;
    setErr('');
    try { await md.orgUsers.remove(u.id); await load(); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  }

  const KNOWN_ROLES = new Set([...BASE_ROLES, 'BRANCH_ADMIN', 'COMPANY_ADMIN']);
  const roleOf = (u: OrgUser) => u.roles.find(r => KNOWN_ROLES.has(r)) ?? (u.roles[0] ?? '—');
  const sortedUsers = useMemo(() => [...users].sort((a, b) => a.username.localeCompare(b.username)), [users]);

  if (available === null) return <main className="page"><p style={dim}>{t('common.loading')}</p></main>;

  if (!available) {
    return (
      <main className="page">
        <h1>{t('access.h')}</h1>
        <p style={dim}>
          {t('access.disabled')}
        </p>
      </main>
    );
  }

  return (
    <main className="page">
      <h1>{t('access.h.full')}</h1>
      <p style={{ ...dim, maxWidth: 720 }}>
        {t('access.intro')}
      </p>

      {err && <div className="error">{err}</div>}

      {freshPassword && (
        <div className="card" style={{ borderColor: 'var(--green)', background: 'var(--green-050)' }}>
          <b>{t('access.temppass')} «{freshPassword.username}»:</b>{' '}
          <span className="number" style={{ fontSize: '1.15em', letterSpacing: '.06em' }}>{freshPassword.password}</span>
          <div style={{ ...dim, marginTop: 4 }}>
            {t('access.temppass.hint')}
          </div>
          <button className="btn secondary" style={{ ...smallBtn, marginTop: 8 }} onClick={() => setFreshPassword(null)}>{t('access.hide')}</button>
        </div>
      )}

      <div className="card">
        <h2>{t('access.grant.h')}</h2>
        <form className="grid" onSubmit={grant}>
          <div>
            <label>{t('access.f.person')}</label>
            <select value={fPerson} onChange={e => pickPerson(e.target.value)}>
              <option value="">{t('access.f.person.ph')}</option>
              {people.map(p => <option key={p.rma} value={p.rma}>{p.name} · {t(p.kind === 'DRIVER' ? 'access.kind.driver' : 'access.kind.employee')} · {p.rma}</option>)}
            </select>
          </div>
          <div>
            <label>{t('access.f.username')}</label>
            <input required value={fUsername} onChange={e => setFUsername(e.target.value)} placeholder="+992900000000" />
          </div>
          <div>
            <label>{t('access.f.fullname')}</label>
            <input value={fName} onChange={e => setFName(e.target.value)} placeholder="Каримов Алишер" />
          </div>
          <div>
            <label>{t('access.f.org')}</label>
            <select value={fOrg} onChange={e => setFOrg(e.target.value)} required>
              {orgs.map(o => (
                <option key={String(o.rma)} value={String(o.rma)}>
                  {String(o.name)}{o.parentRma ? t('access.f.branch.suffix') : ''}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label>{t('access.f.role')}</label>
            <select value={fRole} onChange={e => setFRole(e.target.value)} required>
              {grantableRoles.map(r => <option key={r} value={r}>{t('role.' + r)}</option>)}
            </select>
          </div>
          <div className="full">
            <button className="btn" disabled={busy || !fUsername.trim()}>{busy ? t('access.grant.busy') : t('access.grant.btn')}</button>
          </div>
        </form>
      </div>

      <div className="card">
        <h2>{t('access.list.h')} <span style={dim}>({sortedUsers.length})</span></h2>
        <table>
          <thead>
            <tr><th>{t('access.col.login')}</th><th>{t('access.col.fullname')}</th><th>{t('access.col.org')}</th><th>{t('access.col.role')}</th><th>{t('access.col.status')}</th><th>{' '}</th></tr>
          </thead>
          <tbody>
            {sortedUsers.map(u => (
              <tr key={u.id} style={{ opacity: u.enabled ? 1 : 0.5 }}>
                <td><span className="number">{u.username}</span></td>
                <td>{[u.lastName, u.firstName].filter(Boolean).join(' ') || '—'}</td>
                <td>{orgName(u.organizationRma)}</td>
                <td>{t('role.' + roleOf(u))}</td>
                <td>{u.enabled ? <span className="badge green">{t('access.status.enabled')}</span> : <span className="badge">{t('access.status.disabled')}</span>}</td>
                <td style={{ whiteSpace: 'nowrap', textAlign: 'right' }}>
                  <button className="btn secondary" style={smallBtn} onClick={() => toggle(u)}>{u.enabled ? t('access.btn.disable') : t('access.btn.enable')}</button>{' '}
                  <button className="btn secondary" style={smallBtn} onClick={() => reset(u)}>{t('access.btn.resetpwd')}</button>{' '}
                  <button className="btn danger" style={smallBtn} onClick={() => remove(u)}>{t('access.btn.remove')}</button>
                </td>
              </tr>
            ))}
            {sortedUsers.length === 0 && (
              <tr><td colSpan={6} style={{ textAlign: 'center', ...dim, padding: 22 }}>{t('access.empty')}</td></tr>
            )}
          </tbody>
        </table>
        <div style={{ ...dim, marginTop: 12, fontSize: 12.5 }}>
          {t('access.footnote.pre')}{' '}
          <Link href="/company">{t('nav.company')}</Link>. {t('access.footnote.post')}
        </div>
      </div>
    </main>
  );
}
