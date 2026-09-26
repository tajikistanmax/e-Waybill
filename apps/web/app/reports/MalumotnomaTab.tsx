'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  wb, authHeaders, type Malumotnoma, type MalumotnomaPage, type MalumotnomaRoute, type MalumotnomaReport,
} from '@/lib/api';
import { downloadCsv } from '@/lib/csv';
import { Pager, usePaged } from '../Pager';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

/** Виды транспорта справки — как в формуле цены (§7): 1 автобус, 3 микроавтобус, 4 легковой. */
const TYPES = [
  { id: 1, k: 'rm.bus' },
  { id: 3, k: 'rm.minibus' },
  { id: 4, k: 'rm.car' },
];
/** Кто выдаёт и правит справки; аннулирует — только администратор (удаления нет, как в «Роҳхат»). */
const ISSUERS = ['DISPATCHER', 'ACCOUNTANT', 'COMPANY_ADMIN', 'BRANCH_ADMIN', 'SYSTEM_ADMIN'];
const ANNULLERS = ['COMPANY_ADMIN', 'BRANCH_ADMIN', 'SYSTEM_ADMIN'];

type Line = { routeId: string; roundTrip: boolean };
type RouteDraft = { id: string; name: string; distanceKm: string; busPrice: string; mbusPrice: string; carPrice: string; active: boolean };

function today(offset = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return d.toISOString().slice(0, 10);
}

const label: React.CSSProperties = { display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 };

/** Справки пассажирам (маълумотнома): выдача и правка, реестр, отчёт по кассирам, тарифы маршрутов. */
export default function MalumotnomaTab() {
  const { roles } = useAuth();
  const { t } = useT();
  const isAdmin = roles.includes('SYSTEM_ADMIN');
  const canIssue = roles.some(r => ISSUERS.includes(r));
  const canAnnul = roles.some(r => ANNULLERS.includes(r));
  const [routes, setRoutes] = useState<MalumotnomaRoute[]>([]);
  const [routeForm, setRouteForm] = useState({ name: '', carPrice: '', mbusPrice: '', busPrice: '', distanceKm: '' });
  const [routeQ, setRouteQ] = useState('');
  const [routeDraft, setRouteDraft] = useState<RouteDraft | null>(null);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  // Форма выдачи / правки.
  const [editing, setEditing] = useState<Malumotnoma | null>(null);
  const [fio, setFio] = useState('');
  // По умолчанию — легковой: в старой платформе 99,99 % справок выдавались на легковой (анализ 24.09.2026).
  const [type, setType] = useState(4);
  const [privileged, setPrivileged] = useState(false);
  const [lines, setLines] = useState<Line[]>([{ routeId: '', roundTrip: false }]);
  const [lineQ, setLineQ] = useState('');

  // Реестр (сервер: поиск по номеру / Ф.И.О., период, постранично — с архивом «Роҳхат» справок десятки тысяч).
  const [q, setQ] = useState('');
  const [lf, setLf] = useState('');
  const [lt, setLt] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<MalumotnomaPage | null>(null);

  // Отчёт.
  const [from, setFrom] = useState(today(-30));
  const [to, setTo] = useState(today());
  const [report, setReport] = useState<MalumotnomaReport | null>(null);
  // Постраничный вывод длинных списков (владелец, 22.09): кассиры, тарифы маршрутов.
  const groupsPage = usePaged(report?.groups ?? [], 5);
  const shownRoutes = useMemo(() => {
    const s = routeQ.trim().toLowerCase();
    return s ? routes.filter(r => r.name.toLowerCase().includes(s)) : routes;
  }, [routes, routeQ]);
  const routesPage = usePaged(shownRoutes, 20);

  const priceOf = useCallback((r: MalumotnomaRoute, tp: number) =>
    tp === 1 ? r.busPrice : tp === 3 ? r.mbusPrice : tp === 4 ? r.carPrice : 0, []);

  // Варианты маршрутов формы: справочник + маршруты правимой справки (могли быть отключены).
  const options = useMemo(() => {
    const extra = (editing?.lines ?? []).map(l => l.route).filter(r => !routes.some(x => x.id === r.id));
    return [...routes, ...extra];
  }, [routes, editing]);
  const optionsFor = (selected: string) => {
    const s = lineQ.trim().toLowerCase();
    return s ? options.filter(r => r.id === selected || r.name.toLowerCase().includes(s)) : options;
  };

  const preview = lines.reduce((sum, l) => {
    const r = options.find(x => x.id === l.routeId);
    if (!r) return sum;
    return sum + priceOf(r, type) * (l.roundTrip ? 2 : 1);
  }, 0) * (privileged ? 0.5 : 1);

  const loadList = useCallback(() => {
    wb.malumotnoma.list({ q: q.trim(), from: lf, to: lt, page, size: 20 }).then(setData).catch(e => setError(e.message));
  }, [q, lf, lt, page]);

  const loadRoutes = useCallback(() => {
    (isAdmin ? wb.malumotnomaRoutes.all() : wb.malumotnoma.routes()).then(setRoutes).catch(e => setError(e.message));
  }, [isAdmin]);

  useEffect(() => { loadRoutes(); }, [loadRoutes]);
  useEffect(() => { const h = setTimeout(loadList, 250); return () => clearTimeout(h); }, [loadList]);

  const loadReport = useCallback(() => {
    wb.malumotnoma.report(from, to).then(setReport).catch(e => setError(e.message));
  }, [from, to]);

  useEffect(() => { loadReport(); }, [loadReport]);

  function resetForm() {
    setEditing(null); setFio(''); setType(4); setPrivileged(false);
    setLines([{ routeId: '', roundTrip: false }]); setLineQ('');
  }

  function startEdit(m: Malumotnoma) {
    setError(''); setOk('');
    setEditing(m); setFio(m.fio); setType(m.transportTypeId); setPrivileged(m.age === 1);
    setLines(m.lines.map(l => ({ routeId: l.route.id, roundTrip: l.roundTrip })));
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  async function submit() {
    setError(''); setOk('');
    const body = {
      transportTypeId: type, age: privileged ? 1 : 0,
      lines: lines.filter(l => l.routeId).map(l => ({ routeId: l.routeId, roundTrip: l.roundTrip })),
    };
    try {
      const m = editing
        ? await wb.malumotnoma.update(editing.id, body)
        : await wb.malumotnoma.create({ ...body, fio });
      setOk(`${t(editing ? 'rm.saved.ok' : 'rm.issued.ok')}: № ${m.number} · ${m.fio} — ${m.price.toLocaleString('ru-RU')} ${t('rm.priceunit')}`);
      resetForm();
      loadList(); loadReport();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function annul(m: Malumotnoma) {
    const reason = prompt(`${t('rm.annul.reason')} № ${m.number}`);
    if (!reason || !reason.trim()) return;
    setError(''); setOk('');
    try { await wb.malumotnoma.annul(m.id, reason.trim()); loadList(); loadReport(); }
    catch (e) { setError((e as Error).message); }
  }

  async function saveRoute() {
    setError('');
    try {
      await wb.malumotnomaRoutes.create({
        name: routeForm.name,
        distanceKm: Number(routeForm.distanceKm || 0),
        carPrice: Number(routeForm.carPrice || 0),
        mbusPrice: Number(routeForm.mbusPrice || 0),
        busPrice: Number(routeForm.busPrice || 0),
        active: true,
      });
      setRouteForm({ name: '', carPrice: '', mbusPrice: '', busPrice: '', distanceKm: '' });
      loadRoutes();
    } catch (e) { setError((e as Error).message); }
  }
  async function updateRoute() {
    if (!routeDraft) return;
    setError('');
    try {
      await wb.malumotnomaRoutes.update(routeDraft.id, {
        name: routeDraft.name,
        distanceKm: Number(routeDraft.distanceKm || 0),
        carPrice: Number(routeDraft.carPrice || 0),
        mbusPrice: Number(routeDraft.mbusPrice || 0),
        busPrice: Number(routeDraft.busPrice || 0),
        active: routeDraft.active,
      });
      setRouteDraft(null);
      loadRoutes();
    } catch (e) { setError((e as Error).message); }
  }
  async function removeRoute(id: string) {
    if (!confirm('Удалить маршрут?')) return;
    try { await wb.malumotnomaRoutes.remove(id); loadRoutes(); } catch (e) { setError((e as Error).message); }
  }

  async function openPdf(id: string) {
    try {
      const r = await fetch(`/wb-api/api/v1/malumotnomas/${id}/print.pdf`, { headers: authHeaders() });
      if (!r.ok) throw new Error(`Ошибка ${r.status}`);
      const url = URL.createObjectURL(await r.blob());
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) { setError((e as Error).message); }
  }

  async function downloadReportXlsx() {
    try {
      const r = await fetch(`/wb-api/api/v1/malumotnomas/report.xlsx?from=${from}&to=${to}`, { headers: authHeaders() });
      if (!r.ok) throw new Error(`Ошибка ${r.status}`);
      const url = URL.createObjectURL(await r.blob());
      const a = document.createElement('a');
      a.href = url; a.download = `справки-${from}_${to}.xlsx`; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch (e) { setError((e as Error).message); }
  }

  function exportReportCsv() {
    if (!report) return;
    const rows: unknown[][] = [['Кассир', 'Справка', 'ФИО', 'Транспорт', 'Льготная', 'Дата', 'Маршруты', 'Стоимость', 'Изменил']];
    report.groups.forEach(g => {
      rows.push([`Кассир: ${g.issuerName ?? g.issuerRma}`, '', '', '', '', '', `справок: ${g.count}`, g.amount, '']);
      g.items.forEach(it => rows.push(['', it.number, it.fio, it.transportType, it.privileged ? 'да' : '', it.issuedAt, it.routes, it.price, it.updaterName ?? '']));
    });
    rows.push(['ИТОГО', '', '', '', '', '', `справок: ${report.count}`, report.total, '']);
    downloadCsv(`справки-${from}_${to}.csv`, rows);
  }

  const list = data?.content ?? [];
  const pages = Math.max(1, data?.totalPages ?? 1);

  return (
    <>
      {error && <div className="error">{error}</div>}
      {ok && <div className="ok" data-testid="rm-ok" style={{ background: 'var(--green-050)', color: 'var(--green)', padding: '8px 12px', borderRadius: 8, marginBottom: 12 }}>{ok}</div>}

      <div className="grid-2">
        {/* Выдача / правка */}
        {canIssue && (
          <div className="card" data-testid="rm-form">
            <h2>{editing ? `${t('rm.edit.h')} ${editing.number}` : t('rm.issue.h')}</h2>
            <label style={label}>{t('rm.f.fio')}</label>
            <input style={{ width: '100%' }} value={fio} onChange={e => setFio(e.target.value)} placeholder="Каримов Карим Каримович"
              disabled={!!editing} title={editing ? t('rm.edit.fiolocked') : undefined} />
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', margin: '10px 0', alignItems: 'flex-end' }}>
              <div style={{ flex: 1, minWidth: 160 }}>
                <label style={label}>{t('rm.f.type')}</label>
                <select style={{ width: '100%' }} value={type} onChange={e => setType(Number(e.target.value))}>
                  {TYPES.map(o => <option key={o.id} value={o.id}>{t(o.k)}</option>)}
                </select>
              </div>
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, paddingBottom: 8 }}>
                <input type="checkbox" checked={privileged} onChange={e => setPrivileged(e.target.checked)} />
                {t('rm.privileged')}
              </label>
            </div>

            {options.length > 20 && (
              <input style={{ width: '100%', marginBottom: 6 }} value={lineQ} onChange={e => setLineQ(e.target.value)}
                placeholder={t('rm.routesearch')} data-testid="rm-route-search" />
            )}
            {lines.map((l, i) => (
              <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 6, alignItems: 'center' }}>
                <select style={{ flex: 1, minWidth: 0 }} value={l.routeId} data-testid={`rm-line-${i}`}
                  onChange={e => setLines(ls => ls.map((x, j) => j === i ? { ...x, routeId: e.target.value } : x))}>
                  <option value="">{t('rm.opt.route')}</option>
                  {optionsFor(l.routeId).map(r => (
                    <option key={r.id} value={r.id}>{r.name.trim()} ({priceOf(r, type).toLocaleString('ru-RU')} с.)</option>
                  ))}
                </select>
                <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, whiteSpace: 'nowrap' }}>
                  <input type="checkbox" checked={l.roundTrip}
                    onChange={e => setLines(ls => ls.map((x, j) => j === i ? { ...x, roundTrip: e.target.checked } : x))} />
                  {t('rm.roundtrip')}
                </label>
                {lines.length > 1 && <button className="btn secondary" onClick={() => setLines(ls => ls.filter((_, j) => j !== i))}>×</button>}
              </div>
            ))}
            <button className="btn secondary" onClick={() => setLines(ls => [...ls, { routeId: '', roundTrip: false }])}>{t('rm.addroute')}</button>

            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12 }}>
              <b style={{ fontSize: 16, flex: 1 }}>{t('rm.total')}: {preview.toLocaleString('ru-RU')} {t('rm.priceunit')}</b>
              {editing && <button className="btn secondary" onClick={resetForm}>{t('rm.edit.cancel')}</button>}
              <button className="btn" onClick={submit} data-testid="rm-submit"
                disabled={(!editing && !fio.trim()) || !lines.some(l => l.routeId)}>
                {editing ? t('rm.edit.save') : t('rm.issue.btn')}
              </button>
            </div>
          </div>
        )}

        {/* Реестр */}
        <div className="card" style={{ overflowX: 'auto', gridColumn: canIssue ? undefined : '1 / -1' }}>
          <h2>{t('rm.issued.h')}</h2>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 10 }}>
            <input style={{ flex: 1, minWidth: 160 }} value={q} placeholder={t('rm.search')} data-testid="rm-q"
              onChange={e => { setQ(e.target.value); setPage(0); }} />
            <input type="date" value={lf} onChange={e => { setLf(e.target.value); setPage(0); }} style={{ width: 150 }} />
            <input type="date" value={lt} onChange={e => { setLt(e.target.value); setPage(0); }} style={{ width: 150 }} />
          </div>
          <table>
            <thead><tr><th>{t('rm.number')}</th><th>{t('rj.date')}</th><th>{t('rm.fio')}</th><th>{t('rm.transport')}</th><th>{t('rm.sum')}</th><th></th></tr></thead>
            <tbody>
              {list.map(m => (
                <tr key={m.id} data-testid="rm-row" style={m.annulled ? { color: 'var(--muted)', textDecoration: 'line-through' } : undefined}>
                  <td className="number">{m.number}</td>
                  <td>{new Date(m.createdAt).toLocaleString('ru-RU')}</td>
                  <td>
                    {m.fio}{m.age === 1 ? ` (${t('rm.benefit').toLowerCase()})` : ''}
                    {m.annulled && <> <span className="badge red" title={m.annulReason ?? ''}>{t('rm.annulled')}</span></>}
                    {m.legacy && <> <span className="badge gray">{t('rm.archive')}</span></>}
                  </td>
                  <td>{(() => { const o = TYPES.find(x => x.id === m.transportTypeId); return o ? t(o.k) : m.transportTypeId; })()}</td>
                  <td>{m.price.toLocaleString('ru-RU')}</td>
                  <td style={{ whiteSpace: 'nowrap', textDecoration: 'none' }}>
                    <button className="btn secondary" onClick={() => openPdf(m.id)}>PDF</button>
                    {canIssue && !m.legacy && !m.annulled && <>{' '}<button className="btn secondary" onClick={() => startEdit(m)}>{t('rm.edit')}</button></>}
                    {canAnnul && !m.annulled && <>{' '}<button className="btn secondary" onClick={() => annul(m)}>{t('rm.annul')}</button></>}
                  </td>
                </tr>
              ))}
              {list.length === 0 && <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 16 }}>{t('rm.empty.issued')}</td></tr>}
            </tbody>
          </table>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10, fontSize: 13, color: 'var(--muted)' }}>
            <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{data?.totalElements ?? 0}</b></span>
            <span style={{ flex: 1 }} />
            <button className="btn secondary" disabled={page <= 0} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
            <span>{page + 1} / {pages}</span>
            <button className="btn secondary" disabled={page + 1 >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
          </div>
        </div>
      </div>

      {/* Отчёт по кассирам */}
      <div className="card" style={{ marginTop: 16, overflowX: 'auto' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 10 }}>
          <h2 style={{ margin: 0 }}>{t('rm.cashierreport')}</h2>
          <span className="spacer" style={{ flex: 1 }} />
          <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 160 }} />
          <span>—</span>
          <input type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 160 }} />
          <button className="btn secondary" onClick={exportReportCsv} disabled={!report}>CSV</button>
          <button className="btn secondary" onClick={downloadReportXlsx} disabled={!report}>XLSX</button>
        </div>
        <table>
          <thead><tr><th>{t('rm.cashier')}</th><th>{t('rm.fio')}</th><th>{t('rm.transport')}</th><th>{t('rm.benefit')}</th><th>{t('rj.date')}</th><th>{t('rm.routes')}</th><th>{t('rm.cost')}</th><th>{t('rm.updater')}</th></tr></thead>
          <tbody>
            {groupsPage.view.flatMap(g => [
              <tr key={`g-${g.issuerRma}`} style={{ background: 'var(--amber-050, #fef9e7)', fontWeight: 700 }}>
                <td>Кассир: {g.issuerName ?? g.issuerRma}</td><td colSpan={5}>справок: {g.count}</td>
                <td>{g.amount.toLocaleString('ru-RU')}</td><td></td>
              </tr>,
              ...g.items.map(it => (
                <tr key={it.id}>
                  <td className="number">№ {it.number}</td><td>{it.fio}</td><td>{it.transportType}</td>
                  <td>{it.privileged ? t('rm.yes') : ''}</td>
                  <td>{new Date(it.issuedAt).toLocaleString('ru-RU')}</td>
                  <td>{it.routes}</td><td>{it.price.toLocaleString('ru-RU')}</td>
                  <td>{it.updaterName ?? ''}</td>
                </tr>
              )),
            ])}
            {report && (
              <tr style={{ fontWeight: 700, borderTop: '2px solid var(--line)' }}>
                <td>ИТОГО</td><td colSpan={5}>справок: {report.count}</td>
                <td>{report.total.toLocaleString('ru-RU')}</td><td></td>
              </tr>
            )}
            {(!report || report.groups.length === 0) && <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 16 }}>{t('rj.nodata')}</td></tr>}
          </tbody>
        </table>
        <Pager {...groupsPage} unitLabel={t('rm.cashier')} />
      </div>

      {isAdmin && (
        <div className="card" style={{ marginTop: 16, overflowX: 'auto' }} data-testid="rm-routes">
          <h2>{t('rm.tariffed.h')}</h2>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 10 }}>
            <input style={{ width: 220 }} placeholder="Название маршрута" value={routeForm.name} onChange={e => setRouteForm(f => ({ ...f, name: e.target.value }))} />
            <input style={{ width: 90 }} type="number" placeholder="км" value={routeForm.distanceKm} onChange={e => setRouteForm(f => ({ ...f, distanceKm: e.target.value }))} />
            <input style={{ width: 110 }} type="number" placeholder="Автобус" value={routeForm.busPrice} onChange={e => setRouteForm(f => ({ ...f, busPrice: e.target.value }))} />
            <input style={{ width: 110 }} type="number" placeholder="Микроавт." value={routeForm.mbusPrice} onChange={e => setRouteForm(f => ({ ...f, mbusPrice: e.target.value }))} />
            <input style={{ width: 110 }} type="number" placeholder="Легковой" value={routeForm.carPrice} onChange={e => setRouteForm(f => ({ ...f, carPrice: e.target.value }))} />
            <button className="btn" onClick={saveRoute} disabled={!routeForm.name}>{t('rm.add')}</button>
            <span style={{ flex: 1 }} />
            <input style={{ width: 220 }} placeholder={t('rm.routesearch')} value={routeQ} data-testid="rm-routes-q" onChange={e => setRouteQ(e.target.value)} />
          </div>
          <table>
            <thead><tr><th>{t('rm.route')}</th><th>{t('rm.km')}</th><th>{t('rm.bus')}</th><th>{t('rm.minibus')}</th><th>{t('rm.car')}</th><th>{t('rm.active')}</th><th></th></tr></thead>
            <tbody>
              {routesPage.view.map(r => routeDraft?.id === r.id ? (
                <tr key={r.id} data-testid="rm-route-edit">
                  <td><input style={{ width: '100%', minWidth: 180 }} value={routeDraft.name} onChange={e => setRouteDraft(d => d && ({ ...d, name: e.target.value }))} /></td>
                  <td><input style={{ width: 70 }} type="number" value={routeDraft.distanceKm} onChange={e => setRouteDraft(d => d && ({ ...d, distanceKm: e.target.value }))} /></td>
                  <td><input style={{ width: 90 }} type="number" value={routeDraft.busPrice} onChange={e => setRouteDraft(d => d && ({ ...d, busPrice: e.target.value }))} /></td>
                  <td><input style={{ width: 90 }} type="number" value={routeDraft.mbusPrice} onChange={e => setRouteDraft(d => d && ({ ...d, mbusPrice: e.target.value }))} /></td>
                  <td><input style={{ width: 90 }} type="number" value={routeDraft.carPrice} data-testid="rm-route-car" onChange={e => setRouteDraft(d => d && ({ ...d, carPrice: e.target.value }))} /></td>
                  <td><input type="checkbox" checked={routeDraft.active} onChange={e => setRouteDraft(d => d && ({ ...d, active: e.target.checked }))} /></td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button className="btn" onClick={updateRoute} data-testid="rm-route-save">{t('rm.save')}</button>{' '}
                    <button className="btn secondary" onClick={() => setRouteDraft(null)}>{t('rm.edit.cancel')}</button>
                  </td>
                </tr>
              ) : (
                <tr key={r.id} style={r.active === false ? { color: 'var(--muted)' } : undefined}>
                  <td>{r.name}{r.active === false ? ` (${t('rm.inactive')})` : ''}</td>
                  <td>{r.distanceKm}</td>
                  <td>{r.busPrice.toLocaleString('ru-RU')}</td>
                  <td>{r.mbusPrice.toLocaleString('ru-RU')}</td>
                  <td>{r.carPrice.toLocaleString('ru-RU')}</td>
                  <td>{r.active ? t('rm.yes') : ''}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button className="btn secondary" onClick={() => setRouteDraft({
                      id: r.id, name: r.name, distanceKm: String(r.distanceKm), busPrice: String(r.busPrice),
                      mbusPrice: String(r.mbusPrice), carPrice: String(r.carPrice), active: r.active,
                    })}>{t('rm.edit')}</button>{' '}
                    <button className="btn secondary" onClick={() => removeRoute(r.id)}>×</button>
                  </td>
                </tr>
              ))}
              {shownRoutes.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 14 }}>{t('rm.empty.routes')}</td></tr>}
            </tbody>
          </table>
          <Pager {...routesPage} />
        </div>
      )}
    </>
  );
}
