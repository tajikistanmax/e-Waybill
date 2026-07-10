'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { md, wb, Waybill, TYPE_LABELS } from '@/lib/api';

/**
 * АРМ медицинского работника: лента заявок на предрейсовый (Т2)
 * и послерейсовый (Т6) осмотры.
 */
export default function MedWorkstation() {
  const [pre, setPre] = useState<Waybill[]>([]);
  const [post, setPost] = useState<Waybill[]>([]);
  const [doctors, setDoctors] = useState<Record<string, { rma: string; name: string }[]>>({});
  const [form, setForm] = useState({ pressure: '120/80', pulse: '72', temperature: '36.6', alcotest: '0' });
  const [selected, setSelected] = useState<Waybill | null>(null);
  const [kind, setKind] = useState<'pre' | 'post'>('pre');
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  const reload = useCallback(async () => {
    const all = await wb.list();
    setPre(all.filter(w => w.status === 'CREATED' && !w.medPassed));
    const passengerTypes = ['WB_BUS', 'WB_TROLLEYBUS', 'WB_MINIBUS', 'WB_PAX_INTL', 'WB_TAXI'];
    setPost(all.filter(w => w.status === 'RETURNED' && passengerTypes.includes(w.waybillType)));
  }, []);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);

  async function open(w: Waybill, k: 'pre' | 'post') {
    setSelected(w);
    setKind(k);
    setOk('');
    setError('');
    if (!doctors[w.organizationRma]) {
      const list = await md.employees(w.organizationRma);
      setDoctors(d => ({
        ...d,
        [w.organizationRma]: list.filter(e => e.type === 1).map(e => ({ rma: String(e.rma), name: String(e.name) })),
      }));
    }
  }

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
        ? `${kind === 'pre' ? 'Т2' : 'Т6'} подписан: водитель допущен (врач ${doctor.name})`
        : 'Водитель НЕ допущен — путевой лист отклонён');
      setSelected(null);
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function Queue({ items, k, title }: { items: Waybill[]; k: 'pre' | 'post'; title: string }) {
    return (
      <div className="card">
        <h2>{title} <span className="badge blue">{items.length}</span></h2>
        <table>
          <thead>
            <tr><th>Водитель</th><th>ТС</th><th>Тип</th><th>Организация</th><th></th></tr>
          </thead>
          <tbody>
            {items.map(w => (
              <tr key={w.id}>
                <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                <td>{w.vehicleRegNumber}</td>
                <td>{TYPE_LABELS[w.waybillType] ?? w.waybillType}</td>
                <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                <td><button className="btn secondary" onClick={() => open(w, k)}>Провести осмотр</button></td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr><td colSpan={5} style={{ color: 'var(--muted)', textAlign: 'center', padding: 20 }}>Заявок нет</td></tr>
            )}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <>
      <h1>АРМ медицинского работника (духтур)</h1>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      {selected && (
        <div className="card" style={{ borderColor: 'var(--brand)', borderWidth: 2 }}>
          <h2>{kind === 'pre' ? 'Предрейсовый осмотр (Т2)' : 'Послерейсовый осмотр (Т6)'}:{' '}
            {String(selected.driverSnapshot?.fullName ?? selected.driverRma)}</h2>
          <form className="grid" onSubmit={e => e.preventDefault()}>
            <div>
              <label>Артериальное давление</label>
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
            <div className="full">
              <button className="btn" onClick={() => decide(true)}>✓ Допустить (подписать)</button>
              <button className="btn danger" onClick={() => decide(false)}>✗ Не допустить</button>
              <button className="btn secondary" onClick={() => setSelected(null)}>Отмена</button>
            </div>
          </form>
        </div>
      )}

      <Queue items={pre} k="pre" title="Ожидают предрейсового осмотра (Т2)" />
      <Queue items={post} k="post" title="Ожидают послерейсового осмотра (Т6)" />

      <p style={{ color: 'var(--muted)', fontSize: 13 }}>
        Электронный журнал осмотров — в карточке каждого путевого листа (<Link href="/waybills">реестр</Link>).
      </p>
    </>
  );
}
