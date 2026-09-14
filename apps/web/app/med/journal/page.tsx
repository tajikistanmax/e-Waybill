'use client';

import { useEffect, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { md, wb, Waybill } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

const dt = (iso: string) =>
  iso ? new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

const fmtDate = (v: unknown) => {
  const s = String(v ?? '').trim();
  if (!s) return '—';
  const d = new Date(s);
  return isNaN(d.getTime()) ? s : d.toLocaleDateString('ru-RU');
};

/** Дней до даты (отрицательное — просрочено); null, если даты нет. */
function daysLeft(v: unknown): number | null {
  const s = String(v ?? '').trim();
  if (!s) return null;
  const d = new Date(s);
  if (isNaN(d.getTime())) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.round((d.getTime() - today.getTime()) / 86400000);
}

/** Запись медосмотра — титул Т2/Т6 путевого листа. Показатели тут НЕ лежат: они зашифрованы
 *  (indicatorsEnc) и расшифровываются отдельным аудируемым вызовом в профиле водителя. */
type MedRecord = {
  waybillId: string; titleType: string; number: string | null;
  driver: string; driverRma: string; orgRma: string; org: string;
  date: string; verdict: string; medic: string; passed: boolean;
};

/** Расшифрованные показатели одного осмотра. */
type Vitals = { pressure: string; pulse: string; temperature: string; alcotest: string };

async function medRecordsOf(w: Waybill): Promise<MedRecord[]> {
  try {
    const titles = await wb.titles(w.id);
    return titles.filter(t => t.titleType === 'T2' || t.titleType === 'T6').map(t => {
      const d = (t.data ?? {}) as Record<string, unknown>;
      const verdict = String(d.verdict ?? (w.medPassed ? 'ДОПУЩЕН' : '—'));
      return {
        waybillId: w.id, titleType: t.titleType, number: w.number,
        driver: String(w.driverSnapshot?.fullName ?? w.driverRma),
        driverRma: w.driverRma, orgRma: w.organizationRma,
        org: String(w.organizationSnapshot?.name ?? w.organizationRma),
        date: t.signedAt, verdict, medic: String(d.employeeName ?? '—'),
        passed: !verdict.toUpperCase().includes('НЕ'),
      };
    });
  } catch { return []; }
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div style={{ marginTop: 18 }}>
      <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 8 }}>{title}</div>
      {children}
    </div>
  );
}

/** Строка «срок документа» с подсветкой: просрочен — красным, ближе 30 дней — янтарным. */
function DocDate({ value }: { value: unknown }) {
  const d = daysLeft(value);
  const tone = d == null ? undefined : d < 0 ? 'var(--red)' : d <= 30 ? 'var(--amber)' : undefined;
  return <span style={{ color: tone, fontWeight: tone ? 600 : undefined }}>{fmtDate(value)}</span>;
}

/**
 * История медосмотров — отдельная страница кабинета врача (пункт бокового меню).
 * Реальные проведённые осмотры (титулы Т2/Т6) и профиль водителя по кнопке действия.
 */
export default function MedJournalPage() {
  const { t } = useT();
  const [all, setAll] = useState<Waybill[]>([]);
  const [exams, setExams] = useState<MedRecord[] | null>(null);
  const [q, setQ] = useState('');
  const [error, setError] = useState('');
  // Профиль водителя: справочная карточка + его осмотры + расшифрованные показатели.
  const [profile, setProfile] = useState<MedRecord | null>(null);
  const [driver, setDriver] = useState<Record<string, unknown> | null>(null);
  const [driverBusy, setDriverBusy] = useState(false);
  const [vitals, setVitals] = useState<Record<string, Vitals>>({});
  const [vitalsBusy, setVitalsBusy] = useState(false);
  const [vitalsErr, setVitalsErr] = useState('');

  useEffect(() => {
    wb.list()
      .then(async list => {
        setAll(list);
        const completed = list.filter(x => x.medPassed || x.status === 'MED_REJECTED').slice(0, 100);
        const rs = await Promise.all(completed.map(medRecordsOf));
        setExams(rs.flat().sort((a, b) => b.date.localeCompare(a.date)));
      })
      .catch((e: Error) => { setError(e.message); setExams([]); });
  }, []);

  // Справочная карточка водителя — только на открытие профиля.
  useEffect(() => {
    if (!profile) { setDriver(null); setVitals({}); setVitalsErr(''); return; }
    let alive = true;
    setDriverBusy(true);
    md.searchDrivers(profile.orgRma, profile.driverRma, 5)
      .then(list => {
        if (!alive) return;
        setDriver(list.find(d => String(d.rma ?? '') === profile.driverRma) ?? list[0] ?? null);
      })
      .catch(() => { if (alive) setDriver(null); })
      .finally(() => { if (alive) setDriverBusy(false); });
    return () => { alive = false; };
  }, [profile]);

  /**
   * Группа наблюдения — вычисляемая, отдельного поля в справочнике нет.
   * Риск: у водителя есть НЕзакрытый отказ (ПЛ всё ещё в MED_REJECTED).
   * Наблюдение: отказ был за последние 30 дней, но сейчас активных нет.
   */
  function group(driverRma: string): { tone: 'blue' | 'amber' | 'red'; label: string } {
    const own = all.filter(w => w.driverRma === driverRma);
    if (own.some(w => w.status === 'MED_REJECTED')) return { tone: 'red', label: t('med.grp.risk') };
    const recentFail = (exams ?? []).some(r =>
      r.driverRma === driverRma && !r.passed && (daysLeft(r.date) ?? -999) >= -30);
    return recentFail ? { tone: 'amber', label: t('med.grp.watch') } : { tone: 'blue', label: t('med.grp.norm') };
  }

  const rows = (exams ?? []).filter(r => {
    const s = q.trim().toLowerCase();
    return !s || r.driver.toLowerCase().includes(s) || (r.number ?? '').toLowerCase().includes(s) || r.driverRma.includes(s);
  });

  // Осмотры открытого водителя за последние 30 дней (как в эталоне), но не меньше 5 последних —
  // иначе у редко выезжающего водителя история была бы пустой.
  const profileExams = profile
    ? (() => {
        const own = (exams ?? []).filter(r => r.driverRma === profile.driverRma);
        const last30 = own.filter(r => (daysLeft(r.date) ?? -999) >= -30);
        return (last30.length >= 5 ? last30 : own.slice(0, 5)).slice(0, 15);
      })()
    : [];

  /** Расшифровка показателей — явное действие врача: каждый вызов пишется в аудит. */
  async function revealVitals() {
    setVitalsBusy(true); setVitalsErr('');
    try {
      const got: Record<string, Vitals> = {};
      for (const r of profileExams) {
        const key = `${r.waybillId}:${r.titleType}`;
        if (vitals[key]) { got[key] = vitals[key]; continue; }
        const d = await wb.medIndicators(r.waybillId, r.titleType).catch(() => ({} as Record<string, unknown>));
        got[key] = {
          pressure: String(d.pressure ?? '—'), pulse: String(d.pulse ?? '—'),
          temperature: String(d.temperature ?? '—'), alcotest: String(d.alcotest ?? '—'),
        };
      }
      setVitals(v => ({ ...v, ...got }));
    } catch (e) { setVitalsErr((e as Error).message); }
    finally { setVitalsBusy(false); }
  }

  const revealed = profileExams.length > 0 && profileExams.every(r => vitals[`${r.waybillId}:${r.titleType}`]);

  /** Возраст на сегодня по дате рождения из справочника (полных лет). */
  const age = (() => {
    const b = String(driver?.birthDate ?? '').trim();
    if (!b) return null;
    const d = new Date(b);
    if (isNaN(d.getTime())) return null;
    const now = new Date();
    let y = now.getFullYear() - d.getFullYear();
    const m = now.getMonth() - d.getMonth();
    if (m < 0 || (m === 0 && now.getDate() < d.getDate())) y--;
    return y >= 0 && y < 130 ? y : null;
  })();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('med.history')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('med.lead')}</div>
        </div>
        <Link href="/med" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('act.back')}
        </Link>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="card">
        <div className="card-h">
          <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, margin: 0 }}>
            <Icon d={P.book} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} /> {t('med.history')}
          </h2>
          <span className="badge blue" style={{ marginLeft: 12 }}>{rows.length}</span>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('med.search.ph')} style={{ marginLeft: 'auto', maxWidth: 320 }} />
        </div>
        {exams === null ? (
          <p style={{ color: 'var(--muted)', fontSize: 13, padding: 12 }}>{t('med.loading.history')}</p>
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>{t('col.datetime')}</th><th>{t('col.wbnum')}</th><th>{t('col.driver')}</th>
                  <th>{t('med.col.group')}</th><th>{t('col.company')}</th><th>{t('role.DOCTOR')}</th>
                  <th>{t('col.result')}</th><th style={{ textAlign: 'right' }}>{t('col.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => {
                  const g = group(r.driverRma);
                  return (
                    <tr key={`${r.waybillId}:${r.titleType}:${i}`}>
                      <td>{dt(r.date)}</td>
                      <td><span className="number">{r.number ?? '—'}</span></td>
                      <td>
                        <div>{r.driver}</div>
                        <div style={{ color: 'var(--muted)', fontSize: 11.5, fontFamily: 'var(--mono)' }}>{r.driverRma}</div>
                      </td>
                      <td><span className={`badge ${g.tone}`}>{g.label}</span></td>
                      <td>{r.org}</td>
                      <td>{r.medic}</td>
                      <td><span className={`badge ${r.passed ? 'green' : 'red'}`}>{r.passed ? t('st.passed') : t('st.failed')}</span></td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        <button className="btn secondary" style={{ padding: '6px 12px' }} onClick={() => setProfile(r)}>
                          <Icon d={P.eye} cls="" style={{ width: 15, height: 15 }} /> {t('med.j.profile.btn')}
                        </button>
                      </td>
                    </tr>
                  );
                })}
                {rows.length === 0 && (
                  <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 28 }}>{t('med.empty.history')}</td></tr>
                )}
              </tbody>
            </table>
            <div style={{ marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>{t('med.shown.pre')} {rows.length} {t('med.shown.post')}</div>
          </>
        )}
      </div>

      {/* Профиль водителя: справочные данные, сроки допусков и история осмотров. */}
      {profile && (
        <div onClick={() => setProfile(null)} style={{ position: 'fixed', inset: 0, background: 'rgba(15,27,52,.45)', display: 'grid', placeItems: 'center', zIndex: 60, padding: 20 }}>
          <div className="card" onClick={e => e.stopPropagation()} style={{ width: 760, maxWidth: '100%', margin: 0, maxHeight: '90vh', overflowY: 'auto' }}>
            <div className="card-h">
              <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, margin: 0 }}>
                <Icon d={P.user} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} /> {t('med.j.profile.h')}
              </h2>
              <button onClick={() => setProfile(null)} aria-label={t('btn.close')} style={{ marginLeft: 'auto', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--muted)', fontSize: 16 }}>✕</button>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <div style={{ fontWeight: 700, fontSize: 16 }}>{profile.driver}</div>
              <span className={`badge ${group(profile.driverRma).tone}`}>{group(profile.driverRma).label}</span>
              {Boolean(driver?.suspended) && <span className="badge red">{t('med.j.suspended')}</span>}
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 18, marginTop: 4 }}>
              <Section title={t('med.j.general')}>
                <dl className="kv" style={{ gridTemplateColumns: '140px 1fr', gap: '7px 12px' }}>
                  <dt>{t('med.j.rma')}</dt><dd style={{ fontFamily: 'var(--mono)' }}>{profile.driverRma}</dd>
                  <dt>{t('med.j.age')}</dt>
                  <dd>
                    {age == null ? '—' : `${age} ${t('med.j.years')}`}
                    {age != null && age < 18 && <span className="badge red" style={{ marginLeft: 8 }}>{t('med.j.minor')}</span>}
                  </dd>
                  <dt>{t('fleet.f.experience')}</dt>
                  <dd>{driver?.experienceYears != null ? `${String(driver.experienceYears)} ${t('med.j.years')}` : '—'}</dd>
                  <dt>{t('med.j.tab')}</dt><dd>{String(driver?.tabNumber ?? '—')}</dd>
                  <dt>{t('tech.license')}</dt><dd>{String(driver?.licenseNumber ?? '—')}</dd>
                  <dt>{t('tech.categories')}</dt><dd>{String(driver?.licenseCategories ?? '—')}</dd>
                  <dt>{t('med.j.licvalid')}</dt><dd><DocDate value={driver?.licenseValidTo} /></dd>
                  <dt>{t('med.j.phone')}</dt><dd>{String(driver?.phone ?? '—')}</dd>
                </dl>
              </Section>
              <Section title={t('med.j.meddocs')}>
                <dl className="kv" style={{ gridTemplateColumns: '140px 1fr', gap: '7px 12px' }}>
                  <dt>{t('med.j.medcert')}</dt><dd>{String(driver?.medCertNumber ?? '—')}</dd>
                  <dt>{t('med.j.medcertvalid')}</dt><dd><DocDate value={driver?.medCertValidTo} /></dd>
                  <dt>{t('med.j.safety')}</dt><dd>{String(driver?.safetyCourseNumber ?? '—')}</dd>
                  <dt>{t('med.j.safetyvalid')}</dt><dd><DocDate value={driver?.safetyCourseValidTo} /></dd>
                  <dt>{t('med.j.adr')}</dt><dd><DocDate value={driver?.adrCertValidTo} /></dd>
                  {/* Медограничения из ВУ («очки обязательны» и т. п.) — то, что врач обязан
                      видеть перед допуском. Диагнозы в справочнике не хранятся. */}
                  <dt>{t('fleet.f.medrestr')}</dt>
                  <dd>{String(driver?.medRestrictions ?? '').trim()
                    ? <span style={{ color: 'var(--amber)', fontWeight: 600 }}>{String(driver?.medRestrictions)}</span>
                    : t('med.j.norestr')}</dd>
                </dl>
                {driverBusy && <div style={{ color: 'var(--muted)', fontSize: 12.5 }}>…</div>}
              </Section>
            </div>

            <Section title={t('med.j.exams')}>
              {profileExams.length === 0
                ? <div style={{ color: 'var(--muted)', fontSize: 13 }}>{t('med.noexams')}</div>
                : <>
                    <table style={{ fontSize: 12.5 }}>
                      <thead>
                        <tr>
                          <th>{t('col.datetime')}</th><th>{t('col.wbnum')}</th>
                          <th>{t('col.bp')}</th><th>{t('col.pulse')}</th><th>{t('col.temp')}</th><th>{t('col.alco')}</th>
                          <th>{t('col.verdict')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {profileExams.map(r => {
                          const v = vitals[`${r.waybillId}:${r.titleType}`];
                          const cell = (x: string | undefined) => v ? x : <span style={{ color: 'var(--faint)' }}>•••</span>;
                          return (
                            <tr key={`${r.waybillId}:${r.titleType}`}>
                              <td>{dt(r.date)}</td>
                              <td><span className="number">{r.number ?? '—'}</span></td>
                              <td style={{ fontWeight: 600 }}>{cell(v?.pressure)}</td>
                              <td>{cell(v?.pulse)}</td>
                              <td>{cell(v?.temperature)}</td>
                              <td>{cell(v?.alcotest)}</td>
                              <td><span className={`badge ${r.passed ? 'green' : 'red'}`}>{r.passed ? t('st.passed') : t('st.failed')}</span></td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                    {vitalsErr && <div className="error" style={{ marginTop: 10 }}>{vitalsErr}</div>}
                    {!revealed && (
                      <div style={{ marginTop: 12 }}>
                        <button className="btn secondary" disabled={vitalsBusy} onClick={revealVitals}>
                          {vitalsBusy ? '…' : t('med.j.reveal')}
                        </button>
                        <div className="hint" style={{ marginTop: 10, marginBottom: 0 }}>{t('med.j.reveal.note')}</div>
                      </div>
                    )}
                  </>}
            </Section>

            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
              <button className="btn secondary" onClick={() => setProfile(null)}>{t('btn.close')}</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
