'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { useRoleAccess } from '@/lib/roleaccess';
import { useBrand, BrandLogo } from '@/lib/brand';
import { Icon, P } from '../../icons';

/**
 * Смена пароля — страница платформы.
 *
 * <p>Сюда попадает пользователь с временным паролем при первом входе (раньше его уводило на
 * страницу Keycloak по чужому адресу — оттуда он не возвращался, находка владельца 23.09.2026),
 * а также любой вошедший, если хочет сменить свой пароль (ссылка в шапке).</p>
 *
 * <p>Страница открыта без входа (см. `shell.tsx`, `isPublic`): при первом входе пользователь
 * ещё не вошёл. После смены временного пароля вход выполняется сразу новым паролем; после
 * смены своего пароля все сессии гасятся сервером, поэтому — выход и вход заново.</p>
 */
export default function PasswordPage() {
  const { t } = useT();
  const brand = useBrand();
  const { changePassword, authenticated, logout, roles } = useAuth();
  const { homeFor } = useRoleAccess();
  const router = useRouter();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    if (next !== repeat) {
      setError(t('pwd.err.mismatch'));
      return;
    }
    setBusy(true);
    try {
      const wasSignedIn = authenticated;
      const signedIn = await changePassword(next, wasSignedIn ? current : undefined);
      setDone(true);
      if (wasSignedIn) {
        // Сервер погасил все сессии пользователя — входим заново уже новым паролем.
        setTimeout(() => logout(), 1800);
      } else if (signedIn) {
        // Первый вход: пароль задан и вход выполнен — страница входа сама уведёт в кабинет.
        setTimeout(() => router.replace('/login'), 1200);
      } else {
        setTimeout(() => router.replace('/login'), 1800);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 16, background: 'var(--bg, #f4f7fb)' }}>
      <main style={{ width: '100%', maxWidth: 440 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
          <span className="mark" style={{ width: 34, height: 34, display: 'grid', placeItems: 'center' }}><BrandLogo /></span>
          <span style={{ fontWeight: 700 }}>{brand.name}</span>
        </div>
        <h1 style={{ margin: '0 0 6px' }}>{t('pwd.h')}</h1>
        <p style={{ color: 'var(--muted)', marginTop: 0 }}>{authenticated ? t('pwd.intro.own') : t('pwd.intro.first')}</p>

        {done ? (
          <div className="card" style={{ borderColor: 'var(--green)', background: 'var(--green-050)' }}>
            {authenticated ? t('pwd.ok.relogin') : t('pwd.ok')}
          </div>
        ) : (
          <form className="card" onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {error && <div className="error">{error}</div>}
            {authenticated && (
              <>
                <label htmlFor="pwd-current">{t('pwd.f.current')}</label>
                <input id="pwd-current" name="current" type="password" autoComplete="current-password" required value={current} onChange={e => setCurrent(e.target.value)} />
              </>
            )}
            <label htmlFor="pwd-new">{t('pwd.f.new')}</label>
            <input id="pwd-new" name="new" type="password" autoComplete="new-password" required minLength={12} value={next} onChange={e => setNext(e.target.value)} />
            <label htmlFor="pwd-repeat">{t('pwd.f.repeat')}</label>
            <input id="pwd-repeat" name="repeat" type="password" autoComplete="new-password" required minLength={12} value={repeat} onChange={e => setRepeat(e.target.value)} />
            <div style={{ fontSize: 12, color: 'var(--muted)', margin: '6px 0 10px' }}>{t('pwd.policy')}</div>
            <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
              <button className="btn" type="submit" disabled={busy}>
                <Icon d={P.shield} cls="" /> {busy ? t('pwd.busy') : t('pwd.submit')}
              </button>
              <button type="button" className="btn secondary"
                onClick={() => router.replace(authenticated ? homeFor(roles) : '/login')}>
                {t('pwd.back')}
              </button>
            </div>
          </form>
        )}
      </main>
    </div>
  );
}
