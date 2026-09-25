'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import {
  CARRIER_REPORTS, GENERAL_REPORTS, OPERATIONAL_TABS,
  carrierReportsFor, generalReportsFor, isGeneralPath, isLinkActive,
} from './nav';

/**
 * Каркас отчётов. Навигация между отчётами — в боковом меню (группы «Отчёты» и
 * «Общие отчёты (Умумӣ)», см. ./nav.ts); здесь — заголовок текущего отчёта и вкладки
 * только внутри пункта «Оперативные». У каждого отчёта свой адрес — deep-link,
 * «назад» и F5 работают.
 *
 * Прямой заход на отчёт, закрытый роли, показывает понятное сообщение вместо
 * каскада 403 от API.
 */
export default function ReportsLayout({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const { t } = useT();
  const { roles } = useAuth();
  const general = isGeneralPath(path);
  const all = general ? GENERAL_REPORTS : CARRIER_REPORTS;
  const allowed = general ? generalReportsFor(roles) : carrierReportsFor(roles);
  const item = all.find(l => isLinkActive(l, path));
  const denied = !!item && !allowed.includes(item);
  const operational = OPERATIONAL_TABS.some(o => o.href === path);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{item ? t(item.label) : t(general ? 'nav.general' : 'nav.reports')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t(general ? 'rep.general.lead' : 'rep.lead')}</div>
        </div>
      </div>

      {!denied && operational && (
        <div className="toolbar" style={{ flexWrap: 'wrap' }}>
          {OPERATIONAL_TABS.map(s => (
            <Link
              key={s.href}
              href={s.href}
              className={`btn ${path === s.href ? '' : 'secondary'}`}
              style={{ textDecoration: 'none' }}
            >
              {t(s.label)}
            </Link>
          ))}
        </div>
      )}

      {denied ? <div className="card" style={{ color: 'var(--muted)' }}>{t('rep.noaccess')}</div> : children}
    </>
  );
}
