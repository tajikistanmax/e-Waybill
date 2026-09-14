import './globals.css';
import type { Metadata } from 'next';
import { LangProvider } from '@epd/shared/lib/i18n';

export const metadata: Metadata = {
  title: 'Проверка путевого листа · е-Роҳхат',
  description: 'Публичная проверка подлинности электронного путевого листа по QR-коду · Министерство транспорта Республики Таджикистан',
};

/**
 * Публичный контур (интернет, аноним): единственная функция — проверка ПЛ по QR.
 * Никакой авторизации/Keycloak и никакого служебного кода — минимальная поверхность атаки.
 */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru">
      <body>
        <LangProvider>
          <main className="page" style={{ maxWidth: 640, margin: '40px auto', padding: '0 16px' }}>
            {children}
          </main>
        </LangProvider>
      </body>
    </html>
  );
}
