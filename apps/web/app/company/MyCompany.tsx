'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { md, type OrgUser } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

type Org = Record<string, unknown>;
type Counts = { vehicles: number; drivers: number; employees: number };

const s = (v: unknown) => (v == null || v === '' ? '—' : String(v));
const dim = { color: 'var(--muted)' } as const;

/**
 * «Моя компания» — раздел «Компания» для администратора компании и филиала.
 *
 * <p>Матрица ролей (role_access, миграции V20/V41) даёт этим ролям пункт «Компания» как профиль
 * своей организации, а страница рисовала реестр всех организаций только администратору платформы
 * и остальным показывала пустой экран. Здесь — своя компания и её филиалы: реквизиты, парк и
 * персонал в цифрах, число выданных доступов; администратор компании правит расчётные параметры
 * компании (доля дохода, надбавки за класс) — ровно то, что ему разрешает бэкенд. Реквизиты
 * приходят из единой платформы и здесь не редактируются.</p>
 */
export default function MyCompany() {
  const { t } = useT();
  const { roles } = useAuth();
  const isCompanyAdmin = roles.includes('COMPANY_ADMIN');
  const [orgs, setOrgs] = useState<Org[]>([]);
  const [counts, setCounts] = useState<Record<string, Counts>>({});
  const [users, setUsers] = useState<OrgUser[]>([]);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);
  const [pay, setPay] = useState({ percentIncome: '', cat1: '', cat2: '', cat3: '' });

  const load = useCallback(async () => {
    setErr('');
    try {
      // Полный список без сужения переключателем филиала: страница показывает всю структуру.
      const list = await md.myOrganizations();
      setOrgs(list);
      const c = await md.organizationCounts().catch(() => []);
      setCounts(Object.fromEntries(c.map(x => [x.rma, { vehicles: x.vehicles, drivers: x.drivers, employees: x.employees }])));
      setUsers(await md.orgUsers.list().catch(() => []));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const head = useMemo(() => orgs.find(o => !o.parentRma || !orgs.some(x => x.rma === o.parentRma)) ?? orgs[0], [orgs]);
  const branches = useMemo(() => orgs.filter(o => o !== head), [orgs, head]);
  const usersBy = useMemo(() => {
    const m: Record<string, number> = {};
    users.forEach(u => { if (u.organizationRma) m[u.organizationRma] = (m[u.organizationRma] ?? 0) + 1; });
    return m;
  }, [users]);

  useEffect(() => {
    if (!head) return;
    setPay({
      percentIncome: head.percentIncome == null ? '' : String(head.percentIncome),
      cat1: head.cat1 == null ? '' : String(head.cat1),
      cat2: head.cat2 == null ? '' : String(head.cat2),
      cat3: head.cat3 == null ? '' : String(head.cat3),
    });
  }, [head]);

  async function savePayroll(e: React.FormEvent) {
    e.preventDefault();
    if (!head) return;
    setBusy(true); setErr(''); setMsg('');
    const num = (v: string) => (v.trim() === '' ? undefined : Number(v));
    try {
      await md.createOrganization({
        rma: head.rma, name: head.name,
        percentIncome: num(pay.percentIncome), cat1: num(pay.cat1), cat2: num(pay.cat2), cat3: num(pay.cat3),
      });
      setMsg(t('mycomp.saved'));
      await load();
    } catch (e2) {
      setErr(e2 instanceof Error ? e2.message : String(e2));
    } finally {
      setBusy(false);
    }
  }

  if (!head) {
    return (
      <>
        <h1>{t('mycomp.h')}</h1>
        {err ? <div className="error">{err}</div> : <p style={dim}>{t('common.loading')}</p>}
      </>
    );
  }

  const cnt = (rma: unknown) => counts[String(rma)] ?? { vehicles: 0, drivers: 0, employees: 0 };
  const hc = cnt(head.rma);

  return (
    <>
      <h1>{t('mycomp.h')}</h1>
      <p className="page-lead" style={{ marginTop: 0 }}>{t('mycomp.lead')}</p>
      {err && <div className="error">{err}</div>}
      {msg && <div className="success">{msg}</div>}

      <div className="card">
        <div className="card-h">
          <h2 style={{ margin: 0 }}>{s(head.name)}</h2>
          {head.blocked ? <span className="badge red" style={{ marginLeft: 'auto' }}>{t('mycomp.blocked')}{head.blockReason ? `: ${String(head.blockReason)}` : ''}</span>
            : <span className="badge green" style={{ marginLeft: 'auto' }}>{t('mycomp.active')}</span>}
        </div>
        <div className="grid" style={{ rowGap: 6 }}>
          <div><span style={dim}>{t('mycomp.rma')}:</span> <span className="number">{s(head.rma)}</span></div>
          <div><span style={dim}>{t('comp.f.head')}:</span> {s(head.nameHead)}</div>
          <div><span style={dim}>{t('comp.f.city')}:</span> {s(head.cityName)}</div>
          <div><span style={dim}>{t('mycomp.address')}:</span> {s(head.address)}</div>
          <div><span style={dim}>{t('mycomp.phone')}:</span> {s(head.phone)}</div>
          <div><span style={dim}>{t('comp.f.bank')}:</span> {s(head.bank)}</div>
          <div><span style={dim}>{t('mycomp.license')}:</span> {s(head.carrierLicenseNumber)}{head.licenseTo ? ` · ${t('comp.f.licto')} ${String(head.licenseTo)}` : ''}</div>
        </div>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 12 }}>
          <Link className="btn secondary" href="/fleet/vehicles">{t('mycomp.vehicles')}: {hc.vehicles}</Link>
          <Link className="btn secondary" href="/fleet/drivers">{t('mycomp.drivers')}: {hc.drivers}</Link>
          <Link className="btn secondary" href="/fleet/employees">{t('mycomp.employees')}: {hc.employees}</Link>
          <Link className="btn secondary" href="/company/access">{t('mycomp.users')}: {usersBy[String(head.rma)] ?? 0}</Link>
        </div>
        <p style={{ ...dim, fontSize: 12.5, marginBottom: 0 }}>{t('mycomp.readonly')}</p>
      </div>

      {(isCompanyAdmin || branches.length > 0) && <div className="card">
        <h2>{t('mycomp.branches')} <span style={dim}>({branches.length})</span></h2>
        <table>
          <thead>
            <tr>
              <th>{t('mycomp.branch')}</th><th>{t('mycomp.rma')}</th><th>{t('comp.f.city')}</th><th>{t('comp.f.head')}</th>
              <th style={{ textAlign: 'right' }}>{t('mycomp.vehicles')}</th><th style={{ textAlign: 'right' }}>{t('mycomp.drivers')}</th>
              <th style={{ textAlign: 'right' }}>{t('mycomp.employees')}</th><th style={{ textAlign: 'right' }}>{t('mycomp.users')}</th>
            </tr>
          </thead>
          <tbody>
            {branches.map(b => {
              const c = cnt(b.rma);
              return (
                <tr key={String(b.rma)}>
                  <td>{s(b.name)}</td><td><span className="number">{s(b.rma)}</span></td><td>{s(b.cityName)}</td><td>{s(b.nameHead)}</td>
                  <td style={{ textAlign: 'right' }}>{c.vehicles}</td><td style={{ textAlign: 'right' }}>{c.drivers}</td>
                  <td style={{ textAlign: 'right' }}>{c.employees}</td><td style={{ textAlign: 'right' }}>{usersBy[String(b.rma)] ?? 0}</td>
                </tr>
              );
            })}
            {branches.length === 0 && (
              <tr><td colSpan={8} style={{ textAlign: 'center', ...dim, padding: 18 }}>{t('mycomp.nobranches')}</td></tr>
            )}
          </tbody>
        </table>
        <p style={{ ...dim, fontSize: 12.5, marginBottom: 0 }}>{t('mycomp.branches.hint')}</p>
      </div>}

      {isCompanyAdmin && (
        <div className="card">
          <h2>{t('mycomp.payroll')}</h2>
          <p style={{ ...dim, marginTop: 0 }}>{t('mycomp.payroll.hint')}</p>
          <form className="grid" onSubmit={savePayroll}>
            <div><label htmlFor="mc-percent">{t('mycomp.percent')}</label>
              <input id="mc-percent" type="number" min={0} max={100} step="0.01" value={pay.percentIncome} onChange={e => setPay(p => ({ ...p, percentIncome: e.target.value }))} /></div>
            <div><label htmlFor="mc-cat1">{t('mycomp.cat').replace('{n}', '1')}</label>
              <input id="mc-cat1" type="number" min={0} max={100} value={pay.cat1} onChange={e => setPay(p => ({ ...p, cat1: e.target.value }))} /></div>
            <div><label htmlFor="mc-cat2">{t('mycomp.cat').replace('{n}', '2')}</label>
              <input id="mc-cat2" type="number" min={0} max={100} value={pay.cat2} onChange={e => setPay(p => ({ ...p, cat2: e.target.value }))} /></div>
            <div><label htmlFor="mc-cat3">{t('mycomp.cat').replace('{n}', '3')}</label>
              <input id="mc-cat3" type="number" min={0} max={100} value={pay.cat3} onChange={e => setPay(p => ({ ...p, cat3: e.target.value }))} /></div>
            <div className="full"><button className="btn" type="submit" disabled={busy}>{busy ? t('mycomp.saving') : t('mycomp.save')}</button></div>
          </form>
        </div>
      )}
    </>
  );
}
