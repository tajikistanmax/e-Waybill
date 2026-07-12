import './globals.css';
import type { Metadata } from 'next';
import { AuthProvider } from '@/lib/auth';
import { LangProvider } from '@/lib/i18n';
import { BrandProvider } from '@/lib/brand';
import { Shell } from './shell';

export const metadata: Metadata = {
  title: 'е-Роҳхат · Электронный путевой лист',
  description: 'Единая цифровая транспортная система · Министерство транспорта Республики Таджикистан',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru">
      <body>
        <LangProvider>
          <BrandProvider>
            <AuthProvider>
              <Shell>{children}</Shell>
            </AuthProvider>
          </BrandProvider>
        </LangProvider>
      </body>
    </html>
  );
}
