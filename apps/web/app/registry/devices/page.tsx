'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { md, type MobileDevice } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

// Платформенные роли видят устройства всех организаций (тенант — только свою, по токену).
const PLATFORM_ROLES = ['SYSTEM_ADMIN', 'MINTRANS_ANALYST', 'API_INTEGRATOR'];

const s = (v: unknown) => (v == null || v === '' ? '—' : String(v));

// Постраничный вывод — как в остальных реестрах (решение владельца 22.09): на боевом объёме
// список устройств рос неограниченно и рендерился целиком.
const PER_PAGE = 20;

/**
 * Реестр авторизованных мобильных устройств водителей — перенос legacy-справочника «Телефонҳо»
 * (phone_infos). Мультиарендно: платформенная роль видит все организации (с фильтром по
 * организации, как в мониторе сроков /settings/expiry), тенант — только свою (по токену).
 * Изменение (добавление/удаление) — только SYSTEM_ADMIN.
 */
export default function DevicesRegistryPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const isPlatform = PLATFORM_ROLES.some(r => roles.includes(r));
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [rows, setRows] = useState<MobileDevice[]>([]);
  const [orgs, setOrgs] = useState<Record<string, unknown>[]>([]);
  const [orgFilter, setOrgFilter] = useState('');
  const [q, setQ] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState({ organizationRma: '', driverName: '', driverRma: '', brand: '', model: '' });
  const [page, setPage] = useState(1);

  // Список организаций — для колонки «Корхона» (rma → название) и селектора фильтра/формы.
  useEffect(() => {
    md.organizations().then(setOrgs).catch(() => setOrgs([]));
  }, []);

  const reload = useCallback(async () => {
    setLoading(true); setError('');
    try {
      // Платформенной роли селектор сужает выборку на бэкенде; тенанту фильтр не нужен (скоуп по токену).
      setRows(await md.mobileDevices(isPlatform ? (orgFilter || undefined) : undefined));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [isPlatform, orgFilter]);

  useEffect(() => { reload(); }, [reload]);

  const orgName = useMemo(() => {
    const map = new Map(orgs.map(o => [String(o.rma), String(o.name ?? o.rma)]));
    return (rma: string) => map.get(rma) ?? rma;
  }, [orgs]);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter(r => {
      const hay = [orgName(r.organizationRma), r.driverName, r.driverRma, r.brand, r.model]
        .filter(Boolean).map(String).join(' ').toLowerCase();
      return hay.includes(needle);
    });
  }, [rows, q, orgName]);

  // Сброс на первую страницу при смене фильтров/данных.
  useEffect(() => { setPage(1); }, [q, orgFilter, rows]);
  const pages = Math.max(1, Math.ceil(filtered.length / PER_PAGE));
  const safePage = Math.min(page, pages);
  const view = useMemo(() => filtered.slice((safePage - 1) * PER_PAGE, safePage * PER_PAGE), [filtered, safePage]);

  const fmt = (iso: string) => {
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleString('ru-RU');
  };

  async function add() {
    setBusy(true); setError('');
    try {
      await md.saveMobileDevice({
        organizationRma: form.organizationRma.trim(),
        driverName: form.driverName.trim(),
        driverRma: form.driverRma.trim() || null,
        brand: form.brand.trim() || null,
        model: form.model.trim(),
      });
      setForm({ organizationRma: '', driverName: '', driverRma: '', brand: '', model: '' });
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    setBusy(true); setError('');
    try { await md.deleteMobileDevice(id); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  const canAdd = form.organizationRma.trim() && form.driverName.trim() && form.model.trim();
  const emptyText = loading ? t('common.loading') : t('common.norecords');

  return (
    <div className="card">
      <div className="card-h">
        <h2>{t('dev.title')}</h2>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {isPlatform && (
            <select value={orgFilter} onChange={e => setOrgFilter(e.target.value)} style={{ width: 240 }}>
              <option value="">{t('reg.allorgs')}</option>
              {orgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{String(o.name ?? o.rma)}</option>)}
            </select>
          )}
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('dev.search')} style={{ width: 240 }} />
        </div>
      </div>

      <div className="hint" style={{ marginBottom: 12 }}>{t('dev.note')}</div>
      {error && <div className="error" style={{ marginBottom: 12 }}>{error}</div>}

      <table>
        <thead>
          <tr>
            <th>{t('col.org')}</th>
            <th>{t('col.fio')}</th>
            <th>{t('dev.col.rma')}</th>
            <th>{t('col.brand')}</th>
            <th>{t('col.model')}</th>
            <th>{t('dev.col.authat')}</th>
            {isSysAdmin && <th style={{ width: 60 }} />}
          </tr>
        </thead>
        <tbody>
          {view.map(r => (
            <tr key={r.id}>
              <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{orgName(r.organizationRma)}</td>
              <td>{s(r.driverName)}</td>
              <td><span className="number">{s(r.driverRma)}</span></td>
              <td>{s(r.brand)}</td>
              <td>{s(r.model)}</td>
              <td style={{ fontVariantNumeric: 'tabular-nums' }}>{fmt(r.authorizedAt)}</td>
              {isSysAdmin && (
                <td>
                  <button title={t('pol.remove')} disabled={busy} onClick={() => remove(r.id)}
                    style={{ background: 'none', border: '1px solid var(--line)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--red)' }}>
                    <Icon d={P.trash} cls="" style={{ width: 15, height: 15 }} />
                  </button>
                </td>
              )}
            </tr>
          ))}
          {filtered.length === 0 && (
            <tr><td colSpan={isSysAdmin ? 7 : 6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 22 }}>{emptyText}</td></tr>
          )}
        </tbody>
      </table>

      <div style={{ display: 'flex', alignItems: 'center', marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>
        <span>{t('dash.total')}: <b style={{ color: 'var(--ink)' }}>{filtered.length}</b>{rows.length !== filtered.length ? ` ${t('paging.of')} ${rows.length}` : ''}</span>
        <span style={{ flex: 1 }} />
        <button className="btn secondary" disabled={safePage <= 1} onClick={() => setPage(p => Math.max(1, p - 1))} style={{ padding: '5px 11px' }}>‹</button>
        <span style={{ margin: '0 12px' }}>{safePage} / {pages}</span>
        <button className="btn secondary" disabled={safePage >= pages} onClick={() => setPage(p => Math.min(pages, p + 1))} style={{ padding: '5px 11px' }}>›</button>
      </div>

      {!isSysAdmin && (
        <div style={{ color: 'var(--muted)', fontSize: 13, marginTop: 12 }}>{t('dev.readonly')}</div>
      )}

      {isSysAdmin && (
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('col.org')}
            <select value={form.organizationRma} onChange={e => setForm({ ...form, organizationRma: e.target.value })} style={{ width: 220 }}>
              <option value="">—</option>
              {orgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{String(o.name ?? o.rma)}</option>)}
            </select>
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('col.fio')}
            <input value={form.driverName} onChange={e => setForm({ ...form, driverName: e.target.value })} style={{ width: 200 }} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('dev.col.rma')}
            <input value={form.driverRma} onChange={e => setForm({ ...form, driverRma: e.target.value })} style={{ width: 140 }} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('col.brand')}
            <input value={form.brand} onChange={e => setForm({ ...form, brand: e.target.value })} style={{ width: 150 }} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('col.model')}
            <input value={form.model} onChange={e => setForm({ ...form, model: e.target.value })} style={{ width: 160 }} />
          </label>
          <button className="btn" disabled={busy || !canAdd} onClick={add}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('dev.add')}
          </button>
        </div>
      )}
    </div>
  );
}
