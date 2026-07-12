'use client';

import { useCallback, useEffect, useState, type CSSProperties, type ReactNode } from 'react';
import { md, wb, Waybill } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type CheckItem = { key: string; label: string; desc: string; icon: string };

/** Чек-лист предрейсового техосмотра — точь-в-точь по эталону. label/desc — ключи i18n. */
const CHECK_ITEMS: CheckItem[] = [
  { key: 'brakes', label: 'tech.chk.brakes', desc: 'tech.chk.brakes.d', icon: P.alert },
  { key: 'lights', label: 'tech.chk.lights', desc: 'tech.chk.lights.d', icon: P.help },
  { key: 'tires', label: 'tech.chk.tires', desc: 'tech.chk.tires.d', icon: P.car },
  { key: 'steering', label: 'tech.chk.steering', desc: 'tech.chk.steering.d', icon: P.globe },
  { key: 'fluids', label: 'tech.chk.fluids', desc: 'tech.chk.fluids.d', icon: P.chart },
  { key: 'mirrors', label: 'tech.chk.mirrors', desc: 'tech.chk.mirrors.d', icon: P.eye },
  { key: 'firstaid', label: 'tech.chk.firstaid', desc: 'tech.chk.firstaid.d', icon: P.med },
  { key: 'extinguisher', label: 'tech.chk.extinguisher', desc: 'tech.chk.extinguisher.d', icon: P.shield },
  { key: 'documents', label: 'tech.chk.documents', desc: 'tech.chk.documents.d', icon: P.doc },
  { key: 'general', label: 'tech.chk.general', desc: 'tech.chk.general.d', icon: P.wrench },
];

// Стили тумблеров «Исправно / Неисправно»
const tgBase: CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 13px', borderRadius: 8, fontSize: 12.5, fontWeight: 600, cursor: 'pointer', border: '1px solid var(--line)', fontFamily: 'inherit', background: '#fff', color: 'var(--faint)', whiteSpace: 'nowrap' };
const okActive: CSSProperties = { ...tgBase, borderColor: 'var(--green)', background: 'var(--green-050)', color: 'var(--green)' };
const badActive: CSSProperties = { ...tgBase, borderColor: 'var(--red)', background: 'var(--red-050)', color: 'var(--red)' };

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div className="k-label">{label}</div>
      <div style={{ marginTop: 7 }}>{children}</div>
    </div>
  );
}

const fmt = (s: string | null | undefined) =>
  s ? new Date(s).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

/** АРМ механика (контролёр техсостояния): предрейсовый техконтроль Т3. */
export default function TechWorkstation() {
  const { t, tType, tStatus } = useT();
  const [queue, setQueue] = useState<Waybill[]>([]);
  const [all, setAll] = useState<Waybill[]>([]);
  const [mechanics, setMechanics] = useState<Record<string, { rma: string; name: string }[]>>({});
  const [selected, setSelected] = useState<Waybill | null>(null);
  const [checks, setChecks] = useState<Record<string, boolean>>({});
  const [defects, setDefects] = useState('');
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [search, setSearch] = useState('');

  const shownQueue = queue.filter(w => {
    const s = search.trim().toLowerCase();
    if (!s) return true;
    return [w.number, w.vehicleRegNumber, w.driverRma, w.vehicleSnapshot?.brand, w.driverSnapshot?.fullName, w.organizationSnapshot?.name]
      .map(x => String(x ?? '').toLowerCase()).join(' ').includes(s);
  });

  const reload = useCallback(async () => {
    const list = await wb.list();
    setAll(list);
    setQueue(list.filter(w => w.status === 'CREATED' && !w.techPassed));
  }, []);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);

  async function open(w: Waybill) {
    setSelected(w);
    setChecks(Object.fromEntries(CHECK_ITEMS.map(i => [i.key, true])));
    setDefects('');
    setOk('');
    setError('');
    if (!mechanics[w.organizationRma]) {
      const list = await md.employees(w.organizationRma);
      setMechanics(m => ({
        ...m,
        [w.organizationRma]: list.filter(e => e.type === 2).map(e => ({ rma: String(e.rma), name: String(e.name) })),
      }));
    }
  }

  async function decide(passed: boolean) {
    if (!selected) return;
    const mechanic = mechanics[selected.organizationRma]?.[0];
    if (!mechanic) { setError(t('tech.err.nomechanic')); return; }
    setError('');
    try {
      const checklist: Record<string, string> = {};
      for (const item of CHECK_ITEMS) checklist[item.key] = checks[item.key] ? 'OK' : 'НЕИСПРАВНО';
      if (defects) checklist['notes'] = defects;
      await wb.post(`/${selected.id}/confirm-tech`, { employeeRma: mechanic.rma, passed, checklist });
      setOk(passed
        ? `${t('tech.ok.pre')} ${selected.vehicleRegNumber} ${t('tech.ok.post')} (${t('role.MECHANIC')} ${mechanic.name})`
        : `${t('tech.deny.pre')} ${selected.vehicleRegNumber} ${t('tech.deny.post')}`);
      setSelected(null);
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const banners = (
    <>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}
    </>
  );

  // ============================ РАБОЧИЙ ЭКРАН ОСМОТРА ============================
  if (selected) {
    const w = selected;
    const v = (w.vehicleSnapshot ?? {}) as Record<string, unknown>;
    const dr = (w.driverSnapshot ?? {}) as Record<string, unknown>;
    const org = (w.organizationSnapshot ?? {}) as Record<string, unknown>;
    const cats = Array.isArray(dr.licenseCategories)
      ? (dr.licenseCategories as unknown[]).join(', ')
      : String(dr.licenseCategories ?? '—');
    const failed = CHECK_ITEMS.filter(i => checks[i.key] === false);
    // Реальная история техосмотров этого ТС — из загруженного списка ПЛ (свои по орг):
    // прошлые ПЛ того же ТС, где техконтроль состоялся (пройден или отклонён).
    const vehHistory = all
      .filter(x => x.vehicleRegNumber === w.vehicleRegNumber && x.id !== w.id && (x.techPassed || x.status === 'TECH_REJECTED'))
      .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
      .slice(0, 10);
    const prevExam = vehHistory[0];

    return (
      <>
        <div style={{ display: 'flex', alignItems: 'flex-start', marginBottom: 4, gap: 14 }}>
          <button className="btn secondary" onClick={() => setSelected(null)} style={{ flex: 'none' }}>
            <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('act.back')}
          </button>
          <div>
            <h1>{t('tech.exam.h')}</h1>
            <div className="tb-crumb" style={{ fontSize: 12.5, color: 'var(--muted)' }}>
              {t('tech.crumb')}
            </div>
          </div>
          <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 16, alignItems: 'center', color: 'var(--muted)', fontSize: 12.5 }}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><Icon d={P.doc} style={{ width: 15, height: 15 }} /> {fmt(w.validFrom ?? w.createdAt)}</span>
          </span>
        </div>
        <div style={{ marginTop: 16 }}>{banners}</div>

        {/* Карточка-сводка */}
        <div className="card">
          <div style={{ display: 'grid', gridTemplateColumns: 'auto 1.2fr 1.2fr 1.5fr 1.1fr 1fr', gap: 22, alignItems: 'start' }}>
            <Field label={t('col.vehiclefull')}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'flex-start' }}>
                <span className="plate">
                  <span className="p-main">{w.vehicleRegNumber}</span>
                  <span className="p-reg" style={{ lineHeight: 1 }}>TJ</span>
                </span>
              </div>
            </Field>
            <Field label={t('tech.f.brandmodel')}>
              <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 14 }}>{String(v.brand ?? '—')}</div>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 4 }}>VIN: {String(v.vincode ?? '—')}</div>
            </Field>
            <Field label={t('col.company')}>
              <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 14 }}>{String(org.name ?? w.organizationRma)}</div>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 4 }}>{t('tech.code')}: {String(org.code ?? w.organizationRma)}</div>
            </Field>
            <Field label={t('col.driver')}>
              <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 14 }}>{String(dr.fullName ?? w.driverRma)}</div>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 4 }}>{t('tech.license')}: {String(dr.licenseNumber ?? '—')}</div>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 2 }}>{t('tech.categories')}: {cats}</div>
            </Field>
            <Field label={t('tech.f.waybill')}>
              <div className="number">{w.number ?? '—'}</div>
              <div style={{ marginTop: 6 }}><span className="badge blue">{tStatus(w.status)}</span></div>
            </Field>
            <Field label={t('tech.f.examdate')}>
              <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 14 }}>{fmt(w.validFrom ?? w.createdAt)}</div>
            </Field>
          </div>
        </div>

        {/* Строка статусов */}
        <div className="card" style={{ padding: '16px 22px' }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 22 }}>
            <Field label={t('tech.f.examstatus')}><span className="badge blue">{t('st.inprocess')}</span></Field>
            <Field label={t('tech.f.prevexam')}>
              {prevExam
                ? <span style={{ fontSize: 12.5, display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                    <span className={`badge ${prevExam.techPassed ? 'green' : 'red'}`}>{prevExam.techPassed ? t('st.serviceable') : t('tech.state.faulty')}</span>
                    <span style={{ color: 'var(--muted)' }}>{fmt(prevExam.validFrom ?? prevExam.createdAt)}</span>
                  </span>
                : <span style={{ color: 'var(--muted)' }}>{t('tech.noprev')}</span>}
            </Field>
            <Field label={t('tech.f.techstate')}><span className={`badge ${failed.length ? 'red' : 'green'}`}>{failed.length ? t('tech.state.faulty') : t('st.serviceable')}</span></Field>
            <Field label={t('tech.f.admission')}><span className="badge gray">{t('st.notissued')}</span></Field>
          </div>
        </div>

        {/* Двухколоночная раскладка */}
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 320px', gap: 18, alignItems: 'start' }}>
          {/* Левая колонка */}
          <div>
            <div className="card">
              <div className="card-h">
                <h2>{t('tech.results')}</h2>
                <span style={{ marginLeft: 'auto', display: 'flex', gap: 18, fontSize: 12.5 }}>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, color: 'var(--green)', fontWeight: 600 }}><Icon d={P.check} style={{ width: 15, height: 15 }} /> {t('st.ok')}</span>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, color: 'var(--red)', fontWeight: 600 }}><Icon d={P.alert} style={{ width: 15, height: 15 }} /> {t('st.faulty')}</span>
                </span>
              </div>
              {CHECK_ITEMS.map((item, idx) => {
                const okState = checks[item.key] ?? true;
                return (
                  <div key={item.key} style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '12px 0', borderBottom: idx < CHECK_ITEMS.length - 1 ? '1px solid var(--line-soft)' : 'none' }}>
                    <div className="ic-blue" style={{ width: 38, height: 38, borderRadius: 10, display: 'grid', placeItems: 'center', flex: 'none' }}>
                      <Icon d={item.icon} />
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: 600, color: 'var(--ink)', fontSize: 13.5 }}>{t(item.label)}</div>
                      <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 2 }}>{t(item.desc)}</div>
                    </div>
                    <button type="button" style={okState ? okActive : tgBase} onClick={() => setChecks({ ...checks, [item.key]: true })}>
                      <Icon d={P.check} style={{ width: 14, height: 14 }} /> {t('st.ok')}
                    </button>
                    <button type="button" style={!okState ? badActive : tgBase} onClick={() => setChecks({ ...checks, [item.key]: false })}>
                      <Icon d={P.alert} style={{ width: 14, height: 14 }} /> {t('st.faulty')}
                    </button>
                    <Icon d={P.chevron} style={{ width: 18, height: 18, color: 'var(--faint)', transform: 'rotate(90deg)', flex: 'none' }} />
                  </div>
                );
              })}
            </div>

            <div className="grid-2">
              {/* Примечания механика */}
              <div className="card">
                <div className="card-h"><h2>{t('tech.notes.h')}</h2></div>
                <textarea
                  value={defects}
                  maxLength={500}
                  onChange={e => setDefects(e.target.value)}
                  placeholder={t('tech.notes.ph')}
                  style={{ width: '100%', minHeight: 120, padding: '10px 13px', border: '1px solid var(--line)', borderRadius: 'var(--radius-sm)', fontSize: 13.5, fontFamily: 'var(--sans)', color: 'var(--ink)', resize: 'vertical' }}
                />
                <div style={{ textAlign: 'right', color: 'var(--faint)', fontSize: 11.5, marginTop: 4 }}>{defects.length}/500</div>
              </div>

              {/* Выявленные неисправности */}
              <div className="card">
                <div className="card-h">
                  <h2>{t('tech.faults.h')}</h2>
                </div>
                <table>
                  <thead>
                    <tr><th>{t('col.fault')}</th><th>{t('col.needrepair')}</th><th>{t('col.actions')}</th></tr>
                  </thead>
                  <tbody>
                    {failed.length === 0 ? (
                      <tr><td colSpan={3} style={{ color: 'var(--muted)', textAlign: 'center', padding: 22 }}>{t('tech.faults.empty')}</td></tr>
                    ) : (
                      failed.map(f => (
                        <tr key={f.key}>
                          <td>{t(f.label)}</td>
                          <td><span className="badge red">{t('st.yes')}</span></td>
                          <td><span className="badge amber">{t('st.torepair')}</span></td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Кнопки решения */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 4 }}>
              <button className="btn secondary" onClick={() => setSelected(null)}>{t('btn.cancel')}</button>
              <button className="btn success" onClick={() => decide(true)}>
                <Icon d={P.check} /> {t('tech.btn.allow')}
              </button>
              <button className="btn danger" onClick={() => decide(false)}>
                <Icon d={P.alert} /> {t('tech.btn.deny')}
              </button>
            </div>
          </div>

          {/* Правая колонка — история техосмотров этого ТС (по реальным данным; появится по мере накопления) */}
          <div>
            <div className="card">
              <div className="card-h"><h2>{t('tech.hist.h')}</h2></div>
              {vehHistory.length === 0
                ? <div style={{ color: 'var(--muted)', fontSize: 13, padding: '10px 2px' }}>{t('tech.hist.empty')}</div>
                : <div style={{ display: 'flex', flexDirection: 'column' }}>
                    {vehHistory.map((h, i) => (
                      <div key={h.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '9px 0', borderBottom: i < vehHistory.length - 1 ? '1px solid var(--line-soft)' : 'none' }}>
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div className="number" style={{ fontSize: 12.5 }}>{h.number ?? '—'}</div>
                          <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{fmt(h.validFrom ?? h.createdAt)}</div>
                        </div>
                        <span className={`badge ${h.techPassed ? 'green' : 'red'}`}>{h.techPassed ? t('st.serviceable') : t('tech.state.faulty')}</span>
                      </div>
                    ))}
                  </div>}
            </div>
          </div>
        </div>
      </>
    );
  }

  // ============================ СПИСОК ОЖИДАЮЩИХ ============================
  return (
    <>
      <h1>{t('nav.tech')}</h1>
      <p className="page-lead">{t('tech.lead')}</p>
      {banners}

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        <div className="kpi">
          <div className="k-top"><div className="k-ic ic-amber"><Icon d={P.wrench} /></div></div>
          <div className="k-label">{t('tech.kpi.wait')}</div>
          <div className="k-value">{queue.length}</div>
        </div>
        <div className="kpi">
          <div className="k-top"><div className="k-ic ic-green"><Icon d={P.check} /></div></div>
          <div className="k-label">{t('tech.kpi.ok')}</div>
          <div className="k-value">{all.filter(w => w.techPassed).length}</div>
        </div>
        <div className="kpi">
          <div className="k-top"><div className="k-ic ic-red"><Icon d={P.alert} /></div></div>
          <div className="k-label">{t('tech.kpi.faulty')}</div>
          <div className="k-value">{all.filter(w => w.status === 'TECH_REJECTED').length}</div>
        </div>
        <div className="kpi">
          <div className="k-top"><div className="k-ic ic-blue"><Icon d={P.chart} /></div></div>
          <div className="k-label">{t('kpi.total')}</div>
          <div className="k-value">{all.length}</div>
        </div>
      </div>

      <div className="card">
        <div className="card-h">
          <h2>{t('tech.queue.h')}</h2>
          <span className="badge blue" style={{ marginLeft: 12 }}>{queue.length}</span>
          <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('exam.search')}
            style={{ marginLeft: 'auto', maxWidth: 320 }} />
        </div>
        <table>
          <thead>
            <tr><th>{t('col.vehiclenum')}</th><th>{t('col.brand')}</th><th>{t('col.wbtype')}</th><th>{t('col.org')}</th><th></th></tr>
          </thead>
          <tbody>
            {shownQueue.map(w => (
              <tr key={w.id}>
                <td><span className="plate" style={{ transform: 'scale(.9)', transformOrigin: 'left center' }}><span className="p-main">{w.vehicleRegNumber}</span><span className="p-reg">01</span></span></td>
                <td>{String(w.vehicleSnapshot?.brand ?? '—')}</td>
                <td>{tType(w.waybillType)}</td>
                <td>{String(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                <td style={{ textAlign: 'right' }}><button className="btn" onClick={() => open(w)}>{t('tech.btn.check')}</button></td>
              </tr>
            ))}
            {shownQueue.length === 0 && (
              <tr><td colSpan={5} style={{ color: 'var(--muted)', textAlign: 'center', padding: 20 }}>{search.trim() ? t('exam.search.empty') : t('tech.queue.empty')}</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
