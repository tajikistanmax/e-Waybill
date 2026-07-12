'use client';

import { Fragment, useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { md, wb, TYPE_LABELS, type FieldDefinition } from '@/lib/api';
import { Icon, P } from '../../icons';
import { SearchSelect, type SSOption } from '../../SearchSelect';
import { useT } from '@/lib/i18n';

type Option = { value: string; label: string };
type Trailer = { registrationNumber: string; brand: string };

const INTL_TYPES = ['WB_TRUCK_INTL', 'WB_PAX_INTL'];

// Локальные глифы (Icon принимает любой path d — icons.tsx не трогаем).
const BUS_ICON = 'M4 6h13a2 2 0 0 1 2 2v7H4zM4 15v2h3v-2M16 15v2h3v-2M4 10.5h15M9 6v9M13 6v9';
const TRUCK_ICON = 'M3 6h10v9H3zM13 9h4l3 3v3h-7zM7 18a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM17 18a2 2 0 1 0 0-4 2 2 0 0 0 0 4z';

type TypeMeta = { icon: string; color: string; group: string; desc: string };

// group/desc — ключи словаря i18n (переводятся через tt() при отрисовке)
const TYPE_META: Record<string, TypeMeta> = {
  WB_BUS:         { icon: BUS_ICON,   color: 'blue',   group: 'wb.grp.pax',     desc: 'wb.desc.WB_BUS' },
  WB_TROLLEYBUS:  { icon: BUS_ICON,   color: 'green',  group: 'wb.grp.pax',     desc: 'wb.desc.WB_TROLLEYBUS' },
  WB_MINIBUS:     { icon: BUS_ICON,   color: 'cyan',   group: 'wb.grp.pax',     desc: 'wb.desc.WB_MINIBUS' },
  WB_CAR:         { icon: P.car,      color: 'amber',  group: 'wb.grp.car',     desc: 'wb.desc.WB_CAR' },
  WB_TAXI:        { icon: P.car,      color: 'purple', group: 'wb.grp.car',     desc: 'wb.desc.WB_TAXI' },
  WB_TRUCK:       { icon: TRUCK_ICON, color: 'blue',   group: 'wb.grp.truck',   desc: 'wb.desc.WB_TRUCK' },
  WB_TRUCK_INTL:  { icon: TRUCK_ICON, color: 'purple', group: 'wb.grp.intl',    desc: 'wb.desc.WB_TRUCK_INTL' },
  WB_PAX_INTL:    { icon: P.globe,    color: 'cyan',   group: 'wb.grp.intl',    desc: 'wb.desc.WB_PAX_INTL' },
  WB_SPECIAL:     { icon: P.wrench,   color: 'red',    group: 'wb.grp.special', desc: 'wb.desc.WB_SPECIAL' },
  WB_DANGEROUS:   { icon: P.alert,    color: 'red',    group: 'wb.grp.special', desc: 'wb.desc.WB_DANGEROUS' },
};

// title/sub — ключи словаря i18n
const STEPS = [
  { n: 1, title: 'wb.step1.t', sub: 'wb.step1.s' },
  { n: 2, title: 'wb.step2.t', sub: 'wb.step2.s' },
  { n: 3, title: 'wb.step3.t', sub: 'wb.step3.s' },
  { n: 4, title: 'wb.step4.t', sub: 'wb.step4.s' },
];

export default function NewWaybillPage() {
  const router = useRouter();
  const { t: tt, tType } = useT();
  const [step, setStep] = useState(1);
  const [orgs, setOrgs] = useState<Option[]>([]);
  // Выбранные ТС/водитель — храним саму опцию (label для шага проверки), без загрузки всего парка.
  const [selVehicle, setSelVehicle] = useState<SSOption | null>(null);
  const [selDriver, setSelDriver] = useState<SSOption | null>(null);
  const [selSecondDriver, setSelSecondDriver] = useState<SSOption | null>(null);
  const [countries, setCountries] = useState<Option[]>([]);
  const [adrClasses, setAdrClasses] = useState<Option[]>([]);
  const [permitTypes, setPermitTypes] = useState<Option[]>([]);
  const [dangerous, setDangerous] = useState({ adrClass: '', unNumber: '' });
  const [customDefs, setCustomDefs] = useState<FieldDefinition[]>([]);
  const [customValues, setCustomValues] = useState<Record<string, string>>({});
  const [orgRma, setOrgRma] = useState('');
  const [form, setForm] = useState({
    waybillType: 'WB_BUS',
    vehicleRegNumber: '',
    driverRma: '',
    communicationType: 'URBAN',
    route: '',
    schedule: '',
  });
  // Типовая специфика
  const [serviceKind, setServiceKind] = useState('TAXI');           // 3-С
  const [shipmentKind, setShipmentKind] = useState('PIECEWORK');    // 2-Б
  const [trailers, setTrailers] = useState<Trailer[]>([]);          // 2-Б
  const [intl, setIntl] = useState({                                // 5Б-БМ / 4М-БМ
    secondDriverRma: '', visaValidTo: '', visaCountry: '',
    loadCountry: '', unloadCountry: '', transitCountries: '',
    cargoName: '', permitNumber: '', permitType: '', bbaNumber: '',
  });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    md.organizations()
      .then(list => {
        setOrgs(list.map(o => ({ value: String(o.rma), label: `${o.name} (${o.rma})` })));
        if (list.length === 1) setOrgRma(String(list[0].rma));
      })
      .catch(e => setError(e.message));
  }, []);

  // Поисковые загрузчики (серверный подстрочный поиск, лимит 25) — для автопарков в тысячи ТС/водителей.
  const searchVehicles = useCallback(async (q: string): Promise<SSOption[]> => {
    if (!orgRma) return [];
    const list = await md.searchVehicles(orgRma, q);
    return list.map(v => ({ value: String(v.registrationNumber), label: String(v.registrationNumber), sub: String(v.brand ?? '') }));
  }, [orgRma]);
  const searchDrivers = useCallback(async (q: string): Promise<SSOption[]> => {
    if (!orgRma) return [];
    const list = await md.searchDrivers(orgRma, q);
    return list.map(d => ({ value: String(d.rma), label: String(d.fullName), sub: `ИНН ${d.rma}` }));
  }, [orgRma]);

  // Классификаторы для форм международных/опасных ПЛ (значение = наименование/код).
  useEffect(() => {
    md.classifiers('COUNTRY')
      .then(list => setCountries(list.map(c => ({ value: c.nameRu, label: c.nameRu }))))
      .catch(() => { /* классификатор недоступен — поля останутся пустыми */ });
    md.classifiers('ADR_CLASS')
      .then(list => setAdrClasses(list.map(c => ({ value: c.code, label: `${c.code} — ${c.nameRu}` }))))
      .catch(() => {});
    md.classifiers('PERMIT_TYPE')
      .then(list => setPermitTypes(list.map(c => ({ value: c.nameRu, label: c.nameRu }))))
      .catch(() => {});
  }, []);

  // Доп.поля выбранного типа ПЛ (конструктор полей). Флаг отмены — против гонки:
  // запоздавший ответ по прежнему типу не должен перезаписать поля текущего.
  useEffect(() => {
    setCustomValues({});
    let ignore = false;
    md.fieldDefinitions(form.waybillType)
      .then(defs => { if (!ignore) setCustomDefs(defs); })
      .catch(() => { if (!ignore) setCustomDefs([]); });
    return () => { ignore = true; };
  }, [form.waybillType]);

  // Второй водитель не должен совпасть с основным: при смене основного сбрасываем коллизию
  // (селект второго фильтрует основного и молча показал бы «нет», но слал бы устаревший РМА).
  useEffect(() => {
    if (intl.secondDriverRma && intl.secondDriverRma === form.driverRma) {
      setIntl(prev => ({ ...prev, secondDriverRma: '' }));
      setSelSecondDriver(null);
    }
  }, [form.driverRma, intl.secondDriverRma]);

  useEffect(() => { window.scrollTo({ top: 0 }); }, [step]);

  const t = form.waybillType;
  const isCar = t === 'WB_CAR' || t === 'WB_TAXI';
  const isTruck = t === 'WB_TRUCK';
  const isIntl = INTL_TYPES.includes(t);
  const isDangerous = t === 'WB_DANGEROUS';

  const orgLabel = orgs.find(o => o.value === orgRma)?.label ?? '';
  const vehicleLabel = selVehicle?.label ?? form.vehicleRegNumber;
  const driverLabel = selDriver ? `${selDriver.label} (${selDriver.value})` : form.driverRma;
  const typeMeta = TYPE_META[t];

  // ---- Валидация шагов ----
  const canStep2 = !!orgRma && !!form.vehicleRegNumber && !!form.driverRma;
  const intlValid = !!intl.permitNumber && !!intl.visaValidTo && !!intl.visaCountry
    && !!intl.loadCountry && !!intl.unloadCountry && (t !== 'WB_TRUCK_INTL' || !!intl.cargoName);
  const trailersValid = trailers.every(tr => tr.registrationNumber.trim() && tr.brand.trim());
  const customValid = customDefs.filter(d => d.required)
    .every(d => (customValues[d.fieldKey] ?? '').toString().trim() !== '');
  const canStep3 = (isIntl ? intlValid : true)
    && (isCar && serviceKind === 'ROUTE' ? !!form.route.trim() : true)
    && (isTruck ? trailersValid : true)
    && (isDangerous ? !!dangerous.adrClass : true)
    && customValid;

  const stepOk = (s: number) => s === 1 ? !!form.waybillType : s === 2 ? canStep2 : s === 3 ? canStep3 : true;

  // Реальные клиентские проверки готовности к отправке — то, что клиент действительно знает.
  const checks = [
    { title: tt('wb.chk.org'), sub: orgLabel || tt('wb.chk.org.no'), ok: !!orgRma },
    { title: tt('wb.chk.vehicle'), sub: vehicleLabel || tt('wb.chk.vehicle.no'), ok: !!form.vehicleRegNumber },
    { title: tt('wb.chk.driver'), sub: driverLabel || tt('wb.chk.driver.no'), ok: !!form.driverRma },
    { title: tt('wb.chk.fields'), sub: tt('wb.chk.fields.s'), ok: canStep3 },
  ];
  const clientReady = checks.every(c => c.ok);
  // Блокирующие проверки выполняет АВТОРИТЕТНО бэкенд при создании/выдаче (runBlockingChecks).
  // Показываем их честно как «проверит система», а не фейково-зелёными галочками на клиенте.
  const serverChecks = [
    tt('wb.chk.srv.license'), tt('wb.chk.srv.med'), tt('wb.chk.srv.tech'),
    tt('wb.chk.srv.noactive'), tt('wb.chk.srv.payment'),
  ];

  function buildTypeData(): Record<string, unknown> | undefined {
    if (isCar) return { serviceKind };
    if (isTruck) return { shipmentKind, ...(trailers.length ? { trailers } : {}) };
    if (isIntl) {
      return {
        visaValidTo: intl.visaValidTo,
        visaCountry: intl.visaCountry,
        loadCountry: intl.loadCountry,
        unloadCountry: intl.unloadCountry,
        ...(intl.transitCountries ? { transitCountries: intl.transitCountries.split(',').map(s => s.trim()).filter(Boolean) } : {}),
        ...(t === 'WB_TRUCK_INTL' ? { cargoName: intl.cargoName } : {}),
        permitNumber: intl.permitNumber,
        ...(intl.permitType ? { permitType: intl.permitType } : {}),
        ...(intl.bbaNumber ? { bbaNumber: intl.bbaNumber } : {}),
      };
    }
    if (isDangerous) return { adrClass: dangerous.adrClass, ...(dangerous.unNumber ? { unNumber: dangerous.unNumber } : {}) };
    return undefined;
  }

  async function submit() {
    setError('');
    setBusy(true);
    try {
      const td = buildTypeData() ?? {};
      const custom = Object.fromEntries(
        Object.entries(customValues).filter(([, v]) => v !== '' && v != null));
      if (Object.keys(custom).length) (td as Record<string, unknown>).custom = custom;
      const created = await wb.post('', {
        ...form,
        organizationRma: orgRma,
        communicationType: isIntl ? 'INTERNATIONAL' : form.communicationType,
        ...(isIntl && intl.secondDriverRma ? { secondDriverRma: intl.secondDriverRma } : {}),
        typeData: Object.keys(td).length ? td : undefined,
      });
      router.push(`/waybills/${created.id}`);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  const cur = STEPS[step - 1];

  return (
    <>
      <div className="toolbar">
        <h1>{tt('wb.new.h')}</h1>
        <div className="spacer" />
        <span className="page-lead" style={{ margin: 0 }}>{tt('wb.step')} {step} {tt('paging.of')} {STEPS.length} · {tt(cur.sub)}</span>
      </div>
      {error && <div className="error">{error}</div>}

      {/* ---------- Степпер ---------- */}
      <div className="card" style={{ padding: '18px 22px' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start' }}>
          {STEPS.map((s, i) => {
            const done = s.n < step;
            const current = s.n === step;
            return (
              <Fragment key={s.n}>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, flex: 'none', width: 150, textAlign: 'center' }}>
                  <div style={{
                    width: 40, height: 40, borderRadius: '50%', display: 'grid', placeItems: 'center',
                    fontWeight: 700, fontSize: 15, flex: 'none',
                    background: done || current ? 'var(--blue-600)' : '#fff',
                    color: done || current ? '#fff' : 'var(--muted)',
                    border: done || current ? '2px solid var(--blue-600)' : '2px solid var(--line)',
                    boxShadow: current ? '0 0 0 4px rgba(37,99,235,.16)' : 'none',
                    transition: 'all .15s',
                  }}>
                    {done ? <Icon d={P.check} cls="" style={{ width: 20, height: 20 }} /> : s.n}
                  </div>
                  <div>
                    <div style={{ fontSize: 13, fontWeight: 600, color: current ? 'var(--blue-700)' : done ? 'var(--ink)' : 'var(--muted)' }}>{tt(s.title)}</div>
                    <div style={{ fontSize: 11, color: 'var(--faint)', marginTop: 2 }}>{tt(s.sub)}</div>
                  </div>
                </div>
                {i < STEPS.length - 1 && (
                  <div style={{ flex: 1, height: 2, background: s.n < step ? 'var(--blue-600)' : 'var(--line)', marginTop: 19, transition: 'background-color .15s' }} />
                )}
              </Fragment>
            );
          })}
        </div>
      </div>

      {/* ---------- Тело шага ---------- */}
      <div style={{ display: 'grid', gridTemplateColumns: step === 4 ? '1fr' : 'minmax(0, 1fr) 340px', gap: 18, alignItems: 'start' }}>
        <div className="card">
          <h2 style={{ marginBottom: 4 }}>{tt('wb.step')} {step}. {tt(cur.title)}</h2>
          <p className="page-lead" style={{ marginBottom: 18 }}>{tt(cur.sub)}</p>

          {/* ======= ШАГ 1 — Выбор типа ======= */}
          {step === 1 && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              {Object.entries(TYPE_LABELS).map(([value]) => {
                const meta = TYPE_META[value];
                const active = form.waybillType === value;
                return (
                  <button
                    type="button"
                    key={value}
                    onClick={() => setForm({ ...form, waybillType: value })}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 13, textAlign: 'left', cursor: 'pointer',
                      padding: 14, borderRadius: 12, background: active ? 'var(--blue-050)' : '#fff', fontFamily: 'inherit',
                      border: active ? '1.5px solid var(--blue-600)' : '1.5px solid var(--line)',
                      boxShadow: active ? '0 0 0 3px rgba(37,99,235,.14)' : 'var(--shadow-xs)',
                      transition: 'border-color .12s, box-shadow .12s, background-color .12s',
                    }}
                  >
                    <div className={`ic-${meta.color}`} style={{ width: 48, height: 48, borderRadius: 12, display: 'grid', placeItems: 'center', flex: 'none' }}>
                      <Icon d={meta.icon} cls="" style={{ width: 25, height: 25 }} />
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--ink)' }}>{tType(value)}</div>
                      <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 2, lineHeight: 1.35 }}>{tt(meta.desc)}</div>
                      <span style={{ display: 'inline-block', marginTop: 8, fontSize: 10.5, fontWeight: 600, color: 'var(--ink-soft)', background: 'var(--line-soft)', padding: '3px 9px', borderRadius: 999 }}>{tt(meta.group)}</span>
                    </div>
                    <Icon d={P.chevron} cls="" style={{ width: 18, height: 18, color: active ? 'var(--blue-600)' : 'var(--faint)', flex: 'none' }} />
                  </button>
                );
              })}
            </div>
          )}

          {/* ======= ШАГ 2 — Организация, ТС, водитель ======= */}
          {step === 2 && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '15px 18px' }}>
              <div style={{ gridColumn: '1 / -1' }}>
                <label>{tt('col.org')}</label>
                <select required value={orgRma} onChange={e => { setOrgRma(e.target.value); setForm(f => ({ ...f, vehicleRegNumber: '', driverRma: '' })); setIntl(v => ({ ...v, secondDriverRma: '' })); setSelVehicle(null); setSelDriver(null); setSelSecondDriver(null); }}>
                  <option value="">{tt('wb.opt.selectorg')}</option>
                  {orgs.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </div>
              <div>
                <label>{tt('col.vehiclefull')}</label>
                <SearchSelect
                  value={form.vehicleRegNumber}
                  selectedLabel={selVehicle ? `${selVehicle.label}${selVehicle.sub ? ' — ' + selVehicle.sub : ''}` : form.vehicleRegNumber}
                  placeholder={orgRma ? tt('wb.search.vehicle') : tt('wb.opt.orgfirst')}
                  disabled={!orgRma}
                  onSearch={searchVehicles}
                  onSelect={o => { setSelVehicle(o); setForm(f => ({ ...f, vehicleRegNumber: o.value })); }}
                  onClear={() => { setSelVehicle(null); setForm(f => ({ ...f, vehicleRegNumber: '' })); }}
                  loadingText={tt('wb.search.loading')} emptyText={tt('wb.search.empty')} hintText={tt('wb.search.vehicle.hint')} />
              </div>
              <div>
                <label>{tt('col.driver')}</label>
                <SearchSelect
                  value={form.driverRma}
                  selectedLabel={selDriver ? `${selDriver.label} (${selDriver.value})` : form.driverRma}
                  placeholder={orgRma ? tt('wb.search.driver') : tt('wb.opt.orgfirst')}
                  disabled={!orgRma}
                  onSearch={searchDrivers}
                  onSelect={o => { setSelDriver(o); setForm(f => ({ ...f, driverRma: o.value })); }}
                  onClear={() => { setSelDriver(null); setForm(f => ({ ...f, driverRma: '' })); }}
                  loadingText={tt('wb.search.loading')} emptyText={tt('wb.search.empty')} hintText={tt('wb.search.driver.hint')} />
              </div>
            </div>
          )}

          {/* ======= ШАГ 3 — Маршрут и параметры ======= */}
          {step === 3 && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '15px 18px' }}>
              <div>
                <label>{tt('wb.f.commtype')}</label>
                <select disabled={isIntl} value={isIntl ? 'INTERNATIONAL' : form.communicationType}
                  onChange={e => setForm({ ...form, communicationType: e.target.value })}>
                  <option value="URBAN">{tt('wb.comm.urban')}</option>
                  <option value="SUBURBAN">{tt('wb.comm.suburban')}</option>
                  <option value="INTERCITY">{tt('wb.comm.intercity')}</option>
                  <option value="INTERNATIONAL">{tt('wb.comm.intl')}</option>
                </select>
              </div>

              {/* --- 3-С: вид услуги --- */}
              {isCar && (
                <div>
                  <label>{tt('wb.f.servicekind')}</label>
                  <select value={serviceKind} onChange={e => setServiceKind(e.target.value)}>
                    <option value="TAXI">{tt('wb.svc.taxi')}</option>
                    <option value="ROUTE">{tt('wb.svc.route')}</option>
                    <option value="HOURLY">{tt('wb.svc.hourly')}</option>
                  </select>
                </div>
              )}

              {/* --- 2-Б: вид перевозки --- */}
              {isTruck && (
                <div>
                  <label>{tt('wb.f.shipmentkind')}</label>
                  <select value={shipmentKind} onChange={e => setShipmentKind(e.target.value)}>
                    <option value="PIECEWORK">{tt('wb.ship.piecework')}</option>
                    <option value="HOURLY">{tt('wb.ship.hourly')}</option>
                  </select>
                </div>
              )}

              <div>
                <label>{tt('wb.f.route')}{isCar && serviceKind === 'ROUTE' ? tt('wb.required.suffix') : ''}</label>
                <input required={isCar && serviceKind === 'ROUTE'} value={form.route}
                  onChange={e => setForm({ ...form, route: e.target.value })} placeholder={tt('wb.ph.route')} />
              </div>
              <div>
                <label>{tt('wb.f.schedule')}</label>
                <input value={form.schedule} onChange={e => setForm({ ...form, schedule: e.target.value })} placeholder={tt('wb.ph.schedule')} />
              </div>

              {/* --- 2-Б: прицепы --- */}
              {isTruck && (
                <div style={{ gridColumn: '1 / -1' }}>
                  <label>{tt('wb.f.trailers')}</label>
                  {trailers.map((tr, i) => (
                    <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
                      <input placeholder={tt('wb.ph.trailernum')} required value={tr.registrationNumber}
                        onChange={e => setTrailers(ts => ts.map((x, j) => j === i ? { ...x, registrationNumber: e.target.value } : x))} />
                      <input placeholder={tt('col.brand')} required value={tr.brand}
                        onChange={e => setTrailers(ts => ts.map((x, j) => j === i ? { ...x, brand: e.target.value } : x))} />
                      <button type="button" className="btn danger" onClick={() => setTrailers(ts => ts.filter((_, j) => j !== i))}>✗</button>
                    </div>
                  ))}
                  {trailers.length < 2 && (
                    <button type="button" className="btn secondary" onClick={() => setTrailers(ts => [...ts, { registrationNumber: '', brand: '' }])}>
                      {tt('wb.btn.addtrailer')}
                    </button>
                  )}
                </div>
              )}

              {/* --- Опасные грузы: класс ADR --- */}
              {isDangerous && (
                <>
                  <div>
                    <label>{tt('wb.f.adrclass')}{tt('wb.required.suffix')}</label>
                    <select required value={dangerous.adrClass} onChange={e => setDangerous({ ...dangerous, adrClass: e.target.value })}>
                      <option value="">{tt('wb.opt.adr')}</option>
                      {adrClasses.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
                    </select>
                  </div>
                  <div>
                    <label>{tt('wb.f.unnumber')}</label>
                    <input placeholder="UN 1203" value={dangerous.unNumber} onChange={e => setDangerous({ ...dangerous, unNumber: e.target.value })} />
                  </div>
                </>
              )}

              {/* --- Международные: 5Б-БМ / 4М-БМ --- */}
              {isIntl && (
                <>
                  <div>
                    <label>{tt('wb.f.seconddriver')}</label>
                    <SearchSelect
                      value={intl.secondDriverRma}
                      selectedLabel={selSecondDriver ? `${selSecondDriver.label} (${selSecondDriver.value})` : intl.secondDriverRma}
                      placeholder={tt('wb.search.driver')}
                      disabled={!orgRma}
                      onSearch={async q => (await searchDrivers(q)).filter(d => d.value !== form.driverRma)}
                      onSelect={o => { setSelSecondDriver(o); setIntl(v => ({ ...v, secondDriverRma: o.value })); }}
                      onClear={() => { setSelSecondDriver(null); setIntl(v => ({ ...v, secondDriverRma: '' })); }}
                      loadingText={tt('wb.search.loading')} emptyText={tt('wb.search.empty')} hintText={tt('wb.search.driver.hint')} />
                  </div>
                  <div>
                    <label>{tt('wb.f.permit')}</label>
                    <input required placeholder="EP-2026-..." value={intl.permitNumber} onChange={e => setIntl({ ...intl, permitNumber: e.target.value })} />
                  </div>
                  <div>
                    <label>{tt('wb.f.permittype')}</label>
                    <select value={intl.permitType} onChange={e => setIntl({ ...intl, permitType: e.target.value })}>
                      <option value="">{tt('wb.opt.none')}</option>
                      {permitTypes.map(p => <option key={p.value} value={p.value}>{p.label}</option>)}
                    </select>
                  </div>
                  <div>
                    <label>{tt('wb.f.visato')}</label>
                    <input type="date" required value={intl.visaValidTo} onChange={e => setIntl({ ...intl, visaValidTo: e.target.value })} />
                  </div>
                  <div>
                    <label>{tt('wb.f.visacountry')}</label>
                    <select required value={intl.visaCountry} onChange={e => setIntl({ ...intl, visaCountry: e.target.value })}>
                      <option value="">{tt('wb.opt.country')}</option>
                      {countries.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
                    </select>
                  </div>
                  <div>
                    <label>{tt('wb.f.loadcountry')}</label>
                    <select required value={intl.loadCountry} onChange={e => setIntl({ ...intl, loadCountry: e.target.value })}>
                      <option value="">{tt('wb.opt.country')}</option>
                      {countries.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
                    </select>
                  </div>
                  <div>
                    <label>{tt('wb.f.unloadcountry')}</label>
                    <select required value={intl.unloadCountry} onChange={e => setIntl({ ...intl, unloadCountry: e.target.value })}>
                      <option value="">{tt('wb.opt.country')}</option>
                      {countries.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
                    </select>
                  </div>
                  <div>
                    <label>{tt('wb.f.transitcountries')}</label>
                    <select multiple
                      value={intl.transitCountries ? intl.transitCountries.split(',').map(s => s.trim()).filter(Boolean) : []}
                      onChange={e => setIntl({ ...intl, transitCountries: Array.from(e.target.selectedOptions).map(o => o.value).join(', ') })}
                      style={{ minHeight: 92 }}>
                      {countries.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
                    </select>
                  </div>
                  {t === 'WB_TRUCK_INTL' && (
                    <>
                      <div>
                        <label>{tt('wb.f.cargoname')}</label>
                        <input required placeholder={tt('wb.ph.cotton')} value={intl.cargoName} onChange={e => setIntl({ ...intl, cargoName: e.target.value })} />
                      </div>
                      <div>
                        <label>{tt('wb.f.bba')}</label>
                        <input placeholder="XB 1234567" value={intl.bbaNumber} onChange={e => setIntl({ ...intl, bbaNumber: e.target.value })} />
                      </div>
                    </>
                  )}
                </>
              )}

              {/* --- Доп.поля типа (конструктор полей) --- */}
              {customDefs.length > 0 && (
                <div style={{ gridColumn: '1 / -1', borderTop: '1px solid var(--line)', paddingTop: 10, marginTop: 2, fontSize: 12.5, fontWeight: 700, color: 'var(--muted)' }}>
                  {tt('fld.section')}
                </div>
              )}
              {customDefs.map(d => (
                <div key={d.id}>
                  <label>{d.labelRu}{d.required ? tt('wb.required.suffix') : ''}</label>
                  {d.dataType === 'BOOLEAN' ? (
                    <select value={customValues[d.fieldKey] ?? ''} onChange={e => setCustomValues(v => ({ ...v, [d.fieldKey]: e.target.value }))}>
                      <option value="">—</option>
                      <option value="true">{tt('fld.yes')}</option>
                      <option value="false">{tt('fld.no')}</option>
                    </select>
                  ) : d.dataType === 'ENUM' ? (
                    <select required={d.required} value={customValues[d.fieldKey] ?? ''} onChange={e => setCustomValues(v => ({ ...v, [d.fieldKey]: e.target.value }))}>
                      <option value="">—</option>
                      {(d.options ?? '').split(',').map(o => o.trim()).filter(Boolean).map(o => <option key={o} value={o}>{o}</option>)}
                    </select>
                  ) : (
                    <input type={d.dataType === 'NUMBER' ? 'number' : d.dataType === 'DATE' ? 'date' : 'text'}
                      required={d.required} value={customValues[d.fieldKey] ?? ''}
                      onChange={e => setCustomValues(v => ({ ...v, [d.fieldKey]: e.target.value }))} />
                  )}
                </div>
              ))}
            </div>
          )}

          {/* ======= ШАГ 4 — Проверка и создание ======= */}
          {step === 4 && (
            <>
              <div className="sys-ok" style={{ marginTop: 0, marginBottom: 18 }}>
                <Icon d={P.check} cls="" style={{ width: 16, height: 16 }} />
                {tt('wb.readynote')}
              </div>
              <dl className="kv">
                <dt>{tt('wb.sum.type')}</dt>
                <dd style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  {typeMeta && <span className={`ic-${typeMeta.color}`} style={{ width: 26, height: 26, borderRadius: 7, display: 'grid', placeItems: 'center', flex: 'none' }}><Icon d={typeMeta.icon} cls="" style={{ width: 16, height: 16 }} /></span>}
                  <b>{tType(t)}</b>
                </dd>
                <dt>{tt('col.org')}</dt><dd>{orgLabel || '—'}</dd>
                <dt>{tt('col.vehiclefull')}</dt><dd>{vehicleLabel || '—'}</dd>
                <dt>{tt('col.driver')}</dt><dd>{driverLabel || '—'}</dd>
                <dt>{tt('wb.f.commtype')}</dt>
                <dd>{isIntl ? tt('wb.comm.intl') : tt(({ URBAN: 'wb.comm.urban', SUBURBAN: 'wb.comm.suburban', INTERCITY: 'wb.comm.intercity', INTERNATIONAL: 'wb.comm.intl' } as Record<string, string>)[form.communicationType])}</dd>
                <dt>{tt('col.route')}</dt><dd>{form.route || '—'}</dd>
                <dt>{tt('wb.schedule')}</dt><dd>{form.schedule || '—'}</dd>
                {isCar && (<><dt>{tt('wb.svc.label')}</dt><dd>{tt(({ TAXI: 'wb.svc.taxi', ROUTE: 'wb.svc.route', HOURLY: 'wb.svc.hourly' } as Record<string, string>)[serviceKind])}</dd></>)}
                {isTruck && (<><dt>{tt('wb.ship.label')}</dt><dd>{shipmentKind === 'HOURLY' ? tt('wb.ship.hourly') : tt('wb.ship.piecework')}{trailers.length ? ` · ${tt('wb.trailerscount')}: ${trailers.length}` : ''}</dd></>)}
                {isIntl && (<><dt>{tt('wb.intl.trip')}</dt><dd>{intl.loadCountry || '—'} → {intl.unloadCountry || '—'} · {tt('wb.permit.short')} {intl.permitNumber || '—'}{intl.secondDriverRma ? tt('wb.withsecond') : ''}</dd></>)}
              </dl>
            </>
          )}

          {/* ---------- Навигация ---------- */}
          <div style={{ display: 'flex', gap: 8, marginTop: 24, paddingTop: 18, borderTop: '1px solid var(--line-soft)' }}>
            <button type="button" className="btn secondary" disabled={step === 1} onClick={() => setStep(s => Math.max(1, s - 1))}>
              <Icon d={P.collapse} cls="" style={{ width: 16, height: 16 }} /> {tt('wb.btn.back')}
            </button>
            <div style={{ flex: 1 }} />
            {step < STEPS.length ? (
              <button type="button" className="btn" disabled={!stepOk(step)} onClick={() => setStep(s => Math.min(STEPS.length, s + 1))}>
                {tt('wb.btn.next')} <Icon d={P.chevron} cls="" style={{ width: 16, height: 16 }} />
              </button>
            ) : (
              <button type="button" className="btn" disabled={busy || !canStep2 || !canStep3} onClick={submit}>
                {busy ? tt('wb.btn.creating') : tt('wb.btn.create')}
              </button>
            )}
          </div>
        </div>

        {/* ---------- Правая колонка ---------- */}
        {step !== 4 && (
          <aside style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            {step === 1 && (
              <>
                <div className="card" style={{ marginBottom: 0 }}>
                  <div className="card-h"><h2>{tt('wb.side.params')}</h2></div>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
                    <span style={{ color: 'var(--muted)', fontSize: 13 }}>{tt('col.org')}</span>
                    {orgRma
                      ? <span className="badge green">{tt('wb.chosen')}</span>
                      : <span className="badge gray">{tt('wb.onstep2')}</span>}
                  </div>
                  <div style={{ fontSize: 13.5, color: 'var(--ink)', fontWeight: 600 }}>{orgLabel || tt('wb.willchoose')}</div>
                </div>
                <div className="card" style={{ marginBottom: 0 }}>
                  <div className="card-h"><h2>{tt('wb.side.rules')}</h2></div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                    {[
                      { ic: P.help, tx: tt('wb.hint.type') },
                      { ic: P.shield, tx: tt('wb.hint.change') },
                      { ic: P.doc, tx: tt('wb.hint.norms') },
                    ].map((r, i) => (
                      <div key={i} style={{ display: 'flex', gap: 10, fontSize: 12.5, color: 'var(--ink-soft)' }}>
                        <span className="ic-blue" style={{ width: 28, height: 28, borderRadius: 8, display: 'grid', placeItems: 'center', flex: 'none' }}><Icon d={r.ic} cls="" style={{ width: 15, height: 15 }} /></span>
                        <span style={{ lineHeight: 1.4 }}>{r.tx}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </>
            )}

            {step === 2 && (
              <div className="card" style={{ marginBottom: 0 }}>
                <div className="card-h"><h2>{tt('wb.side.selected')}</h2></div>
                <div style={{ marginBottom: 14 }}>
                  <div style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 4 }}>{tt('col.vehiclefull')}</div>
                  {form.vehicleRegNumber
                    ? <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--ink)' }}>{vehicleLabel}</div>
                    : <div style={{ fontSize: 13, color: 'var(--faint)' }}>{tt('wb.notchosen.veh')}</div>}
                </div>
                <div style={{ marginBottom: 14 }}>
                  <div style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 4 }}>{tt('col.driver')}</div>
                  {form.driverRma
                    ? <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--ink)' }}>{driverLabel}</div>
                    : <div style={{ fontSize: 13, color: 'var(--faint)' }}>{tt('wb.notchosen.drv')}</div>}
                </div>
                <div className={canStep2 ? 'sys-ok' : ''} style={canStep2 ? {} : { fontSize: 12.5, color: 'var(--muted)' }}>
                  {canStep2 ? (<><Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> {tt('wb.vehdrv.ok')}</>) : tt('wb.vehdrv.choose')}
                </div>
              </div>
            )}

            {step === 3 && (
              <div className="card" style={{ marginBottom: 0 }}>
                <div className="card-h">
                  <h2>{tt('wb.side.autocheck')}</h2>
                  {clientReady && <span className="badge green" style={{ marginLeft: 'auto' }}>{tt('wb.ready')}</span>}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                  {checks.map((c, i) => (
                    <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '10px 0', borderBottom: i < checks.length - 1 ? '1px solid var(--line-soft)' : 'none' }}>
                      <span className={c.ok ? 'ic-green' : 'ic-amber'} style={{ width: 30, height: 30, borderRadius: '50%', display: 'grid', placeItems: 'center', flex: 'none' }}>
                        <Icon d={c.ok ? P.check : P.alert} cls="" style={{ width: 16, height: 16 }} />
                      </span>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--ink)' }}>{c.title}</div>
                        <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{c.sub}</div>
                      </div>
                    </div>
                  ))}
                </div>
                {/* Честный список: авторитетные блокирующие проверки бэкенда при выдаче. */}
                <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--line-soft)' }}>
                  <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.03em', marginBottom: 8 }}>{tt('wb.chk.srv.h')}</div>
                  {serverChecks.map((sc, i) => (
                    <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '5px 0', fontSize: 12, color: 'var(--ink-soft)' }}>
                      <Icon d={P.shield} cls="" style={{ width: 14, height: 14, color: 'var(--blue-600)', flex: 'none' }} />
                      {sc}
                    </div>
                  ))}
                  <div className="hint" style={{ marginTop: 8, fontSize: 11 }}>{tt('wb.chk.srv.note')}</div>
                </div>
              </div>
            )}
          </aside>
        )}
      </div>
    </>
  );
}
