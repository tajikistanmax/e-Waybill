'use client';

import { useCallback, useEffect, useState } from 'react';
import { wb, authHeaders, type Malumotnoma, type MalumotnomaRoute, type MalumotnomaReport } from '@/lib/api';
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

type Line = { routeId: string; roundTrip: boolean };

function today(offset = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return d.toISOString().slice(0, 10);
}

/** Справки пассажирам (маълумотнома): выдача, реестр, отчёт по кассирам. */
export default function MalumotnomaTab() {
  const { roles } = useAuth();
  const { t } = useT();
  const isAdmin = roles.includes('SYSTEM_ADMIN');
  const [routes, setRoutes] = useState<MalumotnomaRoute[]>([]);
  const [routeForm, setRouteForm] = useState({ name: '', carPrice: '', mbusPrice: '', busPrice: '', distanceKm: '' });
  const [list, setList] = useState<Malumotnoma[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  // Форма выдачи.
  const [fio, setFio] = useState('');
  // По умолчанию — легковой: в старой платформе 99,99 % справок выдавались на легковой (анализ 24.09.2026).
  const [type, setType] = useState(4);
  const [privileged, setPrivileged] = useState(false);
  const [lines, setLines] = useState<Line[]>([{ routeId: '', roundTrip: false }]);

  // Отчёт.
  const [from, setFrom] = useState(today(-30));
  const [to, setTo] = useState(today());
  const [report, setReport] = useState<MalumotnomaReport | null>(null);
  // Постраничный вывод длинных списков (владелец, 22.09): реестр справок, кассиры, тарифы маршрутов.
  const listPage = usePaged(list, 20);
  const groupsPage = usePaged(report?.groups ?? [], 5);
  const routesPage = usePaged(routes, 20);

  const priceOf = useCallback((r: MalumotnomaRoute, t: number) =>
    t === 1 ? r.busPrice : t === 3 ? r.mbusPrice : t === 4 ? r.carPrice : 0, []);

  const preview = lines.reduce((sum, l) => {
    const r = routes.find(x => x.id === l.routeId);
    if (!r) return sum;
    return sum + priceOf(r, type) * (l.roundTrip ? 2 : 1);
  }, 0) * (privileged ? 0.5 : 1);

  const loadList = useCallback(() => {
    wb.malumotnoma.list().then(setList).catch(e => setError(e.message));
  }, []);

  const loadRoutes = useCallback(() => {
    (isAdmin ? wb.malumotnomaRoutes.all() : wb.malumotnoma.routes()).then(setRoutes).catch(e => setError(e.message));
  }, [isAdmin]);

  useEffect(() => {
    loadRoutes();
    loadList();
  }, [loadRoutes, loadList]);

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
  async function removeRoute(id: string) {
    if (!confirm('Удалить маршрут?')) return;
    try { await wb.malumotnomaRoutes.remove(id); loadRoutes(); } catch (e) { setError((e as Error).message); }
  }

  const loadReport = useCallback(() => {
    wb.malumotnoma.report(from, to).then(setReport).catch(e => setError(e.message));
  }, [from, to]);

  useEffect(() => { loadReport(); }, [loadReport]);

  async function issue() {
    setError(''); setOk('');
    try {
      const body = {
        fio, transportTypeId: type, age: privileged ? 1 : 0,
        lines: lines.filter(l => l.routeId).map(l => ({ routeId: l.routeId, roundTrip: l.roundTrip })),
      };
      const m = await wb.malumotnoma.create(body);
      setOk(`Справка выдана: ${m.fio} — ${m.price.toLocaleString('ru-RU')} сомонӣ`);
      setFio(''); setPrivileged(false); setLines([{ routeId: '', roundTrip: false }]);
      loadList(); loadReport();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function remove(id: string) {
    if (!confirm('Удалить справку?')) return;
    try { await wb.malumotnoma.remove(id); loadList(); loadReport(); }
    catch (e) { setError((e as Error).message); }
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
    const rows: unknown[][] = [['Кассир', 'Справка', 'ФИО', 'Транспорт', 'Льготная', 'Дата', 'Маршруты', 'Стоимость']];
    report.groups.forEach(g => {
      rows.push([`Кассир: ${g.issuerName ?? g.issuerRma}`, '', '', '', '', '', `справок: ${g.count}`, g.amount]);
      g.items.forEach(it => rows.push(['', it.id.slice(0, 8), it.fio, it.transportType, it.privileged ? 'да' : '', it.issuedAt, it.routes, it.price]));
    });
    rows.push(['ИТОГО', '', '', '', '', '', `справок: ${report.count}`, report.total]);
    downloadCsv(`справки-${from}_${to}.csv`, rows);
  }

  return (
    <>
      {error && <div className="error">{error}</div>}
      {ok && <div className="ok" style={{ background: 'var(--green-050)', color: 'var(--green)', padding: '8px 12px', borderRadius: 8, marginBottom: 12 }}>{ok}</div>}

      <div className="grid-2">
        {/* Выдача */}
        <div className="card">
          <h2>{t('rm.issue.h')}</h2>
          <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('rm.f.fio')}</label>
          <input style={{ width: '100%' }} value={fio} onChange={e => setFio(e.target.value)} placeholder="Каримов Карим Каримович" />
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', margin: '10px 0', alignItems: 'flex-end' }}>
            <div style={{ flex: 1, minWidth: 160 }}>
              <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('rm.f.type')}</label>
              <select style={{ width: '100%' }} value={type} onChange={e => setType(Number(e.target.value))}>
                {TYPES.map(o => <option key={o.id} value={o.id}>{t(o.k)}</option>)}
              </select>
            </div>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, paddingBottom: 8 }}>
              <input type="checkbox" checked={privileged} onChange={e => setPrivileged(e.target.checked)} />
              {t('rm.privileged')}
            </label>
          </div>

          {lines.map((l, i) => (
            <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 6, alignItems: 'center' }}>
              <select style={{ flex: 1 }} value={l.routeId}
                onChange={e => setLines(ls => ls.map((x, j) => j === i ? { ...x, routeId: e.target.value } : x))}>
                <option value="">{t('rm.opt.route')}</option>
                {routes.map(r => <option key={r.id} value={r.id}>{r.name} ({priceOf(r, type).toLocaleString('ru-RU')} с.)</option>)}
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

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 12 }}>
            <b style={{ fontSize: 16 }}>{t('rm.total')}: {preview.toLocaleString('ru-RU')} {t('rm.priceunit')}</b>
            <button className="btn" onClick={issue} disabled={!fio || !lines.some(l => l.routeId)}>{t('rm.issue.btn')}</button>
          </div>
        </div>

        {/* Реестр */}
        <div className="card" style={{ overflowX: 'auto' }}>
          <h2>{t('rm.issued.h')}</h2>
          <table>
            <thead><tr><th>{t('rj.date')}</th><th>{t('rm.fio')}</th><th>{t('rm.transport')}</th><th>{t('rm.sum')}</th><th></th></tr></thead>
            <tbody>
              {listPage.view.map(m => (
                <tr key={m.id}>
                  <td>{new Date(m.createdAt).toLocaleString('ru-RU')}</td>
                  <td>{m.fio}{m.age === 1 ? ` (${t('rm.benefit').toLowerCase()})` : ''}</td>
                  <td>{(() => { const o = TYPES.find(x => x.id === m.transportTypeId); return o ? t(o.k) : m.transportTypeId; })()}</td>
                  <td>{m.price.toLocaleString('ru-RU')}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button className="btn secondary" onClick={() => openPdf(m.id)}>PDF</button>{' '}
                    <button className="btn secondary" onClick={() => remove(m.id)}>×</button>
                  </td>
                </tr>
              ))}
              {list.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 16 }}>{t('rm.empty.issued')}</td></tr>}
            </tbody>
          </table>
          <Pager {...listPage} />
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
          <thead><tr><th>{t('rm.cashier')}</th><th>{t('rm.fio')}</th><th>{t('rm.transport')}</th><th>{t('rm.benefit')}</th><th>{t('rj.date')}</th><th>{t('rm.routes')}</th><th>{t('rm.cost')}</th></tr></thead>
          <tbody>
            {groupsPage.view.flatMap(g => [
              <tr key={`g-${g.issuerRma}`} style={{ background: 'var(--amber-050, #fef9e7)', fontWeight: 700 }}>
                <td>Кассир: {g.issuerName ?? g.issuerRma}</td><td colSpan={5}>справок: {g.count}</td>
                <td>{g.amount.toLocaleString('ru-RU')}</td>
              </tr>,
              ...g.items.map(it => (
                <tr key={it.id}>
                  <td>{it.id.slice(0, 8)}</td><td>{it.fio}</td><td>{it.transportType}</td>
                  <td>{it.privileged ? t('rm.yes') : ''}</td>
                  <td>{new Date(it.issuedAt).toLocaleString('ru-RU')}</td>
                  <td>{it.routes}</td><td>{it.price.toLocaleString('ru-RU')}</td>
                </tr>
              )),
            ])}
            {report && (
              <tr style={{ fontWeight: 700, borderTop: '2px solid var(--line)' }}>
                <td>ИТОГО</td><td colSpan={5}>справок: {report.count}</td>
                <td>{report.total.toLocaleString('ru-RU')}</td>
              </tr>
            )}
            {(!report || report.groups.length === 0) && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 16 }}>{t('rj.nodata')}</td></tr>}
          </tbody>
        </table>
        <Pager {...groupsPage} unitLabel={t('rm.cashier')} />
      </div>

      {isAdmin && (
        <div className="card" style={{ marginTop: 16, overflowX: 'auto' }}>
          <h2>{t('rm.tariffed.h')}</h2>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 10 }}>
            <input style={{ width: 220 }} placeholder="Название маршрута" value={routeForm.name} onChange={e => setRouteForm(f => ({ ...f, name: e.target.value }))} />
            <input style={{ width: 90 }} type="number" placeholder="км" value={routeForm.distanceKm} onChange={e => setRouteForm(f => ({ ...f, distanceKm: e.target.value }))} />
            <input style={{ width: 110 }} type="number" placeholder="Автобус" value={routeForm.busPrice} onChange={e => setRouteForm(f => ({ ...f, busPrice: e.target.value }))} />
            <input style={{ width: 110 }} type="number" placeholder="Микроавт." value={routeForm.mbusPrice} onChange={e => setRouteForm(f => ({ ...f, mbusPrice: e.target.value }))} />
            <input style={{ width: 110 }} type="number" placeholder="Легковой" value={routeForm.carPrice} onChange={e => setRouteForm(f => ({ ...f, carPrice: e.target.value }))} />
            <button className="btn" onClick={saveRoute} disabled={!routeForm.name}>{t('rm.add')}</button>
          </div>
          <table>
            <thead><tr><th>{t('rm.route')}</th><th>{t('rm.km')}</th><th>{t('rm.bus')}</th><th>{t('rm.minibus')}</th><th>{t('rm.car')}</th><th></th></tr></thead>
            <tbody>
              {routesPage.view.map(r => (
                <tr key={r.id}>
                  <td>{r.name}{r.active === false ? ` (${t('rm.inactive')})` : ''}</td>
                  <td>{r.distanceKm}</td>
                  <td>{r.busPrice.toLocaleString('ru-RU')}</td>
                  <td>{r.mbusPrice.toLocaleString('ru-RU')}</td>
                  <td>{r.carPrice.toLocaleString('ru-RU')}</td>
                  <td><button className="btn secondary" onClick={() => removeRoute(r.id)}>×</button></td>
                </tr>
              ))}
              {routes.length === 0 && <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 14 }}>{t('rm.empty.routes')}</td></tr>}
            </tbody>
          </table>
          <Pager {...routesPage} />
        </div>
      )}
    </>
  );
}
