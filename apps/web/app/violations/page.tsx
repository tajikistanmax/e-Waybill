'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type Detail = { reason: string | null; at: string; actor: string | null };

// Инциденты = статусы ПЛ, требующие внимания контролёра.
const INCIDENT_STATUSES = ['BLOCKED', 'CANCELLED', 'EXPIRED'];
const PER_PAGE = 15;

/** Источник нарушения по статусу: блокировка — инспектор, аннулирование — диспетчер, просрочка — система. */
function sourceLabel(status: string, t: (k: string) => string): string {
  return status === 'BLOCKED' ? t('role.INSPECTOR') : status === 'CANCELLED' ? t('role.DISPATCHER') : t('viol.system');
}

/**
 * Нарушения и инциденты. Строится над реальными данными waybill-service:
 * список ПЛ (wb.list) фильтруется по «проблемным» статусам, причина и время —
 * из истории статусов (wb.history). Отдельного API нарушений в системе нет —
 * блокировка инспектора (/block) и есть основное «нарушение».
 */
export default function ViolationsPage() {
  const { t, tStatus } = useT();
  const [items, setItems] = useState<Waybill[]>([]);
  const [details, setDetails] = useState<Record<string, Detail | null>>({});
  const [scope, setScope] = useState<'blocked' | 'all'>('blocked');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    let cancelled = false;
    wb.list().then(list => {
      if (!cancelled) { setItems(list); setLoading(false); }
    }).catch(e => { if (!cancelled) { setError(e.message); setLoading(false); } });
    return () => { cancelled = true; };
  }, []);

  const counts = useMemo(() => ({
    blocked: items.filter(w => w.status === 'BLOCKED').length,
    cancelled: items.filter(w => w.status === 'CANCELLED').length,
    expired: items.filter(w => w.status === 'EXPIRED').length,
  }), [items]);

  // Сортировка по дате создания/начала ПЛ — доступна сразу, без сети. Точную дату и причину
  // самого инцидента (из истории статусов) подгружаем отдельно только для видимой страницы —
  // раньше здесь был Promise.all(wb.history(id)) на КАЖДЫЙ инцидент разом (в демо-данных
  // это сотни параллельных запросов на один заход на страницу), что и роняло общий rate-limit
  // на всю платформу разом (найдено 2026-09-04 по жалобе пользователя на частые 429).
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
      .sort((a, b) => (b.validFrom ?? b.createdAt).localeCompare(a.validFrom ?? a.createdAt));
  }, [items, scope, q, details]);

  const pages = Math.max(1, Math.ceil(rows.length / PER_PAGE));
  const pageRows = useMemo(() => rows.slice((page - 1) * PER_PAGE, page * PER_PAGE), [rows, page]);
  useEffect(() => { setPage(1); }, [scope, q]);

  // Точные причина/время инцидента — только для строк текущей страницы (максимум PER_PAGE
  // запросов за раз, не сотни).
  useEffect(() => {
    let cancelled = false;
    const need = pageRows.filter(w => !(w.id in details));
    if (need.length === 0) return;
    Promise.all(need.map(async w => {
      try {
        const h = await wb.history(w.id);
        const ev = [...h].reverse().find(e => e.toStatus === w.status);
        return [w.id, ev ? { reason: ev.reason, at: ev.createdAt, actor: ev.actor } : null] as const;
      } catch {
        return [w.id, null] as const;
      }
    })).then(entries => {
      if (!cancelled) setDetails(d => ({ ...d, ...Object.fromEntries(entries) }));
    });
    return () => { cancelled = true; };
  }, [pageRows, details]);

  const fmt = (d: string | null | undefined) => d ? new Date(d).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

  const KPIS = [
    { label: t('viol.kpi.blocked'), value: counts.blocked, icon: P.shield, cls: 'ic-red' },
    { label: t('kpi.cancel'), value: counts.cancelled, icon: P.doc, cls: 'ic-amber' },
    { label: t('insp.kpi.expired'), value: counts.expired, icon: P.chart, cls: 'ic-purple' },
  ];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.violations')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('viol.lead')}</div>
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
            <button className={`btn ${scope === 'blocked' ? '' : 'secondary'}`} onClick={() => setScope('blocked')}>{t('viol.tab.active')}</button>
            <button className={`btn ${scope === 'all' ? '' : 'secondary'}`} onClick={() => setScope('all')}>{t('viol.tab.all')}</button>
          </div>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('viol.search')} style={{ marginLeft: 'auto', width: 300 }} />
        </div>

        {/* 9 колонок: плотная таблица с прокруткой внутри карточки — на 1280 колонка «Статус» уходила за край. */}
        <div style={{ overflowX: 'auto' }}>
        <table className="dense">
          <thead>
            <tr>
              <th>{t('col.datetime')}</th><th>{t('col.wbnum')}</th><th>{t('viol.col.type')}</th><th>{t('col.transport')}</th>
              <th>{t('col.driver')}</th><th>{t('col.org')}</th><th>{t('col.source')}</th><th>{t('col.status')}</th><th></th>
            </tr>
          </thead>
          <tbody>
            {pageRows.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              const d = details[w.id];
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td>{fmt(d?.at ?? w.validFrom ?? w.createdAt)}</td>
                  <td><span className="number">{w.number ?? t('viol.draft')}</span></td>
                  <td style={{ fontWeight: 600, color: 'var(--ink)', minWidth: 220 }}>{d?.reason ?? '—'}</td>
                  <td>{String(w.vehicleSnapshot?.brand ?? '')} {w.vehicleRegNumber}</td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                  <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                  <td><span className={`badge ${w.status === 'BLOCKED' ? 'red' : 'gray'}`}>{sourceLabel(w.status, t)}</span></td>
                  <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                  <td onClick={e => e.stopPropagation()}>
                    <Link href={`/waybills/${w.id}`} className="tb-icon" style={{ width: 32, height: 32, display: 'inline-grid' }} title={t('btn.open')}>
                      <Icon d={P.eye} cls="" style={{ width: 17, height: 17, color: 'var(--muted)' }} />
                    </Link>
                  </td>
                </tr>
              );
            })}
            {rows.length === 0 && (
              <tr><td colSpan={9} style={{ textAlign: 'center', color: 'var(--muted)', padding: 34 }}>
                {loading ? t('common.loading') : t('viol.empty')}
              </td></tr>
            )}
          </tbody>
        </table>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', padding: '14px 16px', borderTop: '1px solid var(--line)', fontSize: 13, color: 'var(--muted)' }}>
          {t('dash.total')}: <b style={{ color: 'var(--ink)', marginLeft: 4 }}>{rows.length}</b>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
          <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>
      </div>
    </>
  );
}
