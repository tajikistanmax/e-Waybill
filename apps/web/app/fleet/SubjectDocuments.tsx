'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { wb, type SubjectDocument } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

const VEHICLE_TYPES = ['TECH_PASSPORT', 'INSURANCE', 'TECH_INSPECTION', 'LEASE_CONTRACT', 'ADR_CERT', 'OTHER'];
const DRIVER_TYPES = ['DRIVER_LICENSE', 'MED_CERT', 'SAFETY_COURSE', 'ADR_CERT', 'PASSPORT', 'OTHER'];
const STATUS_COLOR: Record<string, string> = { PENDING: 'amber', APPROVED: 'green', REJECTED: 'red' };
const STATUS_KEY: Record<string, string> = { PENDING: 'sd.status.PENDING', APPROVED: 'doc.status.APPROVED', REJECTED: 'doc.status.REJECTED' };
const fmtSize = (b: number) => (b < 1024 ? `${b} Б` : b < 1_048_576 ? `${(b / 1024).toFixed(0)} КБ` : `${(b / 1_048_576).toFixed(1)} МБ`);

/** Документы одного ТС или водителя: прикрепление, скачивание, одобрение/отклонение. */
export default function SubjectDocuments({ subject, subjectKey, title }: {
  subject: 'vehicles' | 'drivers'; subjectKey: string; title: string;
}) {
  const { roles } = useAuth();
  const { t } = useT();
  const canReview = roles.includes('SYSTEM_ADMIN') || roles.includes('COMPANY_ADMIN') || roles.includes('BRANCH_ADMIN');
  const [docs, setDocs] = useState<SubjectDocument[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ docType: subject === 'vehicles' ? 'TECH_PASSPORT' : 'DRIVER_LICENSE', title: '', validTo: '' });
  const fileRef = useRef<HTMLInputElement>(null);
  const TYPES = subject === 'vehicles' ? VEHICLE_TYPES : DRIVER_TYPES;

  const load = useCallback(() => {
    wb.subjectDocuments.list(subject, subjectKey).then(setDocs).catch(e => setError(e.message));
  }, [subject, subjectKey]);
  useEffect(() => { setError(''); load(); }, [load]);

  async function upload() {
    const file = fileRef.current?.files?.[0];
    if (!file) { setError(t('doc.selectfile')); return; }
    setBusy(true); setError('');
    try {
      await wb.subjectDocuments.upload(subject, subjectKey, file, form.docType, form.title, form.validTo);
      setForm(f => ({ ...f, title: '', validTo: '' }));
      if (fileRef.current) fileRef.current.value = '';
      load();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  async function open(d: SubjectDocument) {
    try {
      const blob = await wb.subjectDocuments.download(subject, subjectKey, d.id);
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) { setError((e as Error).message); }
  }
  async function review(d: SubjectDocument, action: 'approve' | 'reject') {
    let note = '';
    if (action === 'reject') { note = prompt(t('doc.reject.reason')) ?? ''; if (!note) return; }
    try { await wb.subjectDocuments.review(subject, subjectKey, d.id, action, note); load(); }
    catch (e) { setError((e as Error).message); }
  }
  async function remove(d: SubjectDocument) {
    if (!confirm(`${t('doc.confirm.delete')} «${d.fileName}»?`)) return;
    try { await wb.subjectDocuments.remove(subject, subjectKey, d.id); load(); }
    catch (e) { setError((e as Error).message); }
  }

  return (
    <div>
      <h3 style={{ margin: '0 0 8px' }}>{t('sd.h')}: {title}</h3>
      {error && <div className="error">{error}</div>}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 10 }}>
        <div style={{ flex: '1 1 240px' }}>
          <label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('doc.f.type')}</label>
          <select value={form.docType} onChange={e => setForm(f => ({ ...f, docType: e.target.value }))} style={{ width: '100%' }}>
            {TYPES.map(v => <option key={v} value={v}>{t('sd.type.' + v)}</option>)}
          </select>
        </div>
        <div><label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{t('sd.validto')}</label>
          <input type="date" value={form.validTo} onChange={e => setForm(f => ({ ...f, validTo: e.target.value }))} /></div>
        <input ref={fileRef} type="file" accept=".pdf,.jpg,.jpeg,.png,.tif,.tiff,.doc,.docx,application/pdf,image/*" style={{ maxWidth: 220 }} />
        <button className="btn" onClick={upload} disabled={busy}>{busy ? t('doc.btn.uploading') : t('att.btn.upload')}</button>
      </div>

      <table>
        <thead><tr><th>{t('doc.col.kind')}</th><th>{t('att.col.file')}</th><th>{t('sd.col.to')}</th><th>{t('doc.col.size')}</th><th>{t('col.status')}</th><th></th></tr></thead>
        <tbody>
          {docs.map(d => {
            const stColor = STATUS_COLOR[d.status] ?? 'gray';
            return (
              <tr key={d.id}>
                <td>{t('sd.type.' + d.docType)}</td>
                <td>
                  <button className="link" style={{ background: 'none', border: 0, color: 'var(--blue-600)', cursor: 'pointer', padding: 0, textAlign: 'left' }} onClick={() => open(d)}>
                    {d.title || d.fileName}
                  </button>
                </td>
                <td>{d.validTo ?? '—'}</td>
                <td>{fmtSize(d.sizeBytes)}</td>
                <td>
                  <span className={`badge ${stColor}`}>{t(STATUS_KEY[d.status] ?? d.status)}</span>
                  {d.status === 'REJECTED' && d.reviewNote && <div style={{ fontSize: 11, color: 'var(--red)' }}>{d.reviewNote}</div>}
                </td>
                <td style={{ whiteSpace: 'nowrap' }}>
                  {canReview && d.status === 'PENDING' && (
                    <>
                      <button className="btn secondary" onClick={() => review(d, 'approve')}>{t('doc.btn.approve')}</button>{' '}
                      <button className="btn secondary" onClick={() => review(d, 'reject')}>{t('doc.btn.reject')}</button>{' '}
                    </>
                  )}
                  <button className="btn secondary" onClick={() => remove(d)}>×</button>
                </td>
              </tr>
            );
          })}
          {docs.length === 0 && <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 14 }}>{t('doc.empty')}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
