'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { md, wb, TYPE_LABELS } from '@/lib/api';

type Option = { value: string; label: string };

export default function NewWaybillPage() {
  const router = useRouter();
  const [orgs, setOrgs] = useState<Option[]>([]);
  const [vehicles, setVehicles] = useState<Option[]>([]);
  const [drivers, setDrivers] = useState<Option[]>([]);
  const [orgRma, setOrgRma] = useState('');
  const [form, setForm] = useState({
    waybillType: 'WB_BUS',
    vehicleRegNumber: '',
    driverRma: '',
    communicationType: 'URBAN',
    route: '',
    schedule: '',
  });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    md.organizations()
      .then(list => setOrgs(list.map(o => ({ value: String(o.rma), label: `${o.name} (${o.rma})` }))))
      .catch(e => setError(e.message));
  }, []);

  useEffect(() => {
    if (!orgRma) return;
    md.vehicles(orgRma)
      .then(list => setVehicles(list.map(v => ({ value: String(v.registrationNumber), label: `${v.registrationNumber} — ${v.brand ?? ''}` }))))
      .catch(e => setError(e.message));
    md.drivers(orgRma)
      .then(list => setDrivers(list.map(d => ({ value: String(d.rma), label: `${d.fullName} (${d.rma})` }))))
      .catch(e => setError(e.message));
  }, [orgRma]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const created = await wb.post('', { ...form, organizationRma: orgRma });
      router.push(`/waybills/${created.id}`);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Новый путевой лист</h1>
      {error && <div className="error">{error}</div>}
      <div className="card">
        <form className="grid" onSubmit={submit}>
          <div className="full">
            <label>Организация</label>
            <select required value={orgRma} onChange={e => setOrgRma(e.target.value)}>
              <option value="">— выберите организацию —</option>
              {orgs.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
          <div>
            <label>Тип путевого листа</label>
            <select value={form.waybillType} onChange={e => setForm({ ...form, waybillType: e.target.value })}>
              {Object.entries(TYPE_LABELS).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
          <div>
            <label>Вид сообщения</label>
            <select value={form.communicationType} onChange={e => setForm({ ...form, communicationType: e.target.value })}>
              <option value="URBAN">Городское (шаҳрӣ)</option>
              <option value="SUBURBAN">Пригородное (наздишаҳрӣ)</option>
              <option value="INTERCITY">Междугородное (байнишаҳрӣ)</option>
              <option value="INTERNATIONAL">Международное (байналмилалӣ)</option>
            </select>
          </div>
          <div>
            <label>Транспортное средство</label>
            <select required value={form.vehicleRegNumber} onChange={e => setForm({ ...form, vehicleRegNumber: e.target.value })}>
              <option value="">— выберите ТС —</option>
              {vehicles.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
          <div>
            <label>Водитель</label>
            <select required value={form.driverRma} onChange={e => setForm({ ...form, driverRma: e.target.value })}>
              <option value="">— выберите водителя —</option>
              {drivers.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
          <div>
            <label>Маршрут (хатсайр)</label>
            <input value={form.route} onChange={e => setForm({ ...form, route: e.target.value })} placeholder="Маршрут № 3" />
          </div>
          <div>
            <label>График (реҷа)</label>
            <input value={form.schedule} onChange={e => setForm({ ...form, schedule: e.target.value })} placeholder="Реҷаи 1" />
          </div>
          <div className="full">
            <button className="btn" type="submit" disabled={busy}>
              {busy ? 'Создание…' : 'Создать путевой лист'}
            </button>
          </div>
        </form>
      </div>
    </>
  );
}
