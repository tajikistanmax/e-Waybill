'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { md, wb } from '@/lib/api';
import { downloadCsv } from '@/lib/csv';
import { useAuth } from '@/lib/auth';
import { FORM_FIELDS, fieldLabel, useDataSource, useFormFieldModes } from '@/lib/formFields';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';
import SubjectDocuments from './SubjectDocuments';

type Row = Record<string, unknown>;
export type FleetKind = 'vehicles' | 'drivers' | 'employees';
// Формы ПЛ legacy → виды ПЛ e-Waybill для фильтров активности (MIGRATION.md 8.5/8.6): 3-С = легковой + такси,
// 1-АД = автобус + троллейбус.
const ACT_FORM_TYPES: Record<string, string[]> = {
  '': [], '3c': ['WB_CAR', 'WB_TAXI'], '2b': ['WB_TRUCK'], '1ad': ['WB_BUS', 'WB_TROLLEYBUS'], '1a': ['WB_MINIBUS'], '5bbm': ['WB_TRUCK_INTL'],
};
const ACT_FORM_LABELS: { v: string; l: string }[] = [
  { v: '3c', l: '3-С' }, { v: '2b', l: '2-Б' }, { v: '1ad', l: '1-АД' }, { v: '1a', l: '1-А' }, { v: '5bbm', l: '5Б-БМ' },
];
type Field = { key: string; label: string; type?: 'text' | 'number' | 'date' | 'select'; opts?: { v: string; l: string }[]; req?: boolean; keyField?: boolean; numeric?: boolean; hidden?: boolean };

/**
 * Раздел «Транспорт и водители» организации: полное нативное управление внутри платформы —
 * ручной ввод всех полей, редактирование, удаление + серверный поиск (для автопарков в тысячи).
 * Заголовок и переключатель разделов — в layout.tsx; у каждого раздела свой адрес.
 */
export default function FleetView({ kind }: { kind: FleetKind }) {
  const { t } = useT();
  const { roles } = useAuth();
  const canManage = roles.includes('DISPATCHER') || roles.includes('COMPANY_ADMIN') || roles.includes('BRANCH_ADMIN') || roles.includes('SYSTEM_ADMIN');
  // Открепление (удаление) от организации — повседневная задача диспетчера (ведение состава парка).
  const canDelete = canManage;
  // Механик осматривает ТС, врач — водителей; управляющие роли видят и то, и другое.
  const allowed = kind === 'vehicles' ? (canManage || roles.includes('MECHANIC'))
    : kind === 'drivers' ? (canManage || roles.includes('DOCTOR'))
      : canManage;

  const [docFor, setDocFor] = useState<{ subject: 'vehicles' | 'drivers'; key: string; title: string } | null>(null);
  const [orgRma, setOrgRma] = useState('');
  // Полный набор (в рамках организации) — только для карточек-счётчиков сверху.
  const [statRows, setStatRows] = useState<Row[]>([]);
  const [q, setQ] = useState('');
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const [form, setForm] = useState<Record<string, string> | null>(null); // null = форма закрыта
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  // Активность за период (MIGRATION.md 8.5/8.6) — фильтры legacy-реестров ТС (active_trans / inactive_trans /
  // active2b / 4-роҳхат(3с) / 2-роҳхат(2b) / период по году выпуска) и водителей (active_drivers{тип} / inactive_drivers).
  // Счётчики ПЛ по ключу берём с бэкенда (/reports/activity), отбор — по всему парку организации (statRows).
  const [actFrom, setActFrom] = useState('');
  const [actTo, setActTo] = useState('');
  const [actForm, setActForm] = useState('');
  const [actMode, setActMode] = useState<'any' | 'active' | 'inactive' | 'exact'>('any');
  const [actN, setActN] = useState('4');
  const [yearFrom, setYearFrom] = useState('');
  const [yearTo, setYearTo] = useState('');
  const [actMap, setActMap] = useState<Record<string, number> | null>(null);
  const actActive = (kind === 'vehicles' || kind === 'drivers') && (actMode !== 'any' || yearFrom !== '' || yearTo !== '');
  useEffect(() => {
    if (!(kind === 'vehicles' || kind === 'drivers') || actMode === 'any' || !actFrom || !actTo) { setActMap(null); return; }
    let alive = true;
    wb.activity(kind === 'vehicles' ? 'VEHICLE' : 'DRIVER', actFrom, actTo, ACT_FORM_TYPES[actForm] ?? [])
      .then(list => { if (alive) setActMap(Object.fromEntries(list.map(r => [r.key, r.waybills]))); })
      .catch(() => { if (alive) setActMap({}); });
    return () => { alive = false; };
  }, [kind, actMode, actFrom, actTo, actForm]);
  const visible = useMemo(() => {
    if (!actActive) return rows;
    const s = q.trim().toLowerCase();
    return statRows.filter(r => {
      const key = String(kind === 'vehicles' ? r.registrationNumber : r.rma);
      if (s) {
        const hay = kind === 'vehicles' ? `${r.registrationNumber ?? ''} ${r.brand ?? ''}` : `${r.fullName ?? ''} ${r.rma ?? ''}`;
        if (!hay.toLowerCase().includes(s)) return false;
      }
      if (kind === 'vehicles') {
        const y = Number(r.yearManufacture);
        if (yearFrom && (!y || y < Number(yearFrom))) return false;
        if (yearTo && (!y || y > Number(yearTo))) return false;
      }
      if (actMode !== 'any') {
        if (!actMap) return false; // период не задан или счётчики ещё грузятся
        const n = actMap[key] ?? 0;
        if (actMode === 'active' && n === 0) return false;
        if (actMode === 'inactive' && n > 0) return false;
        if (actMode === 'exact' && n !== Number(actN || 0)) return false;
      }
      return true;
    });
  }, [actActive, rows, statRows, q, kind, yearFrom, yearTo, actMode, actMap, actN]);

  // Организации области: у администратора компании — компания и филиалы. Раньше новая запись
  // уходила в первую организацию из ответа (порядок не гарантирован — могла попасть в филиал),
  // и в форме не было видно, куда именно. Теперь по умолчанию — головная компания (или филиал,
  // выбранный переключателем в шапке), а при нескольких организациях в форме есть выбор.
  const [orgs, setOrgs] = useState<Row[]>([]);
  const headRma = useMemo(() => {
    const head = orgs.find(o => !o.parentRma || !orgs.some(x => x.rma === o.parentRma)) ?? orgs[0];
    return head ? String(head.rma) : '';
  }, [orgs]);
  useEffect(() => {
    md.organizations().then(l => { setOrgs(l); }).catch(() => {});
  }, []);
  useEffect(() => { if (headRma) setOrgRma(headRma); }, [headRma]);
  // Смена раздела — сброс поиска, формы и сообщений.
  useEffect(() => { setQ(''); setRows([]); setForm(null); setMsg(''); setErr(''); }, [kind]);
  // Полный набор организации для счётчиков (реальные итоги, не ограниченные поиском/страницей).
  useEffect(() => {
    if (!allowed) return;
    let alive = true;
    const all = kind === 'vehicles' ? md.allVehicles() : kind === 'drivers' ? md.allDrivers() : md.allEmployees();
    all.then(l => { if (alive) setStatRows(l); }).catch(() => { if (alive) setStatRows([]); });
    return () => { alive = false; };
  }, [kind, allowed]);

  const TT = [
    { v: '1', l: t('tt.1') }, { v: '2', l: t('tt.2') }, { v: '3', l: t('tt.3') },
    { v: '4', l: t('tt.4') }, { v: '5', l: t('tt.5') }, { v: '6', l: t('tt.6') },
  ];
  const ET = [{ v: '1', l: t('fleet.emp.1') }, { v: '2', l: t('fleet.emp.2') }, { v: '3', l: t('fleet.emp.3') },
    { v: '4', l: t('fleet.emp.4') }, { v: '5', l: t('fleet.emp.5') }];
  // Поля формы — из общего списка карточки (тот же, что в разделе «Компания») с режимами из
  // Настройки → Поля водителя / транспорта / сотрудника. Раньше здесь был свой короткий список:
  // при изменении водителя паспорт, адрес, договор и закреплённое ТС уходили пустыми и затирались.
  const formName = kind === 'vehicles' ? 'vehicle' : kind === 'drivers' ? 'driver' : 'employee';
  const { modes } = useFormFieldModes(formName);
  // Кто ведёт справочник (Настройки → Интеграции): при UNIFIED ручного добавления нет, у записи
  // из единой платформы правятся только поля модуля (источник записи — editingSource).
  const ds = useDataSource();
  const [editingSource, setEditingSource] = useState<string | null>(null);
  const unifiedHere = ds[formName];
  const lockedField = ds.locked(formName, editingSource);
  const fields: Field[] = FORM_FIELDS[formName].map(f => ({
    key: f.key,
    label: fieldLabel(t, f, false),
    type: f.type === 'email' ? 'text' : f.type,
    opts: f.options?.map(o => ({ v: o.v, l: t(o.labelKey) })),
    req: !!f.locked || modes[f.key] === 'required',
    keyField: f.key === (kind === 'vehicles' ? 'registrationNumber' : 'rma'),
    numeric: f.numeric,
    // Закреплённое ТС выбирается в карточке ТС и в разделе «Компания» — здесь списка ТС нет;
    // поле не рисуется, но его значение отправляется обратно и не теряется.
    hidden: modes[f.key] === 'hidden' || f.key === 'assignedVehicleId',
  }));
  const shown = fields.filter(f => !f.hidden);

  const load = useCallback(async (query: string, which: FleetKind) => {
    setLoading(true); setErr('');
    try {
      if (which === 'employees') {
        // Сотрудников у организации немного (врач/механик/диспетчер) — грузим своих и фильтруем на клиенте.
        const all = await md.allEmployees();
        const s = query.trim().toLowerCase();
        setRows(s ? all.filter(e => String(e.name ?? '').toLowerCase().includes(s) || String(e.rma ?? '').includes(s)) : all);
      } else {
        setRows(which === 'vehicles' ? await md.searchVehicles('', query, 50) : await md.searchDrivers('', query, 50));
      }
    } catch (e) { setErr((e as Error).message); setRows([]); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    if (!allowed) return;
    const h = window.setTimeout(() => load(q, kind), 250);
    return () => window.clearTimeout(h);
  }, [q, kind, load, allowed]);

  function openNew() {
    setForm(Object.fromEntries(fields.map(f => [f.key, ''])));
    setOrgRma(headRma);
    setEditingSource(null);
    setEditing(false); setMsg(''); setErr('');
  }
  function openEdit(row: Row) {
    setForm(Object.fromEntries(fields.map(f => [f.key, row[f.key] != null ? String(row[f.key]) : ''])));
    setEditingSource(row.source == null ? null : String(row.source));
    // Правка — в той организации, где запись уже числится (иначе сохранение перенесло бы её).
    const own = orgs.find(o => String(o.id) === String(row.organizationId));
    setOrgRma(own ? String(own.rma) : headRma);
    setEditing(true); setMsg(''); setErr('');
  }

  async function save() {
    if (!form || !orgRma) return;
    setBusy(true); setErr(''); setMsg('');
    try {
      const body: Record<string, unknown> = { organizationRma: orgRma };
      for (const f of fields) {
        const val = (form[f.key] ?? '').trim();
        if (val === '') continue;
        body[f.key] = f.numeric ? Number(val) : val;
      }
      if (kind === 'vehicles') await md.createVehicle(body);
      else if (kind === 'drivers') await md.createDriver(body);
      else await md.createEmployee(body);
      setMsg(editing ? t('fleet.saved') : t('fleet.added'));
      setForm(null);
      load(q, kind);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  async function remove(row: Row) {
    const name = kind === 'vehicles' ? String(row.registrationNumber) : kind === 'drivers' ? String(row.fullName) : String(row.name);
    if (!window.confirm(t('fleet.delete.confirm').replace('{name}', name))) return;
    setErr(''); setMsg('');
    try {
      if (kind === 'vehicles') await md.deleteVehicle(String(row.id));
      else if (kind === 'drivers') await md.deleteDriver(String(row.id));
      else await md.deleteEmployee(String(row.id));
      setMsg(t('fleet.deleted'));
      load(q, kind);
    } catch (e) { setErr((e as Error).message); }
  }

  /** CSV текущего списка: колонки = поля формы раздела (подписи локализованы), плюс счётчик ПЛ при активном отборе. */
  function exportCsv() {
    const head = shown.map(f => f.label).concat(actMap ? [t('fleet.act.count')] : []);
    const line = (r: Row): unknown[] => shown.map(f => {
      const v = r[f.key];
      if (f.type === 'select' && f.opts) return f.opts.find(o => o.v === String(v))?.l ?? (v ?? '');
      return v ?? '';
    }).concat(actMap ? [actMap[String(kind === 'vehicles' ? r.registrationNumber : r.rma)] ?? 0] : []);
    downloadCsv(`${kind}_${new Date().toISOString().slice(0, 10)}.csv`, [head, ...visible.map(line)]);
  }

  if (!allowed) {
    return (
      <div className="card">
        <p style={{ color: 'var(--muted)', margin: 0 }}>{t('fleet.noaccess')}</p>
      </div>
    );
  }

  const cols = kind === 'employees' ? 6 : 5;

  // Счётчики: истекает в ≤30 дней или уже истёк.
  const soon = (d: unknown) => {
    if (!d) return false;
    const ts = new Date(String(d)).getTime();
    return !isNaN(ts) && (ts - Date.now()) / 86400000 <= 30;
  };
  const statCards = kind === 'vehicles'
    ? [
        { label: t('fleet.stat.vehicles'), value: statRows.length, icon: P.car, cls: 'ic-blue' },
        { label: t('fleet.stat.techsoon'), value: statRows.filter(r => soon(r.techInspectionValidTo)).length, icon: P.wrench, cls: 'ic-amber' },
        { label: t('fleet.stat.inssoon'), value: statRows.filter(r => soon(r.insuranceValidTo)).length, icon: P.shield, cls: 'ic-red' },
      ]
    : kind === 'drivers'
      ? [
          { label: t('fleet.stat.drivers'), value: statRows.length, icon: P.user, cls: 'ic-blue' },
          { label: t('fleet.stat.licsoon'), value: statRows.filter(r => soon(r.licenseValidTo)).length, icon: P.doc, cls: 'ic-amber' },
          { label: t('fleet.stat.medsoon'), value: statRows.filter(r => soon(r.medCertValidTo)).length, icon: P.med, cls: 'ic-red' },
        ]
      : [];

  return (
    <>
      {statCards.length > 0 && (
        <>
          <div className="kpi-row" style={{ gridTemplateColumns: `repeat(${statCards.length}, 1fr)`, marginBottom: 6 }}>
            {statCards.map(s => (
              <div className="kpi" key={s.label} style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
                <span className={`k-ic ${s.cls}`}><Icon d={s.icon} cls="" /></span>
                <div style={{ minWidth: 0 }}>
                  <div className="k-value" style={{ fontSize: 22 }}>{s.value.toLocaleString('ru-RU')}</div>
                  <div className="k-label">{s.label}</div>
                </div>
              </div>
            ))}
          </div>
          <div style={{ margin: '0 0 16px', fontSize: 11.5, color: 'var(--muted)' }}>{t('fleet.stat.soonhint')}</div>
        </>
      )}

      <div style={{ display: 'flex', gap: 8, marginBottom: 16, justifyContent: 'flex-end' }}>
        {/* Экспорт текущего списка (8.7, legacy enableExportButtons) — CSV для Excel. */}
        <button className="btn secondary" onClick={exportCsv} disabled={visible.length === 0} title={t('rep.export.hint')}>
          <Icon d={P.chart} cls="" style={{ width: 15, height: 15 }} /> CSV
        </button>
        {canManage && unifiedHere && (
          <span className="badge blue" style={{ alignSelf: 'center' }} title={t('ds.addoff.hint')}>{t('ds.addoff')}</span>
        )}
        {canManage && !unifiedHere && (
          <button className="btn" onClick={() => (form ? setForm(null) : openNew())}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} />{' '}
            {kind === 'vehicles' ? t('fleet.add.vehicle') : kind === 'drivers' ? t('fleet.add.driver') : t('fleet.add.employee')}
          </button>
        )}
      </div>

      {/* Полная форма ручного ввода/редактирования */}
      {canManage && form && (
        <div className="card" style={{ padding: 18, marginBottom: 16 }}>
          <h2 style={{ marginTop: 0 }}>{editing ? t('fleet.edit') : (kind === 'vehicles' ? t('fleet.new.vehicle') : kind === 'drivers' ? t('fleet.new.driver') : t('fleet.new.employee'))}</h2>
          {/* Куда записывается: компания или её филиал (видно и выбирается, если организаций несколько). */}
          {orgs.length > 1 && orgs.length <= 50 && (
            <div style={{ marginBottom: 12, maxWidth: 460 }}>
              <label htmlFor="fleet-org" style={{ fontSize: 12.5, color: 'var(--muted)' }}>{t('access.f.org')} *</label>
              <select id="fleet-org" value={orgRma} disabled={editing} onChange={e => setOrgRma(e.target.value)} style={{ width: '100%', marginTop: 4 }}>
                {orgs.map(o => (
                  <option key={String(o.rma)} value={String(o.rma)}>{String(o.name)}{o.parentRma ? t('access.f.branch.suffix') : ''}</option>
                ))}
              </select>
            </div>
          )}
          {editing && lockedField('') && (
            <div className="hint" style={{ marginBottom: 12, borderColor: 'var(--blue-600)' }}>{t('ds.locked.hint')}</div>
          )}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 12 }}>
            {shown.map(f => (
              <div key={f.key}>
                <label htmlFor={`fleet-${f.key}`} style={{ fontSize: 12.5, color: 'var(--muted)' }}>{f.label}{f.req ? ' *' : ''}</label>
                {f.type === 'select' ? (
                  <select id={`fleet-${f.key}`} value={form[f.key] ?? ''} disabled={editing && lockedField(f.key)}
                    onChange={e => setForm(s => ({ ...s!, [f.key]: e.target.value }))} style={{ width: '100%', marginTop: 4 }}>
                    <option value="">—</option>
                    {f.opts!.map(o => <option key={o.v} value={o.v}>{o.l}</option>)}
                  </select>
                ) : (
                  <input id={`fleet-${f.key}`} type={f.type === 'number' ? 'number' : f.type === 'date' ? 'date' : 'text'}
                    value={form[f.key] ?? ''} disabled={editing && (f.keyField || lockedField(f.key))}
                    onChange={e => setForm(s => ({ ...s!, [f.key]: e.target.value }))} style={{ width: '100%', marginTop: 4 }} />
                )}
              </div>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 16 }}>
            <button className="btn primary" disabled={busy || fields.some(f => f.req && !(editing && lockedField(f.key)) && !(form[f.key] ?? '').trim())} onClick={save}>
              {busy ? '…' : t('fleet.save')}
            </button>
            <button className="btn secondary" onClick={() => setForm(null)}>{t('fleet.cancel')}</button>
          </div>
        </div>
      )}
      {msg && <div className="hint" style={{ marginBottom: 12, color: 'var(--green-700, #15803d)' }}>{msg}</div>}
      {err && <div className="error" style={{ marginBottom: 12 }}>{err}</div>}

      {/* Поиск + таблица */}
      <div className="card" style={{ padding: 16 }}>
        {kind !== 'employees' && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 10, alignItems: 'end', marginBottom: 12 }}>
            <div style={{ gridColumn: '1 / -1', fontSize: 12.5, fontWeight: 600, color: 'var(--muted)' }}>{t('fleet.act.title')}</div>
            <div><label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('flt.datefrom')}</label><input type="date" value={actFrom} onChange={e => setActFrom(e.target.value)} style={{ width: '100%' }} /></div>
            <div><label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('flt.dateto')}</label><input type="date" value={actTo} onChange={e => setActTo(e.target.value)} style={{ width: '100%' }} /></div>
            <div><label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('fleet.act.form')}</label>
              <select value={actForm} onChange={e => setActForm(e.target.value)} style={{ width: '100%' }}>
                <option value="">{t('fleet.act.form.all')}</option>
                {ACT_FORM_LABELS.map(f => <option key={f.v} value={f.v}>{f.l}</option>)}
              </select>
            </div>
            <div><label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('fleet.act.mode')}</label>
              <select value={actMode} onChange={e => setActMode(e.target.value as 'any' | 'active' | 'inactive' | 'exact')} style={{ width: '100%' }}>
                <option value="any">{t('fleet.act.mode.any')}</option>
                <option value="active">{t('fleet.act.mode.active')}</option>
                <option value="inactive">{t('fleet.act.mode.inactive')}</option>
                <option value="exact">{t('fleet.act.mode.exact')}</option>
              </select>
            </div>
            {actMode === 'exact' && <div><label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('fleet.act.n')}</label><input type="number" min={0} value={actN} onChange={e => setActN(e.target.value)} style={{ width: '100%' }} /></div>}
            {kind === 'vehicles' && (<>
              <div><label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('fleet.act.yearfrom')}</label><input type="number" min={1900} value={yearFrom} onChange={e => setYearFrom(e.target.value)} style={{ width: '100%' }} /></div>
              <div><label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('fleet.act.yearto')}</label><input type="number" min={1900} value={yearTo} onChange={e => setYearTo(e.target.value)} style={{ width: '100%' }} /></div>
            </>)}
            {actActive && <div style={{ gridColumn: '1 / -1', fontSize: 11.5, color: 'var(--muted)' }}>{t('fleet.act.hint')} {actMode !== 'any' && !actMap ? '…' : ''}</div>}
          </div>
        )}
        <input value={q} onChange={e => setQ(e.target.value)}
          placeholder={kind === 'vehicles' ? t('fleet.search.vehicle') : kind === 'drivers' ? t('fleet.search.driver') : t('fleet.search.employee')} style={{ marginBottom: 12 }} />
        <table>
          <thead>
            {kind === 'vehicles' ? (
              <tr><th>{t('fleet.col.plate')}</th><th>{t('fleet.col.brand')}</th><th>{t('fleet.col.type')}</th><th>{t('fleet.col.tech')}</th><th style={{ textAlign: 'right' }}>{t('fleet.col.actions')}</th></tr>
            ) : kind === 'drivers' ? (
              <tr><th>{t('fleet.col.name')}</th><th>{t('fleet.col.inn')}</th><th>{t('fleet.col.cat')}</th><th>{t('fleet.col.license')}</th><th style={{ textAlign: 'right' }}>{t('fleet.col.actions')}</th></tr>
            ) : (
              <tr><th>{t('fleet.col.name')}</th><th>{t('fleet.col.inn')}</th><th>{t('fleet.f.emptype')}</th><th>{t('fleet.f.tab')}</th><th>{t('fleet.f.phone')}</th><th style={{ textAlign: 'right' }}>{t('fleet.col.actions')}</th></tr>
            )}
          </thead>
          <tbody>
            {loading && <tr><td colSpan={cols} style={{ color: 'var(--muted)' }}>{t('fleet.loading')}</td></tr>}
            {!loading && visible.length === 0 && <tr><td colSpan={cols} style={{ color: 'var(--muted)' }}>{t('fleet.empty')}</td></tr>}
            {!loading && kind === 'vehicles' && visible.map((v, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600, fontFamily: 'var(--mono)' }}>{String(v.registrationNumber ?? '')}{actMap && <span className="badge blue" style={{ marginLeft: 6 }} title={t('fleet.act.count')}>{actMap[String(v.registrationNumber)] ?? 0}</span>}</td>
                <td>{String(v.brand ?? '—')}</td>
                <td>{TT.find(x => x.v === String(v.transportType))?.l ?? String(v.transportType ?? '—')}</td>
                <td>{v.techInspectionValidTo ? String(v.techInspectionValidTo) : '—'}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{canManage && rowActions(v)}</td>
              </tr>
            ))}
            {!loading && kind === 'drivers' && visible.map((d, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600 }}>{String(d.fullName ?? '')}{actMap && <span className="badge blue" style={{ marginLeft: 6 }} title={t('fleet.act.count')}>{actMap[String(d.rma)] ?? 0}</span>}</td>
                <td style={{ fontFamily: 'var(--mono)' }}>{String(d.rma ?? '')}</td>
                <td>{String(d.licenseCategories ?? '—')}</td>
                <td>{d.licenseValidTo ? String(d.licenseValidTo) : '—'}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{canManage && rowActions(d)}</td>
              </tr>
            ))}
            {!loading && kind === 'employees' && rows.map((e, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600 }}>{String(e.name ?? '')}</td>
                <td style={{ fontFamily: 'var(--mono)' }}>{String(e.rma ?? '')}</td>
                <td>{ET.find(x => x.v === String(e.type))?.l ?? String(e.type ?? '—')}</td>
                <td>{String(e.tabNumber ?? '—')}</td>
                <td>{String(e.phone ?? '—')}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{canManage && rowActions(e)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="hint" style={{ marginTop: 10 }}>{t('fleet.note.native')}</div>
      </div>

      {docFor && (
        <div onClick={() => setDocFor(null)} style={{ position: 'fixed', inset: 0, background: 'rgba(15,32,60,.45)', zIndex: 60, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '5vh 16px', overflowY: 'auto' }}>
          <div onClick={ev => ev.stopPropagation()} className="card" style={{ maxWidth: 780, width: '100%', margin: 0 }}>
            <div className="card-h">
              <h2 style={{ margin: 0 }}>{docFor.subject === 'vehicles' ? t('col.transport') : t('rj.driver')}</h2>
              <button className="btn secondary" style={{ marginLeft: 'auto' }} onClick={() => setDocFor(null)}>✕</button>
            </div>
            <SubjectDocuments subject={docFor.subject} subjectKey={docFor.key} title={docFor.title} />
          </div>
        </div>
      )}
    </>
  );

  function rowActions(row: Row) {
    const subj: 'vehicles' | 'drivers' | null = kind === 'vehicles' ? 'vehicles' : kind === 'drivers' ? 'drivers' : null;
    return (
      <span style={{ display: 'inline-flex', gap: 6, justifyContent: 'flex-end' }}>
        {subj && (
          <button className="btn secondary" style={{ padding: '4px 9px' }} title={t('sd.h')}
            onClick={() => setDocFor({
              subject: subj,
              key: String(subj === 'vehicles' ? row.registrationNumber : row.rma),
              title: String(subj === 'vehicles' ? `${row.brand ?? ''} ${row.registrationNumber}` : row.fullName),
            })}>
            <Icon d={P.book ?? P.doc} cls="" style={{ width: 14, height: 14 }} />
          </button>
        )}
        <button className="btn secondary" style={{ padding: '4px 9px' }} onClick={() => openEdit(row)} title={t('fleet.edit')}>
          <Icon d={P.doc} cls="" style={{ width: 14, height: 14 }} />
        </button>
        {/* Запись из единой платформы в режиме «справочник ведёт e-Transport» открепляется там. */}
        {canDelete && !(unifiedHere && String(row.source ?? '').toUpperCase() === 'UNIFIED') && (
          <button className="btn secondary" style={{ padding: '4px 9px', color: 'var(--red)' }} onClick={() => remove(row)} title={t('fleet.delete')}>
            <Icon d={P.trash ?? P.alert} cls="" style={{ width: 14, height: 14 }} />
          </button>
        )}
      </span>
    );
  }
}
