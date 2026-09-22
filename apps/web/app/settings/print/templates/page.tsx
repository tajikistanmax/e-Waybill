'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { wb, type PrintTemplateSummary, type PrintTemplateContent, type Waybill } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../../icons';

/**
 * Редактор печатных шаблонов бланков (MIGRATION.md 7.1 / 10.4): список встроенных Thymeleaf-шаблонов,
 * текст (переопределение из БД или встроенный), сохранение с пробным рендером, предпросмотр PDF на реальном ПЛ,
 * сброс к встроенному. Только SYSTEM_ADMIN (эндпоинт /print-templates закрыт ролью).
 */
export default function PrintTemplatesPage() {
  const { roles } = useAuth();
  const { t } = useT();
  const sysAdmin = roles.includes('SYSTEM_ADMIN');
  const [list, setList] = useState<PrintTemplateSummary[]>([]);
  const [current, setCurrent] = useState<PrintTemplateContent | null>(null);
  const [text, setText] = useState('');
  const [note, setNote] = useState('');
  const [samples, setSamples] = useState<Waybill[]>([]);
  const [sampleId, setSampleId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  const load = useCallback(() => wb.printTemplates.list().then(setList).catch(e => setError((e as Error).message)), []);
  useEffect(() => { if (sysAdmin) load(); }, [sysAdmin, load]);
  // Образцы ПЛ для предпросмотра — последние 30 не-архивных.
  useEffect(() => {
    if (!sysAdmin) return;
    wb.page({ size: 30 }).then(p => setSamples(p.content)).catch(() => setSamples([]));
  }, [sysAdmin]);

  async function open(name: string) {
    setError(''); setOk('');
    try {
      const c = await wb.printTemplates.get(name);
      setCurrent(c); setText(c.content); setNote(c.note ?? '');
    } catch (e) { setError((e as Error).message); }
  }

  async function save() {
    if (!current) return;
    setBusy(true); setError(''); setOk('');
    try {
      await wb.printTemplates.save(current.name, text, note, sampleId || undefined);
      setOk(t('tpl.saved'));
      await load(); await open(current.name);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  async function reset() {
    if (!current || !confirm(t('tpl.reset.confirm'))) return;
    setBusy(true); setError(''); setOk('');
    try {
      await wb.printTemplates.reset(current.name);
      setOk(t('tpl.resetdone'));
      await load(); await open(current.name);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  async function preview() {
    if (!current) return;
    if (!sampleId) { setError(t('tpl.preview.pick')); return; }
    setBusy(true); setError('');
    try {
      const blob = await wb.printTemplates.preview(current.name, text, sampleId);
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  const dirty = current != null && text !== current.content;
  const fmt = (d: string | null) => d ? new Date(d).toLocaleString('ru-RU') : '';

  if (!sysAdmin) return <div className="error">{t('tpl.adminonly')}</div>;

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('tpl.h')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('tpl.lead')}</div>
        </div>
        <Link href="/settings/print" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('set.mod.print')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 14 }}>{t('tpl.note')}</div>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      <div style={{ display: 'grid', gridTemplateColumns: '300px 1fr', gap: 14, alignItems: 'start' }}>
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <table>
            <thead><tr><th>{t('tpl.col.name')}</th><th>{t('col.status')}</th></tr></thead>
            <tbody>
              {list.map(s => (
                <tr key={s.name} style={{ cursor: 'pointer', background: current?.name === s.name ? 'var(--line-soft)' : undefined }} onClick={() => open(s.name)}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{t('tpl.name.' + s.name)}</div>
                    <div style={{ fontSize: 11, color: 'var(--muted)', fontFamily: 'monospace' }}>print/{s.name}.html</div>
                  </td>
                  <td>
                    {s.overridden
                      ? <span className="badge amber" title={`${s.updatedBy ?? ''} ${fmt(s.updatedAt)}`}>{t('tpl.overridden')}</span>
                      : <span className="badge gray">{t('tpl.builtin')}</span>}
                  </td>
                </tr>
              ))}
              {list.length === 0 && <tr><td colSpan={2} style={{ textAlign: 'center', color: 'var(--muted)', padding: 14 }}>{t('wb.loading')}</td></tr>}
            </tbody>
          </table>
        </div>

        <div className="card">
          {!current && <div style={{ color: 'var(--muted)' }}>{t('tpl.pick')}</div>}
          {current && (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 8 }}>
                <h2 style={{ margin: 0 }}>{t('tpl.name.' + current.name)}</h2>
                <span style={{ fontFamily: 'monospace', fontSize: 12, color: 'var(--muted)' }}>print/{current.name}.html</span>
                {current.overridden
                  ? <span className="badge amber">{t('tpl.overridden')} · {current.updatedBy ?? ''} {fmt(current.updatedAt)}</span>
                  : <span className="badge gray">{t('tpl.builtin')}</span>}
                {dirty && <span className="badge blue">{t('tpl.dirty')}</span>}
              </div>
              <textarea value={text} onChange={e => setText(e.target.value)} spellCheck={false}
                style={{ width: '100%', minHeight: 520, fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12.5, lineHeight: 1.45, whiteSpace: 'pre', tabSize: 4 }} />
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginTop: 10 }}>
                <div>
                  <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('tpl.note.f')}</label>
                  <input value={note} onChange={e => setNote(e.target.value)} maxLength={500} style={{ width: '100%' }} placeholder={t('tpl.note.ph')} />
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('tpl.preview.wb')}</label>
                  <select value={sampleId} onChange={e => setSampleId(e.target.value)} style={{ width: '100%' }} disabled={!current.previewable}>
                    <option value="">{current.previewable ? t('tpl.preview.auto') : t('tpl.preview.na')}</option>
                    {samples.map(w => <option key={w.id} value={w.id}>{w.number ?? w.id.slice(0, 8)} · {w.waybillType} · {w.vehicleRegNumber}</option>)}
                  </select>
                </div>
              </div>
              <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
                <button className="btn" onClick={save} disabled={busy || !dirty && current.overridden}>{busy ? t('cn.btn.saving') : t('tpl.save')}</button>
                <button className="btn secondary" onClick={preview} disabled={busy || !current.previewable}>🖨 {t('tpl.preview')}</button>
                <button className="btn secondary" onClick={() => { setText(current.builtIn); }} disabled={busy || text === current.builtIn}>{t('tpl.loadbuiltin')}</button>
                <span style={{ flex: 1 }} />
                <button className="btn secondary" onClick={reset} disabled={busy || !current.overridden}>{t('tpl.reset')}</button>
              </div>
            </>
          )}
        </div>
      </div>
    </>
  );
}
