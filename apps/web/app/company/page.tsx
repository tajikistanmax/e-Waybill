'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { authHeaders, md } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type Row = Record<string, unknown>;
type Tab = 'drivers' | 'vehicles' | 'employees';
type Counts = { vehicles: number; drivers: number; employees: number };

const EMPLOYEE_TYPES: Record<number, string> = { 1: 'Врач (духтур)', 2: 'Механик', 3: 'Диспетчер (танзимгар)' };
const TRANSPORT_TYPES: Record<number, string> = {
  1: 'Автобус', 2: 'Троллейбус', 3: 'Микроавтобус', 4: 'Легковой (сабукрав)', 5: 'Грузовой (2-Б)', 6: 'Грузовой межд. (5Б-БМ)',
};
const SUBJECT_TYPES: Record<string, string> = { PHYSICAL: 'Физлицо', IP: 'ИП', LEGAL: 'Юрлицо' };

function todayISO() { return new Date().toISOString().slice(0, 10); }
/** Организация считается активной, если её лицензия перевозчика не истекла (или срок не задан). */
function orgActive(o: Row): boolean {
  const to = o.licenseTo ? String(o.licenseTo) : '';
  return !to || to >= todayISO();
}

async function postJson(url: string, body: unknown) {
  const res = await fetch(url, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const p = await res.json().catch(() => null);
    const fields = p?.errors?.map((e: { field: string; message: string }) => `${e.field} — ${e.message}`).join('; ');
    throw new Error((p?.detail ?? p?.title ?? `Ошибка ${res.status}`) + (fields ? `: ${fields}` : ''));
  }
  return res.json();
}

/** Готовит payload ручной формы: пустые поля → null, числовые ключи → число. */
function clean(obj: Record<string, string>, numeric: string[]): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) {
    out[k] = v === '' ? null : (numeric.includes(k) ? Number(v) : v);
  }
  return out;
}

// Наборы полей ручных форм — для предзаполнения при редактировании существующей записи.
const ORG_KEYS = ['rma', 'name', 'kpp', 'typeCompany', 'regionId', 'cityName', 'address', 'phone', 'email', 'nameHead', 'bank', 'licenseFrom', 'licenseTo'];
const DRIVER_KEYS = ['rma', 'fullName', 'tabNumber', 'licenseNumber', 'licenseCategories', 'licenseValidTo', 'degree', 'medCertNumber', 'medCertValidTo', 'safetyCourseValidTo', 'phone'];
const VEHICLE_KEYS = ['registrationNumber', 'transportType', 'brand', 'parkingNumber', 'capacity', 'carrying', 'odometer', 'vincode', 'yearManufacture', 'techInspectionValidTo', 'controlCardValidTo'];
const EMPLOYEE_KEYS = ['rma', 'name', 'type', 'tabNumber', 'phone'];

/** Строка справочника → значения ручной формы (для кнопки «Изменить»). */
function rowToForm(row: Record<string, unknown>, keys: string[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const k of keys) {
    const v = row[k];
    out[k] = v == null ? '' : String(v);
  }
  return out;
}

/**
 * Кабинет компании-перевозчика. Субъекты (водители, сотрудники) и объекты (ТС)
 * НЕ регистрируются здесь вручную — они добавляются по ИНН/госномеру, а данные
 * приходят из единой платформы Минтранса (налоговая, ГАИ, Минздрав).
 */
export default function CompanyPage() {
  const { t } = useT();
  const [orgs, setOrgs] = useState<Row[]>([]);
  const [counts, setCounts] = useState<Record<string, Counts>>({});
  const [orgRma, setOrgRma] = useState('');
  const [orgSearch, setOrgSearch] = useState('');
  const [tab, setTab] = useState<Tab>('drivers');
  const [rows, setRows] = useState<Row[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  // Добавление — только идентификаторы; данные приходят из единой платформы
  const [orgInn, setOrgInn] = useState('');
  const [driverForm, setDriverForm] = useState({ inn: '', tabNumber: '' });
  const [vehicleForm, setVehicleForm] = useState({ registrationNumber: '', parkingNumber: '' });
  const [employeeForm, setEmployeeForm] = useState({ inn: '', type: '1', tabNumber: '' });

  // Ручной ввод (полная форма) — все поля справочника. Режим переключается для организации и для сущностей.
  const [orgMode, setOrgMode] = useState<'sync' | 'manual'>('sync');
  const [entityMode, setEntityMode] = useState<'sync' | 'manual'>('sync');
  const [orgManual, setOrgManual] = useState<Record<string, string>>({});
  const [driverManual, setDriverManual] = useState<Record<string, string>>({});
  const [vehicleManual, setVehicleManual] = useState<Record<string, string>>({ transportType: '1' });
  const [employeeManual, setEmployeeManual] = useState<Record<string, string>>({ type: '1' });

  const loadOrgs = useCallback(async () => {
    const list = await md.organizations();
    setOrgs(list);
    if (list.length > 0) setOrgRma(prev => prev || String(list[0].rma));
    // Счётчики транспорта/водителей/сотрудников по каждой организации — для KPI и таблицы
    const entries = await Promise.all(list.map(async o => {
      const rma = String(o.rma);
      const [v, d, e] = await Promise.all([
        md.vehicles(rma).catch(() => [] as Row[]),
        md.drivers(rma).catch(() => [] as Row[]),
        md.employees(rma).catch(() => [] as Row[]),
      ]);
      return [rma, { vehicles: v.length, drivers: d.length, employees: e.length }] as const;
    }));
    setCounts(Object.fromEntries(entries));
    return list;
  }, []);

  useEffect(() => { loadOrgs().catch(e => setError(e.message)); }, [loadOrgs]);

  const reload = useCallback(async () => {
    if (!orgRma) return;
    const fn = tab === 'drivers' ? md.drivers : tab === 'vehicles' ? md.vehicles : md.employees;
    setRows(await fn(orgRma));
  }, [orgRma, tab]);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);

  async function syncOrganization(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setOk('');
    try {
      const org = await postJson('/md-api/api/v1/sync/organization', { inn: orgInn });
      setOk(`«${org.name}» загружена из единой платформы (${SUBJECT_TYPES[org.subjectType] ?? org.subjectType})`);
      setOrgInn('');
      await loadOrgs();
      setOrgRma(String(org.rma));
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setOk('');
    try {
      if (tab === 'drivers') {
        const d = await postJson('/md-api/api/v1/sync/driver', {
          inn: driverForm.inn,
          organizationRma: orgRma,
          tabNumber: driverForm.tabNumber || null,
        });
        setOk(`Водитель ${d.fullName} добавлен: ФИО — из налоговой, ВУ ${d.licenseNumber} и медсправка — из ГАИ/Минздрава`);
        setDriverForm({ inn: '', tabNumber: '' });
      } else if (tab === 'vehicles') {
        const v = await postJson('/md-api/api/v1/sync/vehicle', {
          registrationNumber: vehicleForm.registrationNumber,
          organizationRma: orgRma,
          parkingNumber: vehicleForm.parkingNumber || null,
        });
        setOk(`ТС ${v.registrationNumber} (${v.brand}) добавлено из базы ГАИ`);
        setVehicleForm({ registrationNumber: '', parkingNumber: '' });
      } else {
        const emp = await postJson('/md-api/api/v1/sync/employee', {
          inn: employeeForm.inn,
          organizationRma: orgRma,
          type: Number(employeeForm.type),
          tabNumber: employeeForm.tabNumber || null,
        });
        setOk(`Сотрудник ${emp.name} добавлен (${EMPLOYEE_TYPES[Number(emp.type)] ?? ''})`);
        setEmployeeForm({ inn: '', type: '1', tabNumber: '' });
      }
      setShowForm(false);
      await loadOrgs().catch(() => {});
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  // Ручное создание организации (POST /organizations — полный набор полей).
  async function createOrgManual(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setOk('');
    try {
      const created = await md.createOrganization(clean(orgManual, ['typeCompany', 'regionId']));
      setOk(`Организация «${String(created.name)}» сохранена (РМА ${String(created.rma)})`);
      setOrgManual({});
      await loadOrgs();
      setOrgRma(String(created.rma));
    } catch (err) {
      setError((err as Error).message);
    }
  }

  // Ручное создание водителя / ТС / сотрудника (POST /drivers|/vehicles|/employees).
  async function submitManual(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setOk('');
    try {
      if (tab === 'drivers') {
        const d = await md.createDriver({ ...clean(driverManual, ['degree']), organizationRma: orgRma });
        setOk(`Водитель ${String(d.fullName)} сохранён`);
        setDriverManual({});
      } else if (tab === 'vehicles') {
        const v = await md.createVehicle({ ...clean(vehicleManual, ['transportType', 'capacity', 'carrying', 'odometer', 'yearManufacture']), organizationRma: orgRma });
        setOk(`ТС ${String(v.registrationNumber)} сохранено`);
        setVehicleManual({ transportType: '1' });
      } else {
        const emp = await md.createEmployee({ ...clean(employeeManual, ['type']), organizationRma: orgRma });
        setOk(`Сотрудник ${String(emp.name)} сохранён`);
        setEmployeeManual({ type: '1' });
      }
      setShowForm(false);
      await loadOrgs().catch(() => {});
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  // Хелпер полей ручных форм: {...om('name')} даёт value + onChange для input/select.
  const mkField = (state: Record<string, string>, set: (v: Record<string, string>) => void) =>
    (k: string) => ({
      value: state[k] ?? '',
      onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => set({ ...state, [k]: e.target.value }),
    });
  const om = mkField(orgManual, setOrgManual);
  const dm = mkField(driverManual, setDriverManual);
  const vm = mkField(vehicleManual, setVehicleManual);
  const em = mkField(employeeManual, setEmployeeManual);

  // Редактирование записи: предзаполняем ручную форму значениями строки и включаем режим «Ручной ввод».
  // Сохранение — тот же upsert по РМА/госномеру (обновляет существующую запись).
  function editOrg(row: Row) {
    setError(''); setOk('');
    setOrgMode('manual');
    setOrgManual(rowToForm(row, ORG_KEYS));
  }
  function editEntity(row: Row) {
    setError(''); setOk('');
    setEntityMode('manual');
    setShowForm(true);
    if (tab === 'drivers') setDriverManual(rowToForm(row, DRIVER_KEYS));
    else if (tab === 'vehicles') setVehicleManual(rowToForm(row, VEHICLE_KEYS));
    else setEmployeeManual(rowToForm(row, EMPLOYEE_KEYS));
  }

  const org = orgs.find(o => String(o.rma) === orgRma);

  const totals = useMemo(() => {
    const list = Object.values(counts);
    return {
      vehicles: list.reduce((a, c) => a + c.vehicles, 0),
      drivers: list.reduce((a, c) => a + c.drivers, 0),
      active: orgs.filter(orgActive).length,
    };
  }, [counts, orgs]);

  const KPIS = [
    { label: t('comp.kpi.orgs'), value: orgs.length, icon: P.building, cls: 'ic-blue' },
    { label: t('comp.kpi.transport'), value: totals.vehicles, icon: P.car, cls: 'ic-cyan' },
    { label: t('comp.kpi.drivers'), value: totals.drivers, icon: P.users, cls: 'ic-purple' },
    { label: t('comp.kpi.active'), value: totals.active, icon: P.check, cls: 'ic-green' },
  ];

  const filteredOrgs = orgs.filter(o => {
    const s = orgSearch.trim().toLowerCase();
    if (!s) return true;
    return String(o.name ?? '').toLowerCase().includes(s) || String(o.rma ?? '').includes(s);
  });

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('comp.h')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('comp.lead')}</div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      {/* KPI */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        {KPIS.map(k => (
          <div className="kpi" key={k.label}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
              <span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span>
              <div style={{ minWidth: 0 }}>
                <div className="k-label">{k.label}</div>
                <div className="k-value" style={{ marginTop: 4 }}>{k.value.toLocaleString('ru-RU')}</div>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Таблица организаций */}
      <div className="card">
        <div className="card-h">
          <h2>{t('comp.orglist')}</h2>
          <input
            value={orgSearch}
            onChange={e => setOrgSearch(e.target.value)}
            placeholder={t('comp.search.org')}
            style={{ marginLeft: 'auto', width: 300 }}
          />
        </div>
        <table>
          <thead>
            <tr><th>{t('col.name')}</th><th>{t('col.innrma')}</th><th>{t('col.type')}</th><th>{t('col.transport')}</th><th>{t('col.drivers')}</th><th>{t('col.status')}</th><th>{t('col.actions')}</th></tr>
          </thead>
          <tbody>
            {filteredOrgs.map(o => {
              const rma = String(o.rma);
              const sel = rma === orgRma;
              const c = counts[rma];
              const active = orgActive(o);
              return (
                <tr
                  key={rma}
                  className="clickable"
                  onClick={() => { setOrgRma(rma); setShowForm(false); }}
                  style={sel ? { background: 'var(--blue-050)' } : undefined}
                >
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(o.name ?? '—')}</td>
                  <td><span className="number">{rma}</span></td>
                  <td>{SUBJECT_TYPES[String(o.subjectType)] ?? '—'}</td>
                  <td>{c ? c.vehicles : '—'}</td>
                  <td>{c ? c.drivers : '—'}</td>
                  <td><span className={`badge ${active ? 'green' : 'red'}`}>{active ? t('comp.badge.active') : t('comp.badge.licexpired')}</span></td>
                  <td onClick={e => e.stopPropagation()}>
                    <button type="button" className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => editOrg(o)}>{t('btn.edit')}</button>
                  </td>
                </tr>
              );
            })}
            {filteredOrgs.length === 0 && (
              <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 24 }}>
                {orgs.length === 0 ? t('comp.empty.orgs') : t('common.notfound')}
              </td></tr>
            )}
          </tbody>
        </table>
        {orgs.length > 0 && (
          <div style={{ marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>{t('comp.totalorgs')}: {orgs.length}</div>
        )}
      </div>

      {/* Добавление организации: из единой платформы (по ИНН) или ручным вводом */}
      <div className="card">
        <div className="card-h">
          <h2>{t('comp.addorg')}</h2>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
            <button type="button" className={`btn ${orgMode === 'sync' ? '' : 'secondary'}`} onClick={() => setOrgMode('sync')}>{t('comp.src.unified')}</button>
            <button type="button" className={`btn ${orgMode === 'manual' ? '' : 'secondary'}`} onClick={() => setOrgMode('manual')}>{t('comp.src.manual')}</button>
          </div>
        </div>
        {orgMode === 'sync' ? (
          <>
            <p className="hint">{t('comp.hint.syncorg')}</p>
            <form className="grid" onSubmit={syncOrganization}>
              <div>
                <label>{t('comp.f.orginn')}</label>
                <input required pattern="\d{9,10}" placeholder="025680800" value={orgInn} onChange={e => setOrgInn(e.target.value)} />
              </div>
              <div style={{ alignSelf: 'end' }}>
                <button className="btn" type="submit">{t('comp.btn.loadunified')}</button>
              </div>
            </form>
          </>
        ) : (
          <>
            <p className="hint">{t('comp.hint.manualorg')}</p>
            <form className="grid" onSubmit={createOrgManual}>
              <div><label>{t('comp.f.rmainn')}</label><input required pattern="\d{9,10}" placeholder="025680800" {...om('rma')} /></div>
              <div><label>{t('comp.f.name_req')}</label><input required placeholder="ООО «ТрансЛогистик»" {...om('name')} /></div>
              <div><label>{t('comp.f.kpp')}</label><input {...om('kpp')} /></div>
              <div><label>{t('comp.f.typecompany')}</label><input type="number" placeholder="1" {...om('typeCompany')} /></div>
              <div><label>{t('f.region')}</label><input type="number" min={1} max={7} {...om('regionId')} /></div>
              <div><label>{t('comp.f.city')}</label><input placeholder="Душанбе" {...om('cityName')} /></div>
              <div><label>{t('col.address')}</label><input {...om('address')} /></div>
              <div><label>{t('col.phone')}</label><input {...om('phone')} /></div>
              <div><label>{t('comp.f.email')}</label><input type="email" {...om('email')} /></div>
              <div><label>{t('comp.f.head')}</label><input {...om('nameHead')} /></div>
              <div><label>{t('comp.f.bank')}</label><input {...om('bank')} /></div>
              <div><label>{t('comp.f.licfrom')}</label><input type="date" {...om('licenseFrom')} /></div>
              <div><label>{t('comp.f.licto')}</label><input type="date" {...om('licenseTo')} /></div>
              <div className="full"><button className="btn" type="submit">{t('comp.btn.saveorg')}</button></div>
            </form>
          </>
        )}
      </div>

      {org && (
        <div className="card">
          <dl className="kv">
            <dt>{t('col.org')}</dt>
            <dd>
              <b>{String(org.name)}</b> · {t('col.innrma')} {String(org.rma)}{' '}
              {org.source === 'UNIFIED' && <span className="badge blue">{t('comp.badge.unified')}</span>}
            </dd>
            <dt>{t('comp.kv.subjectregion')}</dt>
            <dd>{SUBJECT_TYPES[String(org.subjectType)] ?? '—'} · {String(org.cityName ?? '')} ({t('col.region').toLowerCase()} {String(org.regionId ?? '—')})</dd>
            <dt>{t('comp.kv.carrierlic')}</dt>
            <dd>{String(org.licenseFrom ?? '—')} → {String(org.licenseTo ?? '—')}</dd>
          </dl>
        </div>
      )}

      {/* Разделы выбранной организации */}
      <div className="toolbar">
        <button className={`btn ${tab === 'drivers' ? '' : 'secondary'}`} onClick={() => { setTab('drivers'); setShowForm(false); }}>{t('col.drivers')}</button>
        <button className={`btn ${tab === 'vehicles' ? '' : 'secondary'}`} onClick={() => { setTab('vehicles'); setShowForm(false); }}>{t('col.transport')}</button>
        <button className={`btn ${tab === 'employees' ? '' : 'secondary'}`} onClick={() => { setTab('employees'); setShowForm(false); }}>{t('col.employees')}</button>
        <span className="spacer" />
        {org && <span style={{ color: 'var(--muted)', fontSize: 12.5, marginRight: 4 }}>{String(org.name)}</span>}
        <button className="btn" disabled={!orgRma} onClick={() => setShowForm(f => !f)}>{showForm ? t('comp.btn.hideform') : t('btn.add')}</button>
      </div>

      {showForm && (
        <div className="card" style={{ borderColor: 'var(--blue-500)' }}>
          <div className="card-h">
            <h2>{tab === 'drivers' ? t('comp.add.driver') : tab === 'vehicles' ? t('comp.add.vehicle') : t('comp.add.employee')}</h2>
            <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
              <button type="button" className={`btn ${entityMode === 'sync' ? '' : 'secondary'}`} onClick={() => setEntityMode('sync')}>{t('comp.src.unified')}</button>
              <button type="button" className={`btn ${entityMode === 'manual' ? '' : 'secondary'}`} onClick={() => setEntityMode('manual')}>{t('comp.src.manual')}</button>
            </div>
          </div>

          {entityMode === 'sync' ? (
            <>
              {tab === 'drivers' && (
                <>
                  <p className="hint">{t('comp.hint.syncdriver')}</p>
                  <form className="grid" onSubmit={submit}>
                    <div><label>{t('comp.f.driverinn')}</label><input required pattern="\d{9,10}" value={driverForm.inn} onChange={e => setDriverForm({ ...driverForm, inn: e.target.value })} /></div>
                    <div><label>{t('comp.f.tabcompany')}</label><input value={driverForm.tabNumber} onChange={e => setDriverForm({ ...driverForm, tabNumber: e.target.value })} /></div>
                    <div className="full"><button className="btn" type="submit">{t('comp.btn.addunified')}</button></div>
                  </form>
                </>
              )}
              {tab === 'vehicles' && (
                <>
                  <p className="hint">{t('comp.hint.syncvehicle')}</p>
                  <form className="grid" onSubmit={submit}>
                    <div><label>{t('col.regnum')}</label><input required placeholder="0101TJ01" value={vehicleForm.registrationNumber} onChange={e => setVehicleForm({ ...vehicleForm, registrationNumber: e.target.value })} /></div>
                    <div><label>{t('comp.f.parkinglocal')}</label><input pattern="\d{4}" value={vehicleForm.parkingNumber} onChange={e => setVehicleForm({ ...vehicleForm, parkingNumber: e.target.value })} /></div>
                    <div className="full"><button className="btn" type="submit">{t('comp.btn.addgai')}</button></div>
                  </form>
                </>
              )}
              {tab === 'employees' && (
                <>
                  <p className="hint">{t('comp.hint.syncemployee')}</p>
                  <form className="grid" onSubmit={submit}>
                    <div><label>{t('comp.f.empinn')}</label><input required pattern="\d{9,10}" value={employeeForm.inn} onChange={e => setEmployeeForm({ ...employeeForm, inn: e.target.value })} /></div>
                    <div><label>{t('col.position')}</label>
                      <select value={employeeForm.type} onChange={e => setEmployeeForm({ ...employeeForm, type: e.target.value })}>
                        {Object.entries(EMPLOYEE_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                      </select>
                    </div>
                    <div><label>{t('f.tab')}</label><input value={employeeForm.tabNumber} onChange={e => setEmployeeForm({ ...employeeForm, tabNumber: e.target.value })} /></div>
                    <div className="full"><button className="btn" type="submit">{t('comp.btn.addunified')}</button></div>
                  </form>
                </>
              )}
            </>
          ) : (
            <>
              <p className="hint">{t('comp.hint.manual.pre')} «{org ? String(org.name) : ''}»{t('comp.hint.manual.post')}</p>
              {tab === 'drivers' && (
                <form className="grid" onSubmit={submitManual}>
                  <div><label>{t('comp.f.rmainn')}</label><input required pattern="\d{9,10}" {...dm('rma')} /></div>
                  <div><label>{t('comp.f.fio_req')}</label><input required placeholder="Иванов Иван Иванович" {...dm('fullName')} /></div>
                  <div><label>{t('f.tab')}</label><input {...dm('tabNumber')} /></div>
                  <div><label>{t('comp.f.licnum')}</label><input placeholder="77 01 123456" {...dm('licenseNumber')} /></div>
                  <div><label>{t('comp.f.cats')}</label><input {...dm('licenseCategories')} /></div>
                  <div><label>{t('comp.f.licvalid')}</label><input type="date" {...dm('licenseValidTo')} /></div>
                  <div><label>{t('comp.f.degree')}</label><input type="number" {...dm('degree')} /></div>
                  <div><label>{t('comp.f.medcertnum')}</label><input {...dm('medCertNumber')} /></div>
                  <div><label>{t('col.medto')}</label><input type="date" {...dm('medCertValidTo')} /></div>
                  <div><label>{t('comp.f.safetyto')}</label><input type="date" {...dm('safetyCourseValidTo')} /></div>
                  <div><label>{t('col.phone')}</label><input {...dm('phone')} /></div>
                  <div className="full"><button className="btn" type="submit">{t('comp.btn.savedriver')}</button></div>
                </form>
              )}
              {tab === 'vehicles' && (
                <form className="grid" onSubmit={submitManual}>
                  <div><label>{t('comp.f.regnum_req')}</label><input required pattern="[A-Za-zА-Яа-я0-9]{4,20}" placeholder="0101TJ01" {...vm('registrationNumber')} /></div>
                  <div><label>{t('comp.f.vehtype_req')}</label>
                    <select required {...vm('transportType')}>
                      {Object.entries(TRANSPORT_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                    </select>
                  </div>
                  <div><label>{t('tech.f.brandmodel')}</label><input placeholder="КАМАЗ 65115" {...vm('brand')} /></div>
                  <div><label>{t('comp.f.parking')}</label><input pattern="\d{4}" {...vm('parkingNumber')} /></div>
                  <div><label>{t('comp.f.capacity')}</label><input type="number" {...vm('capacity')} /></div>
                  <div><label>{t('comp.f.carrying')}</label><input type="number" step="0.01" {...vm('carrying')} /></div>
                  <div><label>{t('comp.f.odometerkm')}</label><input type="number" {...vm('odometer')} /></div>
                  <div><label>VIN</label><input {...vm('vincode')} /></div>
                  <div><label>{t('comp.f.year')}</label><input type="number" min={1950} max={2100} {...vm('yearManufacture')} /></div>
                  <div><label>{t('col.techto')}</label><input type="date" {...vm('techInspectionValidTo')} /></div>
                  <div><label>{t('comp.f.controlcardto')}</label><input type="date" {...vm('controlCardValidTo')} /></div>
                  <div className="full"><button className="btn" type="submit">{t('comp.btn.savevehicle')}</button></div>
                </form>
              )}
              {tab === 'employees' && (
                <form className="grid" onSubmit={submitManual}>
                  <div><label>{t('comp.f.rmainn')}</label><input required pattern="\d{9,10}" {...em('rma')} /></div>
                  <div><label>{t('comp.f.fio_req')}</label><input required placeholder="Петров Пётр Петрович" {...em('name')} /></div>
                  <div><label>{t('comp.f.position_req')}</label>
                    <select required {...em('type')}>
                      {Object.entries(EMPLOYEE_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                    </select>
                  </div>
                  <div><label>{t('f.tab')}</label><input {...em('tabNumber')} /></div>
                  <div><label>{t('col.phone')}</label><input {...em('phone')} /></div>
                  <div className="full"><button className="btn" type="submit">{t('comp.btn.saveemployee')}</button></div>
                </form>
              )}
            </>
          )}
        </div>
      )}

      <div className="card">
        {tab === 'drivers' && (
          <table>
            <thead><tr><th>{t('col.fio')}</th><th>{t('col.innrma')}</th><th>{t('col.tab')}</th><th>{t('tech.license')}</th><th>{t('tech.categories')}</th><th>{t('col.licto')}</th><th>{t('col.medto')}</th><th>{t('col.actions')}</th></tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={String(r.id)}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.fullName)}</td><td><span className="number">{String(r.rma)}</span></td><td>{String(r.tabNumber ?? '—')}</td>
                  <td>{String(r.licenseNumber ?? '—')}</td><td>{String(r.licenseCategories ?? '—')}</td>
                  <td>{String(r.licenseValidTo ?? '—')}</td><td>{String(r.medCertValidTo ?? '—')}</td>
                  <td><button type="button" className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => editEntity(r)}>{t('btn.edit')}</button></td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('comp.empty.drivers')}</td></tr>}
            </tbody>
          </table>
        )}
        {tab === 'vehicles' && (
          <table>
            <thead><tr><th>{t('col.regnum')}</th><th>{t('col.type')}</th><th>{t('col.brand')}</th><th>{t('col.parking')}</th><th>{t('col.odometer')}</th><th>{t('col.techto')}</th><th>{t('col.cardto')}</th><th>{t('col.actions')}</th></tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={String(r.id)}>
                  <td><span className="number">{String(r.registrationNumber)}</span></td><td>{TRANSPORT_TYPES[Number(r.transportType)] ?? String(r.transportType)}</td>
                  <td>{String(r.brand ?? '—')}</td><td>{String(r.parkingNumber ?? '—')}</td><td>{String(r.odometer)}</td>
                  <td>{String(r.techInspectionValidTo ?? '—')}</td><td>{String(r.controlCardValidTo ?? '—')}</td>
                  <td><button type="button" className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => editEntity(r)}>{t('btn.edit')}</button></td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('comp.empty.vehicles')}</td></tr>}
            </tbody>
          </table>
        )}
        {tab === 'employees' && (
          <table>
            <thead><tr><th>{t('col.fio')}</th><th>{t('col.innrma')}</th><th>{t('col.position')}</th><th>{t('col.tab')}</th><th>{t('col.phone')}</th><th>{t('col.actions')}</th></tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={String(r.id)}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td><span className="number">{String(r.rma)}</span></td><td>{EMPLOYEE_TYPES[Number(r.type)] ?? String(r.type)}</td>
                  <td>{String(r.tabNumber ?? '—')}</td><td>{String(r.phone ?? '—')}</td>
                  <td><button type="button" className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => editEntity(r)}>{t('btn.edit')}</button></td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('comp.empty.employees')}</td></tr>}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
