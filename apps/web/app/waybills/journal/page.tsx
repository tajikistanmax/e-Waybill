'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { wb, STATUS_LABELS, type Waybill } from '@/lib/api';
import { useT } from '@/lib/i18n';

/**
 * Печатная форма: ЖУРНАЛ РЕГИСТРАЦИИ ПУТЕВЫХ ЛИСТОВ за период (вторая печатная форма).
 * Таблица всех ПЛ организации за выбранный период — для сдачи/архива. Печать: кнопка или Ctrl+P.
 */
function ymd(d: Date) { return d.toISOString().slice(0, 10); }

export default function WaybillJournalPrint() {
  const { t, tType, tStatus } = useT();
  const [all, setAll] = useState<Waybill[] | null>(null);
  const [error, setError] = useState('');
  const [from, setFrom] = useState(() => { const d = new Date(); d.setDate(d.getDate() - 30); return ymd(d); });
  const [to, setTo] = useState(() => ymd(new Date()));
  const [landscape, setLandscape] = useState(true);

  useEffect(() => { wb.list().then(setAll).catch(e => setError(e.message)); }, []);

  const rows = useMemo(() => {
    if (!all) return [];
    const f = from, tt = to;
    return all
      .filter(w => { const d = (w.createdAt ?? '').slice(0, 10); return d >= f && d <= tt; })
      .sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1));
  }, [all, from, to]);

  const org = rows[0]?.organizationSnapshot ?? {};
  const s = (o: unknown) => (o == null ? '' : String(o));
  const fmtDate = (d: string | null | undefined) => (d ? new Date(d).toLocaleDateString('ru-RU') : '—');
  const totalDist = rows.reduce((sum, w) => sum + (w.odometerExit != null && w.odometerEntry != null ? w.odometerEntry - w.odometerExit : 0), 0);

  return (
    <>
      <style>{`
        @page { size: ${landscape ? 'A4 landscape' : 'A4 portrait'}; margin: 12mm; }
        @media print {
          header.top, .no-print { display: none !important; }
          main { max-width: none; margin: 0; padding: 0; }
          .jsheet { border: none !important; }
          body { background: #fff; }
        }
        .jsheet { background: #fff; border: 1px solid #94a3b8; padding: 20px 24px; max-width: ${landscape ? 1100 : 780}px; margin: 0 auto; color: #111; font-size: 12px; }
        .jsheet table { width: 100%; border-collapse: collapse; font-size: 11px; }
        .jsheet th, .jsheet td { border: 1px solid #cbd5e1; padding: 3px 6px; text-align: left; }
        .jsheet thead th { background: #f1f5f9; font-weight: 700; }
        .jsheet .tc { text-align: center; }
        .jsheet .head { font-size: 10.5px; color: #334155; line-height: 1.5; }
        .jsheet h2 { text-align: center; margin: 10px 0 2px; font-size: 15px; }
      `}</style>

      <div className="no-print toolbar" style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <button className="btn" onClick={() => window.print()}>🖨 {t('jrn.print')}</button>
        <span style={{ color: 'var(--muted)', fontSize: 13 }}>{t('col.date')}:</span>
        <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 160 }} />
        <span>—</span>
        <input type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 160 }} />
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--muted)' }}>
          <input type="checkbox" checked={landscape} onChange={e => setLandscape(e.target.checked)} /> {t('jrn.landscape')}
        </label>
        <Link className="btn secondary" href="/waybills" style={{ marginLeft: 'auto', textDecoration: 'none' }}>← {t('nav.waybill.registry')}</Link>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="jsheet">
        <div className="head">
          <b>ВАЗОРАТИ НАҚЛИЁТИ ҶУМҲУРИИ ТОҶИКИСТОН</b> · Министерство транспорта Республики Таджикистан<br />
          Единая система путевых листов «е-Роҳхат»
          {org.name ? <> · {t('jrn.org')}: {s(org.name)} (РМА {s(org.rma)})</> : null}
        </div>
        <h2>{t('jrn.h')}</h2>
        <div className="tc" style={{ marginBottom: 10, color: '#334155' }}>{t('jrn.period')}: {fmtDate(from)} — {fmtDate(to)} · {t('jrn.total')}: {rows.length}</div>

        <table>
          <thead>
            <tr>
              <th className="tc" style={{ width: 34 }}>№</th>
              <th>{t('col.number')}</th>
              <th>{t('col.type')}</th>
              <th>{t('col.vehiclenum')}</th>
              <th>{t('col.driver')}</th>
              <th className="tc">{t('jrn.valid')}</th>
              <th className="tc">{t('col.status')}</th>
              <th className="tc">{t('jrn.dist')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((w, i) => {
              const dist = w.odometerExit != null && w.odometerEntry != null ? w.odometerEntry - w.odometerExit : null;
              const label = STATUS_LABELS[w.status]?.label ?? w.status;
              return (
                <tr key={w.id}>
                  <td className="tc">{i + 1}</td>
                  <td style={{ fontFamily: 'monospace' }}>{w.number ?? '—'}</td>
                  <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td style={{ fontFamily: 'monospace' }}>{w.vehicleRegNumber}</td>
                  <td>{s(w.driverSnapshot?.fullName) || w.driverRma}</td>
                  <td className="tc">{fmtDate(w.validFrom)}—{fmtDate(w.validTo)}</td>
                  <td className="tc">{tStatus(w.status) || label}</td>
                  <td className="tc">{dist != null ? dist : '—'}</td>
                </tr>
              );
            })}
            {rows.length === 0 && <tr><td colSpan={8} className="tc" style={{ padding: 16, color: '#64748b' }}>{t('common.norecords')}</td></tr>}
          </tbody>
          {rows.length > 0 && (
            <tfoot>
              <tr>
                <td colSpan={7} style={{ textAlign: 'right', fontWeight: 700 }}>{t('jrn.totaldist')}:</td>
                <td className="tc" style={{ fontWeight: 700 }}>{totalDist.toLocaleString('ru-RU')} {t('unit.km')}</td>
              </tr>
            </tfoot>
          )}
        </table>

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', marginTop: 22, gap: 24 }}>
          <div style={{ fontSize: 11, color: '#334155' }}>
            {t('jrn.signline')}: __________________________ / __________________
            <div style={{ marginTop: 4, color: '#64748b' }}>{t('jrn.signhint')}</div>
          </div>
          <div style={{ width: 118, height: 118, border: '1.5px solid #0c5c3d', borderRadius: '50%', display: 'grid', placeItems: 'center', textAlign: 'center', color: '#0c5c3d', fontSize: 8.5, lineHeight: 1.3, flex: '0 0 auto', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>
            е-РОҲХАТ<br />ЭЛЕКТРОННАЯ<br />ПЕЧАТЬ<br /><span style={{ fontSize: 7 }}>{fmtDate(new Date().toISOString())}</span>
          </div>
        </div>

        <div style={{ marginTop: 10, fontSize: 9.5, color: '#475569' }}>
          {t('jrn.foot')} · {new Date().toLocaleString('ru-RU')}
        </div>
      </div>
    </>
  );
}
