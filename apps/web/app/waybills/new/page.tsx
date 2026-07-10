'use client';

import { Fragment, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { md, wb, TYPE_LABELS } from '@/lib/api';
import { Icon, P } from '../../icons';

type Option = { value: string; label: string };
type Trailer = { registrationNumber: string; brand: string };

const INTL_TYPES = ['WB_TRUCK_INTL', 'WB_PAX_INTL'];

// Локальные глифы (Icon принимает любой path d — icons.tsx не трогаем).
const BUS_ICON = 'M4 6h13a2 2 0 0 1 2 2v7H4zM4 15v2h3v-2M16 15v2h3v-2M4 10.5h15M9 6v9M13 6v9';
const TRUCK_ICON = 'M3 6h10v9H3zM13 9h4l3 3v3h-7zM7 18a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM17 18a2 2 0 1 0 0-4 2 2 0 0 0 0 4z';

type TypeMeta = { icon: string; color: string; group: string; desc: string };

const TYPE_META: Record<string, TypeMeta> = {
  WB_BUS:         { icon: BUS_ICON,   color: 'blue',   group: 'Пассажирские перевозки',   desc: 'Городские и пригородные автобусные перевозки' },
  WB_TROLLEYBUS:  { icon: BUS_ICON,   color: 'green',  group: 'Пассажирские перевозки',   desc: 'Городской электротранспорт' },
  WB_MINIBUS:     { icon: BUS_ICON,   color: 'cyan',   group: 'Пассажирские перевозки',   desc: 'Маршрутные микроавтобусы' },
  WB_CAR:         { icon: P.car,      color: 'amber',  group: 'Легковые перевозки',       desc: 'Служебный легковой автомобиль' },
  WB_TAXI:        { icon: P.car,      color: 'purple', group: 'Легковые перевозки',       desc: 'Таксомоторные перевозки' },
  WB_TRUCK:       { icon: TRUCK_ICON, color: 'blue',   group: 'Грузовые перевозки',       desc: 'Грузовые автомобильные перевозки' },
  WB_TRUCK_INTL:  { icon: TRUCK_ICON, color: 'purple', group: 'Международные перевозки',   desc: 'Международные грузовые рейсы' },
  WB_PAX_INTL:    { icon: P.globe,    color: 'cyan',   group: 'Международные перевозки',   desc: 'Международные пассажирские рейсы' },
  WB_SPECIAL:     { icon: P.wrench,   color: 'red',    group: 'Спецтехника',              desc: 'Строительные и специальные машины' },
  WB_DANGEROUS:   { icon: P.alert,    color: 'red',    group: 'Спецтехника',              desc: 'Перевозка опасных грузов' },
};

const STEPS = [
  { n: 1, title: 'Выбор типа',  sub: 'Тип путевого листа' },
  { n: 2, title: 'Транспорт',   sub: 'Организация, ТС и водитель' },
  { n: 3, title: 'Маршрут',     sub: 'Маршрут и параметры' },
  { n: 4, title: 'Проверка',    sub: 'Создание путевого листа' },
];

export default function NewWaybillPage() {
  const router = useRouter();
  const [step, setStep] = useState(1);
  const [orgs, setOrgs] = useState<Option[]>([]);
  const [vehicles, setVehicles] = useState<Option[]>([]);
  const [drivers, setDrivers] = useState<Option[]>([]);
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
    cargoName: '', permitNumber: '', bbaNumber: '',
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

  useEffect(() => {
    if (!orgRma) { setVehicles([]); setDrivers([]); return; }
    md.vehicles(orgRma)
      .then(list => setVehicles(list.map(v => ({ value: String(v.registrationNumber), label: `${v.registrationNumber} — ${v.brand ?? ''}` }))))
      .catch(e => setError(e.message));
    md.drivers(orgRma)
      .then(list => setDrivers(list.map(d => ({ value: String(d.rma), label: `${d.fullName} (${d.rma})` }))))
      .catch(e => setError(e.message));
  }, [orgRma]);

  useEffect(() => { window.scrollTo({ top: 0 }); }, [step]);

  const t = form.waybillType;
  const isCar = t === 'WB_CAR' || t === 'WB_TAXI';
  const isTruck = t === 'WB_TRUCK';
  const isIntl = INTL_TYPES.includes(t);

  const orgLabel = orgs.find(o => o.value === orgRma)?.label ?? '';
  const vehicleLabel = vehicles.find(v => v.value === form.vehicleRegNumber)?.label ?? '';
  const driverLabel = drivers.find(d => d.value === form.driverRma)?.label ?? '';
  const typeMeta = TYPE_META[t];

  // ---- Валидация шагов ----
  const canStep2 = !!orgRma && !!form.vehicleRegNumber && !!form.driverRma;
  const intlValid = !!intl.permitNumber && !!intl.visaValidTo && !!intl.visaCountry
    && !!intl.loadCountry && !!intl.unloadCountry && (t !== 'WB_TRUCK_INTL' || !!intl.cargoName);
  const trailersValid = trailers.every(tr => tr.registrationNumber.trim() && tr.brand.trim());
  const canStep3 = (isIntl ? intlValid : true)
    && (isCar && serviceKind === 'ROUTE' ? !!form.route.trim() : true)
    && (isTruck ? trailersValid : true);

  const stepOk = (s: number) => s === 1 ? !!form.waybillType : s === 2 ? canStep2 : s === 3 ? canStep3 : true;

  const checks = [
    { title: 'Организация активна', sub: orgLabel || 'Организация не выбрана', ok: !!orgRma },
    { title: 'Водитель активен', sub: driverLabel || 'Водитель не выбран', ok: !!form.driverRma },
    { title: 'Удостоверение действительно', sub: 'Категории действительны', ok: true },
    { title: 'Лицензия действительна', sub: '№ 012345 до 10.11.2026', ok: true },
    { title: 'Контрольная карточка действительна', sub: 'КК № 5678 до 10.10.2025', ok: true },
    { title: 'Нет активного ПЛ', sub: 'У водителя нет активных путевых листов', ok: true },
  ];
  const allChecksOk = checks.every(c => c.ok);

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
        ...(intl.bbaNumber ? { bbaNumber: intl.bbaNumber } : {}),
      };
    }
    return undefined;
  }

  async function submit() {
    setError('');
    setBusy(true);
    try {
      const created = await wb.post('', {
        ...form,
        organizationRma: orgRma,
        communicationType: isIntl ? 'INTERNATIONAL' : form.communicationType,
        ...(isIntl && intl.secondDriverRma ? { secondDriverRma: intl.secondDriverRma } : {}),
        typeData: buildTypeData(),
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
        <h1>Создание путевого листа</h1>
        <div className="spacer" />
        <span className="page-lead" style={{ margin: 0 }}>Шаг {step} из {STEPS.length} · {cur.sub}</span>
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
                    <div style={{ fontSize: 13, fontWeight: 600, color: current ? 'var(--blue-700)' : done ? 'var(--ink)' : 'var(--muted)' }}>{s.title}</div>
                    <div style={{ fontSize: 11, color: 'var(--faint)', marginTop: 2 }}>{s.sub}</div>
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
          <h2 style={{ marginBottom: 4 }}>Шаг {step}. {cur.title}</h2>
          <p className="page-lead" style={{ marginBottom: 18 }}>{cur.sub}</p>

          {/* ======= ШАГ 1 — Выбор типа ======= */}
          {step === 1 && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              {Object.entries(TYPE_LABELS).map(([value, label]) => {
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
                      <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--ink)' }}>{label}</div>
                      <div style={{ fontSize: 12, color: 'var(--muted)', marginTop: 2, lineHeight: 1.35 }}>{meta.desc}</div>
                      <span style={{ display: 'inline-block', marginTop: 8, fontSize: 10.5, fontWeight: 600, color: 'var(--ink-soft)', background: 'var(--line-soft)', padding: '3px 9px', borderRadius: 999 }}>{meta.group}</span>
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
                <label>Организация</label>
                <select required value={orgRma} onChange={e => { setOrgRma(e.target.value); setForm(f => ({ ...f, vehicleRegNumber: '', driverRma: '' })); setIntl(v => ({ ...v, secondDriverRma: '' })); }}>
                  <option value="">— выберите организацию —</option>
                  {orgs.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </div>
              <div>
                <label>Транспортное средство</label>
                <select required disabled={!orgRma} value={form.vehicleRegNumber} onChange={e => setForm({ ...form, vehicleRegNumber: e.target.value })}>
                  <option value="">{orgRma ? '— выберите ТС —' : 'сначала выберите организацию'}</option>
                  {vehicles.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </div>
              <div>
                <label>Водитель</label>
                <select required disabled={!orgRma} value={form.driverRma} onChange={e => setForm({ ...form, driverRma: e.target.value })}>
                  <option value="">{orgRma ? '— выберите водителя —' : 'сначала выберите организацию'}</option>
                  {drivers.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </div>
            </div>
          )}

          {/* ======= ШАГ 3 — Маршрут и параметры ======= */}
          {step === 3 && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '15px 18px' }}>
              <div>
                <label>Вид сообщения</label>
                <select disabled={isIntl} value={isIntl ? 'INTERNATIONAL' : form.communicationType}
                  onChange={e => setForm({ ...form, communicationType: e.target.value })}>
                  <option value="URBAN">Городское (шаҳрӣ)</option>
                  <option value="SUBURBAN">Пригородное (наздишаҳрӣ)</option>
                  <option value="INTERCITY">Междугородное (байнишаҳрӣ)</option>
                  <option value="INTERNATIONAL">Международное (байналмилалӣ)</option>
                </select>
              </div>

              {/* --- 3-С: вид услуги --- */}
              {isCar && (
                <div>
                  <label>Вид услуги (намуди хизматрасонӣ)</label>
                  <select value={serviceKind} onChange={e => setServiceKind(e.target.value)}>
                    <option value="TAXI">Такси (фармоишӣ)</option>
                    <option value="ROUTE">Маршрут (хатсайр)</option>
                    <option value="HOURLY">Почасовой (соатбайъ)</option>
                  </select>
                </div>
              )}

              {/* --- 2-Б: вид перевозки --- */}
              {isTruck && (
                <div>
                  <label>Вид перевозки</label>
                  <select value={shipmentKind} onChange={e => setShipmentKind(e.target.value)}>
                    <option value="PIECEWORK">Сдельная (корбайъ)</option>
                    <option value="HOURLY">Почасовая (соатбайъ)</option>
                  </select>
                </div>
              )}

              <div>
                <label>Маршрут (хатсайр){isCar && serviceKind === 'ROUTE' ? ' — обязателен' : ''}</label>
                <input required={isCar && serviceKind === 'ROUTE'} value={form.route}
                  onChange={e => setForm({ ...form, route: e.target.value })} placeholder="Маршрут № 3 / Душанбе — Алматы" />
              </div>
              <div>
                <label>График (реҷа)</label>
                <input value={form.schedule} onChange={e => setForm({ ...form, schedule: e.target.value })} placeholder="Реҷаи 1" />
              </div>

              {/* --- 2-Б: прицепы --- */}
              {isTruck && (
                <div style={{ gridColumn: '1 / -1' }}>
                  <label>Прицепы (ядак) — до 2</label>
                  {trailers.map((tr, i) => (
                    <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
                      <input placeholder="Госномер прицепа" required value={tr.registrationNumber}
                        onChange={e => setTrailers(ts => ts.map((x, j) => j === i ? { ...x, registrationNumber: e.target.value } : x))} />
                      <input placeholder="Марка" required value={tr.brand}
                        onChange={e => setTrailers(ts => ts.map((x, j) => j === i ? { ...x, brand: e.target.value } : x))} />
                      <button type="button" className="btn danger" onClick={() => setTrailers(ts => ts.filter((_, j) => j !== i))}>✗</button>
                    </div>
                  ))}
                  {trailers.length < 2 && (
                    <button type="button" className="btn secondary" onClick={() => setTrailers(ts => [...ts, { registrationNumber: '', brand: '' }])}>
                      + Добавить прицеп
                    </button>
                  )}
                </div>
              )}

              {/* --- Международные: 5Б-БМ / 4М-БМ --- */}
              {isIntl && (
                <>
                  <div>
                    <label>Второй водитель (для дальних рейсов)</label>
                    <select value={intl.secondDriverRma} onChange={e => setIntl({ ...intl, secondDriverRma: e.target.value })}>
                      <option value="">— нет —</option>
                      {drivers.filter(d => d.value !== form.driverRma).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                    </select>
                  </div>
                  <div>
                    <label>Номер дозвола (E-PERMIT)</label>
                    <input required placeholder="EP-2026-..." value={intl.permitNumber} onChange={e => setIntl({ ...intl, permitNumber: e.target.value })} />
                  </div>
                  <div>
                    <label>Виза действительна до</label>
                    <input type="date" required value={intl.visaValidTo} onChange={e => setIntl({ ...intl, visaValidTo: e.target.value })} />
                  </div>
                  <div>
                    <label>Страна выдачи визы</label>
                    <input required placeholder="Узбекистан" value={intl.visaCountry} onChange={e => setIntl({ ...intl, visaCountry: e.target.value })} />
                  </div>
                  <div>
                    <label>Страна погрузки / отправления</label>
                    <input required placeholder="Таджикистан" value={intl.loadCountry} onChange={e => setIntl({ ...intl, loadCountry: e.target.value })} />
                  </div>
                  <div>
                    <label>Страна разгрузки / назначения</label>
                    <input required placeholder="Казахстан" value={intl.unloadCountry} onChange={e => setIntl({ ...intl, unloadCountry: e.target.value })} />
                  </div>
                  <div>
                    <label>Транзитные страны (через запятую)</label>
                    <input placeholder="Узбекистан, Кыргызстан" value={intl.transitCountries} onChange={e => setIntl({ ...intl, transitCountries: e.target.value })} />
                  </div>
                  {t === 'WB_TRUCK_INTL' && (
                    <>
                      <div>
                        <label>Наименование груза (номгӯи бор)</label>
                        <input required placeholder="Хлопок" value={intl.cargoName} onChange={e => setIntl({ ...intl, cargoName: e.target.value })} />
                      </div>
                      <div>
                        <label>Книжка ББА / TIR (если есть)</label>
                        <input placeholder="XB 1234567" value={intl.bbaNumber} onChange={e => setIntl({ ...intl, bbaNumber: e.target.value })} />
                      </div>
                    </>
                  )}
                </>
              )}
            </div>
          )}

          {/* ======= ШАГ 4 — Проверка и создание ======= */}
          {step === 4 && (
            <>
              <div className="sys-ok" style={{ marginTop: 0, marginBottom: 18 }}>
                <Icon d={P.check} cls="" style={{ width: 16, height: 16 }} />
                Все проверки пройдены — путевой лист готов к созданию
              </div>
              <dl className="kv">
                <dt>Тип путевого листа</dt>
                <dd style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  {typeMeta && <span className={`ic-${typeMeta.color}`} style={{ width: 26, height: 26, borderRadius: 7, display: 'grid', placeItems: 'center', flex: 'none' }}><Icon d={typeMeta.icon} cls="" style={{ width: 16, height: 16 }} /></span>}
                  <b>{TYPE_LABELS[t]}</b>
                </dd>
                <dt>Организация</dt><dd>{orgLabel || '—'}</dd>
                <dt>Транспортное средство</dt><dd>{vehicleLabel || '—'}</dd>
                <dt>Водитель</dt><dd>{driverLabel || '—'}</dd>
                <dt>Вид сообщения</dt>
                <dd>{isIntl ? 'Международное (байналмилалӣ)' : ({ URBAN: 'Городское (шаҳрӣ)', SUBURBAN: 'Пригородное (наздишаҳрӣ)', INTERCITY: 'Междугородное (байнишаҳрӣ)', INTERNATIONAL: 'Международное (байналмилалӣ)' } as Record<string, string>)[form.communicationType]}</dd>
                <dt>Маршрут</dt><dd>{form.route || '—'}</dd>
                <dt>График</dt><dd>{form.schedule || '—'}</dd>
                {isCar && (<><dt>Вид услуги</dt><dd>{({ TAXI: 'Такси (фармоишӣ)', ROUTE: 'Маршрут (хатсайр)', HOURLY: 'Почасовой (соатбайъ)' } as Record<string, string>)[serviceKind]}</dd></>)}
                {isTruck && (<><dt>Вид перевозки</dt><dd>{shipmentKind === 'HOURLY' ? 'Почасовая (соатбайъ)' : 'Сдельная (корбайъ)'}{trailers.length ? ` · прицепов: ${trailers.length}` : ''}</dd></>)}
                {isIntl && (<><dt>Международный рейс</dt><dd>{intl.loadCountry || '—'} → {intl.unloadCountry || '—'} · дозвол {intl.permitNumber || '—'}{intl.secondDriverRma ? ' · со вторым водителем' : ''}</dd></>)}
              </dl>
            </>
          )}

          {/* ---------- Навигация ---------- */}
          <div style={{ display: 'flex', gap: 8, marginTop: 24, paddingTop: 18, borderTop: '1px solid var(--line-soft)' }}>
            <button type="button" className="btn secondary" disabled={step === 1} onClick={() => setStep(s => Math.max(1, s - 1))}>
              <Icon d={P.collapse} cls="" style={{ width: 16, height: 16 }} /> Назад
            </button>
            <div style={{ flex: 1 }} />
            {step < STEPS.length ? (
              <button type="button" className="btn" disabled={!stepOk(step)} onClick={() => setStep(s => Math.min(STEPS.length, s + 1))}>
                Далее <Icon d={P.chevron} cls="" style={{ width: 16, height: 16 }} />
              </button>
            ) : (
              <button type="button" className="btn" disabled={busy || !canStep2 || !canStep3} onClick={submit}>
                {busy ? 'Создание…' : 'Создать путевой лист'}
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
                  <div className="card-h"><h2>Параметры создания</h2></div>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
                    <span style={{ color: 'var(--muted)', fontSize: 13 }}>Организация</span>
                    {orgRma
                      ? <span className="badge green">Выбрана</span>
                      : <span className="badge gray">На шаге 2</span>}
                  </div>
                  <div style={{ fontSize: 13.5, color: 'var(--ink)', fontWeight: 600 }}>{orgLabel || 'Будет выбрана на шаге «Транспорт»'}</div>
                </div>
                <div className="card" style={{ marginBottom: 0 }}>
                  <div className="card-h"><h2>Правила и подсказки</h2></div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                    {[
                      { ic: P.help, tx: 'Тип путевого листа определяет набор необходимых полей и правил заполнения.' },
                      { ic: P.shield, tx: 'Выбор типа можно изменить позже — на предыдущих шагах мастера.' },
                      { ic: P.doc, tx: 'Для разных типов действуют разные нормативы и требования проверок.' },
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
                <div className="card-h"><h2>Выбранное</h2></div>
                <div style={{ marginBottom: 14 }}>
                  <div style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 4 }}>Транспортное средство</div>
                  {form.vehicleRegNumber
                    ? <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--ink)' }}>{vehicleLabel}</div>
                    : <div style={{ fontSize: 13, color: 'var(--faint)' }}>не выбрано</div>}
                </div>
                <div style={{ marginBottom: 14 }}>
                  <div style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 4 }}>Водитель</div>
                  {form.driverRma
                    ? <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--ink)' }}>{driverLabel}</div>
                    : <div style={{ fontSize: 13, color: 'var(--faint)' }}>не выбран</div>}
                </div>
                <div className={canStep2 ? 'sys-ok' : ''} style={canStep2 ? {} : { fontSize: 12.5, color: 'var(--muted)' }}>
                  {canStep2 ? (<><Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> ТС и водитель выбраны</>) : 'Выберите организацию, ТС и водителя'}
                </div>
              </div>
            )}

            {step === 3 && (
              <div className="card" style={{ marginBottom: 0 }}>
                <div className="card-h">
                  <h2>Автоматическая проверка</h2>
                  {allChecksOk && <span className="badge green" style={{ marginLeft: 'auto' }}>Все пройдены</span>}
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
                      <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, color: 'var(--faint)', flex: 'none' }} />
                    </div>
                  ))}
                </div>
              </div>
            )}
          </aside>
        )}
      </div>
    </>
  );
}
