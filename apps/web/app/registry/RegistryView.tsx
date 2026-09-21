'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { md } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type Row = Record<string, unknown>;
export type RegistryKind = 'vehicles' | 'drivers' | 'employees';

const EMPLOYEE_TYPES: Record<number, string> = { 1: 'Врач (духтур)', 2: 'Механик', 3: 'Диспетчер (танзимгар)' };
const TRANSPORT_TYPES: Record<number, string> = {
  1: 'Автобус', 2: 'Троллейбус', 3: 'Микроавтобус', 4: 'Легковой (сабукрав)', 5: 'Грузовой (2-Б)', 6: 'Грузовой межд. (5Б-БМ)',
};
// Переводимые подписи типов: карты выше остаются как проверка допустимости кода и русский фолбэк,
// а сам текст берётся из словаря (emp.type.* / veh.type.*), поэтому меняется при переключении языка.
const vehType = (v: unknown, t: (k: string) => string) => {
  const n = Number(v);
  return TRANSPORT_TYPES[n] ? t('veh.type.' + n) : (v == null || v === '' ? '—' : String(v));
};
const empType = (v: unknown, t: (k: string) => string) => {
  const n = Number(v);
  return EMPLOYEE_TYPES[n] ? t('emp.type.' + n) : (v == null || v === '' ? '—' : String(v));
};

const LABEL_KEY: Record<RegistryKind, string> = {
  vehicles: 'col.transport', drivers: 'col.drivers', employees: 'col.employees',
};

const s = (v: unknown) => (v == null || v === '' ? '—' : String(v));

// Пагинация: рендерим только текущую страницу. Без неё на боевых данных (89k ТС / 68k
// водителей) браузер вешался, пытаясь отрисовать десятки тысяч строк разом.
const PER_PAGE = 20;

/** Модальное окно с полной карточкой записи (только просмотр). */
function DetailModal({ kind, row, orgName, assignedVeh, onClose, t }: {
  kind: RegistryKind; row: Row; orgName: string; assignedVeh: string; onClose: () => void; t: (k: string) => string;
}) {
  const source = String(row.source) === 'UNIFIED' ? t('reg.src.unified') : t('dt.src.manual');
  const fields: [string, unknown][] = kind === 'vehicles' ? [
    [t('col.regnum'), row.registrationNumber], [t('dt.vehtype'), vehType(row.transportType, t)],
    [t('col.brand'), row.brand], [t('dt.vin'), row.vincode], [t('col.org'), orgName],
    [t('dt.parking'), row.parkingNumber], [t('dt.capacity'), row.capacity], [t('dt.carrying'), row.carrying],
    [t('col.odometer'), row.odometer], [t('dt.year'), row.yearManufacture],
    [t('col.techto'), row.techInspectionValidTo], [t('col.cardto'), row.controlCardValidTo],
    [t('dt.controlcardnum'), row.controlCardNumber], [t('dt.intlcertnum'), row.intlCertificateNumber],
    [t('dt.insto'), row.insuranceValidTo], [t('dt.adrto'), row.adrApprovalValidTo],
    [t('dt.source'), source],
  ] : kind === 'drivers' ? [
    [t('col.fio'), row.fullName], [t('col.innrma'), row.rma], [t('col.org'), orgName], [t('dt.tabnum'), row.tabNumber],
    [t('col.phone'), row.phone], [t('dt.license'), row.licenseNumber], [t('dt.cats'), row.licenseCategories],
    [t('dt.licvalidto'), row.licenseValidTo], [t('dt.degree'), row.degree],
    [t('dt.medcertnum'), row.medCertNumber], [t('col.medto'), row.medCertValidTo],
    [t('dt.safetyto'), row.safetyCourseValidTo], [t('dt.safetynum'), row.safetyCourseNumber],
    [t('dt.adrcertto'), row.adrCertValidTo],
    [t('col.assignedveh'), assignedVeh], [t('drv.f.passport'), row.passport], [t('col.address'), row.address],
    [t('drv.f.contractnum'), row.contractNumber], [t('drv.f.contractto'), row.contractValidTo],
    [t('dt.source'), source],
  ] : [
    [t('col.fio'), row.name], [t('col.innrma'), row.rma], [t('col.org'), orgName],
    [t('col.position'), empType(row.type, t)], [t('dt.tabnum'), row.tabNumber],
    [t('col.phone'), row.phone], [t('col.address'), row.address],
    [t('dt.source'), source],
  ];

  return (
    <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(15,32,60,.45)', zIndex: 60, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '6vh 16px', overflowY: 'auto' }}>
      <div onClick={e => e.stopPropagation()} className="card" style={{ maxWidth: 560, width: '100%', margin: 0 }}>
        <div className="card-h">
          <h2>{kind === 'vehicles' ? s(row.registrationNumber) : kind === 'drivers' ? s(row.fullName) : s(row.name)}</h2>
          <button className="btn secondary" style={{ marginLeft: 'auto' }} onClick={onClose}>✕</button>
        </div>
        <table>
          <tbody>
            {fields.map(([k, v]) => (
              <tr key={k}><th style={{ width: '42%', textAlign: 'left' }}>{k}</th><td>{s(v)}</td></tr>
            ))}
          </tbody>
        </table>
        <div style={{ marginTop: 10, fontSize: 12, color: 'var(--muted)' }}>
          {t('reg.detail.hint')} <a href="/company" style={{ color: 'var(--blue-600)' }}>«{t('nav.company')}»</a>
        </div>
      </div>
    </div>
  );
}

/**
 * Таблица одного реестра справочника (ТС / водители / сотрудники) по всем организациям.
 * Данные — GET-эндпоинты master-data без фильтра по организации: платформенному
 * администратору возвращают всё, тенант-ограниченным ролям — свою организацию.
 * Клик по строке — карточка записи; редактирование — в разделе «Компания».
 */
export default function RegistryView({ kind }: { kind: RegistryKind }) {
  const { t } = useT();
  const [rows, setRows] = useState<Row[]>([]);
  const [orgs, setOrgs] = useState<Row[]>([]);
  const [vehiclesForLink, setVehiclesForLink] = useState<Row[]>([]);
  const [q, setQ] = useState('');
  const [orgFilter, setOrgFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [posFilter, setPosFilter] = useState('');
  const [regionFilter, setRegionFilter] = useState('');
  const [cityFilter, setCityFilter] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<Row | null>(null);
  const [page, setPage] = useState(1);

  useEffect(() => { md.organizations().then(setOrgs).catch(e => setError(e.message)); }, []);
  // Для реестра водителей — карта ТС (id → госномер) для колонки «Номер транспорта».
  useEffect(() => {
    if (kind !== 'drivers') return;
    md.allVehicles().then(setVehiclesForLink).catch(() => setVehiclesForLink([]));
  }, [kind]);

  const reload = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const fn = kind === 'vehicles' ? md.allVehicles : kind === 'drivers' ? md.allDrivers : md.allEmployees;
      setRows(await fn());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [kind]);

  useEffect(() => { setQ(''); setOrgFilter(''); setTypeFilter(''); setPosFilter(''); setRegionFilter(''); setCityFilter(''); reload(); }, [reload]);

  const orgById = useMemo(() => new Map(orgs.map(o => [String(o.id), String(o.name ?? o.rma)])), [orgs]);
  const orgName = (row: Row) => orgById.get(String(row.organizationId)) ?? '—';
  // Регион/город записи определяются по организации-владельцу.
  const orgMeta = useMemo(() => new Map(orgs.map(o => [String(o.id), { region: String(o.regionId ?? ''), city: String(o.cityName ?? '') }])), [orgs]);
  // Каскад регион→город: список городов сужается по выбранному региону (при пустом — все).
  const orgCities = useMemo(() => Array.from(new Set(
    orgs.filter(o => !regionFilter || String(o.regionId ?? '') === regionFilter)
      .map(o => String(o.cityName ?? '')).filter(Boolean),
  )).sort(), [orgs, regionFilter]);
  // Карта закреплённого ТС (id → госномер) для колонки реестра водителей.
  const vehRegById = useMemo(() => new Map(vehiclesForLink.map(v => [String(v.id), String(v.registrationNumber ?? '')])), [vehiclesForLink]);
  const assignedVehReg = (row: Row) => (row.assignedVehicleId ? (vehRegById.get(String(row.assignedVehicleId)) ?? '—') : '—');

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return rows.filter(r => {
      if (orgFilter && String(r.organizationId) !== orgFilter) return false;
      if (typeFilter && kind === 'vehicles' && String(r.transportType) !== typeFilter) return false;
      if (posFilter && kind === 'employees' && String(r.type) !== posFilter) return false;
      if (regionFilter && (orgMeta.get(String(r.organizationId))?.region ?? '') !== regionFilter) return false;
      if (cityFilter && (orgMeta.get(String(r.organizationId))?.city ?? '') !== cityFilter) return false;
      if (!needle) return true;
      const company = orgById.get(String(r.organizationId)) ?? '';
      const hay = [company, r.registrationNumber, r.brand, r.vincode, r.fullName, r.name, r.rma, r.licenseNumber, r.tabNumber, r.phone, r.address]
        .filter(Boolean).map(String).join(' ').toLowerCase();
      return hay.includes(needle);
    });
  }, [rows, q, orgFilter, typeFilter, posFilter, regionFilter, cityFilter, kind, orgById, orgMeta]);

  // Сброс на первую страницу при изменении фильтров/поиска/данных.
  useEffect(() => { setPage(1); }, [q, orgFilter, typeFilter, posFilter, regionFilter, cityFilter, rows]);
  const pages = Math.max(1, Math.ceil(filtered.length / PER_PAGE));
  const safePage = Math.min(page, pages);
  const view = filtered.slice((safePage - 1) * PER_PAGE, safePage * PER_PAGE);

  const emptyText = loading ? t('common.loading') : t('common.norecords');

  return (
    <div className="card">
      <div className="card-h">
        <h2>{t(LABEL_KEY[kind])} · {t('reg.shown')} {filtered.length} {t('paging.of')} {rows.length}</h2>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {kind === 'vehicles' && (
            <select value={typeFilter} onChange={e => setTypeFilter(e.target.value)} style={{ width: 190 }}>
              <option value="">{t('flt.allvehkinds')}</option>
              {Object.entries(TRANSPORT_TYPES).map(([v]) => <option key={v} value={v}>{t('veh.type.' + v)}</option>)}
            </select>
          )}
          {kind === 'employees' && (
            <select value={posFilter} onChange={e => setPosFilter(e.target.value)} style={{ width: 190 }}>
              <option value="">{t('flt.allpositions')}</option>
              {Object.entries(EMPLOYEE_TYPES).map(([v]) => <option key={v} value={v}>{t('emp.type.' + v)}</option>)}
            </select>
          )}
          <select value={regionFilter} onChange={e => {
            const v = e.target.value;
            setRegionFilter(v);
            // Каскад: при смене региона сбрасываем город, если он не относится к новому региону.
            if (cityFilter && v && !orgs.some(o => String(o.regionId ?? '') === v && String(o.cityName ?? '') === cityFilter)) setCityFilter('');
          }} style={{ width: 160 }}>
            <option value="">{t('flt.allregions')}</option>
            {[1, 2, 3, 4, 5, 6, 7].map(n => <option key={n} value={String(n)}>{t('region.' + n)}</option>)}
          </select>
          <select value={cityFilter} onChange={e => setCityFilter(e.target.value)} style={{ width: 150 }}>
            <option value="">{t('flt.allcities')}</option>
            {orgCities.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
          <select value={orgFilter} onChange={e => setOrgFilter(e.target.value)} style={{ width: 240 }}>
            <option value="">{t('reg.allorgs')}</option>
            {orgs.map(o => <option key={String(o.id)} value={String(o.id)}>{String(o.name ?? o.rma)}</option>)}
          </select>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('reg.search')} style={{ width: 240 }} />
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {kind === 'vehicles' && (
        <table>
          <thead><tr><th>{t('col.regnum')}</th><th>{t('col.type')}</th><th>{t('col.brand')}</th><th>{t('col.org')}</th><th>{t('col.odometer')}</th><th>{t('col.techto')}</th><th>VIN</th><th>{t('col.actions')}</th></tr></thead>
          <tbody>
            {view.map(r => (
              <tr key={String(r.id)}>
                <td><span className="number">{s(r.registrationNumber)}</span></td>
                <td>{vehType(r.transportType, t)}</td>
                <td>{s(r.brand)}</td>
                <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{orgName(r)}</td>
                <td>{s(r.odometer)}</td>
                <td>{s(r.techInspectionValidTo)}</td>
                <td><span className="number" style={{ fontSize: 11 }}>{s(r.vincode)}</span></td>
                <td>
                  <button
                    type="button"
                    className="btn secondary"
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '4px 10px', fontSize: 12 }}
                    onClick={() => setDetail(r)}
                    title={t('btn.view')}
                  >
                    <Icon d={P.eye} cls="" /> {t('btn.view')}
                  </button>
                </td>
              </tr>
            ))}
            {filtered.length === 0 && <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 22 }}>{emptyText}</td></tr>}
          </tbody>
        </table>
      )}

      {kind === 'drivers' && (
        <table>
          <thead><tr><th>{t('col.fio')}</th><th>{t('col.innrma')}</th><th>{t('col.org')}</th><th>{t('col.phone')}</th><th>{t('tech.categories')}</th><th>{t('col.licto')}</th><th>{t('col.assignedveh')}</th><th>{t('col.actions')}</th></tr></thead>
          <tbody>
            {view.map(r => (
              <tr key={String(r.id)}>
                <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{s(r.fullName)}</td>
                <td><span className="number">{s(r.rma)}</span></td>
                <td>{orgName(r)}</td>
                <td>{s(r.phone)}</td>
                <td>{s(r.licenseCategories)}</td>
                <td>{s(r.licenseValidTo)}</td>
                <td><span className="number">{assignedVehReg(r)}</span></td>
                <td>
                  <button
                    type="button"
                    className="btn secondary"
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '4px 10px', fontSize: 12 }}
                    onClick={() => setDetail(r)}
                    title={t('btn.view')}
                  >
                    <Icon d={P.eye} cls="" /> {t('btn.view')}
                  </button>
                </td>
              </tr>
            ))}
            {filtered.length === 0 && <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 22 }}>{emptyText}</td></tr>}
          </tbody>
        </table>
      )}

      {kind === 'employees' && (
        <table>
          <thead><tr><th>{t('col.fio')}</th><th>{t('col.innrma')}</th><th>{t('col.org')}</th><th>{t('col.position')}</th><th>{t('col.phone')}</th><th>{t('col.address')}</th><th>{t('col.actions')}</th></tr></thead>
          <tbody>
            {view.map(r => (
              <tr key={String(r.id)}>
                <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{s(r.name)}</td>
                <td><span className="number">{s(r.rma)}</span></td>
                <td>{orgName(r)}</td>
                <td>{empType(r.type, t)}</td>
                <td>{s(r.phone)}</td>
                <td>{s(r.address)}</td>
                <td>
                  <button
                    type="button"
                    className="btn secondary"
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '4px 10px', fontSize: 12 }}
                    onClick={() => setDetail(r)}
                    title={t('btn.view')}
                  >
                    <Icon d={P.eye} cls="" /> {t('btn.view')}
                  </button>
                </td>
              </tr>
            ))}
            {filtered.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 22 }}>{emptyText}</td></tr>}
          </tbody>
        </table>
      )}

      <div style={{ display: 'flex', alignItems: 'center', marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>
        <span>{t('dash.total')}: <b style={{ color: 'var(--ink)' }}>{filtered.length}</b>{rows.length !== filtered.length ? ` ${t('paging.of')} ${rows.length}` : ''}</span>
        <span style={{ flex: 1 }} />
        <button className="btn secondary" disabled={safePage <= 1} onClick={() => setPage(p => Math.max(1, p - 1))} style={{ padding: '5px 11px' }}>‹</button>
        <span style={{ margin: '0 12px' }}>{safePage} / {pages}</span>
        <button className="btn secondary" disabled={safePage >= pages} onClick={() => setPage(p => Math.min(pages, p + 1))} style={{ padding: '5px 11px' }}>›</button>
      </div>

      {detail && <DetailModal kind={kind} row={detail} orgName={orgName(detail)} assignedVeh={assignedVehReg(detail)} onClose={() => setDetail(null)} t={t} />}
    </div>
  );
}
