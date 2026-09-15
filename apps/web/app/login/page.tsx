'use client';

import { useEffect, useState, type CSSProperties } from 'react';
import { useRouter } from 'next/navigation';
import { Icon, P } from '../icons';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { md, type PlatformSetting } from '@/lib/api';
import { useBrand, BrandLogo } from '@/lib/brand';
import { useRoleAccess } from '@/lib/roleaccess';
import { appProfile, PROFILE_META } from '@/lib/profile';

// Английский — только для страницы входа (остальная платформа RU/TJ, фолбэк на RU).
const EN: Record<string, string> = {
  'login.h': 'Electronic Waybill',
  'app.subtitle': 'Digital management of transport and waybills',
  'login.user': 'Login',
  'login.user.ph': 'Enter login',
  'login.pass': 'Password',
  'login.pass.ph': 'Enter password',
  'login.remember': 'Remember me',
  'login.forgot': 'Forgot password?',
  'login.forgot.help': 'Your account is unified on the e-transport platform. Password recovery is done there (Ministry single sign-on). If you have trouble — contact support:',
  'login.busy': 'Signing in…',
  'login.submit': 'Sign in',
  'login.or': 'or',
  'login.sso': 'Sign in via e-transport',
  'login.sso.note': 'Single sign-on and password recovery via the Ministry e-transport platform (unified account; integration in progress).',
  'login.secure': 'Your data is protected under the security requirements of the Republic of Tajikistan',
  'login.support': 'Support',
  'login.err': 'Invalid login or password',
  'login.p.h': 'Platform capabilities',
  'nav.waybills': 'Waybills',
  'login.f.transport': 'Vehicle registry',
  'login.f.waybills': 'Electronic waybills',
  'login.f.gps': 'GPS monitoring',
  'login.f.oversight': 'Road oversight',
  'login.p.analytics': 'Reporting & analytics',
  'login.p.precontrol': 'Pre-trip control',
  'login.p.lifecycle': 'Waybill lifecycle',
  'login.p.medexam': 'Medical check (T2)',
  'login.p.techexam': 'Technical check (T3)',
  'login.p.admission': 'Trip admission',
  'login.p.issued': 'Issued',
  'login.p.valid': 'Active',
  'kpi.done': 'Completed',
  'login.caption': 'Digital platform for efficient and secure transport management',
};

const card: CSSProperties = {
  background: 'rgba(255,255,255,.93)', backdropFilter: 'blur(9px)', WebkitBackdropFilter: 'blur(9px)',
  border: '1px solid rgba(255,255,255,.85)', borderRadius: 15, boxShadow: '0 20px 46px -16px rgba(19,46,84,.34)',
};

export default function LoginPage() {
  const { login, authenticated, ready, roles } = useAuth();
  const { t, lang, setLang } = useT();
  const brand = useBrand();
  const { homeFor } = useRoleAccess();
  const router = useRouter();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [remember, setRemember] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [showSupport, setShowSupport] = useState(false);
  const [ssoNote, setSsoNote] = useState(false);
  const [contacts, setContacts] = useState<Record<string, string>>({});

  // Английский для страницы входа; иначе — обычный перевод (RU/TJ).
  const L = (k: string) => (lang === 'en' && EN[k] ? EN[k] : t(k));

  useEffect(() => { if (ready && authenticated) router.replace(homeFor(roles)); }, [ready, authenticated, roles, router, homeFor]);

  // Контакты поддержки — из публичных настроек платформы (§29), не захардкожены.
  useEffect(() => {
    md.publicSettings()
      .then((rows: PlatformSetting[]) => setContacts(Object.fromEntries(
        rows.filter(r => r.category === 'general').map(r => [r.settingKey, r.settingValue ?? '']))))
      .catch(() => { /* нет связи — блок поддержки просто не покажем */ });
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(''); setBusy(true);
    try {
      await login(username.trim(), password, remember);
    } catch (err) {
      // Показываем настоящую причину: auth.login бросает разные сообщения — «Сервер
      // аутентификации недоступен…» (нет связи с Keycloak) vs «Неверный логин или пароль»
      // (401). Раньше здесь всегда выводился login.err, из-за чего недоступность KC
      // выглядела как ошибка пароля.
      setError(err instanceof Error && err.message ? err.message : L('login.err'));
      setBusy(false);
    }
  }

  // Витрина на странице входа — иллюстрация назначения платформы, а НЕ живая статистика.
  // Реальные показатели доступны только после входа (защищённый /dashboard): страница входа
  // публичная, показывать оперативные данные госсистемы анониму нельзя. Поэтому — не числа,
  // а перечень возможностей (не вводит в заблуждение).
  const feature = (label: string, icon: string, color?: string) => (
    <div style={{ background: '#fff', border: '1px solid var(--line-soft)', borderRadius: 11, padding: '11px', display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'flex-start' }}>
      <Icon d={icon} cls="" style={{ width: 18, height: 18, color: color ?? 'var(--blue-600)' }} />
      <div style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--ink)', lineHeight: 1.25 }}>{label}</div>
    </div>
  );
  const capRow = (label: string, icon: string, color: string) => (
    <div className="row"><Icon d={icon} cls="" style={{ width: 13, height: 13, color }} />{label}</div>
  );

  return (
    <div className="login-wrap">
      {/* Левая колонка — форма */}
      <div className="login-left">
        <div className="login-brand">
          <span className="mark"><BrandLogo /></span>
          <span className="lt">{brand.name}</span>
        </div>

        {appProfile() !== 'all' && (
          <div style={{
            display: 'inline-flex', alignItems: 'center', gap: 6, alignSelf: 'flex-start',
            margin: '2px 0 6px', padding: '3px 11px', borderRadius: 999,
            background: 'var(--blue-050)', color: 'var(--blue-700)', fontSize: 12, fontWeight: 700,
          }}>
            {PROFILE_META[appProfile()].title}
          </div>
        )}

        <h1>{L('login.h')}</h1>
        <div className="sub">{L('app.subtitle')}</div>
        <div className="rule" />

        <form className="login-form" onSubmit={submit}>
          {error && <div className="error">{error}</div>}
          <label>{L('login.user')}</label>
          <div className="field">
            <Icon d={P.user} cls="fic" />
            <input value={username} onChange={e => setUsername(e.target.value)} placeholder={L('login.user.ph')} required autoFocus />
          </div>

          <label>{L('login.pass')}</label>
          <div className="field">
            <Icon d={P.shield} cls="fic" />
            <input type={show ? 'text' : 'password'} value={password} onChange={e => setPassword(e.target.value)} placeholder={L('login.pass.ph')} required />
            <button type="button" className="eye" onClick={() => setShow(s => !s)} aria-label={L('login.pass')}>
              <Icon d={show ? P.eyeOff : P.eye} cls="" />
            </button>
          </div>

          <div className="login-row">
            <label><input type="checkbox" checked={remember} onChange={e => setRemember(e.target.checked)} /> {L('login.remember')}</label>
            <button type="button" onClick={() => setShowSupport(s => !s)}
              style={{ background: 'none', border: 'none', padding: 0, cursor: 'pointer', color: 'var(--blue-600)', font: 'inherit' }}>
              {L('login.forgot')}
            </button>
          </div>

          {showSupport && (
            <div className="hint" style={{ marginTop: -4, marginBottom: 4, lineHeight: 1.6 }}>
              {L('login.forgot.help')}
              {(contacts.support_phone || contacts.support_email) && (
                <div style={{ marginTop: 6, fontWeight: 600, color: 'var(--ink)' }}>
                  {contacts.support_phone && <div><Icon d={P.route} cls="" style={{ width: 13, height: 13, verticalAlign: '-2px', marginRight: 6 }} />{contacts.support_phone}</div>}
                  {contacts.support_email && <div><Icon d={P.mail} cls="" style={{ width: 13, height: 13, verticalAlign: '-2px', marginRight: 6 }} />{contacts.support_email}</div>}
                </div>
              )}
            </div>
          )}

          <button className="btn btn-login" type="submit" disabled={busy}>
            <Icon d={P.login} cls="" /> {busy ? L('login.busy') : L('login.submit')}
          </button>

          <div className="login-or">{L('login.or')}</div>
          <button type="button" className="btn-sso" onClick={() => setSsoNote(v => !v)}>
            <span className="mark" style={{ width: 22, height: 22, borderRadius: 6, background: 'linear-gradient(135deg,var(--blue-500),var(--blue-700))', display: 'grid', placeItems: 'center', color: '#fff' }}>
              <Icon d={P.shield} cls="" />
            </span>
            {L('login.sso')}
          </button>
          {ssoNote && <div style={{ fontSize: 11.5, color: 'var(--muted)', textAlign: 'center', marginTop: 6 }}>{L('login.sso.note')}</div>}

          <div className="login-secure">
            <Icon d={P.shield} cls="" />
            {L('login.secure')}
          </div>
        </form>

        <div className="login-foot">© 2025 ГУП «Маркази рақамикунонии соҳаи нақлиёт» · Министерство транспорта Республики Таджикистан</div>
      </div>

      {/* Правая колонка — фон входа. Слои сверху вниз: осветляющий градиент → пользовательское
          изображение (branding/login_bg, если задано админом) → зашитый дефолт /login-bg.png.
          Если пользовательского нет (404), его слой прозрачен и виден дефолт. */}
      <div className="login-right" style={{ background: 'linear-gradient(180deg, rgba(226,238,255,.28), rgba(226,238,255,0) 34%), url(/md-api/api/v1/branding/login_bg) center/cover no-repeat, url(/login-bg.png) center/cover no-repeat' }}>
        <div className="lang">
          <Icon d={P.globe} cls="" style={{ width: 16, height: 16 }} />
          <span className="lang-switch on-blue">
            <button className={lang === 'ru' ? 'on' : ''} onClick={() => setLang('ru')}>RU</button>
            <button className={lang === 'tj' ? 'on' : ''} onClick={() => setLang('tj')}>TJ</button>
            <button className={lang === 'en' ? 'on' : ''} onClick={() => setLang('en')}>EN</button>
          </span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><Icon d={P.help} cls="" style={{ width: 16, height: 16 }} /> {L('login.support')}</span>
        </div>

        {/* Кластер «дашборд» — верхняя правая часть, поверх изображения */}
        <div style={{ position: 'absolute', top: 82, right: 40, width: 'min(560px, 58%)', display: 'flex', flexDirection: 'column', gap: 13 }}>
          {/* Витрина возможностей платформы (не живая статистика — см. комментарий к feature) */}
          <div style={{ ...card, padding: '14px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
              <Icon d={P.chart} cls="" style={{ width: 16, height: 16, color: 'var(--blue-600)' }} />
              <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--ink)' }}>{L('login.p.h')}</span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 9 }}>
              {feature(L('login.f.transport'), P.car)}
              {feature(L('login.f.waybills'), P.doc)}
              {feature(L('login.f.gps'), P.route)}
              {feature(L('login.f.oversight'), P.shield)}
            </div>
          </div>

          {/* Ряд из трёх: аналитика · предрейсовый контроль · жизненный цикл ПЛ (иллюстративно) */}
          <div style={{ display: 'grid', gridTemplateColumns: '1.15fr 1fr 1fr', gap: 12 }}>
            <div style={{ ...card, padding: '12px 13px' }}>
              <div style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--ink)', marginBottom: 8 }}>{L('login.p.analytics')}</div>
              {/* Абстрактная линия «роста» — декоративная иллюстрация, не данные */}
              <svg viewBox="0 0 120 46" width="100%" height="46" preserveAspectRatio="none" aria-hidden="true">
                <polyline points="2,38 20,30 38,33 56,20 74,24 92,10 118,6" fill="none" stroke="#2563eb" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
                {[[2,38],[20,30],[38,33],[56,20],[74,24],[92,10],[118,6]].map(([x, y], i) => <circle key={i} cx={x} cy={y} r="2" fill="#2563eb" />)}
              </svg>
            </div>

            <div style={{ ...card, padding: '12px 13px' }}>
              <div style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--ink)', marginBottom: 8 }}>{L('login.p.precontrol')}</div>
              <div className="donut-legend" style={{ fontSize: 10.5 }}>
                {capRow(L('login.p.medexam'), P.med, 'var(--blue-600)')}
                {capRow(L('login.p.techexam'), P.wrench, 'var(--green)')}
                {capRow(L('login.p.admission'), P.check, 'var(--muted)')}
              </div>
            </div>

            <div style={{ ...card, padding: '12px 13px' }}>
              <div style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--ink)', marginBottom: 8 }}>{L('login.p.lifecycle')}</div>
              <div className="donut-legend" style={{ fontSize: 11 }}>
                {capRow(L('login.p.issued'), P.check, 'var(--green)')}
                {capRow(L('login.p.valid'), P.check, 'var(--blue-600)')}
                {capRow(L('kpi.done'), P.check, 'var(--muted)')}
              </div>
            </div>
          </div>
        </div>

        <div className="login-caption" style={{ left: 'auto', right: 40, width: 'min(420px, 52%)' }}>
          <Icon d={P.shield} cls="" />
          {L('login.caption')}
        </div>
      </div>
    </div>
  );
}
