'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

const LINKS: { href: string; label: string }[] = [
  { href: '/waybills', label: 'Путевые листы' },
  { href: '/med', label: 'АРМ врача' },
  { href: '/tech', label: 'АРМ механика' },
  { href: '/company', label: 'Компания' },
  { href: '/reports', label: 'Отчёты' },
  { href: '/dictionaries', label: 'Справочники' },
];

/** Навигация шапки: активный раздел подчёркнут золотом. */
export function NavLinks() {
  const pathname = usePathname();
  const isActive = (href: string) =>
    href === '/waybills'
      ? pathname === '/waybills' || (pathname.startsWith('/waybills/') && pathname !== '/waybills/new')
      : pathname.startsWith(href);
  return (
    <>
      {LINKS.map(l => (
        <Link key={l.href} href={l.href} className={isActive(l.href) ? 'active' : ''}>
          {l.label}
        </Link>
      ))}
      <Link href="/waybills/new" className="cta">+ Новый лист</Link>
    </>
  );
}
