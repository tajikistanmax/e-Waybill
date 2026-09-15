'use client';

import { Fragment, useEffect, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { md, wb, Title, Waybill } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';
import { CHECK_GROUPS, CHECK_ITEMS, METER_FIELDS, TITLE_META_KEYS } from '../checklist';

const fmt = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

const fmtDate = (s: unknown) => {
  const v = String(s ?? '').trim();
  if (!v) return '—';
  const d = new Date(v);
  return isNaN(d.getTime()) ? v : d.toLocaleDateString('ru-RU');
};

/** Дней до даты (отрицательное — просрочено). null, если даты нет или она не разбирается. */
function daysLeft(value: unknown): number | null {
  const v = String(value ?? '').trim();
  if (!v) return null;
  const d = new Date(v);
  if (isNaN(d.getTime())) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((d.getTime() - today.getTime()) / 86400000);
}

type Cond = { tone: 'green' | 'amber' | 'red'; label: string; note: string };

/**
 * Состояние ТС — вычисляемое, отдельной колонки в справочнике нет. Порядок важен:
 * отказ последнего техконтроля перевешивает сроки документов (машина физически неисправна),
 * дальше просроченный документ, дальше — предупреждение за 30 дней до окончания.
 */
function condition(veh: Record<string, unknown> | null, lastTechPassed: boolean | null,
                   t: (k: string) => string): Cond {
  if (lastTechPassed === false) {
    return { tone: 'red', label: t('tech.cond.bad'), note: t('tech.cond.bad.note') };
  }
  const docs: [string, unknown][] = [
    [t('fleet.f.tech'), veh?.techInspectionValidTo],
    [t('fleet.f.insurance'), veh?.insuranceValidTo],
    [t('fleet.f.card'), veh?.controlCardValidTo],
  ];
  const expired = docs.filter(([, v]) => (daysLeft(v) ?? 1) < 0).map(([n]) => n);
  if (expired.length) {
    return { tone: 'red', label: t('tech.cond.docs'), note: `${expired.join(', ')} — ${t('tech.cond.expired')}` };
  }
  const soon = docs.filter(([, v]) => { const d = daysLeft(v); return d != null && d >= 0 && d <= 30; }).map(([n]) => n);
  if (soon.length) {
    return { tone: 'amber', label: t('tech.cond.warn'), note: `${soon.join(', ')} — ${t('tech.cond.soon')}` };
  }
  return { tone: 'green', label: t('tech.cond.ok'), note: '' };
}

/** Усечённый отпечаток подписи титула — то же представление, что на бланке и в отчётном журнале. */
function fingerprint(signature: string | undefined): string {
  const hex = String(signature ?? '').replace(/^(sha256|sha512|sha1|cades|stub)[:-]?/i, '').replace(/[^A-Fa-f0-9]/g, '').toUpperCase();
  if (!hex) return '—';
  return hex.length <= 12 ? hex : hex.slice(-12);
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div style={{ marginTop: 18 }}>
      <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 8 }}>{title}</div>
      {children}
    </div>
  );
}

/** Журнал техконтроля механика — отдельная страница (пункт бокового меню).
 *  Реальные проведённые осмотры (ПЛ организации с пройденным/отклонённым техконтролем). */
export default function TechJournalPage() {
  const { t } = useT();
  const [all, setAll] = useState<Waybill[]>([]);
  const [q, setQ] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  // Карточка осмотра: сам ПЛ, титул Т3 (чек-лист и подпись) и реквизиты ТС из справочника.
  const [view, setView] = useState<Waybill | null>(null);
  const [t3, setT3] = useState<Title | null>(null);
  const [veh, setVeh] = useState<Record<string, unknown> | null>(null);
  const [viewBusy, setViewBusy] = useState(false);

  useEffect(() => {
    wb.list().then(setAll).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }, []);

  // Титул Т3 и карточка ТС подгружаются только на открытие — списку они не нужны.
  useEffect(() => {
    if (!view) { setT3(null); setVeh(null); return; }
    let alive = true;
    setViewBusy(true);
    const reg = view.vehicleRegNumber.trim().toUpperCase();
    Promise.all([
      wb.titles(view.id).catch(() => [] as Title[]),
      md.searchVehicles(view.organizationRma, view.vehicleRegNumber, 3).catch(() => [] as Record<string, unknown>[]),
    ]).then(([titles, list]) => {
      if (!alive) return;
      // Последний Т3: осмотр могли переподписать, актуальна замыкающая отметка.
      setT3(titles.filter(x => x.titleType === 'T3').slice(-1)[0] ?? null);
      setVeh(list.find(v => String(v.registrationNumber ?? '').toUpperCase() === reg) ?? list[0] ?? null);
    }).finally(() => { if (alive) setViewBusy(false); });
    return () => { alive = false; };
  }, [view]);

  const journal = all.filter(w => w.techPassed || w.status === 'TECH_REJECTED');

  const rows = journal
    .filter(w => {
      const s = q.trim().toLowerCase();
      if (!s) return true;
      return [w.number, w.vehicleRegNumber, w.vehicleSnapshot?.brand, w.organizationSnapshot?.name]
        .map(x => String(x ?? '').toLowerCase()).join(' ').includes(s);
    })
    .sort((a, b) => String(b.validFrom ?? b.createdAt).localeCompare(String(a.validFrom ?? a.createdAt)));

  // История техконтроля открытого ТС и его последний результат — для «Состояния».
  const vehHistory = view
    ? journal.filter(x => x.vehicleRegNumber === view.vehicleRegNumber)
        .sort((a, b) => String(b.validFrom ?? b.createdAt).localeCompare(String(a.validFrom ?? a.createdAt)))
    : [];
  const cond = condition(veh, vehHistory.length ? Boolean(vehHistory[0].techPassed) : null, t);

  const data = (t3?.data ?? {}) as Record<string, unknown>;
  const hasChecklist = CHECK_ITEMS.some(i => data[i.key] != null);
  const meters = METER_FIELDS.filter(m => String(data[m.key] ?? '').trim() !== '');
  // Ключи, которых нет ни в чек-листе, ни в замерах, ни в служебных — осмотры прошлых версий.
  const extra = Object.entries(data).filter(([k, v]) =>
    !TITLE_META_KEYS.has(k) && !CHECK_ITEMS.some(i => i.key === k) && !METER_FIELDS.some(m => m.key === k)
    && v != null && String(v).trim() !== '');

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('tech.journal.h')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('tech.lead')}</div>
        </div>
        <Link href="/tech" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('act.back')}
        </Link>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="card">
        <div className="card-h">
          <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, margin: 0 }}>
            <Icon d={P.book} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} /> {t('tech.journal.h')}
          </h2>
          <span className="badge blue" style={{ marginLeft: 12 }}>{rows.length}</span>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('exam.search')} style={{ marginLeft: 'auto', maxWidth: 320 }} />
        </div>
        <table>
          <thead>
            <tr>
              <th>{t('col.vehiclenum')}</th><th>{t('col.brand')}</th><th>{t('col.company')}</th>
              <th>{t('col.wbnum')}</th><th>{t('col.datetime')}</th><th>{t('col.result')}</th>
              <th style={{ textAlign: 'right' }}>{t('col.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(w => (
              <tr key={w.id}>
                <td><span className="number" style={{ fontFamily: 'var(--mono)', fontWeight: 700 }}>{w.vehicleRegNumber}</span></td>
                <td>{String(w.vehicleSnapshot?.brand ?? '—')}</td>
                <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                <td><span className="number">{w.number ?? '—'}</span></td>
                <td>{fmt(w.validFrom ?? w.createdAt)}</td>
                <td><span className={`badge ${w.techPassed ? 'green' : 'red'}`}>{w.techPassed ? t('st.serviceable') : t('tech.state.faulty')}</span></td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  <button className="btn secondary" style={{ padding: '6px 12px' }} onClick={() => setView(w)}>
                    <Icon d={P.eye} cls="" style={{ width: 15, height: 15 }} /> {t('tech.j.view')}
                  </button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && !loading && (
              <tr><td colSpan={7} style={{ color: 'var(--muted)', textAlign: 'center', padding: 26 }}>{q.trim() ? t('exam.search.empty') : t('tech.journal.empty')}</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Карточка осмотра: что именно проверил механик, чем подписал, и профиль ТС с историей. */}
      {view && (
        <div onClick={() => setView(null)} style={{ position: 'fixed', inset: 0, background: 'rgba(15,27,52,.45)', display: 'grid', placeItems: 'center', zIndex: 60, padding: 20 }}>
          <div className="card" onClick={e => e.stopPropagation()} style={{ width: 720, maxWidth: '100%', margin: 0, maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="card-h">
              <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, margin: 0 }}>
                <Icon d={P.wrench} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} /> {t('tech.j.card')}
              </h2>
              <button onClick={() => setView(null)} aria-label={t('btn.close')} style={{ marginLeft: 'auto', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--muted)', fontSize: 16 }}>✕</button>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <span className="plate"><span className="p-main">{view.vehicleRegNumber}</span><span className="p-reg">01</span></span>
              <div style={{ fontWeight: 700, fontSize: 15 }}>{String(veh?.brand ?? view.vehicleSnapshot?.brand ?? '—')}</div>
              <span className={`badge ${view.techPassed ? 'green' : 'red'}`} style={{ marginLeft: 'auto' }}>
                {view.techPassed ? t('st.serviceable') : t('tech.state.faulty')}
              </span>
            </div>

            <Section title={t('tech.j.verdict')}>
              <dl className="kv" style={{ gridTemplateColumns: '180px 1fr', gap: '7px 12px' }}>
                <dt>{t('col.wbnum')}</dt><dd><span className="number">{view.number ?? '—'}</span></dd>
                <dt>{t('col.datetime')}</dt><dd>{fmt(view.validFrom ?? view.createdAt)}</dd>
                <dt>{t('tech.j.signer')}</dt><dd>{String(data.employeeName ?? t3?.signerRma ?? '—')}</dd>
                <dt>{t('tech.j.signedat')}</dt><dd>{fmt(t3?.signedAt)}</dd>
                <dt>{t('tech.j.fingerprint')}</dt><dd style={{ fontFamily: 'var(--mono)' }}>{fingerprint(t3?.signature)}</dd>
              </dl>
            </Section>

            <Section title={t('tech.j.meters')}>
              {viewBusy ? <div style={{ color: 'var(--muted)', fontSize: 13 }}>…</div>
                : meters.length === 0
                  ? <div style={{ color: 'var(--muted)', fontSize: 13 }}>{t('tech.j.nometers')}</div>
                  : <dl className="kv" style={{ gridTemplateColumns: '180px 1fr', gap: '7px 12px' }}>
                      {meters.map(m => (
                        <Fragment key={m.key}>
                          <dt>{t(m.label)}</dt>
                          <dd>{String(data[m.key])}</dd>
                        </Fragment>
                      ))}
                    </dl>}
              {view.odometerExit != null && (
                <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 6 }}>
                  {t('tech.j.odoexit')}: {Number(view.odometerExit).toLocaleString('ru-RU')} км
                </div>
              )}
            </Section>

            <Section title={t('tech.j.checklist')}>
              {viewBusy ? <div style={{ color: 'var(--muted)', fontSize: 13 }}>…</div>
                : !hasChecklist
                  ? <div style={{ color: 'var(--muted)', fontSize: 13 }}>{t('tech.j.nochecklist')}</div>
                  : CHECK_GROUPS.map(group => (
                      <div key={group.title} style={{ marginBottom: 10 }}>
                        <div style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--faint)', textTransform: 'uppercase', letterSpacing: '.04em', padding: '6px 0 4px' }}>
                          {t(group.title)}
                        </div>
                        {group.keys.map(key => {
                          const item = CHECK_ITEMS.find(i => i.key === key)!;
                          const raw = data[key];
                          if (raw == null) return null;
                          const good = String(raw).toUpperCase() === 'OK';
                          return (
                            <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '5px 0' }}>
                              <div title={t(item.desc)} style={{ flex: 1, minWidth: 0, fontSize: 13 }}>{t(item.label)}</div>
                              <span className={`badge ${good ? 'green' : 'red'}`}>{good ? t('st.ok') : t('st.faulty')}</span>
                            </div>
                          );
                        })}
                      </div>
                    ))}
              {extra.length > 0 && (
                <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 6 }}>
                  {extra.map(([k, v]) => `${k}=${v}`).join('; ')}
                </div>
              )}
            </Section>

            {String(data.notes ?? '').trim() && (
              <Section title={t('tech.j.notes')}>
                <div style={{ fontSize: 13, whiteSpace: 'pre-wrap' }}>{String(data.notes)}</div>
              </Section>
            )}

            <Section title={t('tech.j.profile')}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 18 }}>
                <dl className="kv" style={{ gridTemplateColumns: '130px 1fr', gap: '7px 12px' }}>
                  <dt>{t('fleet.f.vin')}</dt><dd>{String(veh?.vincode ?? '—')}</dd>
                  <dt>{t('fleet.f.year')}</dt><dd>{String(veh?.yearManufacture ?? '—')}</dd>
                  <dt>{t('fleet.f.fueltype')}</dt>
                  <dd>{veh?.fuelType != null ? t(`fuel.type.${veh.fuelType}`) : '—'}</dd>
                  <dt>{t('fleet.f.enginepower')}</dt><dd>{String(veh?.enginePower ?? '—')}</dd>
                  <dt>{t('fleet.f.capacity')}</dt><dd>{String(veh?.capacity ?? '—')}</dd>
                  <dt>{t('fleet.f.carrying')}</dt><dd>{String(veh?.carrying ?? '—')}</dd>
                  <dt>{t('tech.details.odometer')}</dt>
                  <dd>{veh?.odometer != null ? `${Number(veh.odometer).toLocaleString('ru-RU')} км` : '—'}</dd>
                </dl>
                <dl className="kv" style={{ gridTemplateColumns: '130px 1fr', gap: '7px 12px' }}>
                  <dt>{t('fleet.f.tech')}</dt><dd>{fmtDate(veh?.techInspectionValidTo)}</dd>
                  <dt>{t('fleet.f.insurance')}</dt><dd>{fmtDate(veh?.insuranceValidTo)}</dd>
                  <dt>{t('fleet.f.card')}</dt><dd>{fmtDate(veh?.controlCardValidTo)}</dd>
                  <dt>{t('tech.j.condition')}</dt>
                  <dd>
                    <span className={`badge ${cond.tone}`}>{cond.label}</span>
                    {cond.note && <div style={{ color: 'var(--muted)', fontSize: 11.5, marginTop: 4 }}>{cond.note}</div>}
                  </dd>
                </dl>
              </div>
            </Section>

            <Section title={t('tech.j.history')}>
              {vehHistory.length === 0
                ? <div style={{ color: 'var(--muted)', fontSize: 13 }}>{t('tech.details.nohistory')}</div>
                : <table>
                    <thead><tr><th>{t('col.datetime')}</th><th>{t('col.wbnum')}</th><th>{t('col.result')}</th></tr></thead>
                    <tbody>
                      {vehHistory.slice(0, 15).map(h => (
                        <tr key={h.id} style={h.id === view.id ? { background: 'var(--blue-050)' } : undefined}>
                          <td>{fmt(h.validFrom ?? h.createdAt)}</td>
                          <td><span className="number">{h.number ?? '—'}</span></td>
                          <td><span className={`badge ${h.techPassed ? 'green' : 'red'}`}>{h.techPassed ? t('st.serviceable') : t('tech.state.faulty')}</span></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>}
            </Section>

            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
              <button className="btn secondary" onClick={() => setView(null)}>{t('btn.close')}</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
