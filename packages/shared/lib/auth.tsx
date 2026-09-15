'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { setAuthToken, md } from './api';
import { randomToken, pkceChallenge } from './pkce';

// 127.0.0.1 (не localhost): на Windows браузер/Node резолвят localhost в IPv6 ::1 через
// happy-eyeballs, а Keycloak (docker) слушает IPv4 → запрос к localhost:8180 из браузера
// ВИСНЕТ и boot-экран «Загрузка системы…» держится вечно. Та же причина, что в next.config.
const KC = process.env.NEXT_PUBLIC_KEYCLOAK_URL || 'http://127.0.0.1:8180';
const TOKEN_URL = `${KC}/realms/epd/protocol/openid-connect/token`;
const LOGOUT_URL = `${KC}/realms/epd/protocol/openid-connect/logout`;
const AUTH_URL = `${KC}/realms/epd/protocol/openid-connect/auth`;
// Keycloak-клиент приложения. По умолчанию epd-web (монолит / профиль 'all'). Профильные
// сборки задают свой: epd-waybill (кабинет перевозчика) / epd-oversight (платформа надзора)
// через build-arg NEXT_PUBLIC_KC_CLIENT_ID.
const CLIENT_ID = process.env.NEXT_PUBLIC_KC_CLIENT_ID || 'epd-web';
const RT_KEY = 'dts_rt';
// Authorization Code + PKCE — резервный путь входа для ролей с обязательной 2FA
// (SYSTEM_ADMIN/MINTRANS_ANALYST/INSPECTOR, ИБ-13.2.2): grant_type=password не может
// провести пользователя через экран настройки/ввода OTP, а редирект на Keycloak — может.
const PKCE_VERIFIER_KEY = 'dts_pkce_verifier';
const PKCE_STATE_KEY = 'dts_pkce_state';
const PKCE_REMEMBER_KEY = 'dts_pkce_remember';
const PKCE_RETURN_KEY = 'dts_pkce_return';
function callbackUrl() { return `${window.location.origin}/auth/callback`; }

// «Запомнить меня»: при отметке refresh-token живёт в localStorage (переживает закрытие
// браузера), иначе — в sessionStorage (стирается при закрытии вкладки). Чтение — из обоих.
function readRt(): string | null {
  try { return localStorage.getItem(RT_KEY) ?? sessionStorage.getItem(RT_KEY); } catch { return null; }
}
function chooseRt(token: string, remember: boolean) {
  try {
    if (remember) { localStorage.setItem(RT_KEY, token); sessionStorage.removeItem(RT_KEY); }
    else { sessionStorage.setItem(RT_KEY, token); localStorage.removeItem(RT_KEY); }
  } catch { /* ignore */ }
}
/** Сохранить обновлённый (ротированный) RT в то же хранилище, где уже живёт сессия. */
function persistRt(token: string) {
  try {
    if (localStorage.getItem(RT_KEY) != null) localStorage.setItem(RT_KEY, token);
    else sessionStorage.setItem(RT_KEY, token);
  } catch { /* ignore */ }
}
function clearRt() {
  try { localStorage.removeItem(RT_KEY); sessionStorage.removeItem(RT_KEY); } catch { /* ignore */ }
}

type AuthState = {
  ready: boolean;
  authenticated: boolean;
  username: string;
  roles: string[];
  login: (username: string, password: string, remember?: boolean) => Promise<void>;
  logout: () => void;
  completeLoginRedirect: (code: string, state: string) => Promise<string>;
};

const AuthContext = createContext<AuthState>({
  ready: false, authenticated: false, username: '', roles: [],
  login: async () => {}, logout: () => {},
  completeLoginRedirect: async () => '/',
});

export const useAuth = () => useContext(AuthContext);

function decode(token: string): Record<string, unknown> {
  try {
    const p = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(decodeURIComponent(Array.prototype.map.call(atob(p),
      (c: string) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2)).join('')));
  } catch { return {}; }
}

/**
 * Прямая аутентификация в Keycloak (grant_type=password, клиент epd-web) —
 * позволяет использовать собственную страницу входа /login вместо экрана Keycloak.
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<Omit<AuthState, 'login' | 'logout' | 'completeLoginRedirect'>>({
    ready: false, authenticated: false, username: '', roles: [],
  });
  const timer = useRef<number | undefined>(undefined);

  const applyToken = useCallback((data: { access_token: string; refresh_token: string; expires_in: number }) => {
    setAuthToken(data.access_token);
    persistRt(data.refresh_token);
    const claims = decode(data.access_token);
    setState({
      ready: true, authenticated: true,
      username: String(claims.preferred_username ?? claims.email ?? ''),
      roles: ((claims.realm_access as { roles?: string[] })?.roles) ?? [],
    });
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => { void refresh(); }, Math.max(30, data.expires_in - 45) * 1000);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refresh = useCallback(async () => {
    const rt = readRt();
    if (!rt) { setState(s => ({ ...s, ready: true, authenticated: false })); return; }
    try {
      const res = await fetch(TOKEN_URL, {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ client_id: CLIENT_ID, grant_type: 'refresh_token', refresh_token: rt }),
        // Не зависать на недоступном Keycloak: иначе ready не станет true и boot-экран держится вечно.
        signal: AbortSignal.timeout(8000),
      });
      if (!res.ok) throw new Error('expired');
      applyToken(await res.json());
    } catch {
      clearRt();
      setAuthToken('');
      setState(s => ({ ...s, ready: true, authenticated: false }));
    }
  }, [applyToken]);

  // Резервный путь для ролей с обязательной 2FA: полный редирект на Keycloak
  // (Authorization Code + PKCE) — там (и только там) Keycloak покажет экран
  // настройки/ввода OTP. Возврата из этого вызова не происходит (страница уходит).
  const loginRedirect = useCallback(async (usernameHint: string, remember: boolean) => {
    const verifier = randomToken();
    const challenge = await pkceChallenge(verifier);
    const state = randomToken();
    sessionStorage.setItem(PKCE_VERIFIER_KEY, verifier);
    sessionStorage.setItem(PKCE_STATE_KEY, state);
    sessionStorage.setItem(PKCE_REMEMBER_KEY, remember ? '1' : '0');
    sessionStorage.setItem(PKCE_RETURN_KEY, window.location.pathname);
    const params = new URLSearchParams({
      client_id: CLIENT_ID, response_type: 'code', scope: 'openid',
      redirect_uri: callbackUrl(), code_challenge: challenge, code_challenge_method: 'S256', state,
    });
    if (usernameHint) params.set('login_hint', usernameHint);
    window.location.href = `${AUTH_URL}?${params.toString()}`;
  }, []);

  // Завершение Authorization Code + PKCE после возврата с /auth/callback?code=...&state=...
  // Возвращает путь, на который вызвавшая страница должна сделать редирект.
  const completeLoginRedirect = useCallback(async (code: string, state: string) => {
    const expectedState = sessionStorage.getItem(PKCE_STATE_KEY);
    const verifier = sessionStorage.getItem(PKCE_VERIFIER_KEY);
    const remember = sessionStorage.getItem(PKCE_REMEMBER_KEY) === '1';
    const returnTo = sessionStorage.getItem(PKCE_RETURN_KEY) || '/';
    sessionStorage.removeItem(PKCE_STATE_KEY);
    sessionStorage.removeItem(PKCE_VERIFIER_KEY);
    sessionStorage.removeItem(PKCE_REMEMBER_KEY);
    sessionStorage.removeItem(PKCE_RETURN_KEY);
    if (!verifier || !state || state !== expectedState) {
      throw new Error('Сессия входа истекла или недействительна. Повторите попытку.');
    }
    const res = await fetch(TOKEN_URL, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: CLIENT_ID, grant_type: 'authorization_code', code,
        redirect_uri: callbackUrl(), code_verifier: verifier,
      }),
      signal: AbortSignal.timeout(12000),
    });
    if (!res.ok) throw new Error('Не удалось завершить вход.');
    const data = await res.json();
    chooseRt(data.refresh_token, remember);
    applyToken(data);
    return returnTo;
  }, [applyToken]);

  const login = useCallback(async (username: string, password: string, remember = true) => {
    let res: Response;
    try {
      res = await fetch(TOKEN_URL, {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ client_id: CLIENT_ID, grant_type: 'password', scope: 'openid', username, password }),
        signal: AbortSignal.timeout(12000),
      });
    } catch {
      throw new Error('Сервер аутентификации недоступен. Повторите попытку.');
    }
    if (!res.ok) {
      // "Account is not fully set up" — у учётки есть незавершённое требуемое действие
      // (в первую очередь CONFIGURE_TOTP, обязательная 2FA привилегированных ролей,
      // ИБ-13.2.2). grant_type=password провести через это не может — переходим на
      // Authorization Code + PKCE, где Keycloak сам покажет нужный экран.
      let errBody: { error?: string; error_description?: string } = {};
      try { errBody = await res.json(); } catch { /* ignore */ }
      if (res.status === 400 && errBody.error === 'invalid_grant'
          && /not fully set up/i.test(errBody.error_description ?? '')) {
        await loginRedirect(username, remember);
        return; // страница уходит на Keycloak — сюда управление не вернётся
      }
      throw new Error('Неверный логин или пароль');
    }
    const data = await res.json();
    chooseRt(data.refresh_token, remember); // разместить RT по выбору «Запомнить меня»
    applyToken(data);
  }, [applyToken, loginRedirect]);

  const logout = useCallback(() => {
    window.clearTimeout(timer.current);
    const rt = readRt();
    // Серверный отзыв сессии/refresh-token (RP-initiated logout) — иначе украденный RT
    // оставался бы действительным до истечения. Best-effort, редирект не блокируем.
    if (rt) {
      try {
        fetch(LOGOUT_URL, {
          method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ client_id: CLIENT_ID, refresh_token: rt }), keepalive: true,
        }).catch(() => { /* ignore */ });
      } catch { /* ignore */ }
    }
    clearRt();
    setAuthToken('');
    setState({ ready: true, authenticated: false, username: '', roles: [] });
    window.location.href = '/login';
  }, []);

  useEffect(() => { void refresh(); return () => window.clearTimeout(timer.current); }, [refresh]);

  // Авто-выход по бездействию (§29, настройка security/idle_logout_minutes). 0 — выключено.
  // Таймер сбрасывается активностью пользователя; по истечении — logout (защита оставленной сессии).
  useEffect(() => {
    if (!state.authenticated) return;
    let idleMs = 0;
    let idleTimer: number | undefined;
    const events = ['mousemove', 'keydown', 'click', 'scroll', 'touchstart'];
    const reset = () => {
      if (!idleMs) return;
      window.clearTimeout(idleTimer);
      idleTimer = window.setTimeout(() => logout(), idleMs);
    };
    md.settings('security')
      .then(rows => {
        const m = Number(rows.find(r => r.settingKey === 'idle_logout_minutes')?.settingValue ?? '0');
        idleMs = Number.isFinite(m) && m > 0 ? m * 60_000 : 0;
        if (idleMs) { events.forEach(e => window.addEventListener(e, reset, { passive: true })); reset(); }
      })
      .catch(() => { /* нет доступа/связи — авто-выход просто не активируется */ });
    return () => { window.clearTimeout(idleTimer); events.forEach(e => window.removeEventListener(e, reset)); };
  }, [state.authenticated, logout]);

  return <AuthContext.Provider value={{ ...state, login, logout, completeLoginRedirect }}>{children}</AuthContext.Provider>;
}
