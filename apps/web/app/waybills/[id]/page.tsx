'use client';

import { use, useCallback, useEffect, useState } from 'react';
import { md, wb, Waybill, Title, StatusEvent, Payment, STATUS_LABELS, TYPE_LABELS } from '@/lib/api';
import QRCode from 'qrcode';

type Employees = { doctors: { rma: string; name: string }[]; mechanics: { rma: string; name: string }[]; dispatchers: { rma: string; name: string }[] };

export default function WaybillCard({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const [w, setW] = useState<Waybill | null>(null);
  const [titles, setTitles] = useState<Title[]>([]);
  const [history, setHistory] = useState<StatusEvent[]>([]);
  const [emp, setEmp] = useState<Employees>({ doctors: [], mechanics: [], dispatchers: [] });
  const [qrUrl, setQrUrl] = useState('');
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [odometerEntry, setOdometerEntry] = useState('');
  const [fuelCalc, setFuelCalc] = useState<Record<string, unknown> | null>(null);
  const [workDays, setWorkDays] = useState<Record<string, unknown>[]>([]);
  const [dayForm, setDayForm] = useState({ workDate: '', exitTime: '06:00', entryTime: '', odometerExit: '', odometerEntry: '', laps: '', revenue: '' });
  const [fuelForm, setFuelForm] = useState({ fuelType: '1', fuelGiven: '', remainBeforeExit: '' });
  const [replacement, setReplacement] = useState(''); // РМА нового водителя или госномер нового ТС
  const [candidates, setCandidates] = useState<{ value: string; label: string }[]>([]);
  const [blockReason, setBlockReason] = useState('');
  const [payment, setPayment] = useState<Payment | null>(null);
  const [tab, setTab] = useState('main');

  const reload = useCallback(async () => {
    const data = await wb.get(id);
    setW(data);
    setTitles(await wb.titles(id));
    setHistory(await wb.history(id));
    if (data.number) {
      try {
        const { jws } = await wb.qr(id);
        // QR кодирует URL страницы проверки — камера телефона инспектора открывает её напрямую
        const verifyUrl = `${window.location.origin}/verify/${jws}`;
        setQrUrl(await QRCode.toDataURL(verifyUrl, { width: 240, margin: 1 }));
      } catch { /* QR доступен с READY */ }
    }
    try {
      const wd = await fetch(`/wb-api/api/v1/waybills/${id}/work-days`, { headers: (await import('@/lib/api')).authHeaders() });
      if (wd.ok) setWorkDays(await wd.json());
    } catch { /* work-days могут отсутствовать */ }
    const list = await md.employees(data.organizationRma);
    setEmp({
      doctors: list.filter(e => e.type === 1).map(e => ({ rma: String(e.rma), name: String(e.name) })),
      mechanics: list.filter(e => e.type === 2).map(e => ({ rma: String(e.rma), name: String(e.name) })),
      dispatchers: list.filter(e => e.type === 3).map(e => ({ rma: String(e.rma), name: String(e.name) })),
    });
    // Кандидаты для замены после недопуска/отклонения (корректирующий титул)
    if (data.status === 'MED_REJECTED') {
      const drivers = await md.drivers(data.organizationRma);
      setCandidates(drivers
        .filter(d => String(d.rma) !== data.driverRma)
        .map(d => ({ value: String(d.rma), label: `${String(d.fullName)} (${String(d.rma)})` })));
    } else if (data.status === 'TECH_REJECTED') {
      const vehicles = await md.vehicles(data.organizationRma);
      setCandidates(vehicles
        .filter(v => String(v.registrationNumber) !== data.vehicleRegNumber)
        .map(v => ({ value: String(v.registrationNumber), label: `${String(v.registrationNumber)} · ${String(v.brand ?? '')}` })));
    } else {
      setCandidates([]);
    }
    setReplacement('');
    // Оплата (если статус её требует или она уже была)
    if (data.status === 'AWAITING_PAYMENT' || data.status === 'PAID') {
      try { setPayment(await wb.payment(id)); } catch { setPayment(null); }
    } else {
      setPayment(null);
    }
  }, [id]);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);

  async function act(label: string, fn: () => Promise<unknown>) {
    setError(''); setOk('');
    try {
      await fn();
      setOk(label + ' — выполнено');
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  if (!w) return error ? <div className="error">{error}</div> : <p>Загрузка…</p>;

  const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
  const dispatcher = emp.dispatchers[0]?.rma;
  const doctor = emp.doctors[0]?.rma;
  const mechanic = emp.mechanics[0]?.rma;

  // Данные типа ПЛ (те же, что уже приходят в w.typeData) — используем только их, ничего не выдумываем
  const td: Record<string, unknown> = w.typeData ?? {};
  const intlKeys = ['loadCountry', 'unloadCountry', 'transitCountries', 'permitNumber', 'visaValidTo', 'cargoName', 'bbaNumber'];
  const isIntl = intlKeys.some(k => k in td);
  const hasServiceInfo = 'serviceKind' in td || 'shipmentKind' in td;
  const showRoute = isIntl || hasServiceInfo || !!w.route || !!w.schedule;
  const trailers = Array.isArray(td.trailers) ? (td.trailers as { registrationNumber: string; brand: string }[]) : [];
  const titlesWithData = titles.filter(t => t.data && Object.keys(t.data).length > 0);
  const mileage = (w.odometerExit != null && w.odometerEntry != null) ? w.odometerEntry - w.odometerExit : null;

  const vehicleName = String(w.vehicleSnapshot?.brand ?? '');
  const driverName = String(w.driverSnapshot?.fullName ?? w.driverRma);
  const orgName = String(w.organizationSnapshot?.name ?? w.organizationRma);
  const validFrom = w.validFrom ? new Date(w.validFrom).toLocaleString('ru-RU') : '—';
  const validTo = w.validTo ? new Date(w.validTo).toLocaleString('ru-RU') : '—';

  const sumLabel: React.CSSProperties = { fontSize: 11.5, color: 'var(--muted)', fontWeight: 500, textTransform: 'uppercase', letterSpacing: '.03em' };
  const sumValue: React.CSSProperties = { marginTop: 6, fontSize: 14, color: 'var(--ink)', fontWeight: 600 };

  const tabs: { key: string; label: string }[] = [
    { key: 'main', label: 'Основное' },
    { key: 'driver', label: 'Водитель' },
    { key: 'vehicle', label: 'Транспорт' },
    ...(showRoute ? [{ key: 'route', label: 'Маршрут' }] : []),
    { key: 'fuel', label: 'Топливо' },
    { key: 'inspections', label: 'Осмотры' },
    { key: 'qr', label: 'QR' },
    { key: 'history', label: 'История' },
  ];

  // Плашка госномера (класс .plate из дизайн-системы)
  const plate = w.vehicleRegNumber
    ? <span className="plate"><span className="p-main">{w.vehicleRegNumber}</span></span>
    : null;

  return (
    <>
      {/* Шапка документа */}
      <div className="toolbar">
        <h1 style={{ marginBottom: 0 }}>
          Путевой лист {w.number ? <span className="number">{w.number}</span> : '(без номера)'}
        </h1>
        <span style={{ color: 'var(--muted)', fontSize: 13 }}>{TYPE_LABELS[w.waybillType] ?? w.waybillType}</span>
        <span className={`badge ${s.color}`}>{s.label}</span>
        <span className="spacer" />
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
          {w.number && <a className="btn secondary" href={`/waybills/${id}/print`}>🖨 Печатная форма</a>}
          {w.status === 'DRAFT' && (
            <button className="btn" disabled={!dispatcher}
              onClick={() => act('Т1 подписан', () => wb.post(`/${id}/titles/t1`, { dispatcherRma: dispatcher }))}>
              Подписать Т1 (диспетчер {emp.dispatchers[0]?.name ?? '—'})
            </button>
          )}
          {w.status === 'CREATED' && !w.medPassed && (
            <button className="btn" disabled={!doctor}
              onClick={() => act('Медосмотр пройден', () => wb.post(`/${id}/confirm-med`, {
                employeeRma: doctor, passed: true,
                indicators: { pressure: '120/80', pulse: 72, temperature: 36.6, alcotest: 0 },
              }))}>
              Т2 — медосмотр (врач {emp.doctors[0]?.name ?? '—'})
            </button>
          )}
          {w.status === 'CREATED' && !w.techPassed && (
            <button className="btn" disabled={!mechanic}
              onClick={() => act('Техконтроль пройден', () => wb.post(`/${id}/confirm-tech`, {
                employeeRma: mechanic, passed: true,
                checklist: { brakes: 'OK', steering: 'OK', lights: 'OK' },
              }))}>
              Т3 — техконтроль (механик {emp.mechanics[0]?.name ?? '—'})
            </button>
          )}
          {w.status === 'AWAITING_PAYMENT' && (
            <span>
              {payment && (
                <span className="badge amber" style={{ marginRight: 8 }}>
                  К оплате: {payment.amount} {payment.currency}
                </span>
              )}
              <button className="btn"
                onClick={() => act('Оплата подтверждена', () => wb.post(`/${id}/confirm-payment`, { method: 'BANK' }))}>
                Подтвердить оплату (бухгалтер)
              </button>
            </span>
          )}
          {w.status === 'READY' && (
            <button className="btn" onClick={() => act('Выдан водителю', () => wb.post(`/${id}/issue`, { driverConfirmation: 'PIN' }))}>
              Выдать водителю
            </button>
          )}
          {w.status === 'ISSUED' && (
            <button className="btn" disabled={!dispatcher}
              onClick={() => act('Выезд на линию', () => wb.post(`/${id}/activate`, { dispatcherRma: dispatcher }))}>
              Т4 — выезд на линию
            </button>
          )}
          {w.status === 'ACTIVE' && (
            <span>
              <input style={{ width: 180, marginRight: 8, display: 'inline-block' }} placeholder="Одометр возврата"
                value={odometerEntry} onChange={e => setOdometerEntry(e.target.value)} />
              <button className="btn" disabled={!dispatcher || !odometerEntry}
                onClick={() => act('Возвращение', () => wb.post(`/${id}/return`, { dispatcherRma: dispatcher, odometerEntry: Number(odometerEntry) }))}>
                Т5 — возвращение
              </button>
            </span>
          )}
          {w.status === 'RETURNED' && (
            <>
              <button className="btn secondary" disabled={!doctor}
                onClick={() => act('Т6 подписан', () => wb.post(`/${id}/confirm-med`, {
                  employeeRma: doctor, passed: true, indicators: { pressure: '125/82', pulse: 74, alcotest: 0 },
                }))}>
                Т6 — послерейсовый медосмотр
              </button>
              <button className="btn" onClick={() => act('Закрыт', () => wb.post(`/${id}/close`, { actor: dispatcher }))}>
                Закрыть путевой лист
              </button>
            </>
          )}
          {(w.status === 'MED_REJECTED' || w.status === 'TECH_REJECTED') && (
            <span>
              <select style={{ width: 300, marginRight: 8, display: 'inline-block' }}
                value={replacement} onChange={e => setReplacement(e.target.value)}>
                <option value="">{w.status === 'MED_REJECTED' ? '— новый водитель —' : '— новое ТС —'}</option>
                {candidates.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
              </select>
              <button className="btn" disabled={!dispatcher || !replacement}
                onClick={() => act(
                  w.status === 'MED_REJECTED' ? 'Водитель заменён — на повторный осмотр' : 'ТС заменено — на повторный контроль',
                  () => wb.post(`/${id}/${w.status === 'MED_REJECTED' ? 'replace-driver' : 'replace-vehicle'}`,
                    w.status === 'MED_REJECTED'
                      ? { newDriverRma: replacement, dispatcherRma: dispatcher }
                      : { newVehicleRegNumber: replacement, dispatcherRma: dispatcher }))}>
                {w.status === 'MED_REJECTED' ? 'Заменить водителя (титул CORRECTION)' : 'Заменить ТС (титул CORRECTION)'}
              </button>
            </span>
          )}
          {w.status === 'ACTIVE' && (
            <span>
              <input style={{ width: 260, marginRight: 8, display: 'inline-block' }} placeholder="Причина блокировки (нарушение)"
                value={blockReason} onChange={e => setBlockReason(e.target.value)} />
              <button className="btn danger" disabled={!blockReason}
                onClick={() => act('Заблокирован инспектором', () => wb.post(`/${id}/block`, { reason: blockReason }))}>
                Блокировать (инспектор)
              </button>
            </span>
          )}
          {w.status === 'BLOCKED' && (
            <span>
              <input style={{ width: 260, marginRight: 8, display: 'inline-block' }} placeholder="Обоснование разблокировки"
                value={blockReason} onChange={e => setBlockReason(e.target.value)} />
              <button className="btn" disabled={!blockReason}
                onClick={() => act('Разблокирован', () => wb.post(`/${id}/unblock`, { reason: blockReason }))}>
                Разблокировать (админ Минтранса)
              </button>
            </span>
          )}
          {!['COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED'].includes(w.status) && (
            <button className="btn danger"
              onClick={() => act('Аннулирован', () => wb.post(`/${id}/cancel`, { reason: 'Отмена диспетчером', actor: dispatcher ?? 'dispatcher' }))}>
              Аннулировать
            </button>
          )}
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      {/* Сводная карточка */}
      <div className="card">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 20, alignItems: 'start' }}>
          <div>
            <div style={sumLabel}>Транспортное средство</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 6, flexWrap: 'wrap' }}>
              {plate}
              {vehicleName && <span style={{ fontWeight: 600 }}>{vehicleName}</span>}
            </div>
          </div>
          <div>
            <div style={sumLabel}>Водитель</div>
            <div style={sumValue}>{driverName}</div>
          </div>
          <div>
            <div style={sumLabel}>Организация</div>
            <div style={sumValue}>{orgName}</div>
          </div>
          <div>
            <div style={sumLabel}>Срок действия</div>
            <div style={{ ...sumValue, fontWeight: 500, fontSize: 13 }}>{validFrom} → {validTo}</div>
          </div>
          <div>
            <div style={sumLabel}>Отметки</div>
            <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <span className={`badge ${w.medPassed ? 'green' : 'gray'}`}>Т2 {w.medPassed ? '✓' : '…'}</span>
              <span className={`badge ${w.techPassed ? 'green' : 'gray'}`}>Т3 {w.techPassed ? '✓' : '…'}</span>
            </div>
          </div>
        </div>
      </div>

      {/* Таб-бар */}
      <div style={{ display: 'flex', gap: 2, borderBottom: '1px solid var(--line)', marginBottom: 18, flexWrap: 'wrap' }}>
        {tabs.map(t => (
          <button key={t.key} type="button" onClick={() => setTab(t.key)}
            style={{
              background: 'none', border: 'none', cursor: 'pointer', outline: 'none',
              padding: '10px 15px', fontSize: 13.5, fontWeight: 600, fontFamily: 'var(--sans)',
              color: tab === t.key ? 'var(--blue-600)' : 'var(--muted)',
              borderBottom: tab === t.key ? '2px solid var(--blue-600)' : '2px solid transparent',
              marginBottom: -1,
            }}>
            {t.label}
          </button>
        ))}
      </div>

      {/* Основное */}
      {tab === 'main' && (
        <div className="card">
          <h2>Общая информация</h2>
          <dl className="kv">
            <dt>Номер ПЛ</dt><dd>{w.number ?? '—'}</dd>
            <dt>Тип</dt><dd>{TYPE_LABELS[w.waybillType] ?? w.waybillType}</dd>
            <dt>Статус</dt><dd><span className={`badge ${s.color}`}>{s.label}</span></dd>
            <dt>Дата создания</dt><dd>{w.createdAt ? new Date(w.createdAt).toLocaleString('ru-RU') : '—'}</dd>
            <dt>Срок действия</dt><dd>{validFrom} → {validTo}</dd>
            <dt>Организация</dt><dd>{orgName}</dd>
            <dt>Маршрут / график</dt><dd>{w.route ?? '—'} / {w.schedule ?? '—'}</dd>
            {w.specialMark && <><dt>Особая отметка</dt><dd>{w.specialMark}</dd></>}
          </dl>
        </div>
      )}

      {/* Водитель */}
      {tab === 'driver' && (
        <div className="card">
          <h2>Водитель</h2>
          <dl className="kv">
            <dt>ФИО</dt><dd>{driverName}</dd>
            <dt>РМА</dt><dd>{w.driverRma}</dd>
          </dl>
          {w.secondDriverRma && (
            <>
              <h2 style={{ marginTop: 18 }}>Второй водитель</h2>
              <dl className="kv">
                <dt>ФИО</dt><dd>{String((td.secondDriverSnapshot as Record<string, unknown>)?.fullName ?? w.secondDriverRma)}</dd>
                <dt>РМА</dt><dd>{w.secondDriverRma}</dd>
              </dl>
            </>
          )}
        </div>
      )}

      {/* Транспорт */}
      {tab === 'vehicle' && (
        <div className="card">
          <h2>Транспортное средство</h2>
          {plate && <div style={{ marginBottom: 14 }}>{plate}</div>}
          <dl className="kv">
            <dt>Госномер</dt><dd>{w.vehicleRegNumber || '—'}</dd>
            <dt>Марка / модель</dt><dd>{vehicleName || '—'}</dd>
            {trailers.map((tr, i) => (
              <span key={i} style={{ display: 'contents' }}><dt>Прицеп {i + 1} (ядак)</dt><dd>{tr.registrationNumber} · {tr.brand}</dd></span>
            ))}
          </dl>
        </div>
      )}

      {/* Маршрут (маршрут/сообщение + международные поля) */}
      {tab === 'route' && showRoute && (
        <div className="card">
          <h2>Маршрут и сообщение</h2>
          <dl className="kv">
            <dt>Маршрут</dt><dd>{w.route ?? '—'}</dd>
            <dt>График</dt><dd>{w.schedule ?? '—'}</dd>
            {'serviceKind' in td && <><dt>Вид услуги</dt><dd>{{ TAXI: 'Такси (фармоишӣ)', ROUTE: 'Маршрут (хатсайр)', HOURLY: 'Почасовой (соатбайъ)' }[String(td.serviceKind)] ?? String(td.serviceKind)}</dd></>}
            {'shipmentKind' in td && <><dt>Вид перевозки</dt><dd>{String(td.shipmentKind) === 'PIECEWORK' ? 'Сдельная (корбайъ)' : 'Почасовая (соатбайъ)'}</dd></>}
            {'permitNumber' in td && <><dt>Дозвол (E-PERMIT)</dt><dd>{String(td.permitNumber)}</dd></>}
            {'visaValidTo' in td && <><dt>Виза</dt><dd>до {String(td.visaValidTo)} · {String(td.visaCountry ?? '')}</dd></>}
            {'loadCountry' in td && <><dt>Маршрут рейса</dt><dd>{String(td.loadCountry)} → {Array.isArray(td.transitCountries) && td.transitCountries.length ? `${(td.transitCountries as string[]).join(', ')} → ` : ''}{String(td.unloadCountry)}</dd></>}
            {'cargoName' in td && <><dt>Груз (номгӯи бор)</dt><dd>{String(td.cargoName)}</dd></>}
            {'bbaNumber' in td && <><dt>Книжка ББА/TIR</dt><dd>{String(td.bbaNumber)}</dd></>}
          </dl>
        </div>
      )}

      {/* Топливо: одометр/пробег + рабочие дни + нормирование топлива */}
      {tab === 'fuel' && (
        <>
          <div className="card">
            <h2>Одометр и пробег</h2>
            <dl className="kv">
              <dt>Одометр (выезд → возврат)</dt><dd>{w.odometerExit ?? '—'} → {w.odometerEntry ?? '—'}</dd>
              <dt>Пробег</dt><dd>{mileage != null ? `${mileage} км` : '—'}</dd>
            </dl>
          </div>

          {(w.status === 'ACTIVE' || workDays.length > 0) && (
            <div className="card">
              <h2>Рабочие дни (многодневный ПЛ)</h2>
              {workDays.length > 0 && (
                <table style={{ marginBottom: 12 }}>
                  <thead><tr><th>Дата</th><th>Выезд</th><th>Возврат</th><th>Одометр</th><th>Круги</th><th>Выручка</th></tr></thead>
                  <tbody>
                    {workDays.map((d, i) => (
                      <tr key={String(d.id ?? i)}>
                        <td>{String(d.workDate)}</td>
                        <td>{String(d.exitTime ?? '—')}</td>
                        <td>{String(d.entryTime ?? '—')}</td>
                        <td>{String(d.odometerExit ?? '—')} → {String(d.odometerEntry ?? '—')}</td>
                        <td>{String(d.laps ?? '—')}</td>
                        <td>{String(d.revenue ?? '—')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {w.status === 'ACTIVE' && (
                <>
                  <form className="grid" onSubmit={async e => {
                    e.preventDefault();
                    await act('Рабочий день добавлен', () => wb.post(`/${id}/work-days`, {
                      workDate: dayForm.workDate,
                      exitTime: dayForm.exitTime || null,
                      entryTime: dayForm.entryTime || null,
                      odometerExit: dayForm.odometerExit ? Number(dayForm.odometerExit) : null,
                      odometerEntry: dayForm.odometerEntry ? Number(dayForm.odometerEntry) : null,
                      laps: dayForm.laps ? Number(dayForm.laps) : null,
                      revenue: dayForm.revenue ? Number(dayForm.revenue) : null,
                    }));
                  }}>
                    <div><label>Дата</label><input type="date" required value={dayForm.workDate} onChange={e => setDayForm({ ...dayForm, workDate: e.target.value })} /></div>
                    <div><label>Выезд / возврат</label>
                      <span style={{ display: 'flex', gap: 6 }}>
                        <input type="time" value={dayForm.exitTime} onChange={e => setDayForm({ ...dayForm, exitTime: e.target.value })} />
                        <input type="time" value={dayForm.entryTime} onChange={e => setDayForm({ ...dayForm, entryTime: e.target.value })} />
                      </span>
                    </div>
                    <div><label>Одометр выезд / возврат</label>
                      <span style={{ display: 'flex', gap: 6 }}>
                        <input type="number" value={dayForm.odometerExit} onChange={e => setDayForm({ ...dayForm, odometerExit: e.target.value })} />
                        <input type="number" value={dayForm.odometerEntry} onChange={e => setDayForm({ ...dayForm, odometerEntry: e.target.value })} />
                      </span>
                    </div>
                    <div><label>Круги / выручка</label>
                      <span style={{ display: 'flex', gap: 6 }}>
                        <input type="number" value={dayForm.laps} onChange={e => setDayForm({ ...dayForm, laps: e.target.value })} />
                        <input type="number" step="0.01" value={dayForm.revenue} onChange={e => setDayForm({ ...dayForm, revenue: e.target.value })} />
                      </span>
                    </div>
                    <div className="full"><button className="btn secondary" type="submit">+ Добавить день</button></div>
                  </form>
                  <form className="grid" style={{ marginTop: 10 }} onSubmit={async e => {
                    e.preventDefault();
                    await act('Топливо записано', () => wb.post(`/${id}/fuel`, {
                      fuelType: Number(fuelForm.fuelType),
                      fuelGiven: fuelForm.fuelGiven ? Number(fuelForm.fuelGiven) : null,
                      remainBeforeExit: fuelForm.remainBeforeExit ? Number(fuelForm.remainBeforeExit) : null,
                    }));
                  }}>
                    <div><label>Вид топлива</label>
                      <select value={fuelForm.fuelType} onChange={e => setFuelForm({ ...fuelForm, fuelType: e.target.value })}>
                        <option value="1">Бензин</option><option value="2">Дизель</option>
                        <option value="3">Газ сжиженный</option><option value="4">Газ природный</option>
                      </select>
                    </div>
                    <div><label>Выдано, л</label><input type="number" step="0.1" required value={fuelForm.fuelGiven} onChange={e => setFuelForm({ ...fuelForm, fuelGiven: e.target.value })} /></div>
                    <div><label>Остаток перед выездом, л</label><input type="number" step="0.1" value={fuelForm.remainBeforeExit} onChange={e => setFuelForm({ ...fuelForm, remainBeforeExit: e.target.value })} /></div>
                    <div className="full"><button className="btn secondary" type="submit">+ Записать топливо</button></div>
                  </form>
                </>
              )}
            </div>
          )}

          {(w.status === 'RETURNED' || w.status === 'COMPLETED') && (
            <div className="card">
              <h2>Нормирование топлива</h2>
              {!fuelCalc ? (
                <button className="btn secondary" onClick={async () => {
                  try {
                    const { authHeaders } = await import('@/lib/api');
                    const r = await fetch(`/wb-api/api/v1/waybills/${id}/fuel-calculation`, { headers: authHeaders() });
                    if (!r.ok) {
                      const p = await r.json().catch(() => null);
                      throw new Error(p?.detail ?? `Ошибка ${r.status}`);
                    }
                    setFuelCalc(await r.json());
                  } catch (e) {
                    setError((e as Error).message);
                  }
                }}>
                  Рассчитать норму расхода
                </button>
              ) : (
                <dl className="kv">
                  <dt>Пробег</dt><dd>{String(fuelCalc.km)} км</dd>
                  <dt>Базовая норма</dt><dd>{String(fuelCalc.baseNormPer100km)} л/100км</dd>
                  <dt>Коэффициенты</dt>
                  <dd>{Array.isArray(fuelCalc.coefficientsApplied) && fuelCalc.coefficientsApplied.length
                    ? (fuelCalc.coefficientsApplied as Record<string, unknown>[]).map(c => `${c.name} ×${c.value}`).join(', ')
                    : 'не применялись'}</dd>
                  <dt>Норма</dt><dd><b>{String(fuelCalc.normLiters)} л</b></dd>
                  <dt>Факт (выдано)</dt><dd>{String(fuelCalc.factLiters)} л</dd>
                  <dt>Отклонение</dt>
                  <dd style={{ color: Number(fuelCalc.deviationLiters) > 0 ? 'var(--accent)' : 'var(--brand)' }}>
                    {Number(fuelCalc.deviationLiters) > 0 ? '+' : ''}{String(fuelCalc.deviationLiters)} л
                  </dd>
                  {fuelCalc.tripCost != null && <><dt>Стоимость рейса (нархнома)</dt><dd>{String(fuelCalc.tripCost)} сомони ({String(fuelCalc.tariffPerKm)} сомони/км)</dd></>}
                </dl>
              )}
            </div>
          )}
        </>
      )}

      {/* Осмотры: Т2 медосмотр и Т3 техконтроль */}
      {tab === 'inspections' && (
        <div className="card">
          <h2>Осмотры и допуски</h2>
          <dl className="kv">
            <dt>Т2 — предрейсовый медосмотр</dt>
            <dd><span className={`badge ${w.medPassed ? 'green' : 'gray'}`}>{w.medPassed ? 'Пройден ✓' : 'Ожидается'}</span></dd>
            <dt>Т3 — технический контроль</dt>
            <dd><span className={`badge ${w.techPassed ? 'green' : 'gray'}`}>{w.techPassed ? 'Пройден ✓' : 'Ожидается'}</span></dd>
          </dl>
          {titlesWithData.length > 0 && (
            <>
              <h2 style={{ marginTop: 18 }}>Показатели</h2>
              {titlesWithData.map(t => (
                <div key={t.id} style={{ marginTop: 12 }}>
                  <div style={{ fontWeight: 600, marginBottom: 6 }}>{t.titleType} · {t.signerRole} ({t.signerRma})</div>
                  <dl className="kv">
                    {Object.entries(t.data ?? {}).map(([k, v]) => (
                      <span key={k} style={{ display: 'contents' }}>
                        <dt>{k}</dt><dd>{v !== null && typeof v === 'object' ? JSON.stringify(v) : String(v)}</dd>
                      </span>
                    ))}
                  </dl>
                </div>
              ))}
            </>
          )}
        </div>
      )}

      {/* QR */}
      {tab === 'qr' && (
        <div className="card" style={{ textAlign: 'center' }}>
          <h2>QR-код для дорожного контроля</h2>
          {qrUrl ? (
            <>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={qrUrl} alt="QR путевого листа" />
              <p style={{ color: 'var(--muted)', fontSize: 13 }}>
                Инспектор проверяет подпись офлайн — без доступа к сети
              </p>
            </>
          ) : (
            <p style={{ color: 'var(--muted)', fontSize: 13 }}>
              QR-код станет доступен после присвоения номера и готовности путевого листа
            </p>
          )}
        </div>
      )}

      {/* История: титулы Т1–Т6 + история статусов */}
      {tab === 'history' && (
        <>
          <div className="card">
            <h2>Титулы</h2>
            <ul className="timeline">
              {titles.map(t => (
                <li key={t.id}>
                  <b>{t.titleType}</b> — {t.signerRole} ({t.signerRma})
                  <div className="when">{new Date(t.signedAt).toLocaleString('ru-RU')}</div>
                </li>
              ))}
              {titles.length === 0 && <li>Титулы ещё не подписаны</li>}
            </ul>
          </div>

          <div className="card">
            <h2>История статусов</h2>
            <ul className="timeline">
              {history.map((h, i) => (
                <li key={i}>
                  {h.fromStatus ? `${STATUS_LABELS[h.fromStatus]?.label ?? h.fromStatus} → ` : ''}
                  <b>{STATUS_LABELS[h.toStatus]?.label ?? h.toStatus}</b>
                  {h.reason ? ` — ${h.reason}` : ''}
                  <div className="when">{new Date(h.createdAt).toLocaleString('ru-RU')} · {h.actor}</div>
                </li>
              ))}
            </ul>
          </div>
        </>
      )}
    </>
  );
}
