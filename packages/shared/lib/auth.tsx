'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { setAuthToken, md, AUTH_EXPIRED_EVENT } from './api';

// Аутентификация платформы (с 23.09.2026, вместо Keycloak): вход, обновление сессии, выход
// и смена пароля — свои точки в master-data. Внешнего сервера входа больше нет, поэтому
// браузер никуда не уходит с адреса платформы.
const AUTH_BASE = '/md-api/api/v1/auth';
const TOKEN_URL = `${AUTH_BASE}/token`;
const REFRESH_URL = `${AUTH_BASE}/refresh`;
const LOGOUT_URL = `${AUTH_BASE}/logout`;
const PASSWORD_URL = `${AUTH_BASE}/password`;
const SECOND_FACTOR_URL = `${AUTH_BASE}/second-factor`;
const RT_KEY = 'dts_rt';
/**
 * Незавершённый вход со вторым фактором: ключ второго шага (живёт 15 минут на сервере) и,
 * при первой настройке, секрет для QR-кода. Только sessionStorage — закрытие вкладки его стирает.
 */
const MFA_KEY = 'dts_mfa';
/** Одноразовый ключ смены временного пароля — живёт только до перехода на страницу смены. */
const CHANGE_KEY = 'dts_pwd_change';
/** Логин и выбор «Запомнить меня» того, кто меняет временный пароль (пароль НЕ хранится). */
const CHANGE_USER_KEY = 'dts_pwd_change_user';

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
  /**
   * Смена пароля: по одноразовому ключу (первый вход) либо своего, уже войдя в систему.
   * true — после смены временного пароля вход выполнен автоматически.
   */
  changePassword: (newPassword: string, currentPassword?: string) => Promise<boolean>;
  /** Второй шаг входа: код из приложения-аутентификатора. */
  verifySecondFactor: (code: string) => Promise<void>;
};

/** Временный пароль: вход не даётся, интерфейс ведёт на страницу смены пароля. */
export class PasswordChangeRequired extends Error {
  constructor(public readonly changeToken: string) {
    super('Требуется смена пароля');
    this.name = 'PasswordChangeRequired';
  }
}

/** Незавершённый вход: пароль верен, нужен код второго фактора. */
export type SecondFactorPending = {
  challengeToken: string;
  /** Не пусто — второй фактор ещё не подключён: показать QR-код и секрет для ручного ввода. */
  secret?: string;
  otpauthUri?: string;
  remember: boolean;
};

/** Пароль верен, но вход завершится только после кода из приложения-аутентификатора. */
export class SecondFactorRequired extends Error {
  constructor(public readonly pending: SecondFactorPending) {
    super('Требуется код второго фактора');
    this.name = 'SecondFactorRequired';
  }
}

/** Незавершённый вход со вторым фактором (например, после смены временного пароля). */
export function pendingSecondFactor(): SecondFactorPending | null {
  try {
    const raw = sessionStorage.getItem(MFA_KEY);
    return raw ? JSON.parse(raw) as SecondFactorPending : null;
  } catch { return null; }
}

/** Отменить незавершённый вход (кнопка «Назад» на шаге кода). */
export function cancelSecondFactor() {
  try { sessionStorage.removeItem(MFA_KEY); } catch { /* приватный режим */ }
}

const AuthContext = createContext<AuthState>({
  ready: false, authenticated: false, username: '', roles: [],
  login: async () => {}, logout: () => {}, changePassword: async () => false,
  verifySecondFactor: async () => {},
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
  const [state, setState] = useState<Omit<AuthState, 'login' | 'logout' | 'changePassword' | 'verifySecondFactor'>>({
    ready: false, authenticated: false, username: '', roles: [],
  });
  const timer = useRef<number | undefined>(undefined);
  /** Момент истечения текущего токена доступа (мс), 0 — токена нет. */
  const expiresAt = useRef(0);
  /** Идущее обновление: второй одновременный вызов ждёт его, а не шлёт тот же RT повторно. */
  const inflight = useRef<Promise<void> | null>(null);

  const applyToken = useCallback((data: { access_token: string; refresh_token: string; expires_in: number }) => {
    setAuthToken(data.access_token);
    currentAccessToken = data.access_token;
    persistRt(data.refresh_token);
    expiresAt.current = Date.now() + data.expires_in * 1000;
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

  const doRefresh = useCallback(async () => {
    const post = (token: string) => fetch(REFRESH_URL, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: token }),
      // Не зависать на недоступной службе: иначе ready не станет true и boot-экран держится вечно.
      signal: AbortSignal.timeout(8000),
    });
    const rt = readRt();
    if (!rt) { setState(s => ({ ...s, ready: true, authenticated: false })); return; }
    try {
      let res = await post(rt);
      // Токен обновления одноразовый. Если в соседней вкладке его только что обменяли,
      // в хранилище уже лежит новый — пробуем им, а не выходим из системы во всех вкладках.
      if (res.status === 401) {
        const latest = readRt();
        if (latest && latest !== rt) res = await post(latest);
      }
      if (!res.ok) throw new Error('expired');
      applyToken(await res.json());
    } catch {
      clearRt();
      setAuthToken('');
      currentAccessToken = '';
      expiresAt.current = 0;
      setState(s => ({ ...s, ready: true, authenticated: false }));
    }
  }, [applyToken]);

  const lastRefresh = useRef(0);
  const refresh = useCallback(() => {
    if (!inflight.current) {
      lastRefresh.current = Date.now();
      inflight.current = doRefresh().finally(() => { inflight.current = null; });
    }
    return inflight.current;
  }, [doRefresh]);

  // Страховка от «уснувшего» таймера: браузер придерживает таймеры фоновых вкладок и
  // останавливает их на время сна ноутбука. Поэтому срок токена проверяется ещё и
  // периодически, при возврате на вкладку и по сигналу API «401» — сессия не падает
  // через полчаса работы, если вкладка была свёрнута.
  useEffect(() => {
    const check = () => {
      if (expiresAt.current && Date.now() > expiresAt.current - 90_000) void refresh();
    };
    const onVisible = () => { if (document.visibilityState === 'visible') check(); };
    // 401 от API: токен уже не принят (истёк во сне). Не чаще раза в 15 с — чтобы серия
    // отказов одной страницы не превращалась в серию обменов токена.
    const onExpired = () => {
      if (expiresAt.current && Date.now() - lastRefresh.current > 15_000) void refresh();
    };
    const iv = window.setInterval(check, 20_000);
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', check);
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired);
    return () => {
      window.clearInterval(iv);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', check);
      window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired);
    };
  }, [refresh]);

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
      const body = await res.json().catch(() => ({} as Record<string, string>));
      // Второй фактор: пароль верен, вход завершит код из приложения-аутентификатора.
      if (body.error === 'second_factor_required' || body.error === 'second_factor_setup') {
        const pending: SecondFactorPending = {
          challengeToken: String(body.challengeToken ?? ''),
          secret: body.secret || undefined,
          otpauthUri: body.otpauthUri || undefined,
          remember,
        };
        try { sessionStorage.setItem(MFA_KEY, JSON.stringify(pending)); } catch { /* приватный режим */ }
        throw new SecondFactorRequired(pending);
      }
      const token = String(body.changeToken ?? '');
      try {
        sessionStorage.setItem(CHANGE_KEY, token);
        // Логин (не пароль) — чтобы после смены сразу войти новым паролем, без повторного ввода.
        sessionStorage.setItem(CHANGE_USER_KEY, JSON.stringify({ username, remember }));
      } catch { /* приватный режим */ }
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
   * Второй шаг входа. Неверный код — ошибка с текстом сервера, незавершённый вход остаётся
   * (можно ввести код ещё раз); истёкший ключ — ошибка, незавершённый вход снимается.
   */
  const verifySecondFactor = useCallback(async (code: string) => {
    const pending = pendingSecondFactor();
    if (!pending) throw new Error('Время на ввод кода истекло, войдите заново');
    let res: Response;
    try {
      res = await fetch(SECOND_FACTOR_URL, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ challengeToken: pending.challengeToken, code: code.replace(/\s/g, '') }),
        signal: AbortSignal.timeout(12000),
      });
    } catch {
      throw new Error('Сервер аутентификации недоступен. Повторите попытку.');
    }
    if (!res.ok) {
      const body = await res.json().catch(() => ({} as { detail?: string }));
      const message = String(body.detail ?? 'Неверный код');
      // Ключ второго шага истёк или учётка заблокирована — этим ключом больше не войти.
      if (res.status === 429 || /заново/.test(message)) cancelSecondFactor();
      throw new Error(message);
    }
    cancelSecondFactor();
    const data = await res.json();
    chooseRt(data.refresh_token, pending.remember);
    applyToken(data);
  }, [applyToken]);

  /**
   * Смена пароля. При первом входе с временным паролем используется одноразовый ключ,
   * полученный при попытке входа; уже вошедший пользователь подтверждает текущий пароль.
   */
  const changePassword = useCallback(async (newPassword: string, currentPassword?: string) => {
    // Ключ смены — только для первого входа. Вошедший пользователь подтверждает текущий
    // пароль, и завалявшийся в хранилище старый ключ не должен уводить запрос по другой ветке.
    let changeToken: string | null = null;
    if (currentPassword === undefined) {
      try { changeToken = sessionStorage.getItem(CHANGE_KEY); } catch { /* приватный режим */ }
    }
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
    let pending: { username?: string; remember?: boolean } = {};
    try {
      pending = JSON.parse(sessionStorage.getItem(CHANGE_USER_KEY) ?? '{}');
      sessionStorage.removeItem(CHANGE_KEY);
      sessionStorage.removeItem(CHANGE_USER_KEY);
    } catch { /* приватный режим */ }
    // Первый вход: пароль только что задан — входим им сразу, пользователь попадает в свой
    // кабинет. Если не вышло (сеть), страница просто откроет вход.
    if (changeToken && pending.username) {
      try { await login(pending.username, newPassword, pending.remember ?? true); return true; } catch { return false; }
    }
    return false;
  }, [login]);

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
    cancelSecondFactor();
    // Черновик нового путевого листа (waybills/new) несёт персональные данные водителя —
    // на общем компьютере следующий пользователь не должен его увидеть.
    try { localStorage.removeItem('epd:wb-new-draft'); } catch { /* приватный режим */ }
    setAuthToken('');
    currentAccessToken = '';
    setState({ ready: true, authenticated: false, username: '', roles: [] });
    window.location.href = '/login';
  }, []);

  useEffect(() => { void refresh(); return () => window.clearTimeout(timer.current); }, [refresh]);

  // Авто-выход по бездействию (§29, настройка security/idle_logout_minutes). 0 — выключено.
  // Таймер сбрасывается активностью пользователя; по истечении — logout (защита оставленной сессии).
  // Привилегированные роли (ИБ-13.2.5) — не дольше 30 минут бездействия, даже если настройка
  // выключена или больше. Зависимость — флаг, а не массив ролей: массив пересоздаётся при каждом
  // обновлении токена, и таймер бездействия сбрасывался бы без участия пользователя.
  const privileged = state.roles.some(r => PRIVILEGED_ROLES.includes(r));
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
        idleMs = effectiveIdleMs(m, privileged);
        if (idleMs) { events.forEach(e => window.addEventListener(e, reset, { passive: true })); reset(); }
      })
      .catch(() => {
        // Нет доступа/связи к настройкам: у привилегированных ролей предел всё равно действует.
        idleMs = effectiveIdleMs(0, privileged);
        if (idleMs) { events.forEach(e => window.addEventListener(e, reset, { passive: true })); reset(); }
      });
    return () => { window.clearTimeout(idleTimer); events.forEach(e => window.removeEventListener(e, reset)); };
  }, [state.authenticated, privileged, logout]);

  return <AuthContext.Provider value={{ ...state, login, logout, changePassword, verifySecondFactor }}>{children}</AuthContext.Provider>;
}

/** Роли с повышенными правами: второй фактор и обязательный автовыход (ИБ-13.2.2, 13.2.5). */
const PRIVILEGED_ROLES = ['SYSTEM_ADMIN', 'INSPECTOR', 'MINTRANS_ANALYST'];
/** Тайм-аут неактивности привилегированных ролей по ИБ-13.2.5, минут. */
const PRIVILEGED_IDLE_MINUTES = 30;

/**
 * Время бездействия до автовыхода, мс (0 — не выходить): настройка платформы, а у
 * привилегированных ролей — не больше 30 минут, даже если настройка выключена или больше.
 */
export function effectiveIdleMs(settingMinutes: number, privileged: boolean): number {
  const m = Number.isFinite(settingMinutes) && settingMinutes > 0 ? settingMinutes : 0;
  const minutes = privileged ? (m > 0 ? Math.min(m, PRIVILEGED_IDLE_MINUTES) : PRIVILEGED_IDLE_MINUTES) : m;
  return minutes * 60_000;
}

/** Заголовок с токеном, если пользователь уже вошёл (для смены своего пароля). */
function authHeaderIfAny(): Record<string, string> {
  return currentAccessToken ? { Authorization: `Bearer ${currentAccessToken}` } : {};
}
