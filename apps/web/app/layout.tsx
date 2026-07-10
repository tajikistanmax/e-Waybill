import './globals.css';
import Link from 'next/link';
import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'ЭПД РТ — Кабинет диспетчера',
  description: 'Электронные перевозочные документы Республики Таджикистан',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru">
      <body>
        <header className="top">
          <span className="logo">🚌</span>
          <div>
            <div className="title">ЭПД РТ — Электронные перевозочные документы</div>
            <div className="subtitle">Вазорати нақлиёти Ҷумҳурии Тоҷикистон · Кабинет диспетчера</div>
          </div>
          <nav>
            <Link href="/waybills">Путевые листы</Link>
            <Link href="/med">АРМ врача</Link>
            <Link href="/tech">АРМ механика</Link>
            <Link href="/waybills/new">+ Новый</Link>
          </nav>
        </header>
        <main>{children}</main>
      </body>
    </html>
  );
}
