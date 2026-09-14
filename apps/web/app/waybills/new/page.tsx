'use client';

import { Fragment, useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { md, wb, TYPE_LABELS, type FieldDefinition, type Eligibility, type TypeAvailability, type Client, type Direction, type RouteType } from '@/lib/api';
import { Icon, P } from '../../icons';
import { SearchSelect, type SSOption } from '../../SearchSelect';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { canCreateWaybill } from '@/lib/roles';

type Option = { value: string; label: string };
type Trailer = { registrationNumber: string; brand: string };

const INTL_TYPES = ['WB_TRUCK_INTL', 'WB_PAX_INTL'];
// Пассажирские типы (для необязательного поля «Тип маршрута»): автобус/троллейбус/маршрутка + легковой/такси.
const PAX_TYPES = ['WB_BUS', 'WB_TROLLEYBUS', 'WB_MINIBUS', 'WB_CAR', 'WB_TAXI'];

// Ходуди фаъолият (2-Б/3-С) — 7 именованных зон legacy-справочника (spec/data/dictionaries.yaml:
// regions). Отдельного справочника в проекте намеренно нет — лёгкий числовой код 1..7.
const REGION_OPTIONS: Option[] = [
  { value: '1', label: '1 — Душанбе' },
  { value: '2', label: '2 — ВМКБ' },
  { value: '3', label: '3 — Суғд' },
  { value: '4', label: '4 — Рашт' },
  { value: '5', label: '5 — Хатлон-Бохтар' },
  { value: '6', label: '6 — Хатлон-Кӯлоб' },
  { value: '7', label: '7 — Ҳисор' },
];

// Автосохранение черновика мастера (QA §7): состояние формы переживает случайное закрытие
// вкладки/перезагрузку. Живёт только в localStorage браузера — на сервере до шага 4 ничего
// не создаётся (submit — единственная точка появления ПЛ), поэтому это не серверный черновик.
const DRAFT_KEY = 'epd:wb-new-draft';
// Срок жизни черновика: старше — сбрасывается (не восстанавливается) при открытии.
// Отсчёт от последнего изменения, а не от создания: активно правишь — живёт; забыл на день — сбрасывается.
const DRAFT_TTL_MS = 12 * 60 * 60 * 1000; // 12 часов
type DraftState = {
  step: number; orgRma: string;
  form: { waybillType: string; vehicleRegNumber: string; driverRma: string; communicationType: string; route: string; schedule: string };
  serviceKind: string; shipmentKind: string; trailers: Trailer[];
  cargo: { name: string; unit: string; weight: string; packages: string; cls: string };
  intl: { secondDriverRma: string; visaValidTo: string; visaCountry: string; loadCountry: string; unloadCountry: string; loadCity: string; unloadCity: string; transitCountries: string; cargoName: string; permitNumber: string; permitType: string; bbaNumber: string };
  routeTypeCode: string;
  dangerous: { on: boolean; adrClass: string; unNumber: string };
  special: { workType: string; workObject: string; motorHoursExit: string };
  bus: { columnNumber: string; brigadeNumber: string };
  directionId: string; client: { id: string; name: string } | null; workRegions: number[];
  customValues: Record<string, string>;
  selVehicle: SSOption | null; selDriver: SSOption | null; selSecondDriver: SSOption | null;
  savedAt?: number; // момент последнего автосохранения (для TTL)
};

// Черновик «содержательный» — пользователь действительно что-то ввёл, а не открыл чистую форму.
// Организацию и тип ПЛ НЕ считаем: организация автоподставляется при единственной, а тип по умолчанию
// WB_BUS. Иначе после «Начать заново» пустое дефолтное состояние тут же сохранялось бы снова, и баннер
// «восстановлен черновик» возвращался бы при каждом обновлении страницы.
function draftHasContent(d: DraftState): boolean {
  const f = d.form ?? ({} as DraftState['form']);
  if ((d.step ?? 1) > 1) return true;
  if (f.vehicleRegNumber || f.driverRma || f.route || f.schedule) return true;
  if (d.selVehicle || d.selDriver || d.selSecondDriver) return true;
  if ((d.trailers?.length ?? 0) > 0 || (d.workRegions?.length ?? 0) > 0) return true;
  if (d.directionId || d.client) return true;
  if (d.dangerous?.on) return true;
  const c = d.cargo ?? ({} as DraftState['cargo']);
  if (c.name || c.unit || c.weight || c.packages || c.cls) return true;
  const sp = d.special ?? ({} as DraftState['special']);
  if (sp.workType || sp.workObject || sp.motorHoursExit) return true;
  const b = d.bus ?? ({} as DraftState['bus']);
  if (b.columnNumber || b.brigadeNumber) return true;
  if (Object.values(d.intl ?? {}).some(Boolean)) return true;
  if (Object.values(d.customValues ?? {}).some(Boolean)) return true;
  return false;
}

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
  const { t: tt, tType, lang } = useT();
  const { roles } = useAuth();
  const canCreate = canCreateWaybill(roles);
  const [step, setStep] = useState(1);
  const [orgs, setOrgs] = useState<Option[]>([]);
  // Выбранные ТС/водитель — храним саму опцию (label для шага проверки), без загрузки всего парка.
  const [selVehicle, setSelVehicle] = useState<SSOption | null>(null);
  const [selDriver, setSelDriver] = useState<SSOption | null>(null);
  const [selSecondDriver, setSelSecondDriver] = useState<SSOption | null>(null);
  const [countries, setCountries] = useState<Option[]>([]);
  const [adrClasses, setAdrClasses] = useState<Option[]>([]);
  const [permitTypes, setPermitTypes] = useState<Option[]>([]);
  const [workTypes, setWorkTypes] = useState<Option[]>([]);
  // Опасный груз — режим грузового ПЛ (2-Б): галочка on + класс ADR.
  const [dangerous, setDangerous] = useState({ on: false, adrClass: '', unNumber: '' });
  // Спецтехника (09): вид работ + объект + моточасы на выезде (учёт по моточасам, не по км).
  const [special, setSpecial] = useState({ workType: '', workObject: '', motorHoursExit: '' });
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
  // Карточка груза (2-Б / 5Б-БМ) — для печатного бланка «Номгӯи бор» с деталями.
  const [cargo, setCargo] = useState({ name: '', unit: '', weight: '', packages: '', cls: '' });
  const [intl, setIntl] = useState({                                // 5Б-БМ / 4М-БМ
    secondDriverRma: '', visaValidTo: '', visaCountry: '',
    loadCountry: '', unloadCountry: '', loadCity: '', unloadCity: '', transitCountries: '',
    cargoName: '', permitNumber: '', permitType: '', bbaNumber: '',
  });
  // Внешние города (справочник, привязан к стране по ISO alpha-2) — подсказки для datalist.
  // Селекты стран хранят nameRu, поэтому нужен маппинг nameRu → ISO-код для запроса городов.
  const [countryCodeByName, setCountryCodeByName] = useState<Record<string, string>>({});
  const [loadCities, setLoadCities] = useState<string[]>([]);
  const [unloadCities, setUnloadCities] = useState<string[]>([]);
  // Типы маршрутов (справочник, ключ = числовой code) — необязательное поле пассажирских ПЛ.
  const [routeTypes, setRouteTypes] = useState<RouteType[]>([]);
  const [routeTypeCode, setRouteTypeCode] = useState('');
  // Колонна/бригада — Т(1-АД), реальные диспетчерские графы бланка автобуса/троллейбуса.
  const [bus, setBus] = useState({ columnNumber: '', brigadeNumber: '' });
  // Самт (направление, справочник Direction) и Заказчик (справочник Client) — 2-Б.
  const [directions, setDirections] = useState<Direction[]>([]);
  const [clients, setClients] = useState<Client[]>([]);
  const [directionId, setDirectionId] = useState('');
  const [client, setClient] = useState<{ id: string; name: string } | null>(null);
  // Ходуди фаъолият (зоны 1–7) — общее поле для 2-Б (грузовой) и 3-С (такси/легковой).
  const [workRegions, setWorkRegions] = useState<number[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [draftRestored, setDraftRestored] = useState(false);
  const [draftSavedAt, setDraftSavedAt] = useState<number | null>(null);
  const [draftLoaded, setDraftLoaded] = useState(false);
  const [copiedFromNumber, setCopiedFromNumber] = useState('');
  // Пригодность (preflight): доступность типов по лицензии (шаг 1) + полная проверка связки (шаг 3).
  const [typeAvail, setTypeAvail] = useState<Record<string, TypeAvailability>>({});
  // Типы, отключённые администратором (WAYBILL_TYPE.active=false) — скрываются из выбора совсем.
  const [offTypes, setOffTypes] = useState<Set<string>>(new Set());
  const [pf, setPf] = useState<Eligibility | null>(null);
  const [pfBusy, setPfBusy] = useState(false);

  useEffect(() => {
    md.organizations()
      .then(list => {
        setOrgs(list.map(o => ({ value: String(o.rma), label: `${o.name} (${o.rma})` })));
        if (list.length === 1) setOrgRma(String(list[0].rma));
      })
      .catch(e => setError(e.message));
  }, []);

  // Копирование ПЛ (QA §7 «на основании предыдущего») — при ?from={id} префилл полями
  // исходного ПЛ вместо восстановления черновика; даты/показания одометра/разовые номера
  // (виза, дозвол, номер книжки ББА) намеренно НЕ копируются — их нужно вводить заново.
  useEffect(() => {
    const from = new URLSearchParams(window.location.search).get('from');
    if (!from) return;
    wb.get(from).then(src => {
      setOrgRma(src.organizationRma);
      setForm(f => ({
        ...f, waybillType: src.waybillType, vehicleRegNumber: src.vehicleRegNumber,
        driverRma: src.driverRma, route: src.route ?? '', schedule: src.schedule ?? '',
      }));
      const vehName = (src.vehicleSnapshot?.brand as string | undefined);
      setSelVehicle({ value: src.vehicleRegNumber, label: src.vehicleRegNumber, sub: vehName ?? '' });
      const drvName = (src.driverSnapshot?.fullName as string | undefined) ?? src.driverRma;
      setSelDriver({ value: src.driverRma, label: drvName });
      const td = (src.typeData ?? {}) as Record<string, unknown>;
      if (typeof td.serviceKind === 'string') setServiceKind(td.serviceKind);
      if (typeof td.shipmentKind === 'string') setShipmentKind(td.shipmentKind);
      if (Array.isArray(td.trailers)) setTrailers(td.trailers as Trailer[]);
      if (td.dangerous) setDangerous(v => ({ ...v, on: true, adrClass: String(td.adrClass ?? ''), unNumber: String(td.unNumber ?? '') }));
      if (td.cargo && typeof td.cargo === 'object') {
        const c = td.cargo as Record<string, unknown>;
        setCargo({ name: String(c.name ?? ''), unit: String(c.unit ?? ''), weight: c.weight != null ? String(c.weight) : '', packages: c.packages != null ? String(c.packages) : '', cls: String(c.class ?? '') });
      }
      if (typeof td.workType === 'string') setSpecial(v => ({ ...v, workType: td.workType as string, workObject: String(td.workObject ?? '') }));
      // Международные: страны/тип дозвола переносим, визу/номер дозвола/книжки ББА — нет (разовые).
      setIntl(v => ({
        ...v,
        visaCountry: String(td.visaCountry ?? ''), loadCountry: String(td.loadCountry ?? ''),
        unloadCountry: String(td.unloadCountry ?? ''),
        loadCity: String(td.loadCity ?? ''), unloadCity: String(td.unloadCity ?? ''),
        transitCountries: Array.isArray(td.transitCountries) ? (td.transitCountries as string[]).join(', ') : '',
        permitType: String(td.permitType ?? ''), cargoName: String(td.cargoName ?? ''),
      }));
      if (td.routeTypeCode != null) setRouteTypeCode(String(td.routeTypeCode));
      setCopiedFromNumber(src.number ?? src.id.slice(0, 8));
      setStep(2);
    }).catch(() => { /* исходный ПЛ недоступен — остаёмся с чистой формой */ });
  }, []);

  // Восстановление автосохранённого черновика — один раз при монтировании, до первой записи
  // (иначе пустое начальное состояние тут же перезаписало бы сохранённый черновик в localStorage).
  // Пропускается, если пришли через копирование (?from=) — шаблон источника важнее старого черновика.
  useEffect(() => {
    if (new URLSearchParams(window.location.search).get('from')) { setDraftLoaded(true); return; }
    try {
      const raw = window.localStorage.getItem(DRAFT_KEY);
      if (raw) {
        const d = JSON.parse(raw) as DraftState;
        // Просроченный (старше TTL) или пустой черновик не восстанавливаем и удаляем,
        // чтобы он не «воскресал» при следующем открытии.
        const expired = !d.savedAt || (Date.now() - d.savedAt) > DRAFT_TTL_MS;
        if (expired || !draftHasContent(d)) {
          window.localStorage.removeItem(DRAFT_KEY);
          setDraftLoaded(true);
          return;
        }
        setStep(d.step ?? 1);
        setOrgRma(d.orgRma ?? '');
        setForm(f => ({ ...f, ...d.form }));
        setServiceKind(d.serviceKind ?? 'TAXI');
        setShipmentKind(d.shipmentKind ?? 'PIECEWORK');
        setTrailers(d.trailers ?? []);
        setCargo(c => ({ ...c, ...d.cargo }));
        setIntl(v => ({ ...v, ...d.intl }));
        setRouteTypeCode(d.routeTypeCode ?? '');
        setDangerous(v => ({ ...v, ...d.dangerous }));
        setSpecial(v => ({ ...v, ...d.special }));
        setBus(v => ({ ...v, ...d.bus }));
        setDirectionId(d.directionId ?? '');
        setClient(d.client ?? null);
        setWorkRegions(d.workRegions ?? []);
        setCustomValues(d.customValues ?? {});
        setSelVehicle(d.selVehicle ?? null);
        setSelDriver(d.selDriver ?? null);
        setSelSecondDriver(d.selSecondDriver ?? null);
        setDraftSavedAt(d.savedAt ?? null);
        setDraftRestored(true);
      }
    } catch { /* повреждённый черновик — просто начинаем с чистого листа */ }
    setDraftLoaded(true);
  }, []);

  // Автосохранение — на каждое изменение состояния мастера, но только после восстановления
  // (иначе первый рендер с дефолтами затирает ещё не прочитанный черновик).
  useEffect(() => {
    if (!draftLoaded) return;
    const d: DraftState = {
      step, orgRma, form, serviceKind, shipmentKind, trailers, cargo, intl, routeTypeCode, dangerous, special,
      bus, directionId, client, workRegions,
      customValues, selVehicle, selDriver, selSecondDriver,
      savedAt: Date.now(),
    };
    try {
      // Сохраняем только содержательный черновик. Пустую дефолтную форму не пишем (и стираем
      // прежний ключ) — иначе «Начать заново» + обновление возвращали бы баннер черновика.
      if (draftHasContent(d)) window.localStorage.setItem(DRAFT_KEY, JSON.stringify(d));
      else window.localStorage.removeItem(DRAFT_KEY);
    } catch { /* квота/приватный режим — не критично */ }
  }, [draftLoaded, step, orgRma, form, serviceKind, shipmentKind, trailers, cargo, intl, routeTypeCode, dangerous, special,
      bus, directionId, client, workRegions, customValues, selVehicle, selDriver, selSecondDriver]);

  function discardDraft() {
    try { window.localStorage.removeItem(DRAFT_KEY); } catch { /* noop */ }
    window.location.reload();
  }

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
      .then(list => {
        setCountries(list.map(c => ({ value: c.nameRu, label: c.nameRu })));
        // nameRu → ISO alpha-2 (селекты стран хранят nameRu, а справочник городов ждёт код).
        setCountryCodeByName(Object.fromEntries(list.map(c => [c.nameRu, c.code])));
      })
      .catch(() => { /* классификатор недоступен — поля останутся пустыми */ });
    // ADR-классы и виды работ — ОБЯЗАТЕЛЬНЫЕ поля для опасного груза/спецтехники (шаг 3).
    // При сбое загрузки не молчим: иначе пользователь застрянет на пустом обязательном списке
    // без объяснения. Показываем ошибку в общий баннер.
    md.classifiers('ADR_CLASS')
      .then(list => setAdrClasses(list.map(c => ({ value: c.code, label: `${c.code} — ${c.nameRu}` }))))
      .catch(() => setError('Не удалось загрузить справочник классов ADR — обратитесь к администратору'));
    md.classifiers('PERMIT_TYPE')
      .then(list => setPermitTypes(list.map(c => ({ value: c.nameRu, label: c.nameRu }))))
      .catch(() => {});
    md.classifiers('WORK_TYPE')
      .then(list => setWorkTypes(list.map(c => ({ value: c.nameRu, label: c.nameRu }))))
      .catch(() => setError('Не удалось загрузить справочник видов работ — обратитесь к администратору'));
    // Отключённые администратором типы ПЛ убираются из шага выбора (при сбое — показываем все,
    // бэкенд всё равно заблокирует создание отключённого типа).
    md.waybillTypes()
      .then(list => {
        const off = new Set(list.filter(c => !c.active).map(c => c.code));
        setOffTypes(off);
        // Предвыбранный тип оказался отключён — переключаем на первый доступный.
        setForm(f => {
          if (!off.has(f.waybillType)) return f;
          const first = Object.keys(TYPE_LABELS).find(v => !off.has(v));
          return first ? { ...f, waybillType: first } : f;
        });
      })
      .catch(() => { /* fail-open */ });
  }, []);

  // Города погрузки/разгрузки — подгружаются по выбранной стране (справочник неполный, поэтому
  // это лишь подсказки datalist; при непустой стране без справочника список пустой, ввод — свободный).
  useEffect(() => {
    const code = countryCodeByName[intl.loadCountry];
    if (!code) { setLoadCities([]); return; }
    let ignore = false;
    md.externalCities(code)
      .then(list => { if (!ignore) setLoadCities(list.filter(c => c.active).map(c => (lang === 'tj' && c.nameTj) ? c.nameTj : c.nameRu)); })
      .catch(() => { if (!ignore) setLoadCities([]); });
    return () => { ignore = true; };
  }, [intl.loadCountry, countryCodeByName, lang]);
  useEffect(() => {
    const code = countryCodeByName[intl.unloadCountry];
    if (!code) { setUnloadCities([]); return; }
    let ignore = false;
    md.externalCities(code)
      .then(list => { if (!ignore) setUnloadCities(list.filter(c => c.active).map(c => (lang === 'tj' && c.nameTj) ? c.nameTj : c.nameRu)); })
      .catch(() => { if (!ignore) setUnloadCities([]); });
    return () => { ignore = true; };
  }, [intl.unloadCountry, countryCodeByName, lang]);

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

  // Доступные типы ПЛ для организации (по лицензии/виду субъекта) — для индикации на шаге выбора типа.
  useEffect(() => {
    if (!orgRma) { setTypeAvail({}); return; }
    let ignore = false;
    wb.availableTypes(orgRma)
      .then(list => { if (!ignore) setTypeAvail(Object.fromEntries(list.map(a => [a.type, a]))); })
      .catch(() => { if (!ignore) setTypeAvail({}); });
    return () => { ignore = true; };
  }, [orgRma]);

  // Полная проверка пригодности выбранной связки (тип+организация+ТС+водитель) — на шаге параметров.
  useEffect(() => {
    if (step !== 3 || !orgRma || !form.vehicleRegNumber || !form.driverRma) { setPf(null); return; }
    let ignore = false;
    setPfBusy(true);
    wb.preflight({ type: form.waybillType, organizationRma: orgRma, vehicleRegNumber: form.vehicleRegNumber, driverRma: form.driverRma })
      .then(r => { if (!ignore) setPf(r); })
      .catch(() => { if (!ignore) setPf(null); })
      .finally(() => { if (!ignore) setPfBusy(false); });
    return () => { ignore = true; };
  }, [step, orgRma, form.vehicleRegNumber, form.driverRma, form.waybillType]);

  const t = form.waybillType;
  const isCar = t === 'WB_CAR' || t === 'WB_TAXI';
  const isTruck = t === 'WB_TRUCK';
  const isIntl = INTL_TYPES.includes(t);
  const isSpecial = t === 'WB_SPECIAL';
  const isBus = t === 'WB_BUS' || t === 'WB_TROLLEYBUS';
  const isPassenger = PAX_TYPES.includes(t);

  // Заказчик (2-Б): поиск по уже загруженному справочнику Client — без отдельного серверного эндпоинта.
  const searchClients = useCallback(async (q: string): Promise<SSOption[]> => {
    const ql = q.trim().toLowerCase();
    return clients
      .filter(c => !ql || c.name.toLowerCase().includes(ql))
      .slice(0, 25)
      .map(c => ({ value: c.id, label: c.name, sub: c.address ?? '' }));
  }, [clients]);

  function toggleWorkRegion(code: number) {
    setWorkRegions(prev => prev.includes(code) ? prev.filter(r => r !== code) : [...prev, code].sort((a, b) => a - b));
  }

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
    && (isTruck && dangerous.on ? !!dangerous.adrClass : true)
    && (isSpecial ? (!!special.workType && special.motorHoursExit.trim() !== '') : true)
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

  function cargoCard(): Record<string, unknown> | undefined {
    const c: Record<string, unknown> = {};
    if (cargo.name.trim()) c.name = cargo.name.trim();
    if (cargo.unit.trim()) c.unit = cargo.unit.trim();
    if (cargo.weight.trim() && Number.isFinite(Number(cargo.weight))) c.weight = Number(cargo.weight);
    if (cargo.packages.trim() && Number.isFinite(Number(cargo.packages))) c.packages = Number(cargo.packages);
    if (cargo.cls.trim()) c.class = cargo.cls.trim();
    return Object.keys(c).length ? c : undefined;
  }

  function buildTypeData(): Record<string, unknown> | undefined {
    if (isCar) return {
      serviceKind,
      ...(workRegions.length ? { workRegions } : {}),
    };
    if (isTruck) return {
      shipmentKind,
      ...(trailers.length ? { trailers } : {}),
      ...(dangerous.on ? { dangerous: true, adrClass: dangerous.adrClass, ...(dangerous.unNumber ? { unNumber: dangerous.unNumber } : {}) } : {}),
      ...(cargoCard() ? { cargo: cargoCard() } : {}),
      ...(directionId ? { directionId: Number(directionId) } : {}),
      ...(client ? { clientId: client.id, clientName: client.name } : {}),
      ...(workRegions.length ? { workRegions } : {}),
    };
    if (isBus) return {
      ...(bus.columnNumber.trim() ? { columnNumber: bus.columnNumber.trim() } : {}),
      ...(bus.brigadeNumber.trim() ? { brigadeNumber: bus.brigadeNumber.trim() } : {}),
    };
    if (isSpecial) return {
      workType: special.workType,
      motorHoursExit: Number(special.motorHoursExit),
      ...(special.workObject.trim() ? { workObject: special.workObject.trim() } : {}),
    };
    if (isIntl) {
      return {
        visaValidTo: intl.visaValidTo,
        visaCountry: intl.visaCountry,
        loadCountry: intl.loadCountry,
        unloadCountry: intl.unloadCountry,
        ...(intl.loadCity.trim() ? { loadCity: intl.loadCity.trim() } : {}),
        ...(intl.unloadCity.trim() ? { unloadCity: intl.unloadCity.trim() } : {}),
        ...(intl.transitCountries ? { transitCountries: intl.transitCountries.split(',').map(s => s.trim()).filter(Boolean) } : {}),
        ...(t === 'WB_TRUCK_INTL' ? { cargoName: intl.cargoName } : {}),
        permitNumber: intl.permitNumber,
        ...(intl.permitType ? { permitType: intl.permitType } : {}),
        ...(intl.bbaNumber ? { bbaNumber: intl.bbaNumber } : {}),
        ...(cargoCard() ? { cargo: cargoCard() } : {}),
      };
    }
    return undefined;
  }

  async function submit() {
    setError('');
    setBusy(true);
    try {
      const td = buildTypeData() ?? {};
      // Тип маршрута (необязательный) — только для пассажирских ПЛ; кладём числовой code.
      if (isPassenger && routeTypeCode) (td as Record<string, unknown>).routeTypeCode = Number(routeTypeCode);
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
      try { window.localStorage.removeItem(DRAFT_KEY); } catch { /* noop */ }
      router.push(`/waybills/${created.id}`);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  const cur = STEPS[step - 1];

  // Выписывает ПЛ только диспетчер (POST /api/v1/waybills). Прямой заход бухгалтера/аналитика
  // по ссылке иначе упирался бы в 403 на каждом шаге мастера — показываем понятное объяснение.
  if (!canCreate) {
    return (
      <>
        <div className="toolbar"><h1>{tt('wb.new.h')}</h1></div>
        <div className="card">
          <p style={{ margin: '0 0 10px' }}>{tt('wb.new.norole')}</p>
          <Link className="btn secondary" href="/waybills" style={{ textDecoration: 'none' }}>
            {tt('nav.waybill.registry')}
          </Link>
        </div>
      </>
    );
  }

  return (
    <>
      <div className="toolbar">
        <h1>{tt('wb.new.h')}</h1>
        <div className="spacer" />
        <span className="page-lead" style={{ margin: 0 }}>{tt('wb.step')} {step} {tt('paging.of')} {STEPS.length} · {tt(cur.sub)}</span>
      </div>
      {error && <div className="error">{error}</div>}
      {copiedFromNumber && (
        <div className="sys-ok">
          <Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> {tt('wb.copy.notice')} {copiedFromNumber}
        </div>
      )}
      {!copiedFromNumber && draftRestored && (
        <div className="sys-ok" style={{ justifyContent: 'space-between' }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Icon d={P.check} cls="" style={{ width: 16, height: 16 }} />
            <span>
              {tt('wb.draft.restored')}
              {draftSavedAt != null && (
                <span style={{ color: 'var(--muted)', marginLeft: 6 }}>
                  · {new Date(draftSavedAt).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })} · {tt('wb.draft.kept')}
                </span>
              )}
            </span>
          </span>
          <button type="button" className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} onClick={discardDraft}>
            {tt('wb.draft.discard')}
          </button>
        </div>
      )}

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
              {Object.entries(TYPE_LABELS).filter(([value]) => !offTypes.has(value)).map(([value]) => {
                const meta = TYPE_META[value];
                const active = form.waybillType === value;
                const avail = typeAvail[value];
                const unavailable = !!avail && !avail.available;
                const reason = unavailable ? (avail.reasons[0] ?? tt('wb.type.unavail')) : undefined;
                return (
                  <button
                    type="button"
                    key={value}
                    disabled={unavailable}
                    title={reason}
                    onClick={() => { if (!unavailable) setForm({ ...form, waybillType: value }); }}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 13, textAlign: 'left',
                      cursor: unavailable ? 'not-allowed' : 'pointer', opacity: unavailable ? 0.55 : 1,
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
                      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 8, alignItems: 'center' }}>
                        <span style={{ display: 'inline-block', fontSize: 10.5, fontWeight: 600, color: 'var(--ink-soft)', background: 'var(--line-soft)', padding: '3px 9px', borderRadius: 999 }}>{tt(meta.group)}</span>
                        {unavailable && <span className="badge red" title={reason} style={{ fontSize: 10.5 }}>{tt('wb.type.unavail')}</span>}
                      </div>
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

              {/* --- Т(1-АД): колонна/бригада --- */}
              {isBus && (
                <>
                  <div>
                    <label>{tt('wbf.column')}</label>
                    <input value={bus.columnNumber} onChange={e => setBus({ ...bus, columnNumber: e.target.value })} placeholder="напр. 3" />
                  </div>
                  <div>
                    <label>{tt('wbf.brigade')}</label>
                    <input value={bus.brigadeNumber} onChange={e => setBus({ ...bus, brigadeNumber: e.target.value })} placeholder="напр. 12" />
                  </div>
                </>
              )}

              <div>
                <label>{tt('wb.f.route')}{isCar && serviceKind === 'ROUTE' ? tt('wb.required.suffix') : ''}</label>
                <input required={isCar && serviceKind === 'ROUTE'} value={form.route}
                  onChange={e => setForm({ ...form, route: e.target.value })} placeholder={tt('wb.ph.route')} />
              </div>

              {/* --- Тип маршрута (пассажирские ПЛ, справочник route-types) — необязательное поле --- */}
              {isPassenger && (
                <div>
                  <label>{tt('wb.f.routetype')}</label>
                  <select value={routeTypeCode} onChange={e => setRouteTypeCode(e.target.value)}>
                    <option value="">{tt('wb.opt.none')}</option>
                    {routeTypes.map(rt => (
                      <option key={rt.id} value={rt.code}>{(lang === 'tj' && rt.nameTj) ? rt.nameTj : rt.nameRu}</option>
                    ))}
                  </select>
                </div>
              )}
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

              {/* --- 2-Б: Самт (направление, справочник Direction) --- */}
              {isTruck && (
                <div>
                  <label>{tt('wbf.samt')}</label>
                  <select value={directionId} onChange={e => setDirectionId(e.target.value)}>
                    <option value="">{tt('wbf.selectdir')}</option>
                    {directions.map(d => <option key={d.id} value={d.id}>{d.title}</option>)}
                  </select>
                </div>
              )}

              {/* --- 2-Б: Заказчик (справочник Client) --- */}
              {isTruck && (
                <div>
                  <label>{tt('wbf.client')}</label>
                  <SearchSelect
                    value={client?.id ?? ''}
                    selectedLabel={client?.name ?? ''}
                    placeholder={tt('wbf.clientsearch')}
                    onSearch={searchClients}
                    onSelect={o => setClient({ id: o.value, name: o.label })}
                    onClear={() => setClient(null)}
                    loadingText={tt('wb.search.loading')} emptyText={tt('wb.search.empty')} hintText={tt('wbf.clientsearchhint')} />
                </div>
              )}

              {/* --- Карточка груза (2-Б / 5Б-БМ) — для печатного бланка «Номгӯи бор» --- */}
              {(isTruck || isIntl) && (
                <div style={{ gridColumn: '1 / -1', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 8 }}>
                  <div><label>{tt('wbf.cargoname')}</label><input value={cargo.name} onChange={e => setCargo({ ...cargo, name: e.target.value })} placeholder="напр. хлопок-волокно" /></div>
                  <div><label>{tt('wbf.unit')}</label><input value={cargo.unit} onChange={e => setCargo({ ...cargo, unit: e.target.value })} placeholder="т / м³ / шт" /></div>
                  <div><label>{tt('wbf.grossmass')}</label><input type="number" step="0.001" min={0} value={cargo.weight} onChange={e => setCargo({ ...cargo, weight: e.target.value })} /></div>
                  <div><label>{tt('wbf.places')}</label><input type="number" min={0} value={cargo.packages} onChange={e => setCargo({ ...cargo, packages: e.target.value })} /></div>
                  <div><label>{tt('wbf.cargoclass')}</label><input value={cargo.cls} onChange={e => setCargo({ ...cargo, cls: e.target.value })} placeholder="1–4" /></div>
                </div>
              )}

              {/* --- 2-Б / 3-С: Ходуди фаъолият (зоны 1–7) --- */}
              {(isTruck || isCar) && (
                <div style={{ gridColumn: '1 / -1' }}>
                  <label>{tt('wbf.workzones')}</label>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {REGION_OPTIONS.map(r => {
                      const code = Number(r.value);
                      const active = workRegions.includes(code);
                      return (
                        <button type="button" key={r.value} onClick={() => toggleWorkRegion(code)}
                          className={active ? 'btn' : 'btn secondary'} style={{ padding: '5px 11px', fontSize: 12.5 }}>
                          {r.label}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* --- 2-Б: опасный груз — режим грузового ПЛ (галочка + класс ADR) --- */}
              {isTruck && (
                <div style={{ gridColumn: '1 / -1' }}>
                  <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8, cursor: 'pointer', fontWeight: 600 }}>
                    <input type="checkbox" checked={dangerous.on} onChange={e => setDangerous({ ...dangerous, on: e.target.checked })} style={{ width: 'auto' }} />
                    {tt('wb.f.dangerous')}
                  </label>
                </div>
              )}
              {isTruck && dangerous.on && (
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
                    <label>{tt('wb.f.loadcity')}</label>
                    <input list="loadCities" value={intl.loadCity} placeholder={tt('wb.ph.city')}
                      onChange={e => setIntl({ ...intl, loadCity: e.target.value })} />
                    <datalist id="loadCities">
                      {loadCities.map(c => <option key={c} value={c} />)}
                    </datalist>
                  </div>
                  <div>
                    <label>{tt('wb.f.unloadcountry')}</label>
                    <select required value={intl.unloadCountry} onChange={e => setIntl({ ...intl, unloadCountry: e.target.value })}>
                      <option value="">{tt('wb.opt.country')}</option>
                      {countries.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
                    </select>
                  </div>
                  <div>
                    <label>{tt('wb.f.unloadcity')}</label>
                    <input list="unloadCities" value={intl.unloadCity} placeholder={tt('wb.ph.city')}
                      onChange={e => setIntl({ ...intl, unloadCity: e.target.value })} />
                    <datalist id="unloadCities">
                      {unloadCities.map(c => <option key={c} value={c} />)}
                    </datalist>
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

              {/* --- Спецтехника (09): вид работ, объект, моточасы (учёт по моточасам) --- */}
              {isSpecial && (
                <>
                  <div>
                    <label>{tt('wb.f.worktype')}{tt('wb.required.suffix')}</label>
                    <select required value={special.workType} onChange={e => setSpecial({ ...special, workType: e.target.value })}>
                      <option value="">{tt('wb.opt.worktype')}</option>
                      {workTypes.map(w => <option key={w.value} value={w.value}>{w.label}</option>)}
                    </select>
                  </div>
                  <div>
                    <label>{tt('wb.f.motorhours')}{tt('wb.required.suffix')}</label>
                    <input type="number" min="0" step="0.1" required placeholder="1240.5"
                      value={special.motorHoursExit} onChange={e => setSpecial({ ...special, motorHoursExit: e.target.value })} />
                  </div>
                  <div style={{ gridColumn: '1 / -1' }}>
                    <label>{tt('wb.f.workobject')}</label>
                    <input value={special.workObject} placeholder={tt('wb.ph.workobject')}
                      onChange={e => setSpecial({ ...special, workObject: e.target.value })} />
                  </div>
                  <div style={{ gridColumn: '1 / -1' }} className="hint">{tt('wb.spec.note')}</div>
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
                {isTruck && (directionId || client) && (<><dt>{tt('wbf.samt')} / {tt('wbf.client')}</dt><dd>{directions.find(d => String(d.id) === directionId)?.title || '—'} · {client?.name || '—'}</dd></>)}
                {(isTruck || isCar) && workRegions.length > 0 && (<><dt>{tt('wbf.workzones')}</dt><dd>{workRegions.join(', ')}</dd></>)}
                {isBus && (bus.columnNumber || bus.brigadeNumber) && (<><dt>{tt('wbf.column')} / {tt('wbf.brigade')}</dt><dd>{bus.columnNumber || '—'} / {bus.brigadeNumber || '—'}</dd></>)}
                {isIntl && (<><dt>{tt('wb.intl.trip')}</dt><dd>{intl.loadCountry || '—'} → {intl.unloadCountry || '—'} · {tt('wb.permit.short')} {intl.permitNumber || '—'}{intl.secondDriverRma ? tt('wb.withsecond') : ''}</dd></>)}
                {isSpecial && (<><dt>{tt('wb.spec.label')}</dt><dd>{special.workType || '—'}{special.motorHoursExit ? ` · ${tt('wb.f.motorhours')}: ${special.motorHoursExit}` : ''}{special.workObject ? ` · ${special.workObject}` : ''}</dd></>)}
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
              <button type="button" className="btn" disabled={!stepOk(step) || (step === 3 && pf != null && !pf.eligible)} onClick={() => setStep(s => Math.min(STEPS.length, s + 1))}>
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
                {/* Проверка пригодности: реальный результат preflight (что разрешит сервер), с фолбэком. */}
                <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--line-soft)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                    <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.03em' }}>{tt('wb.pf.h')}</div>
                    {pf && <span className={`badge ${pf.eligible ? 'green' : 'red'}`} style={{ marginLeft: 'auto', fontSize: 10.5 }}>{pf.eligible ? tt('wb.pf.ok') : tt('wb.pf.no')}</span>}
                  </div>
                  {pfBusy && !pf ? (
                    <div style={{ fontSize: 12, color: 'var(--muted)' }}>{tt('wb.pf.checking')}</div>
                  ) : pf ? (
                    <>
                      <div className={pf.eligible ? 'sys-ok' : ''} style={pf.eligible ? { marginTop: 0, marginBottom: 8 } : { fontSize: 12.5, color: '#dc2626', fontWeight: 600, marginBottom: 8 }}>
                        {pf.eligible
                          ? (<><Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> {tt('wb.pf.ok.s')}</>)
                          : tt('wb.pf.no.s')}
                      </div>
                      {pf.checks.map((c, i) => (
                        <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 9, padding: '5px 0', fontSize: 12, color: 'var(--ink-soft)' }}>
                          <span className={c.severity === 'ERROR' ? 'ic-red' : 'ic-amber'} style={{ width: 22, height: 22, borderRadius: '50%', display: 'grid', placeItems: 'center', flex: 'none' }}>
                            <Icon d={P.alert} cls="" style={{ width: 13, height: 13 }} />
                          </span>
                          <span style={{ lineHeight: 1.35 }}>{c.message}</span>
                        </div>
                      ))}
                      <div className="hint" style={{ marginTop: 8, fontSize: 11 }}>{tt('wb.pf.note')}</div>
                    </>
                  ) : (
                    <>
                      <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.03em', marginBottom: 8 }}>{tt('wb.chk.srv.h')}</div>
                      {serverChecks.map((sc, i) => (
                        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '5px 0', fontSize: 12, color: 'var(--ink-soft)' }}>
                          <Icon d={P.shield} cls="" style={{ width: 14, height: 14, color: 'var(--blue-600)', flex: 'none' }} />
                          {sc}
                        </div>
                      ))}
                      <div className="hint" style={{ marginTop: 8, fontSize: 11 }}>{tt('wb.chk.srv.note')}</div>
                    </>
                  )}
                </div>
              </div>
            )}
          </aside>
        )}
      </div>
    </>
  );
}
