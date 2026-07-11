'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { md } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type Row = Record<string, unknown>;
type Tab = 'vehicles' | 'drivers' | 'employees';

const EMPLOYEE_TYPES: Record<number, string> = { 1: 'Врач (духтур)', 2: 'Механик', 3: 'Диспетчер (танзимгар)' };
const TRANSPORT_TYPES: Record<number, string> = {
  1: 'Автобус', 2: 'Троллейбус', 3: 'Микроавтобус', 4: 'Легковой (сабукрав)', 5: 'Грузовой (2-Б)', 6: 'Грузовой межд. (5Б-БМ)',
};

const TABS: { key: Tab; labelKey: string; icon: string }[] = [
  { key: 'vehicles', labelKey: 'col.transport', icon: P.car },
  { key: 'drivers', labelKey: 'col.drivers', icon: P.users },
  { key: 'employees', labelKey: 'col.employees', icon: P.building },
];

/** Плашка источника/статуса записи (source из единой платформы — из ЕПМ, иначе ручной ввод). */
function sourceBadge(row: Row, t: (k: string) => string) {
  if (row.suspended) return <span className="badge red">{t('insp.v.blocked.t')}</span>;
  return String(row.source) === 'UNIFIED'
    ? <span className="badge blue">{t('reg.src.unified')}</span>
    : <span className="badge gray">{t('comp.src.manual')}</span>;
}

/**
 * Глобальные реестры справочника: все ТС, водители и сотрудники по всем организациям.
 * Данные — те же GET-эндпоинты master-data (без фильтра по организации): для
 * платформенного администратора возвращают всё, для арендо-ограниченных ролей — свою организацию.
 */
export default function RegistryPage() {
  const { t } = useT();
  const [tab, setTab] = useState<Tab>('vehicles');
  const [rows, setRows] = useState<Row[]>([]);
  const [orgs, setOrgs] = useState<Row[]>([]);
  const [q, setQ] = useState('');
  const [orgFilter, setOrgFilter] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => { md.organizations().then(setOrgs).catch(e => setError(e.message)); }, []);

  const reload = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const fn = tab === 'vehicles' ? md.allVehicles : tab === 'drivers' ? md.allDrivers : md.allEmployees;
      setRows(await fn());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [tab]);

  useEffect(() => { setQ(''); setOrgFilter(''); reload(); }, [reload]);

  const orgById = useMemo(() => new Map(orgs.map(o => [String(o.id), String(o.name ?? o.rma)])), [orgs]);
  const orgName = (row: Row) => orgById.get(String(row.organizationId)) ?? '—';

  const filtered = useMemo(() => {
    const s = q.trim().toLowerCase();
    return rows.filter(r => {
      if (orgFilter && String(r.organizationId) !== orgFilter) return false;
      if (!s) return true;
      const company = orgById.get(String(r.organizationId)) ?? '';
      const hay = [company, r.registrationNumber, r.brand, r.vincode, r.fullName, r.name, r.rma, r.licenseNumber, r.tabNumber, r.phone]
        .filter(Boolean).map(String).join(' ').toLowerCase();
      return hay.includes(s);
    });
  }, [rows, q, orgFilter, orgById]);

  const active = TABS.find(x => x.key === tab)!;
  const emptyText = loading ? t('common.loading') : t('common.norecords');

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.registry')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('reg.lead')}</div>
        </div>
        <span className="spacer" />
        <a className="btn secondary" href="/company">{t('reg.managelink')}</a>
      </div>

      {error && <div className="error">{error}</div>}

      {/* Разделы реестра — карточки-переключатели */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
        {TABS.map(s => {
          const sel = s.key === tab;
          return (
            <button
              key={s.key}
              onClick={() => setTab(s.key)}
              className="kpi"
              style={{
                cursor: 'pointer', textAlign: 'left', gap: 12, fontFamily: 'inherit',
                border: sel ? '1.5px solid var(--blue-500)' : '1px solid var(--line)',
                boxShadow: sel ? '0 6px 16px -6px rgba(37,99,235,.4)' : 'var(--shadow-sm)',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <span className="k-ic ic-blue"><Icon d={s.icon} cls="" /></span>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 700, fontSize: 14, color: sel ? 'var(--blue-700)' : 'var(--ink)' }}>{t(s.labelKey)}</div>
                  <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{sel ? `${t('reg.shown')} ${filtered.length} ${t('paging.of')} ${rows.length}` : t('reg.openregistry')}</div>
                </div>
              </div>
            </button>
          );
        })}
      </div>

      {/* Фильтры + таблица */}
      <div className="card">
        <div className="card-h">
          <h2>{t(active.labelKey)}</h2>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
            <select value={orgFilter} onChange={e => setOrgFilter(e.target.value)} style={{ width: 240 }}>
              <option value="">{t('reg.allorgs')}</option>
              {orgs.map(o => <option key={String(o.id)} value={String(o.id)}>{String(o.name ?? o.rma)}</option>)}
            </select>
            <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('reg.search')} style={{ width: 260 }} />
          </div>
        </div>

        {tab === 'vehicles' && (
          <table>
            <thead><tr><th>{t('col.regnum')}</th><th>{t('col.type')}</th><th>{t('col.brand')}</th><th>{t('col.org')}</th><th>{t('col.odometer')}</th><th>{t('col.techto')}</th><th>{t('col.source')}</th></tr></thead>
            <tbody>
              {filtered.map(r => (
                <tr key={String(r.id)}>
                  <td><span className="number">{String(r.registrationNumber)}</span></td>
                  <td>{TRANSPORT_TYPES[Number(r.transportType)] ?? String(r.transportType)}</td>
                  <td>{String(r.brand ?? '—')}</td>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{orgName(r)}</td>
                  <td>{String(r.odometer ?? '—')}</td>
                  <td>{String(r.techInspectionValidTo ?? '—')}</td>
                  <td>{sourceBadge(r, t)}</td>
                </tr>
              ))}
              {filtered.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 22 }}>{emptyText}</td></tr>}
            </tbody>
          </table>
        )}

        {tab === 'drivers' && (
          <table>
            <thead><tr><th>{t('col.fio')}</th><th>{t('col.innrma')}</th><th>{t('col.org')}</th><th>{t('tech.license')}</th><th>{t('tech.categories')}</th><th>{t('col.licto')}</th><th>{t('col.medto')}</th><th>{t('col.source')}</th></tr></thead>
            <tbody>
              {filtered.map(r => (
                <tr key={String(r.id)}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.fullName)}</td>
                  <td><span className="number">{String(r.rma)}</span></td>
                  <td>{orgName(r)}</td>
                  <td>{String(r.licenseNumber ?? '—')}</td>
                  <td>{String(r.licenseCategories ?? '—')}</td>
                  <td>{String(r.licenseValidTo ?? '—')}</td>
                  <td>{String(r.medCertValidTo ?? '—')}</td>
                  <td>{sourceBadge(r, t)}</td>
                </tr>
              ))}
              {filtered.length === 0 && <tr><td colSpan={8} style={{ textAlign: 'center', color: 'var(--muted)', padding: 22 }}>{emptyText}</td></tr>}
            </tbody>
          </table>
        )}

        {tab === 'employees' && (
          <table>
            <thead><tr><th>{t('col.fio')}</th><th>{t('col.innrma')}</th><th>{t('col.org')}</th><th>{t('col.position')}</th><th>{t('col.phone')}</th><th>{t('col.source')}</th></tr></thead>
            <tbody>
              {filtered.map(r => (
                <tr key={String(r.id)}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{String(r.name)}</td>
                  <td><span className="number">{String(r.rma)}</span></td>
                  <td>{orgName(r)}</td>
                  <td>{EMPLOYEE_TYPES[Number(r.type)] ?? String(r.type)}</td>
                  <td>{String(r.phone ?? '—')}</td>
                  <td>{sourceBadge(r, t)}</td>
                </tr>
              ))}
              {filtered.length === 0 && <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 22 }}>{emptyText}</td></tr>}
            </tbody>
          </table>
        )}

        <div style={{ marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>
          {t('dash.total')}: {filtered.length}{rows.length !== filtered.length ? ` ${t('paging.of')} ${rows.length}` : ''}
        </div>
      </div>
    </>
  );
}
