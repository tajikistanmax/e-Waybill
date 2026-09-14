'use client';

import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import Link from 'next/link';
import { md, wb, Waybill, type WaybillAttachment } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';
import { CHECK_ITEMS, CHECK_GROUPS } from './checklist';

/* Локальные пути иконок для даты/времени в шапке (как в /med). */
const CAL = 'M4 5h16a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM3 9h18M8 3v4M16 3v4';
const CLK = 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2';

// Стили тумблеров «Исправно / Неисправно»
const tgBase: CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 13px', borderRadius: 8, fontSize: 12.5, fontWeight: 600, cursor: 'pointer', border: '1px solid var(--line)', fontFamily: 'inherit', background: '#fff', color: 'var(--faint)', whiteSpace: 'nowrap' };
const okActive: CSSProperties = { ...tgBase, borderColor: 'var(--green)', background: 'var(--green-050)', color: 'var(--green)' };
const badActive: CSSProperties = { ...tgBase, borderColor: 'var(--red)', background: 'var(--red-050)', color: 'var(--red)' };

const PER_PAGE = 10;

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
  const { t, tStatus } = useT();
  const [queue, setQueue] = useState<Waybill[]>([]);
  const [all, setAll] = useState<Waybill[]>([]);
  const [mechanics, setMechanics] = useState<Record<string, { rma: string; name: string }[]>>({});
  const [selected, setSelected] = useState<Waybill | null>(null);
  const [checks, setChecks] = useState<Record<string, boolean>>({});
  // Показания приборов при осмотре. Одометр уходит в Waybill.odometerExit — обязательное
  // поле бланка ПЛ («показания спидометра при выезде»); топливо и давление шин — в чек-лист Т3.
  const [meter, setMeter] = useState({ odometer: '', fuel: '', tires: '' });
  const [defects, setDefects] = useState('');
  // Фотофиксация неисправностей (Т3): фото хранятся при ПЛ через общий механизм вложений
  // (тот же эндпоинт, что и остальные вложения рейса) с видом вложения DEFECT_PHOTO.
  const [photos, setPhotos] = useState<WaybillAttachment[]>([]);
  const [photoBusy, setPhotoBusy] = useState(false);
  const [photoErr, setPhotoErr] = useState('');
  const photoRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [search, setSearch] = useState('');
  // Пагинация списков (ветка СПИСКА): очередь и «последние проверенные».
  const [queuePage, setQueuePage] = useState(1);
  const [recentPage, setRecentPage] = useState(1);
  // «Детали» ТС (модалка из очереди) и «Сообщить о неисправности».
  const [detail, setDetail] = useState<Waybill | null>(null);
  const [detailVeh, setDetailVeh] = useState<Record<string, unknown> | null>(null);
  const [reportOpen, setReportOpen] = useState(false);
  const [reportForm, setReportForm] = useState({ vehicleRegNumber: '', issueType: '', message: '' });
  const [reportBusy, setReportBusy] = useState(false);
  const [reportMsg, setReportMsg] = useState('');
  const [reportErr, setReportErr] = useState('');
  // Кто в кабинете: имя механика и его организация (тенант видит свою организацию).
  const [me, setMe] = useState<{ name: string; orgName: string } | null>(null);
  // Текущие дата/время в шапке кабинета (как в /med).
  const [now, setNow] = useState<Date | null>(null);

  const shownQueue = queue.filter(w => {
    const s = search.trim().toLowerCase();
    if (!s) return true;
    return [w.number, w.vehicleRegNumber, w.driverRma, w.vehicleSnapshot?.brand, w.driverSnapshot?.fullName, w.organizationSnapshot?.name]
      .map(x => String(x ?? '').toLowerCase()).join(' ').includes(s);
  });
  const queuePages = Math.max(1, Math.ceil(shownQueue.length / PER_PAGE));
  const queueView = shownQueue.slice((queuePage - 1) * PER_PAGE, queuePage * PER_PAGE);

  // Полные реквизиты выбранного ТС (для модалки «Детали») из справочника парка.
  useEffect(() => {
    if (!detail) { setDetailVeh(null); return; }
    let alive = true;
    md.searchVehicles(detail.organizationRma, detail.vehicleRegNumber, 3)
      .then(list => {
        if (!alive) return;
        const reg = detail.vehicleRegNumber.trim().toUpperCase();
        setDetailVeh(list.find(v => String(v.registrationNumber ?? '').toUpperCase() === reg) ?? list[0] ?? null);
      })
      .catch(() => { if (alive) setDetailVeh(null); });
    return () => { alive = false; };
  }, [detail]);

  // История техосмотров выбранного ТС (реальные ПЛ этого ТС с пройденным/отклонённым контролем).
  const detailHistory = detail
    ? all.filter(x => x.vehicleRegNumber === detail.vehicleRegNumber && (x.techPassed || x.status === 'TECH_REJECTED'))
        .sort((a, b) => String(b.validFrom ?? b.createdAt).localeCompare(String(a.validFrom ?? a.createdAt)))
        .slice(0, 10)
    : [];

  async function submitReport() {
    if (!reportForm.message.trim()) return;
    setReportBusy(true); setReportErr('');
    try {
      const veh = reportForm.vehicleRegNumber.trim();
      await wb.reportIssue({
        issueType: reportForm.issueType || t('tech.report.t.other'),
        message: (veh ? `[${veh}] ` : '') + reportForm.message.trim(),
      });
      setReportMsg(t('tech.report.sent'));
      setReportOpen(false);
      setReportForm({ vehicleRegNumber: '', issueType: '', message: '' });
    } catch (e) { setReportErr((e as Error).message); }
    finally { setReportBusy(false); }
  }

  const reload = useCallback(async () => {
    const list = await wb.list();
    setAll(list);
    setQueue(list.filter(w => w.status === 'CREATED' && !w.techPassed));
  }, []);

  useEffect(() => { reload().catch(e => setError(e.message)); }, [reload]);

  // Часы в шапке — обновляются раз в секунду (как в /med).
  useEffect(() => {
    setNow(new Date());
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);

  // Механик и его организация — для шапки кабинета (кто проводит осмотр).
  useEffect(() => {
    md.organizations().then(async orgs => {
      const o = orgs[0];
      if (!o) return;
      const emps = await md.employees(String(o.rma)).catch(() => [] as Record<string, unknown>[]);
      const mech = emps.find(e => Number(e.type) === 2);
      setMe({ name: mech ? String(mech.name) : '', orgName: String(o.name ?? o.rma) });
    }).catch(() => { /* нет связи — шапка без идентификации */ });
  }, []);

  async function open(w: Waybill) {
    setSelected(w);
    setChecks(Object.fromEntries(CHECK_ITEMS.map(i => [i.key, true])));
    setMeter({ odometer: '', fuel: '', tires: '' });
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

  // Фото неисправностей выбранного ПЛ — только вложения вида DEFECT_PHOTO.
  const loadPhotos = useCallback(() => {
    if (!selected) { setPhotos([]); return; }
    wb.attachments.list(selected.id)
      .then(list => setPhotos(list.filter(a => a.docType === 'DEFECT_PHOTO')))
      .catch(() => setPhotos([]));
  }, [selected]);
  useEffect(() => { setPhotoErr(''); loadPhotos(); }, [loadPhotos]);

  async function uploadPhoto() {
    const file = photoRef.current?.files?.[0];
    if (!selected || !file) { setPhotoErr(t('doc.selectfile')); return; }
    setPhotoBusy(true); setPhotoErr('');
    try {
      await wb.attachments.upload(selected.id, file, 'DEFECT_PHOTO', '');
      if (photoRef.current) photoRef.current.value = '';
      loadPhotos();
    } catch (e) { setPhotoErr((e as Error).message); }
    finally { setPhotoBusy(false); }
  }

  async function openPhoto(a: WaybillAttachment) {
    if (!selected) return;
    try {
      const blob = await wb.attachments.download(selected.id, a.id);
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) { setPhotoErr((e as Error).message); }
  }

  async function decide(passed: boolean) {
    if (!selected) return;
    const mechanic = mechanics[selected.organizationRma]?.[0];
    if (!mechanic) { setError(t('tech.err.nomechanic')); return; }
    // Одометр обязателен только при допуске: показания спидометра при выезде — реквизит бланка ПЛ.
    // При отклонении рейса не будет, и требовать замер бессмысленно.
    const odometer = meter.odometer.trim();
    if (passed && !odometer) { setError(t('tech.err.odometer')); return; }
    setError('');
    try {
      const checklist: Record<string, string> = {};
      for (const item of CHECK_ITEMS) checklist[item.key] = checks[item.key] ? 'OK' : 'НЕИСПРАВНО';
      if (odometer) checklist['odometer'] = odometer;
      if (meter.fuel.trim()) checklist['fuelPercent'] = meter.fuel.trim();
      if (meter.tires.trim()) checklist['tirePressureBar'] = meter.tires.trim();
      if (defects) checklist['notes'] = defects;
      await wb.post(`/${selected.id}/confirm-tech`, {
        employeeRma: mechanic.rma,
        passed,
        checklist,
        odometerExit: odometer ? Math.trunc(Number(odometer)) : null,
      });
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

              {/* Показания приборов — снимаются механиком у машины. Одометр обязателен для допуска. */}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14, paddingBottom: 16, borderBottom: '1px solid var(--line-soft)' }}>
                <div>
                  <label htmlFor="tech-odometer">{t('tech.m.odometer')}</label>
                  <input id="tech-odometer" type="number" min={0} step={1} inputMode="numeric"
                    value={meter.odometer} onChange={e => setMeter({ ...meter, odometer: e.target.value })} placeholder="45250" />
                </div>
                <div>
                  <label htmlFor="tech-fuel">{t('tech.m.fuel')}</label>
                  <input id="tech-fuel" type="number" min={0} max={100} step={1} inputMode="numeric"
                    value={meter.fuel} onChange={e => setMeter({ ...meter, fuel: e.target.value })} placeholder="75" />
                </div>
                <div>
                  <label htmlFor="tech-tires">{t('tech.m.tires')}</label>
                  <input id="tech-tires" type="number" min={0} step={0.1} inputMode="decimal"
                    value={meter.tires} onChange={e => setMeter({ ...meter, tires: e.target.value })} placeholder="2.5" />
                </div>
              </div>

              {/* Группы узлов — каждая отдельным блоком. Две в ряд, последняя (комплектация)
                  во всю ширину: строк в ней больше и подписи длиннее. На узком экране
                  auto-fit сам сложит блоки в одну колонку. */}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 14, paddingTop: 16 }}>
                {CHECK_GROUPS.map((group, gi) => {
                  const isLast = gi === CHECK_GROUPS.length - 1;
                  const badCount = group.keys.filter(k => checks[k] === false).length;
                  return (
                    <div key={group.title} style={{
                      border: '1px solid var(--line)', borderRadius: 11, padding: '12px 15px 14px',
                      background: 'var(--surface)', gridColumn: isLast ? '1 / -1' : undefined,
                      minWidth: 0,
                    }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingBottom: 4 }}>
                        <span style={{
                          fontSize: 11.5, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.04em',
                          color: isLast ? 'var(--blue-600)' : 'var(--muted)',
                        }}>
                          {t(group.title)}
                        </span>
                        {badCount > 0 && <span className="badge red" style={{ marginLeft: 'auto' }}>{badCount}</span>}
                      </div>
                      {group.keys.map(key => {
                        const item = CHECK_ITEMS.find(i => i.key === key)!;
                        const okState = checks[item.key] ?? true;
                        return (
                          <div key={item.key} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 0' }}>
                            {/* Строка пункта — простым текстом; расшифровка узла ушла в подсказку при наведении. */}
                            <div title={t(item.desc)} style={{ flex: 1, minWidth: 0, color: 'var(--ink)', fontSize: 13.5 }}>
                              {t(item.label)}
                            </div>
                            <button type="button" style={okState ? okActive : tgBase} onClick={() => setChecks({ ...checks, [item.key]: true })}>
                              {t('st.ok')}
                            </button>
                            <button type="button" style={!okState ? badActive : tgBase} onClick={() => setChecks({ ...checks, [item.key]: false })}>
                              {t('st.faulty')}
                            </button>
                          </div>
                        );
                      })}
                    </div>
                  );
                })}
              </div>
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

            {/* Фотофиксация неисправностей (Т3) — через общий механизм вложений рейса (DEFECT_PHOTO) */}
            <div className="card">
              <div className="card-h"><h2>{t('tech.photos.h')}</h2></div>
              <p style={{ color: 'var(--muted)', fontSize: 12.5, marginTop: -4 }}>{t('tech.photos.lead')}</p>
              {photoErr && <div className="error">{photoErr}</div>}
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', margin: '8px 0 12px' }}>
                <input ref={photoRef} type="file" accept="image/*,.jpg,.jpeg,.png,.tif,.tiff,.heic" style={{ maxWidth: 260 }} />
                <button className="btn" onClick={uploadPhoto} disabled={photoBusy}>
                  <Icon d={P.scan} cls="" style={{ width: 15, height: 15 }} /> {photoBusy ? t('doc.btn.uploading') : t('tech.photos.add')}
                </button>
              </div>
              {photos.length === 0 ? (
                <div style={{ color: 'var(--muted)', fontSize: 13, padding: '6px 2px' }}>{t('tech.photos.empty')}</div>
              ) : (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
                  {photos.map(a => (
                    <button key={a.id} type="button" onClick={() => openPhoto(a)}
                      style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '8px 12px', border: '1px solid var(--line)', borderRadius: 8, background: 'var(--surface)', cursor: 'pointer', fontFamily: 'inherit', fontSize: 12.5, color: 'var(--ink)', maxWidth: 260 }}>
                      <Icon d={P.scan} cls="" style={{ width: 15, height: 15, color: 'var(--blue-600)', flex: 'none' }} />
                      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.title || a.fileName}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Кнопки решения */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 4, flexWrap: 'wrap' }}>
              <button className="btn secondary" onClick={() => setSelected(null)}>{t('btn.cancel')}</button>
              <button className="btn success" style={{ minWidth: 270, color: '#fff' }} onClick={() => decide(true)}>
                <Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> {t('tech.btn.allow')}
              </button>
              <button className="btn danger" style={{ minWidth: 270, color: '#fff' }} onClick={() => decide(false)}>
                <Icon d={P.alert} cls="" style={{ width: 16, height: 16 }} /> {t('tech.btn.deny')}
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
  // Последние проверенные ТС — свои ПЛ, где техконтроль состоялся (пройден или отклонён).
  const recentChecked = all
    .filter(w => w.techPassed || w.status === 'TECH_REJECTED')
    .sort((a, b) => String(b.validFrom ?? b.createdAt).localeCompare(String(a.validFrom ?? a.createdAt)));
  const recentPages = Math.max(1, Math.ceil(recentChecked.length / PER_PAGE));
  const recentView = recentChecked.slice((recentPage - 1) * PER_PAGE, recentPage * PER_PAGE);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.tech')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('tech.lead')}</div>
          {me && (
            <div style={{ marginTop: 5, fontSize: 12.5, color: 'var(--ink-soft)', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <Icon d={P.user} cls="" style={{ width: 14, height: 14, color: 'var(--blue-600)' }} />
              <span>{t('role.MECHANIC')}: <b style={{ color: 'var(--ink)' }}>{me.name || '—'}</b> · {me.orgName}</span>
            </div>
          )}
        </div>
        <span className="spacer" />
        <div style={{ display: 'flex', alignItems: 'center', gap: 20, color: 'var(--muted)', fontSize: 13, fontWeight: 500 }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
            <Icon d={CAL} cls="" style={{ width: 15, height: 15 }} />
            {now ? now.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' }) + ' г.' : '—'}
          </span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, fontVariantNumeric: 'tabular-nums' }}>
            <Icon d={CLK} cls="" style={{ width: 15, height: 15 }} />
            {now ? now.toLocaleTimeString('ru-RU') : '—'}
          </span>
        </div>
        <button className="btn secondary" onClick={() => { setReportOpen(true); setReportErr(''); setReportMsg(''); }}>
          <Icon d={P.alert} cls="" style={{ width: 15, height: 15 }} /> {t('tech.report.btn')}
        </button>
        {/* Журнал техконтроля — отдельная страница (пункт в боковом меню). /reports/journals
            механику недоступен: его навигация — только tech,fleet. */}
        <Link href="/tech/journal" className="btn" style={{ textDecoration: 'none', color: '#fff' }}>
          <Icon d={P.book} cls="" style={{ width: 15, height: 15 }} /> {t('tech.journal.link')}
        </Link>
      </div>
      {banners}
      {reportMsg && <div className="success">{reportMsg}</div>}

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

      {/* Очередь + правая колонка (layout как в кабинете доктора) */}
      <div className="grid-2" style={{ gridTemplateColumns: '1.6fr 1fr', alignItems: 'start' }}>
        {/* Очередь на техконтроль */}
        <div className="card" style={{ marginBottom: 0 }}>
          <div className="card-h">
            <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, margin: 0 }}>
              <Icon d={P.wrench} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} /> {t('tech.queue.h')}
            </h2>
            <span className="badge blue" style={{ marginLeft: 12 }}>{queue.length}</span>
            <input value={search} onChange={e => { setSearch(e.target.value); setQueuePage(1); }} placeholder={t('exam.search')}
              style={{ marginLeft: 'auto', maxWidth: 320 }} />
          </div>
          <table>
            <thead>
              <tr><th style={{ width: 40 }}>№</th><th>{t('col.brand')}</th><th>{t('col.vehiclenum')}</th><th>{t('col.driver')}</th><th>{t('col.vehtype')}</th><th>{t('col.mileage')}</th><th>{t('col.time')}</th><th style={{ textAlign: 'right' }}>{t('fleet.col.actions')}</th></tr>
            </thead>
            <tbody>
              {queueView.map((w, i) => (
                <tr key={w.id}>
                  <td style={{ color: 'var(--muted)', fontVariantNumeric: 'tabular-nums' }}>{(queuePage - 1) * PER_PAGE + i + 1}</td>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(w.vehicleSnapshot?.brand ?? '—')}</td>
                  <td><span className="plate" style={{ transform: 'scale(.9)', transformOrigin: 'left center' }}><span className="p-main">{w.vehicleRegNumber}</span><span className="p-reg">01</span></span></td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                  <td>{w.vehicleSnapshot?.transportType != null ? t('veh.type.' + w.vehicleSnapshot.transportType) : '—'}</td>
                  <td style={{ fontVariantNumeric: 'tabular-nums' }}>{w.vehicleSnapshot?.odometer != null ? `${Number(w.vehicleSnapshot.odometer).toLocaleString('ru-RU')} км` : '—'}</td>
                  <td>{fmt(w.validFrom ?? w.createdAt)}</td>
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                    <button className="btn secondary" style={{ padding: '6px 12px' }} onClick={() => setDetail(w)}>{t('tech.details')}</button>
                    <button className="btn" onClick={() => open(w)}>{t('tech.btn.check')}</button>
                  </td>
                </tr>
              ))}
              {shownQueue.length === 0 && (
                <tr><td colSpan={8} style={{ color: 'var(--muted)', textAlign: 'center', padding: 20 }}>{search.trim() ? t('exam.search.empty') : t('tech.queue.empty')}</td></tr>
              )}
            </tbody>
          </table>
          {/* Пагинация */}
          <div style={{ display: 'flex', alignItems: 'center', marginTop: 14, fontSize: 12.5, color: 'var(--muted)' }}>
            <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{shownQueue.length}</b></span>
            <span style={{ flex: 1 }} />
            <button className="btn secondary" disabled={queuePage <= 1} onClick={() => setQueuePage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
            <span style={{ margin: '0 12px' }}>{queuePage} / {queuePages}</span>
            <button className="btn secondary" disabled={queuePage >= queuePages} onClick={() => setQueuePage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
          </div>
        </div>

        {/* Правая колонка */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          {/* Очередь на сегодня — ближайшие ТС на контроль (реальные ожидающие) */}
          <div className="card" style={{ marginBottom: 0 }}>
            <h2>{t('tech.schedule.h')}</h2>
            {shownQueue.slice(0, 6).map(w => (
              <div key={w.id} style={{ display: 'flex', gap: 12, padding: '11px 0', borderBottom: '1px solid var(--line-soft)' }}>
                <div style={{ width: 42, flex: 'none', fontWeight: 700, fontSize: 13, color: 'var(--ink)', fontVariantNumeric: 'tabular-nums' }}>{new Date(w.validFrom ?? w.createdAt).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}</div>
                <div style={{ flex: 1, minWidth: 0, borderLeft: '2px solid var(--blue-500)', paddingLeft: 11 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <b style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink)' }}>{String(w.vehicleSnapshot?.brand ?? w.vehicleRegNumber)}</b>
                    <span className="badge amber" style={{ marginLeft: 'auto' }}>{t('st.waiting')}</span>
                  </div>
                  <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 2 }}>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</div>
                  <div className="number" style={{ fontSize: 11.5, marginTop: 2 }}>{w.vehicleRegNumber}</div>
                </div>
              </div>
            ))}
            {shownQueue.length === 0 && <div style={{ color: 'var(--muted)', fontSize: 13, padding: '12px 2px' }}>{t('tech.queue.empty')}</div>}
          </div>

          {/* Быстрые действия */}
          <div className="card" style={{ marginBottom: 0 }}>
            <h2>{t('med.quickactions')}</h2>
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              {([
                { title: t('tech.act.start.t'), sub: t('tech.act.start.s'), icon: P.wrench, cls: 'ic-blue', onClick: () => { if (queue[0]) open(queue[0]); } },
                { title: t('tech.journal.link'), sub: t('tech.act.journal.s'), icon: P.book, cls: 'ic-green', href: '/tech/journal' },
                { title: t('tech.report.btn'), sub: t('tech.act.report.s'), icon: P.alert, cls: 'ic-amber', onClick: () => { setReportOpen(true); setReportErr(''); setReportMsg(''); } },
              ] as { title: string; sub: string; icon: string; cls: string; href?: string; onClick?: () => void }[]).map((a, idx) => {
                const inner = (
                  <>
                    <span className={a.cls} style={{ width: 38, height: 38, borderRadius: 10, display: 'grid', placeItems: 'center', flex: 'none' }}>
                      <Icon d={a.icon} cls="" />
                    </span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: 600, fontSize: 13, color: 'var(--ink)' }}>{a.title}</div>
                      <div style={{ fontSize: 12, color: 'var(--muted)' }}>{a.sub}</div>
                    </div>
                    <Icon d={P.chevron} cls="" style={{ width: 16, height: 16, color: 'var(--faint)' }} />
                  </>
                );
                const rowStyle: CSSProperties = {
                  display: 'flex', alignItems: 'center', gap: 12, padding: '11px 4px',
                  borderTop: idx === 0 ? 'none' : '1px solid var(--line-soft)',
                  textDecoration: 'none', color: 'inherit',
                };
                return a.href
                  ? <Link key={a.title} href={a.href} style={rowStyle}>{inner}</Link>
                  : (
                    <button
                      key={a.title}
                      onClick={a.onClick}
                      style={{ ...rowStyle, background: 'none', border: 'none', borderTop: rowStyle.borderTop, width: '100%', textAlign: 'left', cursor: 'pointer', fontFamily: 'inherit' }}
                    >
                      {inner}
                    </button>
                  );
              })}
            </div>
          </div>
        </div>
      </div>

      {/* Последние проверенные ТС */}
      <div className="card">
        <div className="card-h"><h2>{t('tech.recent.h')}</h2></div>
        <table>
          <thead>
            <tr><th>{t('col.wbnum')}</th><th>{t('col.vehiclenum')}</th><th>{t('col.driver')}</th><th>{t('col.time')}</th><th>{t('col.result')}</th><th></th></tr>
          </thead>
          <tbody>
            {recentView.map(w => (
              <tr key={w.id}>
                <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                <td><span className="plate" style={{ transform: 'scale(.9)', transformOrigin: 'left center' }}><span className="p-main">{w.vehicleRegNumber}</span><span className="p-reg">01</span></span></td>
                <td>{String(w.driverSnapshot?.fullName ?? w.driverRma)}</td>
                <td>{fmt(w.validFrom ?? w.createdAt)}</td>
                <td>
                  {w.techPassed
                    ? <span className="badge green">{t('st.serviceable')}</span>
                    : <span className="badge red">{t('tech.state.faulty')}</span>}
                </td>
                <td style={{ textAlign: 'right' }}>
                  <Link href={`/waybills/${w.id}`} style={{ color: 'var(--faint)', display: 'inline-flex' }} aria-label={t('btn.view')}>
                    <Icon d={P.eye} cls="" style={{ width: 18, height: 18 }} />
                  </Link>
                </td>
              </tr>
            ))}
            {recentChecked.length === 0 && (
              <tr><td colSpan={6} style={{ color: 'var(--muted)', textAlign: 'center', padding: 28 }}>{t('tech.empty.recent')}</td></tr>
            )}
          </tbody>
        </table>
        {/* Пагинация */}
        <div style={{ display: 'flex', alignItems: 'center', marginTop: 14, fontSize: 12.5, color: 'var(--muted)' }}>
          <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{recentChecked.length}</b></span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={recentPage <= 1} onClick={() => setRecentPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{recentPage} / {recentPages}</span>
          <button className="btn secondary" disabled={recentPage >= recentPages} onClick={() => setRecentPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>
        <Link className="link" href="/tech/journal" style={{ display: 'inline-block', marginTop: 12 }}>{t('tech.gotojournal')}</Link>
      </div>

      {/* Детали ТС — тех. данные + история техосмотров */}
      {detail && (
        <div onClick={() => setDetail(null)} style={{ position: 'fixed', inset: 0, background: 'rgba(15,27,52,.45)', display: 'grid', placeItems: 'center', zIndex: 60, padding: 20 }}>
          <div className="card" onClick={e => e.stopPropagation()} style={{ width: 640, maxWidth: '100%', margin: 0, maxHeight: '88vh', overflowY: 'auto' }}>
            <div className="card-h">
              <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, margin: 0 }}>
                <Icon d={P.car} cls="" style={{ width: 18, height: 18, color: 'var(--blue-600)' }} /> {t('tech.details.h')}
              </h2>
              <button onClick={() => setDetail(null)} aria-label={t('btn.close')} style={{ marginLeft: 'auto', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--muted)', fontSize: 16 }}>✕</button>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
              <span className="plate"><span className="p-main">{detail.vehicleRegNumber}</span><span className="p-reg">01</span></span>
              <div style={{ fontWeight: 700, fontSize: 15 }}>{String(detailVeh?.brand ?? detail.vehicleSnapshot?.brand ?? '—')}</div>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 18 }}>
              <div>
                <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 8 }}>{t('tech.details.specs')}</div>
                <dl className="kv" style={{ gridTemplateColumns: '130px 1fr', gap: '7px 12px' }}>
                  <dt>{t('col.vehtype')}</dt><dd>{detailVeh?.transportType != null ? t('veh.type.' + detailVeh.transportType) : '—'}</dd>
                  <dt>{t('fleet.f.vin')}</dt><dd>{String(detailVeh?.vincode ?? '—')}</dd>
                  <dt>{t('fleet.f.year')}</dt><dd>{String(detailVeh?.yearManufacture ?? '—')}</dd>
                  <dt>{t('fleet.f.capacity')}</dt><dd>{String(detailVeh?.capacity ?? '—')}</dd>
                  <dt>{t('fleet.f.carrying')}</dt><dd>{String(detailVeh?.carrying ?? '—')}</dd>
                  <dt>{t('tech.details.odometer')}</dt><dd>{detailVeh?.odometer != null ? `${Number(detailVeh.odometer).toLocaleString('ru-RU')} км` : '—'}</dd>
                </dl>
              </div>
              <div>
                <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 8 }}>{t('tech.details.service')}</div>
                <dl className="kv" style={{ gridTemplateColumns: '130px 1fr', gap: '7px 12px' }}>
                  <dt>{t('fleet.f.tech')}</dt><dd>{String(detailVeh?.techInspectionValidTo ?? '—')}</dd>
                  <dt>{t('fleet.f.insurance')}</dt><dd>{String(detailVeh?.insuranceValidTo ?? '—')}</dd>
                  <dt>{t('fleet.f.card')}</dt><dd>{String(detailVeh?.controlCardValidTo ?? '—')}</dd>
                </dl>
              </div>
            </div>
            <div style={{ marginTop: 18 }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 8 }}>{t('tech.details.history')}</div>
              {detailHistory.length === 0 ? (
                <div style={{ color: 'var(--muted)', fontSize: 13 }}>{t('tech.details.nohistory')}</div>
              ) : (
                <table>
                  <thead><tr><th>{t('col.datetime')}</th><th>{t('col.wbnum')}</th><th>{t('col.result')}</th></tr></thead>
                  <tbody>
                    {detailHistory.map(h => (
                      <tr key={h.id}>
                        <td>{fmt(h.validFrom ?? h.createdAt)}</td>
                        <td><span className="number">{h.number ?? '—'}</span></td>
                        <td><span className={`badge ${h.techPassed ? 'green' : 'red'}`}>{h.techPassed ? t('st.serviceable') : t('tech.state.faulty')}</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
              <button className="btn secondary" onClick={() => setDetail(null)}>{t('btn.close')}</button>
            </div>
          </div>
        </div>
      )}

      {/* Сообщить о неисправности — уведомление диспетчеру/админу */}
      {reportOpen && (
        <div onClick={() => setReportOpen(false)} style={{ position: 'fixed', inset: 0, background: 'rgba(15,27,52,.45)', display: 'grid', placeItems: 'center', zIndex: 60, padding: 20 }}>
          <div className="card" onClick={e => e.stopPropagation()} style={{ width: 460, maxWidth: '100%', margin: 0, borderColor: 'var(--amber)' }}>
            <div className="card-h">
              <h2 style={{ display: 'inline-flex', alignItems: 'center', gap: 8, margin: 0, color: '#a9700a' }}>
                <Icon d={P.alert} cls="" style={{ width: 18, height: 18 }} /> {t('tech.report.h')}
              </h2>
              <button onClick={() => setReportOpen(false)} aria-label={t('btn.close')} style={{ marginLeft: 'auto', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--muted)', fontSize: 16 }}>✕</button>
            </div>
            <p className="hint">{t('tech.report.note')}</p>
            {reportErr && <div className="error">{reportErr}</div>}
            <div style={{ marginBottom: 12 }}>
              <label>{t('tech.report.veh')}</label>
              <input value={reportForm.vehicleRegNumber} onChange={e => setReportForm({ ...reportForm, vehicleRegNumber: e.target.value })} placeholder="0101TJ01" />
            </div>
            <div style={{ marginBottom: 12 }}>
              <label>{t('tech.report.type')}</label>
              <select value={reportForm.issueType} onChange={e => setReportForm({ ...reportForm, issueType: e.target.value })}>
                <option value="">—</option>
                <option value={t('tech.report.t.malfunction')}>{t('tech.report.t.malfunction')}</option>
                <option value={t('tech.report.t.repair')}>{t('tech.report.t.repair')}</option>
                <option value={t('tech.report.t.to')}>{t('tech.report.t.to')}</option>
                <option value={t('tech.report.t.other')}>{t('tech.report.t.other')}</option>
              </select>
            </div>
            <div style={{ marginBottom: 14 }}>
              <label>{t('tech.report.msg')}</label>
              <textarea value={reportForm.message} onChange={e => setReportForm({ ...reportForm, message: e.target.value })} placeholder={t('tech.report.msg.ph')} maxLength={500}
                style={{ width: '100%', minHeight: 110, padding: '10px 13px', border: '1px solid var(--line)', borderRadius: 'var(--radius-sm)', fontSize: 13.5, fontFamily: 'var(--sans)', color: 'var(--ink)', resize: 'vertical' }} />
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button className="btn secondary" onClick={() => setReportOpen(false)}>{t('btn.cancel')}</button>
              <button className="btn" disabled={reportBusy || !reportForm.message.trim()} onClick={submitReport}>{reportBusy ? '…' : t('tech.report.send')}</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
