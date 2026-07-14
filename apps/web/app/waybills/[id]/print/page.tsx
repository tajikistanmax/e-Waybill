'use client';

import { use, useEffect, useState } from 'react';
import { wb, md, Waybill, Title } from '@/lib/api';
import { useT } from '@/lib/i18n';
import QRCode from 'qrcode';

/**
 * Печатная форма путевого листа (переходный период — раздел 8.11 ТЗ).
 * Печать: кнопка или Ctrl+P → браузер сохраняет в PDF / печатает.
 * Юридическая значимость — за счёт электронных подписей титулов Т1–Т6 (TitleSigner: dev SHA-256,
 * прод CAdES УЦ РТ) + электронной печати платформы + QR офлайн-проверки.
 *
 * Международный грузовой (WB_TRUCK_INTL) печатается по официальному бланку «Шакли 5Б-БМ»
 * (Замима 5 ба Дастурамал, приказ Минтранса РТ № 75 от 12.07.2011) — компонент Form5B.
 * QR-код размещён на месте оттиска (зелёного квадрата) официального бланка.
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
  const [opt, setOpt] = useState({ showQr: true, showStamp: true, paperSize: 'A4' });
  const [landscape, setLandscape] = useState(false);
  const [copy, setCopy] = useState(false);
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

  // Официальный бланк 5Б-БМ широкий (много колонок) → по умолчанию альбомная (переключатель остаётся).
  useEffect(() => { if (w?.waybillType === 'WB_TRUCK_INTL') setLandscape(true); }, [w?.waybillType]);

  if (error) return <div className="error">{error}</div>;
  if (!w) return <p>Загрузка…</p>;

  const org = w.organizationSnapshot ?? {};
  const veh = w.vehicleSnapshot ?? {};
  const drv = w.driverSnapshot ?? {};
  const td = w.typeData ?? {};
  const fmt = (d: string | null | undefined) => (d ? new Date(d).toLocaleString('ru-RU') : '—');
  const s = (o: unknown) => (o == null ? '' : String(o));

  const signed = [...titles].sort((a, b) => (a.signedAt < b.signedAt ? -1 : 1));
  const signerName = (t: Title) => s(t.data?.employeeName ?? t.data?.dispatcher ?? t.data?.newDriverName) || t.signerRma;
  const fingerprint = (t: Title) => (t.signature ? t.signature.replace(/[^A-Za-z0-9]/g, '').slice(0, 16).toUpperCase() : '—');

  const isIntlTruck = w.waybillType === 'WB_TRUCK_INTL';

  // Дополнительные сведения по типу (для обобщённого бланка прочих типов).
  const extra: [string, string][] = [];
  const push = (label: string, val: unknown) => { if (val != null && s(val) !== '') extra.push([label, s(val)]); };
  push('Вид перевозки', td.shipmentKind === 'PIECEWORK' ? 'Корбайъ (сдельно)' : td.shipmentKind === 'HOURLY' ? 'Соатбайъ (повременно)' : td.shipmentKind);
  push('Вид услуги', td.serviceKind);
  push('Вид работ (спецтехника)', td.workType);
  push('Моточасы: выезд / возврат', td.motorHoursExit != null ? `${s(td.motorHoursExit)} / ${s(td.motorHoursEntry) || '—'}` : undefined);
  push('Класс опасного груза (ADR)', td.adrClass);
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
  if (w.secondDriverRma) push('Второй водитель (РМА)', w.secondDriverRma);

  return (
    <>
      <style>{`
        @page { size: ${opt.paperSize} ${landscape ? 'landscape' : 'portrait'}; margin: 10mm; }
        @media print {
          header.top, .no-print { display: none !important; }
          main { max-width: none; margin: 0; padding: 0; }
          .sheet, .f5b { border: none !important; }
          body { background: #fff; }
        }
        .sheet { position: relative; overflow: hidden; background: #fff; border: 1px solid #94a3b8; padding: 22px 26px;
          max-width: ${(landscape ? (opt.paperSize === 'A5' ? 760 : 1040) : (opt.paperSize === 'A5' ? 540 : 760))}px; margin: 0 auto; font-size: 12.5px; color: #111; }
        .copy-wm { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; pointer-events: none; z-index: 5;
          font-size: ${landscape ? 120 : 96}px; font-weight: 800; letter-spacing: 10px; color: rgba(220,38,38,0.18); transform: rotate(-32deg); white-space: nowrap;
          -webkit-print-color-adjust: exact; print-color-adjust: exact; }
        .sheet table { font-size: 12.5px; }
        .sheet th, .sheet td { border: 1px solid #cbd5e1; padding: 4px 8px; }
        .sheet .head { display: flex; justify-content: space-between; align-items: flex-start; gap: 14px; }
        .sheet .stat { font-size: 10px; color: #334155; }
        .sheet h2 { text-align: center; margin: 12px 0 2px; font-size: 16px; }
        .sheet .num { text-align: center; font-family: Consolas, monospace; font-size: 15px; font-weight: 700; margin-bottom: 12px; }
        .sheet .sect { margin-top: 12px; font-weight: 700; font-size: 12px; color: #0c5c3d; border-bottom: 1px solid #cbd5e1; padding-bottom: 3px; }
        .sheet .fp, .f5b .fp { font-family: Consolas, monospace; font-size: 10px; color: #0c5c3d; letter-spacing: 0.5px; }
        .sheet .foot-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; }
        .seal { -webkit-print-color-adjust: exact; print-color-adjust: exact; }

        /* ---- Официальный бланк 5Б-БМ (международный грузовой) ---- */
        .f5b { position: relative; overflow: hidden; background: #fff; color: #000; margin: 0 auto; padding: 6mm;
          max-width: ${landscape ? 1080 : 800}px; font-size: 8.2px; line-height: 1.12; }
        .f5b table { width: 100%; border-collapse: collapse; }
        .f5b td, .f5b th { border: 0.7px solid #000; padding: 1.5px 3px; vertical-align: top; }
        .f5b .t3 { font-weight: 700; text-align: center; }
        .f5b .lbl { font-size: 7px; color: #111; }
        .f5b .en { font-style: italic; color: #333; }
        .f5b .val { min-height: 11px; font-weight: 600; }
        .f5b .cnum { text-align: center; color: #555; font-size: 7px; }
        .f5b .blank td { height: 13px; }
        .f5b .title { text-align: center; }
        .f5b .qrbox { text-align: center; }
        .f5b .qrbox img { width: 96px; height: 96px; }
        .f5b .copy-wm { font-size: ${landscape ? 120 : 90}px; }
      `}</style>

      <div className="no-print toolbar" style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <button className="btn" onClick={() => window.print()}>🖨 Печать / сохранить в PDF</button>
        <button className="btn secondary" onClick={printCopy}>📄 Печать копии</button>
        {isIntlTruck && <span className="badge green" style={{ fontSize: 12 }}>Бланк 5Б-БМ (международный)</span>}
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--muted)' }}>
          <input type="checkbox" checked={landscape} onChange={e => setLandscape(e.target.checked)} />
          Альбомная ориентация
        </label>
        <a className="btn secondary" href={`/waybills/${id}`} style={{ marginLeft: 'auto' }}>← К карточке</a>
      </div>

      {isIntlTruck ? (
        <Form5B w={w} org={org} veh={veh} drv={drv} td={td} signed={signed} qrUrl={qrUrl}
          copy={copy} showQr={opt.showQr} showStamp={opt.showStamp} fingerprint={fingerprint} signerName={signerName} s={s} />
      ) : (
        <div className="sheet">
          {copy && <div className="copy-wm">КОПИЯ</div>}
          <div className="head">
            <div className="stat">
              <b>ВАЗОРАТИ НАҚЛИЁТИ ҶУМҲУРИИ ТОҶИКИСТОН</b><br />
              Министерство транспорта Республики Таджикистан<br />
              Единая система путевых листов «е-Роҳхат»<br /><br />
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
                <tbody>{extra.map(([k, v]) => <tr key={k}><th style={{ width: '30%' }}>{k}</th><td>{v}</td></tr>)}</tbody>
              </table>
            </>
          )}

          <div className="sect">Электронные подписи (титулы Т1–Т6)</div>
          <table className="sig-list" style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead><tr><th>Титул</th><th>Результат</th><th>Подписал</th><th>Дата и время</th><th>Отпечаток</th></tr></thead>
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
      )}
    </>
  );
}

/* ======================================================================
 * Официальный бланк международного грузового ПЛ — Шакли 5Б-БМ
 * (Замимаи 5 ба Дастурамал, приказ Минтранса РТ № 75 от 12.07.2011).
 * Данные системы заполняются автоматически; путевые/топливные/таможенные
 * ячейки — сетка бланка для отметок в пути. QR — на месте оттиска.
 * ==================================================================== */
type S = (o: unknown) => string;
function Form5B({ w, org, veh, drv, td, signed, qrUrl, copy, showQr, showStamp, fingerprint, s }: {
  w: Waybill; org: Record<string, unknown>; veh: Record<string, unknown>; drv: Record<string, unknown>;
  td: Record<string, unknown>; signed: Title[]; qrUrl: string; copy: boolean; showQr: boolean; showStamp: boolean;
  fingerprint: (t: Title) => string; signerName: (t: Title) => string; s: S;
}) {
  const vf = w.validFrom ? new Date(w.validFrom) : null;
  const day = vf ? String(vf.getDate()).padStart(2, '0') : '____';
  const month = vf ? vf.toLocaleDateString('ru-RU', { month: 'long' }) : '________';
  const year = vf ? String(vf.getFullYear()).slice(2) : '__';
  const trailers = (Array.isArray(td.trailers) ? td.trailers : []) as Record<string, unknown>[];
  const transit = (Array.isArray(td.transitCountries) ? td.transitCountries : []) as unknown[];
  const secondDrv = (td.secondDriverSnapshot ?? {}) as Record<string, unknown>;
  const byType = (tt: string) => signed.find(t => t.titleType === tt);
  const sig = (tt: string) => { const t = byType(tt); return t ? fingerprint(t) : '—'; };
  const num = w.number ?? '____________';

  return (
    <div className="f5b">
      {copy && <div className="copy-wm">КОПИЯ</div>}

      {/* Шапка: название (3 языка) + ссылка на приказ + Шакли 5Б-БМ */}
      <table>
        <tbody>
          <tr>
            <td className="title" style={{ width: '72%' }}>
              <div className="t3">РОҲХАТИ АВТОМОБИЛИ БОРКАШИ БАЙНАЛМИЛАЛӢ</div>
              <div className="en">ROUTE BLANK OF THE INTERNATIONAL TRUCK</div>
              <div className="t3">ПУТЕВОЙ ЛИСТ МЕЖДУНАРОДНОГО ГРУЗОВОГО АВТОМОБИЛЯ</div>
            </td>
            <td>
              Замимаи 5 ба Дастурамал<br />
              Бо фармоиши Вазири нақлиёти Ҷумҳурии Тоҷикистон,<br />
              таҳти № 75 аз 12.07.2011 тасдиқ шудааст<br />
              <b>Шакли 5Б – БМ</b>
            </td>
          </tr>
        </tbody>
      </table>

      {/* Серия/номер + дата + QR (на месте оттиска-квадрата официального бланка) */}
      <table style={{ marginTop: 3 }}>
        <tbody>
          <tr>
            <td style={{ width: '76%' }}>
              <div style={{ fontSize: 10, fontWeight: 700 }}>Силсилаи 5Б-БМ № {num}</div>
              <div style={{ marginTop: 4 }}>«{day}» {month} соли 20{year} &nbsp;&nbsp;&nbsp;&nbsp;&nbsp; TJK</div>
              <div style={{ marginTop: 3, fontSize: 7 }}>
                Ташкилот / Организация: <b>{s(org.name)}</b> · РМА {s(org.rma)}<br />
                Амал дорад / Действителен: {vf ? vf.toLocaleDateString('ru-RU') : '—'} — {w.validTo ? new Date(w.validTo).toLocaleDateString('ru-RU') : '—'}
              </div>
            </td>
            <td className="qrbox" style={{ width: '24%' }}>
              {showQr && qrUrl
                ? // eslint-disable-next-line @next/next/no-img-element
                  <img src={qrUrl} alt="QR" />
                : <div style={{ width: 96, height: 96, border: '1px solid #000', margin: '0 auto' }} />}
              <div style={{ fontSize: 6.5 }}>Тафтиши QR / QR-проверка</div>
            </td>
          </tr>
        </tbody>
      </table>

      {/* ТС / водители / прицепы / лицензия / виза / страна назначения */}
      <table style={{ marginTop: 3 }}>
        <tbody>
          <tr>
            <td className="lbl" style={{ width: '30%' }}>Номи корхона / The name of org-n / Наимен. орг-ции</td>
            <td className="val">{s(org.name)}</td>
          </tr>
          <tr>
            <td className="lbl">Автомобил (тамға ва рақ. дав.) / Vehicle (model, plate №) / Автомобиль (марка, гос. №)</td>
            <td className="val">{s(veh.brand)} · {w.vehicleRegNumber}</td>
          </tr>
          <tr>
            <td className="lbl">Ронандаи 1 (насаб, рақ. шаҳод.) / Driver 1 (name, cert. №) / Водитель 1 (Ф.И.О., № удост.)</td>
            <td className="val">{s(drv.fullName) || w.driverRma} · ВУ {s(drv.licenseNumber) || '—'} ({s(drv.licenseCategories) || '—'})</td>
          </tr>
          <tr>
            <td className="lbl">Ронандаи 2 / Driver 2 / Водитель 2</td>
            <td className="val">{s(secondDrv.fullName) || (w.secondDriverRma ?? '')}</td>
          </tr>
          {[0, 1, 2].map(i => (
            <tr key={i}>
              <td className="lbl">Ядаки {i + 1} (тамға ва рақ. дав.) / Trailer {i + 1} / Прицеп {i + 1} (марка, гос. №)</td>
              <td className="val">{trailers[i] ? `${s(trailers[i].brand)} · ${s(trailers[i].registrationNumber)}` : ''}</td>
            </tr>
          ))}
          <tr>
            <td className="lbl">Иҷозатнома / дозвол (E-PERMIT) № · Рақ. сертификат / License · Certificate № / № лицензии · сертификата</td>
            <td className="val">{s(td.permitNumber)}</td>
          </tr>
          <tr>
            <td className="lbl">Рақ. китобчаи ББА / № of TIR Carnet / № книжки МДП</td>
            <td className="val">{s(td.tirCarnet)}</td>
          </tr>
          <tr>
            <td className="lbl">Мӯҳлати виза / Validity of the visa / Срок визы</td>
            <td className="val">{s(td.visaCountry) ? `${s(td.visaCountry)} · ` : ''}до {s(td.visaValidTo) || '____'}</td>
          </tr>
          <tr>
            <td className="lbl">Ба давлати / In State / В государство</td>
            <td className="val">{s(td.unloadCountry)}</td>
          </tr>
        </tbody>
      </table>

      {/* Супориш ба ронанда — задания водителю (14–23) */}
      <div style={{ marginTop: 4, fontWeight: 700 }}>Супориш ба ронанда / Commissions to the driver / Задания водителю</div>
      <table>
        <thead>
          <tr className="lbl">
            <td>Дар ихтиёри / Disposal / В распоряжение</td>
            <td>Сана ва вақти расидан / Arrival / Дата и время прибытия</td>
            <td>Ҷои боргирӣ / Loading / Место погрузки</td>
            <td>Ҷои фаровардан / Delivery / Место разгрузки</td>
            <td>Давлатҳои транзитӣ / Transit / Транзит</td>
            <td>Номгӯи бор / Freight / Наименование груза</td>
            <td>Масофа, км / Distance / Расст.</td>
            <td>Ҳачм, тн / Weight / Объём</td>
          </tr>
          <tr className="cnum"><td>14</td><td>15</td><td>16</td><td>17</td><td>18</td><td>19</td><td>20</td><td>21</td></tr>
        </thead>
        <tbody>
          <tr>
            <td className="val">{s(td.consignee) || ''}</td>
            <td className="val"></td>
            <td className="val">{s(td.loadCountry)}{w.route ? ` · ${w.route}` : ''}</td>
            <td className="val">{s(td.unloadCountry)}</td>
            <td className="val">{transit.join(', ')}</td>
            <td className="val">{s(td.cargoName)}</td>
            <td className="val"></td>
            <td className="val"></td>
          </tr>
        </tbody>
      </table>

      {/* Блок допуска: здоровье / техсостояние / приём ТС / разрешение (с ЭП) */}
      <table style={{ marginTop: 3 }}>
        <tbody>
          <tr className="lbl">
            <td>Ҳолати саломатии ронанда хуб аст<br /><span className="en">Health is good</span><br />Водитель по состоянию здоровья допущен</td>
            <td>Автомобил коршоям аст<br /><span className="en">Technically operable</span><br />Автомобиль технически исправен</td>
            <td>Ронанда автомобилро қабул кард<br /><span className="en">Driver received a vehicle</span><br />Водитель принял автомобиль</td>
            <td>Ба кор иҷозат дода шуд<br /><span className="en">Permit to operation</span><br />К выезду разрешён</td>
          </tr>
          <tr>
            <td>Духтур / Doctor / Врач:<br /><b>{s((byType('T2')?.data?.employeeName)) || (byType('T2') ? byType('T2')!.signerRma : '')}</b><br /><span className="fp">ЭП: {sig('T2')}</span></td>
            <td>Механик / Mechanic:<br /><b>{s((byType('T3')?.data?.employeeName)) || (byType('T3') ? byType('T3')!.signerRma : '')}</b><br /><span className="fp">ЭП: {sig('T3')}</span></td>
            <td>Ронанда / Driver / Водитель:<br /><b>{s(drv.fullName) || w.driverRma}</b><br /><span className="en">предъявляет QR</span></td>
            <td>Диспетчер / Supervisor:<br /><b>{s((byType('T1')?.data?.dispatcher)) || (byType('T1') ? byType('T1')!.signerRma : '')}</b><br /><span className="fp">ЭП: {sig('T1')}</span></td>
          </tr>
        </tbody>
      </table>

      {/* Задание заказчика (24–36) — сетка бланка */}
      <div style={{ marginTop: 4, fontWeight: 700 }}>Ичроиши супориш / Implementation of the tasks / Выполнение задания</div>
      <table>
        <thead>
          <tr className="lbl">
            <td>Сана / Date / Дата</td><td>Заказчик</td><td>Код</td><td>Вид груза</td><td>Код</td>
            <td>Ҷои боркунӣ / Место погрузки</td><td>Ҷои борфарорӣ / Место разгрузки</td>
            <td>Транзит</td><td>Масофа, км</td><td>Ҳачм, тн</td><td>Простой</td><td>№ БМН (CMR)</td><td>Имзо заказчика</td>
          </tr>
          <tr className="cnum"><td>24</td><td>25</td><td>26</td><td>27</td><td>28</td><td>29</td><td>30</td><td>31</td><td>32</td><td>33</td><td>34</td><td>35</td><td>36</td></tr>
        </thead>
        <tbody>
          {[0, 1, 2].map(i => (
            <tr className="blank" key={i}><td>{i === 0 && vf ? vf.toLocaleDateString('ru-RU') : ''}</td><td>{i === 0 ? s(td.consignor) : ''}</td><td></td><td>{i === 0 ? s(td.cargoName) : ''}</td><td></td><td>{i === 0 ? s(td.loadCountry) : ''}</td><td>{i === 0 ? s(td.unloadCountry) : ''}</td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
          ))}
        </tbody>
      </table>

      {/* Прохождение таможенных пунктов (37–41) — сетка бланка */}
      <div style={{ marginTop: 4, fontWeight: 700 }}>Гузаштани назорати гумрукӣ / Passing customs points / Прохождение таможенных пунктов</div>
      <table>
        <thead>
          <tr className="lbl">
            <td>Номи давлат / State / Государство</td><td>Номи гумрук / Customs point / Таможенный пункт</td>
            <td>Сана ва вақти расидан / Arrival / Прибытие</td><td>Сана ва вақти рафтан / Departure / Убытие</td><td>Имзо ва мӯҳр / Signature &amp; seal / Подпись и печать</td>
          </tr>
          <tr className="cnum"><td>37</td><td>38</td><td>39</td><td>40</td><td>41</td></tr>
        </thead>
        <tbody>
          {[0, 1, 2, 3].map(i => <tr className="blank" key={i}><td></td><td></td><td></td><td></td><td></td></tr>)}
        </tbody>
      </table>

      {/* Особые отметки + электронное заверение */}
      <table style={{ marginTop: 3 }}>
        <tbody>
          <tr>
            <td style={{ width: '68%' }}>
              <b>Қайдҳои махсус / Special marks / Особые отметки:</b><br />
              <span className="val">{s(w.specialMark)}</span>
              <div style={{ marginTop: 4, fontSize: 7 }}>
                Ҳуҷҷат дар низоми «е-Роҳхат» электронӣ имзо шудааст — имзои дастӣ ва мӯҳри тар лозим нест.
                Санҷиш — аз рӯи QR (офлайн). / Документ подписан электронно в «е-Роҳхат»; проверка по QR (офлайн).
              </div>
            </td>
            <td style={{ textAlign: 'center' }}>
              {showStamp && <PlatformSeal number={w.number} date={vf ? vf.toLocaleDateString('ru-RU') : '—'} />}
            </td>
          </tr>
        </tbody>
      </table>
      <div style={{ marginTop: 3, fontSize: 6.5, color: '#333' }}>Ташаккул ёфт / Сформирован: {new Date().toLocaleString('ru-RU')} · е-Роҳхат</div>
    </div>
  );
}

/** Электронная печать платформы (SVG-штамп в госцветах РТ; печатается как вектор). */
function PlatformSeal({ number, date }: { number: string | null; date: string }) {
  const green = '#0c5c3d';
  return (
    <svg className="seal" width="112" height="112" viewBox="0 0 150 150" role="img" aria-label="Электронная печать">
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
