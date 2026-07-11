'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { wb, type NotificationItem } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

const KIND_BADGE: Record<string, string> = {
  MED_REJECTED: 'red', TECH_REJECTED: 'red', BLOCKED: 'red', EXPIRED: 'amber', READY: 'green',
};

export default function NotificationsPage() {
  const { t } = useT();
  const [rows, setRows] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const reload = useCallback(async () => {
    setLoading(true);
    try { setRows(await wb.notifications()); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const notifyChange = () => { if (typeof window !== 'undefined') window.dispatchEvent(new Event('notif-changed')); };
  async function markRead(n: NotificationItem) {
    if (n.readAt) return;
    try {
      await wb.markNotifRead(n.id);
      setRows(rs => rs.map(r => r.id === n.id ? { ...r, readAt: new Date().toISOString() } : r));
      notifyChange();
    } catch { /* ignore */ }
  }
  async function markAll() {
    try { await wb.markAllNotifRead(); await reload(); notifyChange(); } catch { /* ignore */ }
  }

  const kindTitle = (n: NotificationItem) => {
    const k = `notif.k.${n.kind}`;
    const tr = t(k);
    return tr === k ? n.title : tr;
  };
  const fmt = (iso: string) => {
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleString('ru-RU', { dateStyle: 'short', timeStyle: 'short' });
  };
  const hasUnread = rows.some(r => !r.readAt);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('notif.title')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('notif.lead')}</div>
        </div>
        {hasUnread && (
          <button className="btn secondary" style={{ marginLeft: 'auto' }} onClick={markAll}>
            <Icon d={P.check} cls="" style={{ width: 15, height: 15 }} /> {t('notif.markall')}
          </button>
        )}
      </div>

      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {rows.length === 0 && !loading && (
          <div style={{ padding: 24, color: 'var(--muted)' }}>{t('notif.empty')}</div>
        )}
        {rows.map(n => (
          <div key={n.id} onClick={() => markRead(n)}
            style={{
              display: 'flex', alignItems: 'flex-start', gap: 12, padding: '14px 18px',
              borderBottom: '1px solid var(--line)', cursor: n.readAt ? 'default' : 'pointer',
              background: n.readAt ? 'transparent' : 'var(--blue-050)',
            }}>
            <span style={{
              width: 9, height: 9, borderRadius: '50%', marginTop: 5, flexShrink: 0,
              background: n.readAt ? 'var(--line)' : 'var(--blue-600)',
            }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 3, flexWrap: 'wrap' }}>
                <span className={`badge ${KIND_BADGE[n.kind] ?? 'gray'}`}>{kindTitle(n)}</span>
                <span style={{ fontSize: 12, color: 'var(--muted)' }}>{fmt(n.createdAt)}</span>
              </div>
              {n.body && <div style={{ fontSize: 13, color: 'var(--text)' }}>{n.body}</div>}
            </div>
            {n.waybillId && (
              <Link href={`/waybills/${n.waybillId}`} onClick={e => e.stopPropagation()}
                className="btn secondary" style={{ textDecoration: 'none', whiteSpace: 'nowrap' }}>
                {t('notif.open')} <Icon d={P.chevron} cls="" style={{ width: 14, height: 14 }} />
              </Link>
            )}
          </div>
        ))}
      </div>
    </>
  );
}
