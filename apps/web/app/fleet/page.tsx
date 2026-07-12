'use client';

import { useCallback, useEffect, useState } from 'react';
import { md } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type Row = Record<string, unknown>;
type Field = { key: string; label: string; type?: 'text' | 'number' | 'date' | 'select'; opts?: { v: string; l: string }[]; req?: boolean; keyField?: boolean };

/** Транспорт и водители организации: полное нативное управление внутри платформы —
 *  ручной ввод всех полей, редактирование, удаление + серверный поиск (для автопарков в тысячи). */
export default function FleetPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const canManage = roles.includes('DISPATCHER') || roles.includes('COMPANY_ADMIN') || roles.includes('SYSTEM_ADMIN');
  const canDelete = roles.includes('COMPANY_ADMIN') || roles.includes('SYSTEM_ADMIN');
  // Механик осматривает ТС, врач — водителей; управляющие роли видят и то, и другое.
  const showVehicles = canManage || roles.includes('MECHANIC');
  const showDrivers = canManage || roles.includes('DOCTOR');

  const [tab, setTab] = useState<'vehicles' | 'drivers'>('vehicles');
  useEffect(() => {
    if (tab === 'vehicles' && !showVehicles && showDrivers) setTab('drivers');
    else if (tab === 'drivers' && !showDrivers && showVehicles) setTab('vehicles');
  }, [showVehicles, showDrivers, tab]);

  const [orgRma, setOrgRma] = useState('');
  const [q, setQ] = useState('');
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const [form, setForm] = useState<Record<string, string> | null>(null); // null = форма закрыта
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => { md.organizations().then(l => { if (l.length) setOrgRma(String(l[0].rma)); }).catch(() => {}); }, []);

  const TT = [
    { v: '1', l: t('tt.1') }, { v: '2', l: t('tt.2') }, { v: '3', l: t('tt.3') },
    { v: '4', l: t('tt.4') }, { v: '5', l: t('tt.5') }, { v: '6', l: t('tt.6') },
  ];
  const vehicleFields: Field[] = [
    { key: 'registrationNumber', label: t('fleet.f.plate'), req: true, keyField: true },
    { key: 'transportType', label: t('fleet.f.type'), type: 'select', opts: TT, req: true },
    { key: 'brand', label: t('fleet.f.brand') },
    { key: 'vincode', label: t('fleet.f.vin') },
    { key: 'yearManufacture', label: t('fleet.f.year'), type: 'number' },
    { key: 'parkingNumber', label: t('fleet.f.parking') },
    { key: 'capacity', label: t('fleet.f.capacity'), type: 'number' },
    { key: 'carrying', label: t('fleet.f.carrying'), type: 'number' },
    { key: 'odometer', label: t('fleet.f.odometer'), type: 'number' },
    { key: 'techInspectionValidTo', label: t('fleet.f.tech'), type: 'date' },
    { key: 'controlCardValidTo', label: t('fleet.f.card'), type: 'date' },
  ];
  const driverFields: Field[] = [
    { key: 'rma', label: t('fleet.f.inn'), req: true, keyField: true },
    { key: 'fullName', label: t('fleet.f.name'), req: true },
    { key: 'licenseNumber', label: t('fleet.f.license') },
    { key: 'licenseCategories', label: t('fleet.f.cat') },
    { key: 'licenseValidTo', label: t('fleet.f.licenseto'), type: 'date' },
    { key: 'medCertNumber', label: t('fleet.f.medcert') },
    { key: 'medCertValidTo', label: t('fleet.f.medcertto'), type: 'date' },
    { key: 'phone', label: t('fleet.f.phone') },
  ];
  const fields = tab === 'vehicles' ? vehicleFields : driverFields;

  const load = useCallback(async (query: string, which: 'vehicles' | 'drivers') => {
    setLoading(true); setErr('');
    try {
      const list = which === 'vehicles' ? await md.searchVehicles('', query, 50) : await md.searchDrivers('', query, 50);
      setRows(list);
    } catch (e) { setErr((e as Error).message); setRows([]); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    const h = window.setTimeout(() => load(q, tab), 250);
    return () => window.clearTimeout(h);
  }, [q, tab, load]);

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
        body[f.key] = (f.type === 'number' || f.key === 'transportType') ? Number(val) : val;
      }
      if (tab === 'vehicles') await md.createVehicle(body);
      else await md.createDriver(body);
      setMsg(editing ? t('fleet.saved') : t('fleet.added'));
      setForm(null);
      load(q, tab);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  async function remove(row: Row) {
    const name = tab === 'vehicles' ? String(row.registrationNumber) : String(row.fullName);
    if (!window.confirm(t('fleet.delete.confirm').replace('{name}', name))) return;
    setErr(''); setMsg('');
    try {
      if (tab === 'vehicles') await md.deleteVehicle(String(row.id));
      else await md.deleteDriver(String(row.id));
      setMsg(t('fleet.deleted'));
      load(q, tab);
    } catch (e) { setErr((e as Error).message); }
  }

  function switchTab(which: 'vehicles' | 'drivers') {
    setTab(which); setQ(''); setRows([]); setForm(null); setMsg(''); setErr('');
  }

  const cols = tab === 'vehicles' ? 5 : 5;

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{showVehicles && showDrivers ? t('nav.fleet') : showDrivers ? t('fleet.tab.drivers') : t('fleet.tab.vehicles')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>
            {showVehicles && showDrivers ? t('fleet.lead') : showDrivers ? t('fleet.lead.drivers') : t('fleet.lead.vehicles')}
          </div>
        </div>
        {canManage && (
          <button className="btn" style={{ marginLeft: 'auto' }} onClick={() => (form ? setForm(null) : openNew())}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} />{' '}
            {tab === 'vehicles' ? t('fleet.add.vehicle') : t('fleet.add.driver')}
          </button>
        )}
      </div>

      {showVehicles && showDrivers && (
        <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
          <button className={`btn ${tab === 'vehicles' ? 'primary' : 'secondary'}`} onClick={() => switchTab('vehicles')}>
            <Icon d={P.car} cls="" style={{ width: 15, height: 15 }} /> {t('fleet.tab.vehicles')}
          </button>
          <button className={`btn ${tab === 'drivers' ? 'primary' : 'secondary'}`} onClick={() => switchTab('drivers')}>
            <Icon d={P.user} cls="" style={{ width: 15, height: 15 }} /> {t('fleet.tab.drivers')}
          </button>
        </div>
      )}

      {/* Полная форма ручного ввода/редактирования */}
      {canManage && form && (
        <div className="card" style={{ padding: 18, marginBottom: 16 }}>
          <h2 style={{ marginTop: 0 }}>{editing ? t('fleet.edit') : (tab === 'vehicles' ? t('fleet.new.vehicle') : t('fleet.new.driver'))}</h2>
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
          placeholder={tab === 'vehicles' ? t('fleet.search.vehicle') : t('fleet.search.driver')} style={{ marginBottom: 12 }} />
        <table>
          <thead>
            {tab === 'vehicles' ? (
              <tr><th>{t('fleet.col.plate')}</th><th>{t('fleet.col.brand')}</th><th>{t('fleet.col.type')}</th><th>{t('fleet.col.tech')}</th><th style={{ textAlign: 'right' }}>{t('fleet.col.actions')}</th></tr>
            ) : (
              <tr><th>{t('fleet.col.name')}</th><th>{t('fleet.col.inn')}</th><th>{t('fleet.col.cat')}</th><th>{t('fleet.col.license')}</th><th style={{ textAlign: 'right' }}>{t('fleet.col.actions')}</th></tr>
            )}
          </thead>
          <tbody>
            {loading && <tr><td colSpan={cols} style={{ color: 'var(--muted)' }}>{t('fleet.loading')}</td></tr>}
            {!loading && rows.length === 0 && <tr><td colSpan={cols} style={{ color: 'var(--muted)' }}>{t('fleet.empty')}</td></tr>}
            {!loading && tab === 'vehicles' && rows.map((v, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600, fontFamily: 'var(--mono)' }}>{String(v.registrationNumber ?? '')}</td>
                <td>{String(v.brand ?? '—')}</td>
                <td>{TT.find(x => x.v === String(v.transportType))?.l ?? String(v.transportType ?? '—')}</td>
                <td>{v.techInspectionValidTo ? String(v.techInspectionValidTo) : '—'}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{canManage && rowActions(v)}</td>
              </tr>
            ))}
            {!loading && tab === 'drivers' && rows.map((d, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600 }}>{String(d.fullName ?? '')}</td>
                <td style={{ fontFamily: 'var(--mono)' }}>{String(d.rma ?? '')}</td>
                <td>{String(d.licenseCategories ?? '—')}</td>
                <td>{d.licenseValidTo ? String(d.licenseValidTo) : '—'}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{canManage && rowActions(d)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="hint" style={{ marginTop: 10 }}>{t('fleet.note.native')}</div>
      </div>
    </>
  );

  function rowActions(row: Row) {
    return (
      <span style={{ display: 'inline-flex', gap: 6, justifyContent: 'flex-end' }}>
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
