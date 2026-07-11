'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  ResponsiveContainer, AreaChart, Area, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip,
  PieChart, Pie, Cell,
} from 'recharts';
import { wb, Waybill, STATUS_LABELS } from '@/lib/api';
import { Icon, P } from '../icons';
import { useT } from '@/lib/i18n';

const TYPE_COLORS = ['#2563eb', '#16a34a', '#ea9615', '#f97316', '#ef4444', '#7c5cdb', '#0ea5c4', '#64748b', '#db2777', '#0891b2'];

function isToday(iso: string) {
  const d = new Date(iso), n = new Date();
  return d.toDateString() === n.toDateString();
}
function dayKey(d: Date) { return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' }); }

export default function DashboardPage() {
  const [items, setItems] = useState<Waybill[]>([]);
  const [loading, setLoading] = useState(true);
  const router = useRouter();
  const { t, tType, tStatus } = useT();

  useEffect(() => { wb.list().then(setItems).catch(() => {}).finally(() => setLoading(false)); }, []);

  const stats = useMemo(() => {
    const total = items.length;
    const today = items.filter(w => isToday(w.createdAt)).length;
    const onLine = items.filter(w => ['ACTIVE', 'IN_TRIP', 'RETURNED'].includes(w.status)).length;
    const completed = items.filter(w => w.status === 'COMPLETED').length;
    const cancelled = items.filter(w => ['CANCELLED', 'EXPIRED', 'BLOCKED'].includes(w.status)).length;

    // Динамика за 7 дней
    const days: { d: string; Создано: number; Завершено: number }[] = [];
    for (let i = 6; i >= 0; i--) {
      const dt = new Date(); dt.setDate(dt.getDate() - i);
      const k = dt.toDateString();
      days.push({
        d: dayKey(dt),
        Создано: items.filter(w => new Date(w.createdAt).toDateString() === k).length,
        Завершено: items.filter(w => w.status === 'COMPLETED' && new Date(w.createdAt).toDateString() === k).length,
      });
    }

    // По типам
    const typeMap = items.reduce<Record<string, number>>((a, w) => { a[w.waybillType] = (a[w.waybillType] ?? 0) + 1; return a; }, {});
    const byType = Object.entries(typeMap).sort((a, b) => b[1] - a[1]).map(([name, value]) => ({ name, value }));

    const spark = (extract: (w: Waybill) => boolean) => days.map(dd => ({
      v: items.filter(w => extract(w) && dayKey(new Date(w.createdAt)) === dd.d).length,
    }));

    return { total, today, onLine, completed, cancelled, days, byType,
      sparkTotal: days.map(dd => ({ v: dd.Создано })),
      sparkDone: spark(w => w.status === 'COMPLETED'),
      sparkActive: spark(w => ['ACTIVE', 'RETURNED'].includes(w.status)),
      sparkCancel: spark(w => ['CANCELLED', 'EXPIRED'].includes(w.status)),
    };
  }, [items]);

  const recent = useMemo(() => [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 6), [items]);

  const KPIS = [
    { label: t('kpi.total'), value: stats.total, icon: P.doc, cls: 'ic-blue', trend: '+8.5%', up: true, spark: stats.sparkTotal, color: '#2563eb' },
    { label: t('kpi.today'), value: stats.today, icon: P.check, cls: 'ic-green', trend: '+12.3%', up: true, spark: stats.sparkTotal, color: '#16a34a' },
    { label: t('kpi.online'), value: stats.onLine, icon: P.car, cls: 'ic-cyan', trend: '•', up: true, spark: stats.sparkActive, color: '#0ea5c4' },
    { label: t('kpi.done'), value: stats.completed, icon: P.route, cls: 'ic-purple', trend: '', up: true, spark: stats.sparkDone, color: '#7c5cdb' },
    { label: t('kpi.cancel'), value: stats.cancelled, icon: P.alert, cls: 'ic-red', trend: '−5.2%', up: false, spark: stats.sparkCancel, color: '#dc2626' },
  ];

  const SYS = ['Сервер приложений', 'База данных', 'Служба авторизации', 'Сервис QR-подписи', 'Шина событий', 'Хранилище файлов'];

  return (
    <>
      <div className="toolbar">
        <div><h1>{t('dash.h')}</h1><div className="page-lead" style={{ margin: 0 }}>{t('dash.lead')}</div></div>
        <span className="spacer" />
        <Link className="btn" href="/waybills/new"><Icon d={P.doc} cls="" style={{ width: 17, height: 17 }} /> {t('dash.new')}</Link>
      </div>

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
            <div className={`k-trend ${k.up ? 'up' : 'down'}`}>{k.trend}</div>
          </div>
        ))}
      </div>

      {/* Динамика · Типы · Состояние */}
      <div className="grid-3">
        <div className="card">
          <div className="card-h"><h2>{t('dash.dynamics')}</h2><span className="badge blue" style={{ marginLeft: 'auto' }}>7</span></div>
          <div style={{ height: 240 }}>
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={stats.days} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#eef2f7" vertical={false} />
                <XAxis dataKey="d" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} allowDecimals={false} />
                <Tooltip contentStyle={{ borderRadius: 10, border: '1px solid #e4e9f0', fontSize: 12 }} />
                <Line type="monotone" dataKey="Создано" stroke="#2563eb" strokeWidth={2.5} dot={{ r: 3 }} />
                <Line type="monotone" dataKey="Завершено" stroke="#7c5cdb" strokeWidth={2.5} dot={{ r: 3 }} />
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

        <div className="card">
          <h2>{t('dash.sysstate')}</h2>
          <div className="sys-list">
            {SYS.map(s => <div className="row" key={s}>{s}<span className="st">{t('sys.online')}</span></div>)}
          </div>
          <div className="sys-ok"><Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> {t('dash.sysok')}</div>
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
                    <td><span className="number">{w.number ?? '— черновик —'}</span></td>
                    <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                    <td>{w.vehicleRegNumber}</td>
                    <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                    <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                  </tr>
                );
              })}
              {recent.length === 0 && !loading && (
                <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>
                  Пока нет — <Link href="/waybills/new" style={{ color: 'var(--blue-600)' }}>создать первый</Link>
                </td></tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="card">
          <h2>{t('dash.quick')}</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div className="q" style={{ background: 'var(--blue-050)', borderRadius: 11, padding: 14 }}>
              <div className="ql" style={{ fontSize: 11, color: 'var(--muted)' }}>Всего документов</div>
              <div style={{ fontSize: 22, fontWeight: 800 }}>{stats.total}</div>
            </div>
            <div className="q" style={{ background: 'var(--green-050)', borderRadius: 11, padding: 14 }}>
              <div className="ql" style={{ fontSize: 11, color: 'var(--muted)' }}>Завершено</div>
              <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--green)' }}>{stats.completed}</div>
            </div>
            <div className="q" style={{ background: 'var(--cyan-050)', borderRadius: 11, padding: 14 }}>
              <div className="ql" style={{ fontSize: 11, color: 'var(--muted)' }}>На линии</div>
              <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--cyan)' }}>{stats.onLine}</div>
            </div>
            <div className="q" style={{ background: 'var(--amber-050)', borderRadius: 11, padding: 14 }}>
              <div className="ql" style={{ fontSize: 11, color: 'var(--muted)' }}>Оформлено сегодня</div>
              <div style={{ fontSize: 22, fontWeight: 800, color: '#a9700a' }}>{stats.today}</div>
            </div>
          </div>
          <div style={{ marginTop: 16 }}>
            <div className="card-h" style={{ marginBottom: 10 }}><h2 style={{ fontSize: 13 }}>Последняя активность</h2></div>
            <div className="feed">
              {recent.slice(0, 4).map(w => (
                <div className="fi" key={w.id}>
                  <span className="fic ic-blue"><Icon d={P.doc} cls="" /></span>
                  <div className="ft">
                    <b>{w.number ?? 'Черновик'} · {tStatus(w.status)}</b>
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
