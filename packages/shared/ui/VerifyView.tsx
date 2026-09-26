'use client';

import { useEffect, useState } from 'react';
import { STATUS_LABELS } from '../lib/api';
import { useT } from '../lib/i18n';

type VerifyResult = {
  signatureValid: boolean;
  kind?: 'WAYBILL' | 'MALUMOTNOMA' | 'CONSIGNMENT_NOTE';
  // Борхат (сверка 25.09, B6): свой QR у каждой накладной к 2-Б.
  noteDate?: string;
  waybillNumber?: string;
  vehicle?: string;
  payerName?: string | null;
  senderName?: string | null;
  receiverName?: string | null;
  receiverAddress?: string | null;
  cargoName?: string | null;
  cargoWeight?: number | null;
  trips?: number | null;
  number?: string;
  onlineStatus?: string;
  validTo?: string;
  fio?: string;
  price?: number;
  routeSummary?: string;
  issuedAt?: string;
  annulled?: boolean;
  annulledAt?: string;
  // Сведения для сверки на месте (сверка 25.09, G6): номер ВУ приходит замаскированным,
  // фото — только одобренное и только если его показ не выключен в настройках платформы.
  route?: string | null;
  parkingNumber?: string | null;
  controlCardNumber?: string | null;
  controlCardValidTo?: string | null;
  licenseNumber?: string | null;
  licenseCategories?: string | null;
  licenseValidTo?: string | null;
  driverPhoto?: string;
  claims?: {
    num?: string;
    veh?: string;
    drv?: string;
    org?: string;
    med?: boolean;
    tec?: boolean;
    exp?: number;
    legacy?: boolean;
  };
};

/**
 * Публичная проверка путевого листа по QR-коду (общий компонент).
 * Инспектор сканирует QR камерой телефона — открывается страница с этим компонентом.
 * Используется и в публичном контуре (apps/public, аноним), и в кабинете надзора
 * (инспектор проверяет ПЛ прямо в приложении). Эндпоинт /wb-api/.../verify публичный.
 */
export function VerifyView({ jws }: { jws: string }) {
  const [result, setResult] = useState<VerifyResult | null>(null);
  const [error, setError] = useState('');
  const { t, tStatus } = useT();

  useEffect(() => {
    fetch(`/wb-api/api/v1/verify/${jws}`)
      .then(async r => {
        if (!r.ok) {
          const p = await r.json().catch(() => null);
          throw new Error(p?.detail ?? t('verify.invalid.default'));
        }
        return r.json();
      })
      .then(setResult)
      .catch(e => setError(e.message));
  }, [jws, t]);

  if (error) {
    return (
      <div className="card" style={{ textAlign: 'center', borderColor: 'var(--red)', borderWidth: 2 }}>
        <div style={{ fontSize: 72 }}>❌</div>
        <h1 style={{ color: 'var(--red)' }}>{t('verify.invalid.h')}</h1>
        <p>{error}</p>
      </div>
    );
  }

  if (!result) return <p>{t('verify.checking')}</p>;

  if (result.kind === 'MALUMOTNOMA') {
    // Аннулированная справка (сверка 25.09, E5) — подпись QR верна, но документ недействителен.
    const annulled = !!result.annulled;
    const okColor = annulled ? 'var(--red)' : 'var(--green)';
    return (
      <div className="card" style={{ textAlign: 'center', borderColor: okColor, borderWidth: 2 }}>
        <div style={{ fontSize: 72 }}>{annulled ? '❌' : '✅'}</div>
        <h1 style={{ color: okColor }}>{t(annulled ? 'verify.malumotnoma.annulled.h' : 'verify.malumotnoma.h')}</h1>
        <dl className="kv" style={{ textAlign: 'left', maxWidth: 520, margin: '20px auto' }}>
          <dt>{t('verify.malumotnoma.number')}</dt><dd className="number">{result.number ?? result.claims?.num ?? '—'}</dd>
          <dt>{t('verify.malumotnoma.fio')}</dt><dd>{result.fio ?? '—'}</dd>
          <dt>{t('verify.malumotnoma.route')}</dt><dd>{result.routeSummary ?? '—'}</dd>
          <dt>{t('verify.malumotnoma.price')}</dt><dd>{result.price != null ? `${result.price} сомонӣ` : '—'}</dd>
          <dt>{t('verify.malumotnoma.issuedat')}</dt>
          <dd>{result.issuedAt ? new Date(result.issuedAt).toLocaleString('ru-RU') : '—'}</dd>
          {annulled && <>
            <dt>{t('verify.malumotnoma.annulledat')}</dt>
            <dd>{result.annulledAt ? new Date(result.annulledAt).toLocaleString('ru-RU') : '—'}</dd>
          </>}
        </dl>
        <p style={{ color: 'var(--muted)', fontSize: 12 }}>{t('verify.foot')}</p>
      </div>
    );
  }

  if (result.kind === 'CONSIGNMENT_NOTE') {
    // Борхат действителен, пока его лист выдан и не аннулирован (как у legacy qr/cargowaybill).
    const st = result.onlineStatus;
    const ok = !!st && !['CANCELLED', 'DRAFT', 'CREATED', 'MED_REJECTED', 'TECH_REJECTED'].includes(st);
    const color = ok ? 'var(--green)' : 'var(--red)';
    const info = st ? (STATUS_LABELS[st] ?? { label: st, color: 'gray' }) : null;
    return (
      <div className="card" style={{ textAlign: 'center', borderColor: color, borderWidth: 2 }} data-testid="verify-note">
        <div style={{ fontSize: 72 }}>{ok ? '✅' : '❌'}</div>
        <h1 style={{ color }}>{t(ok ? 'verify.note.h' : 'verify.note.invalid.h')}</h1>
        <dl className="kv" style={{ textAlign: 'left', maxWidth: 560, margin: '20px auto' }}>
          <dt>{t('verify.note.number')}</dt><dd className="number">{result.number ?? result.claims?.num ?? '—'}</dd>
          <dt>{t('verify.note.date')}</dt><dd>{result.noteDate ?? '—'}</dd>
          <dt>{t('verify.note.waybill')}</dt><dd className="number">{result.waybillNumber ?? '—'}</dd>
          <dt>{t('verify.onlinestatus')}</dt>
          <dd>{info ? <span className={`badge ${info.color}`}>{tStatus(st!)}</span> : '—'}</dd>
          <dt>{t('col.vehiclefull')}</dt><dd>{result.vehicle ?? '—'}</dd>
          <dt>{t('verify.note.sender')}</dt><dd>{result.senderName ?? '—'}</dd>
          <dt>{t('verify.note.payer')}</dt><dd>{result.payerName ?? '—'}</dd>
          <dt>{t('verify.note.receiver')}</dt>
          <dd>{result.receiverName ?? '—'}{result.receiverAddress ? ` · ${result.receiverAddress}` : ''}</dd>
          <dt>{t('verify.note.cargo')}</dt>
          <dd>{result.cargoName ?? '—'}{result.cargoWeight != null ? ` · ${result.cargoWeight} т` : ''}{result.trips != null ? ` · ${result.trips} рейс.` : ''}</dd>
        </dl>
        <p style={{ color: 'var(--muted)', fontSize: 12 }}>{t('verify.foot')}</p>
      </div>
    );
  }

  const status = result.onlineStatus;
  const statusInfo = status ? (STATUS_LABELS[status] ?? { label: status, color: 'gray' }) : null;
  // Действителен: подпись верна И лист на линии (выдан/активен/возвращён). READY (номер есть,
  // но водителю не выдан) и черновые/терминальные статусы — НЕ действителен. Согласовано с /inspector.
  // Лист старой системы «Роҳхат» по старому бумажному QR (claims.legacy): перенесён архивом, его статус
  // в e-Waybill не отражает выдачу на линию — действителен, пока не истёк срок validTo (сверка 25.09, G5).
  const legacy = !!result.claims?.legacy;
  const isValid = result.signatureValid && (legacy
    ? !!result.validTo && new Date(result.validTo).getTime() > Date.now()
    : !!status && ['ISSUED', 'ACTIVE', 'RETURNED'].includes(status));
  const okColor = 'var(--green)';
  const warnColor = 'var(--amber)';
  // 2027-09-24 → 24.09.2027
  const day = (iso?: string | null) => (iso && /^\d{4}-\d{2}-\d{2}/.test(iso) ? iso.slice(0, 10).split('-').reverse().join('.') : iso ?? '');
  const withUntil = (main: string | null | undefined, until?: string | null) =>
    [main, until ? `${t('verify.until')} ${day(until)}` : null].filter(Boolean).join(' · ') || '—';

  return (
    <div className="card" style={{ textAlign: 'center', borderColor: isValid ? okColor : warnColor, borderWidth: 2 }}>
      <div style={{ fontSize: 72 }}>{isValid ? '✅' : '⚠️'}</div>
      <h1 style={{ color: isValid ? okColor : warnColor }}>
        {isValid ? t('verify.valid.h') : t('verify.notactive.h')}
      </h1>
      {result.driverPhoto && (
        // Фото — чтобы инспектор сверил водителя с документом (как на старой странице QR).
        // eslint-disable-next-line @next/next/no-img-element
        <img src={result.driverPhoto} alt={t('verify.photo.alt')} data-testid="verify-photo"
          style={{ maxWidth: 160, maxHeight: 200, borderRadius: 8, border: '1px solid var(--border)', marginTop: 8 }} />
      )}
      <dl className="kv" style={{ textAlign: 'left', maxWidth: 520, margin: '20px auto' }}>
        <dt>{t('col.number')}</dt><dd className="number">{result.number ?? result.claims?.num ?? '—'}</dd>
        <dt>{t('verify.onlinestatus')}</dt>
        <dd>{statusInfo ? <span className={`badge ${statusInfo.color}`}>{tStatus(status!)}</span> : '—'}</dd>
        <dt>{t('col.vehiclefull')}</dt><dd>{result.claims?.veh ?? '—'}</dd>
        <dt>{t('col.driver')}</dt><dd>{result.claims?.drv ?? '—'}</dd>
        {(result.licenseNumber || result.licenseCategories) && <>
          <dt>{t('verify.license')}</dt>
          <dd data-testid="verify-license">
            {withUntil([result.licenseNumber, result.licenseCategories].filter(Boolean).join(' · '), result.licenseValidTo)}
          </dd>
        </>}
        <dt>{t('col.org')}</dt><dd>{result.claims?.org ?? '—'}</dd>
        {result.route && <><dt>{t('verify.route')}</dt><dd>{result.route}</dd></>}
        {result.parkingNumber && <><dt>{t('verify.parking')}</dt><dd>{result.parkingNumber}</dd></>}
        {result.controlCardNumber && <>
          <dt>{t('verify.controlcard')}</dt><dd>{withUntil(result.controlCardNumber, result.controlCardValidTo)}</dd>
        </>}
        <dt>{t('insp.medtech')}</dt>
        <dd>
          <span className={`badge ${result.claims?.med ? 'green' : 'red'}`}>Т2 {result.claims?.med ? '✓' : '✗'}</span>{' '}
          <span className={`badge ${result.claims?.tec ? 'green' : 'red'}`}>Т3 {result.claims?.tec ? '✓' : '✗'}</span>
        </dd>
        <dt>{t('verify.validto')}</dt>
        <dd>{result.validTo ? new Date(result.validTo).toLocaleString('ru-RU') : '—'}</dd>
      </dl>
      {legacy && <p style={{ fontSize: 13 }} data-testid="verify-legacy">{t('verify.legacy')}</p>}
      <p style={{ color: 'var(--muted)', fontSize: 12 }}>{t('verify.foot')}</p>
    </div>
  );
}
