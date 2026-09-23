'use client';

import { use, useCallback, useEffect, useState } from 'react';
import { md, wb, Waybill, Title, StatusEvent, Payment, STATUS_LABELS, type GpsPing, type FieldDefinition, type Inspection } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { verifyLink } from '@/lib/verify';
import { ExpensesSection } from './ExpensesSection';
import { Attachments } from './Attachments';
import { Consignment } from './Consignment';
import QRCode from 'qrcode';

type Employees = { doctors: { rma: string; name: string }[]; mechanics: { rma: string; name: string }[]; dispatchers: { rma: string; name: string }[] };

// Целевые статусы, уже отражённые титулом на том же шаге (T1/CORRECTION → CREATED,
// T4 → ACTIVE, T5 → RETURNED; T2/T3 при отказе тоже пишут титул с verdict «НЕ ДОПУЩЕН»
// → MED_REJECTED/TECH_REJECTED) — их не дублируем отдельной строкой из истории статусов
// в едином журнале событий, иначе один и тот же шаг попадал бы в журнал дважды.
const TITLE_COVERED_STATUSES = new Set(['CREATED', 'ACTIVE', 'RETURNED', 'MED_REJECTED', 'TECH_REJECTED']);
const CANCEL_LIKE_STATUSES = new Set(['CANCELLED', 'EXPIRED', 'BLOCKED']);

type JournalEvent = { at: string; label: string; who: string; tone: 'ok' | 'bad' };

function buildJournal(titles: Title[], history: StatusEvent[], t: (k: string) => string, tStatus: (s: string) => string): JournalEvent[] {
  // Т4/Т5, подписанные до 23.09.2026, не несут Ф.И.О. — берём имя того же РМА из другого титула (Т1).
  const nameByRma = new Map<string, string>();
  for (const x of titles) {
    const xd = (x.data ?? {}) as Record<string, unknown>;
    const n = (xd.employeeName ?? xd.dispatcher) as string | undefined;
    if (n && !nameByRma.has(x.signerRma)) nameByRma.set(x.signerRma, n);
  }
  const titleEvents: JournalEvent[] = titles.map(ttl => {
    const d = (ttl.data ?? {}) as Record<string, unknown>;
    const name = ((d.employeeName ?? d.dispatcher) as string | undefined) || nameByRma.get(ttl.signerRma);
    const roleLabel = t(`role.${ttl.signerRole}`);
    const who = `${roleLabel || ttl.signerRole} (${name ?? ttl.signerRma})`;
    const baseKey = `wb.title.${ttl.titleType}`;
    const base = t(baseKey) !== baseKey ? t(baseKey) : ttl.titleType;
    const verdict = d.verdict as string | undefined;
    const rejected = typeof verdict === 'string' && verdict.toUpperCase().includes('НЕ');
    const label = verdict != null ? `${base} — ${rejected ? t('st.failed') : t('st.passed')}` : base;
    return { at: ttl.signedAt, label, who, tone: rejected ? 'bad' : 'ok' };
  });
  const statusEvents: JournalEvent[] = history
    .filter(h => !TITLE_COVERED_STATUSES.has(h.toStatus))
    .map(h => ({
      at: h.createdAt,
      label: (h.fromStatus ? `${tStatus(h.fromStatus)} → ` : '') + tStatus(h.toStatus) + (h.reason ? ` — ${h.reason}` : ''),
      who: h.actor ?? '—',
      tone: CANCEL_LIKE_STATUSES.has(h.toStatus) ? 'bad' as const : 'ok' as const,
    }));
  return [...titleEvents, ...statusEvents].sort((a, b) => a.at.localeCompare(b.at));
}

export default function WaybillCard({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const [w, setW] = useState<Waybill | null>(null);
  const [titles, setTitles] = useState<Title[]>([]);
  const [history, setHistory] = useState<StatusEvent[]>([]);
  const [emp, setEmp] = useState<Employees>({ doctors: [], mechanics: [], dispatchers: [] });
  const [qrUrl, setQrUrl] = useState('');
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [odometerEntry, setOdometerEntry] = useState('');
  const [motorHoursEntry, setMotorHoursEntry] = useState(''); // моточасы возврата — спецтехника
  const [retMetrics, setRetMetrics] = useState({ transportWork: '', trips: '', conditionerHours: '' }); // факт. показатели рейса
  // Международные формы (MIGRATION.md 3.14/3.15): прибытие в пункт назначения (5Б-БМ/4-МБМ) и пассажиры (4-МБМ).
  const [retIntl, setRetIntl] = useState({ arrivalTime: '', passengersCount: '' });
  const [fuelCalc, setFuelCalc] = useState<Record<string, unknown> | null>(null);
  const [workDays, setWorkDays] = useState<Record<string, unknown>[]>([]);
  const [dayForm, setDayForm] = useState({ workDate: '', exitTime: '06:00', entryTime: '', odometerExit: '', odometerEntry: '', laps: '', revenue: '' });
  const [fuelForm, setFuelForm] = useState({ fuelType: '1', fuelGiven: '', remainBeforeExit: '', additionalGiven: '', returned: '', coefBelow0: '', beGiven: '', workDayId: '' });
  // Посуточный расчёт топлива многодневных 1-А/3-С (MIGRATION.md 5.4, B10): разбивка по дням из POST /calculation.
  const [dailyCalc, setDailyCalc] = useState<Record<string, unknown> | null>(null);
  // Подсказка «из предыдущего ПЛ этого ТС» (legacy parking_fuel_left/parking_fuel_give) — текст под формой топлива.
  const [fuelHint, setFuelHint] = useState('');
  const [replacement, setReplacement] = useState(''); // РМА нового водителя или госномер нового ТС
  const [candidates, setCandidates] = useState<{ value: string; label: string }[]>([]);
  const [blockReason, setBlockReason] = useState(''); // разблокировка (Минтранс) — свободное обоснование
  const [kassaRma, setKassaRma] = useState(''); // касса 3-С: РМА кассира (сотрудник типа 5), MIGRATION.md 4.7
  const [showBlockForm, setShowBlockForm] = useState(false);
  const [blockAct, setBlockAct] = useState({ reasonCode: '', description: '', place: '', protocolNumber: '' });
  const [reasons, setReasons] = useState<{ code: string; label: string }[]>([]);
  const [inspections, setInspections] = useState<Inspection[]>([]);
  const [payment, setPayment] = useState<Payment | null>(null);
  const [tab, setTab] = useState('main');
  const [gps, setGps] = useState<GpsPing | null>(null);
  // Определения доп.полей типа (конструктор полей) — для подписей значений typeData.custom.
  // all=true: поле могло быть отключено после выдачи ПЛ, но его значение в документе остаётся.
  const [fieldDefs, setFieldDefs] = useState<FieldDefinition[]>([]);
  const { t, tType, tStatus, lang } = useT();
  const { roles } = useAuth();

  // Автоподстановка остатка до выезда и нормы к выдаче из предыдущего ПЛ того же ТС по выбранному
  // виду топлива (перенос legacy: при смене вида топлива форма перезапрашивала parking_fuel_left/give).
  useEffect(() => {
    if (!w || !['ISSUED', 'ACTIVE', 'RETURNED', 'READY'].includes(w.status)) return;
    let cancelled = false;
    wb.fuelPrefill(id, Number(fuelForm.fuelType)).then(p => {
      if (cancelled) return;
      setFuelForm(f => ({
        ...f,
        remainBeforeExit: p.remainBeforeExit != null ? String(p.remainBeforeExit) : '',
        beGiven: p.beGiven != null ? String(p.beGiven) : '',
      }));
      setFuelHint(p.found ? `${t('wbd.fuelprefill.from')}${p.sourceWaybillNumber ? ` (${p.sourceWaybillNumber})` : ''}` : t('wbd.fuelprefill.none'));
    }).catch(() => { if (!cancelled) setFuelHint(''); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, fuelForm.fuelType, w?.status]);

  const reload = useCallback(async () => {
    const data = await wb.get(id);
    setW(data);
    wb.gpsLast(data.vehicleRegNumber).then(setGps).catch(() => setGps(null));
    md.fieldDefinitions(data.waybillType, true).then(setFieldDefs).catch(() => setFieldDefs([]));
    setTitles(await wb.titles(id));
    setHistory(await wb.history(id));
    wb.inspections(id).then(setInspections).catch(() => setInspections([]));
    if (data.number) {
      try {
        const { jws } = await wb.qr(id);
        // QR кодирует URL страницы проверки — камера телефона инспектора открывает её напрямую
        const verifyUrl = verifyLink(jws);
        setQrUrl(await QRCode.toDataURL(verifyUrl, { width: 240, margin: 1 }));
      } catch { /* QR доступен с READY */ }
    }
    try {
      const wd = await fetch(`/wb-api/api/v1/waybills/${id}/work-days`, { headers: (await import('@/lib/api')).authHeaders() });
      if (wd.ok) setWorkDays(await wd.json());
    } catch { /* work-days могут отсутствовать */ }
    const list = await md.employees(data.organizationRma);
    setEmp({
      doctors: list.filter(e => e.type === 1).map(e => ({ rma: String(e.rma), name: String(e.name) })),
      mechanics: list.filter(e => e.type === 2).map(e => ({ rma: String(e.rma), name: String(e.name) })),
      dispatchers: list.filter(e => e.type === 3).map(e => ({ rma: String(e.rma), name: String(e.name) })),
    });
    // Кандидаты для замены после недопуска/отклонения (корректирующий титул)
    if (data.status === 'MED_REJECTED') {
      const drivers = await md.drivers(data.organizationRma);
      setCandidates(drivers
        .filter(d => String(d.rma) !== data.driverRma)
        .map(d => ({ value: String(d.rma), label: `${String(d.fullName)} (${String(d.rma)})` })));
    } else if (data.status === 'TECH_REJECTED') {
      const vehicles = await md.vehicles(data.organizationRma);
      setCandidates(vehicles
        .filter(v => String(v.registrationNumber) !== data.vehicleRegNumber)
        .map(v => ({ value: String(v.registrationNumber), label: `${String(v.registrationNumber)} · ${String(v.brand ?? '')}` })));
    } else {
      setCandidates([]);
    }
    setReplacement('');
    // Оплата (если статус её требует или она уже была)
    if (data.status === 'AWAITING_PAYMENT' || data.status === 'PAID') {
      try { setPayment(await wb.payment(id)); } catch { setPayment(null); }
    } else {
      setPayment(null);
    }
  }, [id]);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);
  // Классификатор оснований блокировки — нужен и для формы, и чтобы расшифровать историю.
  useEffect(() => { wb.inspectionReasons().then(setReasons).catch(() => setReasons([])); }, []);

  async function act(label: string, fn: () => Promise<unknown>) {
    setError(''); setOk('');
    try {
      await fn();
      setOk(label + t('wb.done.suffix'));
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (!w) return error ? <div className="error">{error}</div> : <p>{t('common.loading')}</p>;

  const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
  const dispatcher = emp.dispatchers[0]?.rma;
  const doctor = emp.doctors[0]?.rma;
  const mechanic = emp.mechanics[0]?.rma;

  // Доступ к действиям жизненного цикла по роли (админы — сквозной доступ для контроля).
  const has = (r: string) => roles.includes(r);
  const isAdmin = has('SYSTEM_ADMIN') || has('COMPANY_ADMIN') || has('BRANCH_ADMIN');
  const canDispatch = has('DISPATCHER') || isAdmin;   // Т1, выдача, Т4, Т5, закрытие, замена, аннулирование
  const canPay = has('ACCOUNTANT') || isAdmin;        // подтверждение оплаты
  const canBlock = has('INSPECTOR') || has('SYSTEM_ADMIN');   // блокировка инспектором
  const canKassa = has('ACCOUNTANT') || has('SYSTEM_ADMIN');  // касса 3-С «выручка сдана» (4.7)
  const canUnblock = has('SYSTEM_ADMIN');             // разблокировка — только Минтранс

  // Данные типа ПЛ (те же, что уже приходят в w.typeData) — используем только их, ничего не выдумываем
  const td: Record<string, unknown> = w.typeData ?? {};
  const intlKeys = ['loadCountry', 'unloadCountry', 'transitCountries', 'permitNumber', 'visaValidTo', 'cargoName', 'bbaNumber'];
  const isIntl = intlKeys.some(k => k in td);
  const hasServiceInfo = 'serviceKind' in td || 'shipmentKind' in td;
  const isSpecial = w.waybillType === 'WB_SPECIAL';
  const isCargo = ['WB_TRUCK', 'WB_TRUCK_INTL', 'WB_SPECIAL', 'WB_DANGEROUS'].includes(w.waybillType);
  const isIntlForm = w.waybillType === 'WB_TRUCK_INTL' || w.waybillType === 'WB_PAX_INTL'; // 5Б-БМ / 4-МБМ
  // Накладная (борхат/CMR) — только для форм, где у оригинала есть отдельный документ приложения.
  const hasConsignment = ['WB_TRUCK', 'WB_TRUCK_INTL', 'WB_DANGEROUS'].includes(w.waybillType);
  const showRoute = isIntl || hasServiceInfo || isSpecial || !!w.route || !!w.schedule;
  const trailers = Array.isArray(td.trailers) ? (td.trailers as { registrationNumber: string; brand: string }[]) : [];
  const titlesWithData = titles.filter(t => t.data && Object.keys(t.data).length > 0);
  const mileage = (w.odometerExit != null && w.odometerEntry != null) ? w.odometerEntry - w.odometerExit : null;
  // Спецтехника: отработано моточасов (возврат − выезд), если оба показателя есть
  const mhExit = td.motorHoursExit != null ? Number(td.motorHoursExit) : null;
  const mhEntry = td.motorHoursEntry != null ? Number(td.motorHoursEntry) : null;
  const motorHoursWorked = (mhExit != null && mhEntry != null) ? +(mhEntry - mhExit).toFixed(1) : null;
  // Доп.поля (конструктор полей): значения из typeData.custom с подписями из определений
  // (порядок — sortOrder определения; поля без определения — в конце, по ключу).
  const customRaw = (td.custom && typeof td.custom === 'object') ? td.custom as Record<string, unknown> : {};
  const customEntries: { key: string; label: string; value: string }[] = (() => {
    const defByKey = new Map(fieldDefs.map(d => [d.fieldKey, d]));
    const fmt = (d: FieldDefinition | undefined, v: unknown) => {
      const s = String(v);
      if (d?.dataType === 'BOOLEAN' || s === 'true' || s === 'false') return s === 'true' ? t('fld.yes') : t('fld.no');
      return s;
    };
    return Object.entries(customRaw)
      .map(([key, v]) => {
        const d = defByKey.get(key);
        const label = d ? (lang === 'tj' && d.labelTj ? d.labelTj : d.labelRu) : key;
        return { key, label, value: fmt(d, v), order: d?.sortOrder ?? 9999 };
      })
      .sort((a, b) => a.order - b.order || a.key.localeCompare(b.key));
  })();

  const vehicleName = String(w.vehicleSnapshot?.brand ?? '');
  const driverName = String(w.driverSnapshot?.fullName ?? w.driverRma);
  const orgName = String(w.organizationSnapshot?.name ?? w.organizationRma);
  const validFrom = w.validFrom ? new Date(w.validFrom).toLocaleString('ru-RU') : '—';
  const validTo = w.validTo ? new Date(w.validTo).toLocaleString('ru-RU') : '—';

  const sumLabel: React.CSSProperties = { fontSize: 11.5, color: 'var(--muted)', fontWeight: 500, textTransform: 'uppercase', letterSpacing: '.03em' };
  const sumValue: React.CSSProperties = { marginTop: 6, fontSize: 14, color: 'var(--ink)', fontWeight: 600 };

  const tabs: { key: string; label: string }[] = [
    { key: 'main', label: t('wb.tab.main') },
    { key: 'driver', label: t('col.driver') },
    { key: 'vehicle', label: t('col.transport') },
    ...(showRoute ? [{ key: 'route', label: t('col.route') }] : []),
    { key: 'fuel', label: t('col.fuel') },
    ...(hasConsignment ? [{ key: 'consignment', label: t('cn.h') }] : []),
    { key: 'expenses', label: t('wb.tab.expenses') },
    { key: 'attachments', label: t('wb.tab.attachments') },
    { key: 'inspections', label: t('wb.tab.inspections') },
    { key: 'qr', label: 'QR' },
    { key: 'history', label: t('wb.tab.history') },
  ];

  // Плашка госномера (класс .plate из дизайн-системы)
  const plate = w.vehicleRegNumber
    ? <span className="plate"><span className="p-main">{w.vehicleRegNumber}</span></span>
    : null;

  return (
    <>
      {/* Шапка документа */}
      <div className="toolbar">
        <a href="/waybills" className="btn secondary" style={{ textDecoration: 'none' }} title={t('nav.waybill.registry')}>
          ← {t('wb.btn.back')}
        </a>
        <h1 style={{ marginBottom: 0 }}>
          {t('wb.card.h')} {w.number ? <span className="number">{w.number}</span> : t('wb.nonumber')}
        </h1>
        {w.branchSerial != null && (
          <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>№ {w.branchSerial}/{w.branchSerialYear} по журналу организации</span>
        )}
        <span style={{ color: 'var(--muted)', fontSize: 13 }}>{tType(w.waybillType)}</span>
        <span className={`badge ${s.color}`}>{tStatus(w.status)}</span>
        <span className="spacer" />
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
          {w.number && <a className="btn secondary" href={`/waybills/${id}/print`}>{t('wb.printform')}</a>}
          <a className="btn secondary" href={`/waybills/new?from=${id}`}>{t('wb.btn.copy')}</a>
          {w.status === 'DRAFT' && canDispatch && (
            <button className="btn" disabled={!dispatcher}
              onClick={() => act(t('wb.act.t1'), () => wb.post(`/${id}/titles/t1`, { dispatcherRma: dispatcher }))}>
              {t('wb.btn.signt1')} ({t('wb.r.dispatcher')} {emp.dispatchers[0]?.name ?? '—'})
            </button>
          )}
          {/* Предрейсовый медосмотр (Т2) и техконтроль (Т3) проводятся ТОЛЬКО в АРМ врача (/med)
              и АРМ механика (/tech) с реальными показателями — с карточки не подтверждаются. */}
          {w.status === 'CREATED' && (!w.medPassed || !w.techPassed) && (
            <span className="hint" style={{ margin: 0, padding: '8px 12px' }}>{t('wb.await.exams')}</span>
          )}
          {w.status === 'AWAITING_PAYMENT' && (
            <span>
              {payment && (
                <span className="badge amber" style={{ marginRight: 8 }}>
                  {t('wb.topay')}: {payment.amount} {payment.currency}
                </span>
              )}
              {canPay && (
                <button className="btn"
                  onClick={() => act(t('wb.act.paid'), () => wb.post(`/${id}/confirm-payment`, { method: 'BANK' }))}>
                  {t('wb.btn.confirmpay')} ({t('wb.r.accountant')})
                </button>
              )}
            </span>
          )}
          {w.status === 'READY' && canDispatch && (
            <button className="btn" onClick={() => act(t('wb.act.issued'), () => wb.post(`/${id}/issue`, { driverConfirmation: 'PIN' }))}>
              {t('wb.btn.issue')}
            </button>
          )}
          {w.status === 'ISSUED' && canDispatch && (
            <button className="btn" disabled={!dispatcher}
              onClick={() => act(t('wb.act.activated'), () => wb.post(`/${id}/activate`, { dispatcherRma: dispatcher }))}>
              {t('wb.btn.t4')}
            </button>
          )}
          {w.status === 'ACTIVE' && canDispatch && (
            <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              {isSpecial && (
                <input type="number" min={0} step="0.1" style={{ width: 180 }} placeholder={t('wb.ph.motohours')}
                  value={motorHoursEntry} onChange={e => setMotorHoursEntry(e.target.value)} />
              )}
              <input type="number" inputMode="numeric" min={0} style={{ width: 180 }}
                placeholder={isSpecial ? t('wb.ph.odoentry.opt') : t('wb.ph.odoentry')}
                value={odometerEntry} onChange={e => setOdometerEntry(e.target.value)} />
              {isCargo ? (
                <>
                  <input type="number" min={0} step="0.1" style={{ width: 150 }} placeholder="Транс. работа P, т·км"
                    value={retMetrics.transportWork} onChange={e => setRetMetrics(m => ({ ...m, transportWork: e.target.value }))} />
                  <input type="number" min={0} step="1" style={{ width: 110 }} placeholder="Ездок Z"
                    value={retMetrics.trips} onChange={e => setRetMetrics(m => ({ ...m, trips: e.target.value }))} />
                </>
              ) : (
                <input type="number" min={0} step="0.1" style={{ width: 150 }} placeholder="Часы кондиционера"
                  value={retMetrics.conditionerHours} onChange={e => setRetMetrics(m => ({ ...m, conditionerHours: e.target.value }))} />
              )}
              {isIntlForm && (
                <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center', fontSize: 12, color: 'var(--muted)' }}>
                  {t('wb.ph.arrival')}
                  <input type="datetime-local" style={{ width: 200 }} title={t('wb.ph.arrival')}
                    value={retIntl.arrivalTime} onChange={e => setRetIntl(m => ({ ...m, arrivalTime: e.target.value }))} />
                </label>
              )}
              {w.waybillType === 'WB_PAX_INTL' && (
                <input type="number" min={0} step="1" style={{ width: 170 }} placeholder={t('wb.ph.passengers')} title={t('wb.ph.passengers')}
                  value={retIntl.passengersCount} onChange={e => setRetIntl(m => ({ ...m, passengersCount: e.target.value }))} />
              )}
              <button className="btn"
                disabled={!dispatcher || (isSpecial
                  ? (motorHoursEntry.trim() === '' || !Number.isFinite(Number(motorHoursEntry)))
                  : (!Number.isFinite(Number(odometerEntry)) || odometerEntry.trim() === ''))}
                onClick={() => act(t('wb.act.returned'), () => wb.post(`/${id}/return`, {
                  dispatcherRma: dispatcher,
                  odometerEntry: odometerEntry.trim() !== '' ? Number(odometerEntry) : (w.odometerExit ?? 0),
                  ...(isSpecial ? { motorHoursEntry: Number(motorHoursEntry) } : {}),
                  ...(retMetrics.transportWork.trim() !== '' ? { transportWork: Number(retMetrics.transportWork) } : {}),
                  ...(retMetrics.trips.trim() !== '' ? { trips: Number(retMetrics.trips) } : {}),
                  ...(retMetrics.conditionerHours.trim() !== '' ? { conditionerHours: Number(retMetrics.conditionerHours) } : {}),
                  ...(isIntlForm && retIntl.arrivalTime.trim() !== '' ? { arrivalTime: retIntl.arrivalTime.trim() } : {}),
                  ...(w.waybillType === 'WB_PAX_INTL' && retIntl.passengersCount.trim() !== '' ? { passengersCount: Number(retIntl.passengersCount) } : {}),
                }))}>
                {t('wb.btn.t5')}
              </button>
            </span>
          )}
          {/* Послерейсовый медосмотр (Т6): врач проводит его в своём кабинете (/med, очередь Т6) —
              с карточки ведём туда же, как и для Т2. Диспетчеру — подсказка, чего ждёт закрытие. */}
          {w.status === 'RETURNED' && !titles.some(x => x.titleType === 'T6') && (
            (has('DOCTOR') || has('SYSTEM_ADMIN'))
              ? <a className="btn" href={`/med?t6=${id}`} style={{ textDecoration: 'none' }} data-testid="wb-t6-link">
                  {lang === 'tj' ? 'Гузаронидани ташхиси баъд аз рейс (Т6)' : 'Провести послерейсовый медосмотр (Т6)'}
                </a>
              : <span className="hint" style={{ margin: 0, padding: '8px 12px' }}>
                  {lang === 'tj' ? 'Дар интизори ташхиси тиббии баъд аз рейс (Т6) дар кабинети духтур' : 'Ожидает послерейсового медосмотра (Т6) в кабинете врача'}
                </span>
          )}
          {w.status === 'RETURNED' && canDispatch && (
            <button className="btn" onClick={() => act(t('wb.act.closed'), () => wb.post(`/${id}/close`, { actor: dispatcher }))}>
              {t('wb.btn.close')}
            </button>
          )}
          {/* Касса 3-С «выручка сдана» (legacy pay кассира, MIGRATION.md 4.7): только легковой/такси после возврата. */}
          {(w.waybillType === 'WB_CAR' || w.waybillType === 'WB_TAXI') && ['RETURNED', 'COMPLETED'].includes(w.status) && (
            w.kassaConfirmedAt ? (
              <span className="badge green" title={String(w.kassaEmployeeRma ?? '')}>{t('wb.kassa.done')} {new Date(w.kassaConfirmedAt).toLocaleString('ru-RU')}</span>
            ) : canKassa && (
              <span>
                <input style={{ width: 200, marginRight: 8, display: 'inline-block' }} placeholder={t('wb.kassa.ph')}
                  value={kassaRma} onChange={e => setKassaRma(e.target.value)} />
                <button className="btn" disabled={!/^\d{9,10}$/.test(kassaRma)}
                  onClick={() => act(t('wb.kassa.act'), () => wb.post(`/${id}/kassa`, { employeeRma: kassaRma }))}>
                  {t('wb.kassa.btn')}
                </button>
              </span>
            )
          )}
          {(w.status === 'MED_REJECTED' || w.status === 'TECH_REJECTED') && canDispatch && (
            <span>
              <select style={{ width: 300, marginRight: 8, display: 'inline-block' }}
                value={replacement} onChange={e => setReplacement(e.target.value)}>
                <option value="">{w.status === 'MED_REJECTED' ? t('wb.opt.newdriver') : t('wb.opt.newveh')}</option>
                {candidates.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
              </select>
              <button className="btn" disabled={!dispatcher || !replacement}
                onClick={() => act(
                  w.status === 'MED_REJECTED' ? t('wb.act.repldriver') : t('wb.act.replveh'),
                  () => wb.post(`/${id}/${w.status === 'MED_REJECTED' ? 'replace-driver' : 'replace-vehicle'}`,
                    w.status === 'MED_REJECTED'
                      ? { newDriverRma: replacement, dispatcherRma: dispatcher }
                      : { newVehicleRegNumber: replacement, dispatcherRma: dispatcher }))}>
                {w.status === 'MED_REJECTED' ? t('wb.btn.repldriver') : t('wb.btn.replveh')}
              </button>
            </span>
          )}
          {['ISSUED', 'ACTIVE', 'RETURNED'].includes(w.status) && canBlock && (
            <button className="btn danger" onClick={() => setShowBlockForm(v => !v)}>
              {t('wb.btn.block')} ({t('wb.r.inspector')})
            </button>
          )}
          {w.status === 'BLOCKED' && canUnblock && (
            <span>
              <input style={{ width: 260, marginRight: 8, display: 'inline-block' }} placeholder={t('wb.ph.unblockreason')}
                value={blockReason} onChange={e => setBlockReason(e.target.value)} />
              <button className="btn" disabled={!blockReason}
                onClick={() => act(t('wb.act.unblocked'), () => wb.post(`/${id}/unblock`, { reason: blockReason }))}>
                {t('wb.btn.unblock')} ({t('wb.r.mintrans')})
              </button>
            </span>
          )}
          {/* Аннулирование недоступно из BLOCKED (снять блок может только Минтранс через разблокировку). */}
          {canDispatch && !['COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED', 'BLOCKED'].includes(w.status) && (
            <button className="btn danger"
              onClick={() => act(t('wb.act.cancelled'), () => wb.post(`/${id}/cancel`, { reason: 'Отмена диспетчером', actor: dispatcher ?? 'dispatcher' }))}>
              {t('wb.btn.cancel')}
            </button>
          )}
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      {/* Акт дорожной проверки: блокировка — юридическое действие, основание берётся из
          классификатора, место и № акта фиксируются вместе с ним. */}
      {showBlockForm && canBlock && ['ISSUED', 'ACTIVE', 'RETURNED'].includes(w.status) && (
        <div className="card" style={{ borderColor: 'var(--red)' }}>
          <div className="card-h"><h2 style={{ margin: 0 }}>{t('insp.act.h')}</h2></div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 12 }}>
            <div style={{ gridColumn: '1 / -1' }}>
              <label>{t('insp.act.reason')} *</label>
              <select value={blockAct.reasonCode} onChange={e => setBlockAct({ ...blockAct, reasonCode: e.target.value })}>
                <option value="">—</option>
                {reasons.map(r => <option key={r.code} value={r.code}>{r.label}</option>)}
              </select>
            </div>
            <div style={{ gridColumn: '1 / -1' }}>
              <label>{t('insp.act.desc')}{blockAct.reasonCode === 'OTHER' ? ' *' : ''}</label>
              <input value={blockAct.description} placeholder={t('insp.act.desc.ph')}
                onChange={e => setBlockAct({ ...blockAct, description: e.target.value })} />
            </div>
            <div>
              <label>{t('insp.act.place')}</label>
              <input value={blockAct.place} placeholder={t('insp.act.place.ph')}
                onChange={e => setBlockAct({ ...blockAct, place: e.target.value })} />
            </div>
            <div>
              <label>{t('insp.act.protocol')}</label>
              <input value={blockAct.protocolNumber} placeholder={t('insp.act.protocol.ph')}
                onChange={e => setBlockAct({ ...blockAct, protocolNumber: e.target.value })} />
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
            <button className="btn danger"
              disabled={!blockAct.reasonCode || (blockAct.reasonCode === 'OTHER' && !blockAct.description.trim())}
              onClick={() => act(t('wb.act.blocked'), async () => {
                await wb.blockWaybill(id, blockAct);
                setShowBlockForm(false);
                setInspections(await wb.inspections(id));
              })}>
              {t('insp.act.block')}
            </button>
            <button className="btn success"
              onClick={() => act(t('insp.act.passed.done'), async () => {
                await wb.inspectPassed(id, { place: blockAct.place, protocolNumber: blockAct.protocolNumber });
                setShowBlockForm(false);
                setInspections(await wb.inspections(id));
              })}>
              {t('insp.act.passed')}
            </button>
            <button className="btn secondary" onClick={() => setShowBlockForm(false)}>{t('fleet.cancel')}</button>
          </div>
          <div className="hint" style={{ marginTop: 10 }}>{t('insp.act.hint')}</div>
        </div>
      )}

      {/* История дорожных проверок — видна всем, кто видит документ (в т.ч. перевозчику:
          он должен знать, за что и кем заблокирован его лист). */}
      {inspections.length > 0 && (
        <div className="card">
          <div className="card-h"><h2>{t('insp.act.history')}</h2></div>
          <table>
            <thead>
              <tr><th>{t('col.date')}</th><th>{t('insp.act.result')}</th><th>{t('insp.act.reason')}</th>
                <th>{t('insp.act.place')}</th><th>{t('insp.act.protocol')}</th><th>{t('wb.r.inspector')}</th></tr>
            </thead>
            <tbody>
              {inspections.map(i => (
                <tr key={i.id}>
                  <td>{new Date(i.createdAt).toLocaleString('ru-RU')}</td>
                  <td>{i.action === 'BLOCKED'
                    ? <span className="badge red">{t('insp.act.blocked')}</span>
                    : <span className="badge green">{t('insp.act.nofault')}</span>}</td>
                  <td>{reasons.find(r => r.code === i.reasonCode)?.label ?? '—'}
                    {i.description && <div style={{ fontSize: 12, color: 'var(--muted)' }}>{i.description}</div>}</td>
                  <td>{i.place ?? '—'}</td>
                  <td>{i.protocolNumber ?? '—'}</td>
                  <td>{i.inspectorName ?? i.inspectorRma}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Сводная карточка */}
      <div className="card">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 20, alignItems: 'start' }}>
          <div>
            <div style={sumLabel}>{t('col.vehiclefull')}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 6, flexWrap: 'wrap' }}>
              {plate}
              {vehicleName && <span style={{ fontWeight: 600 }}>{vehicleName}</span>}
            </div>
          </div>
          <div>
            <div style={sumLabel}>{t('col.driver')}</div>
            <div style={sumValue}>{driverName}</div>
          </div>
          <div>
            <div style={sumLabel}>{t('col.org')}</div>
            <div style={sumValue}>{orgName}</div>
          </div>
          <div>
            <div style={sumLabel}>{t('drv.validity')}</div>
            <div style={{ ...sumValue, fontWeight: 500, fontSize: 13 }}>{validFrom} → {validTo}</div>
          </div>
          <div>
            <div style={sumLabel}>{t('wb.marks')}</div>
            <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <span className={`badge ${w.medPassed ? 'green' : 'gray'}`}>Т2 {w.medPassed ? '✓' : '…'}</span>
              <span className={`badge ${w.techPassed ? 'green' : 'gray'}`}>Т3 {w.techPassed ? '✓' : '…'}</span>
            </div>
          </div>
        </div>
      </div>

      {/* Таб-бар */}
      <div style={{ display: 'flex', gap: 2, borderBottom: '1px solid var(--line)', marginBottom: 18, flexWrap: 'wrap' }}>
        {tabs.map(t => (
          <button key={t.key} type="button" onClick={() => setTab(t.key)}
            style={{
              background: 'none', border: 'none', cursor: 'pointer', outline: 'none',
              padding: '10px 15px', fontSize: 13.5, fontWeight: 600, fontFamily: 'var(--sans)',
              color: tab === t.key ? 'var(--blue-600)' : 'var(--muted)',
              borderBottom: tab === t.key ? '2px solid var(--blue-600)' : '2px solid transparent',
              marginBottom: -1,
            }}>
            {t.label}
          </button>
        ))}
      </div>

      {/* Основное */}
      {tab === 'main' && (
        <div className="card">
          <h2>{t('wb.main.h')}</h2>
          <dl className="kv">
            <dt>{t('insp.wbnum')}</dt><dd>{w.number ?? '—'}</dd>
            <dt>{t('col.type')}</dt><dd>{tType(w.waybillType)}</dd>
            <dt>{t('col.status')}</dt><dd><span className={`badge ${s.color}`}>{s.label}</span></dd>
            <dt>{t('wb.createdat')}</dt><dd>{w.createdAt ? new Date(w.createdAt).toLocaleString('ru-RU') : '—'}</dd>
            <dt>{t('drv.validity')}</dt><dd>{validFrom} → {validTo}</dd>
            <dt>{t('col.org')}</dt><dd>{orgName}</dd>
            <dt>{t('drv.routeschedule')}</dt><dd>{w.route ?? '—'} / {w.schedule ?? '—'}</dd>
            {w.specialMark && <><dt>{t('wb.specialmark')}</dt><dd>{w.specialMark}</dd></>}
          </dl>
          {customEntries.length > 0 && (
            <>
              <h2 style={{ marginTop: 18 }}>{t('fld.section')}</h2>
              <dl className="kv">
                {customEntries.map(c => (
                  <span key={c.key} style={{ display: 'contents' }}><dt>{c.label}</dt><dd>{c.value}</dd></span>
                ))}
              </dl>
            </>
          )}
        </div>
      )}

      {/* Водитель */}
      {tab === 'driver' && (
        <div className="card">
          <h2>{t('col.driver')}</h2>
          <dl className="kv">
            <dt>{t('col.fio')}</dt><dd>{driverName}</dd>
            <dt>РМА</dt><dd>{w.driverRma}</dd>
          </dl>
          {w.secondDriverRma && (
            <>
              <h2 style={{ marginTop: 18 }}>{t('wb.seconddriver')}</h2>
              <dl className="kv">
                <dt>{t('col.fio')}</dt><dd>{String((td.secondDriverSnapshot as Record<string, unknown>)?.fullName ?? w.secondDriverRma)}</dd>
                <dt>РМА</dt><dd>{w.secondDriverRma}</dd>
              </dl>
            </>
          )}
        </div>
      )}

      {/* Транспорт */}
      {tab === 'vehicle' && (
        <div className="card">
          <h2>{t('col.vehiclefull')}</h2>
          {plate && <div style={{ marginBottom: 14 }}>{plate}</div>}
          <dl className="kv">
            <dt>{t('col.regnum')}</dt><dd>{w.vehicleRegNumber || '—'}</dd>
            <dt>{t('tech.f.brandmodel')}</dt><dd>{vehicleName || '—'}</dd>
            {trailers.map((tr, i) => (
              <span key={i} style={{ display: 'contents' }}><dt>{t('wb.trailer')} {i + 1}</dt><dd>{tr.registrationNumber} · {tr.brand}</dd></span>
            ))}
            {gps && (
              <><dt>{t('wb.gps.last')}</dt><dd>
                <span style={{ fontFamily: 'var(--mono)' }}>{gps.lat.toFixed(5)}, {gps.lon.toFixed(5)}</span>
                {gps.speedKmh != null ? ` · ${gps.speedKmh} ${t('wb.gps.kmh')}` : ''}
                {' · '}{new Date(gps.recordedAt).toLocaleString('ru-RU')}
              </dd></>
            )}
          </dl>
        </div>
      )}

      {/* Маршрут (маршрут/сообщение + международные поля) */}
      {tab === 'route' && showRoute && (
        <div className="card">
          <h2>{t('wb.route.h')}</h2>
          <dl className="kv">
            <dt>{t('col.route')}</dt><dd>{w.route ?? '—'}</dd>
            <dt>{t('wb.schedule')}</dt><dd>{w.schedule ?? '—'}</dd>
            {'serviceKind' in td && <><dt>{t('wb.svc.label')}</dt><dd>{{ TAXI: t('wb.svc.taxi'), ROUTE: t('wb.svc.route'), HOURLY: t('wb.svc.hourly') }[String(td.serviceKind)] ?? String(td.serviceKind)}</dd></>}
            {'shipmentKind' in td && <><dt>{t('wb.ship.label')}</dt><dd>{String(td.shipmentKind) === 'PIECEWORK' ? t('wb.ship.piecework') : t('wb.ship.hourly')}</dd></>}
            {'workType' in td && <><dt>{t('wb.f.worktype')}</dt><dd>{String(td.workType)}</dd></>}
            {'workObject' in td && <><dt>{t('wb.f.workobject')}</dt><dd>{String(td.workObject)}</dd></>}
            {'permitNumber' in td && <><dt>{t('wb.dt.permit')}</dt><dd>{String(td.permitNumber)}</dd></>}
            {'visaValidTo' in td && <><dt>{t('wb.visa')}</dt><dd>{t('wb.until')} {String(td.visaValidTo)} · {String(td.visaCountry ?? '')}</dd></>}
            {'loadCountry' in td && <><dt>{t('wb.triproute')}</dt><dd>{String(td.loadCountry)} → {Array.isArray(td.transitCountries) && td.transitCountries.length ? `${(td.transitCountries as string[]).join(', ')} → ` : ''}{String(td.unloadCountry)}</dd></>}
            {'cargoName' in td && <><dt>{t('wb.cargo')}</dt><dd>{String(td.cargoName)}</dd></>}
            {'bbaNumber' in td && <><dt>{t('wb.bba')}</dt><dd>{String(td.bbaNumber)}</dd></>}
            {'arrivalTime' in td && <><dt>{t('wb.dt.arrival')}</dt><dd>{String(td.arrivalTime).replace('T', ' ')}</dd></>}
            {'passengersCount' in td && <><dt>{t('wb.dt.passengers')}</dt><dd>{String(td.passengersCount)}</dd></>}
          </dl>
        </div>
      )}

      {/* Топливо: одометр/пробег + рабочие дни + нормирование топлива */}
      {tab === 'fuel' && (
        <>
          <div className="card">
            <h2>{t('wb.odo.h')}</h2>
            <dl className="kv">
              <dt>{t('wb.odo.exitentry')}</dt><dd>{w.odometerExit ?? '—'} → {w.odometerEntry ?? '—'}</dd>
              <dt>{t('col.mileage')}</dt><dd>{mileage != null ? `${mileage} ${t('unit.km')}` : '—'}</dd>
              {isSpecial && (<>
                <dt>{t('wb.f.motorhours')} → {t('wb.f.motorhours.in')}</dt><dd>{mhExit ?? '—'} → {mhEntry ?? '—'}</dd>
                <dt>{t('wb.spec.worked')}</dt><dd>{motorHoursWorked != null ? `${motorHoursWorked} ${t('unit.mh')}` : '—'}</dd>
              </>)}
            </dl>
          </div>

          {(w.status === 'ACTIVE' || workDays.length > 0) && (
            <div className="card">
              <h2>{t('wb.workdays.h')}</h2>
              {workDays.length > 0 && (
                <table style={{ marginBottom: 12 }}>
                  <thead><tr><th>{t('col.date')}</th><th>{t('wb.th.exit')}</th><th>{t('wb.th.entry')}</th><th>{t('col.odometer')}</th><th>{t('wb.th.laps')}</th><th>{t('rep.revenue')}</th></tr></thead>
                  <tbody>
                    {workDays.map((d, i) => (
                      <tr key={String(d.id ?? i)}>
                        <td>{String(d.workDate)}</td>
                        <td>{String(d.exitTime ?? '—')}</td>
                        <td>{String(d.entryTime ?? '—')}</td>
                        <td>{String(d.odometerExit ?? '—')} → {String(d.odometerEntry ?? '—')}</td>
                        <td>{String(d.laps ?? '—')}</td>
                        <td>{String(d.revenue ?? '—')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {w.status === 'ACTIVE' && (
                <>
                  <form className="grid" onSubmit={async e => {
                    e.preventDefault();
                    await act(t('wb.act.dayadded'), () => wb.post(`/${id}/work-days`, {
                      workDate: dayForm.workDate,
                      exitTime: dayForm.exitTime || null,
                      entryTime: dayForm.entryTime || null,
                      odometerExit: dayForm.odometerExit ? Number(dayForm.odometerExit) : null,
                      odometerEntry: dayForm.odometerEntry ? Number(dayForm.odometerEntry) : null,
                      laps: dayForm.laps ? Number(dayForm.laps) : null,
                      revenue: dayForm.revenue ? Number(dayForm.revenue) : null,
                    }));
                  }}>
                    <div><label>{t('col.date')}</label><input type="date" required value={dayForm.workDate} onChange={e => setDayForm({ ...dayForm, workDate: e.target.value })} /></div>
                    <div><label>{t('wb.f.exitentry')}</label>
                      <span style={{ display: 'flex', gap: 6 }}>
                        <input type="time" value={dayForm.exitTime} onChange={e => setDayForm({ ...dayForm, exitTime: e.target.value })} />
                        <input type="time" value={dayForm.entryTime} onChange={e => setDayForm({ ...dayForm, entryTime: e.target.value })} />
                      </span>
                    </div>
                    <div><label>{t('wb.f.odoexitentry')}</label>
                      <span style={{ display: 'flex', gap: 6 }}>
                        <input type="number" value={dayForm.odometerExit} onChange={e => setDayForm({ ...dayForm, odometerExit: e.target.value })} />
                        <input type="number" value={dayForm.odometerEntry} onChange={e => setDayForm({ ...dayForm, odometerEntry: e.target.value })} />
                      </span>
                    </div>
                    <div><label>{t('wb.f.lapsrevenue')}</label>
                      <span style={{ display: 'flex', gap: 6 }}>
                        <input type="number" value={dayForm.laps} onChange={e => setDayForm({ ...dayForm, laps: e.target.value })} />
                        <input type="number" step="0.01" value={dayForm.revenue} onChange={e => setDayForm({ ...dayForm, revenue: e.target.value })} />
                      </span>
                    </div>
                    <div className="full"><button className="btn secondary" type="submit">{t('wb.btn.addday')}</button></div>
                  </form>
                  <form className="grid" style={{ marginTop: 10 }} onSubmit={async e => {
                    e.preventDefault();
                    await act(t('wb.act.fuelrecorded'), () => wb.post(`/${id}/fuel`, {
                      fuelType: Number(fuelForm.fuelType),
                      fuelGiven: fuelForm.fuelGiven ? Number(fuelForm.fuelGiven) : null,
                      remainBeforeExit: fuelForm.remainBeforeExit ? Number(fuelForm.remainBeforeExit) : null,
                      additionalGiven: fuelForm.additionalGiven ? Number(fuelForm.additionalGiven) : null,
                      returned: fuelForm.returned ? Number(fuelForm.returned) : null,
                      coefBelow0: fuelForm.coefBelow0 ? Number(fuelForm.coefBelow0) : null,
                      beGiven: fuelForm.beGiven ? Number(fuelForm.beGiven) : null,
                      workDayId: fuelForm.workDayId || null,
                    }));
                  }}>
                    {workDays.length > 0 && (
                      <div><label>{t('wb.f.workday')}</label>
                        <select value={fuelForm.workDayId} onChange={e => setFuelForm({ ...fuelForm, workDayId: e.target.value })}>
                          <option value="">{t('wb.f.workday.whole')}</option>
                          {workDays.map(d => <option key={String(d.id)} value={String(d.id)}>{String(d.workDate)}</option>)}
                        </select>
                      </div>
                    )}
                    <div><label>{t('rep.col.fueltype')}</label>
                      <select value={fuelForm.fuelType} onChange={e => setFuelForm({ ...fuelForm, fuelType: e.target.value })}>
                        <option value="1">{t('wb.fuel.petrol')}</option><option value="2">{t('wb.fuel.diesel')}</option>
                        <option value="3">{t('wb.fuel.lpg')}</option><option value="4">{t('wb.fuel.cng')}</option>
                      </select>
                    </div>
                    <div><label>{t('rep.col.given')}</label><input type="number" step="0.1" required value={fuelForm.fuelGiven} onChange={e => setFuelForm({ ...fuelForm, fuelGiven: e.target.value })} /></div>
                    <div><label>{t('wb.f.remainbefore')}</label><input type="number" step="0.1" value={fuelForm.remainBeforeExit} onChange={e => setFuelForm({ ...fuelForm, remainBeforeExit: e.target.value })} /></div>
                    <div><label>{t('wbd.fueladd')}</label><input type="number" step="0.1" value={fuelForm.additionalGiven} onChange={e => setFuelForm({ ...fuelForm, additionalGiven: e.target.value })} /></div>
                    <div><label>{t('wbd.fuelreturn')}</label><input type="number" step="0.1" value={fuelForm.returned} onChange={e => setFuelForm({ ...fuelForm, returned: e.target.value })} /></div>
                    <div><label>{t('wbd.fuelcoef0')}</label><input type="number" step="0.1" min="0" value={fuelForm.coefBelow0} onChange={e => setFuelForm({ ...fuelForm, coefBelow0: e.target.value })} /></div>
                    <div><label>{t('wbd.fuelbegiven')}</label><input type="number" step="0.1" min="0" value={fuelForm.beGiven} onChange={e => setFuelForm({ ...fuelForm, beGiven: e.target.value })} /></div>
                    {fuelHint && <div className="full" style={{ fontSize: 12, color: 'var(--muted)' }}>{fuelHint}</div>}
                    <div className="full"><button className="btn secondary" type="submit">{t('wb.btn.recordfuel')}</button></div>
                  </form>
                </>
              )}
            </div>
          )}

          {/* Троллейбус — электротранспорт: нормы топлива нет, кнопка расчёта только выдавала бы ошибку. */}
          {(w.status === 'RETURNED' || w.status === 'COMPLETED') && w.waybillType !== 'WB_TROLLEYBUS' && (
            <div className="card">
              <h2>{t('wb.fuelnorm.h')}</h2>
              {!fuelCalc ? (
                <button className="btn secondary" onClick={async () => {
                  try {
                    const { authHeaders } = await import('@/lib/api');
                    const r = await fetch(`/wb-api/api/v1/waybills/${id}/fuel-calculation`, { headers: authHeaders() });
                    if (!r.ok) {
                      const p = await r.json().catch(() => null);
                      throw new Error(p?.detail ?? `${t('wb.error')} ${r.status}`);
                    }
                    setFuelCalc(await r.json());
                  } catch (e) {
                    setError((e as Error).message);
                  }
                }}>
                  {t('wb.btn.calcnorm')}
                </button>
              ) : (
                <dl className="kv">
                  {fuelCalc.unit === 'MOTORHOUR' ? (
                    <>
                      <dt>{t('wb.spec.worked')}</dt><dd>{String(fuelCalc.motorHours)} {t('unit.mh')}</dd>
                      <dt>{t('wb.basenorm')}</dt><dd>{String(fuelCalc.baseNormPer100km)} {t('unit.lpermh')}</dd>
                    </>
                  ) : (
                    <>
                      <dt>{t('col.mileage')}</dt><dd>{String(fuelCalc.km)} {t('unit.km')}</dd>
                      <dt>{t('wb.basenorm')}</dt><dd>{String(fuelCalc.baseNormPer100km)} л/100км</dd>
                    </>
                  )}
                  <dt>{t('dict.sec.coefficients')}</dt>
                  <dd>{Array.isArray(fuelCalc.coefficientsApplied) && fuelCalc.coefficientsApplied.length
                    ? (fuelCalc.coefficientsApplied as Record<string, unknown>[]).map(c => `${c.name} ×${c.value}`).join(', ')
                    : t('wb.notapplied')}</dd>
                  <dt>{t('wb.norm')}</dt><dd><b>{String(fuelCalc.normLiters)} л</b></dd>
                  <dt>{t('wb.fact')}</dt><dd>{String(fuelCalc.factLiters)} л</dd>
                  <dt>{t('wb.deviation')}</dt>
                  <dd style={{ color: Number(fuelCalc.deviationLiters) > 0 ? 'var(--accent)' : 'var(--brand)' }}>
                    {Number(fuelCalc.deviationLiters) > 0 ? '+' : ''}{String(fuelCalc.deviationLiters)} л
                  </dd>
                  {fuelCalc.tripCost != null && <><dt>{t('wb.tripcost')}</dt><dd>{String(fuelCalc.tripCost)} сомони ({String(fuelCalc.tariffPerKm)} сомони/км)</dd></>}
                </dl>
              )}
            </div>
          )}

          {/* Посуточный расчёт топлива (B10): 1-А / 3-С с рабочими днями — строки топлива, привязанные к дням */}
          {workDays.length > 0 && ['WB_MINIBUS', 'WB_CAR', 'WB_TAXI'].includes(w.waybillType) && (
            <div className="card">
              <h2>{t('wb.daily.h')}</h2>
              {!dailyCalc ? (
                <button className="btn secondary" onClick={async () => {
                  try {
                    const { authHeaders } = await import('@/lib/api');
                    const r = await fetch(`/wb-api/api/v1/waybills/${id}/calculation`, { method: 'POST', headers: { ...authHeaders(), 'Content-Type': 'application/json' }, body: '{}' });
                    if (!r.ok) { const p = await r.json().catch(() => null); throw new Error(p?.detail ?? `${t('wb.error')} ${r.status}`); }
                    setDailyCalc(await r.json());
                  } catch (e) { setError((e as Error).message); }
                }}>{t('wb.daily.btn')}</button>
              ) : (() => {
                const days = (Array.isArray(dailyCalc.dailyFuel) ? dailyCalc.dailyFuel : []) as Record<string, unknown>[];
                if (days.length === 0) return <div style={{ color: 'var(--muted)', fontSize: 13 }}>{t('wb.daily.empty')}</div>;
                const num = (v: unknown, d = 2) => (typeof v === 'number' ? (Math.round(v * 10 ** d) / 10 ** d).toLocaleString('ru-RU') : String(v ?? ''));
                const FN: Record<string, string> = { 1: t('wb.fuel.petrol'), 2: t('wb.fuel.diesel'), 3: t('wb.fuel.lpg'), 4: t('wb.fuel.cng') };
                const total = days.reduce((s, d) => s + ((d.lines as Record<string, unknown>[]) ?? []).reduce((a, l) => a + Number(l.consumption ?? 0), 0), 0);
                return (
                  <table>
                    <thead><tr>
                      <th>{t('wb.daily.col.date')}</th><th>{t('wb.daily.col.km')}</th><th>{t('wb.daily.col.coef')}</th><th>{t('wb.daily.col.fuel')}</th>
                      <th>{t('wb.daily.col.given')}</th><th>{t('wb.daily.col.add')}</th><th>{t('wb.daily.col.before')}</th>
                      <th>{t('wb.daily.col.norm100')}</th><th>{t('wb.daily.col.consumption')}</th><th>{t('wb.daily.col.remain')}</th>
                    </tr></thead>
                    <tbody>
                      {days.map((d, i) => {
                        const lines = ((d.lines as Record<string, unknown>[]) ?? []);
                        if (lines.length === 0) return <tr key={i}><td>{String(d.date)}</td><td>{num(d.distanceKm, 0)}</td><td>{num(d.multiplier, 3)}</td><td colSpan={7} style={{ color: 'var(--muted)' }}>—</td></tr>;
                        return lines.map((l, j) => (
                          <tr key={`${i}-${j}`}>
                            <td>{j === 0 ? String(d.date) : ''}</td><td>{j === 0 ? num(d.distanceKm, 0) : ''}</td><td>{j === 0 ? num(d.multiplier, 3) : ''}</td>
                            <td>{FN[String(l.fuelId)] ?? String(l.fuelId)}</td><td>{num(l.given)}</td><td>{num(l.additional)}</td><td>{num(l.remainBeforeExit)}</td>
                            <td>{num(l.norm100)}</td><td><b>{num(l.consumption)}</b></td><td>{num(l.remainEntry)}</td>
                          </tr>
                        ));
                      })}
                      <tr style={{ fontWeight: 700, borderTop: '2px solid var(--line)' }}><td colSpan={8}>{t('wb.daily.total')}</td><td>{num(total)}</td><td></td></tr>
                    </tbody>
                  </table>
                );
              })()}
            </div>
          )}
        </>
      )}

      {/* Накладная: приложение к 2-Б или CMR к 5Б-БМ — стороны, груз, операции погрузки-разгрузки */}
      {tab === 'consignment' && hasConsignment && (
        <Consignment waybillId={w.id} waybillType={w.waybillType} typeData={td} onSaved={reload} />
      )}

      {/* Расходы рейса (§12): суточные/дороги/парковка/ремонт с подтверждением бухгалтером */}
      {tab === 'expenses' && (
        <div className="card">
          <h2>{t('wb.tab.expenses')}</h2>
          <ExpensesSection waybillId={w.id} terminal={['COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED'].includes(w.status)} />
        </div>
      )}

      {/* Вложения рейса: скан-копии сопроводительных документов (CMR / накладные / фото груза) */}
      {tab === 'attachments' && (
        <div className="card">
          <Attachments waybillId={w.id} terminal={['CANCELLED', 'EXPIRED', 'ARCHIVED'].includes(w.status)} />
        </div>
      )}

      {/* Осмотры: Т2 медосмотр и Т3 техконтроль */}
      {tab === 'inspections' && (
        <div className="card">
          <h2>{t('wb.insp.h')}</h2>
          <dl className="kv">
            <dt>{t('wb.insp.t2')}</dt>
            <dd><span className={`badge ${w.medPassed ? 'green' : 'gray'}`}>{w.medPassed ? t('wb.passed') : t('wb.awaiting')}</span></dd>
            <dt>{t('wb.insp.t3')}</dt>
            <dd><span className={`badge ${w.techPassed ? 'green' : 'gray'}`}>{w.techPassed ? t('wb.passed') : t('wb.awaiting')}</span></dd>
          </dl>
          {titlesWithData.length > 0 && (
            <>
              <h2 style={{ marginTop: 18 }}>{t('wb.indicators')}</h2>
              {titlesWithData.map(t => (
                <div key={t.id} style={{ marginTop: 12 }}>
                  <div style={{ fontWeight: 600, marginBottom: 6 }}>{t.titleType} · {t.signerRole} ({t.signerRma})</div>
                  <dl className="kv">
                    {Object.entries(t.data ?? {}).map(([k, v]) => (
                      <span key={k} style={{ display: 'contents' }}>
                        <dt>{k}</dt><dd>{v !== null && typeof v === 'object' ? JSON.stringify(v) : String(v)}</dd>
                      </span>
                    ))}
                  </dl>
                </div>
              ))}
            </>
          )}
        </div>
      )}

      {/* QR */}
      {tab === 'qr' && (
        <div className="card" style={{ textAlign: 'center' }}>
          <h2>{t('wb.qr.h')}</h2>
          {qrUrl ? (
            <>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={qrUrl} alt={t('drv.qr.alt')} />
              <p style={{ color: 'var(--muted)', fontSize: 13 }}>
                {t('wb.qr.offline')}
              </p>
            </>
          ) : (
            <p style={{ color: 'var(--muted)', fontSize: 13 }}>
              {t('wb.qr.pending')}
            </p>
          )}
        </div>
      )}

      {/* История: единый журнал событий жизненного цикла (титулы Т1–Т6 + переходы статусов) */}
      {tab === 'history' && (
        <div className="card">
          <h2>{t('wb.journal.h')}</h2>
          <ul className="timeline">
            {buildJournal(titles, history, t, tStatus).map((e, i) => (
              <li key={i} className={e.tone}>
                <b>{e.label}</b>
                <div className="when">{new Date(e.at).toLocaleString('ru-RU')} · {e.who}</div>
              </li>
            ))}
            {titles.length === 0 && history.length === 0 && <li>{t('wb.journal.empty')}</li>}
          </ul>
        </div>
      )}
    </>
  );
}
