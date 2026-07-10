'use client';

import Keycloak from 'keycloak-js';
import { createContext, useContext, useEffect, useRef, useState } from 'react';
import { setAuthToken } from '@/lib/api';

type AuthState = {
  ready: boolean;
  username: string;
  roles: string[];
  logout: () => void;
};

const AuthContext = createContext<AuthState>({ ready: false, username: '', roles: [], logout: () => {} });

export function useAuth() {
  return useContext(AuthContext);
}

/** Вход через Keycloak (realm epd, клиент epd-web); токен обновляется автоматически. */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const kcRef = useRef<Keycloak | null>(null);
  const [state, setState] = useState<AuthState>({ ready: false, username: '', roles: [], logout: () => {} });

  useEffect(() => {
    if (kcRef.current) return;
    const kcUrl = process.env.NEXT_PUBLIC_KEYCLOAK_URL || 'http://localhost:8180';
    const kc = new Keycloak({ url: kcUrl, realm: 'epd', clientId: 'epd-web' });
    kcRef.current = kc;
    kc.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false })
      .then(authenticated => {
        if (!authenticated) { kc.login(); return; }
        setAuthToken(kc.token ?? '');
        setState({
          ready: true,
          username: (kc.tokenParsed?.preferred_username as string) ?? '',
          roles: (kc.tokenParsed?.realm_access?.roles as string[]) ?? [],
          logout: () => kc.logout({ redirectUri: window.location.origin }),
        });
        // авто-обновление токена
        window.setInterval(() => {
          kc.updateToken(60).then(refreshed => { if (refreshed) setAuthToken(kc.token ?? ''); }).catch(() => kc.login());
        }, 30_000);
      })
      .catch(() => {
        // Keycloak недоступен — работаем без авторизации (dev-режим)
        setState({ ready: true, username: '(без входа)', roles: [], logout: () => {} });
      });
  }, []);

  if (!state.ready) {
    return <main><p>Вход в систему…</p></main>;
  }
  return <AuthContext.Provider value={state}>{children}</AuthContext.Provider>;
}
