'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { ResponsiveContainer, AreaChart, Area } from 'recharts';
import { md, wb, Waybill } from '@/lib/api';
import { Icon, P } from '../icons';

/* Локальные пути иконок (не входят в общий набор icons.tsx). */
const CAL = 'M4 5h16a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM3 9h18M8 3v4M16 3v4';
const CLK = 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2';
const XMARK = 'M18 6 6 18M6 6l12 12';
const REFRESH = 'M23 4v6h-6M1 20v-6h6M3.5 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.65 4.36A9 9 0 0 0 20.5 15';
const SEARCH = 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.35-4.35';
const PRINTER = 'M6 9V3h12v6M6 18H4a1 1 0 0 1-1-1v-5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v5a1 1 0 0 1-1 1h-2M6 14h12v7H6z';

/* Спарклайны KPI (представительные данные для визуализации тренда). */
const SPARK = {
  wait: [{ v: 6 }, { v: 8 }, { v: 7 }, { v: 9 }, { v: 8 }, { v: 11 }, { v: 10 }, { v: 12 }],
  pass: [{ v: 18 }, { v: 20 }, { v: 19 }, { v: 24 }, { v: 22 }, { v: 26 }, { v: 25 }, { v: 28 }],
  fail: [{ v: 5 }, { v: 4 }, { v: 6 }, { v: 3 }, { v: 4 }, { v: 2 }, { v: 3 }, { v: 2 }],
  time: [{ v: 6 }, { v: 5.6 }, { v: 5.2 }, { v: 5 }, { v: 4.8 }, { v: 4.6 }, { v: 4.5 }, { v: 4.53 }],
};

/* Представительное расписание на сегодня (визуальный виджет). */
const SCHEDULE = [
  { time: '11:00', name: 'Давлатов Шерзод У.', org: 'ТаджикТранс ООО', pl: 'PL-2025-000124', st: 'Подтверждён', color: 'green', bar: 'var(--green)' },
  { time: '11:30', name: 'Холматов Бахром Р.', org: 'СеверТранс', pl: 'PL-2025-000125', st: 'В процессе', color: 'blue', bar: 'var(--blue-500)' },
  { time: '12:00', name: 'Юсупов Мухаммад И.', org: 'АвтоСервис', pl: 'PL-2025-000126', st: 'Запланирован', color: 'gray', bar: 'var(--line)' },
  { time: '12:30', name: 'Ибрагимов Саид А.', org: 'Логистик Таджикистан', pl: 'PL-2025-000127', st: 'Запланирован', color: 'gray', bar: 'var(--line)' },
  { time: '13:00', name: 'Курбонов Хусейн К.', org: 'ТаджикТранс ООО', pl: 'PL-2025-000128', st: 'Запланирован', color: 'gray', bar: 'var(--line)' },
];

const MEDIC = 'Иванова Е. А.';

function isToday(iso: string) {
  const d = new Date(iso), n = new Date();
  return d.toDateString() === n.toDateString();
}
function hhmm(iso: string) {
  return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

/**
 * Кабинет медика — панель управления предрейсовыми медосмотрами водителей.
 */
export default function MedWorkstation() {
  const [items, setItems] = useState<Waybill[]>([]);
  const [doctors, setDoctors] = useState<Record<string, { rma: string; name: string }[]>>({});
  const [selected, setSelected] = useState<Waybill | null>(null);
  const [form, setForm] = useState({ pressure: '120/80', pulse: '72', temperature: '36.6', alcotest: '0.00' });
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [now, setNow] = useState<Date | null>(null);

  const reload = useCallback(async () => {
    setItems(await wb.list());
  }, []);

  useEffect(() => { reload().catch(e => setError((e as Error).message)); }, [reload]);

  useEffect(() => {
    setNow(new Date());
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  const pre = useMemo(() => items.filter(w => w.status === 'CREATED' && !w.medPassed), [items]);
  const done = useMemo(
    () => items.filter(w => w.status !== 'CREATED' && (w.medPassed || w.status === 'MED_REJECTED')).slice(0, 5),
    [items],
  );
  const passedToday = useMemo(() => items.filter(w => w.medPassed && isToday(w.createdAt)).length, [items]);
  const rejectedToday = useMemo(() => items.filter(w => w.status === 'MED_REJECTED' && isToday(w.createdAt)).length, [items]);

  const openExam = useCallback(async (w: Waybill) => {
    setSelected(w);
    setError('');
    setOk('');
    setForm({ pressure: '120/80', pulse: '72', temperature: '36.6', alcotest: '0.00' });
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
  }, [doctors]);

  async function decide(passed: boolean) {
    if (!selected) return;
    const doctor = doctors[selected.organizationRma]?.[0];
    if (!doctor) { setError('В организации нет зарегистрированного врача'); return; }
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
      setOk(passed
        ? `Водитель допущен (врач ${doctor.name})`
        : 'Водитель НЕ допущен — путевой лист отклонён');
      setSelected(null);
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const kpis = [
    { label: 'Ожидают медосмотр', value: String(pre.length), icon: P.users, cls: 'ic-blue', trend: '+3 за час', tone: '', color: '#2563eb', spark: SPARK.wait },
    { label: 'Допущено сегодня', value: String(passedToday), icon: P.check, cls: 'ic-green', trend: '+8.3% к вчера', tone: 'up', color: '#16a34a', spark: SPARK.pass },
    { label: 'Не допущено сегодня', value: String(rejectedToday), icon: XMARK, cls: 'ic-red', trend: '−50% к вчера', tone: 'down', color: '#dc2626', spark: SPARK.fail },
    { label: 'Среднее время осмотра', value: '— мин —', icon: CLK, cls: 'ic-amber', trend: '−12% к вчера', tone: 'down', color: '#ea9615', spark: SPARK.time },
  ];

  const actions: { title: string; sub: string; icon: string; cls: string; href?: string; onClick?: () => void }[] = [
    { title: 'Открыть новый осмотр', sub: 'Начать медосмотр водителя', icon: P.med, cls: 'ic-blue', onClick: () => { if (pre[0]) openExam(pre[0]); } },
    { title: 'Поиск по № ПЛ', sub: 'Найти путевой лист', icon: SEARCH, cls: 'ic-cyan', href: '/waybills' },
    { title: 'История осмотров', sub: 'Просмотр завершенных осмотров', icon: P.doc, cls: 'ic-purple', href: '/waybills' },
    { title: 'Печать журнала', sub: 'Печать журнала медосмотров', icon: PRINTER, cls: 'ic-green', onClick: () => window.print() },
  ];

  return (
    <>
      {/* Заголовок + дата/время */}
      <div className="toolbar">
        <div>
          <h1>Кабинет медика</h1>
          <div className="page-lead" style={{ margin: 0 }}>Панель управления медосмотрами водителей</div>
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
        {kpis.map((k, i) => (
          <div className="kpi" key={k.label} style={{ gap: 14 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
              <span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span>
              <div style={{ minWidth: 0 }}>
                <div className="k-label">{k.label}</div>
                <div className="k-value" style={{ marginTop: 6 }}>{k.value}</div>
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 10 }}>
              <span
                className={'k-trend' + (k.tone === 'up' ? ' up' : k.tone === 'down' ? ' down' : '')}
                style={k.tone ? undefined : { color: 'var(--muted)' }}
              >
                {k.trend}
              </span>
              <div className="k-spark" style={{ width: 104, height: 32 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={k.spark} margin={{ top: 4, bottom: 0, left: 0, right: 0 }}>
                    <defs>
                      <linearGradient id={`sp-${i}`} x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor={k.color} stopOpacity={0.35} />
                        <stop offset="100%" stopColor={k.color} stopOpacity={0} />
                      </linearGradient>
                    </defs>
                    <Area type="monotone" dataKey="v" stroke={k.color} strokeWidth={2} fill={`url(#sp-${i})`} />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Очередь + правая колонка */}
      <div className="grid-2" style={{ gridTemplateColumns: '1.6fr 1fr', alignItems: 'start' }}>
        {/* Очередь на медосмотр */}
        <div className="card" style={{ marginBottom: 0 }}>
          <div className="card-h">
            <h2>Очередь на предрейсовый медосмотр</h2>
            <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 16 }}>
              <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>{pre.length} в очереди</span>
              <button
                onClick={() => reload().catch(e => setError((e as Error).message))}
                style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--blue-600)', fontWeight: 600, fontSize: 12.5, display: 'inline-flex', alignItems: 'center', gap: 6, fontFamily: 'inherit' }}
              >
                <Icon d={REFRESH} cls="" style={{ width: 15, height: 15 }} /> Обновить
              </button>
            </div>
          </div>
          <table>
            <thead>
              <tr><th>№ ПЛ</th><th>Водитель</th><th>Компания</th><th>Время</th><th>Статус</th><th></th></tr>
            </thead>
            <tbody>
              {pre.map(w => (
                <tr key={w.id}>
                  <td><span className="number">{w.number ?? '— черновик —'}</span></td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                  <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                  <td>{hhmm(w.createdAt)}</td>
                  <td><span className="badge amber">Ожидает</span></td>
                  <td style={{ textAlign: 'right' }}>
                    <button className="btn secondary" onClick={() => openExam(w)}>Начать осмотр</button>
                  </td>
                </tr>
              ))}
              {pre.length === 0 && (
                <tr><td colSpan={6} style={{ color: 'var(--muted)', textAlign: 'center', padding: 28 }}>Очередь пуста — все водители осмотрены</td></tr>
              )}
            </tbody>
          </table>
          <div style={{ display: 'flex', alignItems: 'center', marginTop: 14, fontSize: 12.5, color: 'var(--muted)' }}>
            <span>Показаны 1–{pre.length} из {pre.length}</span>
            <span style={{ flex: 1 }} />
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <select defaultValue="10" style={{ width: 'auto', padding: '6px 10px', fontSize: 12.5 }}>
                <option value="10">10 на странице</option>
                <option value="20">20 на странице</option>
                <option value="50">50 на странице</option>
              </select>
              <button className="btn secondary" style={{ padding: '7px 10px' }}><Icon d={P.collapse} cls="" style={{ width: 14, height: 14 }} /></button>
              <button className="btn" style={{ padding: '7px 13px' }}>1</button>
              <button className="btn secondary" style={{ padding: '7px 10px' }}><Icon d={P.chevron} cls="" style={{ width: 14, height: 14 }} /></button>
            </div>
          </div>
        </div>

        {/* Правая колонка */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          {/* Расписание на сегодня */}
          <div className="card" style={{ marginBottom: 0 }}>
            <h2>Расписание на сегодня</h2>
            {SCHEDULE.map(s => (
              <div key={s.pl} style={{ display: 'flex', gap: 12, padding: '11px 0', borderBottom: '1px solid var(--line-soft)' }}>
                <div style={{ width: 42, flex: 'none', fontWeight: 700, fontSize: 13, color: 'var(--ink)', fontVariantNumeric: 'tabular-nums' }}>{s.time}</div>
                <div style={{ flex: 1, minWidth: 0, borderLeft: `2px solid ${s.bar}`, paddingLeft: 11 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <b style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink)' }}>{s.name}</b>
                    <span className={`badge ${s.color}`} style={{ marginLeft: 'auto' }}>{s.st}</span>
                  </div>
                  <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 2 }}>{s.org}</div>
                  <div className="number" style={{ fontSize: 11.5, marginTop: 2 }}>{s.pl}</div>
                </div>
              </div>
            ))}
            <Link className="link" href="/waybills" style={{ display: 'inline-block', marginTop: 12 }}>Полное расписание →</Link>
          </div>

          {/* Быстрые действия */}
          <div className="card" style={{ marginBottom: 0 }}>
            <h2>Быстрые действия</h2>
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

      {/* Последние завершённые осмотры */}
      <div className="card">
        <div className="card-h"><h2>Последние завершенные осмотры</h2></div>
        <table>
          <thead>
            <tr><th>№ ПЛ</th><th>Водитель</th><th>Компания</th><th>Время</th><th>Результат</th><th>Медработник</th><th></th></tr>
          </thead>
          <tbody>
            {done.map(w => (
              <tr key={w.id}>
                <td><span className="number">{w.number ?? '— черновик —'}</span></td>
                <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                <td>{hhmm(w.createdAt)}</td>
                <td>
                  {w.medPassed
                    ? <span className="badge green">Допущен</span>
                    : <span className="badge red">Не допущен</span>}
                </td>
                <td>{MEDIC}</td>
                <td style={{ textAlign: 'right' }}>
                  <Link href={`/waybills/${w.id}`} style={{ color: 'var(--faint)', display: 'inline-flex' }} aria-label="Просмотр">
                    <Icon d={P.eye} cls="" style={{ width: 18, height: 18 }} />
                  </Link>
                </td>
              </tr>
            ))}
            {done.length === 0 && (
              <tr><td colSpan={7} style={{ color: 'var(--muted)', textAlign: 'center', padding: 28 }}>Пока нет завершённых осмотров</td></tr>
            )}
          </tbody>
        </table>
        <Link className="link" href="/waybills" style={{ display: 'inline-block', marginTop: 12 }}>Перейти в историю осмотров →</Link>
      </div>

      {/* Модалка проведения осмотра */}
      {selected && (
        <div
          onClick={() => setSelected(null)}
          style={{ position: 'fixed', inset: 0, background: 'rgba(15,27,52,.45)', display: 'grid', placeItems: 'center', zIndex: 50, padding: 20 }}
        >
          <div className="card" onClick={e => e.stopPropagation()} style={{ width: 540, maxWidth: '100%', margin: 0, borderColor: 'var(--blue-500)', boxShadow: 'var(--shadow-lg)' }}>
            <div className="card-h">
              <h2>Предрейсовый медосмотр</h2>
              <button
                onClick={() => setSelected(null)}
                aria-label="Закрыть"
                style={{ marginLeft: 'auto', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--muted)' }}
              >
                <Icon d={XMARK} cls="" style={{ width: 18, height: 18 }} />
              </button>
            </div>
            <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'center', marginBottom: 16, fontSize: 13, color: 'var(--ink-soft)' }}>
              <span className="number">{selected.number ?? '— черновик —'}</span>
              <b style={{ color: 'var(--ink)' }}>{String(selected.driverSnapshot?.fullName ?? selected.driverRma)}</b>
              <span>{String(selected.organizationSnapshot?.name ?? selected.organizationRma)}</span>
            </div>

            {error && <div className="error">{error}</div>}

            <form className="grid" onSubmit={e => e.preventDefault()}>
              <div>
                <label>Артериальное давление, мм рт. ст.</label>
                <input value={form.pressure} onChange={e => setForm({ ...form, pressure: e.target.value })} />
              </div>
              <div>
                <label>Пульс, уд/мин</label>
                <input type="number" value={form.pulse} onChange={e => setForm({ ...form, pulse: e.target.value })} />
              </div>
              <div>
                <label>Температура, °C</label>
                <input type="number" step="0.1" value={form.temperature} onChange={e => setForm({ ...form, temperature: e.target.value })} />
              </div>
              <div>
                <label>Алкотест, ‰</label>
                <input type="number" step="0.01" value={form.alcotest} onChange={e => setForm({ ...form, alcotest: e.target.value })} />
              </div>
              <div className="full" style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <button className="btn success" onClick={() => decide(true)}>
                  <Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> Допустить
                </button>
                <button className="btn danger" onClick={() => decide(false)}>
                  <Icon d={XMARK} cls="" style={{ width: 16, height: 16 }} /> Не допустить
                </button>
                <button className="btn secondary" onClick={() => setSelected(null)}>Отмена</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
}
