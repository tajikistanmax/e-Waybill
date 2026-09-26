'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { md, wb, type Cargo, type Client, type ConsignmentNoteRow, type ConsignmentNotesResponse, type Waybill, type WorkDayRow } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { SearchSelect, type SSOption } from '../../SearchSelect';

/**
 * Борхатҳо (накладные замимаи 1 / 2) листа 2-Б — N на лист, как legacy cargo_waybills (у 20 878 листов
 * старой базы больше одного борхата). Обязательные поля — legacy CargoWaybillRequest: заказчик, отправитель,
 * получатель, груз, количество, масса, расстояние. Из строк сервер собирает P, Z и спецпробег сдельного листа
 * (CargoFuelBase::calcP/calcZ) — при возврате их больше не вводят вручную. Каждый борхат печатается отдельно.
 */
type Props = {
  w: Waybill;
  days: WorkDayRow[];
  overdue: boolean;
  canDispatch: boolean;
  act: (label: string, fn: () => Promise<unknown>) => Promise<void>;
  onChanged: (data: ConsignmentNotesResponse) => void;
};

const EMPTY = {
  kind: '1', noteDate: '', workDayId: '',
  payerId: '', payerName: '', senderId: '', senderName: '', senderAddress: '',
  receiverId: '', receiverName: '', receiverAddress: '', forwarderId: '', forwarderName: '',
  cargoId: '', cargoName: '', cargoNumber: '',
  cargoAmount: '', cargoWeight: '', distance: '', trips: '1', specialDistance: '', invoiceNumber: '',
};
type Form = typeof EMPTY;
type PartyKey = 'payer' | 'sender' | 'receiver' | 'forwarder';

const FREE = 'free:';
const num = (v: string) => (v.trim() === '' ? null : Number(v));
const s = (v: unknown) => (v == null ? '' : String(v));
const fmt = (v: number | null | undefined, d = 2) => (v == null ? '—' : (Math.round(Number(v) * 10 ** d) / 10 ** d).toLocaleString('ru-RU'));

export default function ConsignmentNotes({ w, days, overdue, canDispatch, act, onChanged }: Props) {
  const { t } = useT();
  const id = w.id;
  const [data, setData] = useState<ConsignmentNotesResponse | null>(null);
  const [form, setForm] = useState<Form>(EMPTY);
  const [edit, setEdit] = useState<string | null>(null);
  const [clients, setClients] = useState<Client[]>([]);
  const [cargos, setCargos] = useState<Cargo[]>([]);
  const [printing, setPrinting] = useState('');
  const [error, setError] = useState('');

  const hourly = String((w.typeData ?? {}).shipmentKind) === 'HOURLY';
  const editable = canDispatch
    && (!['COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED', 'BLOCKED'].includes(w.status) || overdue);

  // Последний ответ сервера — для заполнения формы следующего борхата сразу после сохранения
  // (state в замыкании submit ещё старый).
  const dataRef = useRef<ConsignmentNotesResponse | null>(null);
  const load = useCallback(async () => {
    const d = await wb.consignmentNotes(id);
    dataRef.current = d;
    setData(d);
    onChanged(d);
  }, [id, onChanged]);

  useEffect(() => { load().catch(e => setError((e as Error).message)); }, [load]);
  useEffect(() => {
    md.clients().then(setClients).catch(() => { /* справочник недоступен — ввод по имени */ });
    md.cargos().then(setCargos).catch(() => { /* справочник грузов может быть не развёрнут */ });
  }, []);

  // Нет в справочнике — последней строкой предлагаем ввести наименование как есть (снимок без id),
  // чтобы пустой справочник контрагентов организации не блокировал оформление борхата.
  const withFreeText = useCallback((q: string, opts: SSOption[]): SSOption[] => {
    const v = q.trim();
    if (!v || opts.some(o => o.label.toLowerCase() === v.toLowerCase())) return opts;
    return [...opts, { value: `${FREE}${v}`, label: v, sub: t('cnn.freetext') }];
  }, [t]);
  const searchClients = useCallback(async (q: string): Promise<SSOption[]> => {
    const ql = q.trim().toLowerCase();
    return withFreeText(q, clients.filter(c => !ql || c.name.toLowerCase().includes(ql) || String(c.number ?? '').includes(ql))
      .slice(0, 25).map(c => ({ value: c.id, label: c.name, sub: c.address ?? '' })));
  }, [clients, withFreeText]);
  const searchCargos = useCallback(async (q: string): Promise<SSOption[]> => {
    const ql = q.trim().toLowerCase();
    return withFreeText(q, cargos.filter(c => !ql || c.name.toLowerCase().includes(ql) || String(c.number ?? '').includes(ql))
      .slice(0, 25).map(c => ({ value: c.id, label: c.name, sub: c.number != null ? `${t('col.cargonumber')} ${c.number}` : '' })));
  }, [cargos, t, withFreeText]);
  const idOf = (o: SSOption) => (o.value.startsWith(FREE) ? '' : o.value);

  function startEdit(n: ConsignmentNoteRow) {
    setEdit(n.id);
    setForm({
      kind: String(n.kind), noteDate: n.noteDate, workDayId: n.workDayId ?? '',
      payerId: s(n.payerId), payerName: s(n.payerName), senderId: s(n.senderId), senderName: s(n.senderName),
      senderAddress: s(n.senderAddress), receiverId: s(n.receiverId), receiverName: s(n.receiverName),
      receiverAddress: s(n.receiverAddress), forwarderId: s(n.forwarderId), forwarderName: s(n.forwarderName),
      cargoId: s(n.cargoId), cargoName: s(n.cargoName), cargoNumber: s(n.cargoNumber),
      cargoAmount: s(n.cargoAmount), cargoWeight: s(n.cargoWeight), distance: s(n.distance), trips: s(n.trips || 1),
      specialDistance: s(n.specialDistance), invoiceNumber: s(n.invoiceNumber),
    });
  }

  function newForm(): Form {
    // Новый борхат — от предыдущего: те же заказчик, стороны и груз (типичный лист возит один груз
    // одному заказчику много раз), дата — последняя введённая.
    const src = dataRef.current;
    const last = src?.notes[src.notes.length - 1];
    if (!last) return { ...EMPTY, noteDate: w.validFrom ? w.validFrom.slice(0, 10) : '' };
    return {
      ...EMPTY, kind: String(last.kind), noteDate: last.noteDate,
      payerId: s(last.payerId), payerName: s(last.payerName), senderId: s(last.senderId), senderName: s(last.senderName),
      senderAddress: s(last.senderAddress), receiverId: s(last.receiverId), receiverName: s(last.receiverName),
      receiverAddress: s(last.receiverAddress), forwarderId: s(last.forwarderId), forwarderName: s(last.forwarderName),
      cargoId: s(last.cargoId), cargoName: s(last.cargoName), cargoNumber: s(last.cargoNumber), distance: s(last.distance),
    };
  }

  useEffect(() => {
    if (!edit && data && form.noteDate === '') setForm(newForm());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    const kind = Number(form.kind);
    const body = {
      kind, noteDate: form.noteDate, workDayId: form.workDayId || null,
      payerId: form.payerId || null, payerName: form.payerName || null,
      senderId: form.senderId || null, senderName: form.senderName || null, senderAddress: form.senderAddress || null,
      receiverId: form.receiverId || null, receiverName: form.receiverName || null, receiverAddress: form.receiverAddress || null,
      forwarderId: kind === 2 ? form.forwarderId || null : null, forwarderName: kind === 2 ? form.forwarderName || null : null,
      cargoId: form.cargoId || null, cargoName: form.cargoName || null, cargoNumber: num(form.cargoNumber),
      cargoAmount: num(form.cargoAmount), cargoWeight: num(form.cargoWeight), distance: num(form.distance),
      trips: kind === 2 ? 1 : num(form.trips) ?? 1, specialDistance: num(form.specialDistance),
      invoiceNumber: form.invoiceNumber || null,
    };
    const path = `/${id}/consignment-notes`;
    let saved = false;
    await act(edit ? t('cnn.act.saved') : t('cnn.act.added'), async () => {
      await (edit ? wb.put(`${path}/${edit}`, body) : wb.post(path, body));
      await load();
      saved = true;
    });
    if (!saved) return;   // ошибка показана карточкой — введённое не теряем
    setEdit(null);
    setForm(f => ({ ...newForm(), noteDate: f.noteDate }));
  }

  async function print(n: ConsignmentNoteRow) {
    setPrinting(n.id); setError('');
    try {
      const url = URL.createObjectURL(await wb.printNotePdf(id, n.id));
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPrinting('');
    }
  }

  const party = (key: PartyKey, label: string, required: boolean, withAddress: boolean) => {
    const idKey = `${key}Id` as keyof Form;
    const nameKey = `${key}Name` as keyof Form;
    const addrKey = `${key}Address` as keyof Form;
    return (
      <>
        <div>
          <label>{label}{required ? ' *' : ''}</label>
          <SearchSelect value={form[idKey] || form[nameKey]} selectedLabel={form[nameKey]}
            placeholder={`${t('cn.searchph')}: ${label.toLowerCase()}…`} onSearch={searchClients}
            onSelect={o => setForm(f => ({ ...f, [idKey]: idOf(o), [nameKey]: o.label,
              ...(withAddress && !f[addrKey] && o.sub ? { [addrKey]: o.sub } : {}) }))}
            onClear={() => setForm(f => ({ ...f, [idKey]: '', [nameKey]: '' }))}
            loadingText={t('cn.searching')} emptyText={t('cn.notfound')} hintText={t('wbf.clientsearchhint')} />
        </div>
        {withAddress && (
          <div><label>{t('cnn.f.address')}</label>
            <input value={form[addrKey]} onChange={e => setForm(f => ({ ...f, [addrKey]: e.target.value }))} /></div>
        )}
      </>
    );
  };

  const notes = data?.notes ?? [];
  const totals = data?.totals;

  return (
    <div className="card" data-testid="wb-consignment-notes">
      <h2>{t('cnn.h')}</h2>
      <p style={{ color: 'var(--muted)', fontSize: 13, marginTop: -4 }}>
        {hourly ? t('cnn.desc.hourly') : t('cnn.desc')}
      </p>
      {error && <div className="error">{error}</div>}

      {notes.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ marginBottom: 12 }}>
            <thead><tr>
              <th>№</th><th>{t('col.date')}</th><th>{t('cnn.f.kind')}</th><th>{t('cnn.f.payer')}</th>
              <th>{t('cnn.f.route')}</th><th>{t('cn.f.cargo')}</th><th>{t('cnn.f.amount')}</th><th>{t('cnn.f.weight')}</th>
              <th>{t('cnn.f.distance')}</th><th>{t('cnn.f.trips')}</th><th>P, т·км</th><th />
            </tr></thead>
            <tbody>
              {notes.map(n => (
                <tr key={n.id}>
                  <td>{n.number ?? '—'}</td>
                  <td>{n.noteDate}</td>
                  <td>{n.kind === 2 ? t('cnn.kind2') : t('cnn.kind1')}</td>
                  <td>{n.payerName ?? '—'}</td>
                  <td>{n.senderName ?? '—'} → {n.receiverName ?? '—'}{n.kind === 2 && n.forwarderName ? ` · ${n.forwarderName}` : ''}</td>
                  <td>{n.cargoName ?? '—'}</td>
                  <td>{fmt(n.cargoAmount, 3)}</td>
                  <td>{fmt(n.cargoWeight, 3)}</td>
                  <td>{fmt(n.distance)}</td>
                  <td>{n.kind === 2 ? '—' : n.trips}</td>
                  <td>{fmt(Number(n.cargoWeight ?? 0) * Number(n.distance ?? 0) * (n.kind === 2 ? 1 : n.trips))}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button type="button" className="btn secondary" style={{ padding: '3px 8px' }} title={t('cnn.btn.print')}
                      disabled={printing === n.id} onClick={() => void print(n)}>🖨</button>{' '}
                    {editable && (<>
                      <button type="button" className="btn secondary" style={{ padding: '3px 8px' }} title={t('btn.edit')} onClick={() => startEdit(n)}>✎</button>{' '}
                      <button type="button" className="btn secondary" style={{ padding: '3px 8px' }} title={t('btn.delete')}
                        onClick={() => { if (window.confirm(t('cnn.delconfirm'))) void act(t('cnn.act.deleted'), () => wb.del(`/${id}/consignment-notes/${n.id}`).then(load)); }}>×</button>
                    </>)}
                  </td>
                </tr>
              ))}
              {totals && (
                <tr style={{ fontWeight: 700, borderTop: '2px solid var(--line)' }}>
                  <td colSpan={7}>{t('cnn.total')}: {totals.count}</td>
                  <td>{fmt(totals.weight, 3)}</td><td />
                  <td>Z = {fmt(totals.trips, 0)}</td>
                  <td>{fmt(totals.transportWork)}</td><td />
                </tr>
              )}
            </tbody>
          </table>
          {totals && !hourly && (
            <div style={{ fontSize: 13, color: 'var(--muted)', marginBottom: 12 }} data-testid="cnn-totals">
              {t('cnn.calc')}: P = {fmt(totals.transportWork)} т·км, Z = {fmt(totals.trips, 0)}
              {totals.specialDistance > 0 ? `, ${t('cnn.f.special')} = ${fmt(totals.specialDistance)} км` : ''}
            </div>
          )}
        </div>
      )}
      {notes.length === 0 && !editable && <div style={{ color: 'var(--muted)', fontSize: 13 }}>{t('cnn.empty')}</div>}

      {editable && (
        <form className="grid" onSubmit={submit} data-testid="cnn-form">
          <div><label>{t('cnn.f.kind')}</label>
            <select aria-label={t('cnn.f.kind')} value={form.kind} onChange={e => setForm(f => ({ ...f, kind: e.target.value }))}>
              <option value="1">{t('cnn.kind1')}</option><option value="2">{t('cnn.kind2')}</option>
            </select></div>
          <div><label>{t('col.date')} *</label>
            <input type="date" required aria-label={t('col.date')} value={form.noteDate} onChange={e => setForm(f => ({ ...f, noteDate: e.target.value }))} /></div>
          {days.length > 0 && (
            <div><label>{t('cnn.f.day')}</label>
              <select value={form.workDayId} onChange={e => setForm(f => ({ ...f, workDayId: e.target.value }))}>
                <option value="">—</option>
                {days.map(d => <option key={d.id} value={d.id}>{d.workDate}</option>)}
              </select></div>
          )}
          {party('payer', t('cnn.f.payer'), true, false)}
          {party('sender', t('cn.f.sender'), true, true)}
          {party('receiver', t('cn.f.receiver'), true, true)}
          {form.kind === '2' && party('forwarder', t('cn.f.forwarder'), false, false)}
          <div>
            <label>{t('cn.f.cargo')} *</label>
            <SearchSelect value={form.cargoId || form.cargoName} selectedLabel={form.cargoName}
              placeholder={`${t('cn.searchph')}: ${t('cn.f.cargo').toLowerCase()}…`} onSearch={searchCargos}
              onSelect={o => setForm(f => ({ ...f, cargoId: idOf(o), cargoName: o.label,
                cargoNumber: s(cargos.find(c => c.id === o.value)?.number ?? '') }))}
              onClear={() => setForm(f => ({ ...f, cargoId: '', cargoName: '', cargoNumber: '' }))}
              loadingText={t('cn.searching')} emptyText={t('cn.notfound')} hintText={t('wbf.clientsearchhint')} />
          </div>
          <div><label>{t('cnn.f.amount')} *</label>
            <input type="number" required min={0} step="0.001" aria-label={t('cnn.f.amount')} value={form.cargoAmount} onChange={e => setForm(f => ({ ...f, cargoAmount: e.target.value }))} /></div>
          <div><label>{t('cnn.f.weight')} *</label>
            <input type="number" required min={0} max={1000} step="0.001" aria-label={t('cnn.f.weight')} value={form.cargoWeight} onChange={e => setForm(f => ({ ...f, cargoWeight: e.target.value }))} /></div>
          <div><label>{t('cnn.f.distance')} *</label>
            <input type="number" required min={0} max={5000} step="0.01" aria-label={t('cnn.f.distance')} value={form.distance} onChange={e => setForm(f => ({ ...f, distance: e.target.value }))} /></div>
          {form.kind === '1' && (
            <div><label>{t('cnn.f.trips')} *</label>
              <input type="number" required min={1} max={999} step="1" aria-label={t('cnn.f.trips')} value={form.trips} onChange={e => setForm(f => ({ ...f, trips: e.target.value }))} /></div>
          )}
          <div><label>{t('cnn.f.special')}</label>
            <input type="number" min={0} step="0.01" value={form.specialDistance} onChange={e => setForm(f => ({ ...f, specialDistance: e.target.value }))} /></div>
          <div><label>{t('cnn.f.invoice')}</label>
            <input value={form.invoiceNumber} onChange={e => setForm(f => ({ ...f, invoiceNumber: e.target.value }))} /></div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
            <button className="btn" type="submit">{edit ? t('btn.save') : t('cnn.btn.add')}</button>
            {edit && <button type="button" className="btn secondary" onClick={() => { setEdit(null); setForm(newForm()); }}>{t('btn.cancel')}</button>}
          </div>
        </form>
      )}
    </div>
  );
}
