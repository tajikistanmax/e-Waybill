'use client';

import { useEffect, useRef, useState } from 'react';
import jsQR from 'jsqr';
import { useT } from '@/lib/i18n';
import { Icon, P } from './icons';

/**
 * Модальное окно сканирования QR камерой устройства — тот же QR, что на печатном
 * бланке ПЛ (кодирует ссылку {publicBaseUrl}/verify/{jws}). Декодирование — на клиенте
 * (jsQR по кадрам с камеры), без сервера. onScan получает распознанный JWS (не всю ссылку).
 */
export function QrScanner({ onScan, onClose }: { onScan: (jws: string) => void; onClose: () => void }) {
  const { t } = useT();
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const frameRef = useRef<number | null>(null);
  const doneRef = useRef(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;

    async function start() {
      if (!navigator.mediaDevices?.getUserMedia) {
        setError(t('qr.scan.unsupported'));
        return;
      }
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment' },
        });
        if (cancelled) { stream.getTracks().forEach(tr => tr.stop()); return; }
        streamRef.current = stream;
        const video = videoRef.current;
        if (!video) return;
        video.srcObject = stream;
        await video.play();
        tick();
      } catch {
        if (!cancelled) setError(t('qr.scan.denied'));
      }
    }

    function tick() {
      const video = videoRef.current;
      const canvas = canvasRef.current;
      if (!video || !canvas || doneRef.current) return;
      if (video.readyState === video.HAVE_ENOUGH_DATA) {
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        const ctx = canvas.getContext('2d', { willReadFrequently: true });
        if (ctx) {
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
          const frame = ctx.getImageData(0, 0, canvas.width, canvas.height);
          const code = jsQR(frame.data, frame.width, frame.height);
          if (code?.data) {
            doneRef.current = true;
            handleResult(code.data);
            return;
          }
        }
      }
      frameRef.current = requestAnimationFrame(tick);
    }

    function handleResult(text: string) {
      // QR кодирует полную ссылку .../verify/{jws} — вырезаем сам JWS; если внутри
      // почему-то оказался сразу голый JWS (три base64url-сегмента через точку) — берём как есть.
      let jws = text.trim();
      const marker = '/verify/';
      const idx = jws.indexOf(marker);
      if (idx !== -1) jws = jws.slice(idx + marker.length);
      jws = jws.split(/[?#]/)[0];
      stop();
      onScan(jws);
    }

    function stop() {
      if (frameRef.current != null) cancelAnimationFrame(frameRef.current);
      streamRef.current?.getTracks().forEach(tr => tr.stop());
      streamRef.current = null;
    }

    start();
    return () => { cancelled = true; stop(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      role="dialog"
      aria-modal="true"
      onClick={onClose}
      style={{ position: 'fixed', inset: 0, background: 'rgba(15,27,52,.45)', display: 'grid', placeItems: 'center', zIndex: 50, padding: 20 }}
    >
      <div className="card" onClick={e => e.stopPropagation()} style={{ width: 460, maxWidth: '100%', margin: 0, borderColor: 'var(--blue-500)', boxShadow: 'var(--shadow-lg)' }}>
        <div className="card-h">
          <h2>{t('qr.scan.h')}</h2>
          <button
            onClick={onClose}
            aria-label={t('btn.close')}
            style={{ marginLeft: 'auto', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--muted)' }}
          >
            ✕
          </button>
        </div>

        {error ? (
          <div className="error">{error}</div>
        ) : (
          <div style={{ position: 'relative', borderRadius: 12, overflow: 'hidden', background: '#000', aspectRatio: '1 / 1' }}>
            <video ref={videoRef} muted playsInline style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            {/* Рамка-видоискатель поверх видео — чисто визуальная подсказка, где держать QR. */}
            <div style={{
              position: 'absolute', inset: '14%', border: '3px solid rgba(255,255,255,.85)', borderRadius: 16,
              boxShadow: '0 0 0 2000px rgba(0,0,0,.25)', pointerEvents: 'none',
            }} />
          </div>
        )}
        <canvas ref={canvasRef} style={{ display: 'none' }} />

        <p className="hint" style={{ marginTop: 14, marginBottom: 0 }}>{t('qr.scan.hint')}</p>
      </div>
    </div>
  );
}
