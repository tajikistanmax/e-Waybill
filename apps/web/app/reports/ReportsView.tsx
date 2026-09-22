'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { authHeaders, md, STATUS_LABELS } from '@/lib/api';
import { applyColumnConfig } from '@/lib/reportColumns';
import type { RegionalCount, RegionalCounts } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { downloadCsv } from '@/lib/csv';
import { Icon, P } from '../icons';
import { Pager, usePaged } from '../Pager';
import MalumotnomaTab from './MalumotnomaTab';
import JournalsTab from './JournalsTab';
import PlansEditor from './PlansEditor';
import { useAuth } from '@/lib/auth';

type Summary = {
  period: { from: string; to: string };
  totals: { waybills: number; completed: number; cancelled: number; active: number; distanceKm: number; fuelGivenLiters: number; revenue: number };
  byStatus: Record<string, number>;
  byType: Record<string, number>;
};

type JournalRow = {
  number: string | null; waybillType: string; vehicleRegNumber: string; driverName: string;
  status: string; validFrom: string | null; validTo: string | null;
  odometerExit: number | null; odometerEntry: number | null;
};

type GroupRow = {
  driverRma?: string; fullName?: string;
  vehicleRegNumber?: string;
  waybills: number; completed: number; distanceKm: number;
};
type FuelRow = { fuelType: number; fuelName?: string; given: number; remainEnd: number };

type TypedRow = {
  key: string; label: string; waybills: number; laps: number;
  distanceKm: number; routeDistanceKm: number; passengerTurnover: number; passengerCount: number;
  fuelNormLiters: number; fuelGivenLiters: number; fuelDeviationLiters: number;
  revenue: number; kassa: number; driverSalary: number;
};
type TypedReport = {
  type: string; typeLabel: string; from: string; to: string;
  organizationRma: string | null; rows: TypedRow[]; totals: TypedRow | null;
};
type ReportTypeMeta = { code: string; label: string; grouping: string };

type RegionalInd = {
  planVolumeCur: number; factVolumeCur: number; planVolumePrev: number; factVolumePrev: number;
  volumeDoneCurPct: number; volumeDonePrevPct: number;
  planRotationCur: number; factRotationCur: number; planRotationPrev: number; factRotationPrev: number;
  rotationDoneCurPct: number; rotationDonePrevPct: number;
};
type RegionalReport = {
  billKind: string; reportType: string; from: string; to: string; year: number; prevYear: number;
  regions: { title: string; regionId: number | null; totals: RegionalInd;
    cities: { title: string; totals: RegionalInd;
      companies: { title: string; organizationRma: string; totals: RegionalInd }[] }[] }[];
  totals: RegionalInd;
};
type NormRow = {
  organizationRma: string; organizationName: string; regionTitle: string;
  issued: number; parkings: number; perParking: number; mustGive: number; deviation: number;
};
type WaybillNorm = {
  waybillType: string; waybillTypeLabel: string; mustGivePerParking: number;
  from: string; to: string; rows: NormRow[]; totals: NormRow;
};
const REG_COLS: { key: keyof RegionalInd; label: string }[] = [
  { key: 'planVolumeCur', label: 'План об. тек.' }, { key: 'factVolumeCur', label: 'Факт об. тек.' },
  { key: 'planVolumePrev', label: 'План об. пр.' }, { key: 'factVolumePrev', label: 'Факт об. пр.' },
  { key: 'volumeDoneCurPct', label: '% об. тек.' }, { key: 'volumeDonePrevPct', label: '% об. пр.' },
  { key: 'planRotationCur', label: 'План об-т тек.' }, { key: 'factRotationCur', label: 'Факт об-т тек.' },
  { key: 'planRotationPrev', label: 'План об-т пр.' }, { key: 'factRotationPrev', label: 'Факт об-т пр.' },
  { key: 'rotationDoneCurPct', label: '% об-т тек.' }, { key: 'rotationDonePrevPct', label: '% об-т пр.' },
];

const RC_COLS: { key: keyof RegionalCounts; label: string }[] = [
  { key: 'issuedMonth', label: '1 выд.мес' }, { key: 'issuedPrevMonth', label: '2 выд.пр.мес' }, { key: 'issuedMonthDelta', label: '3 Δ' },
  { key: 'issuedYtd', label: '4 выд.год' }, { key: 'issuedYtdPrev', label: '5 выд.пр.год' }, { key: 'issuedYtdDelta', label: '6 Δ' },
  { key: 'processedMonth', label: '7 обр.мес' }, { key: 'processedPrevMonth', label: '8 обр.пр.мес' }, { key: 'processedMonthDelta', label: '9 Δ' },
  { key: 'processedYtd', label: '10 обр.год' }, { key: 'processedYtdPrev', label: '11 обр.пр.год' }, { key: 'processedYtdDelta', label: '12 Δ' },
  { key: 'unprocessedYtd', label: '13 необр.' }, { key: 'vehiclesYtd', label: '14 ТС год' }, { key: 'vehiclesMonth', label: '15 ТС мес' },
  { key: 'vehiclesPrevMonth', label: '16 ТС пр.мес' }, { key: 'vehiclesMonthPrevYear', label: '17 ТС мес.пр.г' }, { key: 'vehiclesMonthDelta', label: '18 Δ' }, { key: 'vehiclesYoYDelta', label: '19 Δг/г' },
  { key: 'cargoWaybillsTotal', label: '20 груз.ПЛ' }, { key: 'cargoWaybillsWithConsignment', label: '21 груз.с накл.' },
];

/** Разрез «ведомственный/общий» (Organization.typeCompany): 1 — общего пользования, 2 — ведомственная. */
const TYPE_COMPANY_OPTIONS = [
  { v: '', k: 'flt.orgtype.all' },
  { v: '1', k: 'flt.orgtype.public' },
  { v: '2', k: 'flt.orgtype.dept' },
];

const TYPED_COLS: { key: keyof TypedRow; label: string }[] = [
  { key: 'label', label: 'Наименование' },
  { key: 'waybills', label: 'ПЛ' },
  { key: 'laps', label: 'Рейсы' },
  { key: 'distanceKm', label: 'Пробег, км' },
  { key: 'routeDistanceKm', label: 'По маршруту, км' },
  { key: 'passengerTurnover', label: 'Пасс-км' },
  { key: 'passengerCount', label: 'Пассажиры' },
  { key: 'fuelNormLiters', label: 'Норма, л' },
  { key: 'fuelGivenLiters', label: 'Выдано, л' },
  { key: 'fuelDeviationLiters', label: 'Откл., л' },
  { key: 'revenue', label: 'Выручка' },
  { key: 'kassa', label: 'Касса' },
  { key: 'driverSalary', label: 'Зарплата вод.' },
];

const FUEL_NAMES: Record<number, string> = { 1: 'Бензин', 2: 'Дизель', 3: 'Газ сжиженный', 4: 'Газ природный', 5: 'Электро' };

/** Цвет полосы для статус-бара — по палитре бейджей статусов. */
const BAR_COLOR: Record<string, string> = {
  gray: '#94a3b8', blue: 'var(--blue-600)', amber: 'var(--amber)',
  green: 'var(--green)', red: 'var(--red)', teal: 'var(--cyan)',
};

async function getJson<T>(url: string): Promise<T> {
  const r = await fetch(url, { headers: authHeaders() });
  if (!r.ok) {
    const p = await r.json().catch(() => null);
    throw new Error(p?.detail ?? p?.title ?? `Ошибка ${r.status}`);
  }
  return r.json();
}

function today(offsetDays = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

/** Горизонтальный бар-ряд для распределений (по статусам / по типам). */
function BarRow({ label, value, max, color }: { label: string; value: number; max: number; color: string }) {
  const pct = max > 0 ? Math.round((value / max) * 100) : 0;
  return (
    <div style={{ marginBottom: 13 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}>
        <span style={{ color: 'var(--ink-soft)' }}>{label}</span>
        <b style={{ color: 'var(--ink)', fontVariantNumeric: 'tabular-nums' }}>{value.toLocaleString('ru-RU')}</b>
      </div>
      <div style={{ height: 8, background: 'var(--line-soft)', borderRadius: 999, overflow: 'hidden' }}>
        <div style={{ width: `${pct}%`, height: '100%', background: color, borderRadius: 999, transition: 'width .3s' }} />
      </div>
    </div>
  );
}

export type ReportTab = 'summary' | 'journal' | 'driver' | 'vehicle' | 'fuel' | 'typed' | 'regional' | 'malum' | 'journals';

/** Один раздел отчётов; вкладка задаётся маршрутом (/reports/<...>), а не состоянием. */
export default function ReportsView({ tab }: { tab: ReportTab }) {
  const { t, tType, tStatus } = useT();
  const { roles } = useAuth();
  const isMintrans = roles.includes('SYSTEM_ADMIN') || roles.includes('MINTRANS_ANALYST');
  const [from, setFrom] = useState(today(-30));
  const [to, setTo] = useState(today());
  const [journalDate, setJournalDate] = useState(today());
  const [summary, setSummary] = useState<Summary | null>(null);
  const [journal, setJournal] = useState<JournalRow[]>([]);
  const [byDriver, setByDriver] = useState<GroupRow[]>([]);
  const [byVehicle, setByVehicle] = useState<GroupRow[]>([]);
  const [fuel, setFuel] = useState<FuelRow[]>([]);
  const [error, setError] = useState('');
  // Типовые разрезы движка «Роҳхат» (11 типов, пассажирский / грузовой).
  const [typedKind, setTypedKind] = useState<'passenger' | 'cargo'>('passenger');
  const [typedType, setTypedType] = useState('BY_VEHICLE');
  const [typedTypes, setTypedTypes] = useState<ReportTypeMeta[]>([]);
  const [typedReport, setTypedReport] = useState<TypedReport | null>(null);
  // Отбор типового отчёта по одному ТС / водителю (legacy report_details, MIGRATION.md 6.7).
  const [typedVehicle, setTypedVehicle] = useState('');
  const [typedDriver, setTypedDriver] = useState('');
  const typedFilterQs = (typedVehicle.trim() ? `&vehicleRegNumber=${encodeURIComponent(typedVehicle.trim())}` : '')
    + (typedDriver.trim() ? `&driverRma=${encodeURIComponent(typedDriver.trim())}` : '');
  const [regional, setRegional] = useState<RegionalReport | null>(null);
  const [regionalMode, setRegionalMode] = useState<'trans' | 'count' | 'norm'>('trans');
  const [regionalBill, setRegionalBill] = useState<'PASSENGER' | 'CARGO'>('PASSENGER');
  const [regionalCount, setRegionalCount] = useState<RegionalCount | null>(null);
  const [normType, setNormType] = useState('WB_MINIBUS');
  const [norm, setNorm] = useState<WaybillNorm | null>(null);
  // Разрез «ведомственный/общий» (Organization.typeCompany) — общий фильтр всех сводных отчётов Минтранса.
  const [typeCompany, setTypeCompany] = useState('');
  // Конфигурируемые колонки отчётов (MIGRATION.md 7.4 / 10.5): настройки категории «reports»
  // (видимые ключи + подписи) применяются к таблицам и CSV; подписи по умолчанию — i18n.
  const [reportCfg, setReportCfg] = useState<Record<string, string>>({});
  useEffect(() => {
    md.settings('reports')
      .then(list => setReportCfg(Object.fromEntries(list.map(s => [s.settingKey, s.settingValue ?? '']))))
      .catch(() => { /* настройки недоступны — колонки по умолчанию */ });
  }, []);
  const typedCols = useMemo(() => applyColumnConfig(TYPED_COLS.map(c => ({ ...c, label: t('rep.typed.' + c.key) })),
    reportCfg.typed_columns, reportCfg.typed_labels, 'label'), [reportCfg, t]);
  const regCols = useMemo(() => applyColumnConfig(REG_COLS.map(c => ({ ...c, label: t('rep.reg.' + c.key) })),
    reportCfg.regional_columns, reportCfg.regional_labels), [reportCfg, t]);
  const rcCols = useMemo(() => applyColumnConfig(RC_COLS.map(c => ({ ...c, label: t('rep.rc.' + c.key) })),
    reportCfg.regional_count_columns, reportCfg.regional_count_labels), [reportCfg, t]);

  const load = useCallback(async () => {
    setError('');
    try {
      if (tab === 'summary') setSummary(await getJson(`/wb-api/api/v1/reports/summary?from=${from}&to=${to}`));
      if (tab === 'journal') setJournal(await getJson(`/wb-api/api/v1/reports/dispatcher-journal?date=${journalDate}`));
      if (tab === 'driver') setByDriver(await getJson(`/wb-api/api/v1/reports/by-driver?from=${from}&to=${to}`));
      if (tab === 'vehicle') setByVehicle(await getJson(`/wb-api/api/v1/reports/by-vehicle?from=${from}&to=${to}`));
      if (tab === 'fuel') setFuel(await getJson(`/wb-api/api/v1/reports/fuel?from=${from}&to=${to}`));
      if (tab === 'typed') {
        setTypedReport(await getJson(`/wb-api/api/v1/reports/${typedKind}?type=${typedType}&from=${from}&to=${to}${typedFilterQs}`));
      }
      if (tab === 'regional') {
        const tc = typeCompany ? `&typeCompany=${typeCompany}` : '';
        if (regionalMode === 'trans') setRegional(await getJson(`/wb-api/api/v1/reports/regional?bill=${regionalBill}&from=${from}&to=${to}${tc}`));
        else if (regionalMode === 'count') setRegionalCount(await getJson(`/wb-api/api/v1/reports/regional-count?bill=ALL&from=${from}&to=${to}${tc}`));
        else setNorm(await getJson(`/wb-api/api/v1/reports/waybill-norm?type=${normType}&from=${from}&to=${to}${tc}`));
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }, [tab, from, to, journalDate, typedKind, typedType, typedFilterQs, regionalMode, regionalBill, normType, typeCompany]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    getJson<ReportTypeMeta[]>('/wb-api/api/v1/reports/types').then(setTypedTypes).catch(() => { /* каталог не критичен */ });
  }, []);

  /** Скачать XLSX по URL (токен в заголовке → fetch + Blob). */
  // Постраничный вывод длинных таблиц отчётов (замечание владельца 22.09). Итоговые строки
  // остаются под таблицей: они считаются по всему периоду, а не по видимой странице.
  // Сводные по регионам листаются по регионам — иерархия «регион → город → предприятие» не рвётся.
  const journalPage = usePaged(journal, 20);
  const byUnitPage = usePaged(tab === 'driver' ? byDriver : byVehicle, 20);
  const typedPage = usePaged(typedReport?.rows ?? [], 20);
  const normPage = usePaged(norm?.rows ?? [], 20);
  const fuelPage = usePaged(fuel, 20);
  const regionalCountPage = usePaged(regionalCount?.regions ?? [], 3);
  const regionalPage = usePaged(regional?.regions ?? [], 3);

  async function downloadXlsx(path: string, name: string) {
    try {
      const r = await fetch(path, { headers: authHeaders() });
      if (!r.ok) throw new Error(`Ошибка ${r.status}`);
      const blob = await r.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = name;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  const downloadTypedXlsx = () => downloadXlsx(
    `/wb-api/api/v1/reports/${typedKind}.xlsx?type=${typedType}&from=${from}&to=${to}${typedFilterQs}`,
    `отчёт-${typedType.toLowerCase()}-${from}_${to}.xlsx`);
  const downloadRegionalXlsx = () => {
    const tc = typeCompany ? `&typeCompany=${typeCompany}` : '';
    return regionalMode === 'trans'
      ? downloadXlsx(`/wb-api/api/v1/reports/regional.xlsx?bill=${regionalBill}&from=${from}&to=${to}${tc}`, `сводный-перевозки-${regionalBill.toLowerCase()}-${from}_${to}.xlsx`)
      : regionalMode === 'count'
        ? downloadXlsx(`/wb-api/api/v1/reports/regional-count.xlsx?bill=ALL&from=${from}&to=${to}${tc}`, `сводный-количество-${from}_${to}.xlsx`)
        : downloadXlsx(`/wb-api/api/v1/reports/waybill-norm.xlsx?type=${normType}&from=${from}&to=${to}${tc}`, `норматив-выдачи-${normType.toLowerCase()}-${from}_${to}.xlsx`);
  };

  const hasData = tab === 'summary' ? !!summary
    : tab === 'journal' ? journal.length > 0
    : tab === 'driver' ? byDriver.length > 0
    : tab === 'vehicle' ? byVehicle.length > 0
    : tab === 'typed' ? !!typedReport && typedReport.rows.length > 0
    : tab === 'regional' ? (regionalMode === 'trans' ? !!regional && regional.regions.length > 0
        : regionalMode === 'count' ? !!regionalCount && regionalCount.regions.length > 0
        : !!norm && norm.rows.length > 0)
    : fuel.length > 0;

  /** Экспорт текущей вкладки в CSV (открывается в Excel). */
  function exportCurrent() {
    const stripType = (x: string) => tType(x).replace(/\s*\(.*\)/, '');
    if (tab === 'summary' && summary) {
      const rows: unknown[][] = [['Показатель', 'Значение'],
        ['Путевых листов', summary.totals.waybills], ['Завершено', summary.totals.completed],
        ['Активных', summary.totals.active], ['Аннулировано', summary.totals.cancelled],
        ['Пробег, км', summary.totals.distanceKm], ['Топливо выдано, л', summary.totals.fuelGivenLiters],
        ['Выручка, сомони', summary.totals.revenue], [], ['По статусам', '']];
      Object.entries(summary.byStatus).forEach(([k, v]) => rows.push([tStatus(k), v]));
      rows.push([], ['По типам', '']);
      Object.entries(summary.byType).forEach(([k, v]) => rows.push([stripType(k), v]));
      downloadCsv(`отчёт-сводка-${from}_${to}.csv`, rows);
    } else if (tab === 'journal') {
      const rows: unknown[][] = [['Номер', 'Тип', 'ТС', 'Водитель', 'Статус', 'Одометр выезд', 'Одометр возврат']];
      journal.forEach(r => rows.push([r.number ?? '', stripType(r.waybillType), r.vehicleRegNumber, r.driverName, tStatus(r.status), r.odometerExit ?? '', r.odometerEntry ?? '']));
      downloadCsv(`отчёт-журнал-${journalDate}.csv`, rows);
    } else if (tab === 'driver') {
      const rows: unknown[][] = [['Водитель', 'РМА', 'Путевых листов', 'Завершено', 'Пробег, км']];
      byDriver.forEach(r => rows.push([r.fullName ?? '', r.driverRma ?? '', r.waybills, r.completed, r.distanceKm]));
      downloadCsv(`отчёт-по-водителям-${from}_${to}.csv`, rows);
    } else if (tab === 'vehicle') {
      const rows: unknown[][] = [['ТС / госномер', 'Путевых листов', 'Завершено', 'Пробег, км']];
      byVehicle.forEach(r => rows.push([r.vehicleRegNumber ?? '', r.waybills, r.completed, r.distanceKm]));
      downloadCsv(`отчёт-по-тс-${from}_${to}.csv`, rows);
    } else if (tab === 'fuel') {
      const rows: unknown[][] = [['Топливо', 'Выдано, л', 'Остаток, л']];
      fuel.forEach(r => rows.push([r.fuelName ?? FUEL_NAMES[r.fuelType] ?? r.fuelType, r.given, r.remainEnd]));
      downloadCsv(`отчёт-топливо-${from}_${to}.csv`, rows);
    } else if (tab === 'typed' && typedReport) {
      const rows: unknown[][] = [typedCols.map(c => c.label)];
      const line = (r: TypedRow) => typedCols.map(c => {
        const v = r[c.key];
        return typeof v === 'number' ? Math.round(v * 100) / 100 : v;
      });
      typedReport.rows.forEach(r => rows.push(line(r)));
      if (typedReport.totals) rows.push(line({ ...typedReport.totals, label: 'ИТОГО' }));
      downloadCsv(`отчёт-${typedType.toLowerCase()}-${from}_${to}.csv`, rows);
    } else if (tab === 'regional' && regionalMode === 'count' && regionalCount) {
      const rows: unknown[][] = [['Уровень', 'Наименование', ...rcCols.map(c => c.label)]];
      const ind = (lvl: string, name: string, t: RegionalCounts) => [lvl, name, ...rcCols.map(c => t[c.key])];
      regionalCount.regions.forEach(reg => {
        rows.push(ind('Регион', reg.title, reg.totals));
        reg.cities.forEach(ct => {
          rows.push(ind('Город', ct.title, ct.totals));
          ct.companies.forEach(co => rows.push(ind('Предприятие', co.title, co.totals)));
        });
      });
      rows.push(ind('ИТОГО', 'Республика Таджикистан', regionalCount.totals));
      downloadCsv(`сводный-количество-${from}_${to}.csv`, rows);
    } else if (tab === 'regional' && regionalMode === 'norm' && norm) {
      const rows: unknown[][] = [['Организация', 'РМА', 'Регион', 'Выдано', 'Стоянок', 'Выдано/стоянку', 'Норматив', 'Отклонение']];
      norm.rows.forEach(r => rows.push([r.organizationName, r.organizationRma, r.regionTitle, r.issued, r.parkings, r.perParking, r.mustGive, r.deviation]));
      rows.push(['ИТОГО', '', '', norm.totals.issued, norm.totals.parkings, norm.totals.perParking, norm.totals.mustGive, norm.totals.deviation]);
      downloadCsv(`норматив-выдачи-${normType.toLowerCase()}-${from}_${to}.csv`, rows);
    } else if (tab === 'regional' && regional) {
      const rows: unknown[][] = [['Уровень', 'Наименование', ...regCols.map(c => c.label)]];
      const ind = (lvl: string, name: string, t: RegionalInd) => [lvl, name, ...regCols.map(c => Math.round(t[c.key] * 100) / 100)];
      regional.regions.forEach(reg => {
        rows.push(ind('Регион', reg.title, reg.totals));
        reg.cities.forEach(ct => {
          rows.push(ind('Город', ct.title, ct.totals));
          ct.companies.forEach(co => rows.push(ind('Предприятие', co.title, co.totals)));
        });
      });
      rows.push(ind('ИТОГО', 'Республика Таджикистан', regional.totals));
      downloadCsv(`сводный-отчёт-${from}_${to}.csv`, rows);
    }
  }

  const statusMax = summary ? Math.max(1, ...Object.values(summary.byStatus)) : 1;
  const typeMax = summary ? Math.max(1, ...Object.values(summary.byType)) : 1;

  const KPIS = summary ? [
    { label: t('kpi.total'), value: summary.totals.waybills, icon: P.doc, cls: 'ic-blue' },
    { label: t('kpi.done'), value: summary.totals.completed, icon: P.check, cls: 'ic-green' },
    { label: t('st.active'), value: summary.totals.active, icon: P.car, cls: 'ic-cyan' },
    { label: t('kpi.cancel'), value: summary.totals.cancelled, icon: P.alert, cls: 'ic-red' },
  ] : [];

  return (
    <>
      {tab !== 'malum' && tab !== 'journals' && (
      <div className="toolbar">
        <span className="spacer" style={{ flex: 1 }} />
        {tab === 'journal' ? (
          <input type="date" style={{ width: 170 }} value={journalDate} onChange={e => setJournalDate(e.target.value)} />
        ) : (
          <>
            <input type="date" style={{ width: 170 }} value={from} onChange={e => setFrom(e.target.value)} />
            <span>—</span>
            <input type="date" style={{ width: 170 }} value={to} onChange={e => setTo(e.target.value)} />
          </>
        )}
        <button className="btn secondary" onClick={exportCurrent} disabled={!hasData} title={t('rep.export.hint')}>
          <Icon d={P.chart} cls="" style={{ width: 15, height: 15 }} /> {t('rep.export')}
        </button>
        {tab === 'typed' && (
          <button className="btn secondary" onClick={downloadTypedXlsx} disabled={!hasData} title={t('rep.downloadxlsx')}>
            <Icon d={P.chart} cls="" style={{ width: 15, height: 15 }} /> XLSX
          </button>
        )}
        {tab === 'regional' && (
          <button className="btn secondary" onClick={downloadRegionalXlsx} disabled={!hasData} title={t('rep.downloadxlsx')}>
            <Icon d={P.chart} cls="" style={{ width: 15, height: 15 }} /> XLSX
          </button>
        )}
      </div>
      )}

      {tab === 'regional' && (
        <div className="toolbar">
          <button className={`btn ${regionalMode === 'trans' ? '' : 'secondary'}`} onClick={() => setRegionalMode('trans')}>{t('rep.mode.trans')}</button>
          <button className={`btn ${regionalMode === 'count' ? '' : 'secondary'}`} onClick={() => setRegionalMode('count')}>{t('rep.mode.count')}</button>
          <button className={`btn ${regionalMode === 'norm' ? '' : 'secondary'}`} onClick={() => setRegionalMode('norm')}>{t('rep.mode.norm')}</button>
          <span className="spacer" style={{ flex: 1 }} />
          {regionalMode === 'trans' && (
            <select value={regionalBill} onChange={e => setRegionalBill(e.target.value as 'PASSENGER' | 'CARGO')} style={{ width: 160 }}>
              <option value="PASSENGER">{t('rep.opt.passenger')}</option>
              <option value="CARGO">{t('rep.opt.cargo')}</option>
            </select>
          )}
          {regionalMode === 'norm' && (
            <select value={normType} onChange={e => setNormType(e.target.value)} style={{ width: 200 }}>
              <option value="WB_MINIBUS">{tType('WB_MINIBUS')}</option>
              <option value="WB_TAXI">{tType('WB_TAXI')}</option>
              <option value="WB_TRUCK">{tType('WB_TRUCK')}</option>
              <option value="WB_TRUCK_INTL">{tType('WB_TRUCK_INTL')}</option>
              <option value="WB_BUS">{tType('WB_BUS')}</option>
            </select>
          )}
          <select value={typeCompany} onChange={e => setTypeCompany(e.target.value)} style={{ width: 200 }} title={t('rep.crosscut.title')}>
            {TYPE_COMPANY_OPTIONS.map(o => <option key={o.v} value={o.v}>{t(o.k)}</option>)}
          </select>
        </div>
      )}

      {tab === 'typed' && (
        <div className="toolbar">
          <select value={typedKind} onChange={e => setTypedKind(e.target.value as 'passenger' | 'cargo')} style={{ width: 170 }}>
            <option value="passenger">{t('rep.opt.passenger')}</option>
            <option value="cargo">{t('rep.opt.cargo')}</option>
          </select>
          <select value={typedType} onChange={e => setTypedType(e.target.value)} style={{ width: 280 }}>
            {typedTypes.map(rt => <option key={rt.code} value={rt.code}>{rt.label}</option>)}
          </select>
          <span className="spacer" style={{ flex: 1 }} />
          <input type="text" style={{ width: 150 }} value={typedVehicle} onChange={e => setTypedVehicle(e.target.value)}
            placeholder={t('rep.typed.filter.vehicle')} title={t('rep.typed.filter.vehicle')} />
          <input type="text" style={{ width: 150 }} value={typedDriver} onChange={e => setTypedDriver(e.target.value)}
            placeholder={t('rep.typed.filter.driver')} title={t('rep.typed.filter.driver')} />
        </div>
      )}
      {error && <div className="error">{error}</div>}

      {tab === 'malum' && <MalumotnomaTab />}
      {tab === 'journals' && <JournalsTab />}

      {tab === 'summary' && summary && (
        <>
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

          {/* Распределения */}
          <div className="grid-2">
            <div className="card">
              <h2>{t('rep.bystatus')}</h2>
              {Object.entries(summary.byStatus).length === 0
                ? <p style={{ color: 'var(--muted)', fontSize: 13 }}>{t('rep.nodata')}</p>
                : Object.entries(summary.byStatus)
                    .sort((a, b) => b[1] - a[1])
                    .map(([k, v]) => (
                      <BarRow key={k} label={tStatus(k)} value={v} max={statusMax} color={BAR_COLOR[STATUS_LABELS[k]?.color ?? 'blue'] ?? 'var(--blue-600)'} />
                    ))}
            </div>
            <div className="card">
              <h2>{t('dash.bytype')}</h2>
              {Object.entries(summary.byType).length === 0
                ? <p style={{ color: 'var(--muted)', fontSize: 13 }}>{t('rep.nodata')}</p>
                : Object.entries(summary.byType)
                    .sort((a, b) => b[1] - a[1])
                    .map(([k, v]) => (
                      <BarRow key={k} label={tType(k).replace(/\s*\(.*\)/, '')} value={v} max={typeMax} color="var(--blue-600)" />
                    ))}
            </div>
          </div>

          {/* Показатели периода */}
          <div className="card">
            <h2>{t('rep.periodmetrics')}</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14 }}>
              <div style={{ background: 'var(--blue-050)', borderRadius: 11, padding: 16 }}>
                <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{t('col.mileage')}</div>
                <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--blue-700)', marginTop: 4 }}>{summary.totals.distanceKm.toLocaleString('ru-RU')} <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--muted)' }}>{t('unit.km')}</span></div>
              </div>
              <div style={{ background: 'var(--cyan-050)', borderRadius: 11, padding: 16 }}>
                <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{t('rep.fuelgiven')}</div>
                <div style={{ fontSize: 22, fontWeight: 800, color: '#0b7f97', marginTop: 4 }}>{summary.totals.fuelGivenLiters.toLocaleString('ru-RU')} <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--muted)' }}>{t('unit.l')}</span></div>
              </div>
              <div style={{ background: 'var(--green-050)', borderRadius: 11, padding: 16 }}>
                <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{t('rep.revenue')}</div>
                <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--green)', marginTop: 4 }}>{summary.totals.revenue.toLocaleString('ru-RU')} <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--muted)' }}>{t('unit.somoni')}</span></div>
              </div>
            </div>
          </div>
        </>
      )}

      {tab === 'journal' && (
        <div className="card">
          <h2>{t('rep.journal.title')} {journalDate}</h2>
          <table>
            <thead><tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.vehicle')}</th><th>{t('col.driver')}</th><th>{t('col.status')}</th><th>{t('rep.col.odoexit')}</th><th>{t('rep.col.odoentry')}</th></tr></thead>
            <tbody>
              {journalPage.view.map((r, i) => {
                const s = STATUS_LABELS[r.status] ?? { label: r.status, color: 'gray' };
                return (
                  <tr key={i}>
                    <td><span className="number">{r.number ?? '—'}</span></td>
                    <td>{tType(r.waybillType).replace(/\s*\(.*\)/, '')}</td>
                    <td>{r.vehicleRegNumber}</td>
                    <td>{r.driverName}</td>
                    <td><span className={`badge ${s.color}`}>{tStatus(r.status)}</span></td>
                    <td>{r.odometerExit ?? '—'}</td>
                    <td>{r.odometerEntry ?? '—'}</td>
                  </tr>
                );
              })}
              {journal.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
          <Pager {...journalPage} />
        </div>
      )}

      {(tab === 'driver' || tab === 'vehicle') && (
        <div className="card">
          <h2>{tab === 'driver' ? t('rep.tab.driver') : t('rep.byvehicle')}</h2>
          <table>
            <thead><tr><th>{tab === 'driver' ? t('col.driver') : t('col.vehicle')}</th><th>{t('rep.col.wbtotal')}</th><th>{t('kpi.done')}</th><th>{t('drv.kpi.km')}</th></tr></thead>
            <tbody>
              {byUnitPage.view.map((r, i) => (
                <tr key={i}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{tab === 'driver' ? `${r.fullName ?? ''} (${r.driverRma ?? ''})` : r.vehicleRegNumber}</td>
                  <td>{r.waybills}</td><td>{r.completed}</td><td>{r.distanceKm.toLocaleString('ru-RU')}</td>
                </tr>
              ))}
              {(tab === 'driver' ? byDriver : byVehicle).length === 0 && <tr><td colSpan={4} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
          <Pager {...byUnitPage} />
        </div>
      )}

      {tab === 'typed' && (
        <div className="card" style={{ overflowX: 'auto' }}>
          <h2>{typedReport?.typeLabel ?? t('rep.crosscut')} · {typedKind === 'passenger' ? t('rep.pax') : t('rep.cargo')} · {from} — {to}</h2>
          <table>
            <thead><tr>{typedCols.map(c => <th key={c.key}>{c.label}</th>)}</tr></thead>
            <tbody>
              {typedPage.view.map((r, i) => (
                <tr key={i}>
                  {typedCols.map(c => {
                    const v = r[c.key];
                    return <td key={c.key} style={c.key === 'label' ? { fontWeight: 600, color: 'var(--ink)' } : undefined}>
                      {typeof v === 'number' ? (Math.round(v * 100) / 100).toLocaleString('ru-RU') : v}
                    </td>;
                  })}
                </tr>
              ))}
              {typedReport?.totals && (
                <tr style={{ fontWeight: 700, borderTop: '2px solid var(--line)' }}>
                  {typedCols.map(c => {
                    const v = c.key === 'label' ? t('rep.total') : typedReport.totals![c.key];
                    return <td key={c.key}>{typeof v === 'number' ? (Math.round(v * 100) / 100).toLocaleString('ru-RU') : v}</td>;
                  })}
                </tr>
              )}
              {(!typedReport || typedReport.rows.length === 0) && <tr><td colSpan={typedCols.length} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
          <Pager {...typedPage} />
        </div>
      )}

      {tab === 'regional' && regionalMode === 'count' && (
        <div className="card" style={{ overflowX: 'auto' }}>
          <h2>{t('rep.h2.count')} · {regionalCount ? `${regionalCount.year} / ${regionalCount.prevYear}` : ''} · {from} — {to}</h2>
          <p style={{ fontSize: 12, color: 'var(--muted)', marginTop: 0 }}>
            «Выдан» = присвоен номер; «обработан» = зафиксирован одометр возврата. Столбцы 1–19 — см. §6.3.
            Столбцы 20–21 — грузовые ПЛ (2-Б/5Б-БМ/спецтехника/опасные грузы) за период и сколько из них с заполненной накладной.
          </p>
          <table>
            <thead><tr><th>{t('rep.h.level')}</th><th>{t('rep.h.name')}</th>{rcCols.map(c => <th key={c.key}>{c.label}</th>)}</tr></thead>
            <tbody>
              {regionalCountPage.view.flatMap(reg => [
                <tr key={`rc-${reg.title}`} style={{ background: 'var(--amber-050, #fef9e7)', fontWeight: 700 }}>
                  <td>{t('col.region')}</td><td>{reg.title}</td>
                  {rcCols.map(c => <td key={c.key}>{reg.totals[c.key].toLocaleString('ru-RU')}</td>)}
                </tr>,
                ...reg.cities.flatMap(ct => [
                  <tr key={`cc-${reg.title}-${ct.title}`} style={{ fontStyle: 'italic' }}>
                    <td>&nbsp;&nbsp;{t('rep.city')}</td><td>{ct.title}</td>
                    {rcCols.map(c => <td key={c.key}>{ct.totals[c.key].toLocaleString('ru-RU')}</td>)}
                  </tr>,
                  ...ct.companies.map(co => (
                    <tr key={`coc-${co.organizationRma}`}>
                      <td>&nbsp;&nbsp;&nbsp;&nbsp;{t('rep.enterprise')}</td><td>{co.title}</td>
                      {rcCols.map(c => <td key={c.key}>{co.totals[c.key].toLocaleString('ru-RU')}</td>)}
                    </tr>
                  )),
                ]),
              ])}
              {regionalCount && (
                <tr style={{ fontWeight: 700, borderTop: '2px solid var(--line)' }}>
                  <td>{t('rep.total')}</td><td>{t('rep.republic')}</td>
                  {rcCols.map(c => <td key={c.key}>{regionalCount.totals[c.key].toLocaleString('ru-RU')}</td>)}
                </tr>
              )}
              {(!regionalCount || regionalCount.regions.length === 0) && <tr><td colSpan={rcCols.length + 2} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
          <Pager {...regionalCountPage} unitLabel={t('rep.regions.total')} />
        </div>
      )}

      {tab === 'regional' && regionalMode === 'norm' && (
        <div className="card" style={{ overflowX: 'auto' }}>
          <h2>{t('rep.h2.norm')} · {norm?.waybillTypeLabel ?? ''} · {t('rep.h2.normperparking')}: {norm?.mustGivePerParking ?? 0} · {from} — {to}</h2>
          <p style={{ fontSize: 12, color: 'var(--muted)', marginTop: 0 }}>
            (1) выдано ПЛ · (2) стоянок (ТС нужного вида) · (3) выдано на стоянку · (4) норматив = (2)×норма · (5) отклонение = (1)−(4)
          </p>
          <table>
            <thead><tr><th>{t('col.org')}</th><th>{t('col.region')}</th><th>{t('rep.norm.issued')}</th><th>{t('rep.norm.parkings')}</th><th>{t('rep.norm.perparking')}</th><th>{t('rep.norm.norm')}</th><th>{t('rep.norm.deviation')}</th></tr></thead>
            <tbody>
              {normPage.view.map(r => (
                <tr key={r.organizationRma}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{r.organizationName}</td>
                  <td>{r.regionTitle}</td>
                  <td>{r.issued}</td><td>{r.parkings}</td><td>{r.perParking}</td><td>{r.mustGive}</td>
                  <td style={{ color: r.deviation < 0 ? 'var(--red)' : 'var(--green)' }}>{r.deviation}</td>
                </tr>
              ))}
              {norm && (
                <tr style={{ fontWeight: 700, borderTop: '2px solid var(--line)' }}>
                  <td>{t('rep.total')}</td><td></td>
                  <td>{norm.totals.issued}</td><td>{norm.totals.parkings}</td><td>{norm.totals.perParking}</td>
                  <td>{norm.totals.mustGive}</td><td>{norm.totals.deviation}</td>
                </tr>
              )}
              {(!norm || norm.rows.length === 0) && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
          <Pager {...normPage} />
        </div>
      )}

      {tab === 'regional' && isMintrans && <PlansEditor />}

      {tab === 'regional' && regionalMode === 'trans' && (
        <div className="card" style={{ overflowX: 'auto' }}>
          <h2>{t('rep.h2.summary')} · {regionalBill === 'CARGO' ? t('rep.cargo') : t('rep.pax')} · {regional ? `${regional.year} / ${regional.prevYear}` : ''} · {from} — {to}</h2>
          <p style={{ fontSize: 12, color: 'var(--muted)', marginTop: 0 }}>
            {regionalBill === 'CARGO' ? t('rep.desc.cargo') : t('rep.desc.pax')}
          </p>
          <table>
            <thead><tr><th>{t('rep.h.level')}</th><th>{t('rep.h.name')}</th>{regCols.map(c => <th key={c.key}>{c.label}</th>)}</tr></thead>
            <tbody>
              {regionalPage.view.flatMap(reg => [
                <tr key={`r-${reg.title}`} style={{ background: 'var(--amber-050, #fef9e7)', fontWeight: 700 }}>
                  <td>{t('col.region')}</td><td>{reg.title}</td>
                  {regCols.map(c => <td key={c.key}>{(Math.round(reg.totals[c.key] * 100) / 100).toLocaleString('ru-RU')}</td>)}
                </tr>,
                ...reg.cities.flatMap(ct => [
                  <tr key={`c-${reg.title}-${ct.title}`} style={{ fontStyle: 'italic' }}>
                    <td>&nbsp;&nbsp;{t('rep.city')}</td><td>{ct.title}</td>
                    {regCols.map(c => <td key={c.key}>{(Math.round(ct.totals[c.key] * 100) / 100).toLocaleString('ru-RU')}</td>)}
                  </tr>,
                  ...ct.companies.map(co => (
                    <tr key={`co-${co.organizationRma}`}>
                      <td>&nbsp;&nbsp;&nbsp;&nbsp;{t('rep.enterprise')}</td><td>{co.title}</td>
                      {regCols.map(c => <td key={c.key}>{(Math.round(co.totals[c.key] * 100) / 100).toLocaleString('ru-RU')}</td>)}
                    </tr>
                  )),
                ]),
              ])}
              {regional && (
                <tr style={{ fontWeight: 700, borderTop: '2px solid var(--line)' }}>
                  <td>{t('rep.total')}</td><td>{t('rep.republic')}</td>
                  {regCols.map(c => <td key={c.key}>{(Math.round(regional.totals[c.key] * 100) / 100).toLocaleString('ru-RU')}</td>)}
                </tr>
              )}
              {(!regional || regional.regions.length === 0) && <tr><td colSpan={regCols.length + 2} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
          <Pager {...regionalPage} unitLabel={t('rep.regions.total')} />
        </div>
      )}

      {tab === 'fuel' && (
        <div className="card">
          <h2>{t('rep.fuel.h')}</h2>
          <table>
            <thead><tr><th>{t('rep.col.fueltype')}</th><th>{t('rep.col.given')}</th><th>{t('rep.col.remain')}</th></tr></thead>
            <tbody>
              {fuelPage.view.map((r, i) => (
                <tr key={i}><td style={{ fontWeight: 600, color: 'var(--ink)' }}>{r.fuelName ?? t('fuel.type.' + r.fuelType)}</td><td>{r.given.toLocaleString('ru-RU')}</td><td>{r.remainEnd.toLocaleString('ru-RU')}</td></tr>
              ))}
              {fuel.length === 0 && <tr><td colSpan={3} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('common.norecords')}</td></tr>}
            </tbody>
          </table>
          <Pager {...fuelPage} />
        </div>
      )}
    </>
  );
}
