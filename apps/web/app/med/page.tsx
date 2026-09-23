'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { md, wb, Waybill } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

/* Локальные пути иконок (не входят в общий набор icons.tsx). */
const CAL = 'M4 5h16a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM3 9h18M8 3v4M16 3v4';
const CLK = 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2';
const XMARK = 'M18 6 6 18M6 6l12 12';
const REFRESH = 'M23 4v6h-6M1 20v-6h6M3.5 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.65 4.36A9 9 0 0 0 20.5 15';
const SEARCH = 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.35-4.35';
const PRINTER = 'M6 9V3h12v6M6 18H4a1 1 0 0 1-1-1v-5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v5a1 1 0 0 1-1 1h-2M6 14h12v7H6z';

const PER_PAGE = 10;

/** Подписи очереди послерейсового осмотра (Т6). Держим рядом с кабинетом, пока общий словарь
 *  правится параллельно; ключи те же по смыслу, что у предрейсовой очереди. */
const T6_TEXT: Record<string, { ru: string; tj: string }> = {
  h: { ru: 'Послерейсовый медосмотр (Т6)', tj: 'Ташхиси тиббии баъд аз рейс (Т6)' },
  lead: { ru: 'Путевые листы после возврата — закрыть лист можно только после этого осмотра',
    tj: 'Роҳхатҳо пас аз бозгашт — роҳхатро танҳо пас аз ин ташхис бастан мумкин аст' },
  empty: { ru: 'Нет водителей, ожидающих послерейсового осмотра', tj: 'Ронандае, ки ташхиси баъд аз рейсро интизор аст, нест' },
  returned: { ru: 'Вернулся', tj: 'Баргашт' },
  ok: { ru: 'Послерейсовый осмотр (Т6) проведён — диспетчер может закрыть путевой лист',
    tj: 'Ташхиси баъд аз рейс (Т6) гузаронида шуд — диспетчер роҳхатро баста метавонад' },
  okDenied: { ru: 'Послерейсовый осмотр (Т6): выявлены нарушения — отмечено в путевом листе',
    tj: 'Ташхиси баъд аз рейс (Т6): вайронкуниҳо ошкор шуданд — дар роҳхат қайд шуд' },
  normal: { ru: 'Состояние в норме', tj: 'Ҳолат муқаррарӣ' },
  abnormal: { ru: 'Выявлены нарушения', tj: 'Вайронкунӣ ошкор шуд' },
};

function isToday(iso: string) {
  const d = new Date(iso), n = new Date();
  return d.toDateString() === n.toDateString();
}
function hhmm(iso: string) {
  return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}
function dt(iso: string) {
  return new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** Запись медосмотра — титул Т2/Т6 путевого листа. Показателей здесь нет: в титуле лежит
 *  только зашифрованный blob indicatorsEnc, читаемые pressure/pulse/temperature в data
 *  не пишутся (WaybillService.withMedicalVerdict). Расшифровка — аудируемый вызов
 *  wb.medIndicators в профиле водителя (/med/journal). */
type MedRecord = {
  id: string; number: string | null; driver: string; org: string; date: string;
  verdict: string; medic: string; passed: boolean;
};

async function medRecordsOf(w: Waybill): Promise<MedRecord[]> {
  try {
    const titles = await wb.titles(w.id);
    return titles.filter(t => t.titleType === 'T2' || t.titleType === 'T6').map(t => {
      const d = (t.data ?? {}) as Record<string, unknown>;
      const verdict = String(d.verdict ?? (w.medPassed ? 'ДОПУЩЕН' : '—'));
      return {
        id: w.id, number: w.number,
        driver: String(w.driverSnapshot?.fullName ?? w.driverRma),
        org: String(w.organizationSnapshot?.name ?? w.organizationRma),
        date: t.signedAt,
        verdict, medic: String(d.employeeName ?? '—'),
        passed: !verdict.toUpperCase().includes('НЕ'),
      };
    });
  } catch { return []; }
}

/**
 * Кабинет медика — панель управления предрейсовыми медосмотрами водителей.
 */
export default function MedWorkstation() {
  const { t, lang } = useT();
  const t6 = (k: string) => T6_TEXT[k]?.[lang === 'tj' ? 'tj' : 'ru'] ?? k;
  const [items, setItems] = useState<Waybill[]>([]);
  const [doctors, setDoctors] = useState<Record<string, { rma: string; name: string }[]>>({});
  const [selected, setSelected] = useState<Waybill | null>(null);
  // Режим осмотра в модалке: предрейсовый (Т2) или послерейсовый (Т6). Эндпоинт один —
  // confirm-med; какой титул ставить, служба решает по статусу листа (CREATED → Т2, RETURNED → Т6).
  const [mode, setMode] = useState<'pre' | 'post'>('pre');
  // Вернувшиеся листы, по которым Т6 ещё не подписан (id). Статус RETURNED остаётся и после Т6,
  // поэтому наличие титула проверяем по списку титулов каждого вернувшегося листа.
  const [postPending, setPostPending] = useState<Set<string>>(new Set());
  const [form, setForm] = useState({ pressure: '120/80', pulse: '72', temperature: '36.6', alcotest: '0.00' });
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [now, setNow] = useState<Date | null>(null);
  const [driverHist, setDriverHist] = useState<MedRecord[] | null>(null);
  const [queueSearch, setQueueSearch] = useState('');
  const [page, setPage] = useState(1);
  // Кто в кабинете: имя врача и его организация (для строки идентификации в шапке).
  const [me, setMe] = useState<{ name: string; orgName: string } | null>(null);

  const reload = useCallback(async () => {
    setItems(await wb.list());
  }, []);

  useEffect(() => { reload().catch(e => setError((e as Error).message)); }, [reload]);

  useEffect(() => {
    setNow(new Date());
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  // Врач и его организация — для шапки кабинета (кто проводит осмотр). Первая организация
  // пользователя; врач — сотрудник с type === 1 (как в остальном коде /med).
  useEffect(() => {
    md.organizations().then(async orgs => {
      const o = orgs[0];
      if (!o) return;
      const emps = await md.employees(String(o.rma)).catch(() => [] as Record<string, unknown>[]);
      const doc = emps.find(e => Number(e.type) === 1);
      setMe({ name: doc ? String(doc.name) : '', orgName: String(o.name ?? o.rma) });
    }).catch(() => { /* нет связи — шапка без идентификации */ });
  }, []);

  const pre = useMemo(() => items.filter(w => w.status === 'CREATED' && !w.medPassed), [items]);

  // Очередь послерейсового осмотра (Т6): вернувшиеся листы без титула Т6. Без неё врач не видел
  // вернувшихся водителей вовсе, а закрытие пассажирских форм требует Т6 — лист нельзя было закрыть
  // из интерфейса (находка подготовки демонстрации 23.09.2026).
  useEffect(() => {
    const returned = items.filter(w => w.status === 'RETURNED');
    if (returned.length === 0) { setPostPending(new Set()); return; }
    let cancelled = false;
    Promise.all(returned.map(w => wb.titles(w.id)
      .then(ts => (ts.some(x => x.titleType === 'T6') ? null : w.id))
      .catch(() => null)))
      .then(ids => { if (!cancelled) setPostPending(new Set(ids.filter((x): x is string => !!x))); });
    return () => { cancelled = true; };
  }, [items]);
  const post = useMemo(() => items.filter(w => w.status === 'RETURNED' && postPending.has(w.id)), [items, postPending]);
  // Группа риска — у водителя есть незакрытый отклонённый медосмотр (другой его ПЛ всё ещё
  // в статусе MED_REJECTED — не заменён и не аннулирован диспетчером): повод для повышенного
  // внимания врача, а не диагноз. Не ловит случаи, когда отказ уже скорректирован титулом
  // CORRECTION или ПЛ аннулирован — там статус этого конкретного ПЛ уже не MED_REJECTED.
  const riskDrivers = useMemo(() => {
    const s = new Set<string>();
    for (const w of items) if (w.status === 'MED_REJECTED') s.add(w.driverRma);
    return s;
  }, [items]);
  const shownPre = useMemo(() => {
    const s = queueSearch.trim().toLowerCase();
    if (!s) return pre;
    return pre.filter(w => [w.number, w.driverRma, w.driverSnapshot?.fullName, w.organizationSnapshot?.name, w.vehicleRegNumber]
      .map(x => String(x ?? '').toLowerCase()).join(' ').includes(s));
  }, [pre, queueSearch]);
  const pages = Math.max(1, Math.ceil(shownPre.length / PER_PAGE));
  const view = shownPre.slice((page - 1) * PER_PAGE, page * PER_PAGE);
  const done = useMemo(
    () => items.filter(w => w.status !== 'CREATED' && (w.medPassed || w.status === 'MED_REJECTED')).slice(0, 5),
    [items],
  );
  const passedToday = useMemo(() => items.filter(w => w.medPassed && isToday(w.createdAt)).length, [items]);
  const rejectedToday = useMemo(() => items.filter(w => w.status === 'MED_REJECTED' && isToday(w.createdAt)).length, [items]);

  const openExam = useCallback(async (w: Waybill, m: 'pre' | 'post' = 'pre') => {
    setSelected(w);
    setMode(m);
    setError('');
    setOk('');
    setForm({ pressure: '120/80', pulse: '72', temperature: '36.6', alcotest: '0.00' });
    // Медицинская история этого водителя — из прошлых осмотров
    setDriverHist(null);
    const past = items.filter(x => x.driverRma === w.driverRma && x.id !== w.id && (x.medPassed || x.status === 'MED_REJECTED')).slice(0, 10);
    Promise.all(past.map(medRecordsOf)).then(rs =>
      setDriverHist(rs.flat().sort((a, b) => b.date.localeCompare(a.date)).slice(0, 5)),
    );
    if (!doctors[w.organizationRma]) {
      try {
        const list = await md.employees(w.organizationRma);
        setDoctors(d => ({
          ...d,
          [w.organizationRma]: list.filter(e => e.type === 1).map(e => ({ rma: String(e.rma), name: String(e.name) })),
        }));
      } catch (e) {
        setError((e as Error).message);
      }
    }
  }, [doctors, items]);

  // Переход с карточки листа «Провести послерейсовый осмотр» — /med?t6=<id>: сразу открыть осмотр.
  useEffect(() => {
    if (typeof window === 'undefined' || selected) return;
    const id = new URLSearchParams(window.location.search).get('t6');
    if (!id) return;
    const w = items.find(x => x.id === id && x.status === 'RETURNED');
    if (w) {
      openExam(w, 'post');
      window.history.replaceState(null, '', '/med');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [items]);

  async function decide(passed: boolean) {
    if (!selected) return;
    const doctor = doctors[selected.organizationRma]?.[0];
    if (!doctor) { setError(t('med.err.nodoctor')); return; }
    setError('');
    try {
      await wb.post(`/${selected.id}/confirm-med`, {
        employeeRma: doctor.rma,
        passed,
        indicators: {
          pressure: form.pressure,
          pulse: Number(form.pulse),
          temperature: Number(form.temperature),
          alcotest: Number(form.alcotest),
        },
      });
      setOk(mode === 'post'
        ? `${passed ? t6('ok') : t6('okDenied')} (${t('med.doctor')} ${doctor.name})`
        : passed
          ? `${t('med.ok.allowed')} (${t('med.doctor')} ${doctor.name})`
          : t('med.ok.denied'));
      setSelected(null);
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const kpis = [
    { label: t('med.kpi.wait'), value: String(pre.length + post.length), icon: P.users, cls: 'ic-blue' },
    { label: t('med.kpi.passed'), value: String(passedToday), icon: P.check, cls: 'ic-green' },
    { label: t('med.kpi.failed'), value: String(rejectedToday), icon: XMARK, cls: 'ic-red' },
    { label: t('med.kpi.examined'), value: String(passedToday + rejectedToday), icon: P.doc, cls: 'ic-amber' },
  ];

  const actions: { title: string; sub: string; icon: string; cls: string; href?: string; onClick?: () => void }[] = [
    { title: t('med.act.new.t'), sub: t('med.act.new.s'), icon: P.med, cls: 'ic-blue', onClick: () => { if (pre[0]) openExam(pre[0]); } },
    { title: t('med.act.search.t'), sub: t('med.act.search.s'), icon: SEARCH, cls: 'ic-cyan', href: '/waybills' },
    { title: t('med.history'), sub: t('med.act.hist.s'), icon: P.doc, cls: 'ic-purple', href: '/waybills' },
    // Журнал медосмотров — готовый отчёт «Дафтари қайди духтӯр» (титулы Т2/Т6 за период,
    // с печатью и выгрузкой). Раньше здесь был window.print(), который печатал экран АРМ, а не журнал.
    { title: t('med.act.print.t'), sub: t('med.act.print.s'), icon: PRINTER, cls: 'ic-green', href: '/reports/journals' },
  ];

  return (
    <>
      {/* Заголовок + дата/время */}
      <div className="toolbar">
        <div>
          <h1>{t('med.h')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('med.lead')}</div>
          {me && (
            <div style={{ marginTop: 5, fontSize: 12.5, color: 'var(--ink-soft)', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <Icon d={P.user} cls="" style={{ width: 14, height: 14, color: 'var(--blue-600)' }} />
              <span>{t('role.DOCTOR')}: <b style={{ color: 'var(--ink)' }}>{me.name || '—'}</b> · {me.orgName}</span>
            </div>
          )}
        </div>
        <span className="spacer" />
        <div style={{ display: 'flex', alignItems: 'center', gap: 20, color: 'var(--muted)', fontSize: 13, fontWeight: 500 }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
            <Icon d={CAL} cls="" style={{ width: 15, height: 15 }} />
            {now ? now.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' }) + ' г.' : '—'}
          </span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, fontVariantNumeric: 'tabular-nums' }}>
            <Icon d={CLK} cls="" style={{ width: 15, height: 15 }} />
            {now ? now.toLocaleTimeString('ru-RU') : '—'}
          </span>
        </div>
      </div>

      {ok && <div className="success">{ok}</div>}
      {error && !selected && <div className="error">{error}</div>}

      {/* KPI */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        {kpis.map(k => (
          <div className="kpi" key={k.label}>
            <div className="k-top"><span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span></div>
            <div className="k-label">{k.label}</div>
            <div className="k-value">{k.value}</div>
          </div>
        ))}
      </div>

      {/* Очередь + правая колонка */}
      <div className="grid-2" style={{ gridTemplateColumns: '1.6fr 1fr', alignItems: 'start' }}>
        {/* Очередь на медосмотр */}
        <div className="card" style={{ marginBottom: 0 }}>
          <div className="card-h">
            <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, margin: 0 }}>
              <Icon d={P.med} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} /> {t('med.queue.h')}
            </h2>
            <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 16 }}>
              <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>{pre.length} {t('med.inqueue')}</span>
              <button
                onClick={() => reload().catch(e => setError((e as Error).message))}
                style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--blue-600)', fontWeight: 600, fontSize: 12.5, display: 'inline-flex', alignItems: 'center', gap: 6, fontFamily: 'inherit' }}
              >
                <Icon d={REFRESH} cls="" style={{ width: 15, height: 15 }} /> {t('btn.refresh')}
              </button>
            </div>
          </div>
          <input value={queueSearch} onChange={e => { setQueueSearch(e.target.value); setPage(1); }} placeholder={t('exam.search')}
            style={{ marginBottom: 12 }} />
          <table>
            <thead>
              <tr><th>{t('col.wbnum')}</th><th>{t('col.driver')}</th><th>{t('col.company')}</th><th>{t('col.time')}</th><th>{t('col.status')}</th><th></th></tr>
            </thead>
            <tbody>
              {view.map(w => (
                <tr key={w.id}>
                  <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                  <td>
                    {String(w.driverSnapshot?.fullName ?? w.driverRma)}
                    {riskDrivers.has(w.driverRma) && (
                      <span className="badge red" style={{ marginLeft: 8 }} title={t('med.riskgroup.hint')}>{t('med.riskgroup')}</span>
                    )}
                  </td>
                  <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                  <td>{hhmm(w.createdAt)}</td>
                  <td><span className="badge amber">{t('st.waiting')}</span></td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn secondary" onClick={() => openExam(w)}>{t('med.btn.start')}</button>
                  </td>
                </tr>
              ))}
              {shownPre.length === 0 && (
                <tr><td colSpan={6} style={{ color: 'var(--muted)', textAlign: 'center', padding: 28 }}>{queueSearch.trim() ? t('exam.search.empty') : t('med.empty.queue')}</td></tr>
              )}
            </tbody>
          </table>
          {/* Пагинация */}
          <div style={{ display: 'flex', alignItems: 'center', marginTop: 14, fontSize: 12.5, color: 'var(--muted)' }}>
            <span>{t('paging.shown')} {shownPre.length === 0 ? 0 : (page - 1) * PER_PAGE + 1}–{Math.min(page * PER_PAGE, shownPre.length)} {t('paging.of')} {shownPre.length}</span>
            <span style={{ flex: 1 }} />
            <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
            <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
            <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
          </div>
        </div>

        {/* Правая колонка */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          {/* Очередь на сегодня — реальные ожидающие медосмотра (не мок) */}
          <div className="card" style={{ marginBottom: 0 }}>
            <h2>{t('med.schedule.h')}</h2>
            {pre.slice(0, 6).map(w => (
              <div key={w.id} style={{ display: 'flex', gap: 12, padding: '11px 0', borderBottom: '1px solid var(--line-soft)' }}>
                <div style={{ width: 42, flex: 'none', fontWeight: 700, fontSize: 13, color: 'var(--ink)', fontVariantNumeric: 'tabular-nums' }}>{hhmm(w.createdAt)}</div>
                <div style={{ flex: 1, minWidth: 0, borderLeft: '2px solid var(--blue-500)', paddingLeft: 11 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <b style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink)' }}>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</b>
                    <span className="badge amber" style={{ marginLeft: 'auto' }}>{t('st.waiting')}</span>
                  </div>
                  <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 2 }}>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</div>
                  <div className="number" style={{ fontSize: 11.5, marginTop: 2 }}>{w.number ?? t('common.draft')}</div>
                </div>
              </div>
            ))}
            {pre.length === 0 && <div style={{ color: 'var(--muted)', fontSize: 13, padding: '12px 2px' }}>{t('med.empty.queue')}</div>}
            <Link className="link" href="/waybills" style={{ display: 'inline-block', marginTop: 12 }}>{t('med.schedule.full')}</Link>
          </div>

          {/* Быстрые действия */}
          <div className="card" style={{ marginBottom: 0 }}>
            <h2>{t('med.quickactions')}</h2>
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              {actions.map((a, idx) => {
                const inner = (
                  <>
                    <span className={a.cls} style={{ width: 38, height: 38, borderRadius: 10, display: 'grid', placeItems: 'center', flex: 'none' }}>
                      <Icon d={a.icon} cls="" />
                    </span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: 600, fontSize: 13, color: 'var(--ink)' }}>{a.title}</div>
                      <div style={{ fontSize: 12, color: 'var(--muted)' }}>{a.sub}</div>
                    </div>
                    <Icon d={P.chevron} cls="" style={{ width: 16, height: 16, color: 'var(--faint)' }} />
                  </>
                );
                const rowStyle: React.CSSProperties = {
                  display: 'flex', alignItems: 'center', gap: 12, padding: '11px 4px',
                  borderTop: idx === 0 ? 'none' : '1px solid var(--line-soft)',
                  textDecoration: 'none', color: 'inherit',
                };
                return a.href
                  ? <Link key={a.title} href={a.href} style={rowStyle}>{inner}</Link>
                  : (
                    <button
                      key={a.title}
                      onClick={a.onClick}
                      style={{ ...rowStyle, background: 'none', border: 'none', borderTop: rowStyle.borderTop, width: '100%', textAlign: 'left', cursor: 'pointer', fontFamily: 'inherit' }}
                    >
                      {inner}
                    </button>
                  );
              })}
            </div>
          </div>
        </div>
      </div>

      {/* Очередь послерейсового медосмотра (Т6) — вернувшиеся водители */}
      <div className="card" data-testid="med-post-queue">
        <div className="card-h">
          <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, margin: 0 }}>
            <Icon d={P.med} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} /> {t6('h')}
          </h2>
          <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 12.5 }}>{post.length} {t('med.inqueue')}</span>
        </div>
        <div style={{ color: 'var(--muted)', fontSize: 12.5, marginBottom: 10 }}>{t6('lead')}</div>
        <table>
          <thead>
            <tr><th>{t('col.wbnum')}</th><th>{t('col.driver')}</th><th>{t('col.company')}</th><th>{t('col.transport')}</th><th>{t('col.status')}</th><th></th></tr>
          </thead>
          <tbody>
            {post.map(w => (
              <tr key={w.id}>
                <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                <td>{w.vehicleRegNumber}</td>
                <td><span className="badge amber">{t6('returned')}</span></td>
                <td style={{ textAlign: 'right' }}>
                  <button className="btn secondary" onClick={() => openExam(w, 'post')}>{t('med.btn.start')}</button>
                </td>
              </tr>
            ))}
            {post.length === 0 && (
              <tr><td colSpan={6} style={{ color: 'var(--muted)', textAlign: 'center', padding: 22 }}>{t6('empty')}</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Последние завершённые осмотры */}
      <div className="card">
        <div className="card-h"><h2>{t('med.recent.h')}</h2></div>
        <table>
          <thead>
            <tr><th>{t('col.wbnum')}</th><th>{t('col.driver')}</th><th>{t('col.company')}</th><th>{t('col.time')}</th><th>{t('col.result')}</th><th></th></tr>
          </thead>
          <tbody>
            {done.map(w => (
              <tr key={w.id}>
                <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                <td>{hhmm(w.createdAt)}</td>
                <td>
                  {w.medPassed
                    ? <span className="badge green">{t('st.passed')}</span>
                    : <span className="badge red">{t('st.failed')}</span>}
                </td>
                <td style={{ textAlign: 'right' }}>
                  <Link href={`/waybills/${w.id}`} style={{ color: 'var(--faint)', display: 'inline-flex' }} aria-label={t('btn.view')}>
                    <Icon d={P.eye} cls="" style={{ width: 18, height: 18 }} />
                  </Link>
                </td>
              </tr>
            ))}
            {done.length === 0 && (
              <tr><td colSpan={6} style={{ color: 'var(--muted)', textAlign: 'center', padding: 28 }}>{t('med.empty.recent')}</td></tr>
            )}
          </tbody>
        </table>
        <Link className="link" href="/med/journal" style={{ display: 'inline-block', marginTop: 12 }}>{t('med.gotohistory')}</Link>
      </div>

      {/* Модалка проведения осмотра */}
      {selected && (
        <div
          onClick={() => setSelected(null)}
          style={{ position: 'fixed', inset: 0, background: 'rgba(15,27,52,.45)', display: 'grid', placeItems: 'center', zIndex: 50, padding: 20 }}
        >
          <div className="card" onClick={e => e.stopPropagation()} style={{ width: 540, maxWidth: '100%', margin: 0, borderColor: 'var(--blue-500)', boxShadow: 'var(--shadow-lg)' }}>
            <div className="card-h">
              <h2>{mode === 'post' ? t6('h') : t('med.modal.h')}</h2>
              <button
                onClick={() => setSelected(null)}
                aria-label={t('btn.close')}
                style={{ marginLeft: 'auto', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--muted)' }}
              >
                <Icon d={XMARK} cls="" style={{ width: 18, height: 18 }} />
              </button>
            </div>
            <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'center', marginBottom: 16, fontSize: 13, color: 'var(--ink-soft)' }}>
              <span className="number">{selected.number ?? t('common.draft')}</span>
              <b style={{ color: 'var(--ink)' }}>{String(selected.driverSnapshot?.fullName ?? selected.driverRma)}</b>
              {riskDrivers.has(selected.driverRma) && (
                <span className="badge red" title={t('med.riskgroup.hint')}>{t('med.riskgroup')}</span>
              )}
              <span>{String(selected.organizationSnapshot?.name ?? selected.organizationRma)}</span>
            </div>

            {/* Медицинская история водителя */}
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink-soft)', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '.04em' }}>{t('med.prevexams')}</div>
              {driverHist === null && <div style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('med.loading.short')}</div>}
              {driverHist && driverHist.length === 0 && <div style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('med.noexams')}</div>}
              {driverHist && driverHist.length > 0 && (
                <>
                  {/* Показатели прошлых осмотров здесь не показываем: они зашифрованы (indicatorsEnc),
                      а их расшифровка — отдельный аудируемый доступ. Он делается осознанно
                      в профиле водителя (История осмотров), а не фоном при открытии формы. */}
                  <table style={{ fontSize: 12.5 }}>
                    <thead><tr><th>{t('col.date')}</th><th>{t('col.wbnum')}</th><th>{t('role.DOCTOR')}</th><th>{t('col.verdict')}</th></tr></thead>
                    <tbody>
                      {driverHist.map((r, i) => (
                        <tr key={i}>
                          <td>{dt(r.date)}</td>
                          <td><span className="number">{r.number ?? '—'}</span></td>
                          <td>{r.medic}</td>
                          <td><span className={`badge ${r.passed ? 'green' : 'red'}`}>{r.passed ? t('st.passed') : t('st.failed')}</span></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <Link className="link" href="/med/journal" style={{ display: 'inline-block', marginTop: 8, fontSize: 12.5 }}>
                    {t('med.j.gotoprofile')}
                  </Link>
                </>
              )}
            </div>

            {error && <div className="error">{error}</div>}

            <form className="grid" onSubmit={e => e.preventDefault()}>
              <div>
                <label>{t('med.f.pressure')}</label>
                <input value={form.pressure} onChange={e => setForm({ ...form, pressure: e.target.value })} />
              </div>
              <div>
                <label>{t('med.f.pulse')}</label>
                <input type="number" value={form.pulse} onChange={e => setForm({ ...form, pulse: e.target.value })} />
              </div>
              <div>
                <label>{t('med.f.temp')}</label>
                <input type="number" step="0.1" value={form.temperature} onChange={e => setForm({ ...form, temperature: e.target.value })} />
              </div>
              <div>
                <label>{t('med.f.alco')}</label>
                <input type="number" step="0.01" value={form.alcotest} onChange={e => setForm({ ...form, alcotest: e.target.value })} />
              </div>
              <div className="full" style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <button className="btn success" onClick={() => decide(true)}>
                  <Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> {mode === 'post' ? t6('normal') : t('med.btn.allow')}
                </button>
                <button className="btn danger" onClick={() => decide(false)}>
                  <Icon d={XMARK} cls="" style={{ width: 16, height: 16 }} /> {mode === 'post' ? t6('abnormal') : t('med.btn.deny')}
                </button>
                <button className="btn secondary" onClick={() => setSelected(null)}>{t('btn.cancel')}</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
}
