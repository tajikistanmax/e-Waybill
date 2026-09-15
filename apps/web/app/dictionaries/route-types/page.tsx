'use client';

import { useCallback, useEffect, useState } from 'react';
import { md, type RouteType } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/**
 * Справочник типов маршрутов — классификация маршрута по дальности сообщения
 * (городской/пригородный/междугородный/…). По образцу справочника внешних городов, но проще:
 * плоский список без фильтра по стране и без пагинации (типов немного). Ключ — числовой код.
 * Правит только SYSTEM_ADMIN (единый для платформы нац. справочник); остальным — только чтение.
 */
export default function RouteTypesPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [rows, setRows] = useState<RouteType[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ code: '', nameRu: '', nameTj: '' });

  const reload = useCallback(async () => {
    try { setRows(await md.routeTypes()); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  async function save(body: Record<string, unknown>) {
    setBusy(true); setError('');
    try { await md.saveRouteType(body); setForm({ code: '', nameRu: '', nameTj: '' }); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function remove(id: string) {
    setBusy(true); setError('');
    try { await md.deleteRouteType(id); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  const codeNum = Number(form.code);
  const canAdd = Number.isInteger(codeNum) && codeNum >= 1 && form.nameRu.trim().length > 0;

  return (
    <>
      <div className="hint" style={{ marginBottom: 14 }}>{t('routetype.note')}</div>
      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}

      <div className="card">
        <div className="card-h">
          <h2>{t('routetype.title')}</h2>
          <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 12.5 }}>{t('dict.totalrecords')}: {rows.length}</span>
        </div>
        <table>
          <thead>
            <tr>
              <th style={{ width: 90 }}>{t('cls.code')}</th>
              <th>{t('cls.name.ru')}</th>
              <th>{t('cls.name.tj')}</th>
              <th style={{ width: 90 }}>{t('cls.active')}</th>
              <th style={{ width: 60 }} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={5} style={{ color: 'var(--muted)', textAlign: 'center', padding: 20 }}>{t('common.norecords')}</td></tr>}
            {rows.map(r => (
              <tr key={r.id} style={{ opacity: r.active ? 1 : 0.5 }}>
                <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{r.code}</td>
                <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{r.nameRu}</td>
                <td>{r.nameTj ?? '—'}</td>
                <td>
                  <button type="button" className={`badge ${r.active ? 'green' : 'gray'}`}
                    style={{ cursor: isSysAdmin ? 'pointer' : 'default', border: 'none', opacity: isSysAdmin ? 1 : 0.7 }}
                    disabled={!isSysAdmin || busy}
                    onClick={() => save({ code: r.code, nameRu: r.nameRu, nameTj: r.nameTj, sortOrder: r.sortOrder, active: !r.active })}>
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

        {!isSysAdmin && (
          <div style={{ color: 'var(--muted)', fontSize: 13, marginTop: 12 }}>{t('extcity.readonly')}</div>
        )}

        {isSysAdmin && (
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('cls.code')}
              <input type="number" min={1} value={form.code} onChange={e => setForm({ ...form, code: e.target.value })} style={{ width: 90 }} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('cls.name.ru')}
              <input value={form.nameRu} onChange={e => setForm({ ...form, nameRu: e.target.value })} style={{ width: 220 }} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('cls.name.tj')}
              <input value={form.nameTj} onChange={e => setForm({ ...form, nameTj: e.target.value })} style={{ width: 220 }} />
            </label>
            <button className="btn" disabled={busy || !canAdd}
              onClick={() => save({ code: codeNum, nameRu: form.nameRu.trim(), nameTj: form.nameTj.trim() || null })}>
              <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('cls.add')}
            </button>
          </div>
        )}
      </div>
    </>
  );
}
