'use client';

import { useCallback, useEffect, useState } from 'react';
import { authHeaders, md, type RouteType } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { canEditNationalDictionaries } from '@/lib/roles';

type Row = Record<string, unknown>;
export type DictTab = 'routes' | 'clients' | 'fuel-norms' | 'coefficients' | 'tariffs' | 'cargos'
  | 'brands' | 'winter-coefs' | 'mountain-coefs' | 'city-coefs' | 'used-coefs' | 'drive-classes'
  | 'directions' | 'route-tariffs';

const LABEL_KEY: Record<DictTab, string> = {
  routes: 'dict.sec.routes', clients: 'dict.sec.clients', 'fuel-norms': 'dict.sec.fuelnorms',
  coefficients: 'dict.sec.coefficients', tariffs: 'dict.sec.tariffs', cargos: 'dict.sec.cargos',
  brands: 'dict.sec.brands', 'winter-coefs': 'dict.sec.wintercoefs', 'mountain-coefs': 'dict.sec.mountaincoefs',
  'city-coefs': 'dict.sec.citycoefs', 'used-coefs': 'dict.sec.usedcoefs', 'drive-classes': 'dict.sec.driveclasses',
  directions: 'dict.sec.directions', 'route-tariffs': 'dict.sec.routetariffs',
};

const TT: Record<number, string> = { 1: 'Автобус', 2: 'Троллейбус', 3: 'Микроавтобус', 4: 'Легковой', 5: 'Грузовой', 6: 'Грузовой межд.' };
const KIND: Record<string, string> = { WINTER: 'Зимний', CITY: 'Внутригородской', HIGHLAND: 'Высокогорный', USAGE: 'Эксплуатационный' };

// Справочники расчётного ядра (LegacyReferenceController) живут под /api/v1/legacy-ref/*,
// а не /api/v1/dictionaries/* — остальное (клиенты/маршруты/нормы/коэфф./тарифы/грузы) там же, где были.
const LEGACY_REF_TABS: DictTab[] = ['brands', 'winter-coefs', 'mountain-coefs', 'city-coefs',
  'used-coefs', 'drive-classes', 'directions', 'route-tariffs'];

function apiBase(tab: DictTab): string {
  return LEGACY_REF_TABS.includes(tab) ? 'legacy-ref' : 'dictionaries';
}

async function api<T>(tab: DictTab, body?: unknown): Promise<T> {
  const r = await fetch(`/md-api/api/v1/${apiBase(tab)}/${tab}`, body ? {
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

/** Один справочник платформы; раздел задаётся маршрутом (/dictionaries/<...>). */
// Нац. справочники (правит только SYSTEM_ADMIN): нормы/коэфф./тарифы (org-нейтральные) +
// весь расчётный блок LegacyReferenceController (тот же принцип — единые для платформы данные).
const NATIONAL_TABS: DictTab[] = ['fuel-norms', 'coefficients', 'tariffs', ...LEGACY_REF_TABS];

export default function DictionariesView({ tab }: { tab: DictTab }) {
  const { t, lang } = useT();
  const { roles } = useAuth();
  // Нац. справочники (нормы/коэффициенты/тарифы, легаси-справочники расчёта) правит только
  // SYSTEM_ADMIN — форма добавления/правки скрыта остальным, иначе COMPANY_ADMIN видит форму,
  // которую сервер всё равно отклонит 403 (тот же класс UX-бага, что был с кнопками бухгалтера).
  // Грузы (cargos) сюда не входят — их правит COMPANY_ADMIN/SYSTEM_ADMIN, как маршруты/клиентов.
  const canEdit = !NATIONAL_TABS.includes(tab) || canEditNationalDictionaries(roles);
  const [rows, setRows] = useState<Row[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [form, setForm] = useState<Record<string, string>>({});
  // Справочник типов маршрутов (V63) — для выбора «Тип маршрута» в форме и подписи в таблице.
  const [routeTypes, setRouteTypes] = useState<RouteType[]>([]);
  const rtName = (rt: RouteType) => (lang === 'tj' && rt.nameTj) ? rt.nameTj : rt.nameRu;
  const rtByCode = (code: unknown) => routeTypes.find(rt => String(rt.code) === String(code));

  const reload = useCallback(async () => {
    setRows(await api<Row[]>(tab));
  }, [tab]);

  useEffect(() => { setForm({}); setOk(''); setError(''); reload().catch(e => setError(e.message)); }, [reload, tab]);
  // Города/районы (справочник city) — подсказки поля «Город/район» маршрута (V67, 2.28).
  const [cities, setCities] = useState<Record<string, unknown>[]>([]);
  // Типы маршрутов и города нужны только на вкладке «Маршруты»; тянем один раз при входе на неё.
  useEffect(() => {
    if (tab !== 'routes') return;
    md.routeTypes().then(setRouteTypes).catch(() => setRouteTypes([]));
    md.cities().then(setCities).catch(() => setCities([]));
  }, [tab]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setOk('');
    try {
      const body: Record<string, unknown> = { ...form };
      // Необязательные числовые поля: пусто → null.
      for (const k of ['transportType', 'regionId', 'routeTypeCode', 'fuelType', 'monthFrom', 'monthTo',
          'typeId', 'capacity', 'carrying', 'costServices', 'fuelInteriorHeating', 'tariffRate',
          'price', 'cargoClass', 'winterCoefId', 'mountainCoefId', 'inCityCoefId', 'fuelId',
          // маршрут: путевые показатели/коэффициенты (V28) и координаты (V67)
          'distanceA', 'distanceB', 'beginPathA', 'beginPathB', 'plannedLap', 'coeUseCapacity', 'averageLengthPassSeat',
          'stationCoef', 'roadQuality', 'mountainCoefValue', 'inCityCoefValue',
          'additionalFuel100', 'additionalFuel', 'condFuel', 'heatingFuel', 'latitude', 'longitude']) {
        if (k in body) body[k] = body[k] === '' ? null : Number(body[k]);
      }
      // Маршрут: время рейса / срок свидетельства — пусто → null; флаг «без коэффициентов» — boolean.
      if (tab === 'routes') {
        for (const k of ['timeOneLapA', 'timeOneLapB', 'validCert']) {
          if (k in body) body[k] = body[k] === '' ? null : body[k];
        }
        body.excludingCoef = form.excludingCoef === 'true';
      }
      // Обязательные числовые поля.
      for (const k of ['baseNorm', 'value', 'pricePerKm', 'coef', 'year', 'km', 'pricePer1Mkm', 'priceOneTime']) {
        if (k in body) body[k] = Number(body[k]);
      }
      // Даты (LocalDate): пусто → null, иначе строка ISO как есть из <input type="date">.
      for (const k of ['periodFrom', 'periodTo']) {
        if (k in body) body[k] = body[k] === '' ? null : body[k];
      }
      // Direction.number — числовой, но ключ формы "number" занят текстовым полем routes/clients/
      // route-tariffs; используем отдельный ключ "dirNumber" и переносим его при отправке.
      if (tab === 'directions') {
        body.number = form.dirNumber ? Number(form.dirNumber) : null;
        delete body.dirNumber;
        body.checked = form.checked === 'true';
      }
      // Client.type — числовой вид клиента (1–4); ключ формы "type" занят текстовым полем cargos.
      if (tab === 'clients') {
        body.type = form.clientType ? Number(form.clientType) : 1;
        delete body.clientType;
      }
      await api(tab, body);
      setOk(t('common.saved'));
      setForm({});
      await reload();
    } catch (err) {
      setError((err as Error).message);
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

  const activeLabel = t(LABEL_KEY[tab]);

  return (
    <>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      {!canEdit && (
        <div className="card" style={{ color: 'var(--muted)', fontSize: 13.5 }}>
          {t(LEGACY_REF_TABS.includes(tab) ? 'dict.readonly.legacyref' : 'dict.readonly.national')}
        </div>
      )}

      {canEdit && (
      <div className="card" style={{ borderColor: 'var(--blue-500)' }}>
        <h2>{activeLabel}: {t('dict.addupdate')}</h2>
        <form className="grid" onSubmit={submit}>
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
            {/* Поля формы legacy (V67, MIGRATION.md 2.28): пункты А/Б, время рейса, свидетельство, город, координаты. */}
            <div><label>{t('route.f.namea')}</label><input {...f('nameA')} /></div>
            <div><label>{t('route.f.nameb')}</label><input {...f('nameB')} /></div>
            <div><label>{t('route.f.city')}</label><input list="route-cities" {...f('cityName')} />
              <datalist id="route-cities">{cities.map(c => <option key={String(c.id)} value={String(c.name)} />)}</datalist>
            </div>
            <div><label>{t('route.f.validcert')}</label><input type="date" {...f('validCert')} /></div>
            <div><label>{t('route.f.timelapa')}</label><input type="time" {...f('timeOneLapA')} /></div>
            <div><label>{t('route.f.timelapb')}</label><input type="time" {...f('timeOneLapB')} /></div>
            <div><label>{t('route.f.latitude')}</label><input type="number" step="0.000001" min={-90} max={90} {...f('latitude')} /></div>
            <div><label>{t('route.f.longitude')}</label><input type="number" step="0.000001" min={-180} max={180} {...f('longitude')} /></div>
            {/* Путевые показатели и коэффициенты (V28) — раньше правились только через API. */}
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
            <div><label>{t('route.f.wintercoef')}</label><input type="number" min={1} {...f('winterCoefId')} /></div>
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
            <div><label>{t('col.address')}</label><input {...f('address')} /></div>
            <div><label>{t('col.phone')}</label><input {...f('phone')} /></div>
            <div><label>{t('dict.f.riam')}</label><input {...f('riam')} /></div>
            <div><label>{t('dict.f.rma')}</label><input {...f('rma')} /></div>
            <div><label>{t('dict.f.account')}</label><input {...f('account')} /></div>
            <div><label>{t('dict.f.corraccount')}</label><input {...f('correspondenceAccount')} /></div>
            <div><label>{t('dict.f.mfo')}</label><input {...f('mfo')} /></div>
            <div><label>{t('dict.f.bank')}</label><input {...f('bankName')} /></div>
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
            <div><label>{t('col.type')}</label><input {...f('type')} /></div>
            <div><label>{t('col.unit')}</label><input {...f('unit')} placeholder="т" /></div>
            <div><label>{t('col.price')}</label><input type="number" step="0.01" {...f('price')} /></div>
            <div><label>{t('col.class')}</label><input type="number" min={1} max={4} {...f('cargoClass')} /></div>
          </>}
          {tab === 'brands' && <>
            <div><label>{t('col.name')}</label><input required {...f('name')} placeholder="КамАЗ" /></div>
            <div><label>{t('col.number')}</label><input {...f('number')} /></div>
            <div><label>{t('col.model')}</label><input {...f('model')} /></div>
            <div><label>{t('dict.f.typeid')}</label><input type="number" {...f('typeId')} /></div>
            <div><label>{t('col.capacity')}</label><input type="number" {...f('capacity')} /></div>
            <div><label>{t('col.carrying')}</label><input type="number" step="0.1" {...f('carrying')} /></div>
            <div><label>{t('dict.f.tariff')}</label><input type="number" step="0.01" {...f('tariffRate')} /></div>
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
            <div><label>{t('dict.f.winterCoefId')}</label><input type="number" {...f('winterCoefId')} /></div>
            <div><label>{t('dict.f.mountainCoefId')}</label><input type="number" {...f('mountainCoefId')} /></div>
            <div><label>{t('dict.f.inCityCoefId')}</label><input type="number" {...f('inCityCoefId')} /></div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <label>{t('col.checked')}</label><input type="checkbox" {...fCheck('checked')} />
            </div>
          </>}
          {tab === 'route-tariffs' && <>
            <div><label>{t('col.routeid')}</label><input required {...f('routeId')} placeholder="UUID маршрута" /></div>
            <div><label>{t('dict.f.fuelidopt')}</label><input type="number" min={1} max={5} {...f('fuelId')} /></div>
            <div><label>{t('col.number')}</label><input {...f('number')} /></div>
            <div><label>{t('col.transport')}</label><input {...f('typeAuto')} /></div>
            <div><label>{t('dict.f.pricepermkm')}</label><input required type="number" step="0.01" {...f('pricePer1Mkm')} /></div>
            <div><label>{t('dict.f.priceonetime')}</label><input required type="number" step="0.01" {...f('priceOneTime')} /></div>
          </>}
          <div className="full"><button className="btn" type="submit">{t('btn.save')}</button></div>
        </form>
      </div>
      )}

      <div className="card">
        <div className="card-h">
          <h2>{activeLabel}</h2>
          <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 12.5 }}>{t('dict.totalrecords')}: {rows.length}</span>
        </div>
        <table>
          <thead>
            {tab === 'routes' && <tr><th>{t('col.number')}</th><th>{t('col.name')}</th><th>{t('f.vehtype')}</th><th>{t('col.region')}</th><th>{t('col.routetype')}</th><th>{t('route.f.namea')} / {t('route.f.nameb')}</th><th>{t('route.f.city')}</th><th>{t('route.f.validcert')}</th></tr>}
            {tab === 'clients' && <tr><th>{t('col.number')}</th><th>{t('col.clienttype')}</th><th>{t('col.name')}</th><th>{t('col.address')}</th><th>{t('col.phone')}</th><th>{t('dict.f.rma')}</th><th>{t('dict.f.bank')}</th></tr>}
            {tab === 'fuel-norms' && <tr><th>{t('f.vehtype')}</th><th>{t('col.brand')}</th><th>{t('dict.col.norm')}</th></tr>}
            {tab === 'coefficients' && <tr><th>{t('dict.f.kind')}</th><th>{t('col.name')}</th><th>{t('dict.f.multiplier').replace(/\s*\(.*\)/, '')}</th><th>{t('col.region')}</th><th>{t('dict.col.months')}</th></tr>}
            {tab === 'tariffs' && <tr><th>{t('f.vehtype')}</th><th>{t('col.fuel')}</th><th>{t('dict.col.somonikm')}</th></tr>}
            {tab === 'cargos' && <tr><th>{t('col.cargonumber')}</th><th>{t('col.name')}</th><th>{t('col.type')}</th><th>{t('col.unit')}</th><th>{t('col.price')}</th><th>{t('col.class')}</th></tr>}
            {tab === 'brands' && <tr><th>{t('col.name')}</th><th>{t('col.number')}</th><th>{t('col.model')}</th><th>{t('col.capacity')}</th><th>{t('dict.f.tariff').replace(/,.*/, '')}</th></tr>}
            {tab === 'winter-coefs' && <tr><th>{t('col.name')}</th><th>{t('col.periodfrom')}</th><th>{t('col.periodto')}</th><th>{t('col.coef')}</th></tr>}
            {(tab === 'mountain-coefs' || tab === 'city-coefs') && <tr><th>{t('col.name')}</th><th>{t('col.coef')}</th></tr>}
            {tab === 'used-coefs' && <tr><th>{t('col.year')}</th><th>{t('col.km')}</th><th>{t('col.coef')}</th></tr>}
            {tab === 'drive-classes' && <tr><th>{t('col.driveclass')}</th><th>{t('col.coef')}</th></tr>}
            {tab === 'directions' && <tr><th>{t('col.title')}</th><th>{t('col.number')}</th><th>{t('col.checked')}</th></tr>}
            {tab === 'route-tariffs' && <tr><th>{t('col.routeid')}</th><th>{t('col.fuel')}</th><th>{t('dict.f.pricepermkm')}</th><th>{t('dict.f.priceonetime')}</th></tr>}
          </thead>
          <tbody>
            {rows.map((r, i) => (
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
                {tab === 'route-tariffs' && <><td><span className="number">{String(r.routeId)}</span></td><td>{r.fuelId != null ? String(r.fuelId) : t('dict.any')}</td><td><b>{String(r.pricePer1Mkm)}</b></td><td>{String(r.priceOneTime)}</td></>}
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
