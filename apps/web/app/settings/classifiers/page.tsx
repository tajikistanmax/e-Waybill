'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { md, type ClassifierItem } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

const CATEGORIES = ['COUNTRY', 'ADR_CLASS', 'PERMIT_TYPE'] as const;
const PER_PAGE = 20;

export default function ClassifiersSettingsPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [cat, setCat] = useState<string>(CATEGORIES[0]);
  const [rows, setRows] = useState<ClassifierItem[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ code: '', nameRu: '', nameTj: '' });
  const [page, setPage] = useState(1);

  const reload = useCallback(async () => {
    try { setRows(await md.classifiers(cat, true)); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, [cat]);

  useEffect(() => { setForm({ code: '', nameRu: '', nameTj: '' }); reload(); }, [reload]);

  async function save(body: Record<string, unknown>) {
    setBusy(true); setError('');
    try { await md.saveClassifier(body); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function remove(id: string) {
    setBusy(true); setError('');
    try { await md.deleteClassifier(id); await reload(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  const pages = Math.max(1, Math.ceil(rows.length / PER_PAGE));
  const view = rows.slice((page - 1) * PER_PAGE, page * PER_PAGE);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.classifiers')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('cls.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('cls.note')}</div>
      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}

      {/* Категории */}
      <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap' }}>
        {CATEGORIES.map(c => (
          <button key={c} onClick={() => { setCat(c); setPage(1); }}
            className={`badge ${c === cat ? 'blue' : 'gray'}`}
            style={{ cursor: 'pointer', border: 'none', padding: '8px 14px', fontSize: 13 }}>
            {t(`cls.cat.${c}`)}
          </button>
        ))}
      </div>

      <div className="card">
        <table>
          <thead>
            <tr>
              <th style={{ width: 120 }}>{t('cls.code')}</th>
              <th>{t('cls.name.ru')}</th>
              <th>{t('cls.name.tj')}</th>
              <th style={{ width: 90 }}>{t('cls.active')}</th>
              <th style={{ width: 60 }} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={5} style={{ color: 'var(--muted)' }}>{t('cls.empty')}</td></tr>}
            {view.map(r => (
              <tr key={r.id} style={{ opacity: r.active ? 1 : 0.5 }}>
                <td><span className="number">{r.code}</span></td>
                <td style={{ fontWeight: 600 }}>{r.nameRu}</td>
                <td>{r.nameTj ?? '—'}</td>
                <td>
                  <button type="button" className={`badge ${r.active ? 'green' : 'gray'}`}
                    style={{ cursor: isSysAdmin ? 'pointer' : 'default', border: 'none', opacity: isSysAdmin ? 1 : 0.7 }}
                    disabled={!isSysAdmin || busy}
                    onClick={() => save({ category: r.category, code: r.code, nameRu: r.nameRu, nameTj: r.nameTj, sortOrder: r.sortOrder, active: !r.active })}>
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

        {isSysAdmin && (
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('cls.code')}
              <input value={form.code} onChange={e => setForm({ ...form, code: e.target.value })} style={{ width: 110 }} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('cls.name.ru')}
              <input value={form.nameRu} onChange={e => setForm({ ...form, nameRu: e.target.value })} style={{ width: 220 }} />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
              {t('cls.name.tj')}
              <input value={form.nameTj} onChange={e => setForm({ ...form, nameTj: e.target.value })} style={{ width: 220 }} />
            </label>
            <button className="btn" disabled={busy || !form.code.trim() || !form.nameRu.trim()}
              onClick={() => save({ category: cat, code: form.code.trim(), nameRu: form.nameRu.trim(), nameTj: form.nameTj.trim() || null })}>
              <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('cls.add')}
            </button>
          </div>
        )}
      </div>
    </>
  );
}
