'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { authHeaders, md } from '@/lib/api';
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
    throw new Error(p?.detail ?? p?.title ?? `Ошибка ${res.status}` + (fields ? `: ${fields}` : ''));
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

/**
 * Кабинет компании-перевозчика. Субъекты (водители, сотрудники) и объекты (ТС)
 * НЕ регистрируются здесь вручную — они добавляются по ИНН/госномеру, а данные
 * приходят из единой платформы Минтранса (налоговая, ГАИ, Минздрав).
 */
export default function CompanyPage() {
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
    { label: 'Организаций', value: orgs.length, icon: P.building, cls: 'ic-blue' },
    { label: 'Транспорта', value: totals.vehicles, icon: P.car, cls: 'ic-cyan' },
    { label: 'Водителей', value: totals.drivers, icon: P.users, cls: 'ic-purple' },
    { label: 'Активных', value: totals.active, icon: P.check, cls: 'ic-green' },
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
          <h1>Организации</h1>
          <div className="page-lead" style={{ margin: 0 }}>Перевозчики, транспорт, водители и сотрудники — из единой платформы Минтранса или ручным вводом</div>
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
          <h2>Список организаций</h2>
          <input
            value={orgSearch}
            onChange={e => setOrgSearch(e.target.value)}
            placeholder="Поиск по названию или ИНН"
            style={{ marginLeft: 'auto', width: 300 }}
          />
        </div>
        <table>
          <thead>
            <tr><th>Название</th><th>ИНН / РМА</th><th>Тип</th><th>Транспорт</th><th>Водители</th><th>Статус</th></tr>
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
                  <td><span className={`badge ${active ? 'green' : 'red'}`}>{active ? 'Активна' : 'Лицензия истекла'}</span></td>
                </tr>
              );
            })}
            {filteredOrgs.length === 0 && (
              <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 24 }}>
                {orgs.length === 0 ? 'Организаций пока нет — добавьте по ИНН ниже' : 'Ничего не найдено'}
              </td></tr>
            )}
          </tbody>
        </table>
        {orgs.length > 0 && (
          <div style={{ marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>Всего организаций: {orgs.length}</div>
        )}
      </div>

      {/* Добавление организации: из единой платформы (по ИНН) или ручным вводом */}
      <div className="card">
        <div className="card-h">
          <h2>Добавить организацию</h2>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
            <button type="button" className={`btn ${orgMode === 'sync' ? '' : 'secondary'}`} onClick={() => setOrgMode('sync')}>Из единой платформы</button>
            <button type="button" className={`btn ${orgMode === 'manual' ? '' : 'secondary'}`} onClick={() => setOrgMode('manual')}>Ручной ввод</button>
          </div>
        </div>
        {orgMode === 'sync' ? (
          <>
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
          </>
        ) : (
          <>
            <p className="hint">Полный ручной ввод всех реквизитов организации. Обязательны РМА/ИНН и название; остальные поля — по мере данных.</p>
            <form className="grid" onSubmit={createOrgManual}>
              <div><label>РМА / ИНН (9–10 цифр) *</label><input required pattern="\d{9,10}" placeholder="025680800" {...om('rma')} /></div>
              <div><label>Название *</label><input required placeholder="ООО «ТрансЛогистик»" {...om('name')} /></div>
              <div><label>КПП</label><input {...om('kpp')} /></div>
              <div><label>Тип компании (код, legacy)</label><input type="number" placeholder="1" {...om('typeCompany')} /></div>
              <div><label>Регион (1–7)</label><input type="number" min={1} max={7} {...om('regionId')} /></div>
              <div><label>Город</label><input placeholder="Душанбе" {...om('cityName')} /></div>
              <div><label>Адрес</label><input {...om('address')} /></div>
              <div><label>Телефон</label><input {...om('phone')} /></div>
              <div><label>Email</label><input type="email" {...om('email')} /></div>
              <div><label>Руководитель</label><input {...om('nameHead')} /></div>
              <div><label>Банк</label><input {...om('bank')} /></div>
              <div><label>Лицензия с</label><input type="date" {...om('licenseFrom')} /></div>
              <div><label>Лицензия по</label><input type="date" {...om('licenseTo')} /></div>
              <div className="full"><button className="btn" type="submit">Сохранить организацию</button></div>
            </form>
          </>
        )}
      </div>

      {org && (
        <div className="card">
          <dl className="kv">
            <dt>Организация</dt>
            <dd>
              <b>{String(org.name)}</b> · ИНН/РМА {String(org.rma)}{' '}
              {org.source === 'UNIFIED' && <span className="badge blue">из единой платформы</span>}
            </dd>
            <dt>Субъект / регион</dt>
            <dd>{SUBJECT_TYPES[String(org.subjectType)] ?? '—'} · {String(org.cityName ?? '')} (регион {String(org.regionId ?? '—')})</dd>
            <dt>Лицензия перевозчика</dt>
            <dd>{String(org.licenseFrom ?? '—')} → {String(org.licenseTo ?? '—')}</dd>
          </dl>
        </div>
      )}

      {/* Разделы выбранной организации */}
      <div className="toolbar">
        <button className={`btn ${tab === 'drivers' ? '' : 'secondary'}`} onClick={() => { setTab('drivers'); setShowForm(false); }}>Водители</button>
        <button className={`btn ${tab === 'vehicles' ? '' : 'secondary'}`} onClick={() => { setTab('vehicles'); setShowForm(false); }}>Транспорт</button>
        <button className={`btn ${tab === 'employees' ? '' : 'secondary'}`} onClick={() => { setTab('employees'); setShowForm(false); }}>Сотрудники</button>
        <span className="spacer" />
        {org && <span style={{ color: 'var(--muted)', fontSize: 12.5, marginRight: 4 }}>{String(org.name)}</span>}
        <button className="btn" disabled={!orgRma} onClick={() => setShowForm(f => !f)}>{showForm ? 'Скрыть форму' : '+ Добавить'}</button>
      </div>

      {showForm && (
        <div className="card" style={{ borderColor: 'var(--blue-500)' }}>
          <div className="card-h">
            <h2>{tab === 'drivers' ? 'Добавить водителя' : tab === 'vehicles' ? 'Добавить транспорт' : 'Добавить сотрудника'}</h2>
            <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
              <button type="button" className={`btn ${entityMode === 'sync' ? '' : 'secondary'}`} onClick={() => setEntityMode('sync')}>Из единой платформы</button>
              <button type="button" className={`btn ${entityMode === 'manual' ? '' : 'secondary'}`} onClick={() => setEntityMode('manual')}>Ручной ввод</button>
            </div>
          </div>

          {entityMode === 'sync' ? (
            <>
              {tab === 'drivers' && (
                <>
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
            </>
          ) : (
            <>
              <p className="hint">Полный ручной ввод в организацию «{org ? String(org.name) : ''}». Обязательные поля отмечены *.</p>
              {tab === 'drivers' && (
                <form className="grid" onSubmit={submitManual}>
                  <div><label>РМА / ИНН (9–10 цифр) *</label><input required pattern="\d{9,10}" {...dm('rma')} /></div>
                  <div><label>Ф.И.О. *</label><input required placeholder="Иванов Иван Иванович" {...dm('fullName')} /></div>
                  <div><label>Табельный номер</label><input {...dm('tabNumber')} /></div>
                  <div><label>Номер ВУ</label><input placeholder="77 01 123456" {...dm('licenseNumber')} /></div>
                  <div><label>Категории (напр. B, C, CE)</label><input {...dm('licenseCategories')} /></div>
                  <div><label>ВУ действует до</label><input type="date" {...dm('licenseValidTo')} /></div>
                  <div><label>Классность (степень)</label><input type="number" {...dm('degree')} /></div>
                  <div><label>Медсправка №</label><input {...dm('medCertNumber')} /></div>
                  <div><label>Медсправка до</label><input type="date" {...dm('medCertValidTo')} /></div>
                  <div><label>Курс БДД до</label><input type="date" {...dm('safetyCourseValidTo')} /></div>
                  <div><label>Телефон</label><input {...dm('phone')} /></div>
                  <div className="full"><button className="btn" type="submit">Сохранить водителя</button></div>
                </form>
              )}
              {tab === 'vehicles' && (
                <form className="grid" onSubmit={submitManual}>
                  <div><label>Госномер *</label><input required pattern="[A-Za-zА-Яа-я0-9]{4,20}" placeholder="0101TJ01" {...vm('registrationNumber')} /></div>
                  <div><label>Тип ТС *</label>
                    <select required {...vm('transportType')}>
                      {Object.entries(TRANSPORT_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                    </select>
                  </div>
                  <div><label>Марка / модель</label><input placeholder="КАМАЗ 65115" {...vm('brand')} /></div>
                  <div><label>Стоянка (4 цифры)</label><input pattern="\d{4}" {...vm('parkingNumber')} /></div>
                  <div><label>Вместимость, мест</label><input type="number" {...vm('capacity')} /></div>
                  <div><label>Грузоподъёмность, т</label><input type="number" step="0.01" {...vm('carrying')} /></div>
                  <div><label>Одометр, км</label><input type="number" {...vm('odometer')} /></div>
                  <div><label>VIN</label><input {...vm('vincode')} /></div>
                  <div><label>Год выпуска</label><input type="number" min={1950} max={2100} {...vm('yearManufacture')} /></div>
                  <div><label>Техосмотр до</label><input type="date" {...vm('techInspectionValidTo')} /></div>
                  <div><label>Контрольная карточка до</label><input type="date" {...vm('controlCardValidTo')} /></div>
                  <div className="full"><button className="btn" type="submit">Сохранить транспорт</button></div>
                </form>
              )}
              {tab === 'employees' && (
                <form className="grid" onSubmit={submitManual}>
                  <div><label>РМА / ИНН (9–10 цифр) *</label><input required pattern="\d{9,10}" {...em('rma')} /></div>
                  <div><label>Ф.И.О. *</label><input required placeholder="Петров Пётр Петрович" {...em('name')} /></div>
                  <div><label>Должность *</label>
                    <select required {...em('type')}>
                      {Object.entries(EMPLOYEE_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                    </select>
                  </div>
                  <div><label>Табельный номер</label><input {...em('tabNumber')} /></div>
                  <div><label>Телефон</label><input {...em('phone')} /></div>
                  <div className="full"><button className="btn" type="submit">Сохранить сотрудника</button></div>
                </form>
              )}
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
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.fullName)}</td><td><span className="number">{String(r.rma)}</span></td><td>{String(r.tabNumber ?? '—')}</td>
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
                  <td><span className="number">{String(r.registrationNumber)}</span></td><td>{TRANSPORT_TYPES[Number(r.transportType)] ?? String(r.transportType)}</td>
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
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td><span className="number">{String(r.rma)}</span></td><td>{EMPLOYEE_TYPES[Number(r.type)] ?? String(r.type)}</td>
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
