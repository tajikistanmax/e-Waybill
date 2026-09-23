'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { setAuthToken, md } from './api';

// Аутентификация платформы (с 23.09.2026, вместо Keycloak): вход, обновление сессии, выход
// и смена пароля — свои точки в master-data. Внешнего сервера входа больше нет, поэтому
// браузер никуда не уходит с адреса платформы.
const AUTH_BASE = '/md-api/api/v1/auth';
const TOKEN_URL = `${AUTH_BASE}/token`;
const REFRESH_URL = `${AUTH_BASE}/refresh`;
const LOGOUT_URL = `${AUTH_BASE}/logout`;
const PASSWORD_URL = `${AUTH_BASE}/password`;
const RT_KEY = 'dts_rt';
/** Одноразовый ключ смены временного пароля — живёт только до перехода на страницу смены. */
const CHANGE_KEY = 'dts_pwd_change';

/** Текущий токен доступа — нужен, чтобы вошедший пользователь мог сменить свой пароль. */
let currentAccessToken = '';

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
  /** Смена пароля: по одноразовому ключу (первый вход) либо своего, уже войдя в систему. */
  changePassword: (newPassword: string, currentPassword?: string) => Promise<void>;
};

/** Временный пароль: вход не даётся, интерфейс ведёт на страницу смены пароля. */
export class PasswordChangeRequired extends Error {
  constructor(public readonly changeToken: string) {
    super('Требуется смена пароля');
    this.name = 'PasswordChangeRequired';
  }
}

const AuthContext = createContext<AuthState>({
  ready: false, authenticated: false, username: '', roles: [],
  login: async () => {}, logout: () => {}, changePassword: async () => {},
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
 * Аутентификация платформы: логин и пароль проверяет master-data, он же выпускает токен.
 * Браузер всё время остаётся на адресе платформы — отдельной страницы входа на чужом
 * сервере больше нет (отказ от Keycloak, 23.09.2026).
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<Omit<AuthState, 'login' | 'logout' | 'changePassword'>>({
    ready: false, authenticated: false, username: '', roles: [],
  });
  const timer = useRef<number | undefined>(undefined);

  const applyToken = useCallback((data: { access_token: string; refresh_token: string; expires_in: number }) => {
    setAuthToken(data.access_token);
    currentAccessToken = data.access_token;
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
      const res = await fetch(REFRESH_URL, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: rt }),
        // Не зависать на недоступной службе: иначе ready не станет true и boot-экран держится вечно.
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

  const login = useCallback(async (username: string, password: string, remember = true) => {
    let res: Response;
    try {
      res = await fetch(TOKEN_URL, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
        signal: AbortSignal.timeout(12000),
      });
    } catch {
      throw new Error('Сервер аутентификации недоступен. Повторите попытку.');
    }
    // Временный пароль: вход не даётся, но выдан одноразовый ключ — страница входа
    // отправляет на смену пароля. Раньше здесь был переход на страницу Keycloak.
    if (res.status === 428) {
      const body = await res.json().catch(() => ({} as { changeToken?: string }));
      const token = String(body.changeToken ?? '');
      try { sessionStorage.setItem(CHANGE_KEY, token); } catch { /* приватный режим */ }
      throw new PasswordChangeRequired(token);
    }
    if (!res.ok) {
      const body = await res.json().catch(() => ({} as { detail?: string }));
      throw new Error(String(body.detail ?? 'Неверный логин или пароль'));
    }
    const data = await res.json();
    chooseRt(data.refresh_token, remember); // разместить RT по выбору «Запомнить меня»
    applyToken(data);
  }, [applyToken]);

  /**
   * Смена пароля. При первом входе с временным паролем используется одноразовый ключ,
   * полученный при попытке входа; уже вошедший пользователь подтверждает текущий пароль.
   */
  const changePassword = useCallback(async (newPassword: string, currentPassword?: string) => {
    let changeToken: string | null = null;
    try { changeToken = sessionStorage.getItem(CHANGE_KEY); } catch { /* приватный режим */ }
    const res = await fetch(PASSWORD_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(authHeaderIfAny()) },
      body: JSON.stringify({ changeToken, currentPassword, newPassword }),
      signal: AbortSignal.timeout(12000),
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({} as { detail?: string }));
      throw new Error(String(body.detail ?? 'Не удалось сменить пароль'));
    }
    try { sessionStorage.removeItem(CHANGE_KEY); } catch { /* приватный режим */ }
  }, []);

  const logout = useCallback(() => {
    window.clearTimeout(timer.current);
    const rt = readRt();
    // Серверный отзыв сессии/refresh-token (RP-initiated logout) — иначе украденный RT
    // оставался бы действительным до истечения. Best-effort, редирект не блокируем.
    if (rt) {
      try {
        fetch(LOGOUT_URL, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: rt }), keepalive: true,
        }).catch(() => { /* ignore */ });
      } catch { /* ignore */ }
    }
    clearRt();
    setAuthToken('');
    currentAccessToken = '';
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

  return <AuthContext.Provider value={{ ...state, login, logout, changePassword }}>{children}</AuthContext.Provider>;
}

/** Заголовок с токеном, если пользователь уже вошёл (для смены своего пароля). */
function authHeaderIfAny(): Record<string, string> {
  return currentAccessToken ? { Authorization: `Bearer ${currentAccessToken}` } : {};
}
