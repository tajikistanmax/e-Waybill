'use client';

import { use, useEffect, useState } from 'react';
import { STATUS_LABELS } from '@/lib/api';

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

  useEffect(() => {
    fetch(`/wb-api/api/v1/verify/${jws}`)
      .then(async r => {
        if (!r.ok) {
          const p = await r.json().catch(() => null);
          throw new Error(p?.detail ?? 'QR-код недействителен');
        }
        return r.json();
      })
      .then(setResult)
      .catch(e => setError(e.message));
  }, [jws]);

  if (error) {
    return (
      <div className="card" style={{ textAlign: 'center', borderColor: '#b91c1c', borderWidth: 2 }}>
        <div style={{ fontSize: 72 }}>❌</div>
        <h1 style={{ color: '#b91c1c' }}>ДОКУМЕНТ НЕДЕЙСТВИТЕЛЕН</h1>
        <p>{error}</p>
      </div>
    );
  }

  if (!result) return <p>Проверка…</p>;

  const status = result.onlineStatus;
  const statusInfo = status ? (STATUS_LABELS[status] ?? { label: status, color: 'gray' }) : null;
  const isValid = result.signatureValid && (status === 'ACTIVE' || status === 'ISSUED' || status === 'READY');

  return (
    <div className="card" style={{ textAlign: 'center', borderColor: isValid ? 'var(--brand)' : '#b45309', borderWidth: 2 }}>
      <div style={{ fontSize: 72 }}>{isValid ? '✅' : '⚠️'}</div>
      <h1 style={{ color: isValid ? 'var(--brand)' : '#b45309' }}>
        {isValid ? 'ПУТЕВОЙ ЛИСТ ДЕЙСТВИТЕЛЕН' : 'ПОДПИСЬ ВЕРНА, НО ДОКУМЕНТ НЕ АКТИВЕН'}
      </h1>
      <dl className="kv" style={{ textAlign: 'left', maxWidth: 520, margin: '20px auto' }}>
        <dt>Номер</dt><dd className="number">{result.number ?? result.claims?.num ?? '—'}</dd>
        <dt>Статус (онлайн)</dt>
        <dd>{statusInfo ? <span className={`badge ${statusInfo.color}`}>{statusInfo.label}</span> : '—'}</dd>
        <dt>Транспортное средство</dt><dd>{result.claims?.veh ?? '—'}</dd>
        <dt>Водитель</dt><dd>{result.claims?.drv ?? '—'}</dd>
        <dt>Организация</dt><dd>{result.claims?.org ?? '—'}</dd>
        <dt>Медосмотр / техконтроль</dt>
        <dd>
          <span className={`badge ${result.claims?.med ? 'green' : 'red'}`}>Т2 {result.claims?.med ? '✓' : '✗'}</span>{' '}
          <span className={`badge ${result.claims?.tec ? 'green' : 'red'}`}>Т3 {result.claims?.tec ? '✓' : '✗'}</span>
        </dd>
        <dt>Действителен до</dt>
        <dd>{result.validTo ? new Date(result.validTo).toLocaleString('ru-RU') : '—'}</dd>
      </dl>
      <p style={{ color: 'var(--muted)', fontSize: 12 }}>
        Криптографическая подпись проверена · Роҳхат · Минтранс Республики Таджикистан
      </p>
    </div>
  );
}
