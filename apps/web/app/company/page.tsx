'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { authHeaders, md, type SubjectKind, type SubjectRef } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT, WAYBILL_TYPE_CODES } from '@/lib/i18n';
import { Icon, P } from '../icons';
import { ExpiryAlert } from '../ExpiryAlert';
import OrgDocuments from './OrgDocuments';

type Row = Record<string, unknown>;
type Tab = 'drivers' | 'vehicles' | 'employees';
type Counts = { vehicles: number; drivers: number; employees: number };

const EMPLOYEE_TYPES: Record<number, string> = { 1: 'Врач (духтур)', 2: 'Механик', 3: 'Диспетчер (танзимгар)', 4: 'Работник заправочного пункта', 5: 'Работник кассы' };
const TRANSPORT_TYPES: Record<number, string> = {
  1: 'Автобус', 2: 'Троллейбус', 3: 'Микроавтобус', 4: 'Легковой', 5: 'Грузовой', 6: 'Грузовой международный',
};
const SUBJECT_TYPES: Record<string, string> = { PHYSICAL: 'Физлицо', IP: 'ИП', LEGAL: 'Юрлицо' };
const PER_PAGE = 10;

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
// Поля-флажки ручных форм: в состоянии хранятся строками 'true'/'false', на сервер уходят boolean.
const BOOLEAN_KEYS = ['giveFuel'];

function clean(obj: Record<string, string>, numeric: string[]): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (BOOLEAN_KEYS.includes(k)) { out[k] = v === 'true'; continue; }
    out[k] = v === '' ? null : (numeric.includes(k) ? Number(v) : v);
  }
  return out;
}

// Наборы полей ручных форм — для предзаполнения при редактировании существующей записи.
const ORG_KEYS = ['rma', 'name', 'kpp', 'typeCompany', 'regionId', 'cityName', 'address', 'phone', 'email', 'nameHead', 'bank', 'licenseFrom', 'licenseTo', 'carrierLicenseNumber', 'percentIncome', 'cat1', 'cat2', 'cat3', 'allowedWaybillTypes', 'ownership', 'latitude', 'longitude', 'registrationCertNumber', 'extractNumber', 'vatCertNumber', 'planPassVolume', 'planPassTraffic',
  // Поля карточки старой платформы (V71): «Рамзи корхона», «Харита», «Сӯзишворӣ».
  'internalNumber', 'mapPoints', 'giveFuel'];
// birthDate/experienceYears/medRestrictions нет в видимой форме (их нет в боевой карточке), но
// держим их в ключах, чтобы при РЕДАКТИРОВАНИИ водителя они round-trip'ились, а не затирались в null.
const DRIVER_KEYS = ['rma', 'fullName', 'tabNumber', 'licenseNumber', 'licenseCategories', 'licenseValidTo', 'degree', 'medCertNumber', 'medCertValidTo', 'safetyCourseValidTo', 'safetyCourseNumber', 'phone', 'passport', 'address', 'email', 'powerAttorney', 'visaValidTo', 'contractNumber', 'contractValidTo', 'assignedVehicleId', 'birthDate', 'experienceYears', 'medRestrictions'];
const VEHICLE_KEYS = ['registrationNumber', 'transportType', 'brand', 'parkingNumber', 'capacity', 'carrying', 'odometer', 'vincode', 'yearManufacture', 'techInspectionValidTo', 'controlCardValidTo', 'controlCardNumber', 'intlCertificateNumber', 'insuranceValidTo', 'adrApprovalValidTo', 'techInspectionNumber', 'techPassportNumber', 'certificateNumber', 'airConditioner', 'intlControlCardNumber', 'intlControlCardValidTo', 'trailer1Number', 'trailer1Brand', 'trailer1Carrying', 'trailer1Weight', 'trailer2Number', 'trailer2Brand', 'trailer2Carrying', 'trailer2Weight'];
const EMPLOYEE_KEYS = ['rma', 'name', 'type', 'tabNumber', 'phone', 'address'];
const COMPANY_TYPES: Record<string, string> = { '1': 'Общего пользования', '2': 'Отраслевая' };

// Переводимые подписи справочников: карты выше — проверка допустимости кода и русский фолбэк,
// а сам текст берётся из словаря (subj.type.* / comp.type.* / veh.type.* / emp.type.*).
const subjType = (v: unknown, t: (k: string) => string) => { const k = String(v); return SUBJECT_TYPES[k] ? t('subj.type.' + k) : '—'; };
const compType = (v: unknown, t: (k: string) => string) => { const k = String(v); return COMPANY_TYPES[k] ? t('comp.type.' + k) : '—'; };
const vehTypeC = (v: unknown, t: (k: string) => string) => { const n = Number(v); return TRANSPORT_TYPES[n] ? t('veh.type.' + n) : (v == null || v === '' ? '—' : String(v)); };
const empTypeC = (v: unknown, t: (k: string) => string) => { const n = Number(v); return EMPLOYEE_TYPES[n] ? t('emp.type.' + n) : (v == null || v === '' ? '—' : String(v)); };

// Реквизиты организации для окна просмотра (только чтение) — метка/значение.
function orgViewRows(o: Row, t: (k: string) => string): [string, string][] {
  const sv = (v: unknown) => (v == null || v === '' ? '—' : String(v));
  return [
    [t('col.innrma'), sv(o.rma)],
    [t('flt.subjecttype'), subjType(o.subjectType, t)],
    [t('flt.orgkind'), compType(o.typeCompany, t)],
    [t('col.region'), sv(o.regionId)],
    [t('comp.f.city'), sv(o.cityName)],
    [t('col.address'), sv(o.address)],
    [t('col.phone'), sv(o.phone)],
    ['Email', sv(o.email)],
    [t('comp.f.head'), sv(o.nameHead)],
    [t('comp.f.bank'), sv(o.bank)],
    [t('comp.kv.carrierlic'), `${sv(o.licenseFrom)} → ${sv(o.licenseTo)}`],
    [t('dt.carrierlicnum'), sv(o.carrierLicenseNumber)],
    [t('org.f.internalnumber'), sv(o.internalNumber)],
    [t('org.f.mappoints'), sv(o.mapPoints)],
    [t('org.f.givefuel'), o.giveFuel ? t('st.yes') : '—'],
    [t('dt.source'), String(o.source) === 'UNIFIED' ? t('reg.src.unified') : t('dt.src.manual')],
  ];
}

/** Модальное окно: формы добавления/правки открываются поверх страницы, а не разворачиваются снизу. */
function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: React.ReactNode; wide?: boolean }) {
  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(15,32,60,.45)', zIndex: 65, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '5vh 16px', overflowY: 'auto' }}>
      <div onClick={e => e.stopPropagation()} className="card" style={{ maxWidth: wide ? 900 : 560, width: '100%', margin: 0 }}>
        <div className="card-h">
          <h2>{title}</h2>
          <button type="button" className="btn secondary" style={{ marginLeft: 'auto' }} onClick={onClose}>✕</button>
        </div>
        {children}
      </div>
    </div>
  );
}

/** Строка справочника → значения ручной формы (для кнопки «Изменить»). */
function rowToForm(row: Record<string, unknown>, keys: string[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const k of keys) {
    const v = row[k];
    out[k] = v == null ? '' : String(v);
  }
  return out;
}

/** value + onChange одного поля ручной формы (результат хелпера mkField). */
type Field = { value: string; onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => void };

/**
 * Мультивыбор типов ПЛ, которые организация вправе выдавать (аналог per-user permissions).
 * Ничего не отмечено = без ограничений. Хранится как CSV кодов (WB_BUS,WB_TAXI…) — тот формат,
 * что читает backend (WaybillService.assertTypeAllowed, split по [,;\s]+). Заменяет прежний
 * свободный ввод кодов через запятую на понятные переключатели с названиями типов.
 */
function WaybillTypesPicker({ field }: { field: Field }) {
  const { t, tType } = useT();
  const selected = new Set(field.value.split(/[,;\s]+/).map(s => s.trim()).filter(Boolean));
  const toggle = (code: string) => {
    const next = new Set(selected);
    if (next.has(code)) next.delete(code); else next.add(code);
    // Сохраняем в каноническом порядке WAYBILL_TYPE_CODES; пусто = без ограничений.
    const csv = WAYBILL_TYPE_CODES.filter(c => next.has(c)).join(',');
    field.onChange({ target: { value: csv } } as React.ChangeEvent<HTMLInputElement>);
  };
  return (
    <div className="full">
      <label>{t('cf.allowedtypes')}</label>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 4 }}>
        {WAYBILL_TYPE_CODES.map(code => {
          const on = selected.has(code);
          return (
            <button type="button" key={code} onClick={() => toggle(code)} aria-pressed={on}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer',
                padding: '6px 12px', borderRadius: 'var(--radius-sm)', fontSize: 13,
                fontWeight: on ? 600 : 500, fontFamily: 'inherit',
                border: `1px solid ${on ? 'var(--blue-600)' : 'var(--line)'}`,
                background: on ? 'var(--blue-050)' : 'var(--surface)',
                color: on ? 'var(--blue-700)' : 'var(--ink-soft)',
              }}>
              <span style={{ opacity: on ? 1 : 0.4, fontWeight: 700 }}>{on ? '✓' : '+'}</span>{tType(code)}
            </button>
          );
        })}
      </div>
      <div style={{ marginTop: 6, fontSize: 12, color: 'var(--muted)' }}>{t('cf.allowedtypes.hint')}</div>
    </div>
  );
}

/**
 * Поля организации — общий набор для формы «Добавить» и окна «Изменить».
 * Вынесены отдельно, чтобы правка и добавление не расходились в списке полей;
 * кнопку сохранения (текст и обработчик у них разные) каждая форма ставит сама.
 */
function OrgFields({ f, t }: { f: (k: string) => Field; t: (k: string) => string }) {
  // Справочник городов (V54) — подсказки для поля «Город», отфильтрованные по выбранному региону.
  const [cities, setCities] = useState<Row[]>([]);
  useEffect(() => { md.cities().then(setCities).catch(() => setCities([])); }, []);
  const region = String(f('regionId').value ?? '');
  const cityOpts = (region ? cities.filter(c => String(c.regionId) === region) : cities);
  return (
    <>
      <div><label>{t('comp.f.rmainn')}</label><input required pattern="\d{9,10}" placeholder="025680800" {...f('rma')} /></div>
      <div><label>{t('comp.f.name_req')}</label><input required placeholder="ООО «ТрансЛогистик»" {...f('name')} /></div>
      <div><label>{t('comp.f.kpp')}</label><input {...f('kpp')} /></div>
      {/* «Рамзи корхона» — внутренний код предприятия из карточки старой платформы (V71). */}
      <div><label>{t('org.f.internalnumber')}</label><input maxLength={20} {...f('internalNumber')} /></div>
      <div><label>{t('comp.f.typecompany')}</label><input type="number" placeholder="1" {...f('typeCompany')} /></div>
      <div><label>{t('f.region')}</label><input type="number" min={1} max={7} {...f('regionId')} /></div>
      <div><label>{t('comp.f.city')}</label>
        <input list="org-city-list" placeholder="Душанбе" {...f('cityName')} />
        <datalist id="org-city-list">{cityOpts.map(c => <option key={String(c.id)} value={String(c.name)} />)}</datalist>
      </div>
      <div><label>{t('col.address')}</label><input {...f('address')} /></div>
      <div><label>{t('col.phone')}</label><input {...f('phone')} /></div>
      <div><label>{t('comp.f.email')}</label><input type="email" {...f('email')} /></div>
      <div><label>{t('comp.f.head')}</label><input {...f('nameHead')} /></div>
      <div><label>{t('comp.f.bank')}</label><input {...f('bank')} /></div>
      <div><label>{t('comp.f.licfrom')}</label><input type="date" {...f('licenseFrom')} /></div>
      <div><label>{t('comp.f.licto')}</label><input type="date" {...f('licenseTo')} /></div>
      <div><label>{t('dt.carrierlicnum')}</label><input placeholder="ЛР-0001234" {...f('carrierLicenseNumber')} /></div>
      <div><label>{t('cf.incomeshare')}</label><input type="number" step="0.01" min={0} max={1} placeholder="0.5" {...f('percentIncome')} /></div>
      <div><label>{t('cf.cat1')}</label><input type="number" min={0} placeholder="200" {...f('cat1')} /></div>
      <div><label>{t('cf.cat2')}</label><input type="number" min={0} placeholder="120" {...f('cat2')} /></div>
      <div><label>{t('cf.cat3')}</label><input type="number" min={0} placeholder="0" {...f('cat3')} /></div>
      <div><label>{t('org.f.ownership')}</label>
        <select {...f('ownership')}>
          <option value="">—</option>
          <option value="1">{t('org.f.ownership.1')}</option>
          <option value="2">{t('org.f.ownership.2')}</option>
        </select>
      </div>
      <div><label>{t('org.f.regcert')}</label><input {...f('registrationCertNumber')} /></div>
      <div><label>{t('org.f.extract')}</label><input {...f('extractNumber')} /></div>
      <div><label>{t('org.f.vatcert')}</label><input {...f('vatCertNumber')} /></div>
      <div><label>{t('org.f.planvolume')}</label><input type="number" step="0.01" min={0} {...f('planPassVolume')} /></div>
      <div><label>{t('org.f.plantraffic')}</label><input type="number" step="0.01" min={0} {...f('planPassTraffic')} /></div>
      <div><label>{t('org.f.latitude')}</label><input type="number" step="0.0000001" placeholder="38.5598" {...f('latitude')} /></div>
      <div><label>{t('org.f.longitude')}</label><input type="number" step="0.0000001" placeholder="68.7870" {...f('longitude')} /></div>
      {/* «Харита» и «Сӯзишворӣ» — поля карточки старой платформы (V71). */}
      <div className="full"><label>{t('org.f.mappoints')}</label><input maxLength={500} {...f('mapPoints')} /></div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <input id="org-give-fuel" type="checkbox" checked={f('giveFuel').value === 'true'}
          onChange={e => f('giveFuel').onChange({ target: { value: e.target.checked ? 'true' : 'false' } } as React.ChangeEvent<HTMLInputElement>)} />
        <label htmlFor="org-give-fuel" style={{ margin: 0 }}>{t('org.f.givefuel')}</label>
      </div>
      <WaybillTypesPicker field={f('allowedWaybillTypes')} />
    </>
  );
}

/**
 * Раздел «Компания» — только для SYSTEM_ADMIN: реестр ВСЕХ организаций (регистрация, управление).
 * Кабинет перевозчика «Моя компания» убран — реквизиты/парк/персонал ведутся в /fleet, дублирующая
 * сводка не нужна. Для остальных ролей раздел вырезан из меню (visibleNav) и Shell уводит их в свой
 * кабинет; здесь на всякий случай ничего не рендерим (защита от прямого захода по URL).
 */
export default function CompanyPage() {
  const { roles } = useAuth();
  if (!roles.includes('SYSTEM_ADMIN')) return null;
  return <OrgRegistry />;
}

/**
 * Реестр организаций — только SYSTEM_ADMIN: список ВСЕХ организаций, регистрация по ИНН
 * (данные из единой платформы) или вручную, управление их водителями/ТС/сотрудниками.
 */
function OrgRegistry() {
  const { t } = useT();
  const [orgs, setOrgs] = useState<Row[]>([]);
  const [counts, setCounts] = useState<Record<string, Counts>>({});
  const [orgRma, setOrgRma] = useState('');
  const [orgSearch, setOrgSearch] = useState('');
  const [orgSubjectFilter, setOrgSubjectFilter] = useState('');
  const [orgTypeFilter, setOrgTypeFilter] = useState('');
  const [orgRegionFilter, setOrgRegionFilter] = useState('');
  const [orgCityFilter, setOrgCityFilter] = useState('');
  const [tab, setTab] = useState<Tab>('drivers');
  const [rows, setRows] = useState<Row[]>([]);
  // Пагинация: список организаций и список сущностей выбранной вкладки (водители/ТС/сотрудники).
  const [orgPage, setOrgPage] = useState(1);
  const [rowsPage, setRowsPage] = useState(1);
  const [showForm, setShowForm] = useState(false);
  // Форма «Добавить организацию» скрыта по умолчанию — открывается кнопкой у фильтров.
  const [showAddOrg, setShowAddOrg] = useState(false);
  // ТС выбранной организации — для списка «закреплённое ТС» в карточке водителя и колонки реестра.
  const [orgVehicles, setOrgVehicles] = useState<Row[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  // Добавление — только идентификаторы; данные приходят из единой платформы
  const [orgInn, setOrgInn] = useState('');
  // Прикрепление уже существующего субъекта к организации и открепление/удаление (владелец, 22.09).
  const [attachOpen, setAttachOpen] = useState(false);
  const [attachKey, setAttachKey] = useState('');
  const [attachFound, setAttachFound] = useState<SubjectRef | null>(null);
  const [attachError, setAttachError] = useState('');
  const [confirmRow, setConfirmRow] = useState<{ row: Row; action: 'detach' | 'delete' } | null>(null);
  const [busy, setBusy] = useState(false);
  const [driverForm, setDriverForm] = useState({ inn: '', tabNumber: '' });
  const [vehicleForm, setVehicleForm] = useState({ registrationNumber: '', parkingNumber: '' });
  const [employeeForm, setEmployeeForm] = useState({ inn: '', type: '1', tabNumber: '' });

  // Ручной ввод (полная форма) — все поля справочника. Режим переключается для организации и для сущностей.
  const [orgMode, setOrgMode] = useState<'sync' | 'manual'>('sync');
  const [entityMode, setEntityMode] = useState<'sync' | 'manual'>('sync');
  const [orgManual, setOrgManual] = useState<Record<string, string>>({});
  // Правка организации идёт в отдельном модальном окне (а не в форме «Добавить» снизу).
  // null — окно закрыто; объект — предзаполненные значения редактируемой организации.
  const [orgEdit, setOrgEdit] = useState<Record<string, string> | null>(null);
  // Просмотр организации (только чтение) — открывается по иконке глаза в таблице,
  // вместо прежней карточки «данные о компании» снизу. null — окно закрыто.
  const [orgView, setOrgView] = useState<Row | null>(null);
  const [driverManual, setDriverManual] = useState<Record<string, string>>({});
  const [vehicleManual, setVehicleManual] = useState<Record<string, string>>({ transportType: '1' });
  const [employeeManual, setEmployeeManual] = useState<Record<string, string>>({ type: '1' });

  const loadOrgs = useCallback(async () => {
    const list = await md.organizations();
    setOrgs(list);
    if (list.length > 0) setOrgRma(prev => prev || String(list[0].rma));
    // Счётчики транспорта/водителей/сотрудников — ОДНИМ агрегирующим запросом с бэкенда
    // (раньше был N+1: по 3 запроса на каждую организацию — на боевом объёме 400+ орг это
    // упирало в rate-limit 429 и тянуло тысячи строк только ради count).
    try {
      const stats = await md.organizationCounts();
      setCounts(Object.fromEntries(stats.map(s =>
        [String(s.rma), { vehicles: s.vehicles, drivers: s.drivers, employees: s.employees }])));
    } catch { setCounts({}); }
    return list;
  }, []);

  useEffect(() => { loadOrgs().catch(e => setError(e.message)); }, [loadOrgs]);

  const reload = useCallback(async () => {
    if (!orgRma) return;
    const fn = tab === 'drivers' ? md.drivers : tab === 'vehicles' ? md.vehicles : md.employees;
    setRows(await fn(orgRma));
  }, [orgRma, tab]);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);

  // Сброс страницы списка сущностей при смене организации/вкладки и списка организаций — при смене фильтров.
  useEffect(() => { setRowsPage(1); }, [orgRma, tab]);
  useEffect(() => { setOrgPage(1); }, [orgSearch, orgSubjectFilter, orgTypeFilter, orgRegionFilter, orgCityFilter]);

  // ТС выбранной организации — для выбора «закреплённое ТС» и колонки «Номер транспорта».
  useEffect(() => {
    if (!orgRma) { setOrgVehicles([]); return; }
    md.vehicles(orgRma).then(setOrgVehicles).catch(() => setOrgVehicles([]));
  }, [orgRma]);

  async function syncOrganization(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setOk('');
    try {
      const org = await postJson('/md-api/api/v1/sync/organization', { inn: orgInn });
      setOk(`«${org.name}» загружена из единой платформы (${subjType(org.subjectType, t)})`);
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
        setOk(`Сотрудник ${emp.name} добавлен (${empTypeC(emp.type, t)})`);
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
      const created = await md.createOrganization(clean(orgManual, ['typeCompany', 'regionId', 'percentIncome', 'cat1', 'cat2', 'cat3', 'ownership', 'latitude', 'longitude', 'planPassVolume', 'planPassTraffic']));
      setOk(`Организация «${String(created.name)}» сохранена (РМА ${String(created.rma)})`);
      setOrgManual({});
      setShowAddOrg(false); // после сохранения форму сворачиваем обратно
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
        const d = await md.createDriver({ ...clean(driverManual, ['degree', 'experienceYears']), organizationRma: orgRma });
        setOk(`Водитель ${String(d.fullName)} сохранён`);
        setDriverManual({});
      } else if (tab === 'vehicles') {
        const v = await md.createVehicle({ ...clean(vehicleManual, ['transportType', 'capacity', 'carrying', 'odometer', 'yearManufacture', 'airConditioner', 'trailer1Carrying', 'trailer1Weight', 'trailer2Carrying', 'trailer2Weight']), organizationRma: orgRma });
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
  // Поля окна правки организации привязаны к своему состоянию orgEdit,
  // чтобы не смешиваться с формой «Добавить организацию» внизу.
  const oe = mkField(orgEdit ?? {}, setOrgEdit);

  // Редактирование организации: открываем отдельное окно, предзаполнив его значениями строки.
  // Сохранение — тот же upsert по РМА (обновляет существующую запись), см. saveOrgEdit.
  function editOrg(row: Row) {
    setError(''); setOk('');
    setOrgEdit(rowToForm(row, ORG_KEYS));
  }

  // Сохранение правки организации из модального окна.
  async function saveOrgEdit(e: React.FormEvent) {
    e.preventDefault();
    if (!orgEdit) return;
    setError(''); setOk('');
    try {
      const saved = await md.createOrganization(clean(orgEdit, ['typeCompany', 'regionId', 'percentIncome', 'cat1', 'cat2', 'cat3', 'ownership', 'latitude', 'longitude', 'planPassVolume', 'planPassTraffic']));
      setOk(`Организация «${String(saved.name)}» обновлена (РМА ${String(saved.rma)})`);
      setOrgEdit(null);
      await loadOrgs();
      setOrgRma(String(saved.rma));
    } catch (err) {
      setError((err as Error).message);
    }
  }
  // --- Прикрепление существующего субъекта, открепление и удаление (владелец, 22.09) ---

  const subjectKind: SubjectKind = tab;

  async function lookupSubject() {
    setAttachError(''); setAttachFound(null);
    try {
      setAttachFound(await md.subjects.lookup(subjectKind, attachKey.trim()));
    } catch (err) {
      setAttachError((err as Error).message);
    }
  }

  async function attachSubject() {
    if (!attachFound || !orgRma) return;
    setBusy(true); setAttachError('');
    try {
      const res = await md.subjects.attach(subjectKind, attachFound.id, orgRma);
      setOk(`${res.key} — ${t('comp.attach.done')} «${res.organizationName ?? orgRma}»`);
      setAttachOpen(false); setAttachKey(''); setAttachFound(null);
      await loadOrgs().catch(() => {});
      await reload();
    } catch (err) {
      setAttachError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function applyRowAction() {
    if (!confirmRow) return;
    const { row, action } = confirmRow;
    setBusy(true); setError(''); setOk('');
    try {
      if (action === 'detach') {
        await md.subjects.detach(subjectKind, String(row.id));
        setOk(t('comp.detach.done'));
      } else {
        const del = tab === 'drivers' ? md.deleteDriver : tab === 'vehicles' ? md.deleteVehicle : md.deleteEmployee;
        await del(String(row.id));
        setOk(t('comp.removed'));
      }
      setConfirmRow(null);
      await loadOrgs().catch(() => {});
      await reload();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  /** Кнопки строки субъекта: изменить · открепить · удалить. */
  const rowActions = (r: Row) => (
    <td style={{ whiteSpace: 'nowrap' }}>
      <button type="button" className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => editEntity(r)}>{t('btn.edit')}</button>{' '}
      <button type="button" className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} title={t('comp.detach.hint')} onClick={() => setConfirmRow({ row: r, action: 'detach' })}>{t('comp.detach')}</button>{' '}
      <button type="button" className="btn secondary" style={{ padding: '4px 9px', fontSize: 12, color: 'var(--red)' }} title={t('btn.delete')} onClick={() => setConfirmRow({ row: r, action: 'delete' })}>
        <Icon d={P.trash} cls="" style={{ width: 14, height: 14 }} />
      </button>
    </td>
  );

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
      employees: list.reduce((a, c) => a + (c.employees ?? 0), 0),
      active: orgs.filter(orgActive).length,
    };
  }, [counts, orgs]);

  const KPIS = [
    { label: t('comp.kpi.orgs'), value: orgs.length, icon: P.building, cls: 'ic-blue' },
    { label: t('comp.kpi.transport'), value: totals.vehicles, icon: P.car, cls: 'ic-cyan' },
    { label: t('comp.kpi.drivers'), value: totals.drivers, icon: P.users, cls: 'ic-purple' },
    { label: t('comp.kpi.employees'), value: totals.employees, icon: P.user, cls: 'ic-amber' },
    { label: t('comp.kpi.active'), value: totals.active, icon: P.check, cls: 'ic-green' },
  ];

  // Список городов для фильтра — каскад регион→город: при выбранном регионе показываем
  // только города организаций этого региона, при пустом — все. Названия берём из самих
  // организаций (фильтр сверяет o.cityName), поэтому все опции гарантированно дают результат.
  const orgCities = Array.from(new Set(
    orgs.filter(o => !orgRegionFilter || String(o.regionId ?? '') === orgRegionFilter)
      .map(o => String(o.cityName ?? '')).filter(Boolean),
  )).sort();

  const filteredOrgs = orgs.filter(o => {
    const s = orgSearch.trim().toLowerCase();
    if (orgSubjectFilter && String(o.subjectType) !== orgSubjectFilter) return false;
    if (orgTypeFilter && String(o.typeCompany) !== orgTypeFilter) return false;
    if (orgRegionFilter && String(o.regionId ?? '') !== orgRegionFilter) return false;
    if (orgCityFilter && String(o.cityName ?? '') !== orgCityFilter) return false;
    if (!s) return true;
    return String(o.name ?? '').toLowerCase().includes(s) || String(o.rma ?? '').includes(s);
  });

  const orgPages = Math.max(1, Math.ceil(filteredOrgs.length / PER_PAGE));
  const orgsView = filteredOrgs.slice((orgPage - 1) * PER_PAGE, orgPage * PER_PAGE);
  const rowsPages = Math.max(1, Math.ceil(rows.length / PER_PAGE));
  const rowsView = rows.slice((rowsPage - 1) * PER_PAGE, rowsPage * PER_PAGE);

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

      <div style={{ marginBottom: 16 }}><ExpiryAlert days={30} compact /></div>

      {/* KPI */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(5, 1fr)' }}>
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
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <select value={orgSubjectFilter} onChange={e => setOrgSubjectFilter(e.target.value)} style={{ width: 150 }}>
              <option value="">{t('flt.subjecttype')}</option>
              {Object.entries(SUBJECT_TYPES).map(([v]) => <option key={v} value={v}>{t('subj.type.' + v)}</option>)}
            </select>
            <select value={orgTypeFilter} onChange={e => setOrgTypeFilter(e.target.value)} style={{ width: 180 }}>
              <option value="">{t('flt.orgkind')}</option>
              {Object.entries(COMPANY_TYPES).map(([v]) => <option key={v} value={v}>{t('comp.type.' + v)}</option>)}
            </select>
            <select value={orgRegionFilter} onChange={e => {
              const v = e.target.value;
              setOrgRegionFilter(v);
              // Каскад: при смене региона сбрасываем город, если он не относится к новому региону.
              if (orgCityFilter && v && !orgs.some(o => String(o.regionId ?? '') === v && String(o.cityName ?? '') === orgCityFilter)) setOrgCityFilter('');
            }} style={{ width: 160 }}>
              <option value="">{t('flt.allregions')}</option>
              {[1, 2, 3, 4, 5, 6, 7].map(n => <option key={n} value={String(n)}>{t('region.' + n)}</option>)}
            </select>
            <select value={orgCityFilter} onChange={e => setOrgCityFilter(e.target.value)} style={{ width: 150 }}>
              <option value="">{t('flt.allcities')}</option>
              {orgCities.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
            <input
              value={orgSearch}
              onChange={e => setOrgSearch(e.target.value)}
              placeholder={t('comp.search.org')}
              style={{ width: 240 }}
            />
            <button type="button" className="btn" onClick={() => setShowAddOrg(v => !v)}>
              {showAddOrg ? t('comp.btn.hideform') : t('btn.add')}
            </button>
          </div>
        </div>
        <table>
          <thead>
            <tr><th>{t('col.name')}</th><th>{t('col.innrma')}</th><th>{t('col.type')}</th><th>{t('col.transport')}</th><th>{t('col.drivers')}</th><th>{t('col.employees')}</th><th>{t('col.status')}</th><th>{t('col.actions')}</th></tr>
          </thead>
          <tbody>
            {orgsView.map(o => {
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
                  <td>{subjType(o.subjectType, t)}</td>
                  <td>{c ? c.vehicles : '—'}</td>
                  <td>{c ? c.drivers : '—'}</td>
                  <td>{c ? (c.employees ?? '—') : '—'}</td>
                  <td><span className={`badge ${active ? 'green' : 'red'}`}>{active ? t('comp.badge.active') : t('comp.badge.licexpired')}</span></td>
                  <td onClick={e => e.stopPropagation()} style={{ whiteSpace: 'nowrap' }}>
                    <button type="button" className="btn secondary" style={{ padding: '4px 9px', fontSize: 12 }} title={t('btn.view')} onClick={() => setOrgView(o)}>
                      <Icon d={P.eye} cls="" />
                    </button>{' '}
                    <button type="button" className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => editOrg(o)}>{t('btn.edit')}</button>
                  </td>
                </tr>
              );
            })}
            {filteredOrgs.length === 0 && (
              <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 24 }}>
                {orgs.length === 0 ? t('comp.empty.orgs') : t('common.notfound')}
              </td></tr>
            )}
          </tbody>
        </table>
        {/* Пагинация */}
        <div style={{ display: 'flex', alignItems: 'center', marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>
          <span>{t('comp.totalorgs')}: <b style={{ color: 'var(--ink)' }}>{filteredOrgs.length}</b></span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={orgPage <= 1} onClick={() => setOrgPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{orgPage} / {orgPages}</span>
          <button className="btn secondary" disabled={orgPage >= orgPages} onClick={() => setOrgPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>
      </div>

      {/* Добавление организации — отдельным окном поверх страницы (замечание владельца 22.09:
          раньше форма разворачивалась прямо под списком и терялась среди таблиц).
          Ввод ручной; синк-логика syncOrganization сохранена для будущей интеграции. */}
      {showAddOrg && (
        <Modal wide title={t('comp.addorg')} onClose={() => setShowAddOrg(false)}>
          <p className="hint">{t('comp.hint.manualorg')}</p>
          <form className="grid" onSubmit={createOrgManual}>
            <OrgFields f={om} t={t} />
            <div className="full" style={{ display: 'flex', gap: 8 }}>
              <button className="btn" type="submit">{t('comp.btn.saveorg')}</button>
              <button className="btn secondary" type="button" onClick={() => setShowAddOrg(false)}>{t('btn.cancel')}</button>
            </div>
          </form>
        </Modal>
      )}

      {/* Профиль выбранной организации показывается по иконке глаза (окно просмотра), а не карточкой снизу.
          Документы организации — в окне «Изменить». */}

      {/* Разделы выбранной организации */}
      <div className="toolbar">
        <button className={`btn ${tab === 'drivers' ? '' : 'secondary'}`} onClick={() => { setTab('drivers'); setShowForm(false); }}>{t('col.drivers')}</button>
        <button className={`btn ${tab === 'vehicles' ? '' : 'secondary'}`} onClick={() => { setTab('vehicles'); setShowForm(false); }}>{t('col.transport')}</button>
        <button className={`btn ${tab === 'employees' ? '' : 'secondary'}`} onClick={() => { setTab('employees'); setShowForm(false); }}>{t('col.employees')}</button>
        <span className="spacer" />
        {org && <span style={{ color: 'var(--muted)', fontSize: 12.5, marginRight: 4 }}>{String(org.name)}</span>}
        {/* Уже существующий в базе субъект не регистрируется заново — его прикрепляют по ИНН/госномеру. */}
        <button className="btn secondary" disabled={!orgRma} onClick={() => { setAttachOpen(true); setAttachKey(''); setAttachFound(null); setAttachError(''); }}>
          {t('comp.attach.btn')}
        </button>
        <button className="btn" disabled={!orgRma} onClick={() => { setShowForm(true); setEntityMode('manual'); }}>{t('btn.add')}</button>
      </div>

      {showForm && (
        <Modal
          wide
          title={tab === 'drivers' ? t('comp.add.driver') : tab === 'vehicles' ? t('comp.add.vehicle') : t('comp.add.employee')}
          onClose={() => setShowForm(false)}
        >
          {(
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
                  <div><label>{t('cf.safetynum20')}</label><input {...dm('safetyCourseNumber')} /></div>
                  <div><label>{t('col.phone')}</label><input {...dm('phone')} /></div>
                  <div><label>{t('drv.f.passport')}</label><input {...dm('passport')} /></div>
                  <div><label>{t('col.address')}</label><input {...dm('address')} /></div>
                  <div><label>{t('drv.f.email')}</label><input type="email" {...dm('email')} /></div>
                  <div><label>{t('drv.f.powerattorney')}</label><input {...dm('powerAttorney')} /></div>
                  <div><label>{t('drv.f.visato')}</label><input type="date" {...dm('visaValidTo')} /></div>
                  <div><label>{t('drv.f.contractnum')}</label><input {...dm('contractNumber')} /></div>
                  <div><label>{t('drv.f.contractto')}</label><input type="date" {...dm('contractValidTo')} /></div>
                  <div><label>{t('drv.f.assignedveh')}</label>
                    <select {...dm('assignedVehicleId')}>
                      <option value="">{t('reg.opt.novehicle')}</option>
                      {orgVehicles.map(v => <option key={String(v.id)} value={String(v.id)}>{String(v.registrationNumber)}{v.brand ? ` · ${String(v.brand)}` : ''}</option>)}
                    </select>
                  </div>
                  <div className="full" style={{ display: 'flex', gap: 8 }}>
                    <button className="btn" type="submit">{t('comp.btn.savedriver')}</button>
                    <button className="btn secondary" type="button" onClick={() => setShowForm(false)}>{t('btn.cancel')}</button>
                  </div>
                </form>
              )}
              {tab === 'vehicles' && (
                <form className="grid" onSubmit={submitManual}>
                  {/* Легковые (тип 4): строгий формат legacy 234AB01 / 1234AB01 (MIGRATION.md 12.2); прочие — буквы/цифры. */}
                  <div><label>{t('comp.f.regnum_req')}</label>
                    <input required pattern={vehicleManual.transportType === '4' ? '\\d{3,4}[A-Za-z]{2}\\d{2}' : '[A-Za-zА-Яа-я0-9]{4,20}'}
                      placeholder={vehicleManual.transportType === '4' ? '1234AB01' : '0101TJ01'}
                      title={vehicleManual.transportType === '4' ? t('comp.f.regnum_car_hint') : ''} {...vm('registrationNumber')} />
                    {vehicleManual.transportType === '4' && <div style={{ fontSize: 11, color: 'var(--muted)', marginTop: 2 }}>{t('comp.f.regnum_car_hint')}</div>}
                  </div>
                  <div><label>{t('comp.f.vehtype_req')}</label>
                    <select required {...vm('transportType')}>
                      {Object.entries(TRANSPORT_TYPES).map(([v]) => <option key={v} value={v}>{t('veh.type.' + v)}</option>)}
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
                  <div><label>{t('cf.controlcardnum')}</label><input {...vm('controlCardNumber')} /></div>
                  <div><label>{t('cf.intlcertnum')}</label><input {...vm('intlCertificateNumber')} /></div>
                  <div><label>{t('cf.insosago')}</label><input type="date" {...vm('insuranceValidTo')} /></div>
                  <div><label>{t('dt.adrto')}</label><input type="date" {...vm('adrApprovalValidTo')} /></div>
                  <div><label>{t('veh.f.techinspnum')}</label><input {...vm('techInspectionNumber')} /></div>
                  <div><label>{t('veh.f.techpassnum')}</label><input {...vm('techPassportNumber')} /></div>
                  <div><label>{t('veh.f.certnum')}</label><input {...vm('certificateNumber')} /></div>
                  <div><label>{t('veh.f.aircond')}</label><input type="number" min={0} max={100} {...vm('airConditioner')} /></div>
                  <div><label>{t('veh.f.intlcardnum')}</label><input {...vm('intlControlCardNumber')} /></div>
                  <div><label>{t('veh.f.intlcardto')}</label><input type="date" {...vm('intlControlCardValidTo')} /></div>
                  <div><label>{t('veh.f.tr1num')}</label><input placeholder="0101TJ01" {...vm('trailer1Number')} /></div>
                  <div><label>{t('veh.f.tr1brand')}</label><input {...vm('trailer1Brand')} /></div>
                  <div><label>{t('veh.f.tr1carrying')}</label><input type="number" step="0.01" {...vm('trailer1Carrying')} /></div>
                  <div><label>{t('veh.f.tr1weight')}</label><input type="number" step="0.01" {...vm('trailer1Weight')} /></div>
                  <div><label>{t('veh.f.tr2num')}</label><input placeholder="0101TJ01" {...vm('trailer2Number')} /></div>
                  <div><label>{t('veh.f.tr2brand')}</label><input {...vm('trailer2Brand')} /></div>
                  <div><label>{t('veh.f.tr2carrying')}</label><input type="number" step="0.01" {...vm('trailer2Carrying')} /></div>
                  <div><label>{t('veh.f.tr2weight')}</label><input type="number" step="0.01" {...vm('trailer2Weight')} /></div>
                  <div className="full" style={{ display: 'flex', gap: 8 }}>
                    <button className="btn" type="submit">{t('comp.btn.savevehicle')}</button>
                    <button className="btn secondary" type="button" onClick={() => setShowForm(false)}>{t('btn.cancel')}</button>
                  </div>
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
                  <div className="full"><label>{t('col.address')}</label><input placeholder="г. Душанбе, ул. …" {...em('address')} /></div>
                  <div className="full" style={{ display: 'flex', gap: 8 }}>
                    <button className="btn" type="submit">{t('comp.btn.saveemployee')}</button>
                    <button className="btn secondary" type="button" onClick={() => setShowForm(false)}>{t('btn.cancel')}</button>
                  </div>
                </form>
              )}
            </>
          )}
        </Modal>
      )}

      <div className="card">
        {tab === 'drivers' && (
          <table>
            <thead><tr><th>{t('col.fio')}</th><th>{t('col.innrma')}</th><th>{t('col.tab')}</th><th>{t('tech.license')}</th><th>{t('tech.categories')}</th><th>{t('col.licto')}</th><th>{t('col.medto')}</th><th>{t('col.assignedveh')}</th><th>{t('col.actions')}</th></tr></thead>
            <tbody>
              {rowsView.map(r => (
                <tr key={String(r.id)}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.fullName)}</td><td><span className="number">{String(r.rma)}</span></td><td>{String(r.tabNumber ?? '—')}</td>
                  <td>{String(r.licenseNumber ?? '—')}</td><td>{String(r.licenseCategories ?? '—')}</td>
                  <td>{String(r.licenseValidTo ?? '—')}</td><td>{String(r.medCertValidTo ?? '—')}</td>
                  <td><span className="number">{orgVehicles.find(v => String(v.id) === String(r.assignedVehicleId))?.registrationNumber as string ?? '—'}</span></td>
                  {rowActions(r)}
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={9} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('comp.empty.drivers')}</td></tr>}
            </tbody>
          </table>
        )}
        {tab === 'vehicles' && (
          <table>
            <thead><tr><th>{t('col.regnum')}</th><th>{t('col.type')}</th><th>{t('col.brand')}</th><th>{t('col.parking')}</th><th>{t('col.odometer')}</th><th>{t('col.techto')}</th><th>{t('col.cardto')}</th><th>{t('col.actions')}</th></tr></thead>
            <tbody>
              {rowsView.map(r => (
                <tr key={String(r.id)}>
                  <td><span className="number">{String(r.registrationNumber)}</span></td><td>{vehTypeC(r.transportType, t)}</td>
                  <td>{String(r.brand ?? '—')}</td><td>{String(r.parkingNumber ?? '—')}</td><td>{String(r.odometer)}</td>
                  <td>{String(r.techInspectionValidTo ?? '—')}</td><td>{String(r.controlCardValidTo ?? '—')}</td>
                  {rowActions(r)}
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
              {rowsView.map(r => (
                <tr key={String(r.id)}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td><span className="number">{String(r.rma)}</span></td><td>{empTypeC(r.type, t)}</td>
                  <td>{String(r.tabNumber ?? '—')}</td><td>{String(r.phone ?? '—')}</td>
                  {rowActions(r)}
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('comp.empty.employees')}</td></tr>}
            </tbody>
          </table>
        )}
        {/* Пагинация */}
        <div style={{ display: 'flex', alignItems: 'center', marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>
          <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{rows.length}</b></span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={rowsPage <= 1} onClick={() => setRowsPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{rowsPage} / {rowsPages}</span>
          <button className="btn secondary" disabled={rowsPage >= rowsPages} onClick={() => setRowsPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>
      </div>

      {/* Прикрепление уже существующего субъекта: поиск по ИНН (водитель/сотрудник) или
          госномеру (ТС) и закрепление за выбранной организацией. */}
      {attachOpen && (
        <Modal title={t('comp.attach.title')} onClose={() => setAttachOpen(false)}>
          <p className="hint" style={{ marginBottom: 12 }}>{t('comp.attach.hint')}</p>
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', marginBottom: 12 }}>
            <div style={{ flex: 1 }}>
              <label>{tab === 'vehicles' ? t('col.regnum') : t('col.innrma')}</label>
              <input value={attachKey} onChange={e => setAttachKey(e.target.value)}
                placeholder={tab === 'vehicles' ? '0101TJ01' : '461930031'} style={{ width: '100%' }} />
            </div>
            <button type="button" className="btn secondary" onClick={lookupSubject} disabled={!attachKey.trim()}>{t('comp.attach.find')}</button>
          </div>
          {attachError && <div className="error">{attachError}</div>}
          {attachFound && (
            <>
              <dl className="kv">
                <dt>{tab === 'vehicles' ? t('col.regnum') : t('col.innrma')}</dt><dd><span className="number">{attachFound.key}</span></dd>
                <dt>{tab === 'vehicles' ? t('col.brand') : t('col.fio')}</dt><dd>{attachFound.name ?? '—'}</dd>
                <dt>{t('col.org')}</dt><dd>{attachFound.attached ? `${attachFound.organizationName ?? ''} (${attachFound.organizationRma ?? ''})` : t('comp.attach.free')}</dd>
              </dl>
              {attachFound.attached && attachFound.organizationRma !== orgRma && (
                <div className="hint" style={{ marginTop: 10 }}>{t('comp.attach.busy')}</div>
              )}
              <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
                <button type="button" className="btn" disabled={busy || attachFound.organizationRma === orgRma} onClick={attachSubject}>
                  {busy ? '…' : `${t('comp.attach.do')} «${org ? String(org.name) : orgRma}»`}
                </button>
                <button type="button" className="btn secondary" onClick={() => setAttachOpen(false)}>{t('btn.cancel')}</button>
              </div>
            </>
          )}
        </Modal>
      )}

      {/* Подтверждение открепления или удаления субъекта. */}
      {confirmRow && (
        <Modal title={confirmRow.action === 'detach' ? t('comp.detach.title') : t('comp.delete.title')} onClose={() => setConfirmRow(null)}>
          <p style={{ marginBottom: 12 }}>
            {confirmRow.action === 'detach' ? t('comp.detach.q') : t('comp.delete.q')}{' '}
            <b>{String(confirmRow.row.fullName ?? confirmRow.row.name ?? confirmRow.row.registrationNumber ?? confirmRow.row.rma)}</b>?
          </p>
          <div className="hint" style={{ marginBottom: 14 }}>
            {confirmRow.action === 'detach' ? t('comp.detach.note') : t('comp.delete.note')}
          </div>
          <div style={{ display: 'flex', gap: 10 }}>
            <button className="btn" style={confirmRow.action === 'delete' ? { background: 'var(--red)' } : undefined} disabled={busy} onClick={applyRowAction}>
              {busy ? '…' : confirmRow.action === 'detach' ? t('comp.detach') : t('btn.delete')}
            </button>
            <button className="btn secondary" onClick={() => setConfirmRow(null)}>{t('btn.cancel')}</button>
          </div>
        </Modal>
      )}

      {/* Окно правки организации: открывается по кнопке «Изменить» в таблице,
          поверх страницы, а не разворачивается формой снизу. */}
      {orgEdit && (
        <div
          onClick={() => setOrgEdit(null)}
          style={{ position: 'fixed', inset: 0, background: 'rgba(15,32,60,.45)', zIndex: 60, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '6vh 16px', overflowY: 'auto' }}
        >
          <div onClick={e => e.stopPropagation()} className="card" style={{ maxWidth: 760, width: '100%', margin: 0 }}>
            <div className="card-h">
              <h2>{t('btn.edit')}: {orgEdit.name || String(orgEdit.rma)}</h2>
              <button type="button" className="btn secondary" style={{ marginLeft: 'auto' }} onClick={() => setOrgEdit(null)}>✕</button>
            </div>
            <form className="grid" onSubmit={saveOrgEdit}>
              <OrgFields f={oe} t={t} />
              <div className="full" style={{ display: 'flex', gap: 8 }}>
                <button className="btn" type="submit">{t('comp.btn.saveorg')}</button>
                <button type="button" className="btn secondary" onClick={() => setOrgEdit(null)}>{t('btn.cancel')}</button>
              </div>
            </form>
            {/* Документы организации — здесь же, в карточке организации: у существующей записи РМА
                уже известен, поэтому загрузка/список/проверка документов доступны прямо в окне правки. */}
            {orgEdit.rma && (
              <div style={{ marginTop: 8, borderTop: '1px solid var(--line)', paddingTop: 4 }}>
                <OrgDocuments rma={orgEdit.rma} />
              </div>
            )}
          </div>
        </div>
      )}

      {/* Окно просмотра организации (только чтение) — по иконке глаза в таблице. */}
      {orgView && (
        <div
          onClick={() => setOrgView(null)}
          style={{ position: 'fixed', inset: 0, background: 'rgba(15,32,60,.45)', zIndex: 60, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '6vh 16px', overflowY: 'auto' }}
        >
          <div onClick={e => e.stopPropagation()} className="card" style={{ maxWidth: 600, width: '100%', margin: 0 }}>
            <div className="card-h">
              <h2 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {String(orgView.name ?? '—')}
                <span className={`badge ${orgActive(orgView) ? 'green' : 'red'}`}>
                  {orgActive(orgView) ? t('comp.badge.active') : t('comp.badge.licexpired')}
                </span>
              </h2>
              <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
                <button type="button" className="btn secondary" onClick={() => { const o = orgView; setOrgView(null); editOrg(o); }}>{t('btn.edit')}</button>
                <button type="button" className="btn secondary" onClick={() => setOrgView(null)}>✕</button>
              </div>
            </div>
            <table>
              <tbody>
                {orgViewRows(orgView, t).map(([k, v]) => (
                  <tr key={k}><th style={{ width: '42%', textAlign: 'left' }}>{k}</th><td>{v}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}

// Компонент CompanyProfile («Моя компания» для COMPANY_ADMIN) удалён — кабинет перевозчика
// на /company убран по продуктовому решению; реквизиты/парк/персонал ведутся в /fleet.
