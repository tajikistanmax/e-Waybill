'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { md, type OrgUser } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

type Row = Record<string, unknown>;

const BASE_ROLES = ['DISPATCHER', 'DOCTOR', 'MECHANIC', 'DRIVER', 'ACCOUNTANT', 'FUEL_STATION'];
// Роли кабинета накладных, которым нужен список контрагентов (claim client_ids).
const CLIENT_CABINET_ROLES = ['CLIENT_SENDER', 'CLIENT_FORWARDER'];
const dim = { color: 'var(--muted)' } as const;
const smallBtn = { padding: '5px 10px', fontSize: 12, whiteSpace: 'nowrap' } as const;

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
    // Кабинет накладных: логины контрагентам выдаёт перевозчик, таможеннику — только Минтранс
    // (MIGRATION.md 1.1/3.11; те же правила в normalizeRole на бэкенде).
    ...(canGrantBranchAdmin ? ['CLIENT_SENDER', 'CLIENT_FORWARDER'] : []),
    ...(canGrantCompanyAdmin ? ['CUSTOMS_OFFICER'] : []),
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
  // Контрагенты для ролей кабинета накладных (claim client_ids).
  const [clients, setClients] = useState<Row[]>([]);
  const [fClients, setFClients] = useState<string[]>([]);

  const orgName = useCallback((rma: string | null) => {
    if (!rma) return '—';
    const o = orgs.find(x => String(x.rma) === rma);
    return o ? String(o.name) : rma;
  }, [orgs]);

  // Организация, с которой работаем. Перевозчик видит одну — свою; администратор платформы
  // выбирает из списка. Без выбора список пользователей на бэкенде пуст: выгружать учётки всех
  // 604 организаций разом нельзя (см. OrgUserController.scopeFor).
  const [org, setOrg] = useState('');
  const [orgQuery, setOrgQuery] = useState('');
  // Отбор организаций по названию/РМА; выбранная остаётся в списке, даже если не подходит.
  const shownOrgs = useMemo(() => {
    const q = orgQuery.trim().toLowerCase();
    if (!q) return orgs;
    const hit = orgs.filter(o => String(o.name ?? '').toLowerCase().includes(q) || String(o.rma ?? '').includes(q));
    const cur = orgs.find(o => String(o.rma) === org);
    return cur && !hit.includes(cur) ? [cur, ...hit] : hit;
  }, [orgs, orgQuery, org]);
  // Куда выдавать доступ: выбранная организация и её филиалы (у администратора платформы —
  // не все сотни организаций в одном списке; у перевозчика список и так = компания + филиалы).
  const formOrgs = useMemo(() => {
    const own = orgs.filter(o => String(o.rma) === org || String(o.parentRma ?? '') === org);
    return own.length > 0 ? own : orgs;
  }, [orgs, org]);
  // Найдена ровно одна организация — выбираем её сразу (типичный путь: ввёл РМА → работаешь).
  useEffect(() => {
    const q = orgQuery.trim().toLowerCase();
    if (!q) return;
    const hit = orgs.filter(o => String(o.name ?? '').toLowerCase().includes(q) || String(o.rma ?? '').includes(q));
    if (hit.length === 1) { const r = String(hit[0].rma); setOrg(r); setFOrg(r); setFPerson(''); }
  }, [orgQuery, orgs]);

  // Список организаций — один раз при открытии страницы.
  useEffect(() => {
    let alive = true;
    md.orgUsers.provisioningEnabled()
      .then(on => {
        if (!alive) return;
        setAvailable(on);
        if (!on) return;
        return md.organizations().then(ol => {
          if (!alive) return;
          setOrgs(ol);
          const first = String((ol.find(o => !o.parentRma) ?? ol[0])?.rma ?? '');
          setOrg(prev => prev || first);
          setFOrg(prev => prev || first);
        });
      })
      .catch(e => { if (alive) setErr(e instanceof Error ? e.message : String(e)); });
    md.clients().then(list => setClients(list as unknown as Row[])).catch(() => setClients([]));
    return () => { alive = false; };
  }, []);

  // Пользователи и список людей — по выбранной организации. Именно по организации, а не
  // «все»: полные справочники водителей и сотрудников на боевых данных весят десятки мегабайт.
  const load = useCallback(async () => {
    if (!org) return;
    setErr('');
    try {
      const [ul, emps, drs] = await Promise.all([
        md.orgUsers.list(org),
        md.employees(org),
        md.drivers(org),
      ]);
      setUsers(ul);
      const list: { rma: string; name: string; kind: 'EMPLOYEE' | 'DRIVER'; phone: string }[] = [];
      emps.forEach(e => list.push({ rma: String(e.rma), name: String(e.name ?? ''), kind: 'EMPLOYEE', phone: String(e.phone ?? '') }));
      drs.forEach(d => list.push({ rma: String(d.rma), name: String(d.fullName ?? ''), kind: 'DRIVER', phone: String(d.phone ?? '') }));
      setPeople(list);
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }, [org]);

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
        clientIds: CLIENT_CABINET_ROLES.includes(fRole) ? fClients : undefined,
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

  // Второй фактор (находка 27): сброс — сотрудник потерял или сменил телефон; признак
  // «требовать» — при следующем входе сотрудник подключит приложение-аутентификатор.
  async function resetSecondFactor(u: OrgUser) {
    if (!confirm(`«${u.username}»: ${t('acc.2fa.reset.confirm')}`)) return;
    setErr('');
    try { await md.orgUsers.resetSecondFactor(u.id); await load(); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  }

  async function toggleSecondFactor(u: OrgUser) {
    setErr('');
    try { await md.orgUsers.setSecondFactorRequired(u.id, !u.secondFactorRequired); await load(); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  }

  // Смена роли учётной записи (одна роль модуля; API /org-users/{id}/role). Раньше в интерфейсе
  // этого действия не было — только через API.
  async function changeRole(u: OrgUser, role: string) {
    if (!role || role === roleOf(u)) return;
    if (!confirm(`${t('access.role.confirm')} «${u.username}» → ${t('role.' + role)}?`)) return;
    setErr('');
    try { await md.orgUsers.setRole(u.id, role, u.organizationRma ?? '', u.username); await load(); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  }

  async function remove(u: OrgUser) {
    if (!confirm(`${t('access.remove.confirm')} «${u.username}»${t('access.remove.confirm.suffix')}`)) return;
    setErr('');
    try { await md.orgUsers.remove(u.id); await load(); }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  }

  const KNOWN_ROLES = new Set([...BASE_ROLES, 'BRANCH_ADMIN', 'COMPANY_ADMIN', ...CLIENT_CABINET_ROLES, 'CUSTOMS_OFFICER']);
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

      {/* Выбор организации — для тех, кто видит больше одной (администратор платформы).
          Перевозчику список сужен токеном, и переключать нечего. */}
      {orgs.length > 1 && (
        <div className="card">
          <div className="card-h" style={{ flexWrap: 'wrap', gap: 8 }}>
            <h2>{t('access.org.h')}</h2>
            {/* У администратора платформы в списке сотни организаций — нужен поиск по названию/РМА. */}
            {orgs.length > 15 && (
              <input value={orgQuery} onChange={e => setOrgQuery(e.target.value)} placeholder={t('access.org.search')}
                aria-label={t('access.org.search')} style={{ marginLeft: 'auto', minWidth: 220 }} />
            )}
            <select
              aria-label={t('access.org.h')}
              value={org}
              onChange={e => { setOrg(e.target.value); setFOrg(e.target.value); setFPerson(''); }}
              style={{ marginLeft: orgs.length > 15 ? 0 : 'auto', minWidth: 320 }}
            >
              {shownOrgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{String(o.name ?? o.rma)} · {String(o.rma)}{o.parentRma ? t('access.f.branch.suffix') : ''}</option>)}
            </select>
          </div>
          <p style={{ ...dim, margin: 0 }}>{t('access.org.hint')}</p>
        </div>
      )}

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
            <label htmlFor="acc-person">{t('access.f.person')}</label>
            <select id="acc-person" value={fPerson} onChange={e => pickPerson(e.target.value)}>
              <option value="">{t('access.f.person.ph')}</option>
              {people.map(p => <option key={p.rma} value={p.rma}>{p.name} · {t(p.kind === 'DRIVER' ? 'access.kind.driver' : 'access.kind.employee')} · {p.rma}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="acc-username">{t('access.f.username')}</label>
            <input id="acc-username" required value={fUsername} onChange={e => setFUsername(e.target.value)} placeholder="+992900000000" />
          </div>
          <div>
            <label htmlFor="acc-fullname">{t('access.f.fullname')}</label>
            <input id="acc-fullname" value={fName} onChange={e => setFName(e.target.value)} placeholder="Каримов Алишер" />
          </div>
          <div>
            <label htmlFor="acc-org">{t('access.f.org')}</label>
            <select id="acc-org" value={fOrg} onChange={e => setFOrg(e.target.value)} required>
              {formOrgs.map(o => (
                <option key={String(o.rma)} value={String(o.rma)}>
                  {String(o.name)}{o.parentRma ? t('access.f.branch.suffix') : ''}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="acc-role">{t('access.f.role')}</label>
            <select id="acc-role" value={fRole} onChange={e => setFRole(e.target.value)} required>
              {grantableRoles.map(r => <option key={r} value={r}>{t('role.' + r)}</option>)}
            </select>
          </div>
          {/* Кабинет накладных: логин видит документы только выбранных контрагентов. */}
          {CLIENT_CABINET_ROLES.includes(fRole) && (
            <div className="full">
              <label>{t('access.f.clients')}</label>
              <select multiple value={fClients} size={Math.min(6, Math.max(3, clients.length))}
                onChange={e => setFClients(Array.from(e.target.selectedOptions).map(o => o.value))}
                style={{ width: '100%' }}>
                {clients.map(c => (
                  <option key={String(c.id)} value={String(c.id)}>
                    {String(c.name)}{c.number ? ` · ${String(c.number)}` : ''}
                  </option>
                ))}
              </select>
              <div className="hint" style={{ marginTop: 4 }}>{t('access.f.clients.hint')}</div>
            </div>
          )}
          <div className="full">
            <button className="btn" disabled={busy || !fUsername.trim()}>{busy ? t('access.grant.busy') : t('access.grant.btn')}</button>
          </div>
        </form>
      </div>

      <div className="card">
        <h2>{t('access.list.h')} <span style={dim}>({sortedUsers.length})</span></h2>
        {/* Таблица шире карточки на ноутбуке — прокрутка внутри карточки, а не за её край. */}
        <div style={{ overflowX: 'auto' }}>
        <table>
          <thead>
            <tr><th>{t('access.col.login')}</th><th>{t('access.col.fullname')}</th><th>{t('access.col.org')}</th><th>{t('access.col.role')}</th><th>{t('access.col.status')}</th><th>{t('acc.2fa')}</th><th>{' '}</th></tr>
          </thead>
          <tbody>
            {sortedUsers.map(u => (
              <tr key={u.id} style={{ opacity: u.enabled ? 1 : 0.5 }}>
                <td><span className="number">{u.username}</span></td>
                <td>{[u.lastName, u.firstName].filter(Boolean).join(' ') || '—'}</td>
                <td>{orgName(u.organizationRma)}</td>
                <td>
                  {/* Роль меняется прямо в строке — в пределах ролей, которые вызывающий вправе выдавать
                      (CLIENT_* требуют списка контрагентов и выдаются только при создании). */}
                  {u.manageable !== false && grantableRoles.includes(roleOf(u)) && !CLIENT_CABINET_ROLES.includes(roleOf(u)) ? (
                    <select aria-label={t('access.col.role')} value={roleOf(u)} onChange={e => changeRole(u, e.target.value)}
                      style={{ padding: '3px 6px', fontSize: 12.5, minWidth: 170 }}>
                      {grantableRoles.filter(r => !CLIENT_CABINET_ROLES.includes(r)).map(r => <option key={r} value={r}>{t('role.' + r)}</option>)}
                    </select>
                  ) : t('role.' + roleOf(u))}
                </td>
                <td>{u.enabled ? <span className="badge green">{t('access.status.enabled')}</span> : <span className="badge">{t('access.status.disabled')}</span>}</td>
                <td>
                  {u.secondFactorEnrolled ? <span className="badge green">{t('acc.2fa.on')}</span>
                    : u.secondFactorRequired ? <span className="badge amber">{t('acc.2fa.pending')}</span>
                    : <span style={dim}>{t('acc.2fa.off')}</span>}
                </td>
                <td style={{ textAlign: 'right' }}><div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, justifyContent: 'flex-end' }}>
                  {u.manageable === false ? (
                    <span style={{ ...dim, fontSize: 12 }}>{t('access.notmanageable')}</span>
                  ) : (
                    <>
                      <button className="btn secondary" style={smallBtn} onClick={() => toggle(u)}>{u.enabled ? t('access.btn.disable') : t('access.btn.enable')}</button>{' '}
                      <button className="btn secondary" style={smallBtn} onClick={() => reset(u)}>{t('access.btn.resetpwd')}</button>{' '}
                      <button className="btn secondary" style={smallBtn} onClick={() => toggleSecondFactor(u)}>{u.secondFactorRequired ? t('acc.2fa.unrequire') : t('acc.2fa.require')}</button>{' '}
                      {u.secondFactorEnrolled && (
                        <button className="btn secondary" style={smallBtn} onClick={() => resetSecondFactor(u)}>{t('acc.2fa.reset')}</button>
                      )}{' '}
                      <button className="btn danger" style={smallBtn} onClick={() => remove(u)}>{t('access.btn.remove')}</button>
                    </>
                  )}
                </div></td>
              </tr>
            ))}
            {sortedUsers.length === 0 && (
              <tr><td colSpan={7} style={{ textAlign: 'center', ...dim, padding: 22 }}>{t('access.empty')}</td></tr>
            )}
          </tbody>
        </table>
        </div>
        <div style={{ ...dim, marginTop: 12, fontSize: 12.5 }}>
          {t('access.footnote.pre')}{' '}
          <Link href="/company">{t('nav.company')}</Link>. {t('access.footnote.post')}
        </div>
      </div>
    </main>
  );
}
