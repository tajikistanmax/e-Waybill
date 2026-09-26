'use client';

import { useEffect, useState } from 'react';

/**
 * Старый бумажный QR «Роҳхат» (/qrcode/{тип}/{id}; сверка 25.09, G5): сервис расшифровывает его и
 * отдаёт токен проверки перенесённого листа — дальше обычная страница проверки /verify/{jws}.
 * Документы, которые не переносились (борхат, СМР, справка), и неизвестные листы — понятное сообщение.
 */
export function LegacyQrRedirect({ type, token }: { type: string; token: string }) {
  const [error, setError] = useState('');
  useEffect(() => {
    let alive = true;
    fetch(`/wb-api/api/v1/verify/legacy/${encodeURIComponent(type)}/${encodeURIComponent(token)}`)
      .then(async r => {
        const body = await r.json().catch(() => null);
        if (!r.ok || !body?.jws) throw new Error(body?.detail ?? `Ошибка ${r.status}`);
        if (alive) window.location.replace(`/verify/${body.jws}`);
      })
      .catch(e => { if (alive) setError((e as Error).message); });
    return () => { alive = false; };
  }, [type, token]);

  return (
    <main style={{ maxWidth: 560, margin: '10vh auto', padding: '0 16px', fontFamily: 'system-ui, sans-serif', textAlign: 'center' }}>
      <h1 style={{ fontSize: 20 }}>Проверка документа «Роҳхат»</h1>
      {!error && <p style={{ color: '#64748b' }}>QR-код старой системы — открываем проверку перенесённого путевого листа…</p>}
      {error && (
        <>
          <p style={{ color: '#b91c1c' }} data-testid="legacy-qr-error">{error}</p>
          <p style={{ color: '#64748b', fontSize: 14 }}>Документ выдан в старой системе «Роҳхат». Проверить действующий путевой лист можно по QR-коду нового бланка.</p>
        </>
      )}
    </main>
  );
}
