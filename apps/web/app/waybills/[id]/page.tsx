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

  return (
    <>
      <div className="toolbar">
        <h1>Путевой лист {w.number ? <span className="number">{w.number}</span> : '(без номера)'}</h1>
        <span className="spacer" />
        {w.number && <a className="btn secondary" href={`/waybills/${id}/print`}>🖨 Печатная форма</a>}
        <span className={`badge ${s.color}`}>{s.label}</span>
      </div>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      <div className="card">
        <h2>Сведения</h2>
        <dl className="kv">
          <dt>Тип</dt><dd>{TYPE_LABELS[w.waybillType] ?? w.waybillType}</dd>
          <dt>Организация</dt><dd>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</dd>
          <dt>Транспортное средство</dt><dd>{String(w.vehicleSnapshot?.brand ?? '')} {w.vehicleRegNumber}</dd>
          <dt>Водитель</dt><dd>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</dd>
          <dt>Маршрут / график</dt><dd>{w.route ?? '—'} / {w.schedule ?? '—'}</dd>
          <dt>Срок действия</dt><dd>{w.validFrom ? new Date(w.validFrom).toLocaleString('ru-RU') : '—'} → {w.validTo ? new Date(w.validTo).toLocaleString('ru-RU') : '—'}</dd>
          <dt>Медосмотр / техконтроль</dt>
          <dd>
            <span className={`badge ${w.medPassed ? 'green' : 'gray'}`}>Т2 {w.medPassed ? '✓' : '…'}</span>{' '}
            <span className={`badge ${w.techPassed ? 'green' : 'gray'}`}>Т3 {w.techPassed ? '✓' : '…'}</span>
          </dd>
          <dt>Одометр (выезд → возврат)</dt><dd>{w.odometerExit ?? '—'} → {w.odometerEntry ?? '—'}</dd>
        </dl>
      </div>

      {w.typeData && Object.keys(w.typeData).length > 0 && (
        <div className="card">
          <h2>Особенности типа</h2>
          <dl className="kv">
            {'serviceKind' in w.typeData && <><dt>Вид услуги</dt><dd>{{ TAXI: 'Такси (фармоишӣ)', ROUTE: 'Маршрут (хатсайр)', HOURLY: 'Почасовой (соатбайъ)' }[String(w.typeData.serviceKind)] ?? String(w.typeData.serviceKind)}</dd></>}
            {'shipmentKind' in w.typeData && <><dt>Вид перевозки</dt><dd>{String(w.typeData.shipmentKind) === 'PIECEWORK' ? 'Сдельная (корбайъ)' : 'Почасовая (соатбайъ)'}</dd></>}
            {Array.isArray(w.typeData.trailers) && (w.typeData.trailers as { registrationNumber: string; brand: string }[]).map((tr, i) => (
              <span key={i} style={{ display: 'contents' }}><dt>Прицеп {i + 1} (ядак)</dt><dd>{tr.registrationNumber} · {tr.brand}</dd></span>
            ))}
            {'permitNumber' in w.typeData && <><dt>Дозвол (E-PERMIT)</dt><dd>{String(w.typeData.permitNumber)}</dd></>}
            {'visaValidTo' in w.typeData && <><dt>Виза</dt><dd>до {String(w.typeData.visaValidTo)} · {String(w.typeData.visaCountry ?? '')}</dd></>}
            {'loadCountry' in w.typeData && <><dt>Маршрут рейса</dt><dd>{String(w.typeData.loadCountry)} → {Array.isArray(w.typeData.transitCountries) && w.typeData.transitCountries.length ? `${(w.typeData.transitCountries as string[]).join(', ')} → ` : ''}{String(w.typeData.unloadCountry)}</dd></>}
            {'cargoName' in w.typeData && <><dt>Груз (номгӯи бор)</dt><dd>{String(w.typeData.cargoName)}</dd></>}
            {'bbaNumber' in w.typeData && <><dt>Книжка ББА/TIR</dt><dd>{String(w.typeData.bbaNumber)}</dd></>}
            {w.secondDriverRma && <><dt>Второй водитель</dt><dd>{String((w.typeData.secondDriverSnapshot as Record<string, unknown>)?.fullName ?? w.secondDriverRma)}</dd></>}
          </dl>
        </div>
      )}

      <div className="card">
        <h2>Действия</h2>
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

      {qrUrl && (
        <div className="card" style={{ textAlign: 'center' }}>
          <h2>QR-код для дорожного контроля</h2>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={qrUrl} alt="QR путевого листа" />
          <p style={{ color: 'var(--muted)', fontSize: 13 }}>
            Инспектор проверяет подпись офлайн — без доступа к сети
          </p>
        </div>
      )}

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
  );
}
