'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS } from '@/lib/api';
import { Icon, P } from '../icons';

type Detail = { reason: string | null; at: string; actor: string | null };

// Инциденты = статусы ПЛ, требующие внимания контролёра.
const INCIDENT_STATUSES = ['BLOCKED', 'CANCELLED', 'EXPIRED'];

/** Источник нарушения по статусу: блокировка — инспектор, аннулирование — диспетчер, просрочка — система. */
function sourceLabel(status: string): string {
  return status === 'BLOCKED' ? 'Инспектор' : status === 'CANCELLED' ? 'Диспетчер' : 'Система';
}

/**
 * Нарушения и инциденты. Строится над реальными данными waybill-service:
 * список ПЛ (wb.list) фильтруется по «проблемным» статусам, причина и время —
 * из истории статусов (wb.history). Отдельного API нарушений в системе нет —
 * блокировка инспектора (/block) и есть основное «нарушение».
 */
export default function ViolationsPage() {
  const [items, setItems] = useState<Waybill[]>([]);
  const [details, setDetails] = useState<Record<string, Detail | null>>({});
  const [scope, setScope] = useState<'blocked' | 'all'>('blocked');
  const [q, setQ] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    let cancelled = false;
    wb.list().then(async list => {
      if (cancelled) return;
      setItems(list);
      const incidents = list.filter(w => INCIDENT_STATUSES.includes(w.status));
      const entries = await Promise.all(incidents.map(async w => {
        try {
          const h = await wb.history(w.id);
          const ev = [...h].reverse().find(e => e.toStatus === w.status);
          return [w.id, ev ? { reason: ev.reason, at: ev.createdAt, actor: ev.actor } : null] as const;
        } catch {
          return [w.id, null] as const;
        }
      }));
      if (!cancelled) { setDetails(Object.fromEntries(entries)); setLoading(false); }
    }).catch(e => { if (!cancelled) { setError(e.message); setLoading(false); } });
    return () => { cancelled = true; };
  }, []);

  const counts = useMemo(() => ({
    blocked: items.filter(w => w.status === 'BLOCKED').length,
    cancelled: items.filter(w => w.status === 'CANCELLED').length,
    expired: items.filter(w => w.status === 'EXPIRED').length,
  }), [items]);

  const rows = useMemo(() => {
    const allowed = scope === 'blocked' ? ['BLOCKED'] : INCIDENT_STATUSES;
    const s = q.trim().toLowerCase();
    return items
      .filter(w => allowed.includes(w.status))
      .filter(w => {
        if (!s) return true;
        const hay = `${w.number ?? ''} ${w.vehicleRegNumber} ${String(w.driverSnapshot?.fullName ?? w.driverRma)} ${String(w.organizationSnapshot?.name ?? '')} ${details[w.id]?.reason ?? ''}`.toLowerCase();
        return hay.includes(s);
      })
      .sort((a, b) => (details[b.id]?.at ?? '').localeCompare(details[a.id]?.at ?? ''));
  }, [items, scope, q, details]);

  const fmt = (d: string | null | undefined) => d ? new Date(d).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

  const KPIS = [
    { label: 'Заблокировано инспектором', value: counts.blocked, icon: P.shield, cls: 'ic-red' },
    { label: 'Аннулировано', value: counts.cancelled, icon: P.doc, cls: 'ic-amber' },
    { label: 'Просрочено', value: counts.expired, icon: P.chart, cls: 'ic-purple' },
  ];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>Нарушения</h1>
          <div className="page-lead" style={{ margin: 0 }}>Блокировки инспекторов и проблемные путевые листы</div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {/* KPI */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
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

      {/* Фильтры + таблица */}
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div className="card-h" style={{ padding: '16px 18px 0' }}>
          <div style={{ display: 'flex', gap: 6 }}>
            <button className={`btn ${scope === 'blocked' ? '' : 'secondary'}`} onClick={() => setScope('blocked')}>Активные блокировки</button>
            <button className={`btn ${scope === 'all' ? '' : 'secondary'}`} onClick={() => setScope('all')}>Все инциденты</button>
          </div>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder="Поиск по № ПЛ, ТС, водителю, причине" style={{ marginLeft: 'auto', width: 300 }} />
        </div>

        <table>
          <thead>
            <tr>
              <th>Дата / время</th><th>№ ПЛ</th><th>Тип нарушения</th><th>Транспорт</th>
              <th>Водитель</th><th>Организация</th><th>Источник</th><th>Статус</th><th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              const d = details[w.id];
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td>{fmt(d?.at ?? w.validFrom ?? w.createdAt)}</td>
                  <td><span className="number">{w.number ?? '— черновик —'}</span></td>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{d?.reason ?? '—'}</td>
                  <td>{String(w.vehicleSnapshot?.brand ?? '')} {w.vehicleRegNumber}</td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                  <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                  <td><span className={`badge ${w.status === 'BLOCKED' ? 'red' : 'gray'}`}>{sourceLabel(w.status)}</span></td>
                  <td><span className={`badge ${s.color}`}>{s.label}</span></td>
                  <td onClick={e => e.stopPropagation()}>
                    <Link href={`/waybills/${w.id}`} className="tb-icon" style={{ width: 32, height: 32, display: 'inline-grid' }} title="Открыть">
                      <Icon d={P.eye} cls="" style={{ width: 17, height: 17, color: 'var(--muted)' }} />
                    </Link>
                  </td>
                </tr>
              );
            })}
            {rows.length === 0 && (
              <tr><td colSpan={9} style={{ textAlign: 'center', color: 'var(--muted)', padding: 34 }}>
                {loading ? 'Загрузка…' : 'Нарушений не найдено'}
              </td></tr>
            )}
          </tbody>
        </table>

        <div style={{ display: 'flex', alignItems: 'center', padding: '14px 16px', borderTop: '1px solid var(--line)', fontSize: 13, color: 'var(--muted)' }}>
          Всего: <b style={{ color: 'var(--ink)', marginLeft: 4 }}>{rows.length}</b>
        </div>
      </div>
    </>
  );
}
