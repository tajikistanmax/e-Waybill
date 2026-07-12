'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { md, type ExpiryItem } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from './icons';

/**
 * Предупреждение о сроках документов: проактивно показывает истёкшие и скоро истекающие
 * ВУ / медсправку / курс БДД / техосмотр / контрольную карту / страховку / лицензию по своей
 * организации (тенант-скоуп; платформенным ролям — по всем). Чтобы недопуск/штраф не стал
 * сюрпризом. Данные — из монитора сроков (GET /document-expiry).
 */
export function ExpiryAlert({ days = 30, max = 6, href = '/settings/expiry' }: { days?: number; max?: number; href?: string }) {
  const { t } = useT();
  const [rows, setRows] = useState<ExpiryItem[] | null>(null);

  useEffect(() => {
    md.documentExpiry(days).then(setRows).catch(() => setRows([]));
  }, [days]);

  if (rows === null) return null; // молча ждём (не мигаем на дашборде)

  const label = (prefix: string, code: string) => { const k = `${prefix}.${code}`; const tr = t(k); return tr === k ? code : tr; };

  if (rows.length === 0) {
    return (
      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px', borderLeft: '3px solid var(--green)' }}>
        <span className="k-ic ic-green" style={{ width: 30, height: 30 }}><Icon d={P.check} cls="" /></span>
        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--ink)' }}>{t('expalert.ok')}</div>
      </div>
    );
  }

  const overdue = rows.filter(r => r.daysLeft < 0).length;
  const shown = rows.slice(0, max);

  return (
    <div className="card" style={{ padding: 16, borderLeft: `3px solid ${overdue ? 'var(--red)' : 'var(--amber)'}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
        <span className={`k-ic ${overdue ? 'ic-red' : 'ic-amber'}`}><Icon d={P.alert} cls="" /></span>
        <div style={{ fontWeight: 700, fontSize: 14, flex: 1 }}>{t('expalert.h')}</div>
        <span className={`badge ${overdue ? 'red' : 'amber'}`}>{rows.length}{overdue ? ` · ${overdue} ${t('expalert.overdue')}` : ''}</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {shown.map((r, i) => {
          const over = r.daysLeft < 0;
          const soon = r.daysLeft >= 0 && r.daysLeft <= 7;
          return (
            <div key={`${r.entityType}-${r.key}-${r.docType}-${i}`} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, padding: '5px 0', borderBottom: i < shown.length - 1 ? '1px solid var(--line-soft)' : 'none' }}>
              <span className="badge gray" style={{ flex: '0 0 auto' }}>{label('aud.ent', r.entityType)}</span>
              <span style={{ fontWeight: 600, color: 'var(--ink)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.name}</span>
              <span style={{ color: 'var(--muted)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>· {label('exp.doc', r.docType)}</span>
              <span className={`badge ${over ? 'red' : soon ? 'amber' : 'gray'}`} style={{ flex: '0 0 auto' }}>
                {over ? t('exp.expired') : `${r.daysLeft} ${t('exp.daysleft')}`}
              </span>
            </div>
          );
        })}
      </div>
      <div style={{ marginTop: 10 }}>
        <Link href={href} className="btn secondary" style={{ textDecoration: 'none', fontSize: 12.5 }}>
          {rows.length > max ? `${t('expalert.all')} (${rows.length})` : t('expalert.all')} →
        </Link>
      </div>
    </div>
  );
}
