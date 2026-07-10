import './globals.css';
import type { Metadata } from 'next';
import { AuthProvider } from '@/lib/auth';
import { Sidebar } from './sidebar';

export const metadata: Metadata = {
  title: 'ЭПД РТ — Электронные перевозочные документы',
  description: 'Государственная платформа электронных путевых листов · Министерство транспорта Республики Таджикистан',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru">
      <body>
        <AuthProvider>
          <div className="app">
            <Sidebar />
            <div className="content">
              <div className="topbar no-print" aria-hidden="true" />
              <main className="page">{children}</main>
            </div>
          </div>
        </AuthProvider>
      </body>
    </html>
  );
}
