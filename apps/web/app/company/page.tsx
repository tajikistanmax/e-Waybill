'use client';

import { useCallback, useEffect, useState } from 'react';
import { authHeaders, md } from '@/lib/api';

type Row = Record<string, unknown>;
type Tab = 'drivers' | 'vehicles' | 'employees';

const EMPLOYEE_TYPES: Record<number, string> = { 1: 'Врач (духтур)', 2: 'Механик', 3: 'Диспетчер (танзимгар)' };
const TRANSPORT_TYPES: Record<number, string> = {
  1: 'Автобус', 2: 'Троллейбус', 3: 'Микроавтобус', 4: 'Легковой (сабукрав)', 5: 'Грузовой (2-Б)', 6: 'Грузовой межд. (5Б-БМ)',
};
const SUBJECT_TYPES: Record<string, string> = { PHYSICAL: 'Физлицо', IP: 'ИП', LEGAL: 'Юрлицо' };

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

/**
 * Кабинет компании-перевозчика. Субъекты (водители, сотрудники) и объекты (ТС)
 * НЕ регистрируются здесь вручную — они добавляются по ИНН/госномеру, а данные
 * приходят из единой платформы Минтранса (налоговая, ГАИ, Минздрав).
 */
export default function CompanyPage() {
  const [orgs, setOrgs] = useState<Row[]>([]);
  const [orgRma, setOrgRma] = useState('');
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

  const loadOrgs = useCallback(async () => {
    const list = await md.organizations();
    setOrgs(list);
    if (list.length > 0) setOrgRma(prev => prev || String(list[0].rma));
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
          {orgs.length === 0 && <option value="">— организаций нет —</option>}
          {orgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{String(o.name)} ({String(o.rma)})</option>)}
        </select>
      </div>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      <div className="card">
        <h2>Организация из единой платформы</h2>
        <p className="hint">
          Субъекты (физлицо, ИП, юрлицо) регистрируются один раз в единой платформе Минтранса.
          Укажите ИНН — название, тип, адрес и лицензия придут из налоговой и платформы лицензирования.
        </p>
        <form className="grid" onSubmit={syncOrganization}>
          <div>
            <label>ИНН организации / ИП (9–10 цифр)</label>
            <input required pattern="\d{9,10}" placeholder="025680800" value={orgInn} onChange={e => setOrgInn(e.target.value)} />
          </div>
          <div style={{ alignSelf: 'end' }}>
            <button className="btn" type="submit">Загрузить из единой платформы</button>
          </div>
        </form>
      </div>

      {org && (
        <div className="card">
          <dl className="kv">
            <dt>Организация</dt>
            <dd>
              <b>{String(org.name)}</b> · ИНН/РМА {String(org.rma)}{' '}
              {org.source === 'UNIFIED' && <span className="badge green">из единой платформы</span>}
            </dd>
            <dt>Субъект / регион</dt>
            <dd>{SUBJECT_TYPES[String(org.subjectType)] ?? '—'} · {String(org.cityName ?? '')} (регион {String(org.regionId ?? '—')})</dd>
            <dt>Лицензия перевозчика</dt>
            <dd>{String(org.licenseFrom ?? '—')} → {String(org.licenseTo ?? '—')}</dd>
          </dl>
        </div>
      )}

      <div className="toolbar">
        <button className={`btn ${tab === 'drivers' ? '' : 'secondary'}`} onClick={() => { setTab('drivers'); setShowForm(false); }}>Водители</button>
        <button className={`btn ${tab === 'vehicles' ? '' : 'secondary'}`} onClick={() => { setTab('vehicles'); setShowForm(false); }}>Транспорт</button>
        <button className={`btn ${tab === 'employees' ? '' : 'secondary'}`} onClick={() => { setTab('employees'); setShowForm(false); }}>Сотрудники</button>
        <span className="spacer" />
        <button className="btn" disabled={!orgRma} onClick={() => setShowForm(f => !f)}>{showForm ? 'Скрыть форму' : '+ Добавить'}</button>
      </div>

      {showForm && (
        <div className="card" style={{ borderColor: 'var(--brand)' }}>
          {tab === 'drivers' && (
            <>
              <h2>Водитель по ИНН</h2>
              <p className="hint">ФИО и телефон — из налоговой; водительское удостоверение и медсправка — из баз ГАИ и Минздрава. Вручную ничего не вводится.</p>
              <form className="grid" onSubmit={submit}>
                <div><label>ИНН водителя (9–10 цифр)</label><input required pattern="\d{9,10}" value={driverForm.inn} onChange={e => setDriverForm({ ...driverForm, inn: e.target.value })} /></div>
                <div><label>Табельный номер (в вашей компании)</label><input value={driverForm.tabNumber} onChange={e => setDriverForm({ ...driverForm, tabNumber: e.target.value })} /></div>
                <div className="full"><button className="btn" type="submit">Добавить из единой платформы</button></div>
              </form>
            </>
          )}
          {tab === 'vehicles' && (
            <>
              <h2>Транспортное средство по госномеру</h2>
              <p className="hint">Марка, VIN, год выпуска, вместимость и техосмотр — из базы ГАИ через единую платформу.</p>
              <form className="grid" onSubmit={submit}>
                <div><label>Госномер</label><input required placeholder="0101TJ01" value={vehicleForm.registrationNumber} onChange={e => setVehicleForm({ ...vehicleForm, registrationNumber: e.target.value })} /></div>
                <div><label>Стоянка (4 цифры, локально)</label><input pattern="\d{4}" value={vehicleForm.parkingNumber} onChange={e => setVehicleForm({ ...vehicleForm, parkingNumber: e.target.value })} /></div>
                <div className="full"><button className="btn" type="submit">Добавить из базы ГАИ</button></div>
              </form>
            </>
          )}
          {tab === 'employees' && (
            <>
              <h2>Сотрудник по ИНН</h2>
              <p className="hint">ФИО — из налоговой; должность (врач/механик/диспетчер) — ваша, локальная.</p>
              <form className="grid" onSubmit={submit}>
                <div><label>ИНН сотрудника (9–10 цифр)</label><input required pattern="\d{9,10}" value={employeeForm.inn} onChange={e => setEmployeeForm({ ...employeeForm, inn: e.target.value })} /></div>
                <div><label>Должность</label>
                  <select value={employeeForm.type} onChange={e => setEmployeeForm({ ...employeeForm, type: e.target.value })}>
                    {Object.entries(EMPLOYEE_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                  </select>
                </div>
                <div><label>Табельный номер</label><input value={employeeForm.tabNumber} onChange={e => setEmployeeForm({ ...employeeForm, tabNumber: e.target.value })} /></div>
                <div className="full"><button className="btn" type="submit">Добавить из единой платформы</button></div>
              </form>
            </>
          )}
        </div>
      )}

      <div className="card">
        {tab === 'drivers' && (
          <table>
            <thead><tr><th>Ф.И.О.</th><th>ИНН/РМА</th><th>Табель</th><th>ВУ</th><th>Категории</th><th>ВУ до</th><th>Медсправка до</th></tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={String(r.id)}>
                  <td>{String(r.fullName)}</td><td>{String(r.rma)}</td><td>{String(r.tabNumber ?? '—')}</td>
                  <td>{String(r.licenseNumber ?? '—')}</td><td>{String(r.licenseCategories ?? '—')}</td>
                  <td>{String(r.licenseValidTo ?? '—')}</td><td>{String(r.medCertValidTo ?? '—')}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Пока пусто — добавьте водителя по ИНН</td></tr>}
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
              {rows.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Пока пусто — добавьте ТС по госномеру</td></tr>}
            </tbody>
          </table>
        )}
        {tab === 'employees' && (
          <table>
            <thead><tr><th>Ф.И.О.</th><th>ИНН/РМА</th><th>Должность</th><th>Табель</th><th>Телефон</th></tr></thead>
            <tbody>
              {rows.map(r => (
                <tr key={String(r.id)}>
                  <td>{String(r.name)}</td><td>{String(r.rma)}</td><td>{EMPLOYEE_TYPES[Number(r.type)] ?? String(r.type)}</td>
                  <td>{String(r.tabNumber ?? '—')}</td><td>{String(r.phone ?? '—')}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>Пока пусто — добавьте сотрудника по ИНН</td></tr>}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
