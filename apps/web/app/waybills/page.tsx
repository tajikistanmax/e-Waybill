'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS, TYPE_LABELS } from '@/lib/api';

export default function WaybillsPage() {
  const [items, setItems] = useState<Waybill[]>([]);
  const [error, setError] = useState('');
  const router = useRouter();

  useEffect(() => {
    wb.list().then(setItems).catch(e => setError(e.message));
  }, []);

  return (
    <>
      <div className="toolbar">
        <h1>Реестр путевых листов</h1>
        <span className="spacer" />
        <Link className="btn" href="/waybills/new">+ Новый путевой лист</Link>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Номер</th>
              <th>Тип</th>
              <th>ТС</th>
              <th>Водитель</th>
              <th>Маршрут</th>
              <th>Статус</th>
              <th>Создан</th>
            </tr>
          </thead>
          <tbody>
            {items.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td className="number">{w.number ?? '—'}</td>
                  <td>{TYPE_LABELS[w.waybillType] ?? w.waybillType}</td>
                  <td>{w.vehicleRegNumber}</td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                  <td>{w.route ?? '—'}</td>
                  <td><span className={`badge ${s.color}`}>{s.label}</span></td>
                  <td>{new Date(w.createdAt).toLocaleString('ru-RU')}</td>
                </tr>
              );
            })}
            {items.length === 0 && !error && (
              <tr><td colSpan={7} style={{ color: 'var(--muted)', textAlign: 'center', padding: 30 }}>
                Путевых листов пока нет — создайте первый
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
