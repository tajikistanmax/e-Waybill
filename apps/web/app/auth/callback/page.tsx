'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';

// Точка возврата Authorization Code + PKCE (см. lib/auth.tsx: loginRedirect/completeLoginRedirect).
// Читаем параметры напрямую из window.location, а не из useSearchParams — страница целиком
// клиентская и не участвует в статическом рендере, доп. Suspense-обёртка не нужна.
export default function AuthCallbackPage() {
  const router = useRouter();
  const { completeLoginRedirect } = useAuth();
  const [error, setError] = useState('');

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get('code');
    const state = params.get('state');
    const kcError = params.get('error');
    if (kcError) {
      setError(`Ошибка входа: ${params.get('error_description') || kcError}`);
      return;
    }
    if (!code || !state) {
      setError('Некорректный ответ от сервера аутентификации.');
      return;
    }
    completeLoginRedirect(code, state)
      .then(returnTo => router.replace(returnTo || '/'))
      .catch(e => setError(e instanceof Error ? e.message : String(e)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <main className="page" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '60vh' }}>
      {error ? (
        <div className="error" style={{ maxWidth: 480, textAlign: 'center' }}>
          {error}
          <div style={{ marginTop: 10 }}><a href="/login">Вернуться к входу</a></div>
        </div>
      ) : (
        <p>Завершение входа…</p>
      )}
    </main>
  );
}
