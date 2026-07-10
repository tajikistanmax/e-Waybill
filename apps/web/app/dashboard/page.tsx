'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS, TYPE_LABELS } from '@/lib/api';

/** Сегменты полосы статусов: группировка 20 статусов в 5 читаемых категорий. */
const SEGMENTS: { key: string; label: string; color: string; match: (s: string) => boolean }[] = [
  { key: 'work', label: 'В работе', color: '#cfa94f', match: s => ['DRAFT', 'CREATED', 'AWAITING_PAYMENT', 'PAID', 'READY', 'ISSUED'].includes(s) },
  { key: 'active', label: 'На линии', color: '#0a4c39', match: s => ['ACTIVE', 'IN_TRIP', 'RETURNED'].includes(s) },
  { key: 'done', label: 'Завершено', color: '#7d8f86', match: s => ['COMPLETED', 'ARCHIVED'].includes(s) },
  { key: 'stop', label: 'Отклонено / блок', color: '#b91f34', match: s => ['MED_REJECTED', 'TECH_REJECTED', 'BLOCKED', 'EXPIRED'].includes(s) },
  { key: 'cancel', label: 'Аннулировано', color: '#c9d3ce', match: s => s === 'CANCELLED' },
];

function isToday(iso: string) {
  const d = new Date(iso);
  const n = new Date();
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
}

export default function DashboardPage() {
  const [items, setItems] = useState<Waybill[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    wb.list().then(setItems).catch(e => setError(e.message)).finally(() => setLoading(false));
  }, []);

  const total = items.length;
  const today = items.filter(w => isToday(w.createdAt)).length;
  const onLine = items.filter(w => ['ACTIVE', 'IN_TRIP', 'RETURNED'].includes(w.status)).length;
  const awaiting = items.filter(w => ['CREATED', 'AWAITING_PAYMENT'].includes(w.status)).length;
  const completed = items.filter(w => w.status === 'COMPLETED').length;

  const segCounts = SEGMENTS.map(seg => ({ ...seg, n: items.filter(w => seg.match(w.status)).length }));
  const segTotal = segCounts.reduce((a, s) => a + s.n, 0) || 1;

  const byType = Object.entries(
    items.reduce<Record<string, number>>((acc, w) => { acc[w.waybillType] = (acc[w.waybillType] ?? 0) + 1; return acc; }, {}),
  ).sort((a, b) => b[1] - a[1]).slice(0, 6);

  const recent = [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 7);

  const dateStr = new Date().toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>Панель управления</h1>
          <p className="page-lead" style={{ margin: 0 }}>Электронные путевые листы · {dateStr}</p>
        </div>
        <span className="spacer" />
        <Link className="btn gold" href="/waybills/new">+ Новый путевой лист</Link>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="kpi-grid">
        <div className="kpi" style={{ '--accent': '#f6eccf' } as React.CSSProperties}>
          <div className="k-label">Оформлено сегодня</div>
          <div className="k-value gold">{loading ? '—' : today}</div>
          <div className="k-foot">Всего в системе: {total}</div>
        </div>
        <div className="kpi" style={{ '--accent': '#e0f0e6' } as React.CSSProperties}>
          <div className="k-label">На линии</div>
          <div className="k-value green">{loading ? '—' : onLine}</div>
          <div className="k-foot">Активные рейсы</div>
        </div>
        <div className="kpi" style={{ '--accent': '#f6eccf' } as React.CSSProperties}>
          <div className="k-label">Ожидают оформления</div>
          <div className="k-value">{loading ? '—' : awaiting}</div>
          <div className="k-foot">Осмотр или оплата</div>
        </div>
        <div className="kpi" style={{ '--accent': '#eef1ef' } as React.CSSProperties}>
          <div className="k-label">Завершено</div>
          <div className="k-value">{loading ? '—' : completed}</div>
          <div className="k-foot">Рейс закрыт</div>
        </div>
      </div>

      <div className="dash-2col">
        <div className="card">
          <h2>Распределение по статусам</h2>
          {total === 0 ? (
            <p style={{ color: 'var(--muted)', fontSize: 13 }}>Путевых листов пока нет.</p>
          ) : (
            <>
              <div className="statbar">
                {segCounts.filter(s => s.n > 0).map(s => (
                  <div key={s.key} className="seg" style={{ flex: s.n, background: s.color }} title={`${s.label}: ${s.n}`}>
                    {s.n / segTotal > 0.06 ? s.n : ''}
                  </div>
                ))}
              </div>
              <div className="stat-legend">
                {segCounts.map(s => (
                  <span key={s.key} className="item">
                    <span className="dot" style={{ background: s.color }} />
                    {s.label} · <b>{s.n}</b>
                  </span>
                ))}
              </div>
            </>
          )}
        </div>

        <div className="card">
          <h2>По типам транспорта</h2>
          {byType.length === 0 ? (
            <p style={{ color: 'var(--muted)', fontSize: 13 }}>Нет данных.</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {byType.map(([type, n]) => {
                const max = byType[0][1] || 1;
                return (
                  <div key={type}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5, marginBottom: 4 }}>
                      <span>{TYPE_LABELS[type] ?? type}</span><b>{n}</b>
                    </div>
                    <div style={{ height: 7, background: 'var(--line-soft)', borderRadius: 4, overflow: 'hidden' }}>
                      <div style={{ width: `${(n / max) * 100}%`, height: '100%', background: 'var(--green-700)', borderRadius: 4 }} />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <div className="card" style={{ marginTop: 18 }}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
          <h2 style={{ margin: 0 }}>Последние путевые листы</h2>
          <span style={{ flex: 1 }} />
          <Link href="/waybills" style={{ fontSize: 13, color: 'var(--green-800)', fontWeight: 600, textDecoration: 'none' }}>
            Все документы →
          </Link>
        </div>
        <table>
          <thead>
            <tr><th>Номер</th><th>Тип</th><th>ТС</th><th>Водитель</th><th>Статус</th><th>Создан</th></tr>
          </thead>
          <tbody>
            {recent.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? '— черновик —'}</span></td>
                  <td>{TYPE_LABELS[w.waybillType] ?? w.waybillType}</td>
                  <td>{w.vehicleRegNumber}</td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                  <td><span className={`badge ${s.color}`}>{s.label}</span></td>
                  <td>{new Date(w.createdAt).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })}</td>
                </tr>
              );
            })}
            {recent.length === 0 && !loading && (
              <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 28 }}>
                Путевых листов пока нет — <Link href="/waybills/new" style={{ color: 'var(--green-800)' }}>создайте первый</Link>
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
