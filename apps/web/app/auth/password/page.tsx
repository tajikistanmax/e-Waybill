'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/**
 * Смена пароля — страница платформы.
 *
 * <p>Сюда попадает пользователь с временным паролем при первом входе (раньше его уводило на
 * страницу Keycloak по чужому адресу — оттуда он не возвращался, находка владельца 23.09.2026),
 * а также любой вошедший, если хочет сменить свой пароль.</p>
 */
export default function PasswordPage() {
  const { t } = useT();
  const { changePassword, authenticated } = useAuth();
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
      await changePassword(next, authenticated ? current : undefined);
      setDone(true);
      setTimeout(() => router.replace('/login'), 1800);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page" style={{ maxWidth: 460 }}>
      <h1>{t('pwd.h')}</h1>
      <p style={{ color: 'var(--muted)' }}>{authenticated ? t('pwd.intro.own') : t('pwd.intro.first')}</p>

      {done ? (
        <div className="card" style={{ borderColor: 'var(--green)', background: 'var(--green-050)' }}>
          {t('pwd.ok')}
        </div>
      ) : (
        <form className="card" onSubmit={submit}>
          {error && <div className="error">{error}</div>}
          {authenticated && (
            <>
              <label>{t('pwd.f.current')}</label>
              <input type="password" required value={current} onChange={e => setCurrent(e.target.value)} />
            </>
          )}
          <label>{t('pwd.f.new')}</label>
          <input type="password" required minLength={12} value={next} onChange={e => setNext(e.target.value)} />
          <label>{t('pwd.f.repeat')}</label>
          <input type="password" required minLength={12} value={repeat} onChange={e => setRepeat(e.target.value)} />
          <div style={{ fontSize: 12, color: 'var(--muted)', margin: '6px 0 10px' }}>{t('pwd.policy')}</div>
          <button className="btn" type="submit" disabled={busy}>
            <Icon d={P.shield} cls="" /> {busy ? t('pwd.busy') : t('pwd.submit')}
          </button>
        </form>
      )}
    </main>
  );
}
