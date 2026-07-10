'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS, TYPE_LABELS } from '@/lib/api';
import { Icon, P } from '../icons';

function fmtDateTime(iso: string | null) {
  return iso
    ? new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
    : '—';
}
function isToday(iso: string | null) {
  if (!iso) return false;
  const d = new Date(iso);
  const n = new Date();
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
}

/** Что нужно сделать по путевому листу в данном статусе (подсказка диспетчеру). */
const ACTION_HINT: Record<string, string> = {
  DRAFT: 'Оформить титул Т1',
  CREATED: 'Ожидает медосмотра и техконтроля',
  MED_REJECTED: 'Медосмотр отклонён — заменить водителя',
  TECH_REJECTED: 'Техконтроль отклонён — заменить ТС',
  AWAITING_PAYMENT: 'Ожидает оплаты',
  PAID: 'Оплачен — подготовить к выдаче',
  READY: 'Готов — выдать водителю',
  RETURNED: 'Возвращён — закрыть путевой лист',
  BLOCKED: 'Заблокирован инспектором',
};

/**
 * Кабинет диспетчера — оперативный обзор смены: сколько ПЛ в работе, какие требуют
 * действия (осмотры, оплата, выдача, закрытие) и последние оформленные листы.
 * Диспетчер оформляет и ведёт путевые листы своей организации.
 */
export default function DispatcherCabinet() {
  const [items, setItems] = useState<Waybill[]>([]);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    wb.list().then(setItems).catch(() => {}).finally(() => setLoading(false));
  }, []);

  const stats = useMemo(() => {
    const total = items.length;
    const active = items.filter(w => ['ISSUED', 'ACTIVE', 'RETURNED'].includes(w.status)).length;
    const pending = items.filter(w => ['DRAFT', 'CREATED', 'MED_REJECTED', 'TECH_REJECTED', 'AWAITING_PAYMENT', 'PAID', 'READY'].includes(w.status)).length;
    const today = items.filter(w => isToday(w.createdAt)).length;
    return { total, active, pending, today };
  }, [items]);

  // Требуют внимания диспетчера — новые сверху.
  const attention = useMemo(
    () => items
      .filter(w => ACTION_HINT[w.status])
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [items],
  );

  const recent = useMemo(
    () => [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, 8),
    [items],
  );

  const kpis = [
    { label: 'Всего путевых листов', value: stats.total, icon: P.doc, cls: 'ic-blue' },
    { label: 'В работе', value: stats.active, icon: P.car, cls: 'ic-cyan' },
    { label: 'Требуют действия', value: stats.pending, icon: P.alert, cls: 'ic-amber' },
    { label: 'Оформлено сегодня', value: stats.today, icon: P.check, cls: 'ic-green' },
  ];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>Кабинет диспетчера</h1>
          <div className="page-lead" style={{ margin: 0 }}>Оформление и контроль путевых листов</div>
        </div>
        <Link href="/waybills/new" className="btn" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.doc} cls="" /> Создать путевой лист
        </Link>
      </div>

      {/* KPI */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        {kpis.map(k => (
          <div className="kpi" key={k.label}>
            <div className="k-top"><span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span></div>
            <div className="k-label">{k.label}</div>
            <div className="k-value">{loading ? '—' : k.value.toLocaleString('ru-RU')}</div>
          </div>
        ))}
      </div>

      {/* Требуют внимания */}
      <div className="card">
        <div className="card-h">
          <h2>Требуют внимания</h2>
          <Link className="link" href="/waybills">Весь реестр →</Link>
        </div>
        <table>
          <thead>
            <tr><th>Номер</th><th>Тип</th><th>Транспорт</th><th>Водитель</th><th>Что сделать</th><th>Статус</th></tr>
          </thead>
          <tbody>
            {attention.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? '— черновик —'}</span></td>
                  <td>{(TYPE_LABELS[w.waybillType] ?? w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{w.vehicleRegNumber || '—'}</td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma ?? '—')}</td>
                  <td style={{ color: 'var(--ink-soft)' }}>{ACTION_HINT[w.status]}</td>
                  <td><span className={`badge ${s.color}`}>{s.label}</span></td>
                </tr>
              );
            })}
            {attention.length === 0 && !loading && (
              <tr>
                <td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>
                  Все путевые листы в порядке — нет ожидающих действий
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Последние путевые листы */}
      <div className="card">
        <div className="card-h">
          <h2>Последние путевые листы</h2>
          <Link className="link" href="/waybills">Все документы →</Link>
        </div>
        <table>
          <thead>
            <tr><th>Номер</th><th>Тип</th><th>Транспорт</th><th>Создан</th><th>Статус</th></tr>
          </thead>
          <tbody>
            {recent.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? '— черновик —'}</span></td>
                  <td>{(TYPE_LABELS[w.waybillType] ?? w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{w.vehicleRegNumber || '—'}</td>
                  <td>{fmtDateTime(w.createdAt)}</td>
                  <td><span className={`badge ${s.color}`}>{s.label}</span></td>
                </tr>
              );
            })}
            {recent.length === 0 && !loading && (
              <tr>
                <td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>Путевых листов пока нет</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
