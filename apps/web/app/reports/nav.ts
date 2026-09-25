import { canSeeMintransReports, canSeeCarrierEconomics } from '@/lib/roles';

/**
 * Карта отчётов для бокового меню и шапки раздела — одна на оба места, чтобы пункт меню,
 * заголовок страницы и проверка доступа не разъезжались.
 *
 * Две группы, как в старой платформе (меню «Ҳисобот»: Мусофирбарӣ / Боркашонӣ / Умумӣ):
 *  - «Отчёты» — данные своей организации (филиалов): сводка, типовые формы Мусофирбарӣ и
 *    Боркашонӣ, оперативные, справки, журналы контроля;
 *  - «Общие отчёты (Умумӣ)» — сводные Минтранса по всем организациям (регион → город →
 *    предприятие), legacy /admin/reportwaybillgeneral. Отдельная группа: другой адресат
 *    (надзор, а не перевозчик) и другой охват данных.
 *
 * `economics` — внутренняя экономика перевозчика, инспектору закрыта (canSeeCarrierEconomics);
 * группа «Умумӣ» целиком — только надзору и админу платформы (canSeeMintransReports).
 */
export type ReportLink = {
  href: string;
  label: string;
  economics?: boolean;
  /** Другие адреса, на которых пункт подсвечивается (вкладки внутри пункта). */
  also?: string[];
};

/** Вкладки пункта «Оперативные» — простые разрезы за период/день. */
export const OPERATIONAL_TABS: ReportLink[] = [
  { href: '/reports/journal', label: 'rep.tab.journal' },
  { href: '/reports/by-driver', label: 'rep.tab.driver' },
  { href: '/reports/by-vehicle', label: 'rep.tab.vehicle' },
  { href: '/reports/fuel', label: 'rep.tab.fuel' },
];

export const CARRIER_REPORTS: ReportLink[] = [
  { href: '/reports/summary', label: 'rep.tab.summary' },
  { href: '/reports/passenger', label: 'rep.nav.passenger', economics: true },
  { href: '/reports/cargo', label: 'rep.nav.cargo', economics: true },
  { href: '/reports/journal', label: 'rep.nav.operational', economics: true, also: OPERATIONAL_TABS.map(o => o.href) },
  { href: '/reports/malumotnoma', label: 'rep.tab.malumotnoma', economics: true },
  { href: '/reports/journals', label: 'rep.tab.journals' },
];

export const GENERAL_REPORTS: ReportLink[] = [
  { href: '/reports/regional', label: 'rep.nav.gen.trans' },
  { href: '/reports/regional/count', label: 'rep.nav.gen.count' },
  { href: '/reports/regional/norm', label: 'rep.nav.gen.norm' },
  { href: '/reports/regional/plans', label: 'rep.nav.gen.plans' },
];

export const isGeneralPath = (path: string) => path === '/reports/regional' || path.startsWith('/reports/regional/');

export const isLinkActive = (l: ReportLink, path: string) => path === l.href || !!l.also?.includes(path);

export function carrierReportsFor(roles: string[]): ReportLink[] {
  const eco = canSeeCarrierEconomics(roles);
  return CARRIER_REPORTS.filter(l => !l.economics || eco);
}

export function generalReportsFor(roles: string[]): ReportLink[] {
  return canSeeMintransReports(roles) ? GENERAL_REPORTS : [];
}
