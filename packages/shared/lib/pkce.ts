// PKCE (RFC 7636) для Authorization Code flow Keycloak. Нужен, чтобы приватилегированные
// роли (2FA обязательна, ИБ-13.2.2) могли пройти OTP-экран Keycloak — прямой grant_type=password
// его не поддерживает (см. lib/auth.tsx).

function base64url(bytes: Uint8Array): string {
  let str = '';
  bytes.forEach(b => { str += String.fromCharCode(b); });
  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64url(bytes);
}

export async function pkceChallenge(verifier: string): Promise<string> {
  const data = new TextEncoder().encode(verifier);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return base64url(new Uint8Array(digest));
}
