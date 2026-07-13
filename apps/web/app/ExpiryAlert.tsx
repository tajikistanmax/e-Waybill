'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { md, type ExpiryItem } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from './icons';

/**
 * Предупреждение о сроках документов: истёкшие/скоро истекающие ВУ, медсправка, курс БДД,
 * техосмотр, контрольная карта, страховка, лицензия по своей организации (тенант-скоуп).
 * Данные — из монитора (GET /document-expiry, ограничен limit=100 на бэкенде).
 *
 * compact (по умолчанию в кабинетах): чтобы при большом автопарке не занимать весь экран —
 * показывается ОДНА строка-сводка со счётчиком, детали (топ-N) раскрываются по клику.
 */
export function ExpiryAlert({ days = 30, max = 6, href = '/settings/expiry', compact = false }:
  { days?: number; max?: number; href?: string; compact?: boolean }) {
  const { t } = useT();
  const [rows, setRows] = useState<ExpiryItem[] | null>(null);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    md.documentExpiry(days).then(setRows).catch(() => setRows([]));
  }, [days]);

  if (rows === null) return null; // молча ждём (не мигаем)

  const label = (prefix: string, code: string) => { const k = `${prefix}.${code}`; const tr = t(k); return tr === k ? code : tr; };

  // Всё в порядке: в компактном режиме не занимаем место совсем.
  if (rows.length === 0) {
    if (compact) return null;
    return (
      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px', borderLeft: '3px solid var(--green)' }}>
        <span className="k-ic ic-green" style={{ width: 30, height: 30 }}><Icon d={P.check} cls="" /></span>
        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--ink)' }}>{t('expalert.ok')}</div>
      </div>
    );
  }

  const overdue = rows.filter(r => r.daysLeft < 0).length;
  const shown = rows.slice(0, max);
  const border = overdue ? 'var(--red)' : 'var(--amber)';
  const summary = t('expalert.compact').replace('{n}', String(rows.length)) + (overdue ? ` · ${overdue} ${t('expalert.overdue')}` : '');

  // Компактная свёрнутая сводка — одна строка (не растёт с размером автопарка).
  if (compact && !expanded) {
    return (
      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 16px', borderLeft: `3px solid ${border}` }}>
        <span className={`k-ic ${overdue ? 'ic-red' : 'ic-amber'}`} style={{ width: 30, height: 30 }}><Icon d={P.alert} cls="" /></span>
        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--ink)', flex: 1, minWidth: 0 }}>{summary}</div>
        <button className="btn secondary" style={{ padding: '4px 12px', fontSize: 12.5 }} onClick={() => setExpanded(true)}>{t('expalert.show')}</button>
      </div>
    );
  }

  return (
    <div className="card" style={{ padding: 16, borderLeft: `3px solid ${border}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
        <span className={`k-ic ${overdue ? 'ic-red' : 'ic-amber'}`}><Icon d={P.alert} cls="" /></span>
        <div style={{ fontWeight: 700, fontSize: 14, flex: 1 }}>{t('expalert.h')}</div>
        <span className={`badge ${overdue ? 'red' : 'amber'}`}>{rows.length}{overdue ? ` · ${overdue} ${t('expalert.overdue')}` : ''}</span>
        {compact && <button className="btn secondary" style={{ padding: '4px 10px', fontSize: 12 }} onClick={() => setExpanded(false)}>{t('expalert.hide')}</button>}
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
      {rows.length > max && (
        <div style={{ marginTop: 10 }}>
          {compact
            ? <span style={{ fontSize: 12, color: 'var(--muted)' }}>{t('expalert.more').replace('{n}', String(rows.length - max))}</span>
            : <Link href={href} className="btn secondary" style={{ textDecoration: 'none', fontSize: 12.5 }}>{t('expalert.all')} ({rows.length}) →</Link>}
        </div>
      )}
    </div>
  );
}
