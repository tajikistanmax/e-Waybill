'use client';

import { useCallback, useEffect, useState } from 'react';
import { md, wb, type Client, type Cargo } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { SearchSelect, type SSOption } from '../../SearchSelect';

function str(v: unknown): string { return v == null ? '' : String(v); }

/**
 * Накладная (приложение к 2-Б / CMR к 5Б-БМ): стороны и груз.
 *
 * Убраны 24.09.2026 по анализу боевой базы старой платформы (spec/АНАЛИЗ-полной-базы-…):
 * таблица операций погрузки-разгрузки (в старом бланке эти графы заполняли от руки — печать
 * выводит две строки «боркунӣ/борфарорӣ» по умолчанию), число рейсов CMR (дубль «Ездок Z» при
 * возврате), таможенник и дата подтверждения (их ставит таможня в своём кабинете — ручной ввод
 * позволял диспетчеру «подтвердить за таможню»). Сервер пустые значения не записывает, поэтому
 * сохранение накладной ранее внесённые данные этих граф не стирает.
 * Печатные формы — /print-attachment.pdf (2-Б) и /print-cmr.pdf (5Б-БМ), см.
 * spec/notes/04-gap-анализ-эталон-vs-платформа.md §7.4.
 *
 * Стороны (отправитель/получатель/экспедитор) и груз выбираются из справочников Client/Cargo
 * (master-data-service) вместо свободного текста; выбранное ИМЯ снимается (snapshot) в те же
 * *Name поля typeData, что читает печатная форма, а id стороны/груза сохраняется ДОПОЛНИТЕЛЬНО
 * только для прослеживаемости/отчётности — без живого джойна при печати (иммутабельность истории).
 */
export function Consignment({
  waybillId, waybillType, typeData, onSaved,
}: {
  waybillId: string;
  waybillType: string;
  typeData: Record<string, unknown>;
  onSaved: () => void;
}) {
  const { roles } = useAuth();
  const { t } = useT();
  const canEdit = ['DISPATCHER', 'COMPANY_ADMIN', 'SYSTEM_ADMIN'].some(r => roles.includes(r));
  const isCmr = waybillType === 'WB_TRUCK_INTL';
  const isHourly = String(typeData.shipmentKind) === 'HOURLY'; // 2-Б, приложение 2 — с экспедитором

  const [form, setForm] = useState({
    senderName: str(typeData.senderName ?? typeData.consignorName),
    senderAddress: str(typeData.senderAddress),
    receiverName: str(typeData.receiverName),
    receiverAddress: str(typeData.receiverAddress),
    forwarderName: str(typeData.forwarderName),
    cargoName: str(typeData.cargoName),
    cargoVolume: str(typeData.cargoVolume),
    cargoStatCode: str(typeData.cargoStatCode),
    submittedDocuments: str(typeData.submittedDocuments),
  });
  // id сторон/груза из справочников Client/Cargo — только для прослеживаемости (не живой джойн).
  const [senderId, setSenderId] = useState(str(typeData.senderId));
  const [receiverId, setReceiverId] = useState(str(typeData.receiverId));
  const [forwarderId, setForwarderId] = useState(str(typeData.forwarderId));
  const [cargoId, setCargoId] = useState(str(typeData.cargoId));
  const [clients, setClients] = useState<Client[]>([]);
  const [cargos, setCargos] = useState<Cargo[]>([]);
  const [busy, setBusy] = useState(false);
  const [printing, setPrinting] = useState(false);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  useEffect(() => {
    md.clients().then(setClients).catch(() => { /* справочник недоступен — поля останутся пустыми */ });
    md.cargos().then(setCargos).catch(() => { /* новый справочник может быть ещё не развёрнут */ });
  }, []);

  // Поиск по уже загруженным справочникам (короткие, org-скоуп на бэкенде) — без отдельного
  // серверного полнотекстового эндпоинта, тот же приём, что и «Заказчик» в мастере создания.
  const searchClients = useCallback(async (q: string): Promise<SSOption[]> => {
    const ql = q.trim().toLowerCase();
    return clients.filter(c => !ql || c.name.toLowerCase().includes(ql))
      .slice(0, 25).map(c => ({ value: c.id, label: c.name, sub: c.address ?? '' }));
  }, [clients]);
  const searchCargos = useCallback(async (q: string): Promise<SSOption[]> => {
    const ql = q.trim().toLowerCase();
    return cargos.filter(c => !ql || c.name.toLowerCase().includes(ql) || String(c.number ?? '').includes(ql))
      .slice(0, 25).map(c => ({ value: c.id, label: c.name, sub: c.number != null ? `${t('col.cargonumber')} ${c.number}` : '' }));
  }, [cargos, t]);

  async function save() {
    setBusy(true); setError(''); setOk('');
    try {
      await wb.post(`/${waybillId}/consignment`, {
        senderName: form.senderName || null,
        senderAddress: form.senderAddress || null,
        receiverName: form.receiverName || null,
        receiverAddress: form.receiverAddress || null,
        forwarderName: form.forwarderName || null,
        cargoName: form.cargoName || null,
        cargoVolume: form.cargoVolume ? Number(form.cargoVolume) : null,
        cargoStatCode: form.cargoStatCode || null,
        submittedDocuments: form.submittedDocuments || null,
        senderId: senderId || null,
        receiverId: receiverId || null,
        forwarderId: forwarderId || null,
        cargoId: cargoId || null,
        // Рамзи бор — снимок сквозного номера груза из справочника (печать борхата, 2.25);
        // при отсутствии справочника сохраняем ранее снятый номер из typeData.
        cargoNumber: cargoId
          ? ((cargos.find(c => c.id === cargoId)?.number as number | undefined)
            ?? (typeData.cargoNumber != null && typeData.cargoNumber !== '' ? Number(typeData.cargoNumber) : null))
          : null,
      });
      setOk(t('cn.saved'));
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function print(kind: 'attachment' | 'cmr') {
    setPrinting(true); setError('');
    try {
      const blob = kind === 'cmr' ? await wb.printCmrPdf(waybillId) : await wb.printAttachmentPdf(waybillId);
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPrinting(false);
    }
  }

  const field = (label: string, key: keyof typeof form, opts?: { type?: string }) => (
    <div>
      <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{label}</label>
      <input
        type={opts?.type ?? 'text'}
        value={form[key]}
        disabled={!canEdit}
        onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
        style={{ width: '100%' }}
      />
    </div>
  );

  // Пикер стороны/груза из справочника: выбор снимает ИМЯ в *Name (печать), id — отдельно
  // (прослеживаемость). Очистка сбрасывает и id, но оставляет снятое имя как было — снимок
  // не должен пропадать только потому, что запись в справочнике переименовали/удалили позже.
  const dictField = (
    label: string, nameKey: 'senderName' | 'receiverName' | 'forwarderName' | 'cargoName',
    idValue: string, setId: (v: string) => void, onSearch: (q: string) => Promise<SSOption[]>,
  ) => (
    <div>
      <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{label}</label>
      {canEdit ? (
        <SearchSelect
          // Чип показываем и для старых записей без id (свободный текст, сохранённый до этой
          // формы) — иначе снятое ранее имя выглядело бы пропавшим, хотя оно цело в form/typeData.
          value={idValue || form[nameKey]}
          selectedLabel={form[nameKey] || ''}
          placeholder={`${t('cn.searchph')}: ${label.toLowerCase()}…`}
          onSearch={onSearch}
          onSelect={o => { setId(o.value); setForm(f => ({ ...f, [nameKey]: o.label })); }}
          onClear={() => { setId(''); setForm(f => ({ ...f, [nameKey]: '' })); }}
          loadingText={t('cn.searching')} emptyText={t('cn.notfound')} hintText={t('wbf.clientsearchhint')} />
      ) : (
        <input value={form[nameKey]} disabled style={{ width: '100%' }} />
      )}
    </div>
  );

  return (
    <div className="card">
      <h2>{t('cn.h')}</h2>
      <p style={{ color: 'var(--muted)', fontSize: 13, marginTop: -4 }}>
        {isCmr ? t('cn.desc.cmr') : t('cn.desc.attach')}
      </p>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12, marginTop: 10 }}>
        {dictField(t('cn.f.sender'), 'senderName', senderId, setSenderId, searchClients)}
        {field(t('cn.f.senderaddr'), 'senderAddress')}
        {dictField(t('cn.f.receiver'), 'receiverName', receiverId, setReceiverId, searchClients)}
        {field(t('cn.f.receiveraddr'), 'receiverAddress')}
        {isHourly && dictField(t('cn.f.forwarder'), 'forwarderName', forwarderId, setForwarderId, searchClients)}
        {dictField(t('cn.f.cargo'), 'cargoName', cargoId, setCargoId, searchCargos)}
        {isCmr && field(t('cn.f.volume'), 'cargoVolume', { type: 'number' })}
        {isCmr && field(t('cn.f.statcode'), 'cargoStatCode')}
        {isCmr && field(t('cn.f.docs'), 'submittedDocuments')}
        {/* Таможенное подтверждение — только для просмотра: его ставит таможня в своём кабинете. */}
        {isCmr && typeData.customsOfficerName != null && String(typeData.customsOfficerName) !== '' && (
          <div>
            <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('cn.f.customs')}</label>
            <input value={`${str(typeData.customsOfficerName)}${typeData.customsConfirmedAt ? ' · ' + str(typeData.customsConfirmedAt) : ''}`} disabled style={{ width: '100%' }} />
          </div>
        )}
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 16, flexWrap: 'wrap' }}>
        {canEdit && <button className="btn" onClick={save} disabled={busy}>{busy ? t('cn.btn.saving') : t('btn.save')}</button>}
        <button className="btn secondary" onClick={() => print(isCmr ? 'cmr' : 'attachment')} disabled={printing}>
          {printing ? t('cn.btn.forming') : `🖨 ${isCmr ? 'CMR' : t('cn.h')} (PDF)`}
        </button>
      </div>
    </div>
  );
}
