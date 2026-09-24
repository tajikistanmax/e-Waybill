'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { authHeaders, md, type RouteType } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { canEditNationalDictionaries } from '@/lib/roles';
import { downloadCsv } from '@/lib/csv';
import { Icon, P } from '../icons';

type Row = Record<string, unknown>;
export type DictTab = 'routes' | 'clients' | 'fuel-norms' | 'coefficients' | 'tariffs' | 'cargos'
  | 'brands' | 'winter-coefs' | 'mountain-coefs' | 'city-coefs' | 'used-coefs' | 'drive-classes'
  | 'directions' | 'route-tariffs' | 'cities';

const LABEL_KEY: Record<DictTab, string> = {
  routes: 'dict.sec.routes', clients: 'dict.sec.clients', 'fuel-norms': 'dict.sec.fuelnorms',
  coefficients: 'dict.sec.coefficients', tariffs: 'dict.sec.tariffs', cargos: 'dict.sec.cargos',
  brands: 'dict.sec.brands', 'winter-coefs': 'dict.sec.wintercoefs', 'mountain-coefs': 'dict.sec.mountaincoefs',
  'city-coefs': 'dict.sec.citycoefs', 'used-coefs': 'dict.sec.usedcoefs', 'drive-classes': 'dict.sec.driveclasses',
  directions: 'dict.sec.directions', 'route-tariffs': 'dict.sec.routetariffs',
  cities: 'dict.sec.cities',
};

const TT: Record<number, string> = { 1: 'Автобус', 2: 'Троллейбус', 3: 'Микроавтобус', 4: 'Легковой', 5: 'Грузовой', 6: 'Грузовой международный' };
const KIND: Record<string, string> = { WINTER: 'Зимний', CITY: 'Внутригородской', HIGHLAND: 'Высокогорный', USAGE: 'Эксплуатационный' };

// Справочники расчётного ядра (LegacyReferenceController) живут под /api/v1/legacy-ref/*,
// а не /api/v1/dictionaries/* — остальное (клиенты/маршруты/нормы/коэфф./тарифы/грузы) там же, где были.
const LEGACY_REF_TABS: DictTab[] = ['brands', 'winter-coefs', 'mountain-coefs', 'city-coefs',
  'used-coefs', 'drive-classes', 'directions', 'route-tariffs'];

// Справочники, привязанные к организации: для системного администратора нужен выбор владельца записи
// (бэкенд требует organizationRma; тенанту организация берётся из токена).
const ORG_TABS: DictTab[] = ['routes', 'clients'];

const PER_PAGE = 20;

function apiBase(tab: DictTab): string {
  // Города живут своим контроллером (/api/v1/cities), остальные — под dictionaries/legacy-ref.
  if (tab === 'cities') return '';
  return LEGACY_REF_TABS.includes(tab) ? 'legacy-ref' : 'dictionaries';
}

/** Путь справочника: у городов — без промежуточного сегмента. */
function apiPath(tab: DictTab): string {
  const base = apiBase(tab);
  return base ? `${base}/${tab}` : tab;
}

async function api<T>(tab: DictTab, body?: unknown): Promise<T> {
  const r = await fetch(`/md-api/api/v1/${apiPath(tab)}`, body ? {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  } : { headers: authHeaders() });
  if (!r.ok) {
    const p = await r.json().catch(() => null);
    throw new Error(p?.detail ?? p?.title ?? `Ошибка ${r.status}`);
  }
  return r.json();
}

/** Удаление записи справочника (DELETE /{base}/{tab}/{id}). */
async function apiDelete(tab: DictTab, id: string): Promise<void> {
  const r = await fetch(`/md-api/api/v1/${apiPath(tab)}/${encodeURIComponent(id)}`, {
    method: 'DELETE', headers: authHeaders(),
  });
  if (!r.ok && r.status !== 204) {
    const p = await r.json().catch(() => null);
    throw new Error(p?.detail ?? p?.title ?? `Ошибка ${r.status}`);
  }
}

/** Нац. справочники (правит только SYSTEM_ADMIN): нормы/коэфф./тарифы + весь расчётный блок. */
const NATIONAL_TABS: DictTab[] = ['fuel-norms', 'coefficients', 'tariffs', 'cities', ...LEGACY_REF_TABS];

const s = (v: unknown) => (v == null || v === '' ? '—' : String(v));

/** Модальное окно: форма добавления/правки или карточка просмотра. */
function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: React.ReactNode; wide?: boolean }) {
  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(15,32,60,.45)', zIndex: 70, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '5vh 16px', overflowY: 'auto' }}>
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

export default function DictionariesView({ tab }: { tab: DictTab }) {
  const { t, lang } = useT();
  const { roles } = useAuth();
  const canEdit = !NATIONAL_TABS.includes(tab) || canEditNationalDictionaries(roles);
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [rows, setRows] = useState<Row[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [form, setForm] = useState<Record<string, string>>({});
  const [editing, setEditing] = useState<Row | null>(null);   // правка существующей записи
  const [showForm, setShowForm] = useState(false);            // окно формы открыто
  const [detail, setDetail] = useState<Row | null>(null);     // окно просмотра
  const [confirmDel, setConfirmDel] = useState<Row | null>(null);
  const [busy, setBusy] = useState(false);
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);

  // Справочник типов маршрутов (V63) — для выбора «Тип маршрута» в форме и подписи в таблице.
  const [routeTypes, setRouteTypes] = useState<RouteType[]>([]);
  const rtName = (rt: RouteType) => (lang === 'tj' && rt.nameTj) ? rt.nameTj : rt.nameRu;
  const rtByCode = (code: unknown) => routeTypes.find(rt => String(rt.code) === String(code));
  const [cities, setCities] = useState<Row[]>([]);
  const [orgs, setOrgs] = useState<Row[]>([]);
  // Списки для выбора вместо ввода числовых идентификаторов (замечание владельца 22.09).
  const [winterList, setWinterList] = useState<Row[]>([]);
  const [mountainList, setMountainList] = useState<Row[]>([]);
  const [cityCoefList, setCityCoefList] = useState<Row[]>([]);
  const [routeList, setRouteList] = useState<Row[]>([]);

  const reload = useCallback(async () => {
    setRows(await api<Row[]>(tab));
  }, [tab]);

  useEffect(() => {
    setForm({}); setOk(''); setError(''); setEditing(null); setShowForm(false); setDetail(null); setQ(''); setPage(1);
    reload().catch(e => setError(e.message));
  }, [reload, tab]);

  useEffect(() => {
    if (tab === 'routes') {
      md.routeTypes().then(setRouteTypes).catch(() => setRouteTypes([]));
      md.cities().then(setCities).catch(() => setCities([]));
    }
    if (ORG_TABS.includes(tab)) md.organizations().then(setOrgs).catch(() => setOrgs([]));
    if (tab === 'routes' || tab === 'directions') {
      fetch('/md-api/api/v1/legacy-ref/winter-coefs', { headers: authHeaders() }).then(r => r.json()).then(setWinterList).catch(() => setWinterList([]));
    }
    if (tab === 'directions') {
      fetch('/md-api/api/v1/legacy-ref/mountain-coefs', { headers: authHeaders() }).then(r => r.json()).then(setMountainList).catch(() => setMountainList([]));
      fetch('/md-api/api/v1/legacy-ref/city-coefs', { headers: authHeaders() }).then(r => r.json()).then(setCityCoefList).catch(() => setCityCoefList([]));
    }
    if (tab === 'route-tariffs') {
      fetch('/md-api/api/v1/dictionaries/routes', { headers: authHeaders() }).then(r => r.json()).then(setRouteList).catch(() => setRouteList([]));
    }
  }, [tab]);

  /** Запись справочника → значения формы (для правки). */
  function rowToForm(r: Row): Record<string, string> {
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(r)) {
      if (v == null || typeof v === 'object') continue;
      out[k] = typeof v === 'boolean' ? String(v) : String(v);
    }
    if (tab === 'directions') { out.dirNumber = r.number == null ? '' : String(r.number); delete out.number; }
    if (tab === 'clients') { out.clientType = r.type == null ? '1' : String(r.type); delete out.type; }
    return out;
  }

  function openCreate() { setEditing(null); setForm({}); setError(''); setOk(''); setShowForm(true); }
  function openEdit(r: Row) { setEditing(r); setForm(rowToForm(r)); setError(''); setOk(''); setShowForm(true); }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setOk(''); setBusy(true);
    try {
      const body: Record<string, unknown> = { ...form };
      for (const k of ['transportType', 'regionId', 'routeTypeCode', 'fuelType', 'monthFrom', 'monthTo',
          // города: регион — число; остальные ключи общие

          'typeId', 'capacity', 'carrying', 'costServices', 'fuelInteriorHeating', 'tariffRate',
          'price', 'cargoClass', 'winterCoefId', 'mountainCoefId', 'inCityCoefId', 'fuelId',
          'distanceA', 'distanceB', 'beginPathA', 'beginPathB', 'plannedLap', 'coeUseCapacity', 'averageLengthPassSeat',
          'stationCoef', 'roadQuality', 'mountainCoefValue', 'inCityCoefValue',
          'additionalFuel100', 'additionalFuel', 'condFuel', 'heatingFuel', 'latitude', 'longitude', 'advCoe']) {
        if (k in body) body[k] = body[k] === '' ? null : Number(body[k]);
      }
      if (tab === 'routes') {
        for (const k of ['timeOneLapA', 'timeOneLapB', 'validCert']) {
          if (k in body) body[k] = body[k] === '' ? null : body[k];
        }
        body.excludingCoef = form.excludingCoef === 'true';
      }
      for (const k of ['baseNorm', 'value', 'pricePerKm', 'coef', 'year', 'km', 'pricePer1Mkm', 'priceOneTime']) {
        if (k in body) body[k] = Number(body[k]);
      }
      for (const k of ['periodFrom', 'periodTo']) {
        if (k in body) body[k] = body[k] === '' ? null : body[k];
      }
      if (tab === 'directions') {
        body.number = form.dirNumber ? Number(form.dirNumber) : null;
        delete body.dirNumber;
        body.checked = form.checked === 'true';
      }
      if (tab === 'clients') {
        body.type = form.clientType ? Number(form.clientType) : 1;
        delete body.clientType;
      }
      // Правка существующей записи — по её идентификатору (иначе апсерт по ключу создал бы вторую).
      body.id = editing?.id ?? null;
      await api(tab, body);
      setOk(t('common.saved'));
      setForm({});
      setEditing(null);
      setShowForm(false);
      await reload();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function remove(r: Row) {
    setBusy(true); setError(''); setOk('');
    try {
      await apiDelete(tab, String(r.id));
      setConfirmDel(null);
      setOk(t('dict.deleted'));
      await reload();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const f = (k: string) => ({ value: form[k] ?? '', onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setForm({ ...form, [k]: e.target.value }) });
  const fCheck = (k: string) => ({ checked: form[k] === 'true', onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.checked ? 'true' : 'false' }) });

  const ttSelect = (key: string) => (
    <select {...f(key)}>
      <option value="">{t('dict.opt.vehtype')}</option>
      {Object.entries(TT).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
    </select>
  );
  /** Выбор записи другого справочника вместо ручного ввода числового id. */
  const refSelect = (key: string, list: Row[], label: (r: Row) => string) => (
    <select {...f(key)}>
      <option value="">—</option>
      {list.map(r => <option key={String(r.id)} value={String(r.id)}>{label(r)}</option>)}
    </select>
  );

  const activeLabel = t(LABEL_KEY[tab]);

  function exportCsv() {
    const keys = Array.from(rows.reduce((acc, r) => { Object.keys(r).forEach(k => { if (k !== 'id') acc.add(k); }); return acc; }, new Set<string>()));
    const cell = (v: unknown) => (v != null && typeof v === 'object') ? JSON.stringify(v) : (v ?? '');
    downloadCsv(`${tab}_${new Date().toISOString().slice(0, 10)}.csv`, [keys, ...rows.map(r => keys.map(k => cell(r[k])))]);
  }

  // Поиск по всем текстовым полям записи + постраничный вывод.
  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter(r => Object.entries(r)
      .filter(([k]) => k !== 'id')
      .map(([, v]) => (v == null || typeof v === 'object' ? '' : String(v)))
      .join(' ').toLowerCase().includes(needle));
  }, [rows, q]);
  useEffect(() => { setPage(1); }, [q, rows]);
  const pages = Math.max(1, Math.ceil(filtered.length / PER_PAGE));
  const safePage = Math.min(page, pages);
  const view = filtered.slice((safePage - 1) * PER_PAGE, safePage * PER_PAGE);

  const actionsCell = (r: Row) => (
    <td style={{ whiteSpace: 'nowrap' }}>
      <button type="button" className="btn secondary" style={{ padding: '4px 8px', fontSize: 12 }} title={t('btn.view')} onClick={() => setDetail(r)}>
        <Icon d={P.eye} cls="" style={{ width: 14, height: 14 }} />
      </button>
      {canEdit && (
        <>
          <button type="button" className="btn secondary" style={{ padding: '4px 8px', fontSize: 12, marginLeft: 6 }} title={t('btn.edit')} onClick={() => openEdit(r)}>
            <Icon d={P.pencil} cls="" style={{ width: 14, height: 14 }} />
          </button>
          <button type="button" className="btn secondary" style={{ padding: '4px 8px', fontSize: 12, marginLeft: 6, color: 'var(--red)' }} title={t('btn.delete')} onClick={() => setConfirmDel(r)}>
            <Icon d={P.trash} cls="" style={{ width: 14, height: 14 }} />
          </button>
        </>
      )}
    </td>
  );

  const formBody = (
    <form className="grid" onSubmit={submit}>
      {ORG_TABS.includes(tab) && isSysAdmin && (
        <div><label>{t('col.org')}</label>
          <select required {...f('organizationRma')}>
            <option value="">—</option>
            {orgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{String(o.name ?? o.rma)}</option>)}
          </select>
        </div>
      )}
      {tab === 'routes' && <>
        <div><label>{t('col.number')}</label><input required {...f('number')} placeholder="3" /></div>
        <div><label>{t('col.name')}</label><input required {...f('name')} placeholder="Вокзал — Аэропорт" /></div>
        <div><label>{t('f.vehtype')}</label>{ttSelect('transportType')}</div>
        <div><label>{t('f.region')}</label><input type="number" min={1} max={7} {...f('regionId')} /></div>
        <div><label>{t('col.routetype')}</label>
          <select {...f('routeTypeCode')}>
            <option value="">{t('dict.opt.routetype')}</option>
            {routeTypes.map(rt => <option key={rt.id} value={rt.code}>{rtName(rt)}</option>)}
          </select>
        </div>
        <div><label>{t('route.f.namea')}</label><input {...f('nameA')} /></div>
        <div><label>{t('route.f.nameb')}</label><input {...f('nameB')} /></div>
        <div><label>{t('route.f.city')}</label><input list="route-cities" {...f('cityName')} />
          <datalist id="route-cities">{cities.map(c => <option key={String(c.id)} value={String(c.name)} />)}</datalist>
        </div>
        <div><label>{t('route.f.validcert')}</label><input type="date" {...f('validCert')} /></div>
        {/* «План выручки по дням недели» убран из формы 24.09.2026 (анализ базы, раздел 9.2 п. 11):
            в старой платформе 5,7 %, нигде не используется. Значение сохраняется как было. */}
        <div><label>{t('route.f.timelapa')}</label><input type="time" {...f('timeOneLapA')} /></div>
        <div><label>{t('route.f.timelapb')}</label><input type="time" {...f('timeOneLapB')} /></div>
        {/* Широта/долгота маршрута убраны из формы 24.09.2026: в старой платформе 0,5 %, никто не читает.
            Значение, если было, сохраняется (остаётся в состоянии формы и отправляется как было). */}
        <div><label>{t('route.f.distancea')}</label><input type="number" step="0.1" {...f('distanceA')} /></div>
        <div><label>{t('route.f.distanceb')}</label><input type="number" step="0.1" {...f('distanceB')} /></div>
        <div><label>{t('route.f.beginpatha')}</label><input type="number" step="0.1" {...f('beginPathA')} /></div>
        <div><label>{t('route.f.beginpathb')}</label><input type="number" step="0.1" {...f('beginPathB')} /></div>
        <div><label>{t('route.f.plannedlap')}</label><input type="number" min={0} {...f('plannedLap')} /></div>
        <div><label>{t('route.f.coeuse')}</label><input type="number" step="0.01" {...f('coeUseCapacity')} /></div>
        <div><label>{t('route.f.avgseat')}</label><input type="number" step="0.1" {...f('averageLengthPassSeat')} /></div>
        <div><label>{t('route.f.stationcoef')}</label><input type="number" min={0} max={100} {...f('stationCoef')} /></div>
        <div><label>{t('route.f.roadquality')}</label><input type="number" min={0} max={100} {...f('roadQuality')} /></div>
        <div><label>{t('route.f.mountain')}</label><input type="number" min={0} max={100} {...f('mountainCoefValue')} /></div>
        <div><label>{t('route.f.incity')}</label><input type="number" min={0} max={100} {...f('inCityCoefValue')} /></div>
        <div><label>{t('route.f.wintercoef')}</label>{refSelect('winterCoefId', winterList, r => `${String(r.name)} (${s(r.coef)})`)}</div>
        <div><label>{t('route.f.addfuel100')}</label><input type="number" step="0.1" {...f('additionalFuel100')} /></div>
        <div><label>{t('route.f.addfuel')}</label><input type="number" step="0.1" {...f('additionalFuel')} /></div>
        <div><label>{t('route.f.condfuel')}</label><input type="number" step="0.1" {...f('condFuel')} /></div>
        <div><label>{t('route.f.heatingfuel')}</label><input type="number" step="0.1" {...f('heatingFuel')} /></div>
        <div><label><input type="checkbox" {...fCheck('excludingCoef')} /> {t('route.f.excludingcoef')}</label></div>
      </>}
      {tab === 'clients' && <>
        <div><label>{t('col.number')}</label><input required {...f('number')} placeholder="000123" /></div>
        <div><label>{t('col.clienttype')}</label>
          <select {...f('clientType')}>
            {[1, 2, 3, 4].map(v => <option key={v} value={String(v)}>{t('client.type.' + v)}</option>)}
          </select>
        </div>
        <div><label>{t('col.name')}</label><input required {...f('name')} /></div>
        <div><label>{t('col.address')}</label><input required {...f('address')} /></div>
        {/* Телефон не обязателен (анализ базы, раздел 9.3 п. 2): при обязательном поле в старой
            платформе у 29 % клиентов вписано «1». */}
        <div><label>{t('col.phone')}</label><input {...f('phone')} /></div>
        {/* Реквизиты заполнены у 9–29 % клиентов, нигде не печатаются — свёрнуты (раздел 9.2 п. 14). */}
        <details className="full">
          <summary style={{ cursor: 'pointer', fontWeight: 600, margin: '4px 0' }}>{t('dict.f.clientextra')}</summary>
          <div className="grid" style={{ marginTop: 8 }}>
            <div><label>{t('dict.f.riam')}</label><input {...f('riam')} /></div>
            <div><label>{t('dict.f.rma')}</label><input {...f('rma')} /></div>
            <div><label>{t('dict.f.account')}</label><input {...f('account')} /></div>
            <div><label>{t('dict.f.corraccount')}</label><input {...f('correspondenceAccount')} /></div>
            <div><label>{t('dict.f.mfo')}</label><input {...f('mfo')} /></div>
            <div><label>{t('dict.f.bank')}</label><input {...f('bankName')} /></div>
          </div>
        </details>
      </>}
      {tab === 'fuel-norms' && <>
        <div><label>{t('f.vehtype')}</label>{ttSelect('transportType')}</div>
        <div><label>{t('dict.f.brandall')}</label><input {...f('brand')} placeholder="Акиа" /></div>
        <div><label>{t('dict.f.basenorm')}</label><input required type="number" step="0.1" {...f('baseNorm')} /></div>
      </>}
      {tab === 'coefficients' && <>
        <div><label>{t('dict.f.kind')}</label>
          <select {...f('kind')} required>
            <option value="">—</option>
            {Object.entries(KIND).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </div>
        <div><label>{t('col.name')}</label><input required {...f('name')} /></div>
        <div><label>{t('dict.f.multiplier')}</label><input required type="number" step="0.001" {...f('value')} /></div>
        <div><label>{t('dict.f.regionhigh')}</label><input type="number" min={1} max={7} {...f('regionId')} /></div>
        <div><label>{t('dict.f.monthfrom')}</label><input type="number" min={1} max={12} {...f('monthFrom')} /></div>
        <div><label>{t('dict.f.monthto')}</label><input type="number" min={1} max={12} {...f('monthTo')} /></div>
      </>}
      {tab === 'tariffs' && <>
        <div><label>{t('f.vehtype')}</label>{ttSelect('transportType')}</div>
        <div><label>{t('dict.f.fuelany')}</label><input type="number" min={1} max={5} {...f('fuelType')} /></div>
        <div><label>{t('dict.f.tariff')}</label><input required type="number" step="0.01" {...f('pricePerKm')} /></div>
      </>}
      {tab === 'cargos' && <>
        <div><label>{t('col.name')}</label><input required {...f('name')} placeholder="Цемент навалом" /></div>
        <div><label>{t('col.type')}</label><input required {...f('type')} /></div>
        <div><label>{t('col.unit')}</label><input required {...f('unit')} placeholder="т" /></div>
        {/* Цена не обязательна (раздел 9.3 п. 2): в старой платформе у 53 % грузов вписано «1»;
            в расчётах цена груза не участвует. */}
        <div><label>{t('col.price')}</label><input type="number" step="0.01" {...f('price')} /></div>
        <div><label>{t('col.class')}</label><input type="number" min={1} max={4} {...f('cargoClass')} /></div>
      </>}
      {tab === 'brands' && <>
        <div><label>{t('col.name')}</label><input required {...f('name')} placeholder="КамАЗ" /></div>
        <div><label>{t('col.number')}</label><input {...f('number')} /></div>
        <div><label>{t('col.model')}</label><input {...f('model')} /></div>
        {/* «Тип марки» убран из формы 24.09.2026: в старой платформе 0 %, в расчётах не участвует
            (код марки — поле «Номер» выше — остаётся, от него зависит норма). */}
        <div><label>{t('col.capacity')}</label><input type="number" {...f('capacity')} /></div>
        <div><label>{t('col.carrying')}</label><input type="number" step="0.1" {...f('carrying')} /></div>
        {/* «Тариф марки» убран 24.09.2026 (раздел 9.2 п. 13): реально 2,4 %, нигде не используется. */}
        <div className="full"><label>{t('dict.f.fuel100')}</label><textarea rows={2} {...f('fuel100')} placeholder='[{"fuel_id":2,"consumption":25}]' /></div>
        <div className="full"><label>{t('dict.f.fuel100dushanbe')}</label><textarea rows={2} {...f('fuel100Dushanbe')} /></div>
        <div className="full"><label>{t('dict.f.fuelhour')}</label><textarea rows={2} {...f('fuelHour')} /></div>
      </>}
      {tab === 'winter-coefs' && <>
        <div><label>{t('col.name')}</label><input required {...f('name')} placeholder="Зимний период (равнина)" /></div>
        <div><label>{t('col.periodfrom')}</label><input type="date" {...f('periodFrom')} /></div>
        <div><label>{t('col.periodto')}</label><input type="date" {...f('periodTo')} /></div>
        <div><label>{t('col.coef')}</label><input required type="number" {...f('coef')} /></div>
      </>}
      {(tab === 'mountain-coefs' || tab === 'city-coefs') && <>
        <div><label>{t('col.name')}</label><input required {...f('name')} /></div>
        <div><label>{t('col.coef')}</label><input required type="number" {...f('coef')} /></div>
      </>}
      {tab === 'used-coefs' && <>
        <div><label>{t('col.year')}</label><input required type="number" {...f('year')} /></div>
        <div><label>{t('col.km')}</label><input required type="number" {...f('km')} /></div>
        <div><label>{t('col.coef')}</label><input required type="number" {...f('coef')} /></div>
      </>}
      {tab === 'drive-classes' && <>
        <div><label>{t('col.driveclass')}</label><input required {...f('driveClass')} placeholder="1" /></div>
        <div><label>{t('col.coef')}</label><input required type="number" {...f('coef')} /></div>
      </>}
      {tab === 'directions' && <>
        <div><label>{t('col.title')}</label><input required {...f('title')} placeholder="Душанбе - Худжанд" /></div>
        <div><label>{t('col.number')}</label><input type="number" {...f('dirNumber')} /></div>
        {/* Коэффициенты выбираются по названию, а не вводом числового идентификатора. */}
        <div><label>{t('dict.f.winterCoefId')}</label>{refSelect('winterCoefId', winterList, r => `${String(r.name)} (${s(r.coef)})`)}</div>
        <div><label>{t('dict.f.mountainCoefId')}</label>{refSelect('mountainCoefId', mountainList, r => `${String(r.name)} (${s(r.coef)})`)}</div>
        <div><label>{t('dict.f.inCityCoefId')}</label>{refSelect('inCityCoefId', cityCoefList, r => `${String(r.name)} (${s(r.coef)})`)}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <label>{t('col.checked')}</label><input type="checkbox" {...fCheck('checked')} />
        </div>
      </>}
      {tab === 'cities' && <>
        <div><label>{t('f.region')}</label>
          <select required {...f('regionId')}>
            <option value="">—</option>
            {[1, 2, 3, 4, 5, 6, 7].map(n => <option key={n} value={String(n)}>{t('region.' + n)}</option>)}
          </select>
        </div>
        <div><label>{t('col.code')}</label><input maxLength={20} {...f('code')} placeholder="01" /></div>
        <div><label>{t('col.name')}</label><input required {...f('name')} placeholder="Душанбе" /></div>
      </>}
      {tab === 'route-tariffs' && <>
        <div><label>{t('col.routeid')}</label>
          <select required {...f('routeId')}>
            <option value="">—</option>
            {routeList.map(r => <option key={String(r.id)} value={String(r.id)}>{`${String(r.number)} — ${String(r.name)}`}</option>)}
          </select>
        </div>
        <div><label>{t('dict.f.fuelidopt')}</label><input type="number" min={1} max={5} {...f('fuelId')} /></div>
        <div><label>{t('col.number')}</label><input {...f('number')} /></div>
        <div><label>{t('col.transport')}</label><input {...f('typeAuto')} /></div>
        <div><label>{t('dict.f.pricepermkm')}</label><input required type="number" step="0.01" {...f('pricePer1Mkm')} /></div>
        <div><label>{t('dict.f.priceonetime')}</label><input required type="number" step="0.01" {...f('priceOneTime')} /></div>
        {/* «Коэффициент доп.» (advCoe) убран 24.09.2026 (раздел 9.2 п. 16): 5 записей за всё время,
            в расчёте не применяется (MIGRATION 2.26). */}
      </>}
      <div className="full" style={{ display: 'flex', gap: 10 }}>
        <button className="btn" type="submit" disabled={busy}>{busy ? '…' : t('btn.save')}</button>
        <button className="btn secondary" type="button" onClick={() => setShowForm(false)}>{t('btn.cancel')}</button>
      </div>
    </form>
  );

  return (
    <>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      {!canEdit && (
        <div className="card" style={{ color: 'var(--muted)', fontSize: 13.5 }}>
          {t(LEGACY_REF_TABS.includes(tab) ? 'dict.readonly.legacyref' : 'dict.readonly.national')}
        </div>
      )}

      <div className="card">
        <div className="card-h">
          <h2>{activeLabel}</h2>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('dict.search')} style={{ width: 220 }} />
            <button type="button" className="btn secondary" onClick={exportCsv} disabled={rows.length === 0} title={t('rep.export.hint')}>CSV</button>
            {canEdit && (
              <button type="button" className="btn" onClick={openCreate}>
                <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('dict.add')}
              </button>
            )}
          </div>
        </div>
        <table>
          <thead>
            {tab === 'routes' && <tr><th>{t('col.number')}</th><th>{t('col.name')}</th><th>{t('f.vehtype')}</th><th>{t('col.region')}</th><th>{t('col.routetype')}</th><th>{t('route.f.namea')} / {t('route.f.nameb')}</th><th>{t('route.f.city')}</th><th>{t('route.f.validcert')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'clients' && <tr><th>{t('col.number')}</th><th>{t('col.clienttype')}</th><th>{t('col.name')}</th><th>{t('col.address')}</th><th>{t('col.phone')}</th><th>{t('dict.f.rma')}</th><th>{t('dict.f.bank')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'fuel-norms' && <tr><th>{t('f.vehtype')}</th><th>{t('col.brand')}</th><th>{t('dict.col.norm')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'coefficients' && <tr><th>{t('dict.f.kind')}</th><th>{t('col.name')}</th><th>{t('dict.f.multiplier').replace(/\s*\(.*\)/, '')}</th><th>{t('col.region')}</th><th>{t('dict.col.months')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'tariffs' && <tr><th>{t('f.vehtype')}</th><th>{t('col.fuel')}</th><th>{t('dict.col.somonikm')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'cargos' && <tr><th>{t('col.cargonumber')}</th><th>{t('col.name')}</th><th>{t('col.type')}</th><th>{t('col.unit')}</th><th>{t('col.price')}</th><th>{t('col.class')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'brands' && <tr><th>{t('col.name')}</th><th>{t('col.number')}</th><th>{t('col.model')}</th><th>{t('col.capacity')}</th><th>{t('dict.f.tariff').replace(/,.*/, '')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'winter-coefs' && <tr><th>{t('col.name')}</th><th>{t('col.periodfrom')}</th><th>{t('col.periodto')}</th><th>{t('col.coef')}</th><th>{t('col.actions')}</th></tr>}
            {(tab === 'mountain-coefs' || tab === 'city-coefs') && <tr><th>{t('col.name')}</th><th>{t('col.coef')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'used-coefs' && <tr><th>{t('col.year')}</th><th>{t('col.km')}</th><th>{t('col.coef')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'drive-classes' && <tr><th>{t('col.driveclass')}</th><th>{t('col.coef')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'directions' && <tr><th>{t('col.title')}</th><th>{t('col.number')}</th><th>{t('col.checked')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'route-tariffs' && <tr><th>{t('col.routeid')}</th><th>{t('col.fuel')}</th><th>{t('dict.f.pricepermkm')}</th><th>{t('dict.f.priceonetime')}</th><th>{t('dict.f.advcoe')}</th><th>{t('col.actions')}</th></tr>}
            {tab === 'cities' && <tr><th>{t('col.region')}</th><th>{t('col.code')}</th><th>{t('col.name')}</th><th>{t('col.actions')}</th></tr>}
          </thead>
          <tbody>
            {view.map((r, i) => (
              <tr key={String(r.id ?? i)}>
                {tab === 'routes' && <><td><span className="number">{String(r.number)}</span></td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td>{TT[Number(r.transportType)] ?? '—'}</td><td>{String(r.regionId ?? '—')}</td><td>{r.routeTypeCode != null ? (rtByCode(r.routeTypeCode) ? rtName(rtByCode(r.routeTypeCode)!) : String(r.routeTypeCode)) : '—'}</td><td>{r.nameA || r.nameB ? `${String(r.nameA ?? '')} — ${String(r.nameB ?? '')}` : '—'}</td><td>{String(r.cityName ?? '—')}</td><td>{String(r.validCert ?? '—')}</td></>}
                {tab === 'clients' && <><td><span className="number">{String(r.number ?? '—')}</span></td><td>{r.type != null && [1, 2, 3, 4].includes(Number(r.type)) ? t('client.type.' + Number(r.type)) : '—'}</td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td>{String(r.address ?? '—')}</td><td>{String(r.phone ?? '—')}</td><td>{String(r.rma ?? '—')}</td><td>{r.bankName ? `${String(r.bankName)}${r.mfo ? ` (${t('dict.f.mfo')} ${String(r.mfo)})` : ''}` : '—'}</td></>}
                {tab === 'fuel-norms' && <><td>{TT[Number(r.transportType)] ?? r.transportType}</td><td>{r.brand ? String(r.brand) : t('dict.all')}</td><td><b>{String(r.baseNorm)}</b></td></>}
                {tab === 'coefficients' && <><td>{KIND[String(r.kind)] ?? String(r.kind)}</td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td><b>{String(r.value)}</b></td><td>{String(r.regionId ?? '—')}</td><td>{r.monthFrom ? `${r.monthFrom}–${r.monthTo}` : '—'}</td></>}
                {tab === 'tariffs' && <><td>{TT[Number(r.transportType)] ?? r.transportType}</td><td>{r.fuelType != null ? String(r.fuelType) : t('dict.any')}</td><td><b>{String(r.pricePerKm)}</b></td></>}
                {tab === 'cargos' && <><td><span className="number">{r.number != null ? String(r.number) : '—'}</span></td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td>{String(r.type ?? '—')}</td><td>{String(r.unit ?? '—')}</td><td>{r.price != null ? String(r.price) : '—'}</td><td>{r.cargoClass != null ? String(r.cargoClass) : '—'}</td></>}
                {tab === 'brands' && <><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td>{String(r.number ?? '—')}</td><td>{String(r.model ?? '—')}</td><td>{String(r.capacity ?? '—')}</td><td>{r.tariffRate != null ? String(r.tariffRate) : '—'}</td></>}
                {tab === 'winter-coefs' && <><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td>{String(r.periodFrom ?? '—')}</td><td>{String(r.periodTo ?? '—')}</td><td><b>{String(r.coef ?? '—')}</b></td></>}
                {(tab === 'mountain-coefs' || tab === 'city-coefs') && <><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td><td><b>{String(r.coef ?? '—')}</b></td></>}
                {tab === 'used-coefs' && <><td>{String(r.year ?? '—')}</td><td>{String(r.km ?? '—')}</td><td><b>{String(r.coef ?? '—')}</b></td></>}
                {tab === 'drive-classes' && <><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.driveClass)}</td><td><b>{String(r.coef ?? '—')}</b></td></>}
                {tab === 'directions' && <><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.title)}</td><td>{String(r.number ?? '—')}</td><td>{r.checked ? t('st.yes') : '—'}</td></>}
                {tab === 'cities' && <><td>{r.regionId != null ? t('region.' + Number(r.regionId)) : '—'}</td><td><span className="number">{s(r.code)}</span></td><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td></>}
                {tab === 'route-tariffs' && <><td>{(() => { const rt = routeList.find(x => String(x.id) === String(r.routeId)); return rt ? `${String(rt.number)} — ${String(rt.name)}` : String(r.routeId); })()}</td><td>{r.fuelId != null ? String(r.fuelId) : t('dict.any')}</td><td><b>{String(r.pricePer1Mkm)}</b></td><td>{String(r.priceOneTime)}</td><td>{r.advCoe != null ? String(r.advCoe) : '—'}</td></>}
                {actionsCell(r)}
              </tr>
            ))}
            {filtered.length === 0 && <tr><td colSpan={10} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
          </tbody>
        </table>

        <div style={{ display: 'flex', alignItems: 'center', marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>
          <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{filtered.length}</b>{rows.length !== filtered.length ? ` ${t('paging.of')} ${rows.length}` : ''}</span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={safePage <= 1} onClick={() => setPage(p => Math.max(1, p - 1))} style={{ padding: '5px 11px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{safePage} / {pages}</span>
          <button className="btn secondary" disabled={safePage >= pages} onClick={() => setPage(p => Math.min(pages, p + 1))} style={{ padding: '5px 11px' }}>›</button>
        </div>
      </div>

      {showForm && (
        <Modal wide title={`${activeLabel}: ${editing ? t('dict.editrecord') : t('dict.add')}`} onClose={() => setShowForm(false)}>
          {formBody}
        </Modal>
      )}

      {detail && (
        <Modal title={activeLabel} onClose={() => setDetail(null)}>
          <table>
            <tbody>
              {Object.entries(detail).filter(([k]) => k !== 'id').map(([k, v]) => (
                <tr key={k}><th style={{ width: '45%', textAlign: 'left' }}>{k}</th><td>{v != null && typeof v === 'object' ? JSON.stringify(v) : s(v)}</td></tr>
              ))}
            </tbody>
          </table>
        </Modal>
      )}

      {confirmDel && (
        <Modal title={t('dict.delete.title')} onClose={() => setConfirmDel(null)}>
          <p style={{ marginBottom: 14 }}>
            {t('dict.delete.q')} <b>{String(confirmDel.name ?? confirmDel.title ?? confirmDel.driveClass ?? confirmDel.number ?? confirmDel.id)}</b>?
          </p>
          <div className="hint" style={{ marginBottom: 14 }}>{t('dict.delete.note')}</div>
          <div style={{ display: 'flex', gap: 10 }}>
            <button className="btn" style={{ background: 'var(--red)' }} disabled={busy} onClick={() => remove(confirmDel)}>{busy ? '…' : t('btn.delete')}</button>
            <button className="btn secondary" onClick={() => setConfirmDel(null)}>{t('btn.cancel')}</button>
          </div>
        </Modal>
      )}
    </>
  );
}
