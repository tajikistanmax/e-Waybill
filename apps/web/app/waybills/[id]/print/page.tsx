'use client';

import { use, useEffect, useState } from 'react';
import { wb, md, Waybill, Title } from '@/lib/api';
import { useT } from '@/lib/i18n';
import QRCode from 'qrcode';

/**
 * Печатная форма путевого листа (переходный период — раздел 8.11 ТЗ).
 * Печать: кнопка или Ctrl+P → браузер сохраняет в PDF / печатает.
 * Юридическая значимость — за счёт электронных подписей титулов Т1–Т6 (TitleSigner: dev SHA-256,
 * прод CAdES УЦ РТ) + электронной печати платформы + QR офлайн-проверки. Ручные росписи/мокрая
 * печать не требуются: документ самозаверяемый в системе е-Роҳхат.
 */

const TITLE_LABEL: Record<string, string> = {
  T1: 'Выпуск (Т1)', T2: 'Предрейсовый медосмотр (Т2)', T3: 'Предрейсовый техконтроль (Т3)',
  T4: 'Выезд на линию (Т4)', T5: 'Возвращение (Т5)', T6: 'Послерейсовый медосмотр (Т6)',
  CORRECTION: 'Корректировка',
};
const ROLE_LABEL: Record<string, string> = { DISPATCHER: 'Диспетчер', DOCTOR: 'Медработник', MECHANIC: 'Механик' };

export default function PrintWaybill({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const [w, setW] = useState<Waybill | null>(null);
  const [titles, setTitles] = useState<Title[]>([]);
  const [qrUrl, setQrUrl] = useState('');
  const [error, setError] = useState('');
  // Опции бланка из настроек платформы (§29, /settings/print). Дефолт — полный бланк A4.
  const [opt, setOpt] = useState({ showQr: true, showStamp: true, paperSize: 'A4' });
  const [landscape, setLandscape] = useState(false); // §17: альбомная ориентация
  const [copy, setCopy] = useState(false);           // §17: отметка «КОПИЯ»
  const { tType } = useT();

  function printCopy() {
    setCopy(true);
    setTimeout(() => { window.print(); setTimeout(() => setCopy(false), 400); }, 60);
  }

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

  useEffect(() => {
    md.settings('print')
      .then(rows => {
        const v = (k: string) => rows.find(r => r.settingKey === k)?.settingValue;
        setOpt({ showQr: v('show_qr') !== 'false', showStamp: v('show_stamp') !== 'false', paperSize: v('paper_size') ?? 'A4' });
      })
      .catch(() => { /* нет настроек — остаётся полный бланк A4 */ });
  }, []);

  if (error) return <div className="error">{error}</div>;
  if (!w) return <p>Загрузка…</p>;

  const org = w.organizationSnapshot ?? {};
  const veh = w.vehicleSnapshot ?? {};
  const drv = w.driverSnapshot ?? {};
  const td = w.typeData ?? {};
  const fmt = (d: string | null | undefined) => (d ? new Date(d).toLocaleString('ru-RU') : '—');
  const s = (o: unknown) => (o == null ? '' : String(o));

  // Электронные подписи титулов (в порядке подписания). Отпечаток — усечение подписи TitleSigner.
  const signed = [...titles].sort((a, b) => (a.signedAt < b.signedAt ? -1 : 1));
  const signerName = (t: Title) => s(t.data?.employeeName ?? t.data?.dispatcher ?? t.data?.newDriverName) || t.signerRma;
  const fingerprint = (t: Title) => (t.signature ? t.signature.replace(/[^A-Za-z0-9]/g, '').slice(0, 16).toUpperCase() : '—');

  // Типовые сведения (по типу ПЛ) — из снимка type_data.
  const extra: [string, string][] = [];
  const push = (label: string, val: unknown) => { if (val != null && s(val) !== '') extra.push([label, s(val)]); };
  push('Вид перевозки', td.shipmentKind === 'PIECEWORK' ? 'Корбайъ (сдельно)' : td.shipmentKind === 'HOURLY' ? 'Соатбайъ (повременно)' : td.shipmentKind);
  push('Вид услуги', td.serviceKind);
  push('Класс опасного груза (ADR)', td.adrClass);
  push('Номер ООН (UN)', td.unNumber);
  push('Наименование груза', td.cargoName);
  push('Страна визы', td.visaCountry);
  push('Виза действительна до', td.visaValidTo);
  push('Страна погрузки', td.loadCountry);
  push('Страна разгрузки', td.unloadCountry);
  push('Номер дозвола (E-PERMIT)', td.permitNumber);
  if (Array.isArray(td.transitCountries) && td.transitCountries.length) push('Транзит', (td.transitCountries as unknown[]).join(', '));
  if (Array.isArray(td.trailers) && td.trailers.length) {
    push('Прицепы', (td.trailers as Record<string, unknown>[]).map(tr => `${s(tr.registrationNumber)} (${s(tr.brand)})`).join('; '));
  }
  if (td.custom && typeof td.custom === 'object') {
    for (const [k, v] of Object.entries(td.custom as Record<string, unknown>)) push(k, v);
  }
  if (w.secondDriverRma) push('Второй водитель (РМА)', w.secondDriverRma);

  return (
    <>
      <style>{`
        @page { size: ${opt.paperSize} ${landscape ? 'landscape' : 'portrait'}; margin: 12mm; }
        @media print {
          header.top, .no-print { display: none !important; }
          main { max-width: none; margin: 0; padding: 0; }
          .sheet { border: none !important; }
          body { background: #fff; }
        }
        .sheet {
          position: relative; overflow: hidden;
          background: #fff; border: 1px solid #94a3b8; padding: 22px 26px;
          max-width: ${(landscape ? (opt.paperSize === 'A5' ? 760 : 1040) : (opt.paperSize === 'A5' ? 540 : 760))}px; margin: 0 auto; font-size: 12.5px; color: #111;
        }
        .sheet .copy-wm {
          position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
          pointer-events: none; z-index: 5;
          font-size: ${landscape ? 120 : 96}px; font-weight: 800; letter-spacing: 10px;
          color: rgba(220, 38, 38, 0.18); transform: rotate(-32deg); white-space: nowrap;
          -webkit-print-color-adjust: exact; print-color-adjust: exact;
        }
        .sheet table { font-size: 12.5px; }
        .sheet th, .sheet td { border: 1px solid #cbd5e1; padding: 4px 8px; }
        .sheet .head { display: flex; justify-content: space-between; align-items: flex-start; gap: 14px; }
        .sheet .stat { font-size: 10px; color: #334155; }
        .sheet h2 { text-align: center; margin: 12px 0 2px; font-size: 16px; }
        .sheet .num { text-align: center; font-family: Consolas, monospace; font-size: 15px; font-weight: 700; margin-bottom: 12px; }
        .sheet .sect { margin-top: 12px; font-weight: 700; font-size: 12px; color: #0c5c3d; border-bottom: 1px solid #cbd5e1; padding-bottom: 3px; }
        .sheet .sig-list { margin-top: 6px; }
        .sheet .sig-list td { vertical-align: top; }
        .sheet .fp { font-family: Consolas, monospace; font-size: 10px; color: #0c5c3d; letter-spacing: 0.5px; }
        .sheet .foot-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; }
        .seal { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
      `}</style>

      <div className="no-print toolbar" style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <button className="btn" onClick={() => window.print()}>🖨 Печать / сохранить в PDF</button>
        <button className="btn secondary" onClick={printCopy}>📄 Печать копии</button>
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--muted)' }}>
          <input type="checkbox" checked={landscape} onChange={e => setLandscape(e.target.checked)} />
          Альбомная ориентация
        </label>
        <a className="btn secondary" href={`/waybills/${id}`} style={{ marginLeft: 'auto' }}>← К карточке</a>
      </div>

      <div className="sheet">
        {copy && <div className="copy-wm">КОПИЯ</div>}
        <div className="head">
          <div className="stat">
            <b>ВАЗОРАТИ НАҚЛИЁТИ ҶУМҲУРИИ ТОҶИКИСТОН</b><br />
            Министерство транспорта Республики Таджикистан<br />
            Единая система путевых листов «е-Роҳхат»<br />
            <br />
            Ташкилот (КУҶТ): {s(org.name)}<br />
            РМА: {s(org.rma)} · Минтақа: {s(org.regionId) || '—'}
          </div>
          {opt.showQr && (qrUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={qrUrl} alt="QR" width={120} height={120} />
          ) : <div style={{ width: 120, height: 120, border: '1px dashed #94a3b8' }} />)}
        </div>

        <h2>ПУТЕВОЙ ЛИСТ · ВАРАҚАИ РОҲХАТ</h2>
        <div className="num">{w.number ?? '(номер не присвоен)'}</div>
        <p style={{ textAlign: 'center', marginBottom: 10 }}>
          {tType(w.waybillType)} · действителен: {fmt(w.validFrom)} — {fmt(w.validTo)}
        </p>

        <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 6 }}>
          <tbody>
            <tr><th style={{ width: '30%' }}>Транспортное средство</th><td>{s(veh.brand)} · госномер {w.vehicleRegNumber} · стоянка {s(veh.parkingNumber) || '—'}</td></tr>
            <tr><th>Водитель (ронанда)</th><td>{s(drv.fullName) || w.driverRma} · РМА {w.driverRma} · ВУ {s(drv.licenseNumber) || '—'} ({s(drv.licenseCategories) || '—'})</td></tr>
            <tr><th>Маршрут / график</th><td>{w.route ?? '—'} / {w.schedule ?? '—'}</td></tr>
            <tr><th>Одометр: выезд / возврат</th><td>{w.odometerExit ?? '—'} км / {w.odometerEntry ?? '—'} км{w.odometerExit != null && w.odometerEntry != null ? ` · пробег ${w.odometerEntry - w.odometerExit} км` : ''}</td></tr>
            <tr><th>Статус</th><td>{w.status}{w.specialMark ? ` · ${w.specialMark}` : ''}</td></tr>
          </tbody>
        </table>

        {extra.length > 0 && (
          <>
            <div className="sect">Дополнительные сведения по типу</div>
            <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 6 }}>
              <tbody>
                {extra.map(([k, v]) => <tr key={k}><th style={{ width: '30%' }}>{k}</th><td>{v}</td></tr>)}
              </tbody>
            </table>
          </>
        )}

        <div className="sect">Электронные подписи (титулы Т1–Т6)</div>
        <table className="sig-list" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr><th>Титул</th><th>Результат</th><th>Подписал</th><th>Дата и время</th><th>Отпечаток</th></tr>
          </thead>
          <tbody>
            {signed.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', color: '#64748b' }}>Титулы ещё не подписаны</td></tr>}
            {signed.map(t => (
              <tr key={t.id}>
                <td>{TITLE_LABEL[t.titleType] ?? t.titleType}</td>
                <td>{s(t.data?.verdict) || '—'}</td>
                <td>{signerName(t)}<br /><span style={{ color: '#64748b', fontSize: 10 }}>{ROLE_LABEL[t.signerRole] ?? t.signerRole} · РМА {t.signerRma}</span></td>
                <td>{fmt(t.signedAt)}</td>
                <td className="fp">{fingerprint(t)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <div className="foot-row">
          <div style={{ fontSize: 10.5, color: '#334155', maxWidth: 360, lineHeight: 1.5 }}>
            Документ подписан электронно в системе «е-Роҳхат» — ручные подписи и мокрая печать
            не требуются. Подлинность и статус проверяются по QR-коду (офлайн).
            <br />Барои тафтиш аз QR-код истифода баред.
          </div>
          {opt.showStamp && <PlatformSeal number={w.number} date={fmt(w.validFrom)} />}
        </div>

        <p style={{ marginTop: 12, fontSize: 10, color: '#475569' }}>
          Документ сформирован системой «е-Роҳхат» {new Date().toLocaleString('ru-RU')}
        </p>
      </div>
    </>
  );
}

/** Электронная печать платформы (SVG-штамп в госцветах РТ; печатается как вектор). */
function PlatformSeal({ number, date }: { number: string | null; date: string }) {
  const green = '#0c5c3d';
  return (
    <svg className="seal" width="132" height="132" viewBox="0 0 150 150" role="img" aria-label="Электронная печать">
      <defs>
        <path id="sealTop" d="M 26 75 A 49 49 0 0 1 124 75" fill="none" />
        <path id="sealBottom" d="M 30 78 A 45 45 0 0 0 120 78" fill="none" />
      </defs>
      <circle cx="75" cy="75" r="71" fill="none" stroke={green} strokeWidth="2.5" />
      <circle cx="75" cy="75" r="58" fill="none" stroke={green} strokeWidth="1" />
      <text fontSize="8.6" fontWeight="700" fill={green} letterSpacing="0.4">
        <textPath href="#sealTop" startOffset="50%" textAnchor="middle">ВАЗОРАТИ НАҚЛИЁТИ ҶУМҲУРИИ ТОҶИКИСТОН</textPath>
      </text>
      <text fontSize="8.2" fontWeight="700" fill={green} letterSpacing="1.2">
        <textPath href="#sealBottom" startOffset="50%" textAnchor="middle">ЭЛЕКТРОННАЯ ПЕЧАТЬ · е-РОҲХАТ</textPath>
      </text>
      <text x="75" y="52" textAnchor="middle" fontSize="13" fill={green}>★</text>
      <text x="75" y="72" textAnchor="middle" fontSize="9" fontWeight="700" fill={green}>ПОДПИСАНО</text>
      <text x="75" y="83" textAnchor="middle" fontSize="9" fontWeight="700" fill={green}>В СИСТЕМЕ</text>
      <text x="75" y="98" textAnchor="middle" fontSize="7" fill={green} fontFamily="Consolas, monospace">{number ?? 'б/н'}</text>
      <text x="75" y="107" textAnchor="middle" fontSize="6" fill={green}>{date}</text>
    </svg>
  );
}
