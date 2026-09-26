'use client';

import { useCallback, useEffect, useState } from 'react';
import { md, type ExternalCity, type ClassifierItem } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

const PER_PAGE = 20;

/**
 * Справочник внешних (зарубежных) городов — источник подсказок для поля «Город» в международных
 * путевых листах и СМР. По образцу справочника классификаторов (страны/ADR): фильтр по стране +
 * таблица с переключателем активности, добавлением и удалением. Правит только SYSTEM_ADMIN
 * (единый для платформы нац. справочник); остальным — только чтение.
 */
export default function ExternalCitiesPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [countries, setCountries] = useState<ClassifierItem[]>([]);
  const [country, setCountry] = useState<string>('');
  const [rows, setRows] = useState<ExternalCity[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ nameRu: '', nameTj: '' });
  const [page, setPage] = useState(1);
  // Поиск по названию во всех странах (сверка 25.09, E6): городов 18 тыс., страну не всегда знают.
  const [q, setQ] = useState('');
  const searching = q.trim().length >= 2;

  // Страны (классификатор COUNTRY, только активные) — для фильтра и привязки нового города.
  useEffect(() => {
    md.classifiers('COUNTRY')
      .then(cs => { setCountries(cs); if (cs.length) setCountry(cs[0].code); })
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  const reload = useCallback(async () => {
    if (searching) {
      try { setRows(await md.externalCities(undefined, q.trim())); setError(''); }
      catch (e) { setError(e instanceof Error ? e.message : String(e)); }
      return;
    }
    if (!country) { setRows([]); return; }
    try { setRows(await md.externalCities(country)); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, [country, q, searching]);

  useEffect(() => { setForm({ nameRu: '', nameTj: '' }); setPage(1); const h = setTimeout(reload, 250); return () => clearTimeout(h); }, [reload]);

  async function save(body: Record<string, unknown>) {
    setBusy(true); setError('');
    try { await md.saveExternalCity(body); setForm({ nameRu: '', nameTj: '' }); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function remove(id: string) {
    setBusy(true); setError('');
    try { await md.deleteExternalCity(id); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  const countryName = (code: string) => countries.find(c => c.code === code)?.nameRu ?? code;

  const pages = Math.max(1, Math.ceil(rows.length / PER_PAGE));
  const view = rows.slice((page - 1) * PER_PAGE, page * PER_PAGE);

  return (
    <>
      <div className="hint" style={{ marginBottom: 14 }}>{t('extcity.note')}</div>
      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}

      <div style={{ display: 'flex', gap: 10, marginBottom: 16, alignItems: 'center', flexWrap: 'wrap' }}>
        <label style={{ fontSize: 13, color: 'var(--muted)' }}>{t('extcity.country')}</label>
        <select value={country} onChange={e => { setCountry(e.target.value); setPage(1); }} style={{ minWidth: 220 }} disabled={searching}>
          {countries.map(c => <option key={c.code} value={c.code}>{c.nameRu}</option>)}
        </select>
        <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('extcity.search')} style={{ width: 260 }}
          data-testid="extcity-q" />
      </div>

      <div className="card">
        <div className="card-h">
          <h2>{t('extcity.title')}: {searching ? t('extcity.allcountries') : (country ? countryName(country) : '—')}</h2>
          <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 12.5 }}>{t('dict.totalrecords')}: {rows.length}</span>
        </div>
        <table>
          <thead>
            <tr>
              {searching && <th>{t('extcity.country')}</th>}
              <th>{t('cls.name.ru')}</th>
              <th>{t('cls.name.tj')}</th>
              <th style={{ width: 90 }}>{t('cls.active')}</th>
              <th style={{ width: 60 }} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={5} style={{ color: 'var(--muted)', textAlign: 'center', padding: 20 }}>{t('common.norecords')}</td></tr>}
            {view.map(r => (
              <tr key={r.id} style={{ opacity: r.active ? 1 : 0.5 }} data-testid="extcity-row">
                {searching && <td>{countryName(r.countryCode)}</td>}
                <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{r.nameRu}</td>
                <td>{r.nameTj ?? '—'}</td>
                <td>
                  <button type="button" className={`badge ${r.active ? 'green' : 'gray'}`}
                    style={{ cursor: isSysAdmin ? 'pointer' : 'default', border: 'none', opacity: isSysAdmin ? 1 : 0.7 }}
                    disabled={!isSysAdmin || busy}
                    onClick={() => save({ countryCode: r.countryCode, nameRu: r.nameRu, nameTj: r.nameTj, sortOrder: r.sortOrder, active: !r.active })}>
                    {r.active ? t('pol.on') : t('pol.off')}
                  </button>
                </td>
                <td>
                  {isSysAdmin && (
                    <button title={t('pol.remove')} disabled={busy} onClick={() => remove(r.id)}
                      style={{ background: 'none', border: '1px solid var(--line)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--red)' }}>
                      <Icon d={P.trash} cls="" style={{ width: 15, height: 15 }} />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        {/* Пагинация */}
        <div style={{ display: 'flex', alignItems: 'center', marginTop: 14, fontSize: 13, color: 'var(--muted)' }}>
          <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{rows.length}</b></span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
          <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>

        {!isSysAdmin && (
          <div style={{ color: 'var(--muted)', fontSize: 13, marginTop: 12 }}>{t('extcity.readonly')}</div>
        )}

        {isSysAdmin && country && !searching && (
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('cls.name.ru')}
              <input value={form.nameRu} onChange={e => setForm({ ...form, nameRu: e.target.value })} style={{ width: 220 }} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('cls.name.tj')}
              <input value={form.nameTj} onChange={e => setForm({ ...form, nameTj: e.target.value })} style={{ width: 220 }} />
            </label>
            <button className="btn" disabled={busy || !form.nameRu.trim()}
              onClick={() => save({ countryCode: country, nameRu: form.nameRu.trim(), nameTj: form.nameTj.trim() || null })}>
              <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('cls.add')}
            </button>
          </div>
        )}
      </div>
    </>
  );
}
