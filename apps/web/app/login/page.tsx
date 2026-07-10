'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Icon, P } from '../icons';
import { useAuth } from '@/lib/auth';

export default function LoginPage() {
  const { login, authenticated, ready } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => { if (ready && authenticated) router.replace('/dashboard'); }, [ready, authenticated, router]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setBusy(true);
    try {
      await login(username.trim(), password);
      router.replace('/dashboard');
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      {/* Левая колонка — форма */}
      <div className="login-left">
        <div className="login-brand">
          <span className="mark"><Icon d={P.docActive} cls="" /></span>
          <span className="lt">DTS</span>
        </div>

        <h1>Электронный<br />путевой лист</h1>
        <div className="sub">Цифровое управление транспортом и путевыми листами</div>
        <div className="rule" />

        <form className="login-form" onSubmit={submit}>
          {error && <div className="error">{error}</div>}
          <label>Телефон или ИНН</label>
          <div className="field">
            <Icon d={P.user} cls="fic" />
            <input value={username} onChange={e => setUsername(e.target.value)} placeholder="Введите телефон или ИНН" required autoFocus />
          </div>

          <label>Пароль</label>
          <div className="field">
            <Icon d={P.shield} cls="fic" />
            <input type={show ? 'text' : 'password'} value={password} onChange={e => setPassword(e.target.value)} placeholder="Введите пароль" required />
            <button type="button" className="eye" onClick={() => setShow(s => !s)} aria-label="Показать пароль">
              <Icon d={show ? P.eyeOff : P.eye} cls="" />
            </button>
          </div>

          <div className="login-row">
            <label><input type="checkbox" defaultChecked /> Запомнить меня</label>
            <a href="#">Забыли пароль?</a>
          </div>

          <button className="btn btn-login" type="submit" disabled={busy}>
            <Icon d={P.login} cls="" /> {busy ? 'Вход…' : 'Войти'}
          </button>

          <div className="login-or">или</div>
          <button type="button" className="btn-sso" onClick={submit as unknown as () => void} disabled>
            <span className="mark" style={{ width: 22, height: 22, borderRadius: 6, background: 'linear-gradient(135deg,var(--blue-500),var(--blue-700))', display: 'grid', placeItems: 'center', color: '#fff' }}>
              <Icon d={P.shield} cls="" />
            </span>
            Войти через DTS SSO
          </button>

          <div className="login-secure">
            <Icon d={P.shield} cls="" />
            Ваши данные защищены в соответствии с требованиями безопасности Республики Таджикистан
          </div>
        </form>

        <div className="login-foot">© 2025 ГУП «Маркази рақамикунонии соҳаи нақлиёт» · Министерство транспорта Республики Таджикистан</div>
      </div>

      {/* Правая колонка — синяя панель с карточками */}
      <div className="login-right">
        <div className="map-dots" />
        <div className="glow" />
        <div className="lang">
          <span><Icon d={P.globe} cls="" style={{ width: 16, height: 16, verticalAlign: -3 }} /></span>
          <span><b>RU</b></span><span>TJ</span><span>EN</span>
          <span>· Поддержка</span>
        </div>

        <div className="float-card float-panel">
          <div className="fp-head"><Icon d={P.chart} cls="" style={{ width: 16, height: 16, color: 'var(--blue-600)' }} /> Панель управления</div>
          <div className="fp-mini">
            <div className="m"><div className="ml">Транспорт</div><div className="mv">128</div></div>
            <div className="m"><div className="ml">Путевые листы</div><div className="mv">3 246</div></div>
            <div className="m"><div className="ml">Активные рейсы</div><div className="mv">86</div></div>
            <div className="m"><div className="ml">Нарушения</div><div className="mv" style={{ color: 'var(--red)' }}>3</div></div>
          </div>
        </div>

        <div className="float-card float-donut">
          <div className="fp-head" style={{ marginBottom: 10 }}>Статус транспорта</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
            <svg width="72" height="72" viewBox="0 0 42 42">
              <circle cx="21" cy="21" r="15.9" fill="none" stroke="#e4e9f0" strokeWidth="6" />
              <circle cx="21" cy="21" r="15.9" fill="none" stroke="#2563eb" strokeWidth="6" strokeDasharray="62 38" strokeDashoffset="25" strokeLinecap="round" />
              <circle cx="21" cy="21" r="15.9" fill="none" stroke="#16a34a" strokeWidth="6" strokeDasharray="24 76" strokeDashoffset="-37" strokeLinecap="round" />
            </svg>
            <div className="donut-legend" style={{ fontSize: 11.5 }}>
              <div className="row"><span className="dot" style={{ background: '#2563eb' }} />На линии<span className="pc">86</span></div>
              <div className="row"><span className="dot" style={{ background: '#16a34a' }} />В рейсе<span className="pc">28</span></div>
              <div className="row"><span className="dot" style={{ background: '#e4e9f0' }} />Стоит<span className="pc">14</span></div>
            </div>
          </div>
        </div>

        <div className="float-card float-list">
          <div className="fp-head" style={{ marginBottom: 10 }}>Путевые листы</div>
          <div className="donut-legend" style={{ fontSize: 12 }}>
            <div className="row"><Icon d={P.check} cls="" style={{ width: 14, height: 14, color: 'var(--green)' }} />Оформлено<span className="pc">2 156</span></div>
            <div className="row"><Icon d={P.check} cls="" style={{ width: 14, height: 14, color: 'var(--blue-600)' }} />Действуют<span className="pc">1 090</span></div>
            <div className="row"><Icon d={P.check} cls="" style={{ width: 14, height: 14, color: 'var(--muted)' }} />Завершено<span className="pc">1 000</span></div>
          </div>
        </div>

        <div className="login-caption">
          <Icon d={P.shield} cls="" />
          Цифровая платформа для эффективного и безопасного управления транспортом
        </div>
      </div>
    </div>
  );
}
