'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { canSeeMintransReports, canSeeCarrierEconomics } from '@/lib/roles';

/**
 * `mintrans` — сводные отчёты Минтранса (закрыты ролям перевозчика);
 * `economics` — внутренняя экономика перевозчика (топливо, зарплата, разрезы, справки):
 * инспектору дорожного контроля не показываем, ему доступны сводка и журналы контроля.
 */
const TABS: { href: string; label: string; mintrans?: boolean; economics?: boolean }[] = [
  { href: '/reports/summary', label: 'rep.tab.summary' },
  { href: '/reports/journal', label: 'rep.tab.journal', economics: true },
  { href: '/reports/by-driver', label: 'rep.tab.driver', economics: true },
  { href: '/reports/by-vehicle', label: 'rep.tab.vehicle', economics: true },
  { href: '/reports/fuel', label: 'rep.tab.fuel', economics: true },
  { href: '/reports/sections', label: 'rep.tab.sections', economics: true },
  { href: '/reports/regional', label: 'rep.tab.regional', mintrans: true },
  { href: '/reports/malumotnoma', label: 'rep.tab.malumotnoma', economics: true },
  { href: '/reports/journals', label: 'rep.tab.journals' },
];

/**
 * Каркас отчётов: заголовок + вкладки-ссылки. У каждого отчёта свой адрес
 * (/reports/summary, /reports/regional …) — deep-link, «назад» и F5 работают,
 * ссылку на конкретный отчёт можно передать для интеграции.
 *
 * Состав вкладок — по роли: сводные отчёты Минтранса видят только надзор и
 * администратор платформы, остальным они возвращали бы 403.
 */
export default function ReportsLayout({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const { t } = useT();
  const { roles } = useAuth();
  const label = (l: string) => (l.startsWith('@') ? l.slice(1) : t(l));
  const tabs = TABS.filter(s =>
    (!s.mintrans || canSeeMintransReports(roles))
    && (!s.economics || canSeeCarrierEconomics(roles)));

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.reports')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('rep.lead')}</div>
        </div>
      </div>

      <div className="toolbar" style={{ flexWrap: 'wrap' }}>
        {tabs.map(s => (
          <Link
            key={s.href}
            href={s.href}
            className={`btn ${path.startsWith(s.href) ? '' : 'secondary'}`}
            style={{ textDecoration: 'none' }}
          >
            {label(s.label)}
          </Link>
        ))}
      </div>

      {children}
    </>
  );
}
