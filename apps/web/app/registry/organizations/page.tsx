'use client';

import { useEffect, useMemo, useState } from 'react';
import { md } from '@/lib/api';
import { downloadCsv } from '@/lib/csv';
import { useT } from '@/lib/i18n';

type Org = Record<string, unknown>;
const PER_PAGE = 50;
const s = (v: unknown) => (v == null || v === '' ? '—' : String(v));

/**
 * Реестр организаций (сверка 25.09, F10): аналитику Минтранса реестр перевозчиков был недоступен —
 * список организаций открывался только администратору платформы в разделе «Компания». Здесь — только
 * просмотр: название, РМА, регион, город, вид (общего пользования / для собственных нужд), лицензия,
 * число ТС и водителей, статус; поиск, фильтры и выгрузка в CSV. Область — по токену (тенант видит свои).
 */
export default function OrganizationsRegistryPage() {
  const { t } = useT();
  const [orgs, setOrgs] = useState<Org[]>([]);
  const [counts, setCounts] = useState<Record<string, { vehicles: number; drivers: number }>>({});
  const [f, setF] = useState({ q: '', region: '', type: '', status: '' });
  const [page, setPage] = useState(1);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    md.organizations().then(setOrgs).catch(e => setError((e as Error).message)).finally(() => setLoading(false));
    md.organizationCounts()
      .then(rows => setCounts(Object.fromEntries(rows.map(r => [r.rma, { vehicles: r.vehicles, drivers: r.drivers }]))))
      .catch(() => setCounts({}));
  }, []);

  const today = new Date().toISOString().slice(0, 10);
  const status = (o: Org) => (o.blocked ? 'blocked' : o.licenseTo && String(o.licenseTo) < today ? 'licexpired' : 'active');

  const rows = useMemo(() => {
    const q = f.q.trim().toLowerCase();
    return orgs
      .filter(o => !q || `${o.name ?? ''} ${o.rma ?? ''} ${o.cityName ?? ''} ${o.carrierLicenseNumber ?? ''}`.toLowerCase().includes(q))
      .filter(o => !f.region || String(o.regionId ?? '') === f.region)
      .filter(o => !f.type || String(o.typeCompany ?? '') === f.type)
      .filter(o => !f.status || status(o) === f.status)
      .sort((a, b) => String(a.name ?? '').localeCompare(String(b.name ?? ''), 'ru'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orgs, f]);

  const pages = Math.max(1, Math.ceil(rows.length / PER_PAGE));
  const shown = rows.slice((page - 1) * PER_PAGE, page * PER_PAGE);
  const set = (k: keyof typeof f, v: string) => { setF(x => ({ ...x, [k]: v })); setPage(1); };
  const typeLabel = (v: unknown) => (String(v) === '2' ? t('regorg.type2') : t('regorg.type1'));
  const statusBadge = (o: Org) => {
    const st = status(o);
    const cls = st === 'blocked' ? 'red' : st === 'licexpired' ? 'amber' : 'green';
    return <span className={`badge ${cls}`}>{t(`regorg.st.${st}`)}</span>;
  };

  function exportCsv() {
    const head = [t('col.org'), 'РМА', t('regorg.region'), t('regorg.city'), t('regorg.type'), t('regorg.license'),
      t('regorg.licto'), t('col.transport'), t('col.drivers'), t('col.status')];
    downloadCsv(`organizations_${today}.csv`, [head, ...rows.map(o => [
      o.name, o.rma, o.regionId ? t(`region.${o.regionId}`) : '', o.cityName, typeLabel(o.typeCompany),
      o.carrierLicenseNumber, o.licenseTo, counts[String(o.rma)]?.vehicles ?? 0, counts[String(o.rma)]?.drivers ?? 0,
      t(`regorg.st.${status(o)}`)])]);
  }

  return (
    <div className="card" style={{ padding: 16 }}>
      {error && <div className="error">{error}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 10, alignItems: 'end', marginBottom: 12 }}>
        <div style={{ gridColumn: 'span 2' }}><label>{t('wb.f.search')}</label>
          <input aria-label={t('wb.f.search')} value={f.q} placeholder={t('regorg.search')} onChange={e => set('q', e.target.value)} /></div>
        <div><label>{t('regorg.region')}</label>
          <select aria-label={t('regorg.region')} value={f.region} onChange={e => set('region', e.target.value)}>
            <option value="">{t('regorg.all')}</option>
            {[1, 2, 3, 4, 5, 6, 7].map(r => <option key={r} value={String(r)}>{t(`region.${r}`)}</option>)}
          </select></div>
        <div><label>{t('regorg.type')}</label>
          <select aria-label={t('regorg.type')} value={f.type} onChange={e => set('type', e.target.value)}>
            <option value="">{t('regorg.all')}</option>
            <option value="1">{t('regorg.type1')}</option><option value="2">{t('regorg.type2')}</option>
          </select></div>
        <div><label>{t('col.status')}</label>
          <select aria-label={t('col.status')} value={f.status} onChange={e => set('status', e.target.value)}>
            <option value="">{t('regorg.all')}</option>
            <option value="active">{t('regorg.st.active')}</option>
            <option value="licexpired">{t('regorg.st.licexpired')}</option>
            <option value="blocked">{t('regorg.st.blocked')}</option>
          </select></div>
        <div><button type="button" className="btn secondary" onClick={exportCsv} disabled={rows.length === 0}>⬇ CSV</button></div>
      </div>

      <div style={{ overflowX: 'auto' }}>
        <table className="dense" data-testid="orgs-table">
          <thead><tr>
            <th>{t('col.org')}</th><th>РМА</th><th>{t('regorg.region')}</th><th>{t('regorg.city')}</th><th>{t('regorg.type')}</th>
            <th>{t('regorg.license')}</th><th>{t('regorg.licto')}</th>
            <th style={{ textAlign: 'right' }}>{t('col.transport')}</th><th style={{ textAlign: 'right' }}>{t('col.drivers')}</th><th>{t('col.status')}</th>
          </tr></thead>
          <tbody>
            {shown.map(o => (
              <tr key={String(o.id)}>
                <td>{s(o.name)}{o.parentRma ? <span style={{ color: 'var(--muted)', fontSize: 12 }}> · {t('regorg.branch')}</span> : null}</td>
                <td>{s(o.rma)}</td>
                <td>{o.regionId ? t(`region.${o.regionId}`) : '—'}</td>
                <td>{s(o.cityName)}</td>
                <td>{typeLabel(o.typeCompany)}</td>
                <td>{s(o.carrierLicenseNumber)}</td>
                <td>{s(o.licenseTo)}</td>
                <td style={{ textAlign: 'right' }}>{(counts[String(o.rma)]?.vehicles ?? 0).toLocaleString('ru-RU')}</td>
                <td style={{ textAlign: 'right' }}>{(counts[String(o.rma)]?.drivers ?? 0).toLocaleString('ru-RU')}</td>
                <td title={o.blocked && o.blockReason ? String(o.blockReason) : undefined}>{statusBadge(o)}</td>
              </tr>
            ))}
            {!loading && rows.length === 0 && <tr><td colSpan={10} style={{ textAlign: 'center', color: 'var(--muted)', padding: 24 }}>{t('cn.notfound')}</td></tr>}
          </tbody>
        </table>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingTop: 12, fontSize: 13, color: 'var(--muted)' }}>
        <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{rows.length}</b></span>
        <span style={{ flex: 1 }} />
        <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
        <span>{page} / {pages}</span>
        <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
      </div>
    </div>
  );
}
