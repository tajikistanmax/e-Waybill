import './globals.css';
import type { Metadata } from 'next';
import { AuthProvider } from '@/lib/auth';
import { HeaderUser } from './header-user';
import { NavLinks } from './nav-links';

export const metadata: Metadata = {
  title: 'ЭПД РТ — Электронные перевозочные документы',
  description: 'Электронные перевозочные документы Республики Таджикистан — Министерство транспорта',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru">
      <body>
        <AuthProvider>
          {/* Лента государственного флага РТ */}
          <div className="flag-ribbon no-print" aria-hidden="true" />
          <header className="top">
            <span className="logo" aria-hidden="true">ЭПД</span>
            <div>
              <div className="title">Электронные перевозочные документы</div>
              <div className="subtitle">Вазорати нақлиёти Ҷумҳурии Тоҷикистон · Министерство транспорта РТ</div>
            </div>
            <nav>
              <NavLinks />
              <HeaderUser />
            </nav>
          </header>
          <main>{children}</main>
        </AuthProvider>
      </body>
    </html>
  );
}
