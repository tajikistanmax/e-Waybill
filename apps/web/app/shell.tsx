'use client';

import { usePathname, useRouter } from 'next/navigation';
import { useEffect } from 'react';
import { useAuth } from '@/lib/auth';
import { Sidebar } from './sidebar';
import { Topbar } from './topbar';

const isPublic = (path: string) => path === '/login' || path.startsWith('/verify/');

/** Каркас приложения: публичные страницы (вход, проверка QR) — на весь экран;
 *  остальные — за авторизацией, в оболочке с меню и верхней панелью. */
export function Shell({ children }: { children: React.ReactNode }) {
  const { ready, authenticated } = useAuth();
  const pathname = usePathname();
  const router = useRouter();

  useEffect(() => {
    if (ready && !authenticated && !isPublic(pathname)) router.replace('/login');
  }, [ready, authenticated, pathname, router]);

  if (isPublic(pathname)) return <>{children}</>;
  if (!ready) return <div className="boot">Загрузка системы…</div>;
  if (!authenticated) return <div className="boot">Переход к странице входа…</div>;

  return (
    <div className="app">
      <Sidebar />
      <div className="content">
        <Topbar />
        <main className="page">{children}</main>
      </div>
    </div>
  );
}
