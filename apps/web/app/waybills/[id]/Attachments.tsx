'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { wb, type WaybillAttachment } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

const DOC_TYPES = ['CMR', 'INVOICE', 'PACKING_LIST', 'WEIGHT_CERT', 'PERMIT_SCAN', 'CARGO_PHOTO', 'DEFECT_PHOTO', 'OTHER'];
const fmtSize = (b: number) => (b < 1024 ? `${b} Б` : b < 1_048_576 ? `${(b / 1024).toFixed(0)} КБ` : `${(b / 1_048_576).toFixed(1)} МБ`);

/**
 * Вложения к путевому листу: скан-копии сопроводительных документов рейса.
 * Прикреплять/удалять может диспетчер / бухгалтер / администратор; терминальные ПЛ — только чтение.
 */
export function Attachments({ waybillId, terminal }: { waybillId: string; terminal: boolean }) {
  const { roles } = useAuth();
  const { t } = useT();
  const canManage = ['DISPATCHER', 'COMPANY_ADMIN', 'SYSTEM_ADMIN', 'ACCOUNTANT'].some(r => roles.includes(r));
  const canDelete = ['DISPATCHER', 'COMPANY_ADMIN', 'SYSTEM_ADMIN'].some(r => roles.includes(r));
  const [items, setItems] = useState<WaybillAttachment[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ docType: 'CMR', title: '' });
  const fileRef = useRef<HTMLInputElement>(null);

  const load = useCallback(() => {
    wb.attachments.list(waybillId).then(setItems).catch(e => setError(e.message));
  }, [waybillId]);
  useEffect(() => { setError(''); load(); }, [load]);

  async function upload() {
    const file = fileRef.current?.files?.[0];
    if (!file) { setError(t('doc.selectfile')); return; }
    setBusy(true); setError('');
    try {
      await wb.attachments.upload(waybillId, file, form.docType, form.title);
      setForm(f => ({ ...f, title: '' }));
      if (fileRef.current) fileRef.current.value = '';
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  async function open(a: WaybillAttachment) {
    try {
      const blob = await wb.attachments.download(waybillId, a.id);
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) { setError((e as Error).message); }
  }

  async function remove(a: WaybillAttachment) {
    if (!confirm(`${t('doc.confirm.delete')} «${a.title || a.fileName}»?`)) return;
    try { await wb.attachments.remove(waybillId, a.id); load(); }
    catch (e) { setError((e as Error).message); }
  }

  return (
    <div>
      <h2>{t('att.h')}</h2>
      <p style={{ color: 'var(--muted)', fontSize: 13, marginTop: -4 }}>{t('att.lead')}</p>
      {error && <div className="error">{error}</div>}

      {canManage && !terminal && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', margin: '10px 0 14px' }}>
          <div style={{ flex: '1 1 260px' }}>
            <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('doc.f.type')}</label>
            <select value={form.docType} onChange={e => setForm(f => ({ ...f, docType: e.target.value }))} style={{ width: '100%' }}>
              {DOC_TYPES.map(v => <option key={v} value={v}>{t('att.type.' + v)}</option>)}
            </select>
          </div>
          <div style={{ flex: '1 1 180px' }}>
            <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('rp.note')}</label>
            <input value={form.title} onChange={e => setForm(f => ({ ...f, title: e.target.value }))}
              placeholder={t('att.f.noteph')} style={{ width: '100%' }} />
          </div>
          <input ref={fileRef} type="file" accept=".pdf,.jpg,.jpeg,.png,.tif,.tiff,.heic,.doc,.docx,application/pdf,image/*" style={{ maxWidth: 220 }} />
          <button className="btn" onClick={upload} disabled={busy}>{busy ? t('doc.btn.uploading') : t('att.btn.upload')}</button>
        </div>
      )}

      <table>
        <thead><tr><th>{t('doc.col.kind')}</th><th>{t('att.col.file')}</th><th>{t('doc.col.size')}</th><th>{t('att.col.added')}</th><th></th></tr></thead>
        <tbody>
          {items.map(a => (
            <tr key={a.id}>
              <td>{t('att.type.' + a.docType)}</td>
              <td>
                <button className="link" style={{ background: 'none', border: 0, color: 'var(--blue-600)', cursor: 'pointer', padding: 0, textAlign: 'left' }} onClick={() => open(a)}>
                  {a.title || a.fileName}
                </button>
                {a.title && <div style={{ fontSize: 11, color: 'var(--muted)' }}>{a.fileName}</div>}
              </td>
              <td>{fmtSize(a.sizeBytes)}</td>
              <td style={{ whiteSpace: 'nowrap', fontSize: 12 }}>
                {new Date(a.uploadedAt).toLocaleDateString('ru-RU')}
                {a.uploadedBy && <div style={{ color: 'var(--muted)' }}>{a.uploadedBy}</div>}
              </td>
              <td style={{ whiteSpace: 'nowrap' }}>
                {canDelete && !terminal && <button className="btn secondary" onClick={() => remove(a)}>×</button>}
              </td>
            </tr>
          ))}
          {items.length === 0 && <tr><td colSpan={5} style={{ textAlign: 'center', color: 'var(--muted)', padding: 14 }}>{t('att.empty')}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
