'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

/**
 * Страница возврата с внешнего сервера входа.
 *
 * <p>Нужна была Keycloak: браузер уходил на его страницу и возвращался сюда с кодом.
 * После переноса аутентификации внутрь платформы (23.09.2026) внешнего перехода нет —
 * вход целиком происходит на `/login`. Адрес оставлен, чтобы старая закладка или ссылка
 * из письма не приводила на «страницу не найдена», и просто ведёт на вход.</p>
 */
export default function AuthCallbackPage() {
  const router = useRouter();
  useEffect(() => { router.replace('/login'); }, [router]);
  return <div className="boot">Переход к странице входа…</div>;
}
