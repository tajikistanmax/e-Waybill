'use client';

import { useEffect, useState } from 'react';
import { md } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

/** За сколько дней до истечения лицензии показывать предупреждение (legacy Notification: 30). */
const WARN_DAYS = 30;
/** Платформенные роли — баннер перевозчика им не нужен. */
const PLATFORM_ROLES = ['SYSTEM_ADMIN', 'MINTRANS_ANALYST', 'INSPECTOR', 'API_INTEGRATOR'];

type Notice = { kind: 'blocked' | 'expired' | 'soon'; org: string; days?: number; date?: string; reason?: string };

/**
 * Баннер кабинета перевозчика (MIGRATION.md 11.6 — legacy middleware Notification):
 * организация заблокирована (status_lock → «оплатите услуги») · лицензия истекла · лицензия истекает
 * через N ≤ 30 дней. Только предупреждение: выписку ПЛ при блокировке/истёкшей лицензии отклоняет
 * бэкенд (403 / отказ при создании), остальной кабинет доступен (осознанно мягче legacy, где
 * весь кабинет редиректился назад).
 */
export function LicenseBanner() {
  const { t } = useT();
  const { ready, authenticated, roles } = useAuth();
  const [notices, setNotices] = useState<Notice[]>([]);

  const tenant = ready && authenticated && roles.length > 0 && !roles.some(r => PLATFORM_ROLES.includes(r));

  useEffect(() => {
    if (!tenant) { setNotices([]); return; }
    let alive = true;
    md.organizations().then(list => {
      if (!alive) return;
      const today = new Date(); today.setHours(0, 0, 0, 0);
      const out: Notice[] = [];
      for (const o of list) {
        const org = String(o.name ?? o.rma ?? '');
        if (o.blocked === true) { out.push({ kind: 'blocked', org, reason: o.blockReason ? String(o.blockReason) : undefined }); continue; }
        const lt = o.licenseTo ? new Date(String(o.licenseTo)) : null;
        if (!lt || isNaN(lt.getTime())) continue;
        lt.setHours(0, 0, 0, 0);
        const days = Math.round((lt.getTime() - today.getTime()) / 86400000);
        if (days < 0) out.push({ kind: 'expired', org, date: String(o.licenseTo) });
        else if (days <= WARN_DAYS) out.push({ kind: 'soon', org, days, date: String(o.licenseTo) });
      }
      setNotices(out);
    }).catch(() => { if (alive) setNotices([]); });
    return () => { alive = false; };
  }, [tenant]);

  if (notices.length === 0) return null;
  return (
    <>
      {notices.map((n, i) => (
        <div key={i} style={{
          background: n.kind === 'soon' ? '#b45309' : '#b91c1c', color: '#fff', padding: '8px 16px', fontSize: 13,
          fontWeight: 600, textAlign: 'center', display: 'flex', gap: 8, justifyContent: 'center', alignItems: 'center',
        }}>
          <span aria-hidden>{n.kind === 'soon' ? '⚠' : '⛔'}</span>
          {n.kind === 'blocked' && `${t('lic.banner.blocked').replace('{org}', n.org)}${n.reason ? ` — ${n.reason}` : ''}`}
          {n.kind === 'expired' && t('lic.banner.expired').replace('{org}', n.org).replace('{date}', n.date ?? '')}
          {n.kind === 'soon' && t('lic.banner.soon').replace('{org}', n.org).replace('{days}', String(n.days ?? 0)).replace('{date}', n.date ?? '')}
        </div>
      ))}
    </>
  );
}
