import './globals.css';
import type { Metadata } from 'next';
import { AuthProvider } from '@/lib/auth';
import { Shell } from './shell';

export const metadata: Metadata = {
  title: 'DTS · Электронный путевой лист',
  description: 'Единая цифровая транспортная система · Министерство транспорта Республики Таджикистан',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru">
      <body>
        <AuthProvider>
          <Shell>{children}</Shell>
        </AuthProvider>
      </body>
    </html>
  );
}
