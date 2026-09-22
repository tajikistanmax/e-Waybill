'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { wb, md, Waybill, PagedWaybills, STATUS_LABELS, TYPE_LABELS } from '@/lib/api';
import { Icon, P } from '../icons';
import { useT } from '@/lib/i18n';

const PER_PAGE = 20;
const EXPORT_CAP = 1000;

/** Один шаг степпера согласования (Диспетчер → Мед → Тех) — виден в реестре без открытия карточки. */
function Stage({ state, label }: { state: 'ok' | 'bad' | 'wait'; label: string }) {
  return (
    <span className={`stage ${state}`} title={label}>
      {state === 'ok' && <Icon d={P.check} cls="" style={{ width: 12, height: 12 }} />}
      {state === 'bad' && <Icon d={P.alert} cls="" style={{ width: 12, height: 12 }} />}
    </span>
  );
}

/** Степпер из трёх этапов для строки реестра. */
function Stages({ w, t }: { w: Waybill; t: (k: string) => string }) {
  const dispatch: 'ok' | 'wait' = w.status === 'DRAFT' ? 'wait' : 'ok';
  const med: 'ok' | 'bad' | 'wait' = w.medPassed ? 'ok' : w.status === 'MED_REJECTED' ? 'bad' : 'wait';
  const tech: 'ok' | 'bad' | 'wait' = w.techPassed ? 'ok' : w.status === 'TECH_REJECTED' ? 'bad' : 'wait';
  return (
    <span className="stages">
      <Stage state={dispatch} label={t(dispatch === 'ok' ? 'wb.stage.dispatch.done' : 'wb.stage.dispatch.wait')} />
      <span className="stage-link" />
      <Stage state={med} label={t(med === 'ok' ? 'wb.stage.med.done' : med === 'bad' ? 'wb.stage.med.rejected' : 'wb.stage.med.wait')} />
      <span className="stage-link" />
      <Stage state={tech} label={t(tech === 'ok' ? 'wb.stage.tech.done' : tech === 'bad' ? 'wb.stage.tech.rejected' : 'wb.stage.tech.wait')} />
    </span>
  );
}

/** Параметры отбора реестра — все уходят на сервер (MIGRATION.md 8.4: серверная пагинация, фильтры в SQL). */
type Filters = {
  org: string; status: string; type: string; vehicle: string; driver: string; svc: string;
  docKind: '' | 'attachment' | 'cmr'; client: string; dateFrom: string; dateTo: string; q: string; archived: boolean;
};
const EMPTY: Filters = { org: '', status: '', type: '', vehicle: '', driver: '', svc: '', docKind: '', client: '', dateFrom: '', dateTo: '', q: '', archived: false };

export default function WaybillsPage() {
  const [orgs, setOrgs] = useState<Record<string, unknown>[]>([]);
  const [error, setError] = useState('');
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(1);
  const [data, setData] = useState<PagedWaybills | null>(null);
  const [loading, setLoading] = useState(false);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const { t, tType, tStatus } = useT();

  // Список организаций — для фильтра по компании (платформенные роли видят все ПЛ).
  useEffect(() => { md.organizations().then(setOrgs).catch(() => setOrgs([])); }, []);

  const query = useCallback((f: Filters, p: number, size: number) => wb.page({
    organizationRma: f.org, status: f.status, type: f.type, vehicle: f.vehicle.trim(), driver: f.driver.trim(),
    svc: f.svc, docKind: f.docKind, client: f.client.trim(), from: f.dateFrom, to: f.dateTo, q: f.q.trim(),
    archived: f.archived, page: p - 1, size,
  }), []);

  // Страница реестра — с сервера; текстовые поля дебаунсим, чтобы не слать запрос на каждую букву.
  useEffect(() => {
    let alive = true;
    setLoading(true);
    const h = setTimeout(() => {
      query(filters, page, PER_PAGE)
        .then(d => { if (alive) { setData(d); setError(''); } })
        .catch(e => { if (alive) setError((e as Error).message); })
        .finally(() => { if (alive) setLoading(false); });
    }, 250);
    return () => { alive = false; clearTimeout(h); };
  }, [filters, page, query]);

  // Карточки-счётчики — по всей области видимости (без архива), в разрезе выбранной компании.
  useEffect(() => {
    wb.statusCounts(filters.org || undefined).then(setCounts).catch(() => setCounts({}));
  }, [filters.org]);

  const set = <K extends keyof Filters>(k: K, v: Filters[K]) => { setFilters(f => ({ ...f, [k]: v })); setPage(1); };
  const reset = () => { setFilters(EMPTY); setPage(1); };
  const svcApplicable = !filters.type || filters.type === 'WB_CAR' || filters.type === 'WB_TAXI';

  const sum = (keys: string[]) => keys.reduce((a, k) => a + (counts[k] ?? 0), 0);
  const stat = {
    total: sum(Object.keys(counts)),
    active: sum(['ISSUED', 'ACTIVE', 'RETURNED']),
    pending: sum(['CREATED', 'MED_REJECTED', 'TECH_REJECTED']),
    done: sum(['COMPLETED', 'ARCHIVED']),
  };
  const statCards = [
    { label: t('wb.stat.total'), value: stat.total, icon: P.doc, cls: 'ic-blue' },
    { label: t('wb.stat.active'), value: stat.active, icon: P.car, cls: 'ic-green' },
    { label: t('wb.stat.pending'), value: stat.pending, icon: P.alert, cls: 'ic-amber' },
    { label: t('wb.stat.done'), value: stat.done, icon: P.check, cls: 'ic-cyan' },
  ];

  const view = data?.content ?? [];
  const total = data?.totalElements ?? 0;
  const pages = Math.max(1, data?.totalPages ?? 1);
  const fmt = (d: string | null) => d ? new Date(d).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';
  const snapStr = (o: Record<string, unknown> | undefined, k: string) => {
    const v = o?.[k];
    return v != null && String(v).trim() !== '' ? String(v) : '—';
  };

  // Колонки реестра — как в боевой «Роҳхат»: у каждого типа ПЛ своя раскладка
  // (в оригинале это отдельные CRUD-страницы Waybill1ad/1a/3c/2b). При выборе типа
  // в фильтре показываем именно его набор граф; «Все типы» — общий вид с колонкой «Тип».
  // Данные берём из полей ПЛ и снимков (route, driverSnapshot.tabNumber/phone и т.д.).
  const cols = useMemo(() => {
    type Col = { key: string; label: string; cell: (w: Waybill) => ReactNode };
    const C: Record<string, Col> = {
      num: { key: 'num', label: t('col.wbnum'), cell: w => (
        <>
          <span className="number">{w.number ?? t('viol.draft')}</span>
          {w.branchSerial != null && (
            <div style={{ fontSize: 11, color: 'var(--muted)' }}>
              №&nbsp;{w.branchSerial}/{w.branchSerialYear} по журналу
            </div>
          )}
        </>
      ) },
      type: { key: 'type', label: t('col.type'), cell: w => tType(w.waybillType).replace(/\s*\(.*\)/, '') },
      company: { key: 'company', label: t('col.company'), cell: w => String(w.organizationSnapshot?.name ?? w.organizationRma) },
      transport: { key: 'transport', label: t('col.transport'), cell: w => `${String(w.vehicleSnapshot?.brand ?? '')} ${w.vehicleRegNumber}`.trim() },
      route: { key: 'route', label: t('col.route'), cell: w => w.route && w.route.trim() !== '' ? w.route : '—' },
      driver: { key: 'driver', label: t('col.driver'), cell: w => String(w.driverSnapshot?.fullName ?? w.driverRma) },
      tab: { key: 'tab', label: t('col.tab'), cell: w => snapStr(w.driverSnapshot, 'tabNumber') },
      phone: { key: 'phone', label: t('col.phone'), cell: w => snapStr(w.driverSnapshot, 'phone') },
      // Касса 3-С «Пардохти маблағ» (legacy employee_kassa_id, MIGRATION.md 4.7): кто/когда сдал выручку.
      kassa: { key: 'kassa', label: t('col.kassa'), cell: w => w.kassaConfirmedAt
        ? <span className="badge green" title={String(w.kassaEmployeeRma ?? '')}>✓ {fmt(w.kassaConfirmedAt)}</span>
        : <span style={{ color: 'var(--muted)' }}>—</span> },
      start: { key: 'start', label: t('wb.col.start'), cell: w => fmt(w.validFrom) },
      stages: { key: 'stages', label: t('wb.col.stages'), cell: w => <Stages w={w} t={t} /> },
      status: { key: 'status', label: t('col.status'), cell: w => {
        const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
        return <span className={`badge ${s.color}`}>{tStatus(w.status)}</span>;
      } },
      actions: { key: 'actions', label: '', cell: w => (
        <Link href={`/waybills/${w.id}`} className="btn secondary"
          style={{ padding: '5px 12px', fontSize: 12.5, textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap' }}>
          <Icon d={P.eye} cls="" style={{ width: 15, height: 15 }} /> {t('btn.open')}
        </Link>
      ) },
    };
    // Раскладки по типу ПЛ (соответствие боевым CRUD-контроллерам). Порядок граф — как в оригинале,
    // с сохранением наших улучшений: ФИО водителя, этапы согласования, статус.
    const pax = ['num', 'company', 'transport', 'route', 'tab', 'driver', 'start', 'stages', 'status', 'actions'];   // 1-АД/1-А (+ 4-МБМ)
    const car = ['num', 'company', 'transport', 'tab', 'phone', 'driver', 'start', 'stages', 'kassa', 'status', 'actions'];    // 3-С легковой/такси (+ касса, 4.7)
    const cargo = ['num', 'company', 'transport', 'route', 'tab', 'driver', 'start', 'stages', 'status', 'actions'];  // 2-Б/5Б-БМ
    const all = ['num', 'type', 'company', 'transport', 'driver', 'route', 'start', 'stages', 'status', 'actions'];   // Все типы
    const LAYOUTS: Record<string, string[]> = {
      '': all,
      WB_BUS: pax, WB_TROLLEYBUS: pax, WB_MINIBUS: pax, WB_PAX_INTL: pax,
      WB_CAR: car, WB_TAXI: car,
      WB_TRUCK: cargo, WB_DANGEROUS: cargo, WB_TRUCK_INTL: cargo,
      WB_SPECIAL: all,
    };
    return (LAYOUTS[filters.type] ?? all).map(k => C[k]);
  }, [filters.type, t, tType, tStatus]);

  // Экспорт — по текущему отбору с сервера, до EXPORT_CAP строк (не только видимая страница).
  async function exportCsv() {
    let rowsSrc: Waybill[] = [];
    try { rowsSrc = (await query(filters, 1, EXPORT_CAP)).content; } catch (e) { setError((e as Error).message); return; }
    const head = ['Номер', 'Тип', 'Компания', 'РМА компании', 'Транспорт', 'Водитель', 'Начало', 'Медосмотр', 'Техконтроль', 'Статус'];
    const rows = rowsSrc.map(w => [
      w.number ?? '', tType(w.waybillType), String(w.organizationSnapshot?.name ?? ''), w.organizationRma,
      `${String(w.vehicleSnapshot?.brand ?? '')} ${w.vehicleRegNumber}`.trim(),
      String(w.driverSnapshot?.fullName ?? w.driverRma), fmt(w.validFrom),
      w.medPassed ? 'да' : 'нет', w.techPassed ? 'да' : 'нет', tStatus(w.status),
    ]);
    const esc = (v: string) => `"${String(v).replace(/"/g, '""')}"`;
    const csv = '﻿' + [head, ...rows].map(r => r.map(esc).join(';')).join('\r\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `waybills_${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <>
      <div className="toolbar">
        <h1>{t('nav.waybill.registry')}</h1>
        <span className="spacer" />
        {/* Печать реестра — это «Журнал за период» (готовая печатная форма). Отдельной кнопки
            печати здесь нет: window.print() печатал бы экран целиком (меню, фильтры), а не документ.
            Печать самого путевого листа — в его карточке (серверный PDF-бланк). */}
        <Link className="btn secondary" href="/waybills/journal" style={{ textDecoration: 'none' }}><Icon d={P.book} cls="" style={{ width: 16, height: 16 }} /> {t('jrn.btn')}</Link>
        <button className="btn secondary" onClick={exportCsv} title={t('wb.export.cap')}><Icon d={P.chart} cls="" style={{ width: 16, height: 16 }} /> Экспорт CSV</button>
      </div>

      {error && <div className="error">{error}</div>}

      {/* Карточки-счётчики */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        {statCards.map(s => (
          <div className="kpi" key={s.label} style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
            <span className={`k-ic ${s.cls}`}><Icon d={s.icon} cls="" /></span>
            <div style={{ minWidth: 0 }}>
              <div className="k-value" style={{ fontSize: 22 }}>{s.value.toLocaleString('ru-RU')}</div>
              <div className="k-label">{s.label}</div>
            </div>
          </div>
        ))}
      </div>

      {/* Фильтры */}
      <div className="card">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14, alignItems: 'end' }}>
          <div>
            <label>{t('col.company')}</label>
            <select value={filters.org} onChange={e => set('org', e.target.value)}>
              <option value="">{t('flt.allcompanies')}</option>
              {orgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{String(o.name ?? o.rma)}</option>)}
            </select>
          </div>
          <div>
            <label>{t('col.status')}</label>
            <select value={filters.status} onChange={e => set('status', e.target.value)}>
              <option value="">{t('wb.f.allstatuses')}</option>
              {Object.entries(STATUS_LABELS).map(([v]) => <option key={v} value={v}>{tStatus(v)}</option>)}
            </select>
          </div>
          <div>
            <label>{t('col.wbtype')}</label>
            <select value={filters.type} onChange={e => set('type', e.target.value)}>
              <option value="">{t('wb.f.alltypes')}</option>
              {Object.entries(TYPE_LABELS).map(([v]) => <option key={v} value={v}>{tType(v)}</option>)}
            </select>
          </div>
          <div>
            <label>{t('col.transport')}</label>
            <input value={filters.vehicle} onChange={e => set('vehicle', e.target.value.toUpperCase())} placeholder={t('wb.f.vehicle.ph')} />
          </div>
          <div>
            <label>{t('col.driver')}</label>
            <input value={filters.driver} onChange={e => set('driver', e.target.value)} placeholder={t('wb.f.driver.ph')} />
          </div>
          <div>
            <label>{t('wb.svc.label')} (3-С)</label>
            <select value={filters.svc} onChange={e => set('svc', e.target.value)} disabled={!svcApplicable} title={svcApplicable ? '' : t('wb.f.svconly3c')}>
              <option value="">{t('wb.f.allsvc')}</option>
              <option value="TAXI">{t('wb.svc.taxi')}</option>
              <option value="ROUTE">{t('wb.svc.route')}</option>
              <option value="HOURLY">{t('wb.svc.hourly')}</option>
            </select>
          </div>
          <div>
            <label>{t('wb.f.doc')}</label>
            <select value={filters.docKind} onChange={e => set('docKind', e.target.value as '' | 'attachment' | 'cmr')}>
              <option value="">{t('wb.f.doc.any')}</option>
              <option value="attachment">{t('wb.f.doc.attachment')}</option>
              <option value="cmr">{t('wb.f.doc.cmr')}</option>
            </select>
          </div>
          <div>
            <label>{t('wb.f.client')}</label>
            <input value={filters.client} onChange={e => set('client', e.target.value)} placeholder={t('wb.f.client.ph')} />
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingBottom: 8 }}>
            <input type="checkbox" id="wb-archived" checked={filters.archived} onChange={e => set('archived', e.target.checked)} />
            <label htmlFor="wb-archived" style={{ margin: 0 }}>{t('wb.f.archived')}</label>
          </div>
          <div>
            <label>{t('flt.datefrom')}</label>
            <input type="date" value={filters.dateFrom} onChange={e => set('dateFrom', e.target.value)} />
          </div>
          <div>
            <label>{t('flt.dateto')}</label>
            <input type="date" value={filters.dateTo} onChange={e => set('dateTo', e.target.value)} />
          </div>
          <div style={{ display: 'flex', gap: 10, alignItems: 'end' }}>
            <div style={{ flex: 1 }}>
              <label>{t('wb.f.search')}</label>
              <input value={filters.q} onChange={e => set('q', e.target.value)} placeholder={t('wb.search.ph')} />
            </div>
            <button className="btn secondary" onClick={reset}>{t('wb.resetfilters')}</button>
          </div>
        </div>
      </div>

      {/* Таблица */}
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <table>
          <thead>
            <tr>{cols.map(c => <th key={c.key}>{c.label}</th>)}</tr>
          </thead>
          <tbody>
            {view.map(w => (
              <tr key={w.id}>{cols.map(c => <td key={c.key}>{c.cell(w)}</td>)}</tr>
            ))}
            {view.length === 0 && (
              <tr><td colSpan={cols.length} style={{ textAlign: 'center', color: 'var(--muted)', padding: 34 }}>
                {loading ? t('wb.loading') : t('wb.empty')}
              </td></tr>
            )}
          </tbody>
        </table>

        {/* Пагинация — серверная: страница PER_PAGE строк, общее число — с сервера */}
        <div style={{ display: 'flex', alignItems: 'center', padding: '14px 16px', borderTop: '1px solid var(--line)', fontSize: 13, color: 'var(--muted)' }}>
          <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{total.toLocaleString('ru-RU')}</b>{loading ? ` · ${t('wb.loading')}` : ''}</span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
          <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>
      </div>
    </>
  );
}
