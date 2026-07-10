'use client';

import { useCallback, useEffect, useState } from 'react';
import { md, wb, Waybill, TYPE_LABELS } from '@/lib/api';

const CHECK_ITEMS: { key: string; label: string }[] = [
  { key: 'brakes', label: 'Тормозная система' },
  { key: 'steering', label: 'Рулевое управление' },
  { key: 'lights', label: 'Световые приборы' },
  { key: 'tires', label: 'Шины и колёса' },
  { key: 'mirrors', label: 'Зеркала и обзорность' },
  { key: 'body', label: 'Кузов и салон' },
];

/** АРМ механика (контролёра техсостояния): предрейсовый техконтроль Т3. */
export default function TechWorkstation() {
  const [queue, setQueue] = useState<Waybill[]>([]);
  const [mechanics, setMechanics] = useState<Record<string, { rma: string; name: string }[]>>({});
  const [selected, setSelected] = useState<Waybill | null>(null);
  const [checks, setChecks] = useState<Record<string, boolean>>({});
  const [defects, setDefects] = useState('');
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  const reload = useCallback(async () => {
    const all = await wb.list();
    setQueue(all.filter(w => w.status === 'CREATED' && !w.techPassed));
  }, []);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);

  async function open(w: Waybill) {
    setSelected(w);
    setChecks(Object.fromEntries(CHECK_ITEMS.map(i => [i.key, true])));
    setDefects('');
    setOk('');
    setError('');
    if (!mechanics[w.organizationRma]) {
      const list = await md.employees(w.organizationRma);
      setMechanics(m => ({
        ...m,
        [w.organizationRma]: list.filter(e => e.type === 2).map(e => ({ rma: String(e.rma), name: String(e.name) })),
      }));
    }
  }

  async function decide(passed: boolean) {
    if (!selected) return;
    const mechanic = mechanics[selected.organizationRma]?.[0];
    if (!mechanic) { setError('В организации нет зарегистрированного механика'); return; }
    setError('');
    try {
      const checklist: Record<string, string> = {};
      for (const item of CHECK_ITEMS) checklist[item.key] = checks[item.key] ? 'OK' : 'НЕИСПРАВНО';
      if (defects) checklist['defects'] = defects;
      await wb.post(`/${selected.id}/confirm-tech`, { employeeRma: mechanic.rma, passed, checklist });
      setOk(passed
        ? `Т3 подписан: ТС ${selected.vehicleRegNumber} выпущено на линию (механик ${mechanic.name})`
        : `ТС ${selected.vehicleRegNumber} отклонено — неисправно`);
      setSelected(null);
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h1>АРМ механика (контролёр техсостояния)</h1>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      {selected && (
        <div className="card" style={{ borderColor: 'var(--brand)', borderWidth: 2 }}>
          <h2>Техконтроль (Т3): {String(selected.vehicleSnapshot?.brand ?? '')} {selected.vehicleRegNumber}</h2>
          <p style={{ fontSize: 13, color: 'var(--muted)', marginBottom: 10 }}>
            Одометр: {String(selected.vehicleSnapshot?.odometer ?? '—')} км ·
            Техосмотр до: {String(selected.vehicleSnapshot?.techInspectionValidTo ?? '—')}
          </p>
          {CHECK_ITEMS.map(item => (
            <label key={item.key} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, color: 'var(--ink)', marginBottom: 6 }}>
              <input type="checkbox" style={{ width: 'auto' }} checked={checks[item.key] ?? true}
                onChange={e => setChecks({ ...checks, [item.key]: e.target.checked })} />
              {item.label}
            </label>
          ))}
          <div style={{ margin: '10px 0' }}>
            <label>Замечания / дефекты</label>
            <input value={defects} onChange={e => setDefects(e.target.value)} placeholder="например: износ передних шин" />
          </div>
          <button className="btn" onClick={() => decide(true)}>✓ Исправно — выпустить (подписать Т3)</button>
          <button className="btn danger" onClick={() => decide(false)}>✗ Неисправно — отклонить</button>
          <button className="btn secondary" onClick={() => setSelected(null)}>Отмена</button>
        </div>
      )}

      <div className="card">
        <h2>Ожидают технического контроля (Т3) <span className="badge blue">{queue.length}</span></h2>
        <table>
          <thead>
            <tr><th>ТС</th><th>Марка</th><th>Тип ПЛ</th><th>Организация</th><th></th></tr>
          </thead>
          <tbody>
            {queue.map(w => (
              <tr key={w.id}>
                <td>{w.vehicleRegNumber}</td>
                <td>{String(w.vehicleSnapshot?.brand ?? '—')}</td>
                <td>{TYPE_LABELS[w.waybillType] ?? w.waybillType}</td>
                <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                <td><button className="btn secondary" onClick={() => open(w)}>Провести контроль</button></td>
              </tr>
            ))}
            {queue.length === 0 && (
              <tr><td colSpan={5} style={{ color: 'var(--muted)', textAlign: 'center', padding: 20 }}>Заявок нет</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
