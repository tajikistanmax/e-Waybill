'use client';

import { useCallback, useEffect, useState } from 'react';
import { wb, authHeaders, type MechanicJournal, type DoctorJournal } from '@/lib/api';
import { downloadCsv } from '@/lib/csv';
import { useT } from '@/lib/i18n';

function today(offset = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return d.toISOString().slice(0, 10);
}

const fmtMark = (m: { verdict: string; employeeName: string; signedAt: string; details: string } | null) => {
  if (!m) return '—';
  const parts = [m.verdict];
  if (m.employeeName) parts.push(m.employeeName);
  if (m.signedAt && m.signedAt !== '—') parts.push(new Date(m.signedAt).toLocaleString('ru-RU'));
  if (m.details) parts.push(m.details);
  return parts.join(' · ');
};

/** Журналы предрейсового контроля: Дафтари қайди механик (Т3) и духтӯр (Т2/Т6). */
export default function JournalsTab() {
  const { t } = useT();
  const [kind, setKind] = useState<'mechanic' | 'doctor'>('mechanic');
  const [from, setFrom] = useState(today(-30));
  const [to, setTo] = useState(today());
  const [mech, setMech] = useState<MechanicJournal | null>(null);
  const [doc, setDoc] = useState<DoctorJournal | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    setError('');
    if (kind === 'mechanic') wb.mechanicJournal(from, to).then(setMech).catch(e => setError(e.message));
    else wb.doctorJournal(from, to).then(setDoc).catch(e => setError(e.message));
  }, [kind, from, to]);

  useEffect(() => { load(); }, [load]);

  async function xlsx() {
    try {
      const r = await fetch(`/wb-api/api/v1/reports/journal/${kind}.xlsx?from=${from}&to=${to}`, { headers: authHeaders() });
      if (!r.ok) throw new Error(`Ошибка ${r.status}`);
      const url = URL.createObjectURL(await r.blob());
      const a = document.createElement('a');
      a.href = url; a.download = `журнал-${kind === 'mechanic' ? 'механика' : 'врача'}-${from}_${to}.xlsx`; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch (e) { setError((e as Error).message); }
  }

  function csv() {
    if (kind === 'mechanic' && mech) {
      const rows: unknown[][] = [['Дата', '№ ПЛ', 'ТС', 'Водитель', 'Одометр выезд', 'Заключение', 'Механик', 'РМА', 'Подписано', 'Показатели']];
      mech.rows.forEach(r => rows.push([r.date, r.number, r.vehicle, r.driver, r.odometerExit ?? '',
        r.control.verdict, r.control.employeeName, r.control.employeeRma, r.control.signedAt, r.control.details]));
      downloadCsv(`журнал-механика-${from}_${to}.csv`, rows);
    } else if (kind === 'doctor' && doc) {
      const rows: unknown[][] = [['Дата', '№ ПЛ', 'ТС', 'Водитель', 'Предрейсовый (Т2)', 'Послерейсовый (Т6)']];
      doc.rows.forEach(r => rows.push([r.date, r.number, r.vehicle, r.driver, fmtMark(r.preTrip), fmtMark(r.postTrip)]));
      downloadCsv(`журнал-врача-${from}_${to}.csv`, rows);
    }
  }

  const rows = kind === 'mechanic' ? mech?.rows ?? [] : doc?.rows ?? [];

  return (
    <>
      <div className="toolbar">
        <button className={`btn ${kind === 'mechanic' ? '' : 'secondary'}`} onClick={() => setKind('mechanic')}>{t('rj.mechanic')}</button>
        <button className={`btn ${kind === 'doctor' ? '' : 'secondary'}`} onClick={() => setKind('doctor')}>{t('rj.doctor')}</button>
        <span className="spacer" style={{ flex: 1 }} />
        <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={{ width: 160 }} />
        <span>—</span>
        <input type="date" value={to} onChange={e => setTo(e.target.value)} style={{ width: 160 }} />
        <button className="btn secondary" onClick={csv} disabled={rows.length === 0}>CSV</button>
        <button className="btn secondary" onClick={xlsx} disabled={rows.length === 0}>XLSX</button>
      </div>
      {error && <div className="error">{error}</div>}

      <div className="card" style={{ overflowX: 'auto' }}>
        <h2>{kind === 'mechanic' ? t('rj.h.mech') : t('rj.h.doc')} · {from} — {to} · {t('rj.rows')}: {rows.length}</h2>
        {kind === 'mechanic' ? (
          <table>
            <thead><tr><th>{t('rj.date')}</th><th>{t('rj.wbnum')}</th><th>{t('rj.vehicle')}</th><th>{t('rj.driver')}</th><th>{t('rj.odometer')}</th><th>{t('rj.conclusion')}</th><th>{t('rj.mechanicname')}</th><th>{t('rj.signed')}</th><th>{t('rj.indicators')}</th></tr></thead>
            <tbody>
              {(mech?.rows ?? []).map((r, i) => (
                <tr key={i}>
                  <td>{r.date}</td><td><span className="number">{r.number}</span></td>
                  <td>{r.vehicle}</td><td>{r.driver}</td><td>{r.odometerExit ?? '—'}</td>
                  <td>{r.control.verdict}</td><td>{r.control.employeeName}<br /><span style={{ color: '#64748b', fontSize: 10 }}>РМА {r.control.employeeRma}</span></td>
                  <td>{r.control.signedAt !== '—' ? new Date(r.control.signedAt).toLocaleString('ru-RU') : '—'}</td>
                  <td style={{ fontSize: 11 }}>{r.control.details || '—'}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={9} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('rj.nodata')}</td></tr>}
            </tbody>
          </table>
        ) : (
          <table>
            <thead><tr><th>{t('rj.date')}</th><th>{t('rj.wbnum')}</th><th>{t('rj.vehicle')}</th><th>{t('rj.driver')}</th><th>{t('rj.preinspect')}</th><th>{t('rj.postinspect')}</th></tr></thead>
            <tbody>
              {(doc?.rows ?? []).map((r, i) => (
                <tr key={i}>
                  <td>{r.date}</td><td><span className="number">{r.number}</span></td>
                  <td>{r.vehicle}</td><td>{r.driver}</td>
                  <td style={{ fontSize: 11 }}>{fmtMark(r.preTrip)}</td>
                  <td style={{ fontSize: 11 }}>{fmtMark(r.postTrip)}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 20 }}>{t('rj.nodata')}</td></tr>}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
