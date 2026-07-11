'use client';

import { use, useEffect, useState } from 'react';
import { STATUS_LABELS } from '@/lib/api';
import { useT } from '@/lib/i18n';

type VerifyResult = {
  signatureValid: boolean;
  number?: string;
  onlineStatus?: string;
  validTo?: string;
  claims?: {
    num?: string;
    veh?: string;
    drv?: string;
    org?: string;
    med?: boolean;
    tec?: boolean;
    exp?: number;
  };
};

/**
 * Публичная страница проверки путевого листа по QR-коду.
 * Инспектор сканирует QR камерой телефона — открывается эта страница.
 */
export default function VerifyPage({ params }: { params: Promise<{ jws: string }> }) {
  const { jws } = use(params);
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

  const status = result.onlineStatus;
  const statusInfo = status ? (STATUS_LABELS[status] ?? { label: status, color: 'gray' }) : null;
  // Действителен: подпись верна И лист на линии (выдан/активен/возвращён). READY (номер есть,
  // но водителю не выдан) и черновые/терминальные статусы — НЕ действителен. Согласовано с /inspector.
  const isValid = result.signatureValid && !!status && ['ISSUED', 'ACTIVE', 'RETURNED'].includes(status);
  const okColor = 'var(--green)';
  const warnColor = 'var(--amber)';

  return (
    <div className="card" style={{ textAlign: 'center', borderColor: isValid ? okColor : warnColor, borderWidth: 2 }}>
      <div style={{ fontSize: 72 }}>{isValid ? '✅' : '⚠️'}</div>
      <h1 style={{ color: isValid ? okColor : warnColor }}>
        {isValid ? t('verify.valid.h') : t('verify.notactive.h')}
      </h1>
      <dl className="kv" style={{ textAlign: 'left', maxWidth: 520, margin: '20px auto' }}>
        <dt>{t('col.number')}</dt><dd className="number">{result.number ?? result.claims?.num ?? '—'}</dd>
        <dt>{t('verify.onlinestatus')}</dt>
        <dd>{statusInfo ? <span className={`badge ${statusInfo.color}`}>{tStatus(status!)}</span> : '—'}</dd>
        <dt>{t('col.vehiclefull')}</dt><dd>{result.claims?.veh ?? '—'}</dd>
        <dt>{t('col.driver')}</dt><dd>{result.claims?.drv ?? '—'}</dd>
        <dt>{t('col.org')}</dt><dd>{result.claims?.org ?? '—'}</dd>
        <dt>{t('insp.medtech')}</dt>
        <dd>
          <span className={`badge ${result.claims?.med ? 'green' : 'red'}`}>Т2 {result.claims?.med ? '✓' : '✗'}</span>{' '}
          <span className={`badge ${result.claims?.tec ? 'green' : 'red'}`}>Т3 {result.claims?.tec ? '✓' : '✗'}</span>
        </dd>
        <dt>{t('verify.validto')}</dt>
        <dd>{result.validTo ? new Date(result.validTo).toLocaleString('ru-RU') : '—'}</dd>
      </dl>
      <p style={{ color: 'var(--muted)', fontSize: 12 }}>{t('verify.foot')}</p>
    </div>
  );
}
