'use client';

import { use, useEffect, useState } from 'react';
import { wb, Waybill, Title, TYPE_LABELS } from '@/lib/api';
import QRCode from 'qrcode';

/**
 * Печатная форма путевого листа (переходный период — раздел 8.11 ТЗ).
 * Печать: кнопка или Ctrl+P → браузер сохраняет в PDF / печатает.
 */
export default function PrintWaybill({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const [w, setW] = useState<Waybill | null>(null);
  const [titles, setTitles] = useState<Title[]>([]);
  const [qrUrl, setQrUrl] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    (async () => {
      const data = await wb.get(id);
      setW(data);
      setTitles(await wb.titles(id));
      if (data.number) {
        const { jws } = await wb.qr(id);
        setQrUrl(await QRCode.toDataURL(`${window.location.origin}/verify/${jws}`, { width: 150, margin: 0 }));
      }
    })().catch(e => setError(e.message));
  }, [id]);

  if (error) return <div className="error">{error}</div>;
  if (!w) return <p>Загрузка…</p>;

  const t = (type: string) => titles.find(x => x.titleType === type);
  const med = t('T2');
  const tech = t('T3');
  const medPost = t('T6');
  const org = w.organizationSnapshot ?? {};
  const veh = w.vehicleSnapshot ?? {};
  const drv = w.driverSnapshot ?? {};
  const fmt = (d: string | null | undefined) => (d ? new Date(d).toLocaleString('ru-RU') : '—');

  return (
    <>
      <style>{`
        @media print {
          header.top, .no-print { display: none !important; }
          main { max-width: none; margin: 0; padding: 0; }
          .sheet { border: none !important; }
          body { background: #fff; }
        }
        .sheet {
          background: #fff; border: 1px solid #94a3b8; padding: 22px 26px;
          max-width: 760px; margin: 0 auto; font-size: 12.5px; color: #111;
        }
        .sheet table { font-size: 12.5px; }
        .sheet th, .sheet td { border: 1px solid #cbd5e1; padding: 4px 8px; }
        .sheet .head { display: flex; justify-content: space-between; align-items: flex-start; gap: 14px; }
        .sheet .stat { font-size: 10px; color: #334155; }
        .sheet h2 { text-align: center; margin: 12px 0 2px; font-size: 16px; }
        .sheet .num { text-align: center; font-family: Consolas, monospace; font-size: 15px; font-weight: 700; margin-bottom: 12px; }
        .sheet .sig { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 30px; margin-top: 14px; }
        .sheet .sig div { border-top: 1px solid #94a3b8; padding-top: 3px; font-size: 11px; color: #334155; }
      `}</style>

      <div className="no-print toolbar">
        <button className="btn" onClick={() => window.print()}>🖨 Печать / сохранить в PDF</button>
        <a className="btn secondary" href={`/waybills/${id}`}>← К карточке</a>
      </div>

      <div className="sheet">
        <div className="head">
          <div className="stat">
            <b>ВАЗОРАТИ НАҚЛИЁТИ ҶУМҲУРИИ ТОҶИКИСТОН</b><br />
            Министерство транспорта Республики Таджикистан<br />
            Платформа «Ҳуҷҷатҳои электронии ҳамлу нақл» (ЭПД РТ)<br />
            <br />
            Ташкилот (КУҶТ): {String(org.name ?? '')}<br />
            РМА: {String(org.rma ?? '')} · Минтақа: {String(org.regionId ?? '—')}
          </div>
          {qrUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={qrUrl} alt="QR" width={120} height={120} />
          ) : <div style={{ width: 120, height: 120, border: '1px dashed #94a3b8' }} />}
        </div>

        <h2>ПУТЕВОЙ ЛИСТ · ВАРАҚАИ РОҲХАТ</h2>
        <div className="num">{w.number ?? '(номер не присвоен)'}</div>
        <p style={{ textAlign: 'center', marginBottom: 10 }}>
          {TYPE_LABELS[w.waybillType] ?? w.waybillType} · действителен: {fmt(w.validFrom)} — {fmt(w.validTo)}
        </p>

        <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 10 }}>
          <tbody>
            <tr><th style={{ width: '30%' }}>Транспортное средство</th><td>{String(veh.brand ?? '')} · госномер {w.vehicleRegNumber} · стоянка {String(veh.parkingNumber ?? '—')}</td></tr>
            <tr><th>Водитель (ронанда)</th><td>{String(drv.fullName ?? w.driverRma)} · РМА {w.driverRma} · ВУ {String(drv.licenseNumber ?? '—')} ({String(drv.licenseCategories ?? '—')})</td></tr>
            <tr><th>Маршрут / график</th><td>{w.route ?? '—'} / {w.schedule ?? '—'}</td></tr>
            <tr><th>Одометр: выезд / возврат</th><td>{w.odometerExit ?? '—'} км / {w.odometerEntry ?? '—'} км</td></tr>
            <tr><th>Статус</th><td>{w.status}{w.specialMark ? ` · Отметки: ${w.specialMark}` : ''}</td></tr>
          </tbody>
        </table>

        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr><th>Отметка</th><th>Результат</th><th>Дата и время</th><th>Подписал</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>Предрейсовый медосмотр (Т2)</td>
              <td>{med ? String(med.data?.verdict ?? 'ДОПУЩЕН') : '—'}</td>
              <td>{med ? fmt(med.signedAt) : '—'}</td>
              <td>{med ? `${String(med.data?.employeeName ?? '')} (${med.signerRma})` : '—'}</td>
            </tr>
            <tr>
              <td>Техконтроль (Т3)</td>
              <td>{tech ? String(tech.data?.verdict ?? 'ИСПРАВНО') : '—'}</td>
              <td>{tech ? fmt(tech.signedAt) : '—'}</td>
              <td>{tech ? `${String(tech.data?.employeeName ?? '')} (${tech.signerRma})` : '—'}</td>
            </tr>
            <tr>
              <td>Послерейсовый медосмотр (Т6)</td>
              <td>{medPost ? String(medPost.data?.verdict ?? '—') : '—'}</td>
              <td>{medPost ? fmt(medPost.signedAt) : '—'}</td>
              <td>{medPost ? `${String(medPost.data?.employeeName ?? '')} (${medPost.signerRma})` : '—'}</td>
            </tr>
          </tbody>
        </table>

        <div className="sig">
          <div>Диспетчер (танзимгар): {w.dispatcherRma ?? '—'}</div>
          <div>ТС принял (қабул кардам) — подпись водителя</div>
          <div>Механик — выпуск разрешён</div>
          <div>ТС сдал (супоридам) — подпись водителя</div>
        </div>

        <p style={{ marginTop: 12, fontSize: 10, color: '#475569' }}>
          Барои тафтиши роҳхат аз QR-код истифода баред · Для проверки подлинности отсканируйте QR-код
          · Документ сформирован платформой ЭПД РТ {new Date().toLocaleString('ru-RU')}
        </p>
      </div>
    </>
  );
}
