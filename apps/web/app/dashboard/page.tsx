'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  ResponsiveContainer, AreaChart, Area, LineChart, ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  PieChart, Pie, Cell,
} from 'recharts';
import { wb, md, Waybill, STATUS_LABELS, type DashboardStats } from '@/lib/api';
import { Icon, P } from '../icons';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { canCreateWaybill } from '@/lib/roles';

const TYPE_COLORS = ['#2563eb', '#16a34a', '#ea9615', '#f97316', '#ef4444', '#7c5cdb', '#0ea5c4', '#64748b', '#db2777', '#0891b2'];

type Svc = 'up' | 'down' | 'checking';

function dayKey(d: Date) { return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' }); }

export default function DashboardPage() {
  const [data, setData] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const router = useRouter();
  const { t, tType, tStatus } = useT();
  const { roles } = useAuth();

  // Оператор платформы: только ему показываем здоровье бэкенд-служб — для перевозчика
  // это чужая эксплуатационная информация, а не показатель его работы.
  const isPlatformOperator = roles.includes('SYSTEM_ADMIN');
  // Кто вправе выписать ПЛ (иначе кнопка вела бы к 403 на создании).
  const canCreate = canCreateWaybill(roles);
  // Область данных: платформенные роли видят все организации, остальные — только свою.
  const isPlatformWide = ['SYSTEM_ADMIN', 'MINTRANS_ANALYST', 'INSPECTOR'].some(r => roles.includes(r));
  const [scopeName, setScopeName] = useState('');

  // Показатели считает сервер по всей области видимости (раньше — в браузере по 1000 последним листам, H4).
  useEffect(() => { wb.dashboard().then(setData).catch((e: Error) => setError(e.message)).finally(() => setLoading(false)); }, []);

  // Пассажирооборот (млн пасс-км) автобус/троллейбус по месяцам — перенос легаси-графика
  // Admin\Charts\Ebus\PassengerVolumeController, единственного содержательного KPI старой панели.
  // + число выписанных ПЛ по месяцам (legаси Ebus\BillCountsController) и выбор вида: оба / автобус / троллейбус
  // (легаси dashboard/ebus — только троллейбус). MIGRATION.md 6.8.
  const [volumeTrend, setVolumeTrend] = useState<{ name: string; value: number; waybills: number }[]>([]);
  const [trendType, setTrendType] = useState<'' | 'WB_BUS' | 'WB_TROLLEYBUS'>('');
  useEffect(() => {
    wb.passengerVolumeTrend(7, trendType || undefined)
      .then(r => setVolumeTrend(r.points.map(p => {
        const [y, m] = p.month.split('-').map(Number);
        return { name: new Date(y, m - 1, 1).toLocaleDateString('ru-RU', { month: 'short', year: '2-digit' }), value: p.turnoverMillion, waybills: p.waybills ?? 0 };
      })))
      .catch(() => setVolumeTrend([]));
  }, [trendType]);

  // Подпись области: название своей организации (для тенанта) — чтобы было видно, чьи это цифры.
  useEffect(() => {
    if (isPlatformWide) { setScopeName(''); return; }
    md.organizations()
      .then(l => setScopeName(l.length === 1 ? String(l[0].name ?? '') : l.length > 1 ? `${l.length} ${t('dash.orgscount')}` : ''))
      .catch(() => setScopeName(''));
  }, [isPlatformWide]);

  const stats = useMemo(() => {
    const src = data?.days ?? [];
    // Динамика за 7 дней (дата — сутки службы, Asia/Dushanbe).
    const days = src.map(x => {
      const [y, m, d] = x.date.split('-').map(Number);
      return { d: dayKey(new Date(y, m - 1, d)), Создано: x.created, Завершено: x.completed };
    });
    return {
      total: data?.total ?? 0, today: data?.today ?? 0, onLine: data?.onLine ?? 0,
      completed: data?.completed ?? 0, cancelled: data?.cancelled ?? 0,
      days,
      byType: (data?.byType ?? []).map(x => ({ name: x.type, value: x.count })),
      sparkTotal: src.map(x => ({ v: x.created })),
      sparkDone: src.map(x => ({ v: x.completed })),
      sparkActive: src.map(x => ({ v: x.active })),
      sparkCancel: src.map(x => ({ v: x.cancelled })),
    };
  }, [data]);

  const recent: Waybill[] = data?.recent ?? [];

  const KPIS = [
    { label: t('kpi.total'), value: stats.total, icon: P.doc, cls: 'ic-blue', spark: stats.sparkTotal, color: '#2563eb' },
    { label: t('kpi.today'), value: stats.today, icon: P.check, cls: 'ic-green', spark: stats.sparkTotal, color: '#16a34a' },
    { label: t('kpi.online'), value: stats.onLine, icon: P.car, cls: 'ic-cyan', spark: stats.sparkActive, color: '#0ea5c4' },
    { label: t('kpi.done'), value: stats.completed, icon: P.route, cls: 'ic-purple', spark: stats.sparkDone, color: '#7c5cdb' },
    { label: t('kpi.cancel'), value: stats.cancelled, icon: P.alert, cls: 'ic-red', spark: stats.sparkCancel, color: '#dc2626' },
  ];

  // Состояние систем — реальная проверка здоровья бэкенд-служб (actuator/health), не заглушка.
  const [health, setHealth] = useState<Record<string, Svc>>({ wb: 'checking', md: 'checking' });
  useEffect(() => {
    if (!isPlatformOperator) return; // карточка скрыта — не дёргаем actuator без нужды
    const set = (k: string, s: Svc) => setHealth(h => ({ ...h, [k]: s }));
    const check = (k: string, url: string) => fetch(url)
      .then(r => set(k, r.ok ? 'up' : 'down')).catch(() => set(k, 'down'));
    check('wb', '/wb-api/actuator/health');
    check('md', '/md-api/actuator/health');
  }, [isPlatformOperator]);
  const services: { key: string; status: Svc }[] = [
    { key: 'sys.svc.app', status: 'up' },
    { key: 'sys.svc.waybill', status: health.wb },
    { key: 'sys.svc.masterdata', status: health.md },
  ];
  const anyDown = services.some(s => s.status === 'down');

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('dash.h')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>
            {isPlatformWide ? t('dash.lead.all') : t('dash.lead.own')}
            {scopeName && <> · <b style={{ color: 'var(--ink)' }}>{scopeName}</b></>}
          </div>
        </div>
        <span className="spacer" />
      </div>

      {error && <div className="error">{error}</div>}

      {/* KPI */}
      <div className="kpi-row">
        {KPIS.map(k => (
          <div className="kpi" key={k.label}>
            <div className="k-top">
              <span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span>
              <div className="k-spark">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={k.spark} margin={{ top: 4, bottom: 0, left: 0, right: 0 }}>
                    <defs><linearGradient id={`g-${k.label}`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={k.color} stopOpacity={0.35} /><stop offset="100%" stopColor={k.color} stopOpacity={0} />
                    </linearGradient></defs>
                    <Area type="monotone" dataKey="v" stroke={k.color} strokeWidth={2} fill={`url(#g-${k.label})`} />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </div>
            <div className="k-label">{k.label}</div>
            <div className="k-value">{loading ? '—' : k.value.toLocaleString('ru-RU')}</div>
          </div>
        ))}
      </div>

      {/* Динамика · Типы · (Состояние служб — только оператору платформы) */}
      <div className={isPlatformOperator ? 'grid-3' : 'grid-2'}>
        <div className="card">
          <div className="card-h"><h2>{t('dash.dynamics')}</h2><span className="badge blue" style={{ marginLeft: 'auto' }}>7</span></div>
          <div style={{ height: 240 }}>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={stats.days} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#eef2f7" vertical={false} />
                <XAxis dataKey="d" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} allowDecimals={false} />
                <Tooltip contentStyle={{ borderRadius: 10, border: '1px solid #e4e9f0', fontSize: 12 }} />
                <Line type="monotone" dataKey="Создано" name={t('dash.created')} stroke="#2563eb" strokeWidth={2.5} dot={{ r: 3 }} />
                <Line type="monotone" dataKey="Завершено" name={t('dash.completed')} stroke="#7c5cdb" strokeWidth={2.5} dot={{ r: 3 }} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>

        <div className="card">
          <h2>{t('dash.bytype')}</h2>
          {stats.byType.length === 0 ? <p style={{ color: 'var(--muted)', fontSize: 13 }}>—</p> : (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <div style={{ width: 150, height: 150, position: 'relative' }}>
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={stats.byType} dataKey="value" innerRadius={44} outerRadius={70} paddingAngle={2} stroke="none">
                      {stats.byType.map((_, i) => <Cell key={i} fill={TYPE_COLORS[i % TYPE_COLORS.length]} />)}
                    </Pie>
                  </PieChart>
                </ResponsiveContainer>
                <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', pointerEvents: 'none' }}>
                  <div style={{ textAlign: 'center' }}>
                    <div style={{ fontSize: 10, color: 'var(--muted)' }}>{t('dash.total')}</div>
                    <div style={{ fontSize: 20, fontWeight: 800 }}>{stats.total}</div>
                  </div>
                </div>
              </div>
              <div className="donut-legend" style={{ flex: 1 }}>
                {stats.byType.map((t, i) => (
                  <div className="row" key={t.name}>
                    <span className="dot" style={{ background: TYPE_COLORS[i % TYPE_COLORS.length] }} />
                    <span className="nm">{tType(t.name).replace(/\s*\(.*\)/, '')}</span>
                    <span className="pc">{Math.round((t.value / stats.total) * 100)}%</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {isPlatformOperator && (
        <div className="card">
          <h2>{t('dash.sysstate')}</h2>
          <div className="sys-list">
            {services.map(s => (
              <div className="row" key={s.key}>
                {t(s.key)}
                <span className="st" style={s.status === 'down' ? { color: 'var(--red)' } : s.status === 'checking' ? { color: 'var(--muted)' } : undefined}>
                  {s.status === 'up' ? t('sys.online') : s.status === 'down' ? t('sys.offline') : t('sys.checking')}
                </span>
              </div>
            ))}
          </div>
          {anyDown
            ? <div className="sys-ok" style={{ color: 'var(--red)' }}><Icon d={P.alert} cls="" style={{ width: 16, height: 16 }} /> {t('dash.sysdown')}</div>
            : <div className="sys-ok"><Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> {t('dash.sysok')}</div>}
        </div>
        )}
      </div>

      {/* Пассажирооборот автобус/троллейбус — реальный KPI (перенос легаси Ebus\PassengerVolumeController) */}
      <div className="card">
        <div className="card-h">
          <h2>{t('dash.paxturnover')}</h2>
          <select value={trendType} onChange={e => setTrendType(e.target.value as '' | 'WB_BUS' | 'WB_TROLLEYBUS')} style={{ marginLeft: 'auto', width: 190 }} title={t('dash.trend.type')}>
            <option value="">{t('dash.trend.all')}</option>
            <option value="WB_BUS">{tType('WB_BUS').replace(/\s*\(.*\)/, '')}</option>
            <option value="WB_TROLLEYBUS">{tType('WB_TROLLEYBUS').replace(/\s*\(.*\)/, '')}</option>
          </select>
          <span className="badge blue" style={{ marginLeft: 8 }}>{volumeTrend.length} {t('dash.months')}</span>
        </div>
        <div style={{ height: 220 }}>
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={volumeTrend} margin={{ top: 8, right: 0, left: -18, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#eef2f7" vertical={false} />
              <XAxis dataKey="name" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
              <YAxis yAxisId="left" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
              <YAxis yAxisId="right" orientation="right" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} width={44} />
              <Tooltip contentStyle={{ borderRadius: 10, border: '1px solid #e4e9f0', fontSize: 12 }}
                formatter={(v, name) => name === t('dash.wbcount')
                  ? [Number(v).toLocaleString('ru-RU'), t('dash.wbcount')]
                  : [`${Number(v).toLocaleString('ru-RU')} ${t('dash.mlnpaxkm')}`, t('dash.turnover')]} />
              <Bar yAxisId="right" dataKey="waybills" name={t('dash.wbcount')} fill="rgba(66, 186, 150, 0.4)" stroke="rgb(66, 186, 150)" radius={[4, 4, 0, 0]} />
              <Line yAxisId="left" type="monotone" dataKey="value" name={t('dash.turnover')} stroke="#7c5cdb" strokeWidth={2.5} dot={{ r: 3 }} />
            </ComposedChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Последние ПЛ · Быстрая статистика */}
      <div className="grid-2" style={{ gridTemplateColumns: '1.6fr 1fr' }}>
        <div className="card">
          <div className="card-h"><h2>{t('dash.recent')}</h2><Link className="link" href="/waybills">{t('dash.all')} →</Link></div>
          <table>
            <thead><tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.vehicle')}</th><th>{t('col.driver')}</th><th>{t('col.status')}</th></tr></thead>
            <tbody>
              {recent.map(w => {
                const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
                return (
                  <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                    <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                    <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                    <td>{w.vehicleRegNumber}</td>
                    <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                    <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                  </tr>
                );
              })}
              {recent.length === 0 && !loading && (
                <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>
                  {canCreate
                    ? <>{t('dash.recent.emptypre')}<Link href="/waybills/new" style={{ color: 'var(--blue-600)' }}>{t('dash.recent.emptylink')}</Link></>
                    : t('dash.recent.empty')}
                </td></tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="card">
          <h2>{t('dash.quick')}</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div className="q" style={{ background: 'var(--blue-050)', borderRadius: 11, padding: 14 }}>
              <div className="ql" style={{ fontSize: 11, color: 'var(--muted)' }}>{t('dash.qs.total')}</div>
              <div style={{ fontSize: 22, fontWeight: 800 }}>{stats.total}</div>
            </div>
            <div className="q" style={{ background: 'var(--green-050)', borderRadius: 11, padding: 14 }}>
              <div className="ql" style={{ fontSize: 11, color: 'var(--muted)' }}>{t('kpi.done')}</div>
              <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--green)' }}>{stats.completed}</div>
            </div>
            <div className="q" style={{ background: 'var(--cyan-050)', borderRadius: 11, padding: 14 }}>
              <div className="ql" style={{ fontSize: 11, color: 'var(--muted)' }}>{t('kpi.online')}</div>
              <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--cyan)' }}>{stats.onLine}</div>
            </div>
            <div className="q" style={{ background: 'var(--amber-050)', borderRadius: 11, padding: 14 }}>
              <div className="ql" style={{ fontSize: 11, color: 'var(--muted)' }}>{t('kpi.today')}</div>
              <div style={{ fontSize: 22, fontWeight: 800, color: '#a9700a' }}>{stats.today}</div>
            </div>
          </div>
          <div style={{ marginTop: 16 }}>
            <div className="card-h" style={{ marginBottom: 10 }}><h2 style={{ fontSize: 13 }}>{t('dash.activity')}</h2></div>
            <div className="feed">
              {recent.slice(0, 4).map(w => (
                <div className="fi" key={w.id}>
                  <span className="fic ic-blue"><Icon d={P.doc} cls="" /></span>
                  <div className="ft">
                    <b>{w.number ?? t('common.draftshort')} · {tStatus(w.status)}</b>
                    <span>{String(w.driverSnapshot?.fullName ?? w.driverRma)} · {w.vehicleRegNumber}</span>
                  </div>
                  <span className="ftime">{new Date(w.createdAt).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
