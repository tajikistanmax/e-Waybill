'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { md, type OrgDocument } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

// Значения — ключи словаря (переводятся через t()), порядок = порядок в выпадающем списке.
const DOC_TYPES: string[] = ['REGISTRATION_CERT', 'CHARTER', 'CARRIER_LICENSE', 'TAX_CERT', 'DIRECTOR_ORDER', 'BANK_DETAILS', 'OTHER'];
const STATUS_COLOR: Record<string, string> = { PENDING: 'amber', APPROVED: 'green', REJECTED: 'red' };

const fmtSize = (b: number) => (b < 1024 ? `${b} Б` : b < 1_048_576 ? `${(b / 1024).toFixed(0)} КБ` : `${(b / 1_048_576).toFixed(1)} МБ`);

/** Учредительные и разрешительные документы организации (скан-копии). */
export default function OrgDocuments({ rma }: { rma: string }) {
  const { roles } = useAuth();
  const { t } = useT();
  const canEdit = roles.includes('SYSTEM_ADMIN') || roles.includes('COMPANY_ADMIN');
  const canReview = roles.includes('SYSTEM_ADMIN');
  const [docs, setDocs] = useState<OrgDocument[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [docType, setDocType] = useState('REGISTRATION_CERT');
  const [title, setTitle] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);

  const load = useCallback(() => {
    md.orgDocuments.list(rma).then(setDocs).catch(e => setError(e.message));
  }, [rma]);

  useEffect(() => { setError(''); load(); }, [load]);

  async function upload() {
    const file = fileRef.current?.files?.[0];
    if (!file) { setError(t('doc.selectfile')); return; }
    setBusy(true); setError('');
    try {
      await md.orgDocuments.upload(rma, file, docType, title);
      setTitle('');
      if (fileRef.current) fileRef.current.value = '';
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function open(d: OrgDocument) {
    try {
      const blob = await md.orgDocuments.download(rma, d.id);
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) { setError((e as Error).message); }
  }

  async function remove(d: OrgDocument) {
    if (!confirm(`${t('doc.confirm.delete')} «${d.fileName}»?`)) return;
    try { await md.orgDocuments.remove(rma, d.id); load(); } catch (e) { setError((e as Error).message); }
  }

  async function review(d: OrgDocument, action: 'approve' | 'reject') {
    let note = '';
    if (action === 'reject') { note = prompt(t('doc.reject.reason')) ?? ''; if (!note) return; }
    try { await md.orgDocuments.review(rma, d.id, action, note); load(); }
    catch (e) { setError((e as Error).message); }
  }

  return (
    <div className="card">
      <div className="card-h">
        <h2>{t('doc.title')}</h2>
        <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 12.5 }}>{t('doc.total')}: {docs.length}</span>
      </div>
      {error && <div className="error">{error}</div>}

      {canEdit && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 12 }}>
          <div style={{ flex: '1 1 260px' }}>
            <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('doc.f.type')}</label>
            <select value={docType} onChange={e => setDocType(e.target.value)} style={{ width: '100%' }}>
              {DOC_TYPES.map(v => <option key={v} value={v}>{t('doc.type.' + v)}</option>)}
            </select>
          </div>
          <div style={{ flex: '1 1 200px' }}>
            <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('doc.f.title')}</label>
            <input value={title} onChange={e => setTitle(e.target.value)} style={{ width: '100%' }} placeholder={t('doc.f.titleph')} />
          </div>
          <input ref={fileRef} type="file" accept=".pdf,.jpg,.jpeg,.png,.tif,.tiff,.doc,.docx,application/pdf,image/*" style={{ maxWidth: 240 }} />
          <button className="btn" onClick={upload} disabled={busy}>{busy ? t('doc.btn.uploading') : t('doc.btn.upload')}</button>
        </div>
      )}

      <table>
        <thead><tr><th>{t('doc.col.kind')}</th><th>{t('doc.col.name')}</th><th>{t('doc.col.size')}</th><th>{t('col.status')}</th><th>{t('doc.col.by')}</th><th>{t('doc.col.date')}</th><th></th></tr></thead>
        <tbody>
          {docs.map(d => {
            const stColor = STATUS_COLOR[d.status] ?? 'gray';
            return (
            <tr key={d.id}>
              <td>{t('doc.type.' + d.docType)}</td>
              <td>
                <button className="link" style={{ background: 'none', border: 0, color: 'var(--blue-600)', cursor: 'pointer', padding: 0, textAlign: 'left' }} onClick={() => open(d)}>
                  {d.title || d.fileName}
                </button>
                {d.title && <div style={{ fontSize: 11, color: 'var(--muted)' }}>{d.fileName}</div>}
              </td>
              <td>{fmtSize(d.sizeBytes)}</td>
              <td>
                <span className={`badge ${stColor}`}>{t('doc.status.' + d.status)}</span>
                {d.status === 'REJECTED' && d.reviewNote && <div style={{ fontSize: 11, color: 'var(--red)' }}>{d.reviewNote}</div>}
              </td>
              <td>{d.uploadedBy ?? '—'}</td>
              <td>{new Date(d.uploadedAt).toLocaleDateString('ru-RU')}</td>
              <td style={{ whiteSpace: 'nowrap' }}>
                <button className="btn secondary" onClick={() => open(d)}>{t('doc.btn.open')}</button>{' '}
                {canReview && d.status === 'PENDING' && (
                  <>
                    <button className="btn secondary" onClick={() => review(d, 'approve')}>{t('doc.btn.approve')}</button>{' '}
                    <button className="btn secondary" onClick={() => review(d, 'reject')}>{t('doc.btn.reject')}</button>{' '}
                  </>
                )}
                {canEdit && <button className="btn secondary" onClick={() => remove(d)}>×</button>}
              </td>
            </tr>
            );
          })}
          {docs.length === 0 && <tr><td colSpan={7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 16 }}>{t('doc.empty')}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
