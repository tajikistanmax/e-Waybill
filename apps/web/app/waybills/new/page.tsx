'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { md, wb, TYPE_LABELS } from '@/lib/api';

type Option = { value: string; label: string };
type Trailer = { registrationNumber: string; brand: string };

const INTL_TYPES = ['WB_TRUCK_INTL', 'WB_PAX_INTL'];

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
  // Типовая специфика
  const [serviceKind, setServiceKind] = useState('TAXI');           // 3-С
  const [shipmentKind, setShipmentKind] = useState('PIECEWORK');    // 2-Б
  const [trailers, setTrailers] = useState<Trailer[]>([]);          // 2-Б
  const [intl, setIntl] = useState({                                // 5Б-БМ / 4М-БМ
    secondDriverRma: '', visaValidTo: '', visaCountry: '',
    loadCountry: '', unloadCountry: '', transitCountries: '',
    cargoName: '', permitNumber: '', bbaNumber: '',
  });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    md.organizations()
      .then(list => {
        setOrgs(list.map(o => ({ value: String(o.rma), label: `${o.name} (${o.rma})` })));
        if (list.length === 1) setOrgRma(String(list[0].rma));
      })
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

  const t = form.waybillType;
  const isCar = t === 'WB_CAR' || t === 'WB_TAXI';
  const isTruck = t === 'WB_TRUCK';
  const isIntl = INTL_TYPES.includes(t);

  function buildTypeData(): Record<string, unknown> | undefined {
    if (isCar) return { serviceKind };
    if (isTruck) return { shipmentKind, ...(trailers.length ? { trailers } : {}) };
    if (isIntl) {
      return {
        visaValidTo: intl.visaValidTo,
        visaCountry: intl.visaCountry,
        loadCountry: intl.loadCountry,
        unloadCountry: intl.unloadCountry,
        ...(intl.transitCountries ? { transitCountries: intl.transitCountries.split(',').map(s => s.trim()).filter(Boolean) } : {}),
        ...(t === 'WB_TRUCK_INTL' ? { cargoName: intl.cargoName } : {}),
        permitNumber: intl.permitNumber,
        ...(intl.bbaNumber ? { bbaNumber: intl.bbaNumber } : {}),
      };
    }
    return undefined;
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const created = await wb.post('', {
        ...form,
        organizationRma: orgRma,
        communicationType: isIntl ? 'INTERNATIONAL' : form.communicationType,
        ...(isIntl && intl.secondDriverRma ? { secondDriverRma: intl.secondDriverRma } : {}),
        typeData: buildTypeData(),
      });
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
            <select disabled={isIntl} value={isIntl ? 'INTERNATIONAL' : form.communicationType}
              onChange={e => setForm({ ...form, communicationType: e.target.value })}>
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

          {/* --- 3-С: вид услуги --- */}
          {isCar && (
            <div>
              <label>Вид услуги (намуди хизматрасонӣ)</label>
              <select value={serviceKind} onChange={e => setServiceKind(e.target.value)}>
                <option value="TAXI">Такси (фармоишӣ)</option>
                <option value="ROUTE">Маршрут (хатсайр)</option>
                <option value="HOURLY">Почасовой (соатбайъ)</option>
              </select>
            </div>
          )}

          {/* --- 2-Б: вид перевозки и прицепы --- */}
          {isTruck && (
            <>
              <div>
                <label>Вид перевозки</label>
                <select value={shipmentKind} onChange={e => setShipmentKind(e.target.value)}>
                  <option value="PIECEWORK">Сдельная (корбайъ)</option>
                  <option value="HOURLY">Почасовая (соатбайъ)</option>
                </select>
              </div>
              <div className="full">
                <label>Прицепы (ядак) — до 2</label>
                {trailers.map((tr, i) => (
                  <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
                    <input placeholder="Госномер прицепа" required value={tr.registrationNumber}
                      onChange={e => setTrailers(ts => ts.map((x, j) => j === i ? { ...x, registrationNumber: e.target.value } : x))} />
                    <input placeholder="Марка" required value={tr.brand}
                      onChange={e => setTrailers(ts => ts.map((x, j) => j === i ? { ...x, brand: e.target.value } : x))} />
                    <button type="button" className="btn danger" onClick={() => setTrailers(ts => ts.filter((_, j) => j !== i))}>✗</button>
                  </div>
                ))}
                {trailers.length < 2 && (
                  <button type="button" className="btn secondary" onClick={() => setTrailers(ts => [...ts, { registrationNumber: '', brand: '' }])}>
                    + Добавить прицеп
                  </button>
                )}
              </div>
            </>
          )}

          {/* --- Международные: 5Б-БМ / 4М-БМ --- */}
          {isIntl && (
            <>
              <div>
                <label>Второй водитель (для дальних рейсов)</label>
                <select value={intl.secondDriverRma} onChange={e => setIntl({ ...intl, secondDriverRma: e.target.value })}>
                  <option value="">— нет —</option>
                  {drivers.filter(d => d.value !== form.driverRma).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </div>
              <div>
                <label>Номер дозвола (E-PERMIT)</label>
                <input required placeholder="EP-2026-..." value={intl.permitNumber} onChange={e => setIntl({ ...intl, permitNumber: e.target.value })} />
              </div>
              <div>
                <label>Виза действительна до</label>
                <input type="date" required value={intl.visaValidTo} onChange={e => setIntl({ ...intl, visaValidTo: e.target.value })} />
              </div>
              <div>
                <label>Страна выдачи визы</label>
                <input required placeholder="Узбекистан" value={intl.visaCountry} onChange={e => setIntl({ ...intl, visaCountry: e.target.value })} />
              </div>
              <div>
                <label>Страна погрузки / отправления</label>
                <input required placeholder="Таджикистан" value={intl.loadCountry} onChange={e => setIntl({ ...intl, loadCountry: e.target.value })} />
              </div>
              <div>
                <label>Страна разгрузки / назначения</label>
                <input required placeholder="Казахстан" value={intl.unloadCountry} onChange={e => setIntl({ ...intl, unloadCountry: e.target.value })} />
              </div>
              <div>
                <label>Транзитные страны (через запятую)</label>
                <input placeholder="Узбекистан, Кыргызстан" value={intl.transitCountries} onChange={e => setIntl({ ...intl, transitCountries: e.target.value })} />
              </div>
              {t === 'WB_TRUCK_INTL' && (
                <>
                  <div>
                    <label>Наименование груза (номгӯи бор)</label>
                    <input required placeholder="Хлопок" value={intl.cargoName} onChange={e => setIntl({ ...intl, cargoName: e.target.value })} />
                  </div>
                  <div>
                    <label>Книжка ББА / TIR (если есть)</label>
                    <input placeholder="XB 1234567" value={intl.bbaNumber} onChange={e => setIntl({ ...intl, bbaNumber: e.target.value })} />
                  </div>
                </>
              )}
            </>
          )}

          <div>
            <label>Маршрут (хатсайр){isCar && serviceKind === 'ROUTE' ? ' — обязателен' : ''}</label>
            <input required={isCar && serviceKind === 'ROUTE'} value={form.route}
              onChange={e => setForm({ ...form, route: e.target.value })} placeholder="Маршрут № 3 / Душанбе — Алматы" />
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
