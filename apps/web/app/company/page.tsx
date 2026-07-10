'use client';

import { useCallback, useEffect, useState } from 'react';
import { authHeaders, md } from '@/lib/api';

type Row = Record<string, unknown>;
type Tab = 'drivers' | 'vehicles' | 'employees';

const EMPLOYEE_TYPES: Record<number, string> = { 1: 'Врач (духтур)', 2: 'Механик', 3: 'Диспетчер (танзимгар)' };
const TRANSPORT_TYPES: Record<number, string> = {
  1: 'Автобус', 2: 'Троллейбус', 3: 'Микроавтобус', 4: 'Легковой (сабукрав)', 5: 'Грузовой (2-Б)', 6: 'Грузовой межд. (5Б-БМ)',
};

async function postJson(url: string, body: unknown) {
  const res = await fetch(url, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const p = await res.json().catch(() => null);
    const fields = p?.errors?.map((e: { field: string; message: string }) => `${e.field} — ${e.message}`).join('; ');
    throw new Error(p?.detail ?? p?.title ?? `Ошибка ${res.status}` + (fields ? `: ${fields}` : ''));
  }
  return res.json();
}

/** Кабинет компании-перевозчика: свои водители, ТС и сотрудники. */
export default function CompanyPage() {
  const [orgs, setOrgs] = useState<Row[]>([]);
  const [orgRma, setOrgRma] = useState('');
  const [tab, setTab] = useState<Tab>('drivers');
  const [rows, setRows] = useState<Row[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  const [driverForm, setDriverForm] = useState({ rma: '', fullName: '', tabNumber: '', licenseNumber: '', licenseCategories: 'D', licenseValidTo: '', medCertValidTo: '', phone: '' });
  const [vehicleForm, setVehicleForm] = useState({ registrationNumber: '', transportType: '1', brand: '', parkingNumber: '', capacity: '', odometer: '', techInspectionValidTo: '', controlCardValidTo: '' });
  const [employeeForm, setEmployeeForm] = useState({ rma: '', name: '', type: '1', tabNumber: '', phone: '' });

  useEffect(() => {
    md.organizations().then(list => {
      setOrgs(list);
      if (list.length > 0 && !orgRma) setOrgRma(String(list[0].rma));
    }).catch(e => setError(e.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const reload = useCallback(async () => {
    if (!orgRma) return;
    const fn = tab === 'drivers' ? md.drivers : tab === 'vehicles' ? md.vehicles : md.employees;
    setRows(await fn(orgRma));
  }, [orgRma, tab]);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setOk('');
    try {
      if (tab === 'drivers') {
        await postJson('/md-api/api/v1/drivers', {
          ...driverForm,
          organizationRma: orgRma,
          licenseValidTo: driverForm.licenseValidTo || null,
          medCertValidTo: driverForm.medCertValidTo || null,
        });
        setOk(`Водитель ${driverForm.fullName} сохранён`);
      } else if (tab === 'vehicles') {
        await postJson('/md-api/api/v1/vehicles', {
          ...vehicleForm,
          organizationRma: orgRma,
          transportType: Number(vehicleForm.transportType),
          capacity: vehicleForm.capacity ? Number(vehicleForm.capacity) : null,
          odometer: vehicleForm.odometer ? Number(vehicleForm.odometer) : null,
          parkingNumber: vehicleForm.parkingNumber || null,
          techInspectionValidTo: vehicleForm.techInspectionValidTo || null,
          controlCardValidTo: vehicleForm.controlCardValidTo || null,
        });
        setOk(`ТС ${vehicleForm.registrationNumber} сохранено`);
      } else {
        await postJson('/md-api/api/v1/employees', {
          ...employeeForm,
          organizationRma: orgRma,
          type: Number(employeeForm.type),
        });
        setOk(`Сотрудник ${employeeForm.name} сохранён`);
      }
      setShowForm(false);
      await reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  const org = orgs.find(o => String(o.rma) === orgRma);

  return (
    <>
      <div className="toolbar">
        <h1>Кабинет компании</h1>
        <span className="spacer" />
        <select style={{ width: 340 }} value={orgRma} onChange={e => setOrgRma(e.target.value)}>
          {orgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{String(o.name)} ({String(o.rma)})</option>)}
        </select>
      </div>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      {org && (
        <div className="card">
          <dl className="kv">
            <dt>Организация</dt><dd><b>{String(org.name)}</b> · РМА {String(org.rma)}</dd>
            <dt>Тип / регион</dt><dd>{org.typeCompany === 1 ? 'Общего пользования' : 'Ведомственная'} · регион {String(org.regionId ?? '—')}</dd>
            <dt>Лицензия</dt><dd>{String(org.licenseFrom ?? '—')} → {String(org.licenseTo ?? '—')}</dd>
          </dl>
        </div>
      )}

      <div className="toolbar">
        <button className={`btn ${tab === 'drivers' ? '' : 'secondary'}`} onClick={() => { setTab('drivers'); setShowForm(false); }}>Водители</button>
        <button className={`btn ${tab === 'vehicles' ? '' : 'secondary'}`} onClick={() => { setTab('vehicles'); setShowForm(false); }}>Транспорт</button>
        <button className={`btn ${tab === 'employees' ? '' : 'secondary'}`} onClick={() => { setTab('employees'); setShowForm(false); }}>Сотрудники</button>
        <span className="spacer" />
        <button className="btn" onClick={() => setShowForm(f => !f)}>{showForm ? 'Скрыть форму' : '+ Добавить'}</button>
      </div>

      {showForm && (
        <div className="card" style={{ borderColor: 'var(--brand)', borderWidth: 2 }}>
          {tab === 'drivers' && (
            <form className="grid" onSubmit={submit}>
              <div><label>РМА (9–10 цифр)</label><input required pattern="\d{9,10}" value={driverForm.rma} onChange={e => setDriverForm({ ...driverForm, rma: e.target.value })} /></div>
              <div><label>Ф.И.О.</label><input required value={driverForm.fullName} onChange={e => setDriverForm({ ...driverForm, fullName: e.target.value })} /></div>
              <div><label>Табельный номер</label><input value={driverForm.tabNumber} onChange={e => setDriverForm({ ...driverForm, tabNumber: e.target.value })} /></div>
              <div><label>№ водительского удостоверения</label><input value={driverForm.licenseNumber} onChange={e => setDriverForm({ ...driverForm, licenseNumber: e.target.value })} /></div>
              <div><label>Категории</label><input value={driverForm.licenseCategories} onChange={e => setDriverForm({ ...driverForm, licenseCategories: e.target.value })} /></div>
              <div><label>ВУ действительно до</label><input type="date" value={driverForm.licenseValidTo} onChange={e => setDriverForm({ ...driverForm, licenseValidTo: e.target.value })} /></div>
              <div><label>Медсправка до</label><input type="date" value={driverForm.medCertValidTo} onChange={e => setDriverForm({ ...driverForm, medCertValidTo: e.target.value })} /></div>
              <div><label>Телефон</label><input value={driverForm.phone} onChange={e => setDriverForm({ ...driverForm, phone: e.target.value })} /></div>
              <div className="full"><button className="btn" type="submit">Сохранить водителя</button></div>
            </form>
          )}
          {tab === 'vehicles' && (
            <form className="grid" onSubmit={submit}>
              <div><label>Госномер</label><input required value={vehicleForm.registrationNumber} onChange={e => setVehicleForm({ ...vehicleForm, registrationNumber: e.target.value })} /></div>
              <div><label>Тип ТС</label>
                <select value={vehicleForm.transportType} onChange={e => setVehicleForm({ ...vehicleForm, transportType: e.target.value })}>
                  {Object.entries(TRANSPORT_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                </select>
              </div>
              <div><label>Марка (тамға)</label><input value={vehicleForm.brand} onChange={e => setVehicleForm({ ...vehicleForm, brand: e.target.value })} /></div>
              <div><label>Стоянка (4 цифры)</label><input pattern="\d{4}" value={vehicleForm.parkingNumber} onChange={e => setVehicleForm({ ...vehicleForm, parkingNumber: e.target.value })} /></div>
              <div><label>Вместимость</label><input type="number" value={vehicleForm.capacity} onChange={e => setVehicleForm({ ...vehicleForm, capacity: e.target.value })} /></div>
              <div><label>Одометр, км</label><input type="number" value={vehicleForm.odometer} onChange={e => setVehicleForm({ ...vehicleForm, odometer: e.target.value })} /></div>
              <div><label>Техосмотр до</label><input type="date" value={vehicleForm.techInspectionValidTo} onChange={e => setVehicleForm({ ...vehicleForm, techInspectionValidTo: e.target.value })} /></div>
              <div><label>Контрольная карточка до</label><input type="date" value={vehicleForm.controlCardValidTo} onChange={e => setVehicleForm({ ...vehicleForm, controlCardValidTo: e.target.value })} /></div>
              <div className="full"><button className="btn" type="submit">Сохранить ТС</button></div>
            </form>
          )}
          {tab === 'employees' && (
            <form className="grid" onSubmit={submit}>
              <div><label>РМА (9–10 цифр)</label><input required pattern="\d{9,10}" value={employeeForm.rma} onChange={e => setEmployeeForm({ ...employeeForm, rma: e.target.value })} /></div>
              <div><label>Ф.И.О.</label><input required value={employeeForm.name} onChange={e => setEmployeeForm({ ...employeeForm, name: e.target.value })} /></div>
              <div><label>Должность</label>
                <select value={employeeForm.type} onChange={e => setEmployeeForm({ ...employeeForm, type: e.target.value })}>
                  {Object.entries(EMPLOYEE_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                </select>
              </div>
              <div><label>Табельный номер</label><input value={employeeForm.tabNumber} onChange={e => setEmployeeForm({ ...employeeForm, tabNumber: e.target.value })} /></div>
              <div><label>Телефон</label><input value={employeeForm.phone} onChange={e => setEmployeeForm({ ...employeeForm, phone: e.target.value })} /></div>
              <div className="full"><button className="btn" type="submit">Сохранить сотрудника</button></div>
            </form>
          )}
        </div>
      )}

      <div className="card">
        {tab === 'drivers' && (
          <table>
            <thead><tr><th>Ф.И.О.</th><th>РМА</th><th>Табель</th><th>ВУ</th><th>Категории</th><th>ВУ до</th><th>Медсправка до</th></tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={String(r.id)}>
                  <td>{String(r.fullName)}</td><td>{String(r.rma)}</td><td>{String(r.tabNumber ?? '—')}</td>
                  <td>{String(r.licenseNumber ?? '—')}</td><td>{String(r.licenseCategories ?? '—')}</td>
                  <td>{String(r.licenseValidTo ?? '—')}</td><td>{String(r.medCertValidTo ?? '—')}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Нет записей</td></tr>}
            </tbody>
          </table>
        )}
        {tab === 'vehicles' && (
          <table>
            <thead><tr><th>Госномер</th><th>Тип</th><th>Марка</th><th>Стоянка</th><th>Одометр</th><th>Техосмотр до</th><th>Карточка до</th></tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={String(r.id)}>
                  <td>{String(r.registrationNumber)}</td><td>{TRANSPORT_TYPES[Number(r.transportType)] ?? String(r.transportType)}</td>
                  <td>{String(r.brand ?? '—')}</td><td>{String(r.parkingNumber ?? '—')}</td><td>{String(r.odometer)}</td>
                  <td>{String(r.techInspectionValidTo ?? '—')}</td><td>{String(r.controlCardValidTo ?? '—')}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Нет записей</td></tr>}
            </tbody>
          </table>
        )}
        {tab === 'employees' && (
          <table>
            <thead><tr><th>Ф.И.О.</th><th>РМА</th><th>Должность</th><th>Табель</th><th>Телефон</th></tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={String(r.id)}>
                  <td>{String(r.name)}</td><td>{String(r.rma)}</td><td>{EMPLOYEE_TYPES[Number(r.type)] ?? String(r.type)}</td>
                  <td>{String(r.tabNumber ?? '—')}</td><td>{String(r.phone ?? '—')}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Нет записей</td></tr>}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
