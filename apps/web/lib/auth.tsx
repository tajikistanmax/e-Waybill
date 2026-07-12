'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { setAuthToken, md } from '@/lib/api';

const KC = process.env.NEXT_PUBLIC_KEYCLOAK_URL || 'http://localhost:8180';
const TOKEN_URL = `${KC}/realms/epd/protocol/openid-connect/token`;
const LOGOUT_URL = `${KC}/realms/epd/protocol/openid-connect/logout`;
const RT_KEY = 'dts_rt';

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
};

const AuthContext = createContext<AuthState>({
  ready: false, authenticated: false, username: '', roles: [],
  login: async () => {}, logout: () => {},
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
  const [state, setState] = useState<Omit<AuthState, 'login' | 'logout'>>({
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
        body: new URLSearchParams({ client_id: 'epd-web', grant_type: 'refresh_token', refresh_token: rt }),
      });
      if (!res.ok) throw new Error('expired');
      applyToken(await res.json());
    } catch {
      clearRt();
      setAuthToken('');
      setState(s => ({ ...s, ready: true, authenticated: false }));
    }
  }, [applyToken]);

  const login = useCallback(async (username: string, password: string, remember = true) => {
    const res = await fetch(TOKEN_URL, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ client_id: 'epd-web', grant_type: 'password', scope: 'openid', username, password }),
    });
    if (!res.ok) throw new Error('Неверный логин или пароль');
    const data = await res.json();
    chooseRt(data.refresh_token, remember); // разместить RT по выбору «Запомнить меня»
    applyToken(data);
  }, [applyToken]);

  const logout = useCallback(() => {
    window.clearTimeout(timer.current);
    const rt = readRt();
    // Серверный отзыв сессии/refresh-token (RP-initiated logout) — иначе украденный RT
    // оставался бы действительным до истечения. Best-effort, редирект не блокируем.
    if (rt) {
      try {
        fetch(LOGOUT_URL, {
          method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ client_id: 'epd-web', refresh_token: rt }), keepalive: true,
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

  return <AuthContext.Provider value={{ ...state, login, logout }}>{children}</AuthContext.Provider>;
}
