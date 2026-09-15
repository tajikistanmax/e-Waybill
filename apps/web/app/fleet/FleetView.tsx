'use client';

import { useCallback, useEffect, useState } from 'react';
import { md } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';
import SubjectDocuments from './SubjectDocuments';

type Row = Record<string, unknown>;
export type FleetKind = 'vehicles' | 'drivers' | 'employees';
type Field = { key: string; label: string; type?: 'text' | 'number' | 'date' | 'select'; opts?: { v: string; l: string }[]; req?: boolean; keyField?: boolean };

/**
 * Раздел «Транспорт и водители» организации: полное нативное управление внутри платформы —
 * ручной ввод всех полей, редактирование, удаление + серверный поиск (для автопарков в тысячи).
 * Заголовок и переключатель разделов — в layout.tsx; у каждого раздела свой адрес.
 */
export default function FleetView({ kind }: { kind: FleetKind }) {
  const { t } = useT();
  const { roles } = useAuth();
  const canManage = roles.includes('DISPATCHER') || roles.includes('COMPANY_ADMIN') || roles.includes('BRANCH_ADMIN') || roles.includes('SYSTEM_ADMIN');
  // Открепление (удаление) от организации — повседневная задача диспетчера (ведение состава парка).
  const canDelete = canManage;
  // Механик осматривает ТС, врач — водителей; управляющие роли видят и то, и другое.
  const allowed = kind === 'vehicles' ? (canManage || roles.includes('MECHANIC'))
    : kind === 'drivers' ? (canManage || roles.includes('DOCTOR'))
      : canManage;

  const [docFor, setDocFor] = useState<{ subject: 'vehicles' | 'drivers'; key: string; title: string } | null>(null);
  const [orgRma, setOrgRma] = useState('');
  // Полный набор (в рамках организации) — только для карточек-счётчиков сверху.
  const [statRows, setStatRows] = useState<Row[]>([]);
  const [q, setQ] = useState('');
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const [form, setForm] = useState<Record<string, string> | null>(null); // null = форма закрыта
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => { md.organizations().then(l => { if (l.length) setOrgRma(String(l[0].rma)); }).catch(() => {}); }, []);
  // Смена раздела — сброс поиска, формы и сообщений.
  useEffect(() => { setQ(''); setRows([]); setForm(null); setMsg(''); setErr(''); }, [kind]);
  // Полный набор организации для счётчиков (реальные итоги, не ограниченные поиском/страницей).
  useEffect(() => {
    if (!allowed) return;
    let alive = true;
    const all = kind === 'vehicles' ? md.allVehicles() : kind === 'drivers' ? md.allDrivers() : md.allEmployees();
    all.then(l => { if (alive) setStatRows(l); }).catch(() => { if (alive) setStatRows([]); });
    return () => { alive = false; };
  }, [kind, allowed]);

  const TT = [
    { v: '1', l: t('tt.1') }, { v: '2', l: t('tt.2') }, { v: '3', l: t('tt.3') },
    { v: '4', l: t('tt.4') }, { v: '5', l: t('tt.5') }, { v: '6', l: t('tt.6') },
  ];
  const ET = [{ v: '1', l: t('fleet.emp.1') }, { v: '2', l: t('fleet.emp.2') }, { v: '3', l: t('fleet.emp.3') }];
  // Классификатор видов топлива — тот же, что у заправок (FuelRecord.fuelType) и тарифов.
  const FUEL = [
    { v: '1', l: t('fuel.type.1') }, { v: '2', l: t('fuel.type.2') }, { v: '3', l: t('fuel.type.3') },
    { v: '4', l: t('fuel.type.4') }, { v: '5', l: t('fuel.type.5') },
  ];
  const vehicleFields: Field[] = [
    { key: 'registrationNumber', label: t('fleet.f.plate'), req: true, keyField: true },
    { key: 'transportType', label: t('fleet.f.type'), type: 'select', opts: TT, req: true },
    { key: 'brand', label: t('fleet.f.brand') },
    { key: 'vincode', label: t('fleet.f.vin') },
    { key: 'fuelType', label: t('fleet.f.fueltype'), type: 'select', opts: FUEL },
    { key: 'enginePower', label: t('fleet.f.enginepower'), type: 'number' },
    { key: 'yearManufacture', label: t('fleet.f.year'), type: 'number' },
    { key: 'parkingNumber', label: t('fleet.f.parking') },
    { key: 'capacity', label: t('fleet.f.capacity'), type: 'number' },
    { key: 'carrying', label: t('fleet.f.carrying'), type: 'number' },
    { key: 'odometer', label: t('fleet.f.odometer'), type: 'number' },
    { key: 'techInspectionValidTo', label: t('fleet.f.tech'), type: 'date' },
    { key: 'controlCardValidTo', label: t('fleet.f.card'), type: 'date' },
    { key: 'controlCardNumber', label: t('dt.controlcardnum') },
    { key: 'intlCertificateNumber', label: t('dt.intlcertnum') },
    { key: 'insuranceValidTo', label: t('fleet.f.insurance'), type: 'date' },
    { key: 'adrApprovalValidTo', label: t('fleet.f.adrappr'), type: 'date' },
  ];
  const driverFields: Field[] = [
    { key: 'rma', label: t('fleet.f.inn'), req: true, keyField: true },
    { key: 'fullName', label: t('fleet.f.name'), req: true },
    { key: 'birthDate', label: t('fleet.f.birth'), type: 'date' },
    { key: 'experienceYears', label: t('fleet.f.experience'), type: 'number' },
    { key: 'licenseNumber', label: t('fleet.f.license') },
    { key: 'licenseCategories', label: t('fleet.f.cat') },
    { key: 'licenseValidTo', label: t('fleet.f.licenseto'), type: 'date' },
    { key: 'medCertNumber', label: t('fleet.f.medcert') },
    { key: 'medCertValidTo', label: t('fleet.f.medcertto'), type: 'date' },
    { key: 'safetyCourseValidTo', label: t('fleet.f.safety'), type: 'date' },
    { key: 'safetyCourseNumber', label: t('dt.safetynum') },
    { key: 'adrCertValidTo', label: t('fleet.f.adrcert'), type: 'date' },
    { key: 'medRestrictions', label: t('fleet.f.medrestr') },
    { key: 'phone', label: t('fleet.f.phone') },
  ];
  const employeeFields: Field[] = [
    { key: 'rma', label: t('fleet.f.inn'), req: true, keyField: true },
    { key: 'name', label: t('fleet.f.name'), req: true },
    { key: 'type', label: t('fleet.f.emptype'), type: 'select', opts: ET, req: true },
    { key: 'tabNumber', label: t('fleet.f.tab') },
    { key: 'phone', label: t('fleet.f.phone') },
    { key: 'address', label: t('col.address') },
  ];
  const fields = kind === 'vehicles' ? vehicleFields : kind === 'drivers' ? driverFields : employeeFields;

  const load = useCallback(async (query: string, which: FleetKind) => {
    setLoading(true); setErr('');
    try {
      if (which === 'employees') {
        // Сотрудников у организации немного (врач/механик/диспетчер) — грузим своих и фильтруем на клиенте.
        const all = await md.allEmployees();
        const s = query.trim().toLowerCase();
        setRows(s ? all.filter(e => String(e.name ?? '').toLowerCase().includes(s) || String(e.rma ?? '').includes(s)) : all);
      } else {
        setRows(which === 'vehicles' ? await md.searchVehicles('', query, 50) : await md.searchDrivers('', query, 50));
      }
    } catch (e) { setErr((e as Error).message); setRows([]); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    if (!allowed) return;
    const h = window.setTimeout(() => load(q, kind), 250);
    return () => window.clearTimeout(h);
  }, [q, kind, load, allowed]);

  function openNew() {
    setForm(Object.fromEntries(fields.map(f => [f.key, ''])));
    setEditing(false); setMsg(''); setErr('');
  }
  function openEdit(row: Row) {
    setForm(Object.fromEntries(fields.map(f => [f.key, row[f.key] != null ? String(row[f.key]) : ''])));
    setEditing(true); setMsg(''); setErr('');
  }

  async function save() {
    if (!form || !orgRma) return;
    setBusy(true); setErr(''); setMsg('');
    try {
      const body: Record<string, unknown> = { organizationRma: orgRma };
      for (const f of fields) {
        const val = (form[f.key] ?? '').trim();
        if (val === '') continue;
        body[f.key] = (f.type === 'number' || f.key === 'transportType' || f.key === 'type') ? Number(val) : val;
      }
      if (kind === 'vehicles') await md.createVehicle(body);
      else if (kind === 'drivers') await md.createDriver(body);
      else await md.createEmployee(body);
      setMsg(editing ? t('fleet.saved') : t('fleet.added'));
      setForm(null);
      load(q, kind);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  async function remove(row: Row) {
    const name = kind === 'vehicles' ? String(row.registrationNumber) : kind === 'drivers' ? String(row.fullName) : String(row.name);
    if (!window.confirm(t('fleet.delete.confirm').replace('{name}', name))) return;
    setErr(''); setMsg('');
    try {
      if (kind === 'vehicles') await md.deleteVehicle(String(row.id));
      else if (kind === 'drivers') await md.deleteDriver(String(row.id));
      else await md.deleteEmployee(String(row.id));
      setMsg(t('fleet.deleted'));
      load(q, kind);
    } catch (e) { setErr((e as Error).message); }
  }

  if (!allowed) {
    return (
      <div className="card">
        <p style={{ color: 'var(--muted)', margin: 0 }}>{t('fleet.noaccess')}</p>
      </div>
    );
  }

  const cols = kind === 'employees' ? 6 : 5;

  // Счётчики: истекает в ≤30 дней или уже истёк.
  const soon = (d: unknown) => {
    if (!d) return false;
    const ts = new Date(String(d)).getTime();
    return !isNaN(ts) && (ts - Date.now()) / 86400000 <= 30;
  };
  const statCards = kind === 'vehicles'
    ? [
        { label: t('fleet.stat.vehicles'), value: statRows.length, icon: P.car, cls: 'ic-blue' },
        { label: t('fleet.stat.techsoon'), value: statRows.filter(r => soon(r.techInspectionValidTo)).length, icon: P.wrench, cls: 'ic-amber' },
        { label: t('fleet.stat.inssoon'), value: statRows.filter(r => soon(r.insuranceValidTo)).length, icon: P.shield, cls: 'ic-red' },
      ]
    : kind === 'drivers'
      ? [
          { label: t('fleet.stat.drivers'), value: statRows.length, icon: P.user, cls: 'ic-blue' },
          { label: t('fleet.stat.licsoon'), value: statRows.filter(r => soon(r.licenseValidTo)).length, icon: P.doc, cls: 'ic-amber' },
          { label: t('fleet.stat.medsoon'), value: statRows.filter(r => soon(r.medCertValidTo)).length, icon: P.med, cls: 'ic-red' },
        ]
      : [];

  return (
    <>
      {statCards.length > 0 && (
        <>
          <div className="kpi-row" style={{ gridTemplateColumns: `repeat(${statCards.length}, 1fr)`, marginBottom: 6 }}>
            {statCards.map(s => (
              <div className="kpi" key={s.label} style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
                <span className={`k-ic ${s.cls}`}><Icon d={s.icon} cls="" /></span>
                <div style={{ minWidth: 0 }}>
                  <div className="k-value" style={{ fontSize: 22 }}>{s.value.toLocaleString('ru-RU')}</div>
                  <div className="k-label">{s.label}</div>
                </div>
              </div>
            ))}
          </div>
          <div style={{ margin: '0 0 16px', fontSize: 11.5, color: 'var(--muted)' }}>{t('fleet.stat.soonhint')}</div>
        </>
      )}

      {canManage && (
        <div style={{ display: 'flex', marginBottom: 16 }}>
          <button className="btn" style={{ marginLeft: 'auto' }} onClick={() => (form ? setForm(null) : openNew())}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} />{' '}
            {kind === 'vehicles' ? t('fleet.add.vehicle') : kind === 'drivers' ? t('fleet.add.driver') : t('fleet.add.employee')}
          </button>
        </div>
      )}

      {/* Полная форма ручного ввода/редактирования */}
      {canManage && form && (
        <div className="card" style={{ padding: 18, marginBottom: 16 }}>
          <h2 style={{ marginTop: 0 }}>{editing ? t('fleet.edit') : (kind === 'vehicles' ? t('fleet.new.vehicle') : kind === 'drivers' ? t('fleet.new.driver') : t('fleet.new.employee'))}</h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 12 }}>
            {fields.map(f => (
              <div key={f.key}>
                <label style={{ fontSize: 12.5, color: 'var(--muted)' }}>{f.label}{f.req ? ' *' : ''}</label>
                {f.type === 'select' ? (
                  <select value={form[f.key] ?? ''} onChange={e => setForm(s => ({ ...s!, [f.key]: e.target.value }))} style={{ width: '100%', marginTop: 4 }}>
                    <option value="">—</option>
                    {f.opts!.map(o => <option key={o.v} value={o.v}>{o.l}</option>)}
                  </select>
                ) : (
                  <input type={f.type === 'number' ? 'number' : f.type === 'date' ? 'date' : 'text'}
                    value={form[f.key] ?? ''} disabled={editing && f.keyField}
                    onChange={e => setForm(s => ({ ...s!, [f.key]: e.target.value }))} style={{ width: '100%', marginTop: 4 }} />
                )}
              </div>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 16 }}>
            <button className="btn primary" disabled={busy || fields.some(f => f.req && !(form[f.key] ?? '').trim())} onClick={save}>
              {busy ? '…' : t('fleet.save')}
            </button>
            <button className="btn secondary" onClick={() => setForm(null)}>{t('fleet.cancel')}</button>
          </div>
        </div>
      )}
      {msg && <div className="hint" style={{ marginBottom: 12, color: 'var(--green-700, #15803d)' }}>{msg}</div>}
      {err && <div className="error" style={{ marginBottom: 12 }}>{err}</div>}

      {/* Поиск + таблица */}
      <div className="card" style={{ padding: 16 }}>
        <input value={q} onChange={e => setQ(e.target.value)}
          placeholder={kind === 'vehicles' ? t('fleet.search.vehicle') : kind === 'drivers' ? t('fleet.search.driver') : t('fleet.search.employee')} style={{ marginBottom: 12 }} />
        <table>
          <thead>
            {kind === 'vehicles' ? (
              <tr><th>{t('fleet.col.plate')}</th><th>{t('fleet.col.brand')}</th><th>{t('fleet.col.type')}</th><th>{t('fleet.col.tech')}</th><th style={{ textAlign: 'right' }}>{t('fleet.col.actions')}</th></tr>
            ) : kind === 'drivers' ? (
              <tr><th>{t('fleet.col.name')}</th><th>{t('fleet.col.inn')}</th><th>{t('fleet.col.cat')}</th><th>{t('fleet.col.license')}</th><th style={{ textAlign: 'right' }}>{t('fleet.col.actions')}</th></tr>
            ) : (
              <tr><th>{t('fleet.col.name')}</th><th>{t('fleet.col.inn')}</th><th>{t('fleet.f.emptype')}</th><th>{t('fleet.f.tab')}</th><th>{t('fleet.f.phone')}</th><th style={{ textAlign: 'right' }}>{t('fleet.col.actions')}</th></tr>
            )}
          </thead>
          <tbody>
            {loading && <tr><td colSpan={cols} style={{ color: 'var(--muted)' }}>{t('fleet.loading')}</td></tr>}
            {!loading && rows.length === 0 && <tr><td colSpan={cols} style={{ color: 'var(--muted)' }}>{t('fleet.empty')}</td></tr>}
            {!loading && kind === 'vehicles' && rows.map((v, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600, fontFamily: 'var(--mono)' }}>{String(v.registrationNumber ?? '')}</td>
                <td>{String(v.brand ?? '—')}</td>
                <td>{TT.find(x => x.v === String(v.transportType))?.l ?? String(v.transportType ?? '—')}</td>
                <td>{v.techInspectionValidTo ? String(v.techInspectionValidTo) : '—'}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{canManage && rowActions(v)}</td>
              </tr>
            ))}
            {!loading && kind === 'drivers' && rows.map((d, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600 }}>{String(d.fullName ?? '')}</td>
                <td style={{ fontFamily: 'var(--mono)' }}>{String(d.rma ?? '')}</td>
                <td>{String(d.licenseCategories ?? '—')}</td>
                <td>{d.licenseValidTo ? String(d.licenseValidTo) : '—'}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{canManage && rowActions(d)}</td>
              </tr>
            ))}
            {!loading && kind === 'employees' && rows.map((e, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600 }}>{String(e.name ?? '')}</td>
                <td style={{ fontFamily: 'var(--mono)' }}>{String(e.rma ?? '')}</td>
                <td>{ET.find(x => x.v === String(e.type))?.l ?? String(e.type ?? '—')}</td>
                <td>{String(e.tabNumber ?? '—')}</td>
                <td>{String(e.phone ?? '—')}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{canManage && rowActions(e)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="hint" style={{ marginTop: 10 }}>{t('fleet.note.native')}</div>
      </div>

      {docFor && (
        <div onClick={() => setDocFor(null)} style={{ position: 'fixed', inset: 0, background: 'rgba(15,32,60,.45)', zIndex: 60, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '5vh 16px', overflowY: 'auto' }}>
          <div onClick={ev => ev.stopPropagation()} className="card" style={{ maxWidth: 780, width: '100%', margin: 0 }}>
            <div className="card-h">
              <h2 style={{ margin: 0 }}>{docFor.subject === 'vehicles' ? t('col.transport') : t('rj.driver')}</h2>
              <button className="btn secondary" style={{ marginLeft: 'auto' }} onClick={() => setDocFor(null)}>✕</button>
            </div>
            <SubjectDocuments subject={docFor.subject} subjectKey={docFor.key} title={docFor.title} />
          </div>
        </div>
      )}
    </>
  );

  function rowActions(row: Row) {
    const subj: 'vehicles' | 'drivers' | null = kind === 'vehicles' ? 'vehicles' : kind === 'drivers' ? 'drivers' : null;
    return (
      <span style={{ display: 'inline-flex', gap: 6, justifyContent: 'flex-end' }}>
        {subj && (
          <button className="btn secondary" style={{ padding: '4px 9px' }} title={t('sd.h')}
            onClick={() => setDocFor({
              subject: subj,
              key: String(subj === 'vehicles' ? row.registrationNumber : row.rma),
              title: String(subj === 'vehicles' ? `${row.brand ?? ''} ${row.registrationNumber}` : row.fullName),
            })}>
            <Icon d={P.book ?? P.doc} cls="" style={{ width: 14, height: 14 }} />
          </button>
        )}
        <button className="btn secondary" style={{ padding: '4px 9px' }} onClick={() => openEdit(row)} title={t('fleet.edit')}>
          <Icon d={P.doc} cls="" style={{ width: 14, height: 14 }} />
        </button>
        {canDelete && (
          <button className="btn secondary" style={{ padding: '4px 9px', color: 'var(--red)' }} onClick={() => remove(row)} title={t('fleet.delete')}>
            <Icon d={P.trash ?? P.alert} cls="" style={{ width: 14, height: 14 }} />
          </button>
        )}
      </span>
    );
  }
}
